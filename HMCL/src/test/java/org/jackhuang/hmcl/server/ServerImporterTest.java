package org.jackhuang.hmcl.server;

import org.jackhuang.hmcl.JavaFXLauncher;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Properties;
import java.util.jar.*;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies that importing copies the original world/config and never modifies or accepts its EULA.
@NotNullByDefault
public final class ServerImporterTest {
    /// A minimal runnable server marker is sufficient; importing must never execute its contents.
    @Test public void importsCopyAndPreservesOriginal(@TempDir Path source) throws Exception {
        JavaFXLauncher.start();
        Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0"); manifest.getMainAttributes().putValue("Main-Class", "TestServer");
        try (var jar = new JarOutputStream(Files.newOutputStream(source.resolve("server.jar")), manifest)) { }
        Files.createDirectories(source.resolve("world")); Files.writeString(source.resolve("world/level.dat"), "world-data"); Files.writeString(source.resolve("eula.txt"), "eula=false\n"); Files.writeString(source.resolve("server.properties"), "server-port=25565\nonline-mode=false\ncustom-setting=retain\n");
        Properties settings = new Properties(); settings.setProperty("name", "Imported"); settings.setProperty("core", "原版"); settings.setProperty("version", "26.2"); settings.setProperty("port", "25566"); settings.setProperty("memory", "1024"); settings.setProperty("jar", "server.jar");
        var imported = ServerImporter.importDirectory(source, settings, text -> {});
        assertNotEquals(source, imported.directory); assertEquals("world-data", Files.readString(imported.directory.resolve("world/level.dat"))); assertEquals("eula=false\n", Files.readString(source.resolve("eula.txt"))); assertFalse(imported.acceptedEula());
        Properties game = new Properties(); try (var input = Files.newInputStream(imported.directory.resolve("server.properties"))) { game.load(input); }
        assertEquals("retain", game.getProperty("custom-setting")); assertEquals("25566", game.getProperty("server-port")); assertTrue(Files.readString(source.resolve("server.properties")).contains("server-port=25565")); assertEquals("false", imported.settings.getProperty("online-mode"));
    }
}
