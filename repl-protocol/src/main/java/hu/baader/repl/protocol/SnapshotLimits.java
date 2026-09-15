package hu.baader.repl.protocol;

/** Serialized UTF-8 envelope limits, including metadata. Large files stay off the wire. */
public final class SnapshotLimits {
    public static final int MAX_BYTES = 200 * 1024 * 1024;
    public static final int INLINE_JSON_BYTES = 2 * 1024 * 1024;
    private SnapshotLimits() {}
}
