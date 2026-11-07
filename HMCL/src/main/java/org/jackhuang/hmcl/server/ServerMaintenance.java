package org.jackhuang.hmcl.server;

import org.jetbrains.annotations.NotNullByDefault;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/// Guarded file maintenance preserving worlds and user configuration with recoverable backups.
@NotNullByDefault
public final class ServerMaintenance {
    /// Utility class.
    private ServerMaintenance() {}
    /// Claims maintenance atomically with respect to starting a server; never waits on a long file operation.
    public static void begin(ServerInstance server, boolean allowRunning) throws IOException {
        synchronized (ServerManager.class) {
            if (ServerServices.running(server, ServerToolInstaller.Tool.RCLONE)) throw new IOException("请先关闭此服务器的 SFTP，再进行文件维护或备份");
            if (server.maintenance || !allowRunning && (server.busy || server.session.isRunning()) || allowRunning && server.busy && !server.session.isRunning()) throw new IOException("请等待当前服务器操作完成");
            server.maintenance = true;
        }
    }
    /// Releases the guard after all file writes/cleanup have completed.
    public static void end(ServerInstance server) { server.maintenance = false; }
    /// Saves only actual server.properties keys, backing up the prior file before atomic replacement.
    public static void saveProperties(ServerInstance server, Properties properties) throws Exception {
        begin(server, false);
        try {
            ServerConfiguration.validate(properties);
            Path file = ServerSnapshots.safe(server.directory, "server.properties");
            if (Files.isRegularFile(file)) { Path backups = ServerSnapshots.directory(server).resolve("configuration"); Files.createDirectories(backups); Files.copy(file, backups.resolve("server-" + System.currentTimeMillis() + ".properties")); }
            Path pending = file.resolveSibling("server.properties.ochre-new");
            try { try (var output = Files.newOutputStream(pending)) { properties.store(output, "Updated by OchreCL"); } Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            finally { Files.deleteIfExists(pending); }
            if (properties.containsKey("server-port")) server.settings.setProperty("port", properties.getProperty("server-port"));
            if (properties.containsKey("online-mode")) server.settings.setProperty("online-mode", properties.getProperty("online-mode")); server.save();
        } finally { end(server); }
    }
    /// Replaces executable core files from a fully staged installation; caller creates a full snapshot first.
    public static String replaceCore(ServerInstance server, ServerInstance replacement, Consumer<String> progress) throws Exception {
        begin(server, false);
        Path rollback = server.directory.resolveSibling(".core-rollback-" + UUID.randomUUID());
        List<String> published = new ArrayList<>(), prior = new ArrayList<>(); Properties settings = new Properties(); settings.putAll(server.settings);
        try {
            Files.createDirectory(rollback);
            Set<String> components = new LinkedHashSet<>(List.of("server.jar", "installer.jar", "libraries")); components.add(replacement.settings.getProperty("jar", "server.jar"));
            for (String name : components) {
                Path source = ServerSnapshots.safe(replacement.directory, name); if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) continue;
                Path destination = ServerSnapshots.safe(server.directory, name), previous = ServerSnapshots.safe(rollback, name);
                if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) { Files.createDirectories(previous.getParent()); Files.move(destination, previous, StandardCopyOption.ATOMIC_MOVE); prior.add(name); }
                Files.createDirectories(destination.getParent()); Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE); published.add(name);
            }
            for (String name : List.of("core", "version", "javaMajor", "jar", "args")) { if (replacement.settings.containsKey(name)) server.settings.setProperty(name, replacement.settings.getProperty(name)); else server.settings.remove(name); }
            server.save(); progress.accept("核心已重新安装，世界及配置保留"); return "核心重装完成";
        } catch (Exception exception) {
            for (String name : published) { Path path = ServerSnapshots.safe(server.directory, name); if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) deleteTree(path); else Files.deleteIfExists(path); }
            for (String name : prior) { Path destination = ServerSnapshots.safe(server.directory, name); Files.createDirectories(destination.getParent()); Files.move(ServerSnapshots.safe(rollback, name), destination, StandardCopyOption.ATOMIC_MOVE); }
            server.settings.clear(); server.settings.putAll(settings); server.save(); throw exception;
        } finally { try { if (Files.exists(rollback)) deleteTree(rollback); } finally { end(server); } }
    }
    /// Deletes only task-owned rollback trees without following symbolic links.
    private static void deleteTree(Path root) throws IOException { try (var paths = Files.walk(root)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
    /// Removes only an unpublished temporary installation after replacement completes or fails.
    public static void discardTemporary(ServerInstance server) throws IOException {
        if (!server.directory.getParent().equals(ServerManager.ROOT) || server.session.isRunning()) throw new IOException("无法清理此临时安装");
        try (var paths = Files.walk(server.directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
    }
}
