package hu.baader.repl.fixture;

import org.hibernate.cache.spi.support.*;
import org.hibernate.engine.spi.*;
import org.hibernate.cache.cfg.spi.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic in-memory storage; Hibernate's real L2/query-cache algorithms operate above it. */
public final class HibernateCacheFixture extends RegionFactoryTemplate {
    protected void prepareForUse(org.hibernate.boot.spi.SessionFactoryOptions options,Map<String,Object> settings) {}
    protected void releaseFromUse() {}
    protected DomainDataStorageAccess createDomainDataStorageAccess(DomainDataRegionConfig config,DomainDataRegionBuildingContext context){return new Storage();}
    protected StorageAccess createQueryResultsRegionStorageAccess(String name,SessionFactoryImplementor factory){return new Storage();}
    protected StorageAccess createTimestampsRegionStorageAccess(String name,SessionFactoryImplementor factory){return new Storage();}
    private static final class Storage implements DomainDataStorageAccess {
        final Map<Object,Object> values=new ConcurrentHashMap<>();
        public Object getFromCache(Object key,SharedSessionContractImplementor session){return values.get(key);}
        public void putIntoCache(Object key,Object value,SharedSessionContractImplementor session){values.put(key,value);}
        public boolean contains(Object key){return values.containsKey(key);}
        public void evictData(){values.clear();}
        public void evictData(Object key){values.remove(key);}
        public void release(){values.clear();}
    }
}
