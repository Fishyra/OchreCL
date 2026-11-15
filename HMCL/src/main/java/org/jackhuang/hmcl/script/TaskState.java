package org.jackhuang.hmcl.script;

import com.google.gson.*;
import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/// Bounded JSON checkpoints in an instance-specific managed directory; no script-chosen paths.
@NotNullByDefault
final class TaskState {
    /// Static state store owns no background tasks.
    private TaskState() { }
    /// Derives an opaque instance directory and validates one logical checkpoint name.
    private static Path file(String instance,String name)throws Exception { if(!name.matches("[A-Za-z0-9_][A-Za-z0-9_-]{0,63}"))throw new IllegalArgumentException("Task key must be 1..64 English letters, digits, underscores or hyphens");String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(instance.getBytes(java.nio.charset.StandardCharsets.UTF_8)));Path root=Metadata.HMCL_LOCAL_HOME.resolve("script-state"),folder=root.resolve(hash);if(Files.isSymbolicLink(root)||Files.isSymbolicLink(folder))throw new IllegalArgumentException("Invalid checkpoint directory");Path path=folder.resolve(name+".json");if(Files.isSymbolicLink(path))throw new IllegalArgumentException("Invalid checkpoint file");return path; }
    /// Supports only DSL data, preserving finite numbers and rejecting host objects and unbounded nesting.
    private static JsonElement encode(Object value,int depth){if(depth>8)throw new IllegalArgumentException("Checkpoint nesting exceeds 8");if(value==OchreScript.Empty.NONE)return JsonNull.INSTANCE;if(value instanceof Boolean flag)return new JsonPrimitive(flag);if(value instanceof String text)return new JsonPrimitive(text);if(value instanceof Double number&&Double.isFinite(number))return new JsonPrimitive(number);if(value instanceof Map<?,?> map){if(map.size()>128)throw new IllegalArgumentException("Checkpoint record exceeds 128 members");JsonObject json=new JsonObject();for(var e:map.entrySet()){if(!(e.getKey() instanceof String key))throw new IllegalArgumentException("Checkpoint keys must be strings");json.add(key,encode(Objects.requireNonNull(e.getValue()),depth+1));}return json;}if(value instanceof List<?> list){if(list.size()>128)throw new IllegalArgumentException("Checkpoint list exceeds 128 items");JsonArray json=new JsonArray();for(Object item:list)json.add(encode(item,depth+1));return json;}throw new IllegalArgumentException("Checkpoint accepts only numbers, bools, strings, none, Lists and Records");}
    /// Restores DSL numbers as doubles and immutable collections, never arbitrary Java types.
    private static Object decode(JsonElement json,int depth){if(depth>8)throw new IllegalArgumentException("Checkpoint nesting exceeds 8");if(json.isJsonNull())return OchreScript.Empty.NONE;if(json.isJsonObject()){Map<String,Object> result=new LinkedHashMap<>();for(var e:json.getAsJsonObject().entrySet())result.put(e.getKey(),decode(e.getValue(),depth+1));return Map.copyOf(result);}if(json.isJsonArray()){List<Object> result=new ArrayList<>();for(var item:json.getAsJsonArray())result.add(decode(item,depth+1));return List.copyOf(result);}JsonPrimitive value=json.getAsJsonPrimitive();if(value.isBoolean())return value.getAsBoolean();if(value.isNumber()){double n=value.getAsDouble();if(!Double.isFinite(n))throw new IllegalArgumentException("Invalid checkpoint number");return n;}return value.getAsString();}
    /// Loads one small checkpoint, returning the caller's default only when the file is absent.
    static synchronized Object load(String instance,String name,Object fallback)throws Exception{Path path=file(instance,name);if(!Files.exists(path))return fallback;if(Files.size(path)>16384)throw new IllegalArgumentException("Checkpoint exceeds 16 KiB");return decode(JsonParser.parseString(Files.readString(path)),0);}
    /// Saves atomically after validating all data; limits each instance to sixty-four named checkpoints.
    static synchronized void save(String instance,String name,Object value)throws Exception{Path path=file(instance,name);String json=encode(value,0).toString();if(json.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>16384)throw new IllegalArgumentException("Checkpoint exceeds 16 KiB");Files.createDirectories(path.getParent());try(var files=Files.list(path.getParent())){if(!Files.exists(path)&&files.filter(p->p.toString().endsWith(".json")).count()>=64)throw new IllegalArgumentException("Checkpoint count exceeds 64");}Path temp=Files.createTempFile(path.getParent(),"checkpoint-",".tmp");try{Files.writeString(temp,json);Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temp);}}
    /// Removes only one named managed checkpoint.
    static synchronized boolean clear(String instance,String name)throws Exception{return Files.deleteIfExists(file(instance,name));}
}
