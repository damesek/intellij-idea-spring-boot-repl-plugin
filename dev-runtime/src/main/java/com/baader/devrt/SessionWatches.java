package com.baader.devrt;

import hu.baader.repl.protocol.ValueTree;
import java.lang.reflect.*;
import java.util.*;
import java.util.regex.*;

/** No timer or polling evaluation: samples only after an explicit REPL run or Refresh watches. */
final class SessionWatches {
    private static final Pattern ROOT=Pattern.compile("[A-Za-z_$][\\w$]*");
    private static final Pattern STEP=Pattern.compile("\\.([A-Za-z_$][\\w$]*)|\\[([0-9]+)\\]|\\[\"((?:[^\"\\\\]|\\\\.)*)\"\\]");
    private static final class Watch {
        final String id=UUID.randomUUID().toString(),expression;final boolean java;
        String before="",after="",diff="",state="WAITING",error="";long sequence,time;
        Watch(String expression,boolean java){this.expression=expression;this.java=java;}
    }
    private final LinkedHashMap<String,Watch> watches=new LinkedHashMap<>();private long sequence;
    Map<String,Object> add(String expression,boolean java){
        if(expression==null||expression.isBlank()||expression.length()>2048||watches.size()>=20)throw new IllegalArgumentException("Use 1–2048 characters; at most 20 watches per session");
        if(!java)path(null,expression,true);
        Watch watch=new Watch(expression,java);watches.put(watch.id,watch);
        return Map.of("id",watch.id,"value","Watch pinned. It will be sampled after your next REPL run.");
    }
    Map<String,Object> list(){return Map.of("watches-json",CaseJson.write(watches.values().stream().map(w->Map.of("id",w.id,"expression",w.expression,"java",w.java,"state",w.state,"sequence",w.sequence,"time",w.time,"error",w.error)).toList()));}
    Map<String,Object> get(String id){Watch w=required(id);return Map.of("id",id,"expression",w.expression,"state",w.state,"before-view",w.before,"after-view",w.after,"diff",w.diff,"error",w.error,"sequence",w.sequence,"time",w.time);}
    private Watch required(String id){Watch watch=watches.get(id);if(watch==null)throw new IllegalArgumentException("Watch not found in this session");return watch;}
    void remove(String id){required(id);watches.remove(id);}
    void clear(){watches.clear();sequence=0;}
    String javaSource(){return String.join("\n",watches.values().stream().filter(w->w.java).map(w->w.expression).toList());}
    void sample(JShellSession shell){
        long run=++sequence;
        for(Watch watch:watches.values()){
            if(Thread.currentThread().isInterrupted())return;
            watch.sequence=run;watch.time=System.currentTimeMillis();watch.error="";
            try{
                Object value=watch.java?shell.watchValue(watch.expression):path(shell,watch.expression,false);
                String wire=Objects.toString(ValuePresentation.present(value).get("view-data"));
                if(wire.length()>131072)throw new IllegalArgumentException("Watch display exceeds 128 KiB; choose a smaller projection");
                ValueTree current=ValueTree.decode(wire);
                watch.before=watch.after;watch.after=wire;watch.diff="";
                boolean partial=partial(current)||!watch.before.isEmpty()&&partial(ValueTree.decode(watch.before));
                if(watch.before.isEmpty())watch.state=partial?"PARTIAL":"FIRST";
                else{
                    var diff=SnapshotDiff.compare(flat(ValueTree.decode(watch.before)),flat(current),0,100);
                    watch.diff=diff.get("value").toString();watch.state=partial||Boolean.TRUE.equals(diff.get("scan-limited"))?"PARTIAL":((Number)diff.get("changes-found")).intValue()>0?"CHANGED":"UNCHANGED";
                }
            }catch(Exception|LinkageError failure){watch.state="ERROR";watch.error=Objects.toString(failure.getMessage(),failure.getClass().getName()).substring(0,Math.min(2048,Objects.toString(failure.getMessage(),failure.getClass().getName()).length()));}
        }
    }
    private static boolean partial(ValueTree tree){return Set.of("LIMIT","ERROR","REFERENCE").contains(tree.kind())||tree.children().stream().anyMatch(SessionWatches::partial);}
    private static Object flat(ValueTree tree){
        if(tree.children().isEmpty())return tree.kind()+":"+tree.type()+":"+tree.text();
        Map<String,Object> data=new LinkedHashMap<>();int index=0;for(ValueTree child:tree.children())data.put(child.label()+" ["+(index++)+"]",flat(child));return data;
    }
    private static Object path(JShellSession shell,String expression,boolean validate){
        Matcher root=ROOT.matcher(expression);if(!root.lookingAt())throw new IllegalArgumentException("Use a variable path, e.g. last1.items[0].total; enable Java explicitly for method calls");
        int at=root.end();Object value=validate?null:shell.value(null,root.group());
        while(at<expression.length()){
            if(expression.substring(at).equals(".size()")){
                if(validate)return null;value=readable(value);
                if(value!=null&&value.getClass().isArray())return Array.getLength(value);
                if(value!=null&&value.getClass().getClassLoader()==null){if(value instanceof Collection<?> c)return c.size();if(value instanceof Map<?,?> m)return m.size();if(value instanceof String s)return s.length();}
                throw new IllegalArgumentException("Size is supported for arrays, strings and JDK collections; custom methods need an explicit Java watch");
            }
            Matcher step=STEP.matcher(expression);step.region(at,expression.length());if(!step.lookingAt())throw new IllegalArgumentException("Unsupported watch path near "+expression.substring(at));
            if(!validate){
                value=readable(value);if(value==null)throw new IllegalArgumentException("Null before "+step.group());
                try{
                    if(step.group(1)!=null){
                        String name=step.group(1);Field field=null;
                        for(Class<?> type=value.getClass();type!=null&&field==null;type=type.getSuperclass())try{field=type.getDeclaredField(name);}catch(NoSuchFieldException ignored){}
                        if(field==null||Modifier.isStatic(field.getModifiers())||!field.trySetAccessible())throw new IllegalArgumentException("Field is unavailable: "+name);
                        var view=HibernateAccess.view(value);if(view!=null&&view.unfetched().contains(name))throw new IllegalArgumentException("Unfetched Hibernate attribute; watch did not initialize it");
                        value=field.get(value);
                    }else if(step.group(2)!=null){int index=Integer.parseInt(step.group(2));if(value.getClass().isArray())value=Array.get(value,index);else if(value instanceof List<?> list&&value.getClass().getClassLoader()==null)value=list.get(index);else throw new IllegalArgumentException("Index requires an array or JDK list");}
                    else{if(!(value instanceof Map<?,?> map)||value.getClass().getClassLoader()!=null)throw new IllegalArgumentException("Key requires a JDK map");Object key=CaseJson.parse("\""+step.group(3)+"\"");if(!map.containsKey(key))throw new IllegalArgumentException("Map key absent");value=map.get(key);}
                }catch(IllegalAccessException e){throw new IllegalArgumentException("Field inaccessible",e);}
            }
            at=step.end();
        }
        return value;
    }
    private static Object readable(Object value){var view=HibernateAccess.view(value);if(view!=null){if(view.opaque())throw new IllegalArgumentException("Uninitialized Hibernate value; watch did not load it");return view.contents();}return value;}
}
