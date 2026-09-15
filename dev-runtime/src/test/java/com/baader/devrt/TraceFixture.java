package hu.baader.repl.fixture;

public final class TraceFixture {
    public int add(int value) { return value+1; }
    public int add(int a,int b) { return a+b; }
    public int nested(int value) { return add(value); }
    public void ping() {}
    public int fail() { throw new IllegalArgumentException("expected-trace-error"); }
    public java.util.List<String> mutate(java.util.List<String> input) { input.add("after"); return input; }
    public int pause(long millis) throws InterruptedException { Thread.sleep(millis); return 42; }
}
