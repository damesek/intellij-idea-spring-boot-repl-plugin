package com.baader.devrt;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.beans.factory.support.RootBeanDefinition;
import java.nio.file.Path;
import java.util.*;
import example.interactive.Service;
import example.interactive.Service.Input;
import static org.junit.jupiter.api.Assertions.*;

public class InteractiveWorkflowTest {
    @TempDir Path home;String oldHome,owner;ReplHandler handler;GenericApplicationContext context;
    @BeforeEach void setup(){
        oldHome=System.getProperty("user.home");System.setProperty("user.home",home.toString());
        context=new GenericApplicationContext();RootBeanDefinition bean=new RootBeanDefinition(Service.class);bean.setLazyInit(true);bean.setPrimary(true);context.registerBeanDefinition("calculator",bean);context.refresh();
        Service.created=Service.calls=0;SpringContextHolder.set(context);handler=new ReplHandler();owner=handler.createSession();
    }
    @AfterEach void close(){handler.close();SpringContextHolder.set(null);context.close();System.setProperty("user.home",oldHome);}
    Map<String,Object> request(String op,String... pairs){Map<String,String> args=new LinkedHashMap<>();args.put("session",owner);for(int i=0;i<pairs.length;i+=2)args.put(pairs[i],pairs[i+1]);return handler.handle(op,args);}
    Map<String,Object> ok(String op,String... pairs){var result=request(op,pairs);assertFalse(result.containsKey("err"),result.toString());return result;}
    @Test void concurrentNewCopiesCannotOverwriteEachOther() throws Exception {
        var pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        var start=new java.util.concurrent.CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<Boolean>> results=new ArrayList<>();
            for(int value:List.of(1,2))results.add(pool.submit(()->{
                Map<String,Object> data=SnapshotManager.envelope("copy","DATA");data.put("declaredType","int");data.put("payload",value);
                if(!start.await(5,java.util.concurrent.TimeUnit.SECONDS))throw new AssertionError("Start timeout");
                try{SnapshotManager.createNew(Map.of("copy",data),Map.of());return true;}
                catch(IllegalStateException collision){assertTrue(collision.getMessage().contains("already exists"));return false;}
            }));
            start.countDown();int saved=0;
            for(var result:results)if(result.get(10,java.util.concurrent.TimeUnit.SECONDS))saved++;
            assertEquals(1,saved);assertTrue(Set.of(1,2).contains(SnapshotManager.load("copy")));
            assertEquals(1,SnapshotVersions.list(SnapshotManager.path("copy")).size());
        } finally {pool.shutdownNow();}
    }
    @Test void newBatchRejectsStaleSourcesAndCollisionsWithoutPartialPublication() throws Exception {
        SnapshotManager.save("original",1,"int");String version=SnapshotVersions.checksum(SnapshotManager.path("original"));
        Map<String,Object> data=SnapshotManager.envelope("new","DATA");data.put("declaredType","int");data.put("payload",3);
        SnapshotManager.save("original",2,"int");
        assertThrows(IllegalStateException.class,()->SnapshotManager.createNew(Map.of("new",data),Map.of("original",version)));
        assertFalse(java.nio.file.Files.exists(SnapshotManager.path("new")));
        Map<String,Map<String,Object>> entries=new LinkedHashMap<>();entries.put("new",data);entries.put("original",data);
        assertThrows(IllegalStateException.class,()->SnapshotManager.createNew(entries,Map.of()));
        assertFalse(java.nio.file.Files.exists(SnapshotManager.path("new")));assertEquals(2,SnapshotManager.<Integer>load("original").intValue());
    }
    @Test void preparedCallsUsePublicProxyContractsAndKeepAdvice() throws Exception {
        for(boolean cglib:List.of(false,true)){
            var factory=new org.springframework.aop.framework.ProxyFactory(new Service());factory.setProxyTargetClass(cglib);
            var advised=new java.util.concurrent.atomic.AtomicInteger();
            factory.addAdvice((org.aopalliance.intercept.MethodInterceptor) invocation->{advised.incrementAndGet();return invocation.proceed();});
            Object proxy=factory.getProxy();String name=cglib?"cglib":"jdk";
            RootBeanDefinition definition=new RootBeanDefinition(proxy.getClass());definition.setInstanceSupplier(()->proxy);context.registerBeanDefinition(name,definition);context.getBean(name);
            SnapshotManager.save(name+"-data","hello","java.lang.String");String descriptor=BeanExplorer.descriptor(Service.class.getMethod("calculate",String.class));
            var prepared=ok("beans/prepare","bean",name,"method","calculate","descriptor",descriptor,"inputs-json","[\""+name+"-data\"]");
            assertFalse(prepared.get("code").toString().contains("$$SpringCGLIB"));assertFalse(prepared.get("code").toString().contains("$Proxy"));
            assertEquals(0,advised.get());assertTrue(ok("eval","code",prepared.get("code").toString()).get("value").toString().contains("hello"));assertEquals(1,advised.get());
            ExecutionHistory history=ExecutionHistory.begin(owner,false,5,false,true);var ticket=history.enter(0,Service.class.getName(),"calculate",descriptor,"input",new Object[]{"hello"});
            history.experiments.enter(ticket,Service.class,"calculate",descriptor,new Object[]{"hello"});history.exit(ticket,1,"hello",null,-1);history.experiments.exit(ticket,"hello",null);
            ok("trace/case-create","recording",history.id,"call-id",""+ticket.id(),"name",name+"-case","bean",name);
            assertEquals("PASSED",ok("case/run","name",name+"-case").get("outcome"));assertEquals(2,advised.get());
        }
    }
    @Test void affectedCasesAndBaselinesDistinguishUnknownCoverageAndChangedData() throws Exception {
        SnapshotManager.save("input",2,"int");SnapshotManager.save("expected",2,"int");
        Map<String,String> definition=new HashMap<>(Map.of("input","input","expected","expected","type","int","code","", "result-expression","input * example.interactive.Service.factor", "observed-classes",Service.class.getName()));
        SnapshotManager.saveCase("observed",definition);definition.remove("observed-classes");SnapshotManager.saveCase("unknown",definition);
        definition.put("observed-classes","example.Unrelated");SnapshotManager.saveCase("unmatched",definition);
        var plan=ok("case/affected","code","package example.interactive; public class Service {} ");assertEquals("observed",plan.get("affected"));assertEquals("unknown",plan.get("unknown"));assertEquals("unmatched",plan.get("unmatched"));
        Service.factor=1;assertEquals(false,CaseJson.object(ok("case/run","name","observed").get("regression-json").toString()).get("comparable"));
        Service.factor=2;var changed=ok("case/run","name","observed");assertEquals("FAILED",changed.get("outcome"));
        var comparison=CaseJson.object(changed.get("regression-json").toString());assertEquals(true,comparison.get("comparable"));
        Map<?,?> row=(Map<?,?>)((List<?>)comparison.get("rows")).get(0);assertEquals(true,((Map<?,?>)row.get("result-sha256")).get("changed"));assertEquals("UNKNOWN",((Map<?,?>)row.get("sql-count")).get("state"));
        SnapshotManager.save("expected",4,"int");assertEquals(false,CaseJson.object(ok("case/run","name","observed").get("regression-json").toString()).get("comparable"));Service.factor=1;
    }
    @Test void notificationsAreBoundedMetadataAndSurviveContextInvalidation() throws Exception {
        String other=handler.createSession();ok("eval","code","42");
        var first=ok("notifications/poll","cursor","0");assertTrue(first.get("events-json").toString().contains("operation.completed"));assertFalse(first.get("events-json").toString().contains("42"));
        assertEquals("[]",handler.handle("notifications/poll",Map.of("session",other)).get("events-json"));
        ok("capture/arm","point","test","name","secret-data");SnapshotTriggers.capture("test","",()->Map.of("password","never-in-notifications"));
        var capture=ok("notifications/poll","cursor",first.get("cursor").toString());assertTrue(capture.get("events-json").toString().contains("capture.completed"));assertFalse(capture.toString().contains("never-in-notifications"));
        for(int i=0;i<150;i++)ReplNotifications.publish(owner,"test",Map.of());
        var limited=ok("notifications/poll","cursor","0");assertEquals(true,limited.get("gap"));assertEquals(128,((List<?>)CaseJson.parse(limited.get("events-json").toString())).size());
        SpringContextHolder.set(null);assertTrue(ok("notifications/poll","cursor",limited.get("cursor").toString()).toString().contains("context.changed"));
        handler.closeSession(other);
    }
    @Test void beanMetadataAndPreparationDoNotInitializeLazyBeans()throws Exception{
        assertTrue(ok("beans/list","query","calculator").get("beans-json").toString().contains("calculator"));
        var info=CaseJson.object(ok("beans/info","bean","calculator").get("bean-json").toString());
        assertEquals(true,info.get("primary"));assertEquals("singleton",info.get("scope"));assertEquals(false,info.get("instantiated"));assertEquals(0,Service.created);
        SnapshotManager.save("text","hello","java.lang.String");
        String descriptor=BeanExplorer.descriptor(Service.class.getMethod("calculate",String.class));
        var candidates=ok("beans/compatible-data","bean","calculator","method","calculate","descriptor",descriptor,"parameter","0");
        assertTrue(candidates.toString().contains("text"));
        var code=ok("beans/prepare","bean","calculator","method","calculate","descriptor",descriptor,"inputs-json","[\"text\"]");
        assertEquals(0,Service.created);assertTrue(code.get("code").toString().contains(".calculate(callArg0)"));
    }
    @Test void editsValidateTypesPreserveOriginalAndRejectChangedSources()throws Exception{
        Input original=new Input(2,List.of("old"));SnapshotManager.save("original",original,Input.class.getName());
        String version=ok("snapshot/edit-read","name","original").get("version").toString();
        ok("snapshot/edit-copy","name","original","version",version,"target","copy","json","{\"amount\":5,\"tags\":[\"new\"]}");
        assertEquals(2,original.amount());assertEquals(original,SnapshotManager.load("original"));assertEquals(5,((Input)SnapshotManager.load("copy")).amount());
        assertTrue(request("snapshot/edit-copy","name","original","version",version,"target","invalid","json","{\"amount\":\"no\"}").containsKey("err"));
        assertFalse(java.nio.file.Files.exists(SnapshotManager.path("invalid")));
        SnapshotManager.save("original",new Input(3,List.of()),Input.class.getName());
        assertTrue(request("snapshot/edit-copy","name","original","version",version,"target","stale","json","{}").get("err").toString().contains("changed"));
    }
    @Test void recordedArgumentsAreFrozenAndCreateExecutableExportableCaseWithoutCallingBean()throws Exception{
        ExecutionHistory history=ExecutionHistory.begin(owner,false,5,false,true);
        var method=Service.class.getMethod("calculate",Input.class,int.class);String descriptor=BeanExplorer.descriptor(method);
        var tags=new ArrayList<>(List.of("entry"));Input input=new Input(3,tags);Object[] args={input,4};
        var ticket=history.enter(0,Service.class.getName(),method.getName(),descriptor,"input\nmultiplier",args);
        history.experiments.enter(ticket,Service.class,method.getName(),descriptor,args);tags.add("later");
        history.exit(ticket,1,12,null,-1);history.experiments.exit(ticket,12,null);
        ok("trace/case-create","recording",history.id,"call-id",""+ticket.id(),"name","replay","bean","calculator");
        assertEquals(0,Service.calls);assertEquals(0,Service.created);
        assertFalse(SnapshotManager.json("replay-input").contains("later"));
        assertTrue(SnapshotManager.loadCase("replay").get("observed-classes").contains(Service.class.getName()));
        assertEquals("PASSED",ok("case/run","name","replay").get("outcome"));assertEquals(1,Service.calls);
        var files=CaseJUnitExport.files("replay","example","ReplayTest",Map.of("execution-mode","LIVE"));
        assertTrue(files.get("src/test/java/example/ReplayTest.java").contains(".calculate(callArg0, callArg1)"));
        ok("case/variants","name","replay","target","variants","inputs","replay-input");
        assertEquals(1,SnapshotCases.rows(SnapshotManager.loadCase("variants")).size());
        assertEquals(1,Service.calls);
    }
    @Test void previewOnlyAndIncompleteCapturesCannotCreateCases(){
        ExecutionHistory history=ExecutionHistory.begin(owner);var ticket=history.enter(0,Service.class.getName(),"calculate","(Ljava/lang/String;)Ljava/lang/String;","value",new Object[]{"x"});
        assertEquals(false,ok("trace/case-info","recording",history.id,"call-id",""+ticket.id()).get("ready"));
        assertTrue(request("trace/case-create","recording",history.id,"call-id",""+ticket.id(),"name","bad").containsKey("err"));
    }
    @Test void watchesTrackAfterExplicitRunsWithoutOverwritingLastResult(){
        String id=ok("watch/add","expression","last1.amount").get("id").toString();
        ok("eval","code","new "+Input.class.getCanonicalName()+"(3, java.util.List.of(\"a\"))");
        assertEquals("FIRST",ok("watch/get","watch-id",id).get("state"));
        ok("eval","code","new "+Input.class.getCanonicalName()+"(4, java.util.List.of(\"a\"))");
        assertEquals("CHANGED",ok("watch/get","watch-id",id).get("state"));
        assertTrue(ok("watch/get","watch-id",id).get("diff").toString().contains("4"));
        assertTrue(request("watch/add","expression","ctx.getBean(\"calculator\")").containsKey("err"));assertEquals(0,Service.created);
        String java=ok("watch/add","expression","(("+Input.class.getCanonicalName()+")last1).amount() + 100","allow-java","true").get("id").toString();
        ok("watch/refresh");assertEquals("FIRST",ok("watch/get","watch-id",java).get("state"));
        assertEquals(Input.class.getName(),ok("inspector/start","var","last1").get("type"));
        ok("session/reset");assertTrue(request("watch/get","watch-id",id).containsKey("err"));
    }
}
