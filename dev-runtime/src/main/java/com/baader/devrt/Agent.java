package com.baader.devrt;

import net.bytebuddy.agent.builder.AgentBuilder;

import java.io.File;
import java.io.IOException;
import java.lang.instrument.Instrumentation;
import java.net.URISyntaxException;
import java.security.CodeSource;
import java.util.jar.JarFile;

public class Agent {
    public static void premain(String agentArgs, Instrumentation inst) {
        setup(agentArgs, inst);
    }

    public static void agentmain(String agentArgs, Instrumentation inst) {
        setup(agentArgs, inst);
    }

    private static void setup(String agentArgs, Instrumentation inst) {
        AgentRuntime.setInstrumentation(inst);

        File agentJarFile = getAgentJarFile();
        if (agentJarFile == null) {
            System.err.println("[dev-runtime] Could not determine agent JAR location. Context auto-binding will fail.");
            startNreplServer(agentArgs); // Start server anyway
            return;
        }
        System.out.println("[dev-runtime] Agent JAR located at: " + agentJarFile.getAbsolutePath());

        // Strategy 1: Make agent classes available to the system.
        // This is crucial for the manual "Bind" action via JShell to find AutoBinder.
        try {
            inst.appendToSystemClassLoaderSearch(new JarFile(agentJarFile));
            System.out.println("[dev-runtime] Agent JAR added to system class path.");
        } catch (IOException e) {
            System.err.println("[dev-runtime] Failed to add agent JAR to system class path.");
        }

        // Strategy 2: Use InjectionStrategy for the transformer.
        // This is more robust for the transformation process itself.
        System.out.println("[dev-runtime] Installing context transformer with injection strategy...");
        new AgentBuilder.Default()
                .with(AgentBuilder.RedefinitionStrategy.RETRANSFORMATION)
                .with(new AgentBuilder.InjectionStrategy.UsingInstrumentation(inst, agentJarFile))
                .with(new Slf4jAgentBuilderListener()) // Use SLF4J for logging
                .type(ContextCapturingTransformer.MATCHER)
                .transform(ContextCapturingTransformer.TRANSFORMER)
                .installOn(inst);
        System.out.println("[dev-runtime] Transformer installed.");

        // Start background auto-bind attempts (ContextLoader, LiveBeansView, static scan, JMX).
        AutoBinder.scheduleAutoBind();

        // Start the nREPL server
        startNreplServer(agentArgs);
    }

    private static File getAgentJarFile() {
        try {
            CodeSource codeSource = Agent.class.getProtectionDomain().getCodeSource();
            if (codeSource != null) {
                return new File(codeSource.getLocation().toURI().getSchemeSpecificPart());
            }
        } catch (URISyntaxException e) {
            System.err.println("[dev-runtime] Failed to get agent JAR location.");
            e.printStackTrace();
        }
        return null;
    }

    private static void startNreplServer(String agentArgs) {
        int port = 5557;
        try {
            if (agentArgs != null) {
                for (String part : agentArgs.split(",")) {
                    String[] kv = part.split("=", 2);
                    if (kv.length == 2 && kv[0].trim().equals("port")) {
                        port = Integer.parseInt(kv[1].trim());
                    }
                }
            }
        } catch (Throwable ignored) {}

        final int finalPort = port;
        Thread t = new Thread(() -> {
            try {
                MiniNreplServer server = new MiniNreplServer(finalPort);
                server.start();
                System.out.println("[dev-runtime] nREPL server started on port " + finalPort);
            } catch (Throwable t1) {
                t1.printStackTrace();
            }
        }, "dev-runtime-server");
        t.setDaemon(true);
        t.start();
    }
}
