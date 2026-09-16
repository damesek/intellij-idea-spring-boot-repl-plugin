package com.baader.devrt;

import java.util.*;
import java.util.function.Consumer;

/** Explicit case execution in a fresh JShell, using the current application's real Spring beans. */
final class SnapshotCases {
    static final List<String> HIBERNATE_FIELDS=List.of("max-hibernate-loads","max-hibernate-flushes","max-hibernate-lazy-loads","max-hibernate-response-lazy-loads");
    static boolean hibernateAssertions(Map<String,String> definition){return HIBERNATE_FIELDS.stream().anyMatch(k->!definition.getOrDefault(k,"").isBlank());}
    private static final Set<String> FIELDS=Set.of("input","expected","type","variable","code","expected-exception","expected-message",
            "assertions-json","parameters-json","setup","teardown","result-expression","imports","max-duration-ms","max-sql-count","max-sql-repetitions","max-hibernate-loads","max-hibernate-flushes","max-hibernate-lazy-loads","max-hibernate-response-lazy-loads","tags","disabled","observed-classes");
    static Map<String,String> definition(Map<String,String> request) {
        Map<String,String> value=new LinkedHashMap<>();
        for(String field:FIELDS) value.put(field,request.getOrDefault(field,field.equals("variable")?"input":""));
        SnapshotManager.checkedName(value.get("input")); SnapshotManager.checkedName(value.get("expected"));
        if(!value.get("type").isBlank()) SnapshotManager.checkedType(value.get("type"));
        String variable=value.get("variable");
        if(!javax.lang.model.SourceVersion.isIdentifier(variable) || javax.lang.model.SourceVersion.isKeyword(variable)
                || Set.of("ctx","last1","last2","last3","lastError").contains(variable)) throw new IllegalArgumentException("Choose a valid input variable name");
        if(source(value).isBlank() || source(value).length()>100_000) throw new IllegalArgumentException("Combined CASE source must contain 1–100000 characters");
        if(value.get("code").isBlank()&&value.get("result-expression").isBlank())throw new IllegalArgumentException("Code or result expression is required");
        if (!value.get("expected-exception").isBlank() && !value.get("expected-exception").matches("[\\w.$]{1,256}")) throw new IllegalArgumentException("Invalid expected exception class");
        if (value.get("expected-message").length() > 65536) throw new IllegalArgumentException("Expected message too long");
        if(!value.get("max-duration-ms").isBlank()) {long max=Long.parseLong(value.get("max-duration-ms"));if(max<1||max>120000)throw new IllegalArgumentException("Maximum duration must be 1–120000 ms");}
        for(String field:List.of("max-sql-count","max-sql-repetitions","max-hibernate-loads","max-hibernate-flushes","max-hibernate-lazy-loads","max-hibernate-response-lazy-loads")) if(!value.get(field).isBlank()) {
            long max=Long.parseLong(value.get(field)); if(max<0 || max>1000000) throw new IllegalArgumentException(field+" must be 0–1000000");
        }
        if(!Set.of("","true","false").contains(value.get("disabled")))throw new IllegalArgumentException("disabled must be true or false");
        if(value.get("tags").length()>1024||!value.get("tags").matches("[\\w, .-]*"))throw new IllegalArgumentException("Use comma-separated tags containing letters, digits, dot, underscore or hyphen");
        if(value.get("observed-classes").length()>32768||value.get("observed-classes").lines().filter(s->!s.isBlank()).anyMatch(s->!s.matches("[\\w$]+(?:\\.[\\w$]+)*")))throw new IllegalArgumentException("Observed classes must be exact class names, one per line");
        for(String tag:value.get("tags").split(","))if(!tag.isBlank()&&!tag.trim().matches("[\\w.-]+"))throw new IllegalArgumentException("Tags cannot contain spaces");
        CaseAssertions.validate(CaseJson.object(value.get("assertions-json"))); rows(value);
        return value;
    }
    static String source(Map<String,String> definition) {
        return String.join("\n",List.of("imports","setup","code","result-expression","teardown").stream().map(key->definition.getOrDefault(key,"")).toList());
    }
    static List<Map<String,String>> rows(Map<String,String> definition) {
        String json=definition.getOrDefault("parameters-json","");
        if(json.isBlank())return List.of(Map.of("id","default","input",definition.get("input"),"expected",definition.get("expected")));
        Object parsed=CaseJson.parse(json);
        if(!(parsed instanceof List<?> list)||list.isEmpty()||list.size()>20)throw new IllegalArgumentException("parameters-json must contain 1–20 rows");
        List<Map<String,String>> rows=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(Object item:list) {
            if(!(item instanceof Map<?,?> row)||!Set.of("id","input","expected").equals(row.keySet())||row.values().stream().anyMatch(v->!(v instanceof String)))throw new IllegalArgumentException("Each parameter row requires string id, input and expected");
            String id=row.get("id").toString();SnapshotManager.checkedName(id);
            if(!ids.add(id))throw new IllegalArgumentException("Duplicate parameter row id");
            rows.add(Map.of("id",id,"input",SnapshotManager.checkedName(row.get("input").toString()),"expected",SnapshotManager.checkedName(row.get("expected").toString())));
        }
        return List.copyOf(rows);
    }
    static void requireData(Map<String,String> definition) {
        SnapshotManager.requireData(definition.get("input"));SnapshotManager.requireData(definition.get("expected"));
        for(Map<String,String> row:rows(definition)){SnapshotManager.requireData(row.get("input"));SnapshotManager.requireData(row.get("expected"));}
    }
    static Map<String,Object> run(String owner,String name,Object context,Consumer<JShellSession> active) {
        return run(owner,name,context,active,SnapshotManager.loadCase(name));
    }
    static Map<String,Object> run(String owner,String name,Object context,Consumer<JShellSession> active,Map<String,String> savedDefinition) {
        return run(owner,name,context,active,savedDefinition,()->false);
    }
    static Map<String,Object> run(String owner,String name,Object context,Consumer<JShellSession> active,Map<String,String> savedDefinition,java.util.function.BooleanSupplier stopped) {
        Map<String,String> definition=definition(savedDefinition);
        long start=System.nanoTime(),epoch=SpringContextHolder.epoch();
        Map<String,Object> result=new LinkedHashMap<>();StringBuilder output=new StringBuilder();
        boolean initialized=false,cancelled=false;
        try(JShellSession shell=new JShellSession(context)) {
            active.accept(shell);
            try {
                checkStopped(stopped);
                SnapshotManager.requireData(definition.get("input")); SnapshotManager.requireData(definition.get("expected"));
                String type=definition.get("type");
                Object input=type.isBlank()?SnapshotManager.load(definition.get("input")):SnapshotManager.loadTyped(definition.get("input"),type);
                checkStopped(stopped);
                shell.bindValue(definition.get("variable"),input,type.isBlank()?SnapshotManager.sourceType(definition.get("input"),input):type);
                initialized=true;
                evaluateStage(shell,definition.get("imports"),output,"Imports");
                checkStopped(stopped);
                evaluateStage(shell,definition.get("setup"),output,"Setup");
                checkStopped(stopped);
                long executionStart=System.nanoTime();
                JShellSession.EvalResult evaluation;
                hu.baader.repl.protocol.SqlSnapshot sql=null;
                hu.baader.repl.protocol.HibernateSnapshot hibernate=null;
                boolean ormAssertions=hibernateAssertions(definition);
                boolean sqlAssertions=!definition.get("max-sql-count").isBlank() || !definition.get("max-sql-repetitions").isBlank();
                boolean collectOrm=ormAssertions || new HibernateRecorder.Log(true).snapshot().available();
                try(SqlRecorder.Scope scope=sqlAssertions||ormAssertions||SqlRecorder.available()?SqlRecorder.scope(collectOrm):null) {
                evaluation=shell.eval(definition.get("code"));
                output.append(evaluation.output());
                checkStopped(stopped);
                if(evaluation.error().isEmpty()&&!evaluation.interrupted()&&!definition.get("result-expression").isBlank()) {
                    evaluation=shell.eval(definition.get("result-expression"));output.append(evaluation.output());
                }
                checkStopped(stopped);
                if(scope!=null) {sql=scope.snapshot();if(collectOrm)hibernate=scope.hibernate();}
                }
                long duration=(System.nanoTime()-executionStart)/1_000_000;
                result.put("code-duration-ms",duration);
                result.put("exception-type",evaluation.exceptionType());result.put("exception-message",evaluation.exceptionMessage());
                cancelled=evaluation.interrupted();
                if(epoch!=SpringContextHolder.epoch())throw new IllegalStateException("Spring context changed during the test case; reset the session");
                if(cancelled) {result.put("outcome","CANCELLED");result.put("detail",evaluation.error());}
                else if(!evaluation.error().isEmpty()&&evaluation.exceptionType().isEmpty()) {result.put("outcome","ERROR");result.put("detail",evaluation.error());}
                else if(!definition.get("expected-exception").isBlank()) {
                    boolean passed=definition.get("expected-exception").equals(evaluation.exceptionType())&&definition.get("expected-message").equals(evaluation.exceptionMessage());
                    result.put("outcome",passed?"PASSED":"FAILED");result.put("detail",passed?"Expected exception type and message matched":"Expected exception did not match: "+evaluation.error());
                } else if(!evaluation.error().isEmpty()) {result.put("outcome","ERROR");result.put("detail",evaluation.error());}
                else {
                    if(evaluation.handle().isEmpty())throw new IllegalArgumentException("Test code must produce a value to compare");
                    Object actual=shell.value(evaluation.handle(),null);
                    Object actualData=SnapshotManager.casePayload(actual);
                    result.put("result-sha256",CaseRegression.fingerprint(actualData));
                    Map<String,Object> options=CaseJson.object(definition.get("assertions-json"));
                    if(options.isEmpty()) {
                        result.putAll(SnapshotDiff.compare(SnapshotManager.dataPayload(definition.get("expected")),actualData,0,100));
                        int changes=((Number)result.get("changes-found")).intValue();boolean limited=Boolean.TRUE.equals(result.get("scan-limited"));
                        result.put("outcome",changes>0?"FAILED":limited?"INCONCLUSIVE":"PASSED");
                        result.put("detail",changes>0?"Result differs from the expected snapshot":limited?"Comparison budget reached":"Result matches the expected snapshot");
                    } else {
                        CaseAssertions.Report comparison=CaseAssertions.compare(SnapshotManager.dataPayload(definition.get("expected")),actualData,options);
                        result.put("outcome",comparison.outcome());result.put("detail",comparison.detail());result.put("value",String.join("\n",comparison.failures()));
                    }
                    result.put("event",RuntimeEvents.recordCase(owner,name,System.nanoTime()-start,actual));
                }
                if(sql!=null) {
                    result.put("sql-count",sql.count());result.put("sql-duration-ms",sql.nanos()/1_000_000.0);
                    result.put("sql-max-repetitions",sql.maxRepetitions());result.put("sql-partial",sql.partial());
                    if(sqlAssertions && Set.of("PASSED","FAILED","INCONCLUSIVE").contains(result.get("outcome"))) {
                        boolean exceeded=(!definition.get("max-sql-count").isBlank() && sql.count()>Long.parseLong(definition.get("max-sql-count")))
                            || (!definition.get("max-sql-repetitions").isBlank() && sql.maxRepetitions()>Long.parseLong(definition.get("max-sql-repetitions")));
                        if(exceeded) { result.put("outcome","FAILED"); result.put("detail",result.get("detail")+"; SQL budget exceeded (count="+sql.count()+", max repetition="+sql.maxRepetitions()+")"); }
                        else if(sql.partial() && !"FAILED".equals(result.get("outcome"))) { result.put("outcome","INCONCLUSIVE");result.put("detail",result.get("detail")+"; SQL evidence is incomplete"); }
                    }
                }
                if(hibernate!=null) {
                    long[] measured={hibernate.count("ENTITY_LOAD"),hibernate.flushCount(),hibernate.lazyCount(),hibernate.responseLazyCount()};
                    boolean exceeded=false;
                    for(int i=0;i<HIBERNATE_FIELDS.size();i++) {
                        String key=HIBERNATE_FIELDS.get(i);result.put(key.substring(4),measured[i]);
                        if(!definition.get(key).isBlank()&&measured[i]>Long.parseLong(definition.get(key)))exceeded=true;
                    }
                    result.put("hibernate-partial",hibernate.partial());
                    if(ormAssertions && Set.of("PASSED","FAILED","INCONCLUSIVE").contains(result.get("outcome"))) {
                        if(exceeded){result.put("outcome","FAILED");result.put("detail",result.get("detail")+"; Hibernate budget exceeded");}
                        else if(hibernate.partial()&&!"FAILED".equals(result.get("outcome"))){result.put("outcome","INCONCLUSIVE");result.put("detail",result.get("detail")+"; Hibernate evidence is incomplete");}
                    }
                }
                if(!definition.get("max-duration-ms").isBlank()&&duration>Long.parseLong(definition.get("max-duration-ms"))&&Set.of("PASSED","FAILED","INCONCLUSIVE").contains(result.get("outcome"))) {
                    result.put("outcome","FAILED");result.put("detail",result.get("detail")+"; maximum code duration exceeded ("+duration+" ms)");
                }
            } catch(Exception failure) {cancelled = cancelled || failure instanceof StageInterrupted || Thread.currentThread().isInterrupted();result.put("outcome",cancelled?"CANCELLED":"ERROR");result.put("detail",JShellSession.limit(Objects.toString(failure.getMessage(),failure.getClass().getName())));}
            finally {
                if(initialized&&!definition.get("teardown").isBlank()) {
                    if(cancelled||stopped.getAsBoolean()||Thread.currentThread().isInterrupted())result.put("teardown","Skipped after interruption; rollback still runs");
                    else try {evaluateStage(shell,definition.get("teardown"),output,"Teardown");result.put("teardown","Completed");}
                    catch(Exception failure){result.put("outcome","ERROR");result.put("detail",result.get("detail")+"; "+failure.getMessage());}
                }
            }
        } finally {active.accept(null);}
        result.put("duration-ms",(System.nanoTime()-start)/1_000_000);result.put("out",JShellSession.limit(output.toString()));return result;
    }
    private static void checkStopped(java.util.function.BooleanSupplier stopped){if(stopped.getAsBoolean()||Thread.currentThread().isInterrupted())throw new StageInterrupted("CASE execution interrupted");}
    private static final class StageInterrupted extends RuntimeException { StageInterrupted(String message){super(message);} }
    private static void evaluateStage(JShellSession shell,String code,StringBuilder output,String stage) {
        if(code.isBlank())return;
        var result=shell.eval(code);output.append(result.output());
        if(result.interrupted())throw new StageInterrupted(stage+" interrupted");
        if(!result.error().isEmpty())throw new IllegalStateException(stage+" failed: "+result.error());
    }
}
