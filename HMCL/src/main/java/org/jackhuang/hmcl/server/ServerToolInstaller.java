package org.jackhuang.hmcl.server;

import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.*;

/// Installs verified official rclone/frpc executables without invoking a shell or installer script.
@NotNullByDefault
public final class ServerToolInstaller {
    /// Supported external integrations and official upstream repositories.
    @NotNullByDefault
    public enum Tool {
        RCLONE("rclone", "rclone/rclone"), FRPC("frpc", "fatedier/frp"), OPENFRP("openfrpc", "");
        /// Executable base name.
        final String binary;
        /// Official release repository.
        final String repository;
        /// Creates a fixed, non-user-controlled download source.
        Tool(String binary, String repository) { this.binary = binary; this.repository = repository; }
    }
    /// Tool binaries are stored outside all served server directories.
    private static final Path ROOT = Metadata.HMCL_LOCAL_HOME.resolve("ochre-tools");
    /// Static utility class.
    private ServerToolInstaller() {}
    /// Platform-specific executable filename.
    static String executable(Tool tool) { return tool.binary + (platform().equals("windows") ? ".exe" : ""); }
    /// Resolves only launcher-managed executables, avoiding untrusted current-directory/PATH entries.
    public static Path path(Tool tool) { return ROOT.resolve(tool.binary).resolve(executable(tool)); }
    /// Detects a regular executable; runs no process during UI construction.
    public static boolean available(Tool tool) { Path file = path(tool); return Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.isExecutable(file); }
    /// Detects supported desktop platforms explicitly instead of silently downloading a wrong binary.
    static String platform() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        if (os.contains("mac")) return "osx";
        if (os.contains("win")) return "windows";
        if (os.contains("linux")) return "linux";
        throw new IllegalStateException("此系统暂无自动安装支持");
    }
    /// Selects the current JVM architecture for compatible native executables.
    static String architecture() {
        return switch (System.getProperty("os.arch", "").toLowerCase(Locale.ROOT)) {
            case "aarch64", "arm64" -> "arm64";
            case "amd64", "x86_64" -> "amd64";
            default -> throw new IllegalStateException("此处理器架构暂无自动安装支持");
        };
    }
    /// Downloads a release asset with its official SHA-256 digest, extracts only the desired binary and publishes atomically.
    public static synchronized void install(Tool tool, Consumer<String> progress) throws Exception {
        if (available(tool)) return;
        if (tool == Tool.OPENFRP) { OpenFrpInstaller.install(progress); return; }
        Files.createDirectories(ROOT);
        Path staging = Files.createTempDirectory(ROOT, ".install-");
        try {
            progress.accept("获取官方工具版本…");
            var release = ServerCatalog.json("https://api.github.com/repos/" + tool.repository + "/releases/latest").getAsJsonObject();
            String version = release.get("tag_name").getAsString();
            String os = platform(), arch = architecture();
            String name = tool == Tool.RCLONE ? "rclone-" + version + "-" + os + "-" + arch + ".zip"
                : "frp_" + version.replaceFirst("^v", "") + "_" + (os.equals("osx") ? "darwin" : os) + "_" + arch + (os.equals("windows") ? ".zip" : ".tar.gz");
            @Nullable com.google.gson.JsonObject selected = null;
            for (var entry : release.getAsJsonArray("assets")) {
                var asset = entry.getAsJsonObject();
                if (name.equals(asset.get("name").getAsString())) { selected = asset; break; }
            }
            if (selected == null) throw new IOException("官方版本没有适用于此系统的工具文件");
            @Nullable var digestValue = selected.get("digest");
            String digest = digestValue == null || digestValue.isJsonNull() ? "" : digestValue.getAsString();
            if (!digest.matches("sha256:[0-9a-fA-F]{64}")) throw new IOException("官方文件未提供 SHA-256 校验值，已停止安装");
            Path archive = staging.resolve(name);
            ServerCatalog.download(new ServerCatalog.Artifact(name, selected.get("browser_download_url").getAsString(), "SHA-256", digest.substring(7), tool.binary, version, 0), archive, progress);
            progress.accept("校验完成，正在安装…");
            Path binary = staging.resolve(executable(tool));
            extract(archive, executable(tool), binary);
            if (!binary.toFile().setExecutable(true, true) && !Files.isExecutable(binary)) throw new IOException("无法设置工具执行权限");
            Path destination = path(tool); Files.createDirectories(destination.getParent());
            Files.move(binary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            progress.accept("工具安装完成");
        } finally {
            try (var files = Files.walk(staging)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); }
        }
    }
    /// Extracts one regular executable without resolving archive-controlled paths or following links.
    static void extract(Path archive, String wanted, Path destination) throws IOException {
        if (archive.toString().endsWith(".zip")) {
            try (var input = new ZipInputStream(Files.newInputStream(archive))) {
                ZipEntry entry;
                while ((entry = input.getNextEntry()) != null) {
                    if (!entry.isDirectory() && basename(entry.getName()).equals(wanted)) { copy(input, destination, -1); return; }
                }
            }
        } else {
            try (var input = new GZIPInputStream(Files.newInputStream(archive))) {
                byte[] header;
                while ((header = input.readNBytes(512)).length == 512 && header[0] != 0) {
                    String name = new String(header, 0, 100, StandardCharsets.UTF_8).split("\u0000", 2)[0];
                    String octal = new String(header, 124, 12, StandardCharsets.US_ASCII).replace("\u0000", "").trim();
                    long length;
                    try { length = Long.parseLong(octal, 8); } catch (NumberFormatException e) { throw new IOException("工具归档格式无效", e); }
                    if (length < 0 || length > 512L * 1024 * 1024) throw new IOException("工具归档大小无效");
                    if ((header[156] == 0 || header[156] == '0') && basename(name).equals(wanted)) { copy(input, destination, length); return; }
                    input.skipNBytes(length + (512 - length % 512) % 512);
                }
            }
        }
        throw new IOException("归档中没有找到工具可执行文件");
    }
    /// Treats archive member names as text, never filesystem destinations.
    private static String basename(String name) { String value = name.replace('\\', '/'); return value.substring(value.lastIndexOf('/') + 1); }
    /// Enforces an extracted executable size bound and detects truncated tar members.
    private static void copy(InputStream input, Path destination, long length) throws IOException {
        try (var output = Files.newOutputStream(destination, StandardOpenOption.CREATE_NEW)) {
            byte[] buffer = new byte[65536]; long total = 0; int count;
            while (length < 0 || total < length) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("安装已取消");
                count = input.read(buffer, 0, (int) Math.min(buffer.length, length < 0 ? buffer.length : length - total));
                if (count < 0) { if (length >= 0) throw new EOFException("工具归档不完整"); break; }
                total += count; if (total > 512L * 1024 * 1024) throw new IOException("工具文件过大"); output.write(buffer, 0, count);
            }
            if (total == 0) throw new IOException("工具文件为空");
        }
    }
}
