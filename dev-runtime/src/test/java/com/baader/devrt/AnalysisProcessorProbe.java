package hu.baader.repl.fixture;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.TypeElement;
import java.util.Set;

/** Must not even be instantiated by the live checker, despite a service entry on the compilation path. */
public final class AnalysisProcessorProbe extends AbstractProcessor {
    public AnalysisProcessorProbe() { System.setProperty("sb.repl.test.analysis-processor", "created"); }
    @Override public Set<String> getSupportedAnnotationTypes() { return Set.of("*"); }
    @Override public SourceVersion getSupportedSourceVersion() { return SourceVersion.latestSupported(); }
    @Override public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment environment) {
        System.setProperty("sb.repl.test.analysis-processor", "processed"); return false;
    }
}
