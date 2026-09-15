package hu.baader.repl.fixture;

import com.baader.devrt.SnapshotManager;
import java.util.*;

/** The JDI test captures the initialized local immediately before its mutation. */
public final class SnapshotBreakpointProbe {
    public static void main(String[] args) throws Exception {
        Class.forName("com.baader.devrt.SnapshotBreakpoint");
        work();
    }
    public static void work() {
        List<String> input = new ArrayList<>(List.of("at-hit"));
        input.add("after-hit");
        if (!List.of("at-hit").equals(SnapshotManager.load("line-input"))) throw new AssertionError("The snapshot did not preserve the hit-time local");
        System.out.println("SNAPSHOT_LINE_AND_RESUME_OK");
    }
}
