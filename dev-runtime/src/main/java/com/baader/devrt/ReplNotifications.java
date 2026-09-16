package com.baader.devrt;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Metadata-only journal; never retains code, values, messages, source paths or application references. */
final class ReplNotifications {
    private static final Map<String,Journal> JOURNALS=new ConcurrentHashMap<>();
    private static final class Journal {
        long sequence;final Deque<Map<String,Object>> events=new ArrayDeque<>();
        synchronized void add(String kind,Map<String,Object> fields){
            var event=new LinkedHashMap<String,Object>();event.put("sequence",++sequence);event.put("time",System.currentTimeMillis());
            event.put("kind",kind);event.put("contextEpoch",SpringContextHolder.epoch());event.putAll(fields);
            while(events.size()>=128)events.removeFirst();events.addLast(Map.copyOf(event));
        }
        synchronized Map<String,Object> read(long cursor){
            if(cursor<0||cursor>sequence)throw new IllegalArgumentException("Invalid notification cursor; use 0 for a fresh journal");
            long first=events.isEmpty()?sequence+1:((Number)events.peekFirst().get("sequence")).longValue();
            return Map.of("cursor",sequence,"gap",cursor<first-1,"events-json",CaseJson.write(events.stream().filter(e->((Number)e.get("sequence")).longValue()>cursor).toList()));
        }
    }
    private ReplNotifications(){}
    static void register(String owner){JOURNALS.put(owner,new Journal());}
    static void release(String owner){JOURNALS.remove(owner);}
    static void publish(String owner,String kind,Map<String,Object> fields){Journal journal=JOURNALS.get(owner);if(journal!=null)journal.add(kind,fields);}
    static Map<String,Object> poll(String owner,long cursor){Journal journal=JOURNALS.get(owner);if(journal==null)throw new IllegalArgumentException("Notification session closed");return journal.read(cursor);}
}
