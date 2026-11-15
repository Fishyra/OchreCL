package io.ochre.bridge;
import java.util.*;
/// Tests cursor paging and overflow so polling scripts do not silently receive fabricated complete histories.
public final class ObservationHistoryTest {
 public static void main(String[] args){ObservationHistory h=new ObservationHistory();Map<String,String> data=new HashMap<>();h.read(0,data);check(data.get("count").equals("0"),"initially empty");for(int i=0;i<300;i++)h.add("move",Map.of("distance","1"));data.clear();h.read(0,data);check(data.get("missed").equals("true"),"explicit overflow");check(Integer.parseInt(data.get("count"))>0&&Integer.parseInt(data.get("count"))<=64&&data.size()<=256&&data.get("event.0.sequence").equals("45"),"bounded oldest retained page");long cursor=Long.parseLong(data.get("cursor"));data.clear();h.read(cursor,data);check(data.get("event.0.sequence").equals(Long.toString(cursor+1)),"no duplicate events");data.clear();h.read(300,data);check(data.get("count").equals("0")&&data.get("missed").equals("false"),"caught up");System.out.println("PASS: event retention, overflow and independent cursor pages");}
 private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
