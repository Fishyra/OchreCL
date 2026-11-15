package io.ochre.bridge;

import java.util.*;

/// Bounded ordered observations with explicit overflow, independent of rendering and script polling frequency.
final class ObservationHistory {
 private final ArrayDeque<Map<String,String>> events=new ArrayDeque<>();private long sequence;
 void add(String type,Map<String,String> fields){Map<String,String> event=new LinkedHashMap<>(fields);event.put("type",type);event.put("sequence",Long.toString(++sequence));event.put("time",Double.toString(System.nanoTime()/1e9));events.addLast(Map.copyOf(event));while(events.size()>256)events.removeFirst();}
 void read(long cursor,Map<String,String> result){long oldest=events.isEmpty()?sequence+1:Long.parseLong(events.getFirst().get("sequence"));result.put("missed",Boolean.toString(cursor<oldest-1));int count=0;long next=cursor;for(var event:events){long seq=Long.parseLong(event.get("sequence"));if(seq<=cursor)continue;if(count>=64||result.size()+event.size()>220)break;String prefix="event."+count+++".";for(var entry:event.entrySet())result.put(prefix+entry.getKey(),entry.getValue());next=seq;}result.put("count",Integer.toString(count));result.put("cursor",Long.toString(Math.max(next,Math.min(cursor,sequence))));}
}
