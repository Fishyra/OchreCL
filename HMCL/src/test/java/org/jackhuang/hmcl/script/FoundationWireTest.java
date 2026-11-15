package org.jackhuang.hmcl.script;

import org.jackhuang.hmcl.bridge.BridgeWire;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises the actual production host over the authenticated bridge protocol, including cleanup.
@NotNullByDefault
public final class FoundationWireTest {
    /// Fake bridge metadata is isolated from real running games.
    @TempDir Path directory;
    /// Stores a fixture properties file without printing its token.
    private void properties(Path file, Map<String,String> fields) throws Exception { Properties p=new Properties();p.putAll(fields);try(var out=Files.newOutputStream(file)){p.store(out,"fixture");} }
    /// A complete mining action waits for readback and releases its exact owner; raw and chat text stay distinct.
    @Test public void bindingsReadbackAndCleanup() throws Exception {
        Path bridge=directory.resolve(".ochre-bridge");Files.createDirectories(bridge);Files.writeString(bridge.resolve("fixture.jar"),"fixture");
        try(ServerSocket server=new ServerSocket(0,16,InetAddress.getLoopbackAddress())) {
            properties(bridge.resolve("settings.properties"),Map.of("enabled","true","agent","fixture.jar"));properties(bridge.resolve("launch.properties"),Map.of("token","0".repeat(64)));properties(bridge.resolve("connection.properties"),Map.of("port",Integer.toString(server.getLocalPort()),"pid",Long.toString(ProcessHandle.current().pid())));
            ExecutorService worker=Executors.newSingleThreadExecutor();List<Map<String,String>> calls=new CopyOnWriteArrayList<>(); var menu=new java.util.concurrent.atomic.AtomicBoolean(true); var approved=new java.util.concurrent.atomic.AtomicBoolean(); var prompts=new java.util.concurrent.atomic.AtomicInteger();
            Future<?> served=worker.submit(()->{boolean mined=false;try{while(!Thread.currentThread().isInterrupted())try(var socket=server.accept()){var request=BridgeWire.read(socket.getInputStream());calls.add(request);String command=request.get("command");Map<String,String> result=new HashMap<>(Map.of("ok","true","gameVersion","26.2"));
                switch(command) {
                    case "script.context" -> result.putAll(Map.of("in_world","true","menu",Boolean.toString(menu.get()),"paused",Boolean.toString(menu.get()),"screen","ChatScreen","screen_token","test-screen","world","test-world"));
                    case "script.ui.close" -> { if (!approved.get()) throw new AssertionError("Closed without consent"); menu.set(false); result.put("status","closed"); }
                    case "script.block.read" -> {result.putAll(Map.of("world","test-world","id",mined?"minecraft:air":"minecraft:stone","state",mined?"air":"stone","x","1","y","64","z","1","isAir",Boolean.toString(mined),"blocksMotion",Boolean.toString(!mined),"canBeReplaced",Boolean.toString(mined),"loaded","true"));}
                    case "state.read" -> result.putAll(Map.of("player.YRot","0","player.XRot","0"));
                    case "script.block.mine" -> {mined=true;result.put("status","mining");}
                    case "script.input.text" -> result.put("status","true".equals(request.get("open_chat"))?"filled_not_sent":"inserted_not_sent");
                    case "script.chat" -> result.put("status","submitted_to_server");
                    case "script.observe" -> result.putAll(Map.of("count","1","cursor","2","missed","true","event.0.type","move","event.0.distance","1.5"));
                    default -> { }
                }
                BridgeWire.write(socket.getOutputStream(),result);
            }}catch(java.net.SocketException expected){}catch(Exception error){throw new RuntimeException(error);}});
            try {
                String code="List events = player.events(); print(events.length); print(player.mine(vec3(1, 64, 1), timeout=2s)); print(input.text(\"draft\")); print(input.send_text(\"raw\")); print(chat.send(\"sent\"));";
                var program=OchreScript.compile(code);List<String> output=new ArrayList<>();program.run(new ScriptGameHost(directory.toString(), (screen, control) -> { assertEquals("ChatScreen", screen); prompts.incrementAndGet(); approved.set(true); Thread.sleep(150); return true; }),program.permissions(),output::add,new OchreScript.Control());
                assertEquals(List.of("2","client_observed_break","filled_not_sent","inserted_not_sent","submitted_to_server"),output);
                var mine=calls.stream().filter(c->c.get("command").equals("script.block.mine")).findFirst().orElseThrow();var stop=calls.stream().filter(c->c.get("command").equals("script.block.stop")).findFirst().orElseThrow();assertEquals(mine.get("owner"),stop.get("owner"));assertEquals("test-world",mine.get("world"));assertEquals("minecraft:stone",mine.get("expected"));
                assertEquals(1, prompts.get()); int before=calls.size(); menu.set(true); approved.set(false); var blocked=OchreScript.compile("player.jump();"); assertThrows(CancellationException.class, () -> blocked.run(new ScriptGameHost(directory.toString()), blocked.permissions(), line -> {}, new OchreScript.Control())); assertTrue(calls.subList(before,calls.size()).stream().noneMatch(c -> Set.of("script.ui.close","script.jump").contains(c.get("command"))));
                server.close();served.get(2,TimeUnit.SECONDS);
            } finally { server.close();worker.shutdownNow(); }
        }
    }
    /// New container identity fields never replace authentication, and structured observations keep bool/number types.
    @Test public void expandedRecordsAndContainerIdentity() throws Exception {
        Path bridge=directory.resolve(".ochre-bridge");Files.createDirectories(bridge);Files.writeString(bridge.resolve("fixture.jar"),"fixture");
        try(ServerSocket server=new ServerSocket(0,16,InetAddress.getLoopbackAddress())) {
            properties(bridge.resolve("settings.properties"),Map.of("enabled","true","agent","fixture.jar"));properties(bridge.resolve("launch.properties"),Map.of("token","0".repeat(64)));properties(bridge.resolve("connection.properties"),Map.of("port",Integer.toString(server.getLocalPort()),"pid",Long.toString(ProcessHandle.current().pid())));
            ExecutorService worker=Executors.newSingleThreadExecutor();List<Map<String,String>> calls=new CopyOnWriteArrayList<>();
            Future<?> served=worker.submit(()->{try{while(!Thread.currentThread().isInterrupted())try(var socket=server.accept()){var request=BridgeWire.read(socket.getInputStream());calls.add(request);Map<String,String> result=new HashMap<>(Map.of("ok","true","gameVersion","26.2"));switch(request.get("command")){
                case "script.inspect" -> result.putAll(Map.of("food","18","in_lava","false","health","19.5"));
                case "script.container.read" -> result.putAll(Map.of("menu_token","world:menu:1","total","46","count","1","slot.0.slot","36","slot.0.empty","false","slot.0.accepts_held","true"));
                case "script.container.act" -> result.put("status","submitted_to_server");
                case "script.observe" -> {if(request.get("cursor").equals("0"))result.putAll(Map.of("count","1","cursor","1","missed","false","event.0.type","hurt","event.0.target_id","4","event.0.evidence","server_damage_event"));else result.putAll(Map.of("count","0","cursor","1","missed","false"));}
                case "script.snapshot" -> result.putAll(Map.of("in_world","true","vital.health","19.5","screen.open","false","container.token","world:menu:1","input.forward","true","state.jumping","false"));
                default -> { }
            }BridgeWire.write(socket.getOutputStream(),result);}}catch(java.net.SocketException expected){}catch(Exception error){throw new RuntimeException(error);}});
            try {
                String code="Record v=player.vitals; print(v.food, v.in_lava); Record page=player.container_slots(); for(slot in page.slots) { print(slot.slot, slot.accepts_held); } print(player.container_move(36,37,count=1,token=page.token)); Record e=script.wait_event(\"hurt\",timeout=100ms); print(e.target_id,e.evidence); print(script.wait_event(\"nothing\",timeout=100ms)); Record state=player.snapshot(); print(state.vitals.health,state.inputs.forward,state.screen.open);";
                var program=OchreScript.compile(code);List<String> output=new ArrayList<>();program.run(new ScriptGameHost(directory.toString()),program.permissions(),output::add,new OchreScript.Control());
                assertEquals(List.of("18 false","36 true","submitted_to_server","4 server_damage_event","none","19.5 true false"),output);
                var action=calls.stream().filter(call->call.get("command").equals("script.container.act")).findFirst().orElseThrow();assertEquals("0".repeat(64),action.get("token"));assertEquals("world:menu:1",action.get("menu_token"));
                server.close();served.get(2,TimeUnit.SECONDS);
            } finally { server.close();worker.shutdownNow(); }
        }
    }
}
