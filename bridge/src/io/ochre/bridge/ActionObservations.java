package io.ochre.bridge;

import java.util.*;
import static io.ochre.bridge.ActionSupport.*;

/// Passive client-thread observations. Packets and client predictions carry distinct evidence labels.
public final class ActionObservations {
 private static volatile Object client;
 private static final Map<String,ArrayDeque<Map<String,String>>> pending=new HashMap<>();
 private static final List<Map<String,String>> placements=new ArrayList<>();
 private static double mx,my,dx,dy,sx,sy;private static boolean pointer;
 private ActionObservations() { }
 static void ensure(Object value){client=value;}
 private static boolean thread(Object c){try{return Boolean.TRUE.equals(call(c,"isSameThread"));}catch(Exception e){return false;}}
 static void event(String type,Map<String,String> fields){PlayerObservations.event(type,fields);}
 public static void input(Object c,double x,double y,int kind){if(c!=client||!thread(c))return;try{Object window=call(c,"getWindow");Class<?> glfw=type(c,"org.lwjgl.glfw.GLFW");long handle=((Number)call(window,"handle")).longValue();if(((Number)glfw.getMethod("glfwGetWindowAttrib",long.class,int.class).invoke(null,handle,0x20001)).intValue()!=1){pointer=false;return;}if(kind==1){sx+=x;sy+=y;}else{if(pointer){dx+=x-mx;dy+=y-my;}mx=x;my=y;pointer=true;}}catch(Exception ignored){}}
 private static Map<String,String> block(Object c,Object pos)throws Exception{Object level=field(c,"level");if(level==null||!Boolean.TRUE.equals(level.getClass().getMethod("hasChunkAt",type(c,"net.minecraft.core.BlockPos")).invoke(level,pos)))return Map.of();Object state=level.getClass().getMethod("getBlockState",type(c,"net.minecraft.core.BlockPos")).invoke(level,pos);Map<String,String> data=new LinkedHashMap<>();for(String axis:List.of("X","Y","Z"))data.put(axis.toLowerCase(Locale.ROOT),call(pos,"get"+axis).toString());data.put("world",world(c));data.put("block_id",id(call(state,"getBlock")));data.put("block_state",state.toString());return data;}
 private static Map<String,String> item(Object p)throws Exception{Object stack=call(p,"getUseItem");return Map.of("item",Boolean.TRUE.equals(call(stack,"isEmpty"))?"minecraft:air":id(call(stack,"getItem")),"animation",call(stack,"getUseAnimation").toString().toLowerCase(Locale.ROOT));}
 private static void push(String name,Map<String,String> data){var queue=pending.computeIfAbsent(name,k->new ArrayDeque<>());if(queue.size()>=8)queue.removeLast();queue.push(data);}
 private static Map<String,String> pop(String name){var queue=pending.get(name);return queue==null||queue.isEmpty()?Map.of():queue.pop();}
 /// Observers never change arguments, results, permissions or control flow of the original game method.
 public static void action(Object source,Object data,Object extra,String kind){Object c=client;if(c==null||!thread(c))return;try{
  Object p=field(c,"player");if(kind.startsWith("mouse_button:")){if(source==c)event("mouse_button",Map.of("button",call(data,"button").toString(),"action",kind.substring(13)));return;}if(p==null||field(c,"level")==null)return;
  boolean mode=source==field(c,"gameMode"),connection=source==call(c,"getConnection");
  switch(kind){
   case "attack"->{if(mode)event("attack_attempt",Map.of("target_id",call(data,"getId").toString(),"target_uuid",call(data,"getUUID").toString(),"evidence","client_request"));}
   case "damage"->{if(connection){String target=call(data,"entityId").toString(),cause=call(data,"sourceCauseId").toString(),direct=call(data,"sourceDirectId").toString(),self=call(p,"getId").toString();Map<String,String> fields=new LinkedHashMap<>(Map.of("target_id",target,"source_id",cause,"direct_id",direct,"evidence","server_damage_event"));Object holder=call(data,"sourceType");Optional<?> key=(Optional<?>)call(holder,"unwrapKey");if(key.isPresent())fields.put("damage_type",call(key.get(),"identifier").toString());if(target.equals(self))event("hurt",fields);if(cause.equals(self)&&!target.equals(self))event("attack_hit",fields);}}
   case "consume_before"->{if(source==p)push("consume",item(p));}
   case "consume_after"->{if(source==p){var before=pop("consume");if(!before.isEmpty()){Map<String,String> fields=new LinkedHashMap<>(before);fields.put("evidence","client_use_completion");event("item_use_complete",fields);if("eat".equals(before.get("animation")))event("food_consumed",fields);}}}
   case "use_before"->{if(mode){Object stack=p.getClass().getMethod("getItemInHand",type(c,"net.minecraft.world.InteractionHand")).invoke(p,data);push("use",Map.of("item",id(call(stack,"getItem")),"hand",data.toString().toLowerCase(Locale.ROOT)));}}
   case "use_after"->{if(mode){var before=pop("use");if(!before.isEmpty()){Map<String,String> fields=new LinkedHashMap<>(before);fields.put("accepted",call(data,"consumesAction").toString());fields.put("evidence","client_result");event("item_use_result",fields);}}}
   case "break_before"->{if(mode)push("break",block(c,data));}
   case "break_after"->{if(mode){var before=pop("break");if(!before.isEmpty()&&Boolean.TRUE.equals(data)){Map<String,String> fields=new LinkedHashMap<>(before);fields.put("evidence","client_prediction");event("block_break",fields);}}}
   case "place_before"->{if(mode){Object pos=call(data,"getBlockPos"),direction=call(data,"getDirection"),adjacent=pos.getClass().getMethod("relative",type(c,"net.minecraft.core.Direction")).invoke(pos,direction);Object stack=p.getClass().getMethod("getItemInHand",type(c,"net.minecraft.world.InteractionHand")).invoke(p,extra);Object held=call(stack,"getItem");Map<String,String> record=new LinkedHashMap<>(block(c,pos));record.put("item",id(held));record.put("face",direction.toString().toLowerCase(Locale.ROOT));record.put("hand",extra.toString().toLowerCase(Locale.ROOT));record.put("evidence","client_request");event("block_interact",record);if(type(c,"net.minecraft.world.item.BlockItem").isInstance(held)){record.put("expected",id(call(held,"getBlock")));var other=block(c,adjacent);for(var entry:other.entrySet())record.put("adjacent."+entry.getKey(),entry.getValue());}push("place",record);}}
   case "place_after"->{if(mode){var before=pop("place");if(!before.isEmpty()&&before.containsKey("expected")){Map<String,String> record=new LinkedHashMap<>(before);record.put("accepted",call(data,"consumesAction").toString());record.put("evidence","client_result");event("block_place_result",record);if("true".equals(record.get("accepted"))){record.put("deadline",Long.toString(System.nanoTime()+2_000_000_000L));if(placements.size()<32)placements.add(record);}}}}
  }
 }catch(Exception ignored){} }
 static void flush(Object c)throws Exception{if(dx!=0||dy!=0){event("mouse_move",Map.of("x",Double.toString(mx),"y",Double.toString(my),"dx",Double.toString(dx),"dy",Double.toString(dy)));dx=dy=0;}if(sx!=0||sy!=0){event("mouse_scroll",Map.of("dx",Double.toString(sx),"dy",Double.toString(sy)));sx=sy=0;}
  for(var record:List.copyOf(placements)){boolean done=false;if(field(c,"level")==null||!world(c).equals(record.get("world"))){placements.remove(record);continue;}for(String prefix:List.of("","adjacent.")){if(!record.containsKey(prefix+"x"))continue;Map<String,String> query=Map.of("x",record.get(prefix+"x"),"y",record.get(prefix+"y"),"z",record.get(prefix+"z"));var now=block(c,position(c,query));if(record.get("expected").equals(now.get("block_id"))&&!Objects.equals(now.get("block_state"),record.get(prefix+"block_state"))){Map<String,String> result=new LinkedHashMap<>(now);result.put("item",record.get("item"));result.put("face",record.get("face"));result.put("evidence","client_observed_change");event("block_place",result);done=true;break;}}if(done||System.nanoTime()>Long.parseLong(record.get("deadline")))placements.remove(record);}
 }
}
