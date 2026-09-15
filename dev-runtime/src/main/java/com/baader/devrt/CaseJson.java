package com.baader.devrt;

import java.util.*;

/** Plain JSON only; assertion metadata must not invoke application codecs or polymorphic constructors. */
final class CaseJson {
    static Object mapper() {
        try {
            ClassLoader loader=AppClassPath.loader(SpringContextHolder.get());
            Class<?> type=Class.forName("com.fasterxml.jackson.databind.ObjectMapper",true,loader);
            Object mapper=type.getConstructor().newInstance();
            Class<?> feature=Class.forName("com.fasterxml.jackson.databind.DeserializationFeature",true,loader);
            for(String flag:List.of("FAIL_ON_TRAILING_TOKENS","FAIL_ON_READING_DUP_TREE_KEY","USE_BIG_DECIMAL_FOR_FLOATS"))
                type.getMethod("configure",feature,boolean.class).invoke(mapper,feature.getField(flag).get(null),true);
            Class<?> parserFeature=Class.forName("com.fasterxml.jackson.core.JsonParser$Feature",true,loader);
            type.getMethod("configure",parserFeature,boolean.class).invoke(mapper,parserFeature.getField("STRICT_DUPLICATE_DETECTION").get(null),true);
            try {
                Class<?> constraints=Class.forName("com.fasterxml.jackson.core.StreamReadConstraints",true,loader);
                Object builder=constraints.getMethod("builder").invoke(null);
                builder.getClass().getMethod("maxStringLength",int.class).invoke(builder,hu.baader.repl.protocol.SnapshotLimits.MAX_BYTES);
                Object factory=type.getMethod("getFactory").invoke(mapper);
                factory.getClass().getMethod("setStreamReadConstraints",constraints).invoke(factory,builder.getClass().getMethod("build").invoke(builder));
            } catch(ClassNotFoundException ignored) { /* Older Jackson uses its parser constraints. */ }
            return mapper;
        } catch(ReflectiveOperationException failure){throw new IllegalArgumentException("Jackson is required for CASE metadata",failure);}
    }
    static Object parse(String text) { return parse(text,65536); }
    static Object parse(String text,int max) {
        if(text.length()>max)throw new IllegalArgumentException("CASE JSON exceeds 64 KiB");
        try {Object mapper=mapper();Object value=mapper.getClass().getMethod("readValue",String.class,Class.class).invoke(mapper,text,Object.class); depth(value,0);return value;}
        catch(ReflectiveOperationException failure){throw new IllegalArgumentException("Invalid CASE JSON",failure);}
    }
    private static void depth(Object value,int depth){if(depth>64)throw new IllegalArgumentException("CASE JSON nesting exceeds 64");if(value instanceof Map<?,?> m)m.values().forEach(v->depth(v,depth+1));else if(value instanceof List<?> l)l.forEach(v->depth(v,depth+1));}
    @SuppressWarnings("unchecked") static Map<String,Object> object(String text){Object value=parse(text.isBlank()?"{}":text);if(!(value instanceof Map<?,?>))throw new IllegalArgumentException("Expected a JSON object");return (Map<String,Object>)value;}
    static String write(Object value){try{Object mapper=mapper();return (String)mapper.getClass().getMethod("writeValueAsString",Object.class).invoke(mapper,value);}catch(ReflectiveOperationException e){throw new IllegalArgumentException("Cannot encode CASE JSON",e);}}
}
