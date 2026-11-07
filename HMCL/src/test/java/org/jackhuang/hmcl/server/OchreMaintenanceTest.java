package org.jackhuang.hmcl.server;

import org.glavo.nbt.io.NBTCodec;
import org.glavo.nbt.tag.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies recoverable server/player writes, path isolation and actual deployment configuration.
@NotNullByDefault
public final class OchreMaintenanceTest {
    /// Keeps metadata and backup roots away from user game installations.
    @BeforeAll public static void isolate() throws Exception { Path root = Files.createTempDirectory("ochre-maintenance-"); System.setProperty("hmcl.dir", root.toString()); System.setProperty("hmcl.home", root.resolve("home").toString()); org.jackhuang.hmcl.JavaFXLauncher.start(); }
    /// Produces one stopped server with a small real world fixture.
    private static ServerInstance fixture(Path root) throws Exception {
        Properties settings = new Properties(); settings.setProperty("name", "Fixture"); settings.setProperty("core", "Fabric"); settings.setProperty("version", "1.21.8"); settings.setProperty("memory", "1024");
        try (var output = Files.newOutputStream(root.resolve("ochre-server.properties"))) { settings.store(output, "fixture"); }
        Files.createDirectories(root.resolve("world")); Files.writeString(root.resolve("world/marker.txt"), "original world"); return new ServerInstance(root);
    }
    /// Restoring all files removes newer files while keeping a recoverable pre-restore snapshot.
    @Test public void fullAndSingleFileRecovery(@TempDir Path root) throws Exception {
        ServerInstance server = fixture(root); Path snapshot = ServerSnapshots.create(server, progress -> {});
        Files.writeString(root.resolve("world/marker.txt"), "new world"); Files.writeString(root.resolve("world/new.txt"), "new file");
        ServerSnapshots.restoreFile(server, snapshot, "world/marker.txt", progress -> {}); assertEquals("original world", Files.readString(root.resolve("world/marker.txt"))); assertTrue(Files.exists(root.resolve("world/new.txt")));
        ServerSnapshots.restore(server, snapshot, progress -> {}); assertFalse(Files.exists(root.resolve("world/new.txt"))); assertEquals("original world", Files.readString(root.resolve("world/marker.txt"))); assertTrue(ServerSnapshots.list(server).size() >= 3); assertFalse(server.maintenance);
    }
    /// Malicious paths and linked destinations cannot write outside a server installation.
    @Test public void rejectsZipSlipAndReleasesGuard(@TempDir Path root) throws Exception {
        ServerInstance server = fixture(root); Path malicious = root.resolve("bad.zip");
        try (var zip = new ZipOutputStream(Files.newOutputStream(malicious))) { zip.putNextEntry(new ZipEntry("../escape.txt")); zip.write("escape".getBytes()); zip.closeEntry(); }
        assertThrows(java.io.IOException.class, () -> ServerSnapshots.restore(server, malicious, progress -> {})); assertFalse(server.maintenance); assertEquals("original world", Files.readString(root.resolve("world/marker.txt")));
        assertThrows(java.io.IOException.class, () -> ServerSnapshots.safe(root, "../outside"));
        if (!System.getProperty("os.name").startsWith("Windows")) { Path external = Files.createTempDirectory("ochre-external-"); Files.createSymbolicLink(root.resolve("link"), external); assertThrows(java.io.IOException.class, () -> ServerSnapshots.safe(root, "link/data")); }
    }
    /// JVM settings never become shell commands or replace the Java entry point.
    @Test public void appliesDeploymentOptions(@TempDir Path root) throws Exception {
        ServerInstance server = fixture(root); Properties options = new Properties(); options.setProperty("port", "25570"); options.setProperty("view-distance", "6"); options.setProperty("simulation-distance", "4"); options.setProperty("online-mode", "true"); options.setProperty("eula", "true"); options.setProperty("jvm", "-XX:+UseG1GC\n-Dmessage=hello world");
        ServerConfiguration.apply(server, options); Properties actual = new Properties(); try (var input = Files.newInputStream(root.resolve("server.properties"))) { actual.load(input); }
        assertEquals("25570", actual.getProperty("server-port")); assertEquals("6", actual.getProperty("view-distance")); assertTrue(server.acceptedEula()); assertEquals(2, ServerConfiguration.jvmArguments(options.getProperty("jvm")).size());
        assertThrows(java.io.IOException.class, () -> ServerConfiguration.jvmArguments("-jar evil.jar")); assertThrows(java.io.IOException.class, () -> ServerConfiguration.jvmArguments("@args.txt"));
        options.setProperty("view-distance", "100"); assertThrows(java.io.IOException.class, () -> ServerConfiguration.apply(server, options));
    }
    /// Modern game rules include renamed and integer-valued rules, while old servers retain old syntax.
    @Test public void versionedRuleCommands() {
        assertFalse(ServerCommands.modern("1.21.10")); assertTrue(ServerCommands.modern("1.21.11")); assertTrue(ServerCommands.modern("26.2"));
        assertEquals("gamerule minecraft:advance_weather false", ServerCommands.rule("doWeatherCycle", false, true)); assertEquals("gamerule minecraft:fire_spread_radius_around_player 0", ServerCommands.rule("doFireTick", false, true)); assertEquals("gamerule keepInventory true", ServerCommands.rule("keepInventory", true, false));
    }
    /// Editing a saved slot preserves NBT components and migration retains source identity data.
    @Test public void playerInventoryAndMigration(@TempDir Path root) throws Exception {
        ServerInstance server = fixture(root); UUID uuid = UUID.randomUUID(), target = UUID.randomUUID(); Path players = Files.createDirectories(root.resolve("world/playerdata"));
        CompoundTag data = new CompoundTag(); ListTag<CompoundTag> inventory = new ListTag<>(TagType.COMPOUND); inventory.setName("Inventory"); CompoundTag stack = new CompoundTag(); stack.addByte("Slot", (byte) 0).addString("id", "minecraft:diamond").addInt("count", 3).addString("custom_fixture", "retain me"); inventory.addTag(stack); data.addTag(inventory); data.addTag(new ListTag<>(TagType.COMPOUND).setName("EnderItems")); data.addUUID("UUID", uuid);
        try (var output = new GZIPOutputStream(Files.newOutputStream(players.resolve(uuid + ".dat")))) { NBTCodec.of().writeTag(output, data); }
        assertEquals(3, ServerPlayers.items(server, uuid, false).get(0).count());
        ServerPlayers.editItem(server, uuid, false, 0, "minecraft:diamond", 9, progress -> {}); assertEquals(9, ServerPlayers.items(server, uuid, false).get(0).count());
        CompoundTag changed = NBTCodec.of().readTag(players.resolve(uuid + ".dat"), CompoundTag.class); assertEquals("retain me", ((CompoundTag) ((ListTag<?>) changed.get("Inventory")).getTag(0)).getString("custom_fixture"));
        ServerPlayers.migrate(server, uuid, target, progress -> {}); assertTrue(Files.exists(players.resolve(uuid + ".dat"))); assertEquals(9, ServerPlayers.items(server, target, false).get(0).count());
        server.maintenance = true; ServerManager.start(server); assertFalse(server.busy); server.maintenance = false;
        ServerPlayers.reset(server, target, progress -> {}); assertFalse(Files.exists(players.resolve(target + ".dat"))); assertTrue(Files.exists(players.resolve(uuid + ".dat"))); assertFalse(server.maintenance);
    }
    /// Core replacement swaps only binaries and rolls back safely when the metadata cannot be saved.
    @Test public void coreReplacementRetainsWorld(@TempDir Path root) throws Exception {
        Path original = Files.createDirectory(root.resolve("original")), replacement = Files.createDirectory(root.resolve("replacement"));
        ServerInstance server = fixture(original), staged = fixture(replacement); Files.writeString(original.resolve("server.jar"), "old core"); Files.writeString(replacement.resolve("server.jar"), "new core");
        ServerMaintenance.replaceCore(server, staged, progress -> {}); assertEquals("new core", Files.readString(original.resolve("server.jar"))); assertEquals("original world", Files.readString(original.resolve("world/marker.txt"))); assertFalse(server.maintenance);
        Files.writeString(replacement.resolve("server.jar"), "failed replacement");
        assertThrows(java.util.concurrent.CancellationException.class, () -> ServerMaintenance.replaceCore(server, staged, progress -> { throw new java.util.concurrent.CancellationException(); }));
        assertEquals("new core", Files.readString(original.resolve("server.jar"))); assertFalse(server.maintenance);
        assertEquals(java.util.List.of("--demo", "literal value"), ServerConfiguration.serverArguments("--demo\nliteral value"));
    }

}
