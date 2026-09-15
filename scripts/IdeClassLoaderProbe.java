import java.net.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Checks separate platform, Java-plugin and REPL classloaders against a supplied IDE distribution. */
public final class IdeClassLoaderProbe {
    private static URL[] jars(Path root) throws Exception {
        try(var paths=Files.walk(root)) {
            List<URL> urls=new ArrayList<>();
            for(Path path:paths.filter(p->p.toString().endsWith(".jar")).sorted().toList()) urls.add(path.toUri().toURL());
            return urls.toArray(URL[]::new);
        }
    }
    public static void main(String[] args) throws Exception {
        Path ide=Path.of(args[0]),plugin=Path.of(args[1]);
        try(var platform=new URLClassLoader(jars(ide.resolve("lib")),ClassLoader.getPlatformClassLoader());
            var java=new URLClassLoader(jars(ide.resolve("plugins/java/lib")),platform);
            var repl=new URLClassLoader(jars(plugin),java)) {
            Class<?> required=java.loadClass("com.intellij.execution.RunConfigurationExtension");
            Class<?> extension=repl.loadClass("hu.baader.repl.runner.SpringBootReplRunConfigurationExtension");
            if(!required.isInstance(extension.getConstructor().newInstance())) throw new AssertionError("Extension classloader/superclass mismatch");
            Class<?> application=java.loadClass("com.intellij.execution.application.ApplicationConfiguration");
            if(!application.isAssignableFrom(repl.loadClass("hu.baader.repl.runner.SpringBootReplRunConfiguration"))) throw new AssertionError("ApplicationConfiguration mismatch");
            if(repl.loadClass("com.intellij.ide.highlighter.JavaFileType")!=java.loadClass("com.intellij.ide.highlighter.JavaFileType")) throw new AssertionError("JavaFileType mismatch");
            Class<?> confidence=repl.loadClass("hu.baader.repl.editor.ReplCompletionConfidence");
            if(!java.loadClass("com.intellij.codeInsight.completion.CompletionConfidence").isAssignableFrom(confidence)) throw new AssertionError("Completion extension mismatch");
            confidence.getConstructor().newInstance(); confidence.getDeclaredMethods();
            repl.loadClass("hu.baader.repl.editor.ReplCompletionController").getDeclaredMethods();
            repl.loadClass("hu.baader.repl.editor.ReplDiagnosticsController").getDeclaredMethods();
            repl.loadClass("hu.baader.repl.ui.StructuredValuePanel").getDeclaredMethods();
            Class<?> display = repl.loadClass("hu.baader.repl.ui.ValueDisplay");
            display.getDeclaredMethods();
            Object companion = display.getField("Companion").get(null);
            Class<?> tree = repl.loadClass("hu.baader.repl.protocol.ValueTree");
            Object parsed = companion.getClass().getMethod("parseJson", String.class).invoke(companion, "{\"probe\":[12345678901234567890,null]}");
            String formatted = (String)companion.getClass().getMethod("pretty", tree).invoke(companion, parsed);
            if (!formatted.contains("\n  \"probe\": [\n    12345678901234567890,")) throw new AssertionError("IDE JSON formatter/classloader mismatch");
            repl.loadClass("hu.baader.repl.ui.SnapshotTriggerPanel").getDeclaredMethods();
            repl.loadClass("hu.baader.repl.ui.SnapshotDiffPanel").getDeclaredMethods();
            for(String name:List.of("InspectorPanel","RuntimeEventsPanel","SnapshotCasesPanel","InteractiveDebuggerPanel"))
                repl.loadClass("hu.baader.repl.ui."+name).getDeclaredMethods();
            repl.loadClass("hu.baader.repl.debug.DebugTransfer").getDeclaredMethods();
            for(String name:List.of("McpHttpServer","McpRouter","McpBackend","NreplMcpBackend","McpTools","McpPanel","McpService"))
                repl.loadClass("hu.baader.repl.mcp."+name).getDeclaredMethods();
            if(repl.loadClass("com.sun.net.httpserver.HttpServer") != ClassLoader.getPlatformClassLoader().loadClass("com.sun.net.httpserver.HttpServer"))
                throw new AssertionError("MCP HTTP server must use the bundled JDK module");
            repl.loadClass("hu.baader.repl.actions.TraceMethodAction").getDeclaredMethods();
            Class<?> help = repl.loadClass("hu.baader.repl.help.ReplHelp");
            help.getDeclaredMethods();
            Class<?> helpAction = repl.loadClass("hu.baader.repl.actions.OpenReplHelpAction");
            if (!platform.loadClass("com.intellij.openapi.project.DumbAware").isInstance(helpAction.getConstructor().newInstance()))
                throw new AssertionError("PDF help must remain available during indexing");
            try(var input = help.getResourceAsStream((String)help.getField("RESOURCE").get(null))) {
                if (input == null) throw new AssertionError("Bundled PDF guide is missing");
                byte[] guide = input.readAllBytes();
                if (!new String(guide, 0, 5, StandardCharsets.US_ASCII).equals("%PDF-")) throw new AssertionError("Invalid PDF resource");
                if (args.length > 2) {
                    var extract = Arrays.stream(help.getDeclaredMethods())
                        .filter(m -> m.getName().startsWith("extract") && Arrays.equals(m.getParameterTypes(), new Class<?>[]{Path.class})).findFirst().orElseThrow();
                    Path extracted = (Path)extract.invoke(help.getField("INSTANCE").get(null), Path.of(args[2]));
                    if (!Arrays.equals(guide, Files.readAllBytes(extracted))) throw new AssertionError("Plugin JAR PDF extraction differs from the resource");
                }
            }
            extension.getDeclaredMethods();
            repl.loadClass("hu.baader.repl.ui.JavaReplToolWindowFactory").getDeclaredMethods();
            if(extension.getClassLoader()!=repl) throw new AssertionError("Plugin loaded outside its own loader");
            System.out.println("CLASSLOADER_OK " + ide);
        }
    }
}
