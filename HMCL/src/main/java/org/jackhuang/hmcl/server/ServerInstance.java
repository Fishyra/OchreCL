package org.jackhuang.hmcl.server;

import org.jetbrains.annotations.NotNullByDefault;
import java.nio.file.Path;
import java.nio.file.Files;
import java.io.IOException;
import java.util.Properties;

/// One isolated local server installation; metadata never shares the game instance directory.
@NotNullByDefault
public final class ServerInstance {
    /// Installation directory and saved configuration.
    public final Path directory;
    /// Mutable settings written only by explicit panel actions.
    public final Properties settings;
    /// Console and current owned process for this installation.
    public final RuntimeSession session = new RuntimeSession();
    /// Prevents starting twice while Java discovery or process creation is pending.
    public volatile boolean busy;
    /// Blocks starts while snapshots, recovery or player-file maintenance owns this installation.
    public volatile boolean maintenance;
    /// Distinguishes a requested shutdown from a crash.
    public volatile boolean stopping;

    /// Loads persisted metadata from an existing server directory.
    public ServerInstance(Path directory) throws IOException {
        this.directory = directory;
        settings = new Properties();
        try (var input = Files.newInputStream(directory.resolve("ochre-server.properties"))) { settings.load(input); }
        session.recover(directory.resolve(".ochre-runtime"));
    }
    /// Saves metadata with an atomic replacement so interrupted saves cannot truncate it.
    public void save() throws IOException {
        Path pending = directory.resolve("ochre-server.properties.new");
        try (var output = Files.newOutputStream(pending)) { settings.store(output, "OchreCL local server"); }
        Files.move(pending, directory.resolve("ochre-server.properties"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }
    /// Returns the configured title.
    @Override public String toString() { return settings.getProperty("name", directory.getFileName().toString()); }
    /// Resolves the local Quick Play endpoint; binding always stays under player control.
    public String address() { return "127.0.0.1:" + settings.getProperty("port", "25565"); }
    /// Reads explicit prior acceptance without modifying the agreement.
    public boolean acceptedEula() {
        try {
            Properties eula = new Properties();
            try (var input = Files.newInputStream(directory.resolve("eula.txt"))) { eula.load(input); }
            return Boolean.parseBoolean(eula.getProperty("eula", "false"));
        } catch (IOException ignored) { return false; }
    }
}
