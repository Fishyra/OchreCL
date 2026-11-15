package io.ochre.bridge;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static io.ochre.bridge.ActionSupport.*;

/// Game-local input events, with short owner-scoped leases and no operating-system injection.
final class InputActions {
 private record Held(Object mapping, int code) { }
 private static final List<Held> held=new ArrayList<>();
 private static String owner=""; private static long expires; private static Object activeClient, activeScreen, activeLevel;
 private static final AtomicBoolean queued=new AtomicBoolean();
 private static final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(task->{Thread t=new Thread(task,"OchreBridge input lease");t.setDaemon(true);return t;});
 static {timer.scheduleWithFixedDelay(()->{Object c=activeClient;if(c instanceof Executor executor&&queued.compareAndSet(false,true))try{executor.execute(()->{try{if(!owner.isEmpty()&&(System.nanoTime()>expires||screen(c)!=activeScreen||field(c,"level")!=activeLevel||(Boolean)call(c,"isPaused")))release();}catch(Exception ignored){}finally{queued.set(false);}});}catch(Exception ignored){queued.set(false);}},100,100,TimeUnit.MILLISECONDS);}
 private InputActions() { }
 static Object screen(Object client)throws Exception{return call(field(client,"gui"),"screen");}
 static int code(String value)throws IOException{
  String key=value.toUpperCase(Locale.ROOT);if(key.length()==1&&"ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".contains(key))return key.charAt(0);
  Map<String,Integer> names=Map.ofEntries(Map.entry("SPACE",32),Map.entry("ESCAPE",256),Map.entry("ENTER",257),Map.entry("TAB",258),Map.entry("BACKSPACE",259),Map.entry("DELETE",261),Map.entry("RIGHT",262),Map.entry("LEFT",263),Map.entry("DOWN",264),Map.entry("UP",265),Map.entry("HOME",268),Map.entry("END",269),Map.entry("SHIFT",340),Map.entry("CONTROL",341),Map.entry("ALT",342));
  if(names.containsKey(key))return names.get(key);try{int n=Integer.parseInt(value);if(n>=32&&n<=348)return n;}catch(NumberFormatException ignored){}throw new IOException("input.key.invalid");
 }
 private static Object event(Object client,int key,int modifiers)throws Exception{return type(client,"net.minecraft.client.input.KeyEvent").getConstructor(int.class,int.class,int.class).newInstance(key,0,modifiers);}
 private static void down(Object mapping,boolean value)throws Exception{mapping.getClass().getMethod("setDown",boolean.class).invoke(mapping,value);if((Boolean)call(mapping,"isDown")!=value)mapping.getClass().getMethod("setDown",boolean.class).invoke(mapping,true);if((Boolean)call(mapping,"isDown")!=value)throw new IOException("input.key.mode.unsupported");}
 private static void release()throws Exception{
  Exception failure=null;for(Held entry:List.copyOf(held))try{boolean physical=false;try{Object window=call(activeClient,"getWindow");long handle=((Number)call(window,"handle")).longValue();Class<?> glfw=type(activeClient,"org.lwjgl.glfw.GLFW");physical=((Number)glfw.getMethod("glfwGetKey",long.class,int.class).invoke(null,handle,entry.code)).intValue()==1;}catch(Exception ignored){}down(entry.mapping,physical);}catch(Exception e){failure=e;}
  held.clear();owner="";expires=0;if(failure!=null)throw failure;
 }
 static void handle(Object client,Map<String,String> request,Map<String,String> response)throws Exception{
  String command=request.getOrDefault("command","");
  if(command.equals("script.input.release")){if(owner.equals(owner(request)))release();return;}
  if(command.equals("script.input.heartbeat")){if(owner.equals(owner(request))&&System.nanoTime()<=expires)expires=System.nanoTime()+750_000_000L;return;}
  if(command.equals("script.chat")){player(client);String text=text(request);if(text.stripLeading().startsWith("/"))throw new IOException("chat.command.requires.game.command");Object connection=call(client,"getConnection");if(connection==null)throw new IOException("game.not.connected");connection.getClass().getMethod("sendChat",String.class).invoke(connection,text);response.put("status","submitted_to_server");return;}
  if(command.equals("script.input.text")){
   String text=text(request);Object screen=screen(client);
   if(screen==null&&"true".equals(request.get("open_chat"))){player(client);Object chat=type(client,"net.minecraft.client.gui.screens.ChatScreen").getConstructor(String.class,boolean.class).newInstance(text,false);Object gui=field(client,"gui");gui.getClass().getMethod("setScreen",type(client,"net.minecraft.client.gui.screens.Screen")).invoke(gui,chat);response.put("status","filled_not_sent");return;}
   if(screen==null){response.put("status","not_inserted");response.put("accepted","0");return;}int accepted=0;
   for(int cp:text.codePoints().toArray()){if(screen(client)!=screen)throw new IOException("input.screen.changed");Object event=type(client,"net.minecraft.client.input.CharacterEvent").getConstructor(int.class).newInstance(cp);if((Boolean)screen.getClass().getMethod("charTyped",event.getClass()).invoke(screen,event))accepted++;else{response.put("status",accepted==0?"not_inserted":"partially_inserted");response.put("accepted",Integer.toString(accepted));return;}}
   response.put("status","inserted_not_sent");response.put("accepted",Integer.toString(accepted));return;
  }
  if(!command.equals("script.input.key"))throw new IOException("command.unsupported");
  int code=code(request.getOrDefault("key","")),mods=integer(request,"modifiers",0,15);String action=request.getOrDefault("action","press");if(!Set.of("press","release","repeat").contains(action))throw new IOException("input.action.invalid");
  Object screen=screen(client),event=event(client,code,mods);
  if(screen!=null){if(!held.isEmpty())release();boolean handled=(Boolean)screen.getClass().getMethod(action.equals("release")?"keyReleased":"keyPressed",event.getClass()).invoke(screen,event);if(!handled&&!"false".equals(request.get("require_handled")))throw new IOException("input.key.not.handled:"+screen.getClass().getSimpleName());response.put("handled",Boolean.toString(handled));response.put("status",handled?"delivered":"unhandled_release");return;}
  interactive(client);player(client);if(action.equals("repeat"))throw new IOException("input.repeat.requires.game.screen");String next=owner(request);
  if(!owner.isEmpty()&&System.nanoTime()>expires)release();if(!owner.isEmpty()&&!owner.equals(next))throw new IOException("input.busy");
  if(action.equals("release")){for(Held entry:List.copyOf(held))if(entry.code==code){down(entry.mapping,false);held.remove(entry);}if(held.isEmpty())owner="";response.put("status","released");return;}
  Object[] mappings=(Object[])field(field(client,"options"),"keyMappings");List<Object> matched=new ArrayList<>();
  for(Object mapping:mappings)if((Boolean)mapping.getClass().getMethod("matches",event.getClass()).invoke(mapping,event)){if(held.stream().anyMatch(h->h.mapping==mapping))throw new IOException("input.key.already.leased");if((Boolean)call(mapping,"isDown"))throw new IOException("input.key.already.held");matched.add(mapping);}
  if(matched.isEmpty())throw new IOException("input.key.unmapped;use.a.game.screen.for.raw.events");
  owner=next;activeClient=client;activeScreen=null;activeLevel=field(client,"level");expires=System.nanoTime()+750_000_000L;
  try{for(Object mapping:matched){held.add(new Held(mapping,code));down(mapping,true);}Class<?> constants=type(client,"com.mojang.blaze3d.platform.InputConstants");Object key=constants.getMethod("getKey",event.getClass()).invoke(null,event);type(client,"net.minecraft.client.KeyMapping").getMethod("click",type(client,"com.mojang.blaze3d.platform.InputConstants$Key")).invoke(null,key);}catch(Exception e){release();throw e;}
  response.put("status","pressed");response.put("leased","true");
 }
 private static String text(Map<String,String> request)throws IOException{String text=request.getOrDefault("text","");if(text.isEmpty()||text.length()>256||text.codePoints().anyMatch(cp->Character.isISOControl(cp)))throw new IOException("input.text.invalid;expected.1.to.256.printable.characters");return text;}
}
