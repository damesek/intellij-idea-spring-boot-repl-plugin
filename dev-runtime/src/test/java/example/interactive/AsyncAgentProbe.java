package example.interactive;

import com.baader.devrt.*;
import hu.baader.repl.protocol.*;
import org.springframework.context.annotation.*;
import org.springframework.scheduling.annotation.*;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.h2.jdbcx.JdbcDataSource;
import java.util.*;
import java.util.concurrent.*;

public class AsyncAgentProbe {
    @Configuration @EnableAsync(proxyTargetClass=true) public static class Config {
        @Bean public Worker worker(){return new Worker();}
        @Bean public ThreadPoolTaskExecutor taskExecutor(){var e=new ThreadPoolTaskExecutor();e.setCorePoolSize(1);e.setThreadNamePrefix("spring-worker-");return e;}
    }
    public static class Worker {
        static JdbcTemplate jdbc;
        public int leaf(){return jdbc.queryForObject("select 42",Integer.class);}
        @Async public CompletableFuture<Integer> annotated(){return CompletableFuture.completedFuture(leaf());}
        public int submit(ExecutorService executor) throws Exception {return executor.submit(this::leaf).get(5,TimeUnit.SECONDS);}
        public int future() throws Exception {return CompletableFuture.supplyAsync(this::leaf).thenApplyAsync(n->leaf()+n).get(5,TimeUnit.SECONDS);}
        public int simple(SimpleAsyncTaskExecutor executor) throws Exception {return CompletableFuture.supplyAsync(this::leaf,executor).get(5,TimeUnit.SECONDS);}
        public int spring(Worker bean) throws Exception {return bean.annotated().get(5,TimeUnit.SECONDS);}
        public void queue(Executor executor,Runnable task){executor.execute(task);}
    }
    static void check(boolean value,Object evidence){if(!value)throw new AssertionError(evidence);}
    static Map<String,Object> ok(ReplHandler h,String s,String op,Map<String,String> args){var p=new HashMap<>(args);p.put("session",s);var r=h.handle(op,p);check(!ReplProtocol.error(r),r);return r;}
    public static void main(String[] args) throws Exception {
        var ds=new JdbcDataSource();ds.setURL("jdbc:h2:mem:async");Worker.jdbc=new JdbcTemplate(ds);
        try(var ctx=new AnnotationConfigApplicationContext(Config.class);var h=new ReplHandler()){
            SpringContextHolder.set(ctx);String session=h.createSession();Worker bean=ctx.getBean(Worker.class);Worker caller=new Worker();
            ok(h,session,"trace/record",Map.of("classes",Worker.class.getName(),"async","true","sql","true"));
            var pool=Executors.newSingleThreadExecutor();var simple=new SimpleAsyncTaskExecutor("simple-worker-");
            try {check(caller.submit(pool)==42,"Executor result");check(caller.future()==84,"Future result");check(caller.simple(simple)==42,"SimpleAsync result");check(caller.spring(bean)==42,"@Async result");}
            finally{pool.shutdown();check(pool.awaitTermination(5,TimeUnit.SECONDS),"Pool did not terminate");simple.close();}
            // Await the observer's afterExecute after the application futures have completed.
            Map<String,Object> history=Map.of();List<RecordedCall> calls=List.of();
            for(int i=0;i<100;i++){history=ok(h,session,"trace/history",Map.of());calls=history.get("value").toString().lines().filter(s->!s.isEmpty()).map(RecordedCall::decode).toList();if(calls.stream().noneMatch(c->c.status().equals("RUNNING")))break;Thread.sleep(10);}
            var byId=new HashMap<Long,RecordedCall>();calls.forEach(c->byId.put(c.id(),c));
            var boundaries=calls.stream().filter(c->c.className().equals("async.Task")).toList();check(boundaries.size()==5,"Expected 5 handoffs: "+calls);
            check(boundaries.stream().allMatch(c->c.parent()>0),"Missing cross-thread ancestry: "+calls);
            check(calls.stream().filter(c->c.method().equals("leaf")).allMatch(c->c.parent()>0&&c.root()!=c.id()),"Unlinked worker leaf: "+calls);
            var sql=SqlSnapshot.decode(history.get("sql").toString());check(sql.count()==5,sql);check(!sql.partial(),sql);
            check(history.get("async-pending").toString().equals("0"),history);check(history.get("async-dropped").toString().equals("0"),history);
            for(var call:boundaries){String detail=ok(h,session,"trace/call",Map.of("recording",history.get("recording").toString(),"call-id",""+call.id())).get("value").toString();check(ValueTree.decode(RecordedCall.decode(detail).input()).toString().contains("queueWaitMs")&&ValueTree.decode(RecordedCall.decode(detail).input()).toString().contains("submissionTransactionActive"),detail);}
            ok(h,session,"trace/stop",Map.of());
            // Reused Runnable identities must never be attributed to an arbitrary submitter.
            ok(h,session,"trace/record",Map.of("classes",Worker.class.getName(),"async","true","sql","true"));
            var blocked=Executors.newSingleThreadExecutor();CountDownLatch release=new CountDownLatch(1);blocked.submit(()->{try{release.await();}catch(InterruptedException e){Thread.currentThread().interrupt();}});
            Runnable reused=caller::leaf;caller.queue(blocked,reused);caller.queue(blocked,reused);release.countDown();blocked.shutdown();check(blocked.awaitTermination(5,TimeUnit.SECONDS),"Queue timeout");
            var ambiguous=ok(h,session,"trace/history",Map.of());check(Long.parseLong(ambiguous.get("async-dropped").toString())>=2,ambiguous);check(SqlSnapshot.decode(ambiguous.get("sql").toString()).partial(),ambiguous);
            ok(h,session,"trace/stop",Map.of());SpringContextHolder.clear(ctx);
        }
        System.out.println("ASYNC_EXECUTOR_FUTURE_SPRING_OK");
    }
}
