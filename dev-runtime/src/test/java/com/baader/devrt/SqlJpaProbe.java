package hu.baader.repl.fixture;

import jakarta.persistence.*;
import org.hibernate.SessionFactory;
import com.baader.devrt.ReplHandler;
import hu.baader.repl.protocol.SqlSnapshot;
import hu.baader.repl.protocol.HibernateSnapshot;
import com.baader.devrt.*;
import java.util.*;

/** Hibernate lazy association produces 1+5 SELECTs; fetch join produces one, with identical results. */
public class SqlJpaProbe {
    @org.hibernate.annotations.Cache(usage=org.hibernate.annotations.CacheConcurrencyStrategy.READ_WRITE)
    @Entity(name="SqlCustomer") @Table(name="sql_customer") public static class Customer {
        @Id public int id; public String name;
        @OneToMany(mappedBy="customer",fetch=FetchType.LAZY) public List<Purchase> purchases=new ArrayList<>();
        public Customer() {} Customer(int id) { this.id=id;name="customer-"+id; }
        public String getName() { return name; }
    }
    @Entity(name="SqlPurchase") @Table(name="sql_purchase") public static class Purchase {
        @Id public int id;
        @ManyToOne(fetch=FetchType.LAZY) public Customer customer;
        public Purchase() {} Purchase(int id,Customer customer) { this.id=id;this.customer=customer; }
    }
    private static final ThreadLocal<org.hibernate.Session> WEB_SESSION=new ThreadLocal<>();
    public static class Response {
        private final Purchase purchase;Response(Purchase purchase){this.purchase=purchase;}
        public String getCustomerName(){return purchase.customer.getName();}
    }
    @org.springframework.web.bind.annotation.RestController public static class WebController {
        @org.springframework.web.bind.annotation.GetMapping("/hibernate-response")
        public Response load(){return new Response(WEB_SESSION.get().find(Purchase.class,1));}
    }
    public static Service sharedService;
    private static SessionFactory enhancedFactory;
    private static Class<?> enhancedType;
    public static class Service {
        final SessionFactory factory; Service(SessionFactory factory) { this.factory=factory; }
        public String enhanced() throws Exception {
            try(var session=enhancedFactory.openSession()) {
                Object entity=session.find(enhancedType,1);HibernateInspectorProbe.enhanced(entity,false);
                String result=(String)enhancedType.getMethod("getText").invoke(entity);
                HibernateInspectorProbe.enhanced(entity,true);return result;
            }
        }
        public void rollback(){try(var session=factory.openSession()){var tx=session.beginTransaction();tx.rollback();}}
        public void stateless(){try(var session=factory.openStatelessSession()){session.get(Customer.class,1);}}
        public void overflow(){for(int i=0;i<600;i++)try(var session=factory.openSession()){session.find(Customer.class,1);}}
        public void detached(){Customer proxy;try(var session=factory.openSession()){proxy=session.getReference(Customer.class,1);}proxy.getName();}
        public void insertDelete(){try(var session=factory.openSession()){var tx=session.beginTransaction();var c=new Customer(99);session.persist(c);session.flush();session.remove(c);tx.commit();}}
        public List<String> cachedQuery() {
            try(var session=factory.openSession()) {return session.createQuery("select c.name from SqlCustomer c order by c.id",String.class).setCacheable(true).getResultList();}
        }
        public String cachedEntity() {try(var session=factory.openSession()){return session.find(Customer.class,1).name;}}
        public void update() {
            try(var session=factory.openSession()) {var tx=session.beginTransaction();var c=session.find(Customer.class,1);c.name="updated";session.flush();tx.commit();}
        }
        public int collection() {try(var session=factory.openSession()){return session.find(Customer.class,5).purchases.size();}}
        public void inspect() {
            Purchase purchase;Customer customer;
            try(var session=factory.openSession()) {
                purchase=session.find(Purchase.class,1);customer=session.find(Customer.class,5);
                HibernateInspectorProbe.inspect(purchase,purchase.customer,customer.purchases,true);
                SqlAgentProbe.check(!org.hibernate.Hibernate.isInitialized(purchase.customer)&&!org.hibernate.Hibernate.isInitialized(customer.purchases),"Inspector initialized a relationship");
            }
            HibernateInspectorProbe.inspect(purchase,purchase.customer,customer.purchases,false);
            SqlAgentProbe.check(!org.hibernate.Hibernate.isInitialized(purchase.customer)&&!org.hibernate.Hibernate.isInitialized(customer.purchases),"Detached inspector initialized a relationship");
        }
        public List<String> load(boolean joined) {
            try(var session=factory.openSession()) {
                var purchases=session.createQuery("select p from SqlPurchase p"+(joined ? " join fetch p.customer" : ""),Purchase.class).getResultList();
                List<String> result=new ArrayList<>();
                for(Purchase purchase:purchases) result.add(purchase.customer.getName());
                return result;
            }
        }
    }
    public static void clearCache(){sharedService.factory.getCache().evictAllRegions();}
    private static HibernateSnapshot record(ReplHandler handler,String owner,Runnable code) throws Exception {
        SqlAgentProbe.ok(handler,owner,"trace/record",Map.of("classes",Service.class.getName(),"sql","true","hibernate","true"));code.run();
        return HibernateSnapshot.decode(SqlAgentProbe.ok(handler,owner,"trace/stop",Map.of()).get("hibernate").toString());
    }
    static void verify() throws Exception {
        var ds=new org.h2.jdbcx.JdbcDataSource();ds.setURL("jdbc:h2:mem:sql-jpa;DB_CLOSE_DELAY=-1");
        var configuration=new org.hibernate.cfg.Configuration().addAnnotatedClass(Customer.class).addAnnotatedClass(Purchase.class);
        configuration.getProperties().put("hibernate.connection.datasource",ds);
        configuration.setProperty("hibernate.hbm2ddl.auto","create-drop");
        configuration.setProperty("hibernate.cache.region.factory_class",HibernateCacheFixture.class.getName());
        configuration.setProperty("hibernate.cache.use_second_level_cache","true");
        configuration.setProperty("hibernate.cache.use_query_cache","true");
        var inspected=new java.util.concurrent.atomic.AtomicInteger();
        configuration.getProperties().put("hibernate.session_factory.statement_inspector",(org.hibernate.resource.jdbc.spi.StatementInspector)sql->{inspected.incrementAndGet();return sql;});
        try(var factory=configuration.buildSessionFactory();var handler=new ReplHandler()) {
            try(var session=factory.openSession()) {
                var tx=session.beginTransaction();
                for(int i=1;i<=5;i++) { var customer=new Customer(i);session.persist(customer);session.persist(new Purchase(i,customer)); }
                tx.commit();
            }
            Service service=sharedService=new Service(factory);String owner=handler.createSession();factory.getCache().evictAllRegions();
            SqlAgentProbe.ok(handler,owner,"trace/record",Map.of("classes",Service.class.getName(),"sql","true","hibernate","true"));
            var expected=service.load(false);
            var response=SqlAgentProbe.ok(handler,owner,"trace/stop",Map.of());
            var before=SqlSnapshot.decode(response.get("sql").toString());
            var orm=hu.baader.repl.protocol.HibernateSnapshot.decode(response.get("hibernate").toString());
            SqlAgentProbe.check(!orm.partial() && orm.count("ENTITY_LOAD")==10 && orm.lazyCount()==5,orm);
            SqlAgentProbe.check(orm.events().stream().filter(e->e.kind().equals("LAZY_ENTITY")).allMatch(e->e.role().endsWith("Purchase.customer")),orm);
            SqlAgentProbe.check(before.events().stream().filter(e->e.kind().equals("SQL")).allMatch(e->e.orm()>0),before);
            SqlAgentProbe.check(before.count()==6 && before.findings().size()==1 && before.maxRepetitions()==5 && !before.partial(),before);
            SqlAgentProbe.ok(handler,owner,"trace/record",Map.of("classes",Service.class.getName(),"sql","true","hibernate","true"));
            var actual=service.load(true);
            var after=SqlSnapshot.decode(SqlAgentProbe.ok(handler,owner,"trace/stop",Map.of()).get("sql").toString());
            SqlAgentProbe.check(expected.equals(actual) && after.count()==1 && after.findings().isEmpty() && !after.partial(),after);
            SqlAgentProbe.check(orm.findings(before,5).stream().anyMatch(f->f.kind().equals("suspected-n-plus-one")&&f.events().size()==5),orm);
            SqlAgentProbe.check(!factory.getStatistics().isStatisticsEnabled(),"Global statistics were changed");
            SqlAgentProbe.check(inspected.get()>0,"Application SQL inspector was overwritten");
            factory.getCache().evictAllRegions();
            record(handler,owner,service::inspect);
            var inspect=SqlSnapshot.decode(SqlAgentProbe.ok(handler,owner,"trace/history",Map.of()).get("sql").toString());
            SqlAgentProbe.check(inspect.count()==2,inspect);
            var collection=record(handler,owner,()->service.collection());
            SqlAgentProbe.check(collection.count("COLLECTION_INIT")==1 && collection.events().stream().anyMatch(e->e.role().endsWith("Customer.purchases")),collection);
            factory.getCache().evictAllRegions();
            var cold=record(handler,owner,()->service.cachedEntity());
            var warm=record(handler,owner,()->service.cachedEntity());
            SqlAgentProbe.check(cold.count("CACHE_MISS")>=1 && warm.count("CACHE_HIT")>=1,warm);
            var coldQuery=record(handler,owner,()->service.cachedQuery());
            var warmQuery=record(handler,owner,()->service.cachedQuery());
            SqlAgentProbe.check(coldQuery.count("QUERY_CACHE_MISS")==1 && warmQuery.count("QUERY_CACHE_HIT")==1,warmQuery);
            SqlAgentProbe.check(SqlSnapshot.decode(SqlAgentProbe.ok(handler,owner,"trace/history",Map.of()).get("sql").toString()).count()==0,"Warm query still hit database");
            try(var context=new org.springframework.context.support.GenericApplicationContext()) {
                context.registerBean(Service.class,()->service);context.refresh();SpringContextHolder.set(context);
                SqlAgentProbe.ok(handler,owner,"session/reset",Map.of());
                SnapshotManager.save("hibernate-input",false);SnapshotManager.save("hibernate-expected",expected);
                var def=new HashMap<>(Map.of("name","hibernate-case","input","hibernate-input","expected","hibernate-expected","type","boolean","code","// exercise via result expression", "result-expression","hu.baader.repl.fixture.SqlJpaProbe.sharedService.load(input)","max-hibernate-loads","10","max-hibernate-lazy-loads","5","max-hibernate-flushes","0"));
                def.put("max-hibernate-response-lazy-loads","0");
                def.put("setup","hu.baader.repl.fixture.SqlJpaProbe.clearCache();");
                SqlAgentProbe.ok(handler,owner,"case/save",def);
                var passed=SqlAgentProbe.ok(handler,owner,"case/run",Map.of("name","hibernate-case"));SqlAgentProbe.check("PASSED".equals(passed.get("outcome")),passed);
                SqlAgentProbe.check("10".equals(passed.get("hibernate-loads").toString())&&"5".equals(passed.get("hibernate-lazy-loads").toString()),passed);
                HibernateInspectorProbe.exportedCase("hibernate-case");
                SpringContextHolder.set(context);SqlAgentProbe.ok(handler,owner,"session/reset",Map.of());
                def.put("max-hibernate-lazy-loads","4");SqlAgentProbe.ok(handler,owner,"case/save",def);
                var failed=SqlAgentProbe.ok(handler,owner,"case/run",Map.of("name","hibernate-case"));SqlAgentProbe.check("FAILED".equals(failed.get("outcome")),failed);
                HibernateInspectorProbe.exportedCase("hibernate-case",false);
                SpringContextHolder.clear(context);
            }
            SqlAgentProbe.ok(handler,owner,"session/reset",Map.of());
            var mvc=org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(new WebController()).addFilters((jakarta.servlet.Filter)(request,res,chain)->{
                try(var session=factory.openSession()){WEB_SESSION.set(session);chain.doFilter(request,res);}finally{WEB_SESSION.remove();}
            }).build();
            SqlAgentProbe.ok(handler,owner,"trace/record",Map.of("classes",WebController.class.getName(),"sql","true","hibernate","true"));
            var executor=java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                var tasks=new ArrayList<java.util.concurrent.Future<?>>();
                for(int i=0;i<2;i++)tasks.add(executor.submit(()->{try{
                    var res=mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/hibernate-response")).andReturn().getResponse();
                    SqlAgentProbe.check(res.getStatus()==200&&res.getContentAsString().contains("customer-1"),res.getContentAsString());
                }catch(Exception e){throw new RuntimeException(e);}}));
                for(var task:tasks)task.get(10,java.util.concurrent.TimeUnit.SECONDS);
            }finally{executor.shutdownNow();}
            var webResponse=SqlAgentProbe.ok(handler,owner,"trace/stop",Map.of());
            var web=HibernateSnapshot.decode(webResponse.get("hibernate").toString());var webSql=SqlSnapshot.decode(webResponse.get("sql").toString());
            SqlAgentProbe.check(web.responseLazyCount()==2&&!web.partial(),web);
            SqlAgentProbe.check(web.events().stream().map(e->e.root()).distinct().count()==2,web);
            for(var event:webSql.events())if(event.orm()>0)SqlAgentProbe.check(web.events().stream().anyMatch(e->e.id()==event.orm()&&e.root()==event.root()&&e.thread()==event.thread()),event);
            SqlAgentProbe.check(web.findings(webSql,5).stream().filter(f->f.kind().equals("lazy-during-response")).count()==2,web);
            var detached=record(handler,owner,()->{try{service.detached();throw new AssertionError("Lazy exception swallowed");}catch(org.hibernate.LazyInitializationException expectedFailure){}});
            SqlAgentProbe.check(detached.lazyCount()==1&&!detached.partial()&&detached.events().stream().anyMatch(e->e.error().contains("LazyInitializationException")),detached);
            var mutations=record(handler,owner,service::insertDelete);
            SqlAgentProbe.check(mutations.count("ENTITY_INSERT")==1&&mutations.count("ENTITY_DELETE")==1,mutations);
            var unsupported=record(handler,owner,service::stateless);
            SqlAgentProbe.check(unsupported.partial()&&!unsupported.available(),unsupported);
            var update=record(handler,owner,service::update);
            SqlAgentProbe.check(!update.partial(),"Unsupported session poisoned a later stateful recording: "+update);
            SqlAgentProbe.check(update.count("ENTITY_UPDATE")==1 && update.flushCount()>=1 && update.count("DIRTY_CHECK")>0 && update.events().stream().anyMatch(e->e.kind().equals("ENTITY_UPDATE")&&e.detail().contains("name")),update);
            SqlAgentProbe.check(update.count("TRANSACTION")==1,update);
            SqlAgentProbe.check(update.count("TRANSACTION_BEGIN")==1&&update.count("TRANSACTION_COMMIT")==1,update);
            var rollback=record(handler,owner,service::rollback);
            SqlAgentProbe.check(rollback.count("TRANSACTION_ROLLBACK")==1&&rollback.events().stream().anyMatch(e->e.kind().equals("TRANSACTION")&&e.detail().equals("successful=false")),rollback);
            enhancedVerify(handler,owner,service);
            var limited=record(handler,owner,service::overflow);
            SqlAgentProbe.check(limited.dropped()>0&&limited.partial()&&limited.pending()==0&&limited.events().size()==HibernateSnapshot.MAX_EVENTS,limited);
            System.out.println("SQL_HIBERNATE_N_PLUS_ONE_6_TO_1_OK · ORM_INSPECT_CACHE_FLUSH_CASE_JUNIT_OK");
        }
    }
    private static void enhancedVerify(ReplHandler handler,String owner,Service service) throws Exception {
        String name="hu.baader.repl.fixture.EnhancedDocument";
        byte[] original;try(var in=SqlJpaProbe.class.getResourceAsStream("/"+name.replace('.','/')+".class")){original=in.readAllBytes();}
        var provider=new org.hibernate.bytecode.internal.bytebuddy.BytecodeProviderImpl();
        byte[] enhanced=provider.getEnhancer(new org.hibernate.bytecode.enhance.spi.DefaultEnhancementContext()).enhance(name,original);
        var loader=new ClassLoader(SqlJpaProbe.class.getClassLoader()) {Class<?> define(){return defineClass(name,enhanced,0,enhanced.length);}};
        enhancedType=loader.define();ClassLoader previous=Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(loader);
        var ds=new org.h2.jdbcx.JdbcDataSource();ds.setURL("jdbc:h2:mem:enhanced;DB_CLOSE_DELAY=-1");
        var bootstrap=new org.hibernate.boot.registry.BootstrapServiceRegistryBuilder().applyClassLoader(loader).build();
        var configuration=new org.hibernate.cfg.Configuration(bootstrap).addAnnotatedClass(enhancedType);
        configuration.getProperties().put("hibernate.connection.datasource",ds);configuration.setProperty("hibernate.hbm2ddl.auto","create-drop");
        try(var factory=configuration.buildSessionFactory()) {
            enhancedFactory=factory;
            try(var session=factory.openSession()){
                var tx=session.beginTransaction();Object value=enhancedType.getConstructor().newInstance();enhancedType.getField("id").setInt(value,1);enhancedType.getField("text").set(value,"stored content");session.persist(value);tx.commit();
            }
            var orm=record(handler,owner,()->{try{SqlAgentProbe.check(service.enhanced().equals("stored content"),"Enhanced value changed");}catch(Exception e){throw new RuntimeException(e);}});
            var sql=SqlSnapshot.decode(SqlAgentProbe.ok(handler,owner,"trace/history",Map.of()).get("sql").toString());
            SqlAgentProbe.check(!orm.partial()&&orm.count("LAZY_ATTRIBUTE")==1&&orm.events().stream().anyMatch(e->e.role().equals(name+".text")),orm);
            SqlAgentProbe.check(sql.count()==2,"Inspector caused extra SQL: "+sql);
        }finally{enhancedFactory=null;enhancedType=null;Thread.currentThread().setContextClassLoader(previous);provider.resetCaches();}
    }
}
