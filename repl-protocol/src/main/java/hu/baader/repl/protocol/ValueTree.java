package hu.baader.repl.protocol;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** A bounded display projection, never an executable expression or a serialized application object. */
public record ValueTree(String label, String kind, String type, String text, List<ValueTree> children) {
    public static final int MAX_NODES = 5000, MAX_DEPTH = 32, MAX_WIRE = 1_048_576;
    public ValueTree { children = List.copyOf(children); }
    public static ValueTree leaf(String label, String kind, String type, String text) {
        return new ValueTree(label, kind, type, text, List.of());
    }
    public String encode() {
        StringBuilder out = new StringBuilder("v1\n");
        write(this, 0, out, new int[1]);
        if (out.length() > MAX_WIRE) throw new IllegalArgumentException("Value preview is too large");
        return out.toString();
    }
    private static void write(ValueTree node, int depth, StringBuilder out, int[] count) {
        if (depth > MAX_DEPTH || ++count[0] > MAX_NODES) throw new IllegalArgumentException("Value tree limit exceeded");
        out.append(depth).append('\t').append(node.kind).append('\t').append(encoded(node.label)).append('\t')
                .append(encoded(node.type)).append('\t').append(encoded(node.text)).append('\n');
        for (ValueTree child : node.children) write(child, depth + 1, out, count);
    }
    private static String encoded(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String decoded(String value) { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
    public static ValueTree decode(String wire) {
        if (wire == null || wire.length() > MAX_WIRE || !wire.startsWith("v1\n")) throw new IllegalArgumentException("Invalid value preview");
        String[] lines = wire.substring(3).split("\n");
        if (lines.length == 0 || lines.length > MAX_NODES) throw new IllegalArgumentException("Value tree limit exceeded");
        List<Builder> stack = new ArrayList<>();
        Builder root = null;
        for (String line : lines) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 5) throw new IllegalArgumentException("Invalid value row");
            int depth = Integer.parseInt(fields[0]);
            if (depth < 0 || depth > MAX_DEPTH || depth > stack.size() || root != null && depth == 0)
                throw new IllegalArgumentException("Invalid value depth");
            if (!Set.of("OBJECT", "ARRAY", "STRING", "NUMBER", "BOOLEAN", "NULL", "REFERENCE", "LIMIT", "ERROR").contains(fields[1]))
                throw new IllegalArgumentException("Unknown value kind");
            Builder item = new Builder(decoded(fields[2]), fields[1], decoded(fields[3]), decoded(fields[4]));
            while (stack.size() > depth) stack.remove(stack.size() - 1);
            if (depth == 0) root = item; else stack.get(depth - 1).children.add(item);
            stack.add(item);
        }
        if (root == null) throw new IllegalArgumentException("Empty value preview");
        return root.build();
    }
    private static final class Builder {
        final String label, kind, type, text;
        final List<Builder> children = new ArrayList<>();
        Builder(String label, String kind, String type, String text) { this.label=label; this.kind=kind; this.type=type; this.text=text; }
        ValueTree build() { return new ValueTree(label, kind, type, text, children.stream().map(Builder::build).toList()); }
    }
}
