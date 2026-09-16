package com.baader.devrt;

import java.util.*;
import java.lang.ref.*;

/** Propagates recording identity only. Never copies transactions, security context or application ThreadLocals. */
public final class AsyncRecorder {
    private record Pending(long nano,long time,List<ExecutionHistory.Ticket> parents,String taskType,boolean submittingTransaction){}
    private record Frame(Object task,Frame previous,List<ExecutionHistory.Ticket> tickets,long start){}
    private static final ReferenceQueue<Object> COLLECTED=new ReferenceQueue<>();
    private static final LinkedHashMap<Key,Pending> PENDING=new LinkedHashMap<>();
    private static final class Key extends WeakReference<Object>{
        final int hash;Key(Object task){super(task,COLLECTED);hash=System.identityHashCode(task);}
        @Override public int hashCode(){return hash;}
        @Override public boolean equals(Object other){return this==other||other instanceof Key k&&get()!=null&&get()==k.get();}
    }
    private static void consumed(Pending p,boolean lost){if(p!=null)p.parents().forEach(t->{t.history().asyncPending.decrementAndGet();if(lost)t.history().asyncIncomplete();});}
    static void prune(){synchronized(PENDING){
        Reference<?> key;while((key=COLLECTED.poll())!=null)consumed(PENDING.remove(key),true);
        var it=PENDING.entrySet().iterator();long now=System.currentTimeMillis();
        while(it.hasNext()){Pending p=it.next().getValue();if(now-p.time()>300000||p.parents().stream().allMatch(t->!t.history().recording())){it.remove();consumed(p,true);}}
    }}
    private static Pending take(Object task){synchronized(PENDING){Pending p=PENDING.remove(new Key(task));consumed(p,false);return p;}}
    private static final ThreadLocal<Frame> CURRENT=new ThreadLocal<>();
    private static final ThreadLocal<Boolean> BUSY=ThreadLocal.withInitial(()->false);
    private static volatile boolean installed;
    private AsyncRecorder(){}
    public static void installed(){installed=true;}
    public static void failed(){installed=false;}
    static boolean available(){return installed;}
    static Object dispatch(Object[] values){
        if(BUSY.get())return null;BUSY.set(true);
        try{
            int kind=(Integer)values[0];Object task=values[1],extra=values[2];
            return switch(kind){
                case 0 -> submit(task);
                case 1 -> begin(task);
                case 2 -> {finish(task,extra instanceof Throwable t?t:null);yield null;}
                case 3 -> {if(extra instanceof Throwable){Pending p=take(task);if(p!=null)p.parents().forEach(t->t.history().asyncIncomplete());}yield null;}
                case 4 -> wrap((Runnable)task);
                default -> null;
            };
        }finally{BUSY.remove();}
    }
    static void enabled(boolean value){try{Class.forName("com.baader.devrt.bootstrap.AsyncBridge",false,null).getField("enabled").setBoolean(null,value);}catch(ReflectiveOperationException ignored){}}
    private static Object submit(Object task){
        List<ExecutionHistory.Ticket> parents=TraceRecorder.currentRecordings().stream().filter(t->t.history().async&&t.history().recording()).toList();
        if(task==null||parents.isEmpty())return null;
        synchronized(PENDING){
            prune();Key key=new Key(task);Pending previous=PENDING.get(key);
            if(previous!=null&&System.currentTimeMillis()-previous.time()<300000){
                consumed(previous,false);previous.parents().forEach(t->t.history().asyncIncomplete());parents.forEach(t->t.history().asyncIncomplete());
                // Concurrent reuse cannot be attributed reliably; preserve an empty sentinel until execution consumes it.
                PENDING.put(key,new Pending(System.nanoTime(),System.currentTimeMillis(),List.of(),task.getClass().getName(),false));return null;
            }
            if(PENDING.size()>=1024){var first=PENDING.entrySet().iterator();Pending old=first.next().getValue();first.remove();consumed(old,true);}
            parents.forEach(t->t.history().asyncPending.incrementAndGet());
            PENDING.put(key,new Pending(System.nanoTime(),System.currentTimeMillis(),parents,task.getClass().getName(),transaction()));
        }
        return null;
    }
    private static Object begin(Object task){
        Frame prior=CURRENT.get();if(prior!=null&&prior.task()==task)return null;
        Pending pending=take(task);
        if(pending==null)return null;
        if(System.currentTimeMillis()-pending.time()>300000){pending.parents().forEach(t->t.history().asyncIncomplete());return null;}
        List<ExecutionHistory.Ticket> tickets=new ArrayList<>();
        for(var parent:pending.parents()){
            if(ExecutionHistory.get(parent.history().owner)!=parent.history()||!parent.history().recording())continue;
            Map<String,Object> metadata=Map.of("taskType",pending.taskType(),"queueWaitMs",Math.max(0,(System.nanoTime()-pending.nano())/1_000_000.0),
                "submittedAt",pending.time(),"submissionTransactionActive",pending.submittingTransaction(),"executionTransactionActive",transaction(),
                "cancelledBeforeStart",task instanceof java.util.concurrent.Future<?> future&&future.isCancelled(),
                "context","Recording identity only; transactions and security are not propagated by REPL");
            var ticket=parent.history().enter(parent.id(),"async.Task","execute","(Ljava/util/Map;)V","handoff",new Object[]{metadata});if(ticket!=null)tickets.add(ticket);
        }
        if(tickets.isEmpty())return null;Frame frame=new Frame(task,prior,List.copyOf(tickets),System.nanoTime());CURRENT.set(frame);return frame;
    }
    private static void finish(Object task,Throwable error){
        Frame frame=CURRENT.get();if(frame==null||frame.task()!=task)return;
        if(frame.previous()==null)CURRENT.remove();else CURRENT.set(frame.previous());
        frame.tickets().forEach(t->t.history().exit(t,System.nanoTime()-frame.start(),null,error,-1));
    }
    static List<ExecutionHistory.Ticket> current(){Frame frame=CURRENT.get();return frame==null?List.of():frame.tickets();}
    private static Runnable wrap(Runnable original){
        // SimpleAsyncTaskExecutor has no executor queue/removal API; preserve the user task's behavior in a wrapper.
        submit(original);return ()->{Object frame=begin(original);try{original.run();}catch(Throwable failure){if(frame!=null)finish(original,failure);throw failure;}finally{if(frame!=null)finish(original,null);}};
    }
    private static boolean transaction(){
        try{Class<?> support=Class.forName("org.springframework.transaction.support.TransactionSynchronizationManager",false,AppClassPath.loader(SpringContextHolder.get()));return Boolean.TRUE.equals(support.getMethod("isActualTransactionActive").invoke(null));}
        catch(ReflectiveOperationException|LinkageError ignored){return false;}
    }
}
