#!/usr/bin/env python3
"""Verify both Maven and Gradle agent distributions before they can be released."""
import io
import sys
from zipfile import ZipFile

with ZipFile(sys.argv[1]) as jar:
    files = set(jar.namelist())
    manifest = jar.read("META-INF/MANIFEST.MF").decode()
    for attribute in ("Premain-Class: com.baader.devrt.Agent", "Agent-Class: com.baader.devrt.Agent",
                      "Can-Retransform-Classes: true", "SB-Repl-Protocol: 1"):
        assert attribute in manifest, attribute
    for required in ("com/baader/devrt/Agent.class", "com/baader/devrt/AgentInstrumentation.class",
                     "com/baader/devrt/JShellSession.class", "hu/baader/repl/protocol/Bencode.class",
                     "com/baader/devrt/ObjectInspector.class", "com/baader/devrt/RuntimeEvents.class",
                     "com/baader/devrt/TraceRecorder.class", "com/baader/devrt/DebugBridge.class",
                     "com/baader/devrt/SnapshotCases.class", "com/baader/devrt/SourceAnalyzer.class",
                     "com/baader/devrt/ValuePresentation.class", "hu/baader/repl/protocol/ValueTree.class",
                     "com/baader/devrt/HibernateRecorder.class", "com/baader/devrt/HibernateAccess.class",
                     "com/baader/devrt/CaseHibernateProbe.class", "com/baader/devrt/SqlRecorder.class",
                     "com/baader/devrt/CallExperiments.class", "com/baader/devrt/DataCopies.class",
                     "com/baader/devrt/BeanExplorer.class", "com/baader/devrt/InvocationPlan.class",
                     "com/baader/devrt/SessionWatches.class", "com/baader/devrt/CaseRegression.class",
                     "com/baader/devrt/AsyncRecorder.class", "com/baader/devrt/bootstrap/AsyncBridge.class",
                     "com/baader/devrt/ReplNotifications.class", "com/baader/devrt/ReplSession.class",
                     "com/baader/devrt/CaseOperations.class",
                     "hu/baader/repl/protocol/HibernateSnapshot.class", "hu/baader/repl/protocol/HibernateObservation.class",
                     "hu/baader/repl/protocol/SqlSnapshot.class", "hu/baader/repl/protocol/SqlObservation.class",
                     "case-export/CaseHibernateProbe.java", "case-export/CaseSqlCounter.java", "case-export/SqlText.java"):
        assert required in files, required
    libraries = [name for name in files if name.startswith("agent-libs/byte-buddy-") and name.endswith(".jar")]
    assert libraries, "Private Byte Buddy JAR missing"
    assert not any(name.startswith(("net/bytebuddy/", "org/springframework/", "org/slf4j/", "org/hibernate/")) for name in files)
    for library in libraries:
        with ZipFile(io.BytesIO(jar.read(library))) as nested:
            assert nested.testzip() is None, library
    assert jar.testzip() is None
print("Agent package verified:", sys.argv[1])
