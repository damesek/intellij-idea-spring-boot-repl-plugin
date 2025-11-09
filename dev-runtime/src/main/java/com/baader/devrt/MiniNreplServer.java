package com.baader.devrt;

import java.io.*;
import java.lang.reflect.Method;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal bencode-based server handling clone/describe/java-eval/eval+//!java
 */
public class MiniNreplServer {
    private final int port;
    private ServerSocket serverSocket;
    private ExecutorService executor;
    private volatile boolean running;

    public MiniNreplServer(int port) { this.port = port; }

    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        executor = Executors.newCachedThreadPool();
        running = true;
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
                // ignore
            } finally { try { socket.close(); } catch (IOException ignored) {} }
        }

        private void handle(Map<String,String> m, BufferedWriter out) throws IOException {
            String op = m.get("op");
            String id = m.get("id");
            if ("clone".equals(op)) {
                Map<String,String> resp = new LinkedHashMap<>();
                resp.put("id", id);
                resp.put("new-session", sessionId);
                resp.put("status", "done");
                writeBencode(resp, out);
                return;
            }
            if ("describe".equals(op)) {
                Map<String,String> resp = new LinkedHashMap<>();
                resp.put("id", id);
                resp.put("ops", "clone,describe,java-eval,eval");
                resp.put("status", "done");
                writeBencode(resp, out);
                return;
            }
            if ("java-eval".equals(op) || ("eval".equals(op) && m.get("code") != null && m.get("code").startsWith("//!java"))) {
                String code = m.get("code");
                if (code != null && code.startsWith("//!java")) code = code.substring("//!java".length()).trim();
                evalJavaAndRespond(code, id, m.get("session"), out);
                return;
            }
            if ("bind-spring".equals(op)) {
                String expr = m.get("expr");
                boolean ok = false;
                try {
                    if (expr != null && !expr.isEmpty()) {
                        // Evaluate custom expression to get ctx, then set holder via reflection
                        String code = "" +
                                "Object tmpCtx = (" + expr + ");" +
                                "Class<?> h = Class.forName(\"com.baader.devrt.SpringContextHolder\");" +
                                "java.lang.reflect.Method sm = h.getMethod(\"set\", Object.class);" +
                                "sm.invoke(null, tmpCtx);" +
                                "return String.valueOf(tmpCtx != null);";
                        JavaCodeEvaluator.EvalResult res = JavaCodeEvaluator.evaluate(code);
                        ok = "true".equalsIgnoreCase(String.valueOf(res.value));
                    } else {
                        ok = AutoBinder.tryBindOnce();
                    }
                } catch (Throwable t) {
                    Map<String,String> e = new LinkedHashMap<>();
                    e.put("id", id); e.put("session", m.get("session")); e.put("err", String.valueOf(t));
                    writeBencode(e, out);
                }
                Map<String,String> v = new LinkedHashMap<>();
                v.put("id", id); v.put("session", m.get("session")); v.put("value", String.valueOf(ok));
                writeBencode(v, out);
                Map<String,String> done = new LinkedHashMap<>();
                done.put("id", id); done.put("session", m.get("session")); done.put("status", "done");
                writeBencode(done, out);
                return;
            }
            if ("class-reload".equals(op)) {
                String code = m.get("code");
                JavaCodeEvaluator.HotSwapResult res = (code == null || code.trim().isEmpty())
                        ? new JavaCodeEvaluator.HotSwapResult(false, null, "No Java source provided")
                        : JavaCodeEvaluator.hotSwap(code);
                Map<String,String> v = new LinkedHashMap<>();
                v.put("id", id); v.put("session", m.get("session"));
                if (res.message != null) v.put("value", res.message);
                if (!res.success) v.put("err", res.error != null ? res.error : "HotSwap failed");
                writeBencode(v, out);
                Map<String,String> done = new LinkedHashMap<>();
                done.put("id", id); done.put("session", m.get("session")); done.put("status", "done");
                writeBencode(done, out);
                return;
            }
            if ("list-beans".equals(op)) {
                Map<String,String> v = new LinkedHashMap<>();
                v.put("id", id); v.put("session", m.get("session"));
                try {
                    Object ctx = SpringContextHolder.get();
                    if (ctx == null) throw new IllegalStateException("Spring context not bound");
                    Class<?> appCtx = Class.forName("org.springframework.context.ApplicationContext");
                    if (!appCtx.isInstance(ctx)) throw new IllegalStateException("Spring context unavailable");
                    Method getNames = appCtx.getMethod("getBeanDefinitionNames");
                    String[] names = (String[]) getNames.invoke(ctx);
                    Method getType = null;
                    try { getType = appCtx.getMethod("getType", String.class); } catch (Throwable ignored) {}
                    StringBuilder sb = new StringBuilder();
                    if (names != null) {
                        for (String name : names) {
                            String typeName = "";
                            if (getType != null) {
                                try {
                                    Object type = getType.invoke(ctx, name);
                                    if (type instanceof Class) typeName = ((Class<?>) type).getName();
                                    else if (type != null) typeName = type.toString();
                                } catch (Throwable ignored) {}
                            }
                            sb.append(name).append('\t').append(typeName == null ? "" : typeName).append('\n');
                        }
                    }
                    v.put("value", sb.toString());
                } catch (Throwable t) {
                    v.put("err", String.valueOf(t));
                }
                writeBencode(v, out);
                Map<String,String> done = new LinkedHashMap<>();
                done.put("id", id); done.put("session", m.get("session")); done.put("status", "done");
                writeBencode(done, out);
                return;
            }
            if ("snapshots".equals(op)) {
                Map<String,String> v = new LinkedHashMap<>();
                v.put("id", id); v.put("session", m.get("session")); v.put("value", SnapshotStore.listAsTsv());
                writeBencode(v, out);
                Map<String,String> done = new LinkedHashMap<>();
                done.put("id", id); done.put("session", m.get("session")); done.put("status", "done");
                writeBencode(done, out);
                return;
            }
            if ("snapshot-delete".equals(op)) {
                String name = m.get("name");
                if (name != null) SnapshotStore.delete(name);
                Map<String,String> v = new LinkedHashMap<>(); v.put("id", id); v.put("session", m.get("session")); v.put("value", "OK"); writeBencode(v, out);
                Map<String,String> done = new LinkedHashMap<>(); done.put("id", id); done.put("session", m.get("session")); done.put("status", "done"); writeBencode(done, out);
                return;
            }
            if ("snapshot-pin".equals(op)) {
                String name = m.get("name"); String expr = m.get("expr");
                boolean ok = false; String err = null;
                try {
                    if (name != null && expr != null) {
                        // Evaluate expression to get object (real LIVE pin)
                        JavaCodeEvaluator.EvalObj res = JavaCodeEvaluator.evaluateObject(expr);
                        if (res.error == null) {
                            SnapshotStore.pin(name, res.obj);
                            ok = true;
                        } else err = res.error;
                    }
                } catch (Throwable t) { err = String.valueOf(t); }
                Map<String,String> v = new LinkedHashMap<>(); v.put("id", id); v.put("session", m.get("session")); v.put("value", String.valueOf(ok)); if (err!=null) v.put("err", err); writeBencode(v, out);
                Map<String,String> done = new LinkedHashMap<>(); done.put("id", id); done.put("session", m.get("session")); done.put("status", "done"); writeBencode(done, out);
                return;
            }
            if ("snapshot-save-json".equals(op)) {
                String name = m.get("name"); String expr = m.get("expr");
                boolean ok = false; String err = null;
                try {
                    if (name != null && expr != null) {
                        JavaCodeEvaluator.EvalObj res = JavaCodeEvaluator.evaluateObject(expr);
                        if (res.error == null) {
                            SnapshotStore.saveJson(name, res.obj);
                            ok = true;
                        } else err = res.error;
                    }
                } catch (Throwable t) { err = String.valueOf(t); }
                Map<String,String> v = new LinkedHashMap<>(); v.put("id", id); v.put("session", m.get("session")); v.put("value", String.valueOf(ok)); if (err!=null) v.put("err", err); writeBencode(v, out);
                Map<String,String> done = new LinkedHashMap<>(); done.put("id", id); done.put("session", m.get("session")); done.put("status", "done"); writeBencode(done, out);
                return;
            }
            if ("snapshot-materialize".equals(op)) {
                String name = m.get("name"); String type = m.get("type"); String target = m.get("target");
                boolean ok = false; String err = null;
                try {
                    if (name != null && type != null) {
                        // Resolve class with app CL
                        Class<?> cls = JavaCodeEvaluator.resolveAppClass(type);
                        // get JSON
                        String json = SnapshotStore.getJson(name);
                        if (json == null) throw new IllegalStateException("No JSON snapshot: " + name);
                        // Instantiate ObjectMapper reflectively and read
                        ClassLoader cl = Thread.currentThread().getContextClassLoader();
                        if (cl == null) cl = JavaCodeEvaluator.class.getClassLoader();
                        Class<?> om = Class.forName("com.fasterxml.jackson.databind.ObjectMapper", true, cl);
                        Object mapper = om.getConstructor().newInstance();
                        java.lang.reflect.Method read = om.getMethod("readValue", String.class, Class.class);
                        Object obj = read.invoke(mapper, json, cls);
                        // pin as LIVE under target or same name
                        String liveName = (target != null && !target.isEmpty()) ? target : name;
                        SnapshotStore.pin(liveName, obj);
                        ok = true;
                    }
                } catch (Throwable t) { err = String.valueOf(t); }
                Map<String,String> v = new LinkedHashMap<>(); v.put("id", id); v.put("session", m.get("session")); v.put("value", String.valueOf(ok)); if (err!=null) v.put("err", err); writeBencode(v, out);
                Map<String,String> done = new LinkedHashMap<>(); done.put("id", id); done.put("session", m.get("session")); done.put("status", "done"); writeBencode(done, out);
                return;
            }
            // New simplified snapshot operations - use existing SnapshotStore directly
            if ("snapshot-save".equals(op)) {
                String name = m.get("name"); String expr = m.get("expr");
                boolean ok = false; String err = null;
                try {
                    if (name != null && expr != null) {
                        // Evaluate expression to get object
                        JavaCodeEvaluator.EvalObj res = JavaCodeEvaluator.evaluateObject(expr);
                        if (res.error == null && res.obj != null) {
                            // Use existing SnapshotStore.pin to save
                            SnapshotStore.pin(name, res.obj);
                            ok = true;
                        } else {
                            err = res.error != null ? res.error : "Evaluation returned null";
                        }
                    }
                } catch (Throwable t) { err = String.valueOf(t); }
                String msg = ok ? "Saved: " + name : "Failed: " + (err != null ? err : "unknown error");
                Map<String,String> v = new LinkedHashMap<>(); v.put("id", id); v.put("session", m.get("session")); v.put("value", msg); if (err!=null) v.put("err", err); writeBencode(v, out);
                Map<String,String> done = new LinkedHashMap<>(); done.put("id", id); done.put("session", m.get("session")); done.put("status", "done"); writeBencode(done, out);
                return;
            }
            if ("snapshot-load".equals(op)) {
                String name = m.get("name"); String var = m.get("var");
                String msg = ""; String err = null;
                try {
                    // Load from SnapshotStore directly
                    Object loaded = SnapshotStore.get(name);

                    if (loaded != null) {
                        String varName = (var != null && !var.isEmpty()) ? var : name;
                        // Store with new name if different
                        if (!varName.equals(name)) {
                            SnapshotStore.pin(varName, loaded);
                        }
                        msg = "Loaded: " + varName + " (" + loaded.getClass().getSimpleName() + ")";
                    } else msg = "Not found: " + name;
                } catch (Throwable t) { err = String.valueOf(t); }
                Map<String,String> v = new LinkedHashMap<>(); v.put("id", id); v.put("session", m.get("session")); v.put("value", msg); if (err!=null) v.put("err", err); writeBencode(v, out);
                Map<String,String> done = new LinkedHashMap<>(); done.put("id", id); done.put("session", m.get("session")); done.put("status", "done"); writeBencode(done, out);
                return;
            }
            if ("snapshot-list-simple".equals(op)) {
                // Use SnapshotStore's list
                String tsv = SnapshotStore.listAsTsv();
                // Convert TSV to simple list
                String[] lines = tsv.split("\n");
                StringBuilder names = new StringBuilder();
                for (String line : lines) {
                    String[] parts = line.split("\t");
                    if (parts.length > 0 && !parts[0].isEmpty()) {
                        names.append(parts[0]).append("\n");
                    }
                }
                String list = names.toString().trim();
                Map<String,String> v = new LinkedHashMap<>(); v.put("id", id); v.put("session", m.get("session")); v.put("value", list); writeBencode(v, out);
                Map<String,String> done = new LinkedHashMap<>(); done.put("id", id); done.put("session", m.get("session")); done.put("status", "done"); writeBencode(done, out);
                return;
            }
            if ("snapshot-info".equals(op)) {
                String name = m.get("name");
                String info = SnapshotManager.info(name);
                Map<String,String> v = new LinkedHashMap<>(); v.put("id", id); v.put("session", m.get("session")); v.put("value", info); writeBencode(v, out);
                Map<String,String> done = new LinkedHashMap<>(); done.put("id", id); done.put("session", m.get("session")); done.put("status", "done"); writeBencode(done, out);
                return;
            }
            // unknown -> done
            Map<String,String> resp = new LinkedHashMap<>();
            resp.put("id", id);
            resp.put("status", "unknown-op");
            writeBencode(resp, out);
        }

        private void evalJavaAndRespond(String code, String id, String session, BufferedWriter out) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            PrintStream old = System.out;
            PrintStream ps = new PrintStream(baos);
            try {
                System.setOut(ps);
                JavaCodeEvaluator.EvalResult res = JavaCodeEvaluator.evaluate(code);
                System.out.flush();
                System.setOut(old);
                String outStr = baos.toString();
                if (!outStr.isEmpty()) {
                    Map<String,String> o = new LinkedHashMap<>();
                    o.put("id", id); o.put("session", session); o.put("out", outStr);
                    writeBencode(o, out);
                }
                if (res.error != null) {
                    Map<String,String> e = new LinkedHashMap<>();
                    e.put("id", id); e.put("session", session); e.put("err", res.error);
                    writeBencode(e, out);
                } else if (res.value != null) {
                    Map<String,String> v = new LinkedHashMap<>();
                    v.put("id", id); v.put("session", session); v.put("value", res.value);
                    writeBencode(v, out);
                }
                Map<String,String> done = new LinkedHashMap<>();
                done.put("id", id); done.put("session", session); done.put("status", "done");
                writeBencode(done, out);
            } finally { System.setOut(old); }
        }
    }

    private static void writeBencode(Map<String,String> map, BufferedWriter out) throws IOException {
        StringBuilder sb = new StringBuilder("d");
        List<String> keys = new ArrayList<>(map.keySet()); Collections.sort(keys);
        for (String k : keys) {
            String v = map.get(k); if (v == null) v = "";
            sb.append(k.length()).append(":").append(k);
            sb.append(v.length()).append(":").append(v);
        }
        sb.append("e"); out.write(sb.toString()); out.flush();
    }

    private static Map<String,String> readBencode(BufferedReader in) throws IOException {
        int ch = in.read(); if (ch != 'd') return null;
        Map<String,String> m = new LinkedHashMap<>();
        while (true) {
            ch = in.read(); if (ch == -1) return null; char c = (char) ch;
            if (c == 'e') break;
            // key length
            StringBuilder len = new StringBuilder(); len.append(c);
            while ((ch = in.read()) != ':') len.append((char)ch);
            int klen = Integer.parseInt(len.toString());
            char[] kbuf = new char[klen]; in.read(kbuf); String key = new String(kbuf);
            // value (assume string)
            len = new StringBuilder(); while ((ch = in.read()) != ':') len.append((char)ch);
            int vlen = Integer.parseInt(len.toString());
            char[] vbuf = new char[vlen]; in.read(vbuf); String val = new String(vbuf);
            m.put(key, val);
        }
        return m;
    }
}
