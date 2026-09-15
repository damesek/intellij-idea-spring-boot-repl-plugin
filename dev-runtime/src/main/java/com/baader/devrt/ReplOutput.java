package com.baader.devrt;

import java.io.*;
import java.nio.charset.StandardCharsets;

/** Only eval-thread output is captured. Other application threads retain their original output. */
final class ReplOutput {
    private static final InheritableThreadLocal<Capture> CURRENT = new InheritableThreadLocal<>();
    private static boolean installed;
    static synchronized void install() {
        if (installed) return;
        System.setOut(route(System.out, false)); System.setErr(route(System.err, true)); installed = true;
    }
    private static PrintStream route(PrintStream fallback, boolean error) {
        return new PrintStream(new OutputStream() {
            @Override public void write(int value) { write(new byte[]{(byte) value}, 0, 1); }
            @Override public void write(byte[] bytes, int offset, int length) {
                Capture current = CURRENT.get();
                if (current == null || current.closed) fallback.write(bytes, offset, length);
                else current.append(error, bytes, offset, length);
            }
            @Override public void flush() { fallback.flush(); }
        }, true, StandardCharsets.UTF_8);
    }
    static Capture capture() { Capture capture = new Capture(CURRENT.get()); CURRENT.set(capture); return capture; }
    static final class Capture implements AutoCloseable {
        private final Capture previous;
        private final ByteArrayOutputStream out = new ByteArrayOutputStream(), err = new ByteArrayOutputStream();
        private volatile boolean closed;
        private boolean outTruncated, errTruncated;
        Capture(Capture previous) { this.previous = previous; }
        synchronized void append(boolean error, byte[] bytes, int offset, int length) {
            ByteArrayOutputStream buffer = error ? err : out;
            if (length > 65536 - buffer.size()) { if (error) errTruncated = true; else outTruncated = true; }
            buffer.write(bytes, offset, Math.max(0, Math.min(length, 65536 - buffer.size())));
        }
        synchronized String out() { return out.toString(StandardCharsets.UTF_8) + (outTruncated ? "\n[truncated]" : ""); }
        synchronized String err() { return err.toString(StandardCharsets.UTF_8) + (errTruncated ? "\n[truncated]" : ""); }
        @Override public void close() { closed = true; if (previous == null) CURRENT.remove(); else CURRENT.set(previous); }
    }
}
