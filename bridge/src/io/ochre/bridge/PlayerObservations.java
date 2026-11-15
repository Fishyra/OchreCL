package io.ochre.bridge;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static io.ochre.bridge.ActionSupport.*;

/// Samples actual client state at up to 10 Hz on the client thread, without depending on the overlay renderer.
final class PlayerObservations {
 private static final ObservationHistory history=new ObservationHistory();private static Map<String,String> previous=Map.of();
 private static volatile Object client;private static final AtomicBoolean queued=new AtomicBoolean();
 private static final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(task->{Thread thread=new Thread(task,"OchreBridge observations");thread.setDaemon(true);return thread;});
 static {timer.scheduleWithFixedDelay(()->{Object target=client;if(target instanceof Executor executor&&queued.compareAndSet(false,true))try{executor.execute(()->{try{sample(target);}catch(Exception ignored){}finally{queued.set(false);}});}catch(Exception ignored){queued.set(false);}},100,100,TimeUnit.MILLISECONDS);}
 private PlayerObservations() { }
 static void ensure(Object value){client=value;ActionObservations.ensure(value);}
 static void event(String type,Map<String,String> fields){history.add(type,fields);}
 static Map<String,String> snapshot(Object client)throws Exception {
  Object player=field(client,"player"),level=field(client,"level");if(player==null||level==null)return Map.of("in_world","false");
  Map<String,String> data=new LinkedHashMap<>();ObservationSnapshots.append(data,"vital.",ObservationSnapshots.vitals(client));ObservationSnapshots.append(data,"container.",ObservationSnapshots.container(client));ObservationSnapshots.append(data,"screen.",ObservationSnapshots.screen(client));data.put("in_world","true");data.put("world",world(client));for(String axis:List.of("X","Y","Z"))data.put(axis.toLowerCase(Locale.ROOT),call(player,"get"+axis).toString());data.put("yaw",call(player,"getYRot").toString());data.put("pitch",call(player,"getXRot").toString());
  data.put("slot",call(call(player,"getInventory"),"getSelectedSlot").toString());Object stack=call(player,"getMainHandItem");data.put("item",(Boolean)call(stack,"isEmpty")?"minecraft:air":id(call(stack,"getItem")));data.put("count",call(stack,"getCount").toString());
  InputSnapshot.bindings(client,data);
  Map<String,String> states=new LinkedHashMap<>();ExtendedActions.state(player,states);states.forEach((key,value)->{if(key.startsWith("state."))data.put(key,value);});return Map.copyOf(data);
 }
 static void sample(Object client)throws Exception {
  ActionObservations.flush(client); sampleInput(client); sampleScreen(client); Map<String,String> next=snapshot(client);boolean present="true".equals(next.get("in_world"));boolean prior="true".equals(previous.get("in_world"));
  if(!present){if(prior){if("true".equals(previous.get("container.open")))containerEvent("container_close",previous);history.add("world_leave",Map.of("world",previous.get("world")));}previous=next;return;}
  if(!prior||!Objects.equals(next.get("world"),previous.get("world"))){history.add("world_enter",next);previous=next;return;}
  double dx=Double.parseDouble(next.get("x"))-Double.parseDouble(previous.get("x")),dy=Double.parseDouble(next.get("y"))-Double.parseDouble(previous.get("y")),dz=Double.parseDouble(next.get("z"))-Double.parseDouble(previous.get("z"));double distance=Math.sqrt(dx*dx+dy*dy+dz*dz);
  if(distance>.001){Map<String,String> movement=new LinkedHashMap<>();for(String axis:List.of("x","y","z","world"))movement.put(axis,next.get(axis));movement.put("distance",Double.toString(distance));movement.put("dx",Double.toString(dx));movement.put("dy",Double.toString(dy));movement.put("dz",Double.toString(dz));history.add("move",movement);}
  if(!next.get("slot").equals(previous.get("slot")))history.add("hotbar",Map.of("from_slot",previous.get("slot"),"to_slot",next.get("slot"),"item",next.get("item")));
  if(!next.get("item").equals(previous.get("item"))||!next.get("count").equals(previous.get("count")))history.add("held_item",Map.of("from_item",previous.get("item"),"item",next.get("item"),"count",next.get("count")));
  for(String name:List.of("swimming","sprinting","crouching","underwater","fallflying","on_ground","flying","jumping")){String key="state."+name;if(next.containsKey(key)&&!Objects.equals(next.get(key),previous.get(key)))history.add("state",Map.of("name",name,"from",previous.getOrDefault(key,"unknown"),"to",next.get(key)));}
  if(!Objects.equals(next.get("yaw"),previous.get("yaw"))||!Objects.equals(next.get("pitch"),previous.get("pitch")))history.add("look",Map.of("yaw",next.get("yaw"),"pitch",next.get("pitch"),"from_yaw",previous.get("yaw"),"from_pitch",previous.get("pitch")));
  for(String name:List.of("health","max_health","absorption","food","saturation","armor","air","xp_level","xp_progress","alive","on_fire","in_water","in_lava","freezing","frozen_ticks","fall_distance","passenger")){String key="vital."+name;if(!Objects.equals(next.get(key),previous.get(key)))history.add("vital_change",Map.of("name",name,"from",previous.get(key),"to",next.get(key)));}
  if(!Objects.equals(next.get("vital.using_item"),previous.get("vital.using_item")))history.add(Boolean.parseBoolean(next.get("vital.using_item"))?"item_use_start":"item_use_stop",Map.of("item",Boolean.parseBoolean(next.get("vital.using_item"))?next.get("vital.using_item_id"):previous.get("vital.using_item_id"),"animation",Boolean.parseBoolean(next.get("vital.using_item"))?next.get("vital.use_animation"):previous.get("vital.use_animation"),"evidence","sampled_state"));
  if(!Objects.equals(next.get("container.identity"),previous.get("container.identity"))){if("true".equals(previous.get("container.open")))containerEvent("container_close",previous);if("true".equals(next.get("container.open")))containerEvent("container_open",next);}
  for(String name:List.of("forward","backward","left","right","jump","sneak","sprint","attack","use")){String key="input."+name;if(!Objects.equals(next.get(key),previous.get(key)))history.add("input_change",Map.of("name",name,"from",previous.get(key),"to",next.get(key)));}
  previous=next;
 }
 private static Map<String,String> oldScreen=Map.of(),oldKeys=Map.of();
 private static void containerEvent(String type,Map<String,String> data){Map<String,String> fields=new LinkedHashMap<>();data.forEach((k,v)->{if(k.startsWith("container."))fields.put(k.equals("container.type")?"container_type":k.substring(10),v);});history.add(type,fields);}
 private static Map<String,String> screenFields(Map<String,String> data){Map<String,String> result=new LinkedHashMap<>(data);result.put("screen_type",result.remove("type"));return result;}
 private static void sampleScreen(Object c)throws Exception{var next=ObservationSnapshots.screen(c);if(!Objects.equals(oldScreen.get("identity"),next.get("identity"))){if("true".equals(oldScreen.get("open")))history.add("screen_close",screenFields(oldScreen));if("true".equals(next.get("open")))history.add("screen_open",screenFields(next));}oldScreen=next;}
 private static void sampleInput(Object c)throws Exception{Map<String,String> raw=new LinkedHashMap<>();InputSnapshot.read(c,raw);Map<String,String> next=new LinkedHashMap<>();for(int i=0;i<Integer.parseInt(raw.get("keys"));i++)next.put("key:"+raw.get("key."+i+".code"),raw.get("key."+i+".name"));for(int i=0;i<Integer.parseInt(raw.get("mouse_buttons"));i++)next.put("mouse:"+raw.get("mouse."+i),"mouse");Set<String> union=new LinkedHashSet<>(next.keySet());union.addAll(oldKeys.keySet());for(String key:union)if(next.containsKey(key)!=oldKeys.containsKey(key))history.add(next.containsKey(key)?"input_press":"input_release",Map.of("device",key.startsWith("key:")?"keyboard":"mouse","code",key.substring(key.indexOf(':')+1),"name",next.getOrDefault(key,oldKeys.getOrDefault(key,"")),"evidence","sampled_input"));oldKeys=Map.copyOf(next);}

 static void events(Object client,Map<String,String> request,Map<String,String> response)throws Exception{sample(client);long cursor;try{cursor=Long.parseLong(request.getOrDefault("cursor","0"));if(cursor<0)throw new NumberFormatException();}catch(NumberFormatException error){throw new java.io.IOException("event.cursor.invalid");}history.read(cursor,response);}
}
