package org.jackhuang.hmcl.server;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/// Validated deployment settings and shell-free JVM argument handling shared by installation and management.
@NotNullByDefault
public final class ServerConfiguration {
    /// Properties written to the actual Minecraft server configuration.
    private static final @Unmodifiable Set<String> GAME = Set.of("server-port", "online-mode", "max-players", "view-distance", "simulation-distance", "difficulty", "gamemode", "white-list", "motd");
    /// Utility class.
    private ServerConfiguration() {}
    /// Rejects malformed values before any downloads or writes begin.
    public static void validate(Properties options) throws IOException {
        if (options.containsKey("name") && (options.getProperty("name").isBlank() || options.getProperty("name").length() > 120)) throw new IOException("服务器名称应为 1–120 个字符");
        range(options, "port", 1, 65535); range(options, "memory", 512, 65536); range(options, "server-port", 1, 65535);
        range(options, "max-players", 1, 100000); range(options, "view-distance", 2, 32); range(options, "simulation-distance", 2, 32);
        if (!options.getProperty("restart.time", "").isBlank()) try { java.time.LocalTime.parse(options.getProperty("restart.time")); } catch (Exception exception) { throw new IOException("自动重启时间格式应为 HH:mm"); }
        jvmArguments(options.getProperty("jvm", "")); serverArguments(options.getProperty("server.arguments", ""));
        if (options.containsKey("jar")) { Path jar = Path.of(options.getProperty("jar")); if (jar.isAbsolute() || jar.normalize().startsWith("..") || !jar.toString().endsWith(".jar")) throw new IOException("服务端 JAR 必须是此服务器目录内的相对路径"); }
    }
    /// Validates an optional bounded integer without silently replacing player input.
    private static void range(Properties values, String key, int min, int max) throws IOException {
        if (!values.containsKey(key)) return;
        try { int value = Integer.parseInt(values.getProperty(key)); if (value < min || value > max) throw new NumberFormatException(); }
        catch (NumberFormatException exception) { throw new IOException(key + " 应为 " + min + "–" + max); }
    }
    /// Accepts one JVM option per line and rejects entry-point/argument-file injection.
    public static @Unmodifiable List<String> jvmArguments(String text) throws IOException {
        List<String> result = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            String line = raw.strip(); if (line.isEmpty()) continue;
            if (!(line.startsWith("-D") || line.startsWith("-XX:") || line.startsWith("-Xlog:") || line.equals("-ea") || line.equals("-da")) || line.contains("\u0000")) throw new IOException("JVM 参数每行一项，仅支持 -D、-XX:、-Xlog:、-ea 或 -da；内存请使用内存设置");
            result.add(line);
        }
        return List.copyOf(result);
    }
    /// Parses one literal server argument per line without shell interpretation.
    public static @Unmodifiable List<String> serverArguments(String text) throws IOException {
        List<String> result = new ArrayList<>();
        for (String raw : text.split("\\R")) { String value = raw.strip(); if (value.isEmpty()) continue; if (value.length() > 2048 || value.contains("\u0000") || result.size() >= 100) throw new IOException("服务端参数过长或过多"); result.add(value); }
        return List.copyOf(result);
    }
    /// Saves game and launcher properties while retaining unknown server-specific properties.
    public static void apply(ServerInstance server, Properties options) throws IOException {
        ServerMaintenance.begin(server, false);
        try { applyGuarded(server, options); } finally { ServerMaintenance.end(server); }
    }
    /// Writes settings only after the caller owns the stopped-server maintenance guard.
    private static void applyGuarded(ServerInstance server, Properties options) throws IOException {
        validate(options);
        Properties game = new Properties(); Path file = server.directory.resolve("server.properties");
        if (Files.isRegularFile(file)) try (var input = Files.newInputStream(file)) { game.load(input); }
        for (String key : options.stringPropertyNames()) {
            if (GAME.contains(key)) game.setProperty(key, options.getProperty(key));
            else if (!key.equals("eula")) server.settings.setProperty(key, options.getProperty(key));
        }
        if (options.containsKey("port")) game.setProperty("server-port", options.getProperty("port"));
        if (options.containsKey("server-port")) server.settings.setProperty("port", options.getProperty("server-port"));
        if (options.containsKey("online-mode")) server.settings.setProperty("online-mode", options.getProperty("online-mode"));
        Path pending = file.resolveSibling("server.properties.ochre-new");
        try (var output = Files.newOutputStream(pending)) { game.store(output, "Configured by OchreCL"); }
        Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        if (options.getProperty("eula", "false").equals("true")) Files.writeString(server.directory.resolve("eula.txt"), "eula=true\n");
        server.save();
    }
}
