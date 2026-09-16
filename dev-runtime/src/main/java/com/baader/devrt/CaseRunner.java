package com.baader.devrt;

import java.util.*;
import java.util.function.*;

/** Sequential rows share one request deadline, but never a JShell or database transaction. */
final class CaseRunner {
    static Map<String,Object> run(String owner,String name,Map<String,String> definition,Object context,ExecutionPolicy policy,
            long deadline,Runnable interrupt,BooleanSupplier cancelled,Consumer<JShellSession> active) {
        long start=System.nanoTime();
        if("true".equals(definition.get("disabled")))return Map.of("outcome","SKIPPED","detail","CASE is disabled; no code executed","duration-ms",0);
        List<Map<String,Object>> results=new ArrayList<>();
        List<Map<String,String>> rows=SnapshotCases.rows(definition);
        for(Map<String,String> row:rows) {
            long remaining=(deadline-System.nanoTime())/1_000_000;
            if(cancelled.getAsBoolean()||remaining<100) {results.add(Map.of("row",row.get("id"),"outcome","CANCELLED","detail","Stopped before row execution"));break;}
            Map<String,String> current=new LinkedHashMap<>(definition);current.putAll(row);current.put("parameters-json","");
            Map<String,String> settings=new LinkedHashMap<>(policy.arguments());settings.put("timeout-ms",String.valueOf(Math.min(policy.timeoutMillis,remaining)));
            Map<String,Object> result;
            try {result=ExecutionPolicy.from(settings).execute(context,interrupt,SnapshotCases.source(current),()->SnapshotCases.run(owner,name+" ["+row.get("id")+"]",context,active,current,cancelled));}
            catch(Exception failure){result=Map.of("outcome","ERROR","detail",Objects.toString(failure.getMessage(),failure.getClass().getName()),"transaction-rolled-back",false);}
            result=new LinkedHashMap<>(result);result.put("row",row.get("id"));results.add(result);
            if(Set.of("CANCELLED","ERROR").contains(result.get("outcome")))break;
        }
        Map<String,Object> result=results.size()==1?new LinkedHashMap<>(results.get(0)):new LinkedHashMap<>();
        String outcome=aggregate(results,results.size()<rows.size());
        result.put("outcome",outcome);result.put("rows-completed",results.size());result.put("rows-total",rows.size());
        result.put("run-id",UUID.randomUUID().toString());result.put("duration-ms",(System.nanoTime()-start)/1_000_000);
        if(rows.size()>1) {
            result.put("detail","Parameterized CASE: "+outcome+"; "+results.size()+"/"+rows.size()+" rows reported");
            result.put("value",String.join("\n",results.stream().map(r->r.get("row")+"\t"+r.get("outcome")+"\t"+r.getOrDefault("duration-ms",0)+"\t"+clean(r.get("detail"),512)).toList()));
        }
        result.put("rows-json",CaseJson.write(results.stream().map(CaseRunner::compact).toList()));
        return result;
    }
    static String aggregate(List<Map<String,Object>> rows,boolean incomplete) {
        for(String status:List.of("ERROR","CANCELLED","FAILED","INCONCLUSIVE"))if(rows.stream().anyMatch(row->status.equals(row.get("outcome"))))return status;
        if(incomplete)return "CANCELLED";
        return rows.stream().allMatch(row->"SKIPPED".equals(row.get("outcome")))?"SKIPPED":"PASSED";
    }
    static Map<String,Object> compact(Map<String,Object> result) {
        Map<String,Object> copy=new LinkedHashMap<>();
        for(String key:List.of("result-sha256","exception-type","exception-message","baseline-identity","regression-json"))if(result.containsKey(key))copy.put(key,result.get(key));
        for(String key:List.of("row","outcome","detail","value","out","duration-ms","code-duration-ms","sql-count","sql-duration-ms","sql-max-repetitions","sql-partial","hibernate-loads","hibernate-flushes","hibernate-lazy-loads","hibernate-response-lazy-loads","hibernate-partial","event","execution-mode","transaction-manager","transaction-rolled-back","teardown","run-id","rows-completed","rows-total")) {
            Object value=result.get(key);if(value!=null)copy.put(key,value instanceof String?clean(value,Set.of("value","out").contains(key)?2048:512):value);
        }
        return copy;
    }
    private static String clean(Object value,int max){String text=Objects.toString(value,"");return text.length()>max?text.substring(0,max)+" [truncated]":text;}
}
