package hu.baader.repl.protocol;

import java.util.regex.Pattern;

/** Best-effort text redaction. Does not infer all personal data or hide secrets under arbitrary field names. */
public final class SensitiveValues {
    private static final Pattern NAMED = Pattern.compile("(?i)([\\\"']?(?:password|passwd|secret|token|api[_-]?key|authorization|cookie|client[_-]?secret)[\\\"']?\\s*[:=]\\s*)(?:\\\"[^\\\"]*\\\"|'[^']*'|[^\\s,;}&]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\b(?:bearer|basic)\\s+[A-Za-z0-9+/_.=:-]{4,}");
    private static final Pattern JWT = Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b");
    private SensitiveValues() {}
    public static String redact(String value) {
        String text = value == null ? "" : value;
        text = BEARER.matcher(text).replaceAll("[REDACTED authorization]");
        text = NAMED.matcher(text).replaceAll("$1\"[REDACTED]\"");
        return JWT.matcher(text).replaceAll("[REDACTED token]");
    }
    public static boolean detected(String value) { return value != null && !redact(value).equals(value); }
    public static boolean sensitiveName(String name) {
        return name != null && name.toLowerCase(java.util.Locale.ROOT).matches(".*(password|passwd|secret|token|authorization|cookie|api[-_]?key).*");
    }
}
