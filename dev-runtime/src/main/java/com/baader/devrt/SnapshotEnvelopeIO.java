package com.baader.devrt;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.util.*;
import hu.baader.repl.protocol.SnapshotLimits;

/** Streaming plain JSON envelope operations; application codecs are never invoked. */
final class SnapshotEnvelopeIO {
    static void rewrite(Path source, OutputStream out, Map<String,?> replacements, Object payload) throws IOException {
        try (InputStream input = SnapshotIO.input(source, SnapshotLimits.MAX_BYTES)) {
            Object mapper = CaseJson.mapper(), factory = mapper.getClass().getMethod("getFactory").invoke(mapper);
            ClassLoader loader = mapper.getClass().getClassLoader();
            Class<?> p = Class.forName("com.fasterxml.jackson.core.JsonParser", true, loader);
            Class<?> g = Class.forName("com.fasterxml.jackson.core.JsonGenerator", true, loader);
            try (Closeable parser = (Closeable) factory.getClass().getMethod("createParser", InputStream.class).invoke(factory, input);
                 Closeable generator = (Closeable) factory.getClass().getMethod("createGenerator", OutputStream.class).invoke(factory, out)) {
                Method next = p.getMethod("nextToken");
                if (!"START_OBJECT".equals(String.valueOf(next.invoke(parser)))) throw new IOException("Invalid snapshot envelope");
                g.getMethod("writeStartObject").invoke(generator);
                for (var entry : replacements.entrySet()) {
                    g.getMethod("writeFieldName", String.class).invoke(generator, entry.getKey());
                    g.getMethod("writeObject", Object.class).invoke(generator, entry.getValue());
                }
                while ("FIELD_NAME".equals(String.valueOf(next.invoke(parser)))) {
                    String field = (String) p.getMethod("getCurrentName").invoke(parser); next.invoke(parser);
                    if (replacements.containsKey(field)) p.getMethod("skipChildren").invoke(parser);
                    else {
                        g.getMethod("writeFieldName", String.class).invoke(generator, field);
                        if (field.equals("payload") && payload != null) {
                            p.getMethod("skipChildren").invoke(parser); g.getMethod("writeObject", Object.class).invoke(generator, payload);
                        } else g.getMethod("copyCurrentStructure", p).invoke(generator, parser);
                    }
                }
                if (!"END_OBJECT".equals(String.valueOf(p.getMethod("currentToken").invoke(parser))) || next.invoke(parser) != null) throw new IOException("Invalid trailing snapshot JSON");
                g.getMethod("writeEndObject").invoke(generator);
            }
        } catch (ReflectiveOperationException e) { throw new IOException("Invalid snapshot envelope", e instanceof InvocationTargetException i ? i.getCause() : e); }
    }
    static Object headerValue(Object parser, Class<?> p, int depth) throws ReflectiveOperationException, IOException {
        if (depth > 8) throw new IOException("Snapshot provenance is too deeply nested");
        String token = String.valueOf(p.getMethod("currentToken").invoke(parser));
        if (token.equals("START_OBJECT")) {
            Map<String,Object> map = new LinkedHashMap<>();
            while ("FIELD_NAME".equals(String.valueOf(p.getMethod("nextToken").invoke(parser)))) {
                if (map.size() >= 64) throw new IOException("Too many provenance fields");
                String name = (String) p.getMethod("getCurrentName").invoke(parser);
                if (name.length() > 256) throw new IOException("Provenance field name too long");
                p.getMethod("nextToken").invoke(parser); map.put(name, headerValue(parser, p, depth+1));
            }
            return map;
        }
        if (token.equals("START_ARRAY")) {
            List<Object> list = new ArrayList<>();
            while (!"END_ARRAY".equals(String.valueOf(p.getMethod("nextToken").invoke(parser)))) {
                if (list.size() >= 64) throw new IOException("Too many provenance values");
                list.add(headerValue(parser, p, depth+1));
            }
            return list;
        }
        if (!token.startsWith("VALUE_")) throw new IOException("Invalid provenance JSON");
        String text = (String) p.getMethod("getText").invoke(parser);
        if (text.length() > 8192) throw new IOException("Provenance field too long");
        return text;
    }
}
