package org.jackhuang.hmcl.server;

import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.task.Schedulers;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/// Owns only launcher-started SFTP and FRP child processes and private runtime configuration.
@NotNullByDefault
public final class ServerServices {
    /// Active helper processes keyed by installation and tool.
    private static final Map<String, Process> ACTIVE = new ConcurrentHashMap<>();
    /// Private configuration files associated only with owned FRP processes.
    private static final Map<Process, Path> CONFIGS = new ConcurrentHashMap<>();
    /// Keeps temporary credentials outside SFTP roots.
    private static final Path PRIVATE = Metadata.HMCL_LOCAL_HOME.resolve("ochre-service-runtime");
    /// Installs shutdown cleanup once without stopping unrelated system services.
    static { Runtime.getRuntime().addShutdownHook(new Thread(ServerServices::stopAll, "Ochre service cleanup")); }
    /// Utility class.
    private ServerServices() {}
    /// Stable runtime identity for a service on one installation.
    private static String key(ServerInstance server, ServerToolInstaller.Tool tool) { return server.directory.toAbsolutePath().normalize() + ":" + tool; }
    /// Returns whether the owned helper process remains alive.
    public static boolean running(ServerInstance server, ServerToolInstaller.Tool tool) { @Nullable Process process = ACTIVE.get(key(server, tool)); return process != null && process.isAlive(); }
    /// Starts password-authenticated SFTP, restricted to the selected server and loopback interface.
    public static void sftp(ServerInstance server, int port, String user, String password) throws Exception {
        sftp(server, "127.0.0.1", port, user, password);
    }
    /// Allows explicit external binding while retaining loopback as the default configuration.
    public static void sftp(ServerInstance server, String bind, int port, String user, String password) throws Exception {
        if (!bind.equals("127.0.0.1") && !bind.equals("0.0.0.0")) throw new IOException("不支持此 SFTP 监听地址");
        checkPort(port);
        if (server.maintenance) throw new IOException("请等待服务器文件维护完成");
        if (user.isBlank() || password.length() < 8) throw new IOException("请填写 SFTP 用户名和至少 8 位密码");
        Path cache = privateDirectory().resolve("sftp-" + server.directory.getFileName()); Files.createDirectories(cache);
        var builder = new ProcessBuilder(ServerToolInstaller.path(ServerToolInstaller.Tool.RCLONE).toString(), "serve", "sftp", server.directory.toAbsolutePath().toString(),
            "--addr", bind + ":" + port, "--cache-dir", cache.toString(), "--config", cache.resolve("rclone.conf").toString(), "--authorized-keys", cache.resolve("no-authorized-keys").toString());
        builder.environment().keySet().removeIf(name -> name.startsWith("RCLONE_"));
        builder.environment().put("RCLONE_USER", user); builder.environment().put("RCLONE_PASS", password);
        builder.environment().put("RCLONE_NO_AUTH", "false"); builder.environment().put("RCLONE_COPY_LINKS", "false");
        server.settings.setProperty("service.sftp.port", Integer.toString(port)); server.settings.setProperty("service.sftp.user", user); server.settings.setProperty("service.sftp.bind", bind); server.save();
        start(server, ServerToolInstaller.Tool.RCLONE, builder, null);
        ServerManager.notice("SFTP 已启动 · " + (bind.equals("127.0.0.1") ? "仅本机访问" : "允许网络访问") + " · 端口 " + port);
    }
    /// Starts a TCP FRP client using a player-provided relay; does not invent a public relay or alter server authentication.
    public static void tunnel(ServerInstance server, String host, int relayPort, int remotePort, String token) throws Exception {
        checkPort(relayPort); checkPort(remotePort);
        if (host.isBlank() || token.isBlank()) throw new IOException("请填写穿透服务地址和认证令牌");
        Path config = Files.createTempFile(privateDirectory(), "frpc-", ".toml");
        try {
            restrict(config, false);
            String text = "serverAddr = " + quote(host) + "\nserverPort = " + relayPort + "\nloginFailExit = true\nauth.method = \"token\"\nauth.token = " + quote(token)
                + "\ntransport.tls.enable = true\nlog.level = \"error\"\n[[proxies]]\nname = " + quote("ochre-" + server.directory.getFileName())
                + "\ntype = \"tcp\"\nlocalIP = \"127.0.0.1\"\nlocalPort = " + Integer.parseInt(server.settings.getProperty("port", "25565")) + "\nremotePort = " + remotePort + "\n";
            Files.writeString(config, text);
            server.settings.setProperty("service.frp.host", host); server.settings.setProperty("service.frp.port", Integer.toString(relayPort)); server.settings.setProperty("service.frp.remote", Integer.toString(remotePort)); server.save();
            start(server, ServerToolInstaller.Tool.FRPC, new ProcessBuilder(ServerToolInstaller.path(ServerToolInstaller.Tool.FRPC).toString(), "-c", config.toString()), config);
        } catch (Exception exception) { Files.deleteIfExists(config); throw exception; }
    }
    /// Starts the official OpenFrp client for one user-selected tunnel; credentials are never persisted.
    public static void openFrp(ServerInstance server, OpenFrpAccount.Tunnel tunnel) throws Exception {
        if (tunnel.localPort() != Integer.parseInt(server.settings.getProperty("port", "25565"))) throw new IOException("服务器端口已更改，请重新选择 OpenFrp 隧道");
        if (tunnel.id() <= 0 || tunnel.token().isBlank()) throw new IOException("OpenFrp 隧道授权无效");
        start(server, ServerToolInstaller.Tool.OPENFRP, new ProcessBuilder(ServerToolInstaller.path(ServerToolInstaller.Tool.OPENFRP).toString(), "-n", "-u", tunnel.token(), "-p", Integer.toString(tunnel.id())).directory(privateDirectory().toFile()), null);
        server.settings.setProperty("service.openfrp.id", Integer.toString(tunnel.id())); server.save();
        ServerManager.notice("OpenFrp 客户端已启动 · " + tunnel.address());
    }
    /// Creates owner-only credential storage on POSIX platforms.
    private static Path privateDirectory() throws IOException { Files.createDirectories(PRIVATE); restrict(PRIVATE, true); return PRIVATE; }
    /// Restricts service files to their owner where POSIX permissions are available.
    private static void restrict(Path file, boolean directory) throws IOException {
        if (Files.getFileStore(file).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
    }
    /// Escapes a TOML basic string using the compatible JSON string representation.
    static String quote(String value) { return new com.google.gson.Gson().toJson(value); }
    /// Rejects invalid network ports before process creation.
    static void checkPort(int port) throws IOException { if (port < 1 || port > 65535) throw new IOException("端口应在 1–65535 之间"); }
    /// Starts one owned process, detects immediate startup failure and cleans credentials on exit.
    private static synchronized void start(ServerInstance server, ServerToolInstaller.Tool tool, ProcessBuilder builder, @Nullable Path config) throws Exception {
        String identity = key(server, tool);
        if (running(server, tool)) throw new IOException("对应服务已经运行");
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        Process process = builder.start(); ACTIVE.put(identity, process);
        try {
            if (process.waitFor(1500, TimeUnit.MILLISECONDS)) throw new IOException("工具启动失败（退出代码 " + process.exitValue() + "），请检查端口及认证配置");
        } catch (Exception exception) { process.destroy(); ACTIVE.remove(identity, process); if (config != null) Files.deleteIfExists(config); throw exception; }
        if (config != null) CONFIGS.put(process, config);
        process.onExit().thenRun(() -> {
            boolean unexpected = ACTIVE.remove(identity, process);
            if (config != null) { CONFIGS.remove(process); try { Files.deleteIfExists(config); } catch (IOException ignored) {} }
            if (unexpected) ServerManager.notice(ServerManager.NoticeKind.WARNING, tool.binary + " 服务已退出（代码 " + process.exitValue() + "）");
        });
        ServerManager.notice(ServerManager.NoticeKind.INFO, tool != ServerToolInstaller.Tool.RCLONE ? "穿透客户端已启动，连接状态请通过外网连接验证" : "SFTP 进程已启动");
    }
    /// Stops the selected owned helper and waits briefly for normal cleanup.
    public static void stop(ServerInstance server, ServerToolInstaller.Tool tool) throws InterruptedException {
        @Nullable Process process = ACTIVE.remove(key(server, tool));
        if (process != null) { process.destroy(); if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly(); }
    }
    /// Stops all launcher-owned helpers during application shutdown.
    private static void stopAll() {
        var processes = List.copyOf(ACTIVE.values()); ACTIVE.clear();
        for (Process process : processes) process.destroy();
        for (Process process : processes) {
            try { if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly(); } catch (InterruptedException exception) { process.destroyForcibly(); Thread.currentThread().interrupt(); }
            @Nullable Path config = CONFIGS.remove(process); if (config != null) try { Files.deleteIfExists(config); } catch (IOException ignored) {}
        }
    }
}
