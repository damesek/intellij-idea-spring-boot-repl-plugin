package hu.baader.repl.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Byte-oriented nREPL transport. A limit applies to the entire message, not just each string. */
public final class Bencode {
    public static final int MAX_MESSAGE_BYTES = 4 * 1024 * 1024;
    private Bencode() {}

    public static Map<String, Object> read(InputStream input) throws IOException {
        int first = input.read();
        if (first == -1) return null;
        Reader reader = new Reader(input);
        Object value = reader.value(first, 0);
        if (!(value instanceof Map<?, ?>)) throw new IOException("Expected bencode dictionary");
        @SuppressWarnings("unchecked") Map<String, Object> result = (Map<String, Object>) value;
        return result;
    }

    public static void write(Map<String, ?> value, OutputStream output) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        encode(value, bytes, 0);
        if (bytes.size() > MAX_MESSAGE_BYTES) throw new IOException("Message too large");
        synchronized (output) { bytes.writeTo(output); output.flush(); }
    }

    private static void encode(Object value, ByteArrayOutputStream out, int depth) throws IOException {
        if (depth > 32 || out.size() > MAX_MESSAGE_BYTES) throw new IOException("Message limit exceeded");
        if (value instanceof Map<?, ?> map) {
            out.write('d');
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, item) -> { if (item != null) sorted.put(key.toString(), item); });
            for (var entry : sorted.entrySet()) { encode(entry.getKey(), out, depth + 1); encode(entry.getValue(), out, depth + 1); }
            out.write('e');
        } else if (value instanceof Iterable<?> values) {
            out.write('l');
            for (Object item : values) encode(item, out, depth + 1);
            out.write('e');
        } else if (value instanceof Number number) {
            out.writeBytes(("i" + number.longValue() + "e").getBytes(StandardCharsets.US_ASCII));
        } else {
            byte[] bytes = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_MESSAGE_BYTES) throw new IOException("String too large");
            out.writeBytes((bytes.length + ":").getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(bytes);
        }
    }

    private static final class Reader {
        private final InputStream in;
        private int remaining = MAX_MESSAGE_BYTES - 1;
        Reader(InputStream in) { this.in = in; }
        int next() throws IOException {
            if (--remaining < 0) throw new IOException("Message too large");
            int next = in.read();
            if (next == -1) throw new EOFException("Truncated bencode message");
            return next;
        }
        Object value(int first, int depth) throws IOException {
            if (depth > 32) throw new IOException("Message nesting limit exceeded");
            if (first == 'd') {
                Map<String, Object> result = new LinkedHashMap<>();
                for (int next = next(); next != 'e'; next = next()) {
                    Object key = value(next, depth + 1);
                    if (!(key instanceof String text)) throw new IOException("Dictionary key must be a string");
                    if (result.containsKey(text)) throw new IOException("Duplicate dictionary key");
                    result.put(text, value(next(), depth + 1));
                }
                return result;
            }
            if (first == 'l') {
                List<Object> result = new ArrayList<>();
                for (int next = next(); next != 'e'; next = next()) result.add(value(next, depth + 1));
                return result;
            }
            if (first == 'i') {
                StringBuilder number = new StringBuilder();
                for (int next = next(); next != 'e'; next = next()) {
                    if (number.length() > 20) throw new IOException("Invalid integer");
                    number.append((char) next);
                }
                try { if (!number.toString().matches("0|-?[1-9][0-9]*")) throw new NumberFormatException(); return Long.parseLong(number.toString()); }
                catch (NumberFormatException ex) { throw new IOException("Invalid integer", ex); }
            }
            if (first < '0' || first > '9') throw new IOException("Invalid bencode token");
            long length = first - '0';
            boolean zero = first == '0';
            for (int next = next(); next != ':'; next = next()) {
                if (zero || next < '0' || next > '9') throw new IOException("Invalid string length");
                length = length * 10 + next - '0';
                if (length > remaining) throw new IOException("String too large");
            }
            if (length > remaining) throw new IOException("Message too large");
            remaining -= (int) length;
            byte[] bytes = in.readNBytes((int) length);
            if (bytes.length != length) throw new EOFException("Truncated string");
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }
    }
}
