package org.jackhuang.hmcl.server;

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.java.JavaManager;
import org.jackhuang.hmcl.java.JavaRuntime;
import org.jackhuang.hmcl.task.Schedulers;
import org.jetbrains.annotations.NotNullByDefault;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/// Owns local server lifecycle, serialized startup, graceful shutdown, persistence and notifications.
@NotNullByDefault
public final class ServerManager {
    /// Installed local servers; mutations occur on the JavaFX thread.
    public static final ObservableList<ServerInstance> SERVERS = FXCollections.observableArrayList();
    /// Semantic notification category, selected before publishing each message on the JavaFX thread.
    @NotNullByDefault
    public enum NoticeKind { INFO, SUCCESS, WARNING, ERROR }
    /// Current category for the compatibility string notification property.
    public static final javafx.beans.property.ObjectProperty<NoticeKind> NOTICE_KIND = new javafx.beans.property.SimpleObjectProperty<>(NoticeKind.INFO);
    /// New lifecycle messages consumed by the shell's top-right notification overlay.
    public static final StringProperty NOTICE = new SimpleStringProperty("");
    /// Root directory for isolated, player-managed installations.
    public static final Path ROOT = Metadata.HMCL_LOCAL_HOME.resolve("ochre-servers");
    /// Whether directory discovery has begun.
    private static boolean loaded;
    /// Coalesces repeated close requests while servers save their worlds.
    private static boolean shuttingDown;
    /// Prevents construction of the shared manager.
    private ServerManager() {}
    /// Discovers installations without scanning on every navigation.
    public static synchronized void load() {
        if (loaded) return;
        loaded = true;
        ServerAutomation.start();
        Schedulers.io().execute(() -> {
            try {
                Files.createDirectories(ROOT);
                List<ServerInstance> values = new ArrayList<>();
                try (var dirs = Files.list(ROOT)) {
                    for (Path dir : dirs.sorted().toList()) {
                        if (!dir.getFileName().toString().startsWith(".") && Files.isRegularFile(dir.resolve("ochre-server.properties"))) values.add(new ServerInstance(dir));
                    }
                }
                Platform.runLater(() -> SERVERS.addAll(values));
            } catch (IOException e) { notice("服务器列表读取失败：" + e.getMessage()); }
        });
    }
    /// Posts a fresh notification even when the same message occurs twice.
    public static void notice(String text) { notice(classifyNotice(text), text); }
    /// Publishes a category and content atomically so repeated messages can replay their animation.
    public static void notice(NoticeKind kind, String text) {
        Platform.runLater(() -> { NOTICE_KIND.set(kind); NOTICE.set(""); NOTICE.set(text); });
    }
    /// Classifies legacy text-only callers; critical lifecycle events can pass their category explicitly.
    static NoticeKind classifyNotice(String text) {
        if (text.contains("失败") || text.contains("异常") || text.contains("错误") || text.contains("无效")) return NoticeKind.ERROR;
        if (text.contains("请先") || text.contains("超时") || text.contains("尚未") || text.contains("未运行") || text.contains("无法")) return NoticeKind.WARNING;
        if (text.contains("成功") || text.contains("已复制") || text.contains("已保存") || text.contains("已恢复") || text.contains("部署完成")) return NoticeKind.SUCCESS;
        return NoticeKind.INFO;
    }
    /// Defers application exit until owned servers have saved and stopped, without silently killing worlds.
    public static boolean deferShutdown(Runnable proceed) {
        var active = SERVERS.stream().filter(server -> server.busy || server.session.isRunning()).toList();
        if (active.isEmpty()) return false;
        if (shuttingDown) return true;
        shuttingDown = true;
        notice("正在保存并停止服务器，完成后退出…");
        for (var server : active) { server.stopping = true; if (server.session.isRunning()) stop(server, false); }
        Schedulers.io().execute(() -> {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(65);
            try {
                while (active.stream().anyMatch(server -> server.busy || server.session.isRunning()) && System.nanoTime() < deadline) Thread.sleep(100);
                boolean complete = active.stream().noneMatch(server -> server.busy || server.session.isRunning());
                Platform.runLater(() -> { shuttingDown = false; if (complete) proceed.run(); else notice("服务器尚未停止，已保留窗口。请查看控制台或明确选择强制结束。"); });
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); Platform.runLater(() -> shuttingDown = false); }
        });
        return true;
    }

    /// A recoverable server installation kept intact after removal from the active list.
    @NotNullByDefault
    public record DeletedServer(Path directory, String name) {
        /// Displays the original player-assigned name in the recovery picker.
        @Override public String toString() { return name; }
    }
    /// Creates an archive directory inside the managed root and rejects redirected paths.
    private static Path archiveRoot() throws IOException {
        Files.createDirectories(ROOT);
        Path root = ROOT.toRealPath();
        Path archive = Files.createDirectories(root.resolve(".deleted")).toRealPath();
        if (!archive.getParent().equals(root)) throw new IOException("已删除目录不在服务器根目录内");
        return archive;
    }
    /// Moves a stopped managed installation intact to recoverable storage; never erases world files.
    public static Path remove(ServerInstance server) throws IOException {
        synchronized (ServerManager.class) {
            if (server.busy || server.maintenance || server.session.isRunning() || ServerServices.running(server, ServerToolInstaller.Tool.RCLONE) || ServerServices.running(server, ServerToolInstaller.Tool.FRPC) || ServerServices.running(server, ServerToolInstaller.Tool.OPENFRP)) throw new IOException("请先停止服务器及文件传输、穿透服务，等待当前操作完成后再删除");
            server.busy = true;
        }
        try {
            Path source = server.directory.toRealPath();
            if (!Files.isDirectory(source) || Files.isSymbolicLink(server.directory) || !source.getParent().equals(ROOT.toRealPath())) throw new IOException("只能删除此启动器管理的服务器目录");
            Path destination = archiveRoot().resolve(source.getFileName() + "-" + UUID.randomUUID());
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
            Platform.runLater(() -> {
                SERVERS.remove(server);
                if (ServersPage.SELECTED.get() == server) ServersPage.SELECTED.set(SERVERS.isEmpty() ? null : SERVERS.get(0));
                notice(server + "：已删除，可从“已删除服务器”恢复");
            });
            return destination;
        } finally { server.busy = false; }
    }
    /// Lists intact recoverable installations without loading them into the active server picker.
    public static @org.jetbrains.annotations.Unmodifiable List<DeletedServer> deletedServers() throws IOException {
        List<DeletedServer> result = new ArrayList<>();
        try (var directories = Files.list(archiveRoot())) {
            for (Path directory : directories.sorted().toList()) {
                if (Files.isSymbolicLink(directory) || !Files.isRegularFile(directory.resolve("ochre-server.properties"))) continue;
                result.add(new DeletedServer(directory, new ServerInstance(directory).toString()));
            }
        }
        return List.copyOf(result);
    }
    /// Restores an archived installation to a fresh active directory with all worlds and configuration preserved.
    public static ServerInstance restore(DeletedServer deleted) throws IOException {
        Path source = deleted.directory().toRealPath();
        if (Files.isSymbolicLink(deleted.directory()) || !source.getParent().equals(archiveRoot())) throw new IOException("无效的已删除服务器路径");
        // Read metadata before moving so a corrupt entry stays in recoverable storage.
        new ServerInstance(source);
        Path destination = ROOT.toRealPath().resolve(UUID.randomUUID().toString());
        Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        ServerInstance server = new ServerInstance(destination);
        Platform.runLater(() -> { SERVERS.add(server); ServersPage.SELECTED.set(server); notice(server + "：已恢复"); });
        return server;
    }

    /// Finds the newest installed compatible Java, honoring an explicit executable override.
    static Path javaBinary(ServerInstance server) throws Exception {
        String custom = server.settings.getProperty("java", "").strip();
        if (!custom.isEmpty()) {
            Path path = Path.of(custom);
            if (!Files.isRegularFile(path)) throw new IOException("所选 Java 不存在");
            return path;
        }
        int required = Integer.parseInt(server.settings.getProperty("javaMajor", "21"));
        return JavaManager.getAllJava().stream().filter(java -> major(java.getVersion()) == required)
                .max(Comparator.comparing(JavaRuntime::getVersionNumber)).orElseThrow(() ->
                        new IOException("未找到 Java " + required + " 或更高版本，请在 Java 管理中安装，或选择 Java 可执行文件")).getBinary();
    }
    /// Parses the major Java release without relying on lexicographic version ordering.
    static int major(String version) {
        String[] parts = version.split("[._+\\-]");
        return Integer.parseInt(parts[0].equals("1") ? parts[1] : parts[0]);
    }
    /// Starts a deployed jar only after the player has accepted the agreement in the UI.
    public static synchronized void start(ServerInstance server) {
        if (server.busy || server.maintenance || server.session.isRunning()) return;
        if (!server.acceptedEula()) { notice("请先阅读并同意 Minecraft EULA"); return; }
        server.busy = true;
        server.stopping = false;
        server.session.status("启动中");
        Schedulers.io().execute(() -> {
            try {
                Path java = javaBinary(server);
                int memory = Integer.parseInt(server.settings.getProperty("memory", "2048"));
                if (memory < 512 || memory > 65536) throw new IOException("内存应为 512–65536 MiB");
                int port = Integer.parseInt(server.settings.getProperty("port", "25565"));
                if (port < 1 || port > 65535) throw new IOException("端口应为 1–65535");
                List<String> command = new ArrayList<>(List.of(java.toString(), "-Xms256M", "-Xmx" + memory + "M"));
                command.addAll(ServerConfiguration.jvmArguments(server.settings.getProperty("jvm", "")));
                String argFile = server.settings.getProperty("args", "");
                if (!argFile.isEmpty()) {
                    String platform = System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "win_args.txt" : "unix_args.txt";
                    Path arguments = server.directory.resolve(argFile).resolveSibling(platform).normalize();
                    if (!arguments.startsWith(server.directory) || !Files.isRegularFile(arguments)) throw new IOException("Forge 启动参数文件缺失，请重新部署");
                    command.add("@" + server.directory.relativize(arguments));
                } else {
                    Path jar = ServerSnapshots.safe(server.directory, server.settings.getProperty("jar", "server.jar"));
                    if (!Files.isRegularFile(jar)) throw new IOException("找不到服务端 JAR");
                    command.add("-jar"); command.add(server.directory.toAbsolutePath().normalize().relativize(jar).toString());
                }
                command.addAll(List.of("nogui", "--port", Integer.toString(port)));
                command.addAll(ServerConfiguration.serverArguments(server.settings.getProperty("server.arguments", "")));
                if (server.stopping) throw new java.util.concurrent.CancellationException("启动已取消");
                Process process = new ProcessBuilder(command).directory(server.directory.toFile()).redirectErrorStream(true).start();
                server.session.attach(process);
                boolean ready = false;
                try (var reader = process.inputReader(StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        server.session.append(line);
                        if (!ready && isReady(line)) {
                            ready = true;
                            server.session.status("运行中");
                            notice(NoticeKind.SUCCESS, server + "：服务器启动成功");
                        }
                    }
                }
                int code = process.waitFor();
                boolean normal = server.stopping || (ready && code == 0);
                server.session.status(normal ? "已停止" : "异常退出");
                notice(normal ? NoticeKind.INFO : NoticeKind.ERROR, server + (normal ? "：已停止" : "：服务器异常退出（代码 " + code + "），请查看日志"));
            } catch (Exception e) {
                server.session.append("启动失败：" + e.getMessage());
                server.session.status("启动失败");
                notice(NoticeKind.ERROR, server + "：启动失败，" + e.getMessage());
            } finally { server.busy = false; }
        });
    }
    /// Recognizes Minecraft's explicit successful startup line rather than any incidental Done text.
    static boolean isReady(String line) { return line.contains("Done (") && line.contains("For help"); }
    /// Sends a single console command with a flushed UTF-8 line ending.
    public static void command(ServerInstance server, String command) throws IOException {
        var process = server.session.process();
        if (process == null || !process.isAlive()) throw new IOException("服务器未运行");
        if (command.isBlank() || command.contains("\n") || command.contains("\r")) throw new IOException("请输入一条命令");
        synchronized (server) {
            if (command.strip().equalsIgnoreCase("stop")) server.stopping = true;
            process.getOutputStream().write((command + "\n").getBytes(StandardCharsets.UTF_8));
            process.getOutputStream().flush();
        }
    }
    /// Requests world-saving graceful shutdown; force stop is a separate explicit user action.
    public static void stop(ServerInstance server, boolean restart) {
        if (server.maintenance) { notice("正在备份或维护，请完成后再停止服务器"); return; }
        if (!server.session.isRunning()) return;
        server.stopping = true;
        server.session.status("停止中");
        Schedulers.io().execute(() -> {
            try {
                var process = server.session.process();
                command(server, "stop");
                if (process != null && !process.waitFor(60, TimeUnit.SECONDS)) {
                    notice(server + "：停止超时，可在面板中强制结束");
                    return;
                }
                if (restart) {
                    while (server.busy) Thread.sleep(50);
                    start(server);
                }
            } catch (Exception e) { notice("停止服务器失败：" + e.getMessage()); }
        });
    }
}
