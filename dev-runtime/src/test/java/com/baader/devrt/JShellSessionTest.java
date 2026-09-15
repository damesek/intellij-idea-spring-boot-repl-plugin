package com.baader.devrt;

import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class JShellSessionTest {
    private static JShellSession.EvalResult ok(JShellSession s, String code) {
        var result = s.eval(code); assertEquals("", result.error(), code + "\n" + result.error()); return result;
    }
    @Test void importsMethodsDefinitionsAndMultipleSnippetsPersist() {
        try (var s = new JShellSession(null)) {
            ok(s, "import java.math.BigDecimal; BigDecimal amount = new BigDecimal(\"3.5\"); int add(int n) { return n + 1; }");
            assertEquals(List.of("42", "4.5"), ok(s, "add(41); amount.add(BigDecimal.ONE)").values());
            assertEquals(new java.math.BigDecimal("3.5"), s.value(null, "amount"));
        }
    }
    @Test void redeclarationAndLastValuesWorkWithoutReset() {
        try (var s = new JShellSession(null)) {
            ok(s, "int value = 1;"); ok(s, "int value = 2;"); ok(s, "value + 1");
            assertEquals(3, s.value(null, "last1")); assertEquals(2, s.value(null, "last2")); assertEquals(1, s.value(null, "last3"));
            assertTrue(s.variables().contains("value\tint\t2"));
        }
    }
    @Test void outputAndErrorsAreNotReportedAsSuccessfulValues() {
        try (var s = new JShellSession(null)) {
            var result = s.eval("System.out.println(\"árvíztűrő 🧪\"); System.err.print(\"warning\"); 42; missingMethod()");
            assertTrue(result.output().contains("árvíztűrő 🧪")); assertEquals("warning", result.stderr());
            assertEquals(List.of("42"), result.values()); assertTrue(result.error().contains("cannot find symbol"));
            assertNotNull(s.value(null, "lastError"));
            assertFalse(s.eval("throw new IllegalStateException(\"expected\");").error().isEmpty());
        }
    }
    @Test void stringsCommentsAndIncompleteSnippetsUseJavaParser() {
        try (var s = new JShellSession(null)) {
            assertEquals(List.of("\"a;b\""), ok(s, "/* ; */ String text = \"a;b\"; // ;").values());
            assertFalse(s.eval("if (true) {").error().isEmpty());
            ok(s, "int n = 0; for (int i = 0; i < 3; i++) { n += i; } n");
            assertEquals(3, s.value(null, "n"));
        }
    }
    @Test void resetAndIndependentSessionsDoNotShareDefinitions() {
        try (var first = new JShellSession(null); var second = new JShellSession(null)) {
            ok(first, "int unique = 41;");
            assertTrue(second.eval("unique").error().contains("cannot find symbol"));
            ok(second, "int unique = 7;"); assertEquals(41, first.value(null, "unique"));
        }
        try (var fresh = new JShellSession(null)) { assertFalse(fresh.eval("unique").error().isEmpty()); }
    }
    @Test void bindingPreservesDefinitionsAndContextIdentity() {
        try (var ctx = new GenericApplicationContext(); var s = new JShellSession(null)) {
            ctx.refresh(); ok(s, "int existing = 42;"); s.bindContext(ctx);
            assertSame(ctx, s.value(null, "ctx"));
            assertEquals(List.of("42"), ok(s, "existing").values());
            assertEquals(List.of("true"), ok(s, "((org.springframework.context.ConfigurableApplicationContext)ctx).isActive()").values());
            assertThrows(IllegalStateException.class, () -> s.bindContext(new GenericApplicationContext()));
        }
    }
    @Test void handlesRetainRealObjectsAndRejectUnknownExpressions() {
        try (var s = new JShellSession(null)) {
            var first = ok(s, "var list = new ArrayList<String>(); list.add(\"a\"); list");
            Object object = s.value(first.handle(), null);
            ok(s, "list.add(\"b\"); 99");
            assertSame(object, s.value(first.handle(), null)); assertEquals(List.of("a", "b"), object);
            assertThrows(IllegalArgumentException.class, () -> s.value(null, "list.clear()"));
            assertThrows(IllegalArgumentException.class, () -> s.value("unknown", null));
        }
    }
    @Test void inspectorCompletionAndDropUseActualSessionState() {
        try (var s = new JShellSession(null)) {
            ok(s, "var numbers = java.util.stream.IntStream.range(0, 120).boxed().toList();");
            String page = s.inspect(null, "numbers", 50);
            assertTrue(page.contains("50: 50")); assertFalse(page.contains("0: 0\n")); assertTrue(page.contains("Elements: 120"));
            assertTrue(s.complete("numbers.si", 10).stream().anyMatch(x -> x.contains("size(")));
            s.drop("numbers"); assertFalse(s.eval("numbers").error().isEmpty());
        }
    }
    @Test void completionAfterMultipleStatementsDoesNotRunEarlierCode() {
        try(var context=new GenericApplicationContext();var shell=new JShellSession(null)) {
            context.refresh();shell.bindContext(context);ok(shell,"int calls=0;");
            String code="++calls;\nctx.getB";
            var suggestions=shell.complete(code,code.length());
            assertTrue(suggestions.stream().anyMatch(s->s.startsWith(code.indexOf("getB")+"\tgetBean(")),suggestions.toString());
            assertEquals(List.of("0"),ok(shell,"calls").values());
            assertThrows(IllegalArgumentException.class,()->shell.complete("x",2));
        }
    }
    @Test void largeOutputIsBounded() {
        try (var s = new JShellSession(null)) {
            var result = ok(s, "System.out.print(\"x\".repeat(100000));");
            assertTrue(result.output().length() < 66000); assertTrue(result.output().contains("[truncated]"));
        }
    }
    @Test void largeStringPreviewKeepsTheFullValueUsable() {
        try (var s = new JShellSession(null)) {
            String value = "\n\t\\\"".repeat(3_000_000);
            s.bindValue("largeText", value, "java.lang.String");
            var result = ok(s, "largeText");
            assertTrue(result.values().get(0).length() < 66000);
            assertSame(value, s.value(result.handle(), null));
            assertEquals(List.of(Integer.toString(value.length())), ok(s, "largeText.length()").values());
        }
    }
    @Test void interruptDoesNotWaitForEvalLock() throws Exception {
        try (var s = new JShellSession(null)) {
            var executor = Executors.newSingleThreadExecutor();
            try {
                var entered = new CountDownLatch(1);
                s.bindValue("entered", entered, "java.util.concurrent.CountDownLatch");
                Future<JShellSession.EvalResult> result = executor.submit(() -> s.eval("entered.countDown(); Thread.sleep(60000);"));
                assertTrue(entered.await(10, TimeUnit.SECONDS));
                assertTimeout(Duration.ofSeconds(1), s::interrupt);
                assertFalse(result.get(10, TimeUnit.SECONDS).error().isEmpty());
            } finally { executor.shutdownNow(); }
        }
    }
    @org.junit.jupiter.api.Test void notebookSymbolAnalysisDoesNotExecuteOrInstallDeclarations() {
        try (JShellSession session = new JShellSession(null)) {
            var counter = new java.util.concurrent.atomic.AtomicInteger();
            session.bindValue("counter", counter, "java.util.concurrent.atomic.AtomicInteger");
            var symbols = session.symbols("int dangerous = counter.incrementAndGet(); int another = 2;");
            org.junit.jupiter.api.Assertions.assertEquals(0, counter.get());
            org.junit.jupiter.api.Assertions.assertEquals("false", symbols.get("analysis-executed").toString());
            org.junit.jupiter.api.Assertions.assertTrue(symbols.get("declared-symbols").toString().contains("dangerous"));
            org.junit.jupiter.api.Assertions.assertTrue(Boolean.TRUE.equals(symbols.get("conservative")));
            org.junit.jupiter.api.Assertions.assertFalse(session.variables().contains("dangerous"));
        }
    }
}
