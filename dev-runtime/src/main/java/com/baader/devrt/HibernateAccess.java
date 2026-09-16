package com.baader.devrt;

import java.lang.ref.*;
import java.lang.reflect.*;
import java.util.*;

/** Hibernate 6.6 metadata only. Never invokes entity getters, Hibernate.initialize, size or iterators. */
final class HibernateAccess {
    record Entity(String name, WeakReference<Object> session) {}
    record View(Map<String,String> metadata,Object contents,boolean opaque,Set<String> unfetched) {
        View(Map<String,String> metadata,Object contents,boolean opaque){this(metadata,contents,opaque,Set.of());}
    }
    static final WeakIdentity<Entity> ENTITIES=new WeakIdentity<>();
    static final WeakIdentity<String> ROLES=new WeakIdentity<>();
    private static final ClassValue<String> VERSIONS=new ClassValue<>() {
        protected String computeValue(Class<?> type) {
            try { return String.valueOf(Class.forName("org.hibernate.Version",false,type.getClassLoader()).getMethod("getVersionString").invoke(null)); }
            catch(ReflectiveOperationException|LinkageError e) { return "unavailable"; }
        }
    };
    static String version(Object object) { return VERSIONS.get(object.getClass()); }
    static Object get(Object object,String method,Object...args) throws ReflectiveOperationException {
        if(object==null) return null;
        for(Method m:object.getClass().getMethods()) if(m.getName().equals(method)&&m.getParameterCount()==args.length) {
            try { if(!m.canAccess(object)) m.trySetAccessible(); return m.invoke(object,args); }
            catch(IllegalArgumentException ignored) { /* Try another overload. */ }
        }
        throw new NoSuchMethodException(method);
    }
    static Object field(Object object,String name) {
        if(object==null)return null;
        for(Class<?> c=object.getClass();c!=null;c=c.getSuperclass()) try {
            Field f=c.getDeclaredField(name); if(!Modifier.isStatic(f.getModifiers())&&f.trySetAccessible())return f.get(object);
        } catch(ReflectiveOperationException|RuntimeException ignored) { }
        return null;
    }
    static boolean is(Object value,String name) {
        return value!=null&&isType(value.getClass(),name);
    }
    private static boolean isType(Class<?> c,String name) {
        if(c==null)return false;
        if(c.getName().equals(name))return true;
        for(Class<?> i:c.getInterfaces())if(isType(i,name))return true;
        return isType(c.getSuperclass(),name);
    }
    static void observedEntity(Object entity,Object persister,Object session) throws ReflectiveOperationException {
        if(entity==null)return;
        String name=(String)get(persister,"getEntityName");
        ENTITIES.put(entity,new Entity(name,new WeakReference<>(session)));
        String[] names=(String[])get(persister,"getPropertyNames");
        for(String property:names) {
            Object value=field(entity,property);
            if(is(value,"org.hibernate.proxy.HibernateProxy")) {
                Object initializer=get(value,"getHibernateLazyInitializer");
                String role=name+"."+property;
                String previous=ROLES.get(initializer);
                // A shared proxy may be reachable through several associations: do not invent one owner.
                ROLES.put(initializer,previous==null||previous.equals(role)?role:"<multiple associations>");
            }
        }
    }
    static View view(Object value) {
        if(value==null)return null;
        boolean proxy=is(value,"org.hibernate.proxy.HibernateProxy");
        boolean collection=is(value,"org.hibernate.collection.spi.PersistentCollection");
        boolean enhanced=is(value,"org.hibernate.engine.spi.PersistentAttributeInterceptable");
        Entity tracked=ENTITIES.get(value);
        if(!proxy&&!collection&&!enhanced&&tracked==null)return null;
        Map<String,String> meta=new LinkedHashMap<>();
        Set<String> unfetched=new TreeSet<>();
        try {
            String version=version(value);meta.put("version",version);
            if(!version.startsWith("6.6.")) { meta.put("state","Unsupported Hibernate version; value not expanded");return new View(meta,value,true); }
            Object session=tracked==null?null:tracked.session().get();
            Object contents=value;boolean opaque=false;
            if(proxy) {
                Object lazy=get(value,"getHibernateLazyInitializer");
                boolean initialized=!Boolean.TRUE.equals(get(lazy,"isUninitialized"));
                meta.put("kind","entity proxy");meta.put("entity",Objects.toString(get(lazy,"getEntityName"),""));
                meta.put("initialized",""+initialized);meta.put("role",Objects.toString(ROLES.get(lazy),"unknown"));
                session=get(lazy,"getSession");
                if(initialized) contents=field(lazy,"target");else opaque=true;
            } else if(collection) {
                boolean initialized=Boolean.TRUE.equals(get(value,"wasInitialized"));
                meta.put("kind","persistent collection");meta.put("role",Objects.toString(get(value,"getRole"),""));
                meta.put("initialized",""+initialized);session=get(value,"getSession");
                opaque=true;
                if(initialized) for(String name:List.of("collection","bag","list","set","map","array")) {
                    Object data=field(value,name);
                    if(data!=null&&data!=value) { contents=data;opaque=false;break; }
                }
            } else {
                meta.put("kind",enhanced?"enhanced entity":"entity");
                meta.put("entity",tracked==null?value.getClass().getName():tracked.name());
                if(enhanced) {
                    Object interceptor=get(value,"$$_hibernate_getInterceptor");
                    if(interceptor!=null&&is(interceptor,"org.hibernate.bytecode.enhance.spi.interceptor.LazyAttributeLoadingInterceptor")) {
                        Object lazyFields=field(interceptor,"lazyFields");
                        if(lazyFields instanceof Set<?> fields)for(Object property:fields)if(property instanceof String name&&!Boolean.TRUE.equals(get(interceptor,"isAttributeLoaded",name)))unfetched.add(name);
                        meta.put("unfetchedAttributes",String.join(", ",unfetched));
                        meta.put("initializedLazyAttributes",Objects.toString(get(interceptor,"getInitializedLazyAttributeNames"),""));
                        session=get(interceptor,"getLinkedSession");
                    } else if(interceptor!=null&&is(interceptor,"org.hibernate.bytecode.enhance.spi.interceptor.EnhancementAsProxyLazinessInterceptor")) {
                        opaque=!Boolean.TRUE.equals(get(interceptor,"isInitialized"));
                        meta.put("kind","enhanced entity proxy");meta.put("initialized",""+!opaque);
                        meta.put("entity",Objects.toString(get(interceptor,"getEntityName"),""));session=get(interceptor,"getLinkedSession");
                    }
                }
            }
            boolean open=session!=null&&Boolean.TRUE.equals(get(session,"isOpen"));
            String state=tracked==null&&!proxy&&!collection?"unknown (not observed in a session)":"detached";
            if(open) {
                if(collection||opaque)state="attached";
                else {
                    Object pc=get(session,"getPersistenceContextInternal");
                    Object entry=get(pc,"getEntry",contents);
                    if(entry!=null)state=Objects.toString(get(entry,"getStatus"),"managed").toLowerCase(Locale.ROOT);
                }
            }
            meta.put("state",state);
            return new View(Map.copyOf(meta),contents,opaque||contents==null,Set.copyOf(unfetched));
        } catch(ReflectiveOperationException|RuntimeException|LinkageError e) {
            meta.put("state","Hibernate metadata unavailable: "+e.getClass().getSimpleName());
            return new View(Map.copyOf(meta),value,true);
        }
    }
    static final class WeakIdentity<V> {
        private final ReferenceQueue<Object> queue=new ReferenceQueue<>();
        private final Map<Key,V> values=new HashMap<>();
        synchronized V get(Object key) { clean();return key==null?null:values.get(new Key(key,null)); }
        synchronized V remove(Object key) { clean();return key==null?null:values.remove(new Key(key,null)); }
        synchronized void put(Object key,V value) { if(key==null)return;clean();if(values.size()>=10000)values.remove(values.keySet().iterator().next());values.put(new Key(key,queue),value); }
        private void clean() { Reference<?> k;while((k=queue.poll())!=null)values.remove(k); }
        private static final class Key extends WeakReference<Object> {
            final int hash;Key(Object k,ReferenceQueue<Object> q) { super(k,q);hash=System.identityHashCode(k); }
            public int hashCode(){return hash;}
            public boolean equals(Object o){return this==o||o instanceof Key k&&get()!=null&&get()==k.get();}
        }
    }
}
