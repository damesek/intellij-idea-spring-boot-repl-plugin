package com.baader.devrt;

import java.util.*;
import hu.baader.repl.protocol.AuditTrail;

/** Suggestions are based on recorded evidence, never presented as complete code coverage. */
final class CaseRegression {
    static Map<String,Object> affected(String source) throws Exception {
        Set<String> classes=JavaCodeEvaluator.sourceClasses(source);List<String> affected=new ArrayList<>(),unknown=new ArrayList<>(),unmatched=new ArrayList<>();
        for(String row:SnapshotManager.list()){
            String[] fields=row.split("\t");if(fields.length<3||!fields[2].equals("CASE"))continue;
            Map<String,String> definition=SnapshotManager.loadCase(fields[0]);if("true".equals(definition.get("disabled")))continue;
            List<String> observed=definition.getOrDefault("observed-classes","").lines().filter(s->!s.isBlank()).toList();
            if(observed.isEmpty())unknown.add(fields[0]);
            else if(observed.stream().anyMatch(c->classes.stream().anyMatch(t->c.equals(t)||c.startsWith(t+"$")||t.startsWith(c+"$"))))affected.add(fields[0]);
            else unmatched.add(fields[0]);
        }
        return Map.of("classes",String.join("\n",classes),"affected",String.join("\n",affected),"unknown",String.join("\n",unknown),"unmatched",String.join("\n",unmatched),
                "source-sha256",AuditTrail.hash(source),"detail","Suggestions use previously observed classes only. Unknown and unmatched cases may still be affected. No reload or execution has occurred.");
    }
    static String identity(Map<String,String> definition,ExecutionPolicy policy) throws Exception {
        Map<String,Object> data=new TreeMap<>(definition);data.put("policy",new TreeMap<>(policy.arguments()));data.put("epoch",SpringContextHolder.epoch());
        Map<String,String> versions=new TreeMap<>();
        for(Map<String,String> row:SnapshotCases.rows(definition))for(String key:List.of("input","expected")){
            String name=row.get(key);versions.putIfAbsent(name,SnapshotVersions.checksum(SnapshotManager.path(name)));
        }
        data.put("fixtures",versions);return AuditTrail.hash(CaseJson.write(data));
    }
    static String fingerprint(Object value){
        try{return AuditTrail.hash(CaseJson.write(canonical(value,0,new int[]{0})));}
        catch(IllegalArgumentException limit){return "";}
    }
    private static Object canonical(Object value,int depth,int[] count){
        if(depth>64||++count[0]>100000)throw new IllegalArgumentException("Fingerprint limit");
        if(value instanceof Map<?,?> map){Map<String,Object> result=new TreeMap<>();map.forEach((k,v)->result.put(k.toString(),canonical(v,depth+1,count)));return result;}
        if(value instanceof List<?> list)return list.stream().map(v->canonical(v,depth+1,count)).toList();
        if(value instanceof Number number)return new java.math.BigDecimal(number.toString()).stripTrailingZeros();return value;
    }
    static String compare(Map<String,Object> before,Map<String,Object> after){
        if(before==null)return CaseJson.write(Map.of("comparable",false,"reason","First run in this session; baseline recorded"));
        if(!Objects.equals(before.get("baseline-identity"),after.get("baseline-identity")))return CaseJson.write(Map.of("comparable",false,"reason","CASE, DATA, context or execution settings changed"));
        List<?> left=(List<?>)CaseJson.parse(before.getOrDefault("rows-json","[]").toString(),2_000_000),right=(List<?>)CaseJson.parse(after.getOrDefault("rows-json","[]").toString(),2_000_000);
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int i=0;i<right.size();i++){
            Map<?,?> current=(Map<?,?>)right.get(i),previous=i<left.size()?(Map<?,?>)left.get(i):Map.of();Map<String,Object> row=new LinkedHashMap<>();row.put("row",current.get("row"));
            for(String key:List.of("outcome","result-sha256","exception-type","exception-message","sql-count","sql-max-repetitions","hibernate-loads","hibernate-flushes","hibernate-lazy-loads","hibernate-response-lazy-loads","code-duration-ms")){
                Object a=previous.get(key),b=current.get(key);
                String availability=key.startsWith("sql-")?"sql-partial":key.startsWith("hibernate-")?"hibernate-partial":"";
                boolean partial=!availability.isEmpty()&&(!Boolean.FALSE.equals(previous.get(availability))||!Boolean.FALSE.equals(current.get(availability)));
                if(a==null||b==null||key.equals("result-sha256")&&(a.equals("")||b.equals(""))||partial){row.put(key,Map.of("state","UNKNOWN"));continue;}
                row.put(key,Map.of("before",a,"after",b,"changed",!Objects.equals(a,b)));
            }
            rows.add(row);
        }
        return CaseJson.write(Map.of("comparable",true,"rows",rows,"note","Duration is a single-run measurement, not proof of regression. Assertions and explicit SQL/ORM limits determine pass/fail."));
    }
}
