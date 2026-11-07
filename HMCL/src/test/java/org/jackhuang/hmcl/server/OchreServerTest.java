package org.jackhuang.hmcl.server;

import org.jackhuang.hmcl.JavaFXLauncher;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Properties;
import java.util.jar.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises bounded output, Forge launch discovery and real child-process lifecycle with a fake server.
@NotNullByDefault
public final class OchreServerTest {
    /// Initializes metadata with a fresh directory before any shared manager is first loaded.
    @org.junit.jupiter.api.BeforeAll public static void isolateMetadata() throws Exception {
        var root = Files.createTempDirectory("ochre-server-test-");
        System.setProperty("hmcl.dir", root.resolve("local").toString());
        System.setProperty("hmcl.home", root.resolve("home").toString());
        JavaFXLauncher.start();
    }
    /// A minimal test server with no game code or EULA; emits the real ready marker and accepts stop/crash.
    @NotNullByDefault
    public static final class FakeServer {
        /// Prints readiness then exits in response to a command.
        public static void main(String[] arguments) throws Exception {
            System.out.println("[Server thread/INFO]: Done (0.123s)! For help, type \\\"help\\\"");
            java.util.Map<String, String> rules = new java.util.HashMap<>();
            try (var input = new java.io.BufferedReader(new java.io.InputStreamReader(System.in))) {
                String line;
                while ((line = input.readLine()) != null) {
                    if (line.equals("stop")) return;
                    if (line.equals("crash")) System.exit(7);
                    if (line.startsWith("gamerule ")) {
                        String[] parts = line.split(" "); if (parts.length == 3) rules.put(parts[1], parts[2]);
                        System.out.println("Game rule " + parts[1] + (parts.length == 3 ? " is now set to: " : " is currently set to: ") + rules.getOrDefault(parts[1], "false")); continue;
                    }
                    System.out.println("echo: " + line);
                }
            }
        }
    }
    /// Produces an isolated installation fixture pointing at the test JVM, without changing real servers.
    private static ServerInstance fixture(Path directory) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("name", "Test server"); properties.setProperty("core", "Forge"); properties.setProperty("version", "1.21.8");
        properties.setProperty("java", Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString());
        properties.setProperty("memory", "512");
        try (var output = Files.newOutputStream(directory.resolve("ochre-server.properties"))) { properties.store(output, "test fixture"); }
        return new ServerInstance(directory);
    }
    /// Confirms bounded logs and exact, non-incidental ready-marker detection.
    @Test public void boundedLogsAndReadyMarkers() {
        RuntimeSession session = new RuntimeSession();
        for (int i = 0; i < 10000; i++) session.append("line " + i + "x".repeat(300));
        assertTrue(session.log().length() < 182000);
        assertTrue(session.log().contains("line 9999"));
        assertFalse(session.log().contains("line 0x"));
        assertTrue(ServerManager.isReady("Done (1.2s)! For help, type help"));
        assertFalse(ServerManager.isReady("Download task Done"));
        assertEquals(8, ServerManager.major("1.8.0_412")); assertEquals(21, ServerManager.major("21.0.4+7"));
    }
    /// Modern Forge must use generated argument files; legacy Forge must use its executable jar.
    @Test public void forgeLaunchDetectionAndEulaPersistence(@TempDir Path directory) throws Exception {
        ServerInstance server = fixture(directory);
        assertFalse(server.acceptedEula());
        Path arguments = directory.resolve("libraries/net/minecraftforge/forge/1.21.8-58.1.26/unix_args.txt");
        Files.createDirectories(arguments.getParent()); Files.writeString(arguments, "--example");
        ServerCatalog.configureForgeLaunch(server); server.save();
        assertEquals(directory.relativize(arguments).toString(), new ServerInstance(directory).settings.getProperty("args"));
        Files.delete(arguments); Files.write(directory.resolve("forge-example.jar"), new byte[0]);
        server.settings.remove("args"); ServerCatalog.configureForgeLaunch(server);
        assertEquals("forge-example.jar", server.settings.getProperty("jar"));
    }
    /// Starts a real harmless child JVM, confirms ready state, graceful stop, and crash classification.
    @Test public void realProcessReadyStopCrash(@TempDir Path directory) throws Exception {
        JavaFXLauncher.start();
        ServerInstance server = fixture(directory);
        Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", FakeServer.class.getName());
        String name = FakeServer.class.getName().replace('.', '/') + ".class";
        try (var output = new JarOutputStream(Files.newOutputStream(directory.resolve("server.jar")), manifest);
             var input = FakeServer.class.getClassLoader().getResourceAsStream(name)) {
            assertNotNull(input); output.putNextEntry(new JarEntry(name)); input.transferTo(output); output.closeEntry();
        }
        // This is a test fixture for FakeServer, not acceptance of a Minecraft agreement.
        Files.writeString(directory.resolve("eula.txt"), "eula=true\n");
        try {
            ServerManager.start(server); await(() -> server.session.log().contains("Done ("));
            assertFalse(ServerCommands.readRule(server, "keepInventory", false));
            ServerCommands.applyRule(server, "keepInventory", true, false); assertTrue(ServerCommands.readRule(server, "keepInventory", false));
            ServerCommands.applyRule(server, "doFireTick", true, true); assertTrue(ServerCommands.readRule(server, "doFireTick", true));
            ServerCommands.applyRule(server, "doFireTick", false, true); assertFalse(ServerCommands.readRule(server, "doFireTick", true));
            ServerManager.stop(server, false); await(() -> !server.busy);
            assertFalse(server.session.isRunning());
            ServerManager.start(server); await(() -> server.session.isRunning() && server.session.log().contains("Done ("));
            ServerManager.command(server, "crash"); await(() -> !server.busy);
            assertEquals(7, server.session.process().exitValue());
        } finally { if (server.session.isRunning()) server.session.stop(); }
    }
    /// Recoverable deletion preserves worlds and settings, rejects busy installs and supports a complete restore.
    @Test public void recoverableRemovalPreservesData() throws Exception {
        Files.createDirectories(ServerManager.ROOT);
        Path directory = Files.createTempDirectory(ServerManager.ROOT, "delete-test-");
        ServerInstance server = fixture(directory);
        Path world = directory.resolve("world/region/r.0.0.mca"); Files.createDirectories(world.getParent());
        byte[] content = "test-world-contents".getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(world, content);
        server.busy = true; assertThrows(java.io.IOException.class, () -> ServerManager.remove(server)); server.busy = false;
        Path archived = ServerManager.remove(server);
        assertFalse(Files.exists(directory)); assertArrayEquals(content, Files.readAllBytes(archived.resolve("world/region/r.0.0.mca")));
        var deleted = ServerManager.deletedServers().stream().filter(value -> value.directory().equals(archived)).findFirst().orElseThrow();
        ServerInstance restored = ServerManager.restore(deleted);
        assertArrayEquals(content, Files.readAllBytes(restored.directory.resolve("world/region/r.0.0.mca")));
        assertEquals("Test server", restored.toString()); assertFalse(Files.exists(archived));
        assertThrows(java.io.IOException.class, () -> ServerManager.restore(new ServerManager.DeletedServer(directory.getParent(), "outside archive")));
    }
    /// Missing process measurements are explicit rather than represented by a misleading zero percent.
    @Test public void stoppedResourceSnapshotHasNoFabricatedProcessValues() {
        var sample = new RuntimeSession().snapshot();
        assertFalse(sample.running()); assertTrue(Double.isNaN(sample.processCpu())); assertEquals(-1, sample.residentBytes());
        assertTrue(sample.report().contains("未运行")); assertFalse(sample.report().contains("进程 CPU：0.0%"));
    }

    /// Legacy notifications receive meaningful icons while critical callers can provide explicit categories.
    @Test public void notificationKinds() {
        assertEquals(ServerManager.NoticeKind.ERROR, ServerManager.classifyNotice("服务器异常退出"));
        assertEquals(ServerManager.NoticeKind.WARNING, ServerManager.classifyNotice("请先停止服务器"));
        assertEquals(ServerManager.NoticeKind.SUCCESS, ServerManager.classifyNotice("已复制连接地址"));
        assertEquals(ServerManager.NoticeKind.INFO, ServerManager.classifyNotice("正在保存服务器"));
    }
    /// Waits for an observable process condition with a strict deadline.
    private static void await(java.util.function.BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (!condition.getAsBoolean() && System.nanoTime() < until) Thread.sleep(30);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for process state");
    }
}
