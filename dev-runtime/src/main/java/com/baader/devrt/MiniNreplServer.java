package com.baader.devrt;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal bencode-based server. Manages client connections and delegates
 * all REPL logic to a dedicated ReplHandler.
 */
public class MiniNreplServer {
    private final int port;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running;
    private final ReplHandler replHandler = new ReplHandler();

    public MiniNreplServer(int port) { this.port = port; }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        executor = Executors.newCachedThreadPool();
        running = true;
        System.out.println("nREPL server started on port " + port);
        executor.submit(() -> {
            while (running) {
                try {
                    Socket s = serverSocket.accept();
                    executor.submit(new Client(s));
                } catch (IOException e) {
                    if (running) e.printStackTrace();
                }
            }
        });
    }

    class Client implements Runnable {
        private final Socket socket;
        private final String sessionId = UUID.randomUUID().toString();

        Client(Socket socket) { this.socket = socket; }

        @Override public void run() {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream()));
                 BufferedWriter out = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream()))) {
                while (!socket.isClosed()) {
                    Map<String, String> msg = readBencode(in);
                    if (msg == null) break;
                    handle(msg, out);
                }
            } catch (Exception e) {
                // Client disconnected or other error
            } finally { try { socket.close(); } catch (IOException ignored) {} }
        }

        private void handle(Map<String, String> msg, BufferedWriter out) throws IOException {
            String op = msg.get("op");
            String id = msg.get("id");

            if ("clone".equals(op)) {
                respond(Map.of("id", id, "new-session", sessionId, "status", "done"), out);
                return;
            }
            if ("describe".equals(op)) {
                // Restore full ops list for compatibility
                respond(Map.of(
                    "id", id,
                    "ops", "clone,describe,eval,java-eval,imports/get,imports/add,session/reset,snapshots,snapshot/save,snapshot/get,snapshot/list,snapshot/delete,list-beans,bind-spring,class-reload",
                    "status", "done"
                ), out);
                return;
            }

            // Delegate all other ops to the handler
            Map<String, Object> result = replHandler.handle(op, msg);
            
            // Translate handler response to nREPL messages
            Map<String, String> response = new LinkedHashMap<>();
            for (Map.Entry<String, Object> entry : result.entrySet()) {
                response.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            
            response.put("id", id);
            response.put("session", sessionId);
            
            // Send value, out, message, and err responses
            String primaryValue = response.get("value");
            String valuesField = response.get("values");
            if (primaryValue != null && !primaryValue.isEmpty()) {
                respond(Map.of("id", id, "session", sessionId, "value", primaryValue), out);
            } else if (valuesField != null && !valuesField.equals("[]")) {
                respond(Map.of("id", id, "session", sessionId, "value", valuesField), out);
            }
            String output = response.getOrDefault("output", "");
            String message = response.getOrDefault("message", "");
            if (!output.isEmpty() || !message.isEmpty()) {
                String combinedOut = output + (output.isEmpty() ? "" : "\n") + message;
                respond(Map.of("id", id, "session", sessionId, "out", combinedOut), out);
            }
            if (response.containsKey("err")) {
                respond(Map.of("id", id, "session", sessionId, "err", response.get("err")), out);
            }
            
            // Send final "done" status
            respond(Map.of("id", id, "session", sessionId, "status", "done"), out);
        }

        private void respond(Map<String, String> data, BufferedWriter out) throws IOException {
            writeBencode(data, out);
        }
    }

    private static void writeBencode(Map<String, String> map, BufferedWriter out) throws IOException {
        StringBuilder sb = new StringBuilder("d");
        List<String> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys);
        for (String k : keys) {
            String v = map.get(k);
            if (v == null) continue;
            sb.append(k.length()).append(":").append(k);
            sb.append(v.length()).append(":").append(v);
        }
        sb.append("e");
        out.write(sb.toString());
        out.flush();
    }

    private static Map<String, String> readBencode(BufferedReader in) throws IOException {
        int firstChar = in.read();
        if (firstChar != 'd') return null;
        
        Map<String, String> m = new LinkedHashMap<>();
        while (true) {
            int ch = in.read();
            if (ch == -1 || ch == 'e') break;
            
            // Read key
            StringBuilder lenStr = new StringBuilder();
            lenStr.append((char)ch);
            while ((ch = in.read()) != ':') lenStr.append((char)ch);
            int klen = Integer.parseInt(lenStr.toString());
            char[] kbuf = new char[klen];
            in.read(kbuf);
            String key = new String(kbuf);

            // Read value
            lenStr = new StringBuilder();
            while ((ch = in.read()) != ':') lenStr.append((char)ch);
            int vlen = Integer.parseInt(lenStr.toString());
            char[] vbuf = new char[vlen];
            in.read(vbuf);
            String val = new String(vbuf);
            
            m.put(key, val);
        }
        return m;
    }
}
