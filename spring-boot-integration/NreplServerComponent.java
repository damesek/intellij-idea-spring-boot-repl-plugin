package com.example.springboot.nrepl;

import clojure.java.api.Clojure;
import clojure.lang.IFn;
import clojure.lang.RT;
import nrepl.server.Server;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.stereotype.Component;

import javax.annotation.PreDestroy;

/**
 * Spring Boot komponens, ami elindítja az nREPL szervert Java middleware támogatással.
 * Használja a valódi nREPL szervert és egy middleware-t, ami a //!java kódot kezeli.
 */
@Component
public class NreplServerComponent implements CommandLineRunner, ApplicationContextAware {

    @Value("${nrepl.port:5557}")
    private int port;

    @Value("${nrepl.host:127.0.0.1}")
    private String host;

    @Value("${nrepl.enabled:true}")
    private boolean enabled;

    private volatile Server server;
    private volatile ApplicationContext applicationContext;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
        // Tegyük el az ApplicationContext-et a REPL kód számára
        EvalEnvironment.setApplicationContext(applicationContext);
    }

    @Override
    public void run(String... args) {
        if (enabled) {
            startNreplServer();
        }
    }

    private void startNreplServer() {
        try {
            System.out.println("Starting nREPL server on " + host + ":" + port);

            // Betöltjük a middleware Clojure forrást a classpath-ról
            // Helyezd el a fájlt: src/main/resources/com/example/springboot/nrepl/java_middleware.clj
            RT.loadResourceScript("com/example/springboot/nrepl/java_middleware.clj");

            // nREPL start
            IFn startServer = Clojure.var("nrepl.server", "start-server");
            IFn defaultHandler = Clojure.var("nrepl.server", "default-handler");

            // A middleware függvény var-ja
            IFn javaMw = Clojure.var("com.example.springboot.nrepl.java-middleware", "java-eval-middleware");

            // Handler összeállítása (default-handler + saját middleware)
            Object handler = defaultHandler.invoke(javaMw);

            // Szerver indítása
            server = (Server) startServer.invoke(
                    Clojure.read(":port"), port,
                    Clojure.read(":bind"), host,
                    Clojure.read(":handler"), handler
            );

            System.out.println("✅ nREPL server started successfully on port " + port);
            System.out.println("   Java code evaluation enabled (prefix with //!java)");

        } catch (Exception e) {
            System.err.println("❌ Failed to start nREPL server: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @PreDestroy
    public void shutdown() {
        Server s = this.server;
        if (s != null) {
            try {
                s.close();
                System.out.println("nREPL server stopped");
            } catch (Exception e) {
                System.err.println("Error stopping nREPL server: " + e.getMessage());
            }
        }
    }
}
