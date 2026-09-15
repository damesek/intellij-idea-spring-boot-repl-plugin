package com.baader.devrt;

public final class CaptureWithoutAgentProbe {
    public static void main(String[] args) {
        if(com.baader.sbrepl.bridge.SnapshotHelper.captureLazy("point","case",()->{ throw new AssertionError("Disabled supplier ran"); }))
            throw new AssertionError("Captured without an agent");
    }
}
