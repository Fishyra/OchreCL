package io.ochre.bridge;

import java.io.IOException;
import java.util.*;

/// Reflection utilities shared by explicitly allowlisted, client-thread-only script actions.
final class ActionSupport {
 private ActionSupport() { }
 static Object call(Object value,String name)throws Exception{return value.getClass().getMethod(name).invoke(value);}
 static Object field(Object value,String name)throws Exception{return value.getClass().getField(name).get(value);}
 static Class<?> type(Object client,String name)throws Exception{return Class.forName(name,false,client.getClass().getClassLoader());}
 static double number(Object value,String name)throws Exception{return ((Number)call(value,name)).doubleValue();}
 static int integer(Map<String,String> request,String name,int min,int max)throws IOException{try{int n=Integer.parseInt(request.getOrDefault(name,""));if(n>=min&&n<=max)return n;}catch(NumberFormatException ignored){}throw new IOException("argument.invalid:"+name);}
 static String owner(Map<String,String> request)throws IOException{String owner=request.getOrDefault("owner","");if(!owner.matches("[a-f0-9-]{36}"))throw new IOException("control.owner.invalid");return owner;}
 static Object player(Object client)throws Exception{Object player=field(client,"player");if(player==null)throw new IOException("game.not.in.world");return player;}
 static void interactive(Object client)throws Exception{if((Boolean)call(client,"isPaused")||call(field(client,"gui"),"screen")!=null)throw new IOException("game.control.menu.or.paused");}
 static String id(Object value)throws Exception{return call(call(call(value,"builtInRegistryHolder"),"key"),"identifier").toString();}
 static String world(Object client)throws Exception{Object level=field(client,"level");if(level==null)throw new IOException("game.not.in.world");return call(level,"dimension").toString()+":"+System.identityHashCode(level);}
 static Object position(Object client,Map<String,String> request)throws Exception{return type(client,"net.minecraft.core.BlockPos").getConstructor(int.class,int.class,int.class).newInstance(integer(request,"x",-30_000_000,30_000_000),integer(request,"y",-2048,2048),integer(request,"z",-30_000_000,30_000_000));}
 static void sameWorld(Object client,Map<String,String> request)throws Exception{String expected=request.getOrDefault("world","");if(!expected.isEmpty()&&!world(client).equals(expected))throw new IOException("target.world.changed");}
}
