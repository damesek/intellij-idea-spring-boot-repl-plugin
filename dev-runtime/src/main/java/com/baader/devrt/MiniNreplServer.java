package com.baader.devrt;

import hu.baader.repl.protocol.Bencode;
import hu.baader.repl.protocol.ReplProtocol;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public final class MiniNreplServer implements AutoCloseable {
    private final int requestedPort;
    private final byte[] token;
    private volatile boolean closed;
    private ServerSocket listener;
    private final Set<Socket> clients = ConcurrentHashMap.newKeySet();
    private final ExecutorService readers = Executors.newFixedThreadPool(8, runnable -> {
        Thread thread = new Thread(runnable, "sb-repl-client"); thread.setDaemon(true); return thread;
    });
    public MiniNreplServer(int port, String token) {
        if (token == null || token.length() < 32) throw new IllegalArgumentException("A random token of at least 32 characters is required");
        this.requestedPort = port; this.token = token.getBytes(StandardCharsets.UTF_8);
    }
    public synchronized void start() throws IOException {
        if (listener != null) throw new IllegalStateException("Server already started");
        listener = new ServerSocket();
        listener.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), requestedPort), 8);
        Thread accept = new Thread(() -> {
            while (!closed) try {
                Socket socket = listener.accept();
                try {
                    if (closed || clients.size() >= 8) { socket.close(); continue; }
                    socket.setTcpNoDelay(true); socket.setSoTimeout(15 * 60_000);
                    clients.add(socket); readers.execute(() -> serve(socket));
                } catch (IOException | RejectedExecutionException failure) {
                    clients.remove(socket); socket.close(); throw failure;
                }
            } catch (IOException | RejectedExecutionException exception) { if (!closed) System.err.println("[sb-repl] Connection rejected: " + exception.getMessage()); }
        }, "sb-repl-accept");
        accept.setDaemon(true); accept.start();
    }
    public int port() { return listener.getLocalPort(); }
    boolean authenticated(Map<String, ?> message) {
        Object candidate = message.get("token");
        return candidate instanceof String text && MessageDigest.isEqual(token, text.getBytes(StandardCharsets.UTF_8));
    }
    private void serve(Socket socket) {
        try (socket; ReplHandler handler = new ReplHandler()) {
            InputStream input = new BufferedInputStream(socket.getInputStream());
            OutputStream output = new BufferedOutputStream(socket.getOutputStream());
            for (Map<String, Object> message; (message = Bencode.read(input)) != null;) {
                Map<String, String> request = ReplProtocol.strings(message);
                if (!authenticated(message)) { respond(request, ReplHandler.error("Authentication failed"), output); break; }
                request.remove("token");
                String op = request.getOrDefault("op", "");
                Runnable work = () -> {
                    try { respond(request, handler.handle(op, request), output); }
                    catch (IOException exception) { try { socket.close(); } catch (IOException ignored) {} }
                    finally { Thread.interrupted(); }
                };
                if (ReplProtocol.CONTROL_OPS.contains(op)) work.run();
                else try { handler.submit(request.getOrDefault("session", ""), work); }
                catch (IllegalArgumentException | RejectedExecutionException exception) { respond(request, ReplHandler.error("Session unavailable or request queue full"), output); }
            }
        } catch (IOException ignored) {
            // EOF, malformed frames, timeouts and disconnects close this connection only.
        } finally { clients.remove(socket); }
    }
    static void respond(Map<String, String> request, Map<String, Object> values, OutputStream output) throws IOException {
        Map<String, Object> response = new LinkedHashMap<>(values);
        response.put("id", request.getOrDefault("id", ""));
        response.put("session", request.getOrDefault("session", ""));
        response.put("op", request.getOrDefault("op", ""));
        Bencode.write(response, output);
    }
    @Override public synchronized void close() {
        closed = true;
        if (listener != null) try { listener.close(); } catch (IOException ignored) {}
        for (Socket socket : clients) try { socket.close(); } catch (IOException ignored) {}
        readers.shutdownNow();
    }
}
