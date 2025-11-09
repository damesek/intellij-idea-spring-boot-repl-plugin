package com.example.springboot.nrepl;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Egyszerűsített nREPL szerver Spring Boot-hoz - tisztán Java implementáció.
 * Ez a komponens egy alapvető nREPL szervert valósít meg Java kód futtatásához.
 * 
 * Használat: Add hozzá ezt az osztályt a Spring Boot projektedhez!
 */
@Component
public class SimpleNreplServer implements CommandLineRunner {

    @Value("${nrepl.port:5557}")
    private int port;

    // Separate toggle for the simple (non-Clojure) nREPL-like server.
    // Disabled by default to avoid port conflicts with the real nREPL server.
    @Value("${nrepl.simple.enabled:false}")
    private boolean enabled;

    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private volatile boolean running = false;

    @Override
    public void run(String... args) throws Exception {
        if (enabled) {
            startServer();
        }
    }

    private void startServer() {
        try {
            serverSocket = new ServerSocket(port);
            executorService = Executors.newCachedThreadPool();
            running = true;

            System.out.println("✅ nREPL server started on port " + port);
            System.out.println("   Java code evaluation enabled (prefix with //!java)");

            // Accept connections in background
            executorService.submit(() -> {
                while (running) {
                    try {
                        Socket client = serverSocket.accept();
                        System.out.println("New nREPL client connected from: " + client.getInetAddress());
                        executorService.submit(new ClientHandler(client));
                    } catch (IOException e) {
                        if (running) {
                            System.err.println("Error accepting connection: " + e.getMessage());
                        }
                    }
                }
            });

        } catch (IOException e) {
            System.err.println("Failed to start nREPL server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @PreDestroy
    public void shutdown() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
                System.out.println("nREPL server stopped");
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        if (executorService != null) {
            executorService.shutdown();
        }
    }

    /**
     * Client handler - kezeli egy kliens kapcsolatát
     */
    private class ClientHandler implements Runnable {
        private final Socket socket;
        private String sessionId;
        
        public ClientHandler(Socket socket) {
            this.socket = socket;
            this.sessionId = UUID.randomUUID().toString();
        }

        @Override
        public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))) {
                
                while (!socket.isClosed()) {
                    Map<String, String> message = readBencodedMessage(in);
                    if (message == null) break;
                    
                    handleMessage(message, out);
                }
                
            } catch (Exception e) {
                System.err.println("Client handler error: " + e.getMessage());
            } finally {
                try {
                    socket.close();
                } catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }

        private void handleMessage(Map<String, String> message, BufferedWriter out) throws IOException {
            String op = message.get("op");
            String id = message.get("id");
            
            if ("clone".equals(op)) {
                // Clone session
                Map<String, String> response = new HashMap<>();
                response.put("id", id);
                response.put("new-session", sessionId);
                response.put("status", "done");
                sendBencodedMessage(response, out);
                
            } else if ("eval".equals(op)) {
                // Evaluate code
                String code = message.get("code");
                
                if (code != null && code.startsWith("//!java")) {
                    evaluateJavaCode(code, id, message.get("session"), out);
                } else {
                    // Ha nem Java kód, küldjünk hibát
                    Map<String, String> response = new HashMap<>();
                    response.put("id", id);
                    response.put("session", message.get("session"));
                    response.put("err", "Only Java code evaluation is supported (prefix with //!java)\n");
                    response.put("status", "done");
                    sendBencodedMessage(response, out);
                }
                
            } else if ("describe".equals(op)) {
                // Describe capabilities
                Map<String, String> response = new HashMap<>();
                response.put("id", id);
                response.put("ops", "clone,eval,describe");
                response.put("status", "done");
                sendBencodedMessage(response, out);
                
            } else {
                // Unknown op
                Map<String, String> response = new HashMap<>();
                response.put("id", id);
                response.put("status", "unknown-op");
                sendBencodedMessage(response, out);
            }
        }

        private void evaluateJavaCode(String code, String id, String session, BufferedWriter out) throws IOException {
            // Remove //!java prefix
            String javaCode = code.substring("//!java".length()).trim();
            
            // Capture stdout
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream oldOut = System.out;
            PrintStream newOut = new PrintStream(baos);
            
            try {
                System.setOut(newOut);
                
                // Evaluate Java code
                JavaCodeEvaluator.EvalResult result = JavaCodeEvaluator.evaluate(javaCode);
                
                // Restore stdout
                System.setOut(oldOut);
                String capturedOutput = baos.toString();
                
                // Send output if any
                if (!capturedOutput.isEmpty()) {
                    Map<String, String> outMsg = new HashMap<>();
                    outMsg.put("id", id);
                    outMsg.put("session", session);
                    outMsg.put("out", capturedOutput);
                    sendBencodedMessage(outMsg, out);
                }
                
                // Send result or error
                if (result.error != null) {
                    Map<String, String> errMsg = new HashMap<>();
                    errMsg.put("id", id);
                    errMsg.put("session", session);
                    errMsg.put("err", result.error);
                    sendBencodedMessage(errMsg, out);
                } else if (result.value != null) {
                    Map<String, String> valueMsg = new HashMap<>();
                    valueMsg.put("id", id);
                    valueMsg.put("session", session);
                    valueMsg.put("value", result.value);
                    sendBencodedMessage(valueMsg, out);
                }
                
                // Send done status
                Map<String, String> doneMsg = new HashMap<>();
                doneMsg.put("id", id);
                doneMsg.put("session", session);
                doneMsg.put("status", "done");
                sendBencodedMessage(doneMsg, out);
                
            } finally {
                System.setOut(oldOut);
            }
        }

        private Map<String, String> readBencodedMessage(BufferedReader in) throws IOException {
            int ch = in.read();
            if (ch != 'd') return null; // Not a dictionary
            
            Map<String, String> result = new HashMap<>();
            
            while (true) {
                ch = in.read();
                if (ch == 'e') break; // End of dictionary
                if (ch == -1) return null;
                
                // Read key length
                StringBuilder lenStr = new StringBuilder();
                lenStr.append((char) ch);
                while ((ch = in.read()) != ':') {
                    lenStr.append((char) ch);
                }
                int keyLen = Integer.parseInt(lenStr.toString());
                
                // Read key
                char[] keyChars = new char[keyLen];
                in.read(keyChars);
                String key = new String(keyChars);
                
                // Read value length
                lenStr = new StringBuilder();
                while ((ch = in.read()) != ':') {
                    lenStr.append((char) ch);
                }
                int valueLen = Integer.parseInt(lenStr.toString());
                
                // Read value
                char[] valueChars = new char[valueLen];
                in.read(valueChars);
                String value = new String(valueChars);
                
                result.put(key, value);
            }
            
            return result;
        }

        private void sendBencodedMessage(Map<String, String> message, BufferedWriter out) throws IOException {
            StringBuilder encoded = new StringBuilder("d");
            
            // Sort keys for consistent encoding
            List<String> keys = new ArrayList<>(message.keySet());
            Collections.sort(keys);
            
            for (String key : keys) {
                String value = message.get(key);
                encoded.append(key.length()).append(":").append(key);
                encoded.append(value.length()).append(":").append(value);
            }
            
            encoded.append("e");
            
            out.write(encoded.toString());
            out.flush();
        }
    }
}
