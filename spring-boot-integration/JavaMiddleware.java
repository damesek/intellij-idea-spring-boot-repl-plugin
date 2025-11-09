package com.example.springboot.nrepl;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.IPersistentMap;
import clojure.lang.Keyword;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

/**
 * nREPL middleware bridge: intercepts eval ops with `//!java` prefix and
 * evaluates them via JavaCodeEvaluator, sending results back over transport.
 */
public final class JavaMiddleware {

    private static final IFn SEND = Clojure.var("nrepl.transport", "send");
    private static final IFn RESPONSE_FOR = Clojure.var("nrepl.misc", "response-for");

    private static Keyword kw(String n) { return (Keyword) Clojure.read(":" + n); }

    private JavaMiddleware() {}

    public static void handle(IPersistentMap msg) {
        String code = (String) msg.valAt(kw("code"));
        if (code == null) return;

        String javaCode = stripPrefix(code);

        // Capture stdout during evaluation
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream oldOut = System.out;
        PrintStream ps = new PrintStream(baos);

        try {
            System.setOut(ps);

            JavaCodeEvaluator.EvalResult res = JavaCodeEvaluator.evaluate(javaCode);

            // Flush and restore stdout
            System.out.flush();
            System.setOut(oldOut);
            String out = baos.toString();

            Object transport = msg.valAt(kw("transport"));

            if (out != null && !out.isEmpty()) {
                Object outResp = RESPONSE_FOR.invoke(msg, kw("out"), out);
                SEND.invoke(transport, outResp);
            }

            if (res.error != null) {
                Object errResp = RESPONSE_FOR.invoke(msg,
                        kw("err"), res.error);
                SEND.invoke(transport, errResp);
            }
            if (res.value != null) {
                Object valResp = RESPONSE_FOR.invoke(msg,
                        kw("value"), res.value);
                SEND.invoke(transport, valResp);
            }

            Object doneResp = RESPONSE_FOR.invoke(msg, kw("status"), Clojure.read(":done"));
            SEND.invoke(transport, doneResp);
        } catch (Throwable t) {
            try {
                System.setOut(oldOut);
            } catch (Throwable ignore) { }

            Object transport = msg.valAt(kw("transport"));
            String err = t.getClass().getName() + ": " + t.getMessage();
            Object errResp = RESPONSE_FOR.invoke(msg, kw("err"), err);
            SEND.invoke(transport, errResp);
            Object doneResp = RESPONSE_FOR.invoke(msg, kw("status"), Clojure.read("[:eval-error :done]"));
            SEND.invoke(transport, doneResp);
        } finally {
            try { System.setOut(oldOut); } catch (Throwable ignore) {}
        }
    }

    private static String stripPrefix(String code) {
        final String p = "//!java";
        if (code.startsWith(p)) {
            return code.substring(p.length()).trim();
        }
        return code;
    }
}

