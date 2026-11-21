package io.ochre.bridge;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static io.ochre.bridge.ActionSupport.*;

/// Loaded-block inspection and ordinary reach/visibility-checked mining and placement.
final class BlockActions {
 private static String miningOwner="", miningWorld="", miningType="";private static Object miningPos, miningKey;private static volatile Object miningClient;private static long expires;
 private static final AtomicBoolean queued=new AtomicBoolean();
 private static final ScheduledExecutorService timer=Executors.newSingleThreadScheduledExecutor(task->{Thread t=new Thread(task,"OchreBridge mining lease");t.setDaemon(true);return t;});
 static {timer.scheduleWithFixedDelay(()->{Object c=miningClient;if(c instanceof Executor e&&queued.compareAndSet(false,true))try{e.execute(()->{try{if(!miningOwner.isEmpty()&&(System.nanoTime()>expires||InputActions.screen(c)!=null||(Boolean)call(c,"isPaused")||!world(c).equals(miningWorld)))stop();}catch(Exception ignored){try{stop();}catch(Exception ignoredAgain){}}finally{queued.set(false);}});}catch(Exception ignored){queued.set(false);}},100,100,TimeUnit.MILLISECONDS);}
 private BlockActions() { }
 private static Object state(Object client,Object pos)throws Exception{Object level=field(client,"level");if(level==null)throw new IOException("game.not.in.world");if(!(Boolean)level.getClass().getMethod("hasChunkAt",type(client,"net.minecraft.core.BlockPos")).invoke(level,pos))throw new IOException("block.chunk.not.loaded");return level.getClass().getMethod("getBlockState",type(client,"net.minecraft.core.BlockPos")).invoke(level,pos);}
 private static String blockId(Object state)throws Exception{return id(call(state,"getBlock"));}
 private static void describe(Object client,Object pos,Map<String,String> response)throws Exception{
  Object player=player(client);double distance=Math.sqrt(Math.pow(number(pos,"getX")+.5-number(player,"getX"),2)+Math.pow(number(pos,"getY")+.5-number(player,"getY"),2)+Math.pow(number(pos,"getZ")+.5-number(player,"getZ"),2));if(distance>64)throw new IOException("block.query.out.of.range");Object state=state(client,pos);
  response.put("world",world(client));response.put("id",blockId(state));response.put("state",state.toString());for(String axis:List.of("X","Y","Z"))response.put(axis.toLowerCase(Locale.ROOT),call(pos,"get"+axis).toString());for(String name:List.of("isAir","blocksMotion","canBeReplaced"))response.put(name,call(state,name).toString());response.put("distance",Double.toString(distance));response.put("loaded","true");
 }
 private static Object pick(Object client)throws Exception{Object player=player(client);return player.getClass().getMethod("pick",double.class,float.class,boolean.class).invoke(player,number(player,"blockInteractionRange"),1f,false);}
 private static Object hit(Object client,Object pos,Object face,boolean matchFace)throws Exception{
  Object result=pick(client);if(!type(client,"net.minecraft.world.phys.BlockHitResult").isInstance(result)||!"BLOCK".equals(call(result,"getType").toString())||!pos.equals(call(result,"getBlockPos"))||(matchFace&&!face.equals(call(result,"getDirection"))))throw new IOException("block.not.visible.or.out.of.reach");return result;
 }
 private static Object face(Object client,Map<String,String> request)throws Exception{String name=request.getOrDefault("face","up").toUpperCase(Locale.ROOT);if(name.equals("TOP"))name="UP";if(name.equals("BOTTOM"))name="DOWN";try{return type(client,"net.minecraft.core.Direction").getField(name).get(null);}catch(NoSuchFieldException e){throw new IOException("block.face.invalid");}}
 private static void permitted(Object client,Object pos)throws Exception{Object player=player(client);interactive(client);if((Boolean)call(player,"isSpectator"))throw new IOException("permission.spectator.cannot.modify.blocks");Object level=field(client,"level");if(!(Boolean)level.getClass().getMethod("mayInteract",type(client,"net.minecraft.world.entity.Entity"),type(client,"net.minecraft.core.BlockPos")).invoke(level,player,pos))throw new IOException("permission.block.denied");}
 private static void stop()throws Exception{Object c=miningClient,key=miningKey;miningOwner="";miningPos=null;miningWorld="";miningKey=null;expires=0;try{if(key!=null)key.getClass().getMethod("setDown",boolean.class).invoke(key,false);}finally{if(c!=null)call(field(c,"gameMode"),"stopDestroyBlock");}}
 static void handle(Object client,Map<String,String> request,Map<String,String> response)throws Exception{
  String command=request.getOrDefault("command","");
  if(command.equals("script.block.stop")){if(miningOwner.equals(owner(request)))stop();return;}
  if(command.equals("script.block.target")){Object target=pick(client);if(!type(client,"net.minecraft.world.phys.BlockHitResult").isInstance(target)||!"BLOCK".equals(call(target,"getType").toString())){response.put("found","false");return;}describe(client,call(target,"getBlockPos"),response);response.put("face",call(target,"getDirection").toString().toLowerCase(Locale.ROOT));response.put("found","true");return;}
  sameWorld(client,request);Object pos=position(client,request),state=state(client,pos);describe(client,pos,response);
  String expected=request.getOrDefault("expected","");if(!expected.isEmpty()&&!expected.equals(blockId(state)))throw new IOException("block.target.changed");
  if(command.equals("script.block.read"))return;
  permitted(client,pos);Object player=player(client),mode=field(client,"gameMode"),face=face(client,request);
  if(command.equals("script.block.open_crafting")){
   if(!"minecraft:crafting_table".equals(blockId(state)))throw new IOException("craft.expected.crafting.table");
   Object target=hit(client,pos,face,false),hand=type(client,"net.minecraft.world.InteractionHand").getField("MAIN_HAND").get(null);
   Object outcome=mode.getClass().getMethod("useItemOn",type(client,"net.minecraft.client.player.LocalPlayer"),type(client,"net.minecraft.world.InteractionHand"),type(client,"net.minecraft.world.phys.BlockHitResult")).invoke(mode,player,hand,target);
   if(!(Boolean)call(outcome,"consumesAction"))throw new IOException("craft.table.not.opened");
   return;
  }
  if(command.equals("script.block.mine")){
   String next=owner(request);if(!miningOwner.isEmpty()&&System.nanoTime()>expires)stop();if(!miningOwner.isEmpty()&&!miningOwner.equals(next))throw new IOException("block.mining.busy");
   if((Boolean)call(state,"isAir")){response.put("status","client_observed_break");return;}
   Object key=field(field(client,"options"),"keyAttack");
   if(miningOwner.isEmpty()&&(Boolean)call(key,"isDown"))throw new IOException("block.manual.attack.active");
   Object aimed=hit(client,pos,face,false);
   boolean start=miningOwner.isEmpty();if(start&&(Boolean)call(mode,"isDestroying"))throw new IOException("block.manual.mining.active");
   if(!start&&(!miningPos.equals(pos)||!miningWorld.equals(world(client))||!miningType.equals(blockId(state))))throw new IOException("block.target.changed");
   miningOwner=next;miningClient=client;miningPos=pos;miningWorld=world(client);miningType=blockId(state);expires=System.nanoTime()+750_000_000L;
   // The native tick cancels mining whenever attack is not held; use its normal continued attack path.
   try{
    if(start){
     Object accepted=mode.getClass().getMethod("startDestroyBlock",type(client,"net.minecraft.core.BlockPos"),type(client,"net.minecraft.core.Direction")).invoke(mode,pos,call(aimed,"getDirection"));
     if(!Boolean.TRUE.equals(accepted))throw new IOException("block.mine.not.accepted");
     if(!(Boolean)call(state(client,pos),"isAir")){
      miningKey=key;key.getClass().getMethod("setDown",boolean.class).invoke(key,true);
     }
    }
   }catch(Exception e){stop();throw e;}
   response.put("status",(Boolean)call(state(client,pos),"isAir")?"client_observed_break":"mining");return;
  }
  if(!command.equals("script.block.place"))throw new IOException("command.unsupported");
  Object stack=call(player,"getMainHandItem"),item=call(stack,"getItem");if((Boolean)call(stack,"isEmpty")||!type(client,"net.minecraft.world.item.BlockItem").isInstance(item))throw new IOException("block.place.requires.selected.block.item");
  Object destination=pos.getClass().getMethod("relative",type(client,"net.minecraft.core.Direction")).invoke(pos,face);Object before=state(client,destination);if(!(Boolean)call(before,"canBeReplaced"))throw new IOException("block.destination.not.replaceable");
  if(!(Boolean)player.getClass().getMethod("mayUseItemAt",type(client,"net.minecraft.core.BlockPos"),type(client,"net.minecraft.core.Direction"),type(client,"net.minecraft.world.item.ItemStack")).invoke(player,destination,face,stack))throw new IOException("permission.block.place.denied");
  Object hit=hit(client,pos,face,true),hand=type(client,"net.minecraft.world.InteractionHand").getField("MAIN_HAND").get(null);Object result=mode.getClass().getMethod("useItemOn",type(client,"net.minecraft.client.player.LocalPlayer"),type(client,"net.minecraft.world.InteractionHand"),type(client,"net.minecraft.world.phys.BlockHitResult")).invoke(mode,player,hand,hit);
  if(!(Boolean)call(result,"consumesAction"))throw new IOException("block.place.not.accepted");
  response.put("before",before.toString());describe(client,destination,response);response.put("status","submitted_to_server");
 }
}
