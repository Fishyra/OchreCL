package org.jackhuang.hmcl.script;

import org.jackhuang.hmcl.bridge.BridgeWire;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises the production script host's timing across two acknowledged local speech calls.
@NotNullByDefault
public final class SpeechSequenceTest {
    /// Temporary fake instance never touches game accounts or worlds.
    @TempDir Path directory;
    /// Writes small authenticated bridge metadata for this local test server only.
    private void properties(Path path, Map<String,String> fields)throws Exception { Properties p=new Properties();p.putAll(fields);try(var output=Files.newOutputStream(path)){p.store(output,"test only");} }
    /// A following say cannot replace the previous bubble before its requested duration elapses.
    @Test public void consecutiveSpeechWaitsForPreviousBubble()throws Exception {
        Path bridge=directory.resolve(".ochre-bridge");Files.createDirectories(bridge);Files.writeString(bridge.resolve("fixture.jar"),"fixture");
        try(ServerSocket server=new ServerSocket(0,2,InetAddress.getLoopbackAddress())) {
            properties(bridge.resolve("settings.properties"),Map.of("enabled","true","agent","fixture.jar"));properties(bridge.resolve("launch.properties"),Map.of("token","0".repeat(64)));properties(bridge.resolve("connection.properties"),Map.of("port",Integer.toString(server.getLocalPort()),"pid",Long.toString(ProcessHandle.current().pid())));
            ExecutorService worker=Executors.newSingleThreadExecutor();List<String> messages=new ArrayList<>();List<Long> arrivals=new ArrayList<>();
            try {
                Future<?> received=worker.submit(()->{try{for(int i=0;i<2;i++)try(var socket=server.accept()){var request=BridgeWire.read(socket.getInputStream());messages.add(request.get("text"));arrivals.add(System.nanoTime());BridgeWire.write(socket.getOutputStream(),Map.of("ok","true","gameVersion","26.2","bubble","shown"));}}catch(Exception e){throw new RuntimeException(e);}});
                var program=OchreScript.compile("say(\"Hello!\", 100ms); say(\"Done!\", duration=150ms);");long start=System.nanoTime();program.run(new ScriptGameHost(directory.toString()),Set.of(),line->{},new OchreScript.Control());received.get(2,TimeUnit.SECONDS);
                assertEquals(List.of("Hello!","Done!"),messages);assertTrue(arrivals.get(1)-arrivals.get(0)>=90_000_000L);assertTrue(System.nanoTime()-start>=240_000_000L);
            } finally { worker.shutdownNow(); }
        }
    }
}
