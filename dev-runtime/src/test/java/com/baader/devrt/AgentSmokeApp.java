package hu.baader.repl.fixture;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods=false)
public class AgentSmokeApp {
    public record Greeting(String text) {}
    @Bean public Greeting greeting() { return new Greeting("hello"); }
    @Bean public Integer answer() { return 42; }
    public static void main(String[] args) throws Exception {
        SpringApplication application = new SpringApplication(AgentSmokeApp.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setLogStartupInfo(false);
        try (var context = application.run("--spring.profiles.active=test-repl")) {
            Class<?> holder = Class.forName("com.baader.devrt.SpringContextHolder", false, ClassLoader.getSystemClassLoader());
            if (holder.getMethod("get").invoke(null) != context) throw new AssertionError("Agent did not capture the actual Spring context");
            if (!context.getEnvironment().acceptsProfiles(org.springframework.core.env.Profiles.of("test-repl"))) throw new AssertionError("Profile was lost");
            try (var repl = new com.baader.devrt.JShellSession(context)) {
                var response = repl.eval("ctx.getBean(hu.baader.repl.fixture.AgentSmokeApp.Greeting.class).text()");
                if (!response.error().isEmpty() || !"hello".equals(repl.value(null, "last1"))) throw new AssertionError(response);
                var object = repl.eval("ctx.getBean(hu.baader.repl.fixture.AgentSmokeApp.Greeting.class)");
                var tree = hu.baader.repl.protocol.ValueTree.decode((String)object.presentation().get("view-data"));
                if (tree.children().stream().noneMatch(field -> field.label().equals("text") && field.text().equals("hello")))
                    throw new AssertionError("Packaged agent did not format the actual bean: " + tree);
                var analysis = repl.analyze("ctx.getBean(hu.baader.repl.fixture.AgentSmokeApp.Greeting.class).text(); System.setProperty(\"sb.repl.test.packaged-analysis\", \"executed\");");
                if (!"".equals(analysis.get("diagnostics")) || System.getProperty("sb.repl.test.packaged-analysis") != null)
                    throw new AssertionError("Packaged analysis executed code or lost application types: " + analysis);
                if (!repl.analyze("ctx.missingMethod()").get("diagnostics").toString().contains("missingMethod"))
                    throw new AssertionError("Packaged analysis did not report a type error");
                if (repl.value(null, "last1") != context.getBean(Greeting.class)) throw new AssertionError("Analysis replaced the last result");
                System.out.println("AGENT_PRESENTATION_ANALYSIS_OK");
            }
            var existing = new HotSwapFixture();
            if (existing.value() != 42) throw new AssertionError("Original class was changed before HotSwap");
            try (var handler = new com.baader.devrt.ReplHandler()) {
                String session = handler.createSession();
                var traceFixture = new TraceFixture();
                for (String method : java.util.List.of("add", "nested", "ping", "fail")) {
                    var configured = handler.handle("trace/configure", java.util.Map.of("session", session, "class", TraceFixture.class.getName(), "method", method));
                    if (hu.baader.repl.protocol.ReplProtocol.error(configured)) throw new AssertionError(configured);
                }
                if (traceFixture.add(1)!=2 || traceFixture.add(2,3)!=5 || traceFixture.nested(3)!=4) throw new AssertionError("Tracing changed results");
                traceFixture.ping();
                try { traceFixture.fail(); throw new AssertionError("Tracing swallowed an application exception"); }
                catch (IllegalArgumentException expected) { if (!expected.getMessage().equals("expected-trace-error")) throw expected; }
                String events = handler.handle("events/list", java.util.Map.of("session", session)).get("value").toString();
                if (events.lines().count()!=6 || !events.contains("TRACE")) throw new AssertionError("Missing method traces: "+events);
                String event = events.lines().findFirst().orElseThrow().split("\t")[0];
                var inspected = handler.handle("inspector/start", java.util.Map.of("session", session, "event", event));
                if (!inspected.get("value").toString().contains("arguments")) throw new AssertionError(inspected);
                handler.handle("trace/clear", java.util.Map.of("session", session));
                handler.handle("events/clear", java.util.Map.of("session", session));
                traceFixture.add(4);
                if (!handler.handle("events/list", java.util.Map.of("session", session)).get("value").toString().isEmpty()) throw new AssertionError("Stopped trace still captures");
                var recording = handler.handle("trace/record", java.util.Map.of("session", session, "classes", TraceFixture.class.getName()));
                if (hu.baader.repl.protocol.ReplProtocol.error(recording)) throw new AssertionError(recording);
                traceFixture.nested(6); traceFixture.add(2,3);
                var input = new java.util.ArrayList<>(java.util.List.of("before"));traceFixture.mutate(input);input.clear();
                try { traceFixture.fail(); } catch (IllegalArgumentException expected) { }
                var thread = new Thread(() -> traceFixture.add(8), "recorded-other-thread");thread.start();thread.join();
                var history = handler.handle("trace/history", java.util.Map.of("session",session));
                var calls = history.get("value").toString().lines().map(hu.baader.repl.protocol.RecordedCall::decode).toList();
                if(calls.size()!=6) throw new AssertionError("Missing recorded calls: "+history);
                var parent=calls.get(0);var child=calls.get(1);
                if(child.parent()!=parent.id() || child.root()!=parent.id() || !child.descriptor().equals("(I)I") || !calls.get(2).descriptor().equals("(II)I")) throw new AssertionError(calls);
                if(!calls.get(4).status().equals("ERROR") || calls.get(5).parent()!=0 || calls.get(5).threadId()==parent.threadId()) throw new AssertionError(calls);
                var full = handler.handle("trace/call", java.util.Map.of("session",session,"id","request-uuid","recording",recording.get("recording").toString(),"call-id",""+calls.get(3).id()));
                if(hu.baader.repl.protocol.ReplProtocol.error(full))throw new AssertionError(full);
                var mutation=hu.baader.repl.protocol.RecordedCall.decode(full.get("value").toString());
                if(hu.baader.repl.protocol.ValueTree.decode(mutation.input()).children().get(0).children().size()!=1 || hu.baader.repl.protocol.ValueTree.decode(mutation.output()).children().size()!=2) throw new AssertionError("Recorded data was mutated: "+mutation);
                handler.handle("trace/stop",java.util.Map.of("session",session)); traceFixture.add(9);
                if(handler.handle("trace/history",java.util.Map.of("session",session)).get("value").toString().lines().count()!=6) throw new AssertionError("Stopped recording still captures");
                System.out.println("AGENT_RECORDING_OK");
                var tracedReload = handler.handle("trace/configure", java.util.Map.of("session", session, "class", HotSwapFixture.class.getName(), "method", "value"));
                if (hu.baader.repl.protocol.ReplProtocol.error(tracedReload)) throw new AssertionError(tracedReload);
                var reloaded = handler.handle("class-reload", java.util.Map.of("session", session, "code",
                    "package hu.baader.repl.fixture; public class HotSwapFixture { public int value() { return 43; } }"));
                if (hu.baader.repl.protocol.ReplProtocol.error(reloaded) || existing.value() != 43) throw new AssertionError(reloaded);
                var structural = handler.handle("class-reload", java.util.Map.of("session", session, "code",
                    "package hu.baader.repl.fixture; public class HotSwapFixture { public int added; public int value() { return 44; } }"));
                if (!hu.baader.repl.protocol.ReplProtocol.error(structural) || existing.value() != 43) throw new AssertionError("Structural failure changed existing class: " + structural);
                if (!handler.handle("events/list", java.util.Map.of("session", session)).get("value").toString().contains("HotSwapFixture.value")) throw new AssertionError("Reload lost tracing");
            }
            System.out.println("AGENT_TRACE_OK");
            System.out.println("AGENT_HOTSWAP_OK");
            System.out.println("AGENT_SPRING_READY");
            System.out.flush();
            if (args.length > 0 && args[0].equals("wire")) System.in.read();
        }
        if (Class.forName("com.baader.devrt.SpringContextHolder").getMethod("get").invoke(null) != null) throw new AssertionError("Closed context retained");
        System.out.println("AGENT_CONTEXT_RELEASED");
    }
}
