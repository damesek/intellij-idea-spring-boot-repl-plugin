package hu.baader.repl.protocol;

import java.nio.charset.StandardCharsets;
import java.util.*;

/** Frozen display evidence for one invocation. Never an executable object or a restored JVM frame. */
public record RecordedCall(String recording, long id, long parent, long root, String className, String method,
        String descriptor, String parameterNames, long threadId, String threadName, long startedAt, long durationNanos,
        String status, String summary, String input, String output, String exception, long event, long revision) {
    public static final int MAX_CALLS = 200, MAX_VALUE = 262_144;
    public RecordedCall {
        if (recording == null || !recording.matches("[a-f0-9-]{36}") || id < 1 || parent < 0 || parent >= id || root < 1 || root > id
                || startedAt < 0 || durationNanos < 0 || revision < 1 || threadId < 0 || event < -1)
            throw new IllegalArgumentException("Invalid recorded call identity");
        if (className == null || !className.matches("[\\w$]+(?:\\.[\\w$]+)*") || className.length() > 512
                || method == null || !method.matches("[\\w$]+") || method.length() > 256
                || !Set.of("RUNNING", "SUCCESS", "ERROR", "INCOMPLETE").contains(status))
            throw new IllegalArgumentException("Invalid recorded method");
        for (String text : List.of(descriptor, parameterNames, threadName, summary))
            if (text.length() > 4096) throw new IllegalArgumentException("Recorded metadata too large");
        for (String value : List.of(input, output, exception))
            if (value.length() > MAX_VALUE) throw new IllegalArgumentException("Recorded value too large");
    }
    public RecordedCall header() {
        return new RecordedCall(recording,id,parent,root,className,method,descriptor,parameterNames,threadId,threadName,
                startedAt,durationNanos,status,summary,"","","",event,revision);
    }
    public String encode() {
        return String.join("\t", List.of("call-v1", recording, ""+id, ""+parent, ""+root, b64(className), b64(method), b64(descriptor),
                b64(parameterNames), ""+threadId, b64(threadName), ""+startedAt, ""+durationNanos, status, b64(summary),
                b64(input), b64(output), b64(exception), ""+event, ""+revision));
    }
    public static RecordedCall decode(String wire) {
        if (wire == null || wire.length() > 1_100_000) throw new IllegalArgumentException("Recorded call too large");
        String[] p = wire.split("\t", -1);
        if (p.length != 20 || !p[0].equals("call-v1")) throw new IllegalArgumentException("Invalid recorded call");
        return new RecordedCall(p[1], n(p[2]), n(p[3]), n(p[4]), text(p[5]), text(p[6]), text(p[7]), text(p[8]), n(p[9]), text(p[10]),
                n(p[11]), n(p[12]), p[13], text(p[14]), text(p[15]), text(p[16]), text(p[17]), n(p[18]), n(p[19]));
    }
    private static long n(String value) { return Long.parseLong(value); }
    private static String b64(String value) { return Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)); }
    private static String text(String value) { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
}
