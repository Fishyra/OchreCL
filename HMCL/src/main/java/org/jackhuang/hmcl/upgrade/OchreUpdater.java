package org.jackhuang.hmcl.upgrade;

import com.google.gson.*;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.JarFile;

/// Checks only this fork's GitHub Releases and stages verified launcher-only updates in user data.
@NotNullByDefault
public final class OchreUpdater {
    /// Official feed supports prereleases as well as stable releases; never the upstream HMCL feed.
    public static final String FEED = "https://api.github.com/repos/Fishyra/OchreCL/releases?per_page=20";
    /// Bounded image size matches the standalone bootstrap limit.
    private static final long LIMIT = 160L * 1024 * 1024;
    /// Utility class.
    private OchreUpdater() {}
    /// Release identity and GitHub-computed SHA-256 digest; only the named fork payload is eligible.
    @NotNullByDefault
    public record Release(String version, String notes, String url, String sha256, long size) {}
    /// Downloads a bounded release-list response from the fixed official project.
    public static @Nullable Release check() throws IOException {
        try (var input = connect(FEED).getInputStream()) { byte[] data = input.readNBytes(1024 * 1024 + 1); if (data.length > 1024 * 1024) throw new IOException("发布信息过大"); return parse(new String(data, java.nio.charset.StandardCharsets.UTF_8), Metadata.VERSION); }
    }
    /// Selects the newest non-draft, newer release containing an exact update payload and a valid digest.
    public static @Nullable Release parse(String json, String current) throws IOException {
        try {
            @Nullable Release selected = null;
            for (var item : JsonParser.parseString(json).getAsJsonArray()) {
                var release = item.getAsJsonObject(); if (release.get("draft").getAsBoolean()) continue;
                String version = release.get("tag_name").getAsString().replaceFirst("^v", "");
                if (!version.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:[-+.][A-Za-z0-9.-]+)?") || VersionNumber.compare(version, current) <= 0) continue;
                for (var entry : release.getAsJsonArray("assets")) {
                    var asset = entry.getAsJsonObject(); if (!"OchreCL-update.jar".equals(asset.get("name").getAsString())) continue;
                    if (!asset.has("digest") || asset.get("digest").isJsonNull()) continue;
                    String digest = asset.get("digest").getAsString(); if (!digest.matches("sha256:[a-f0-9]{64}")) continue;
                    String url = asset.get("browser_download_url").getAsString(); validateUrl(url); long size = asset.get("size").getAsLong(); if (size <= 0 || size > LIMIT) continue;
                    Release candidate = new Release(version, release.has("body") && !release.get("body").isJsonNull() ? release.get("body").getAsString() : "", url, digest.substring(7), size);
                    if (selected == null || VersionNumber.compare(candidate.version(), selected.version()) > 0) selected = candidate;
                }
            }
            return selected;
        } catch (RuntimeException error) { throw new IOException("无法解析 ØchreCL 发布信息", error); }
    }
    /// Validates download URLs against this project's release assets before any request is made.
    public static void validateUrl(String value) throws IOException {
        try { URI uri = URI.create(value); if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) || uri.getUserInfo() != null || uri.getPort() != -1 || !uri.getPath().startsWith("/Fishyra/OchreCL/releases/download/") || uri.getRawQuery() != null || uri.getFragment() != null) throw new IOException("更新地址必须来自 Fishyra/OchreCL 的 HTTPS 发布资源"); }
        catch (IllegalArgumentException error) { throw new IOException("更新地址无效", error); }
    }
    /// Streams an update, checks length, SHA-256 and launcher markers, then atomically selects it for next launch.
    public static Path download(Release release, Consumer<String> progress) throws Exception {
        validateUrl(release.url()); Path root = Metadata.HMCL_LOCAL_HOME.resolve("launcher-updates"); Files.createDirectories(root); Path pending = Files.createTempFile(root, "download-", ".part");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256"); long count = 0;
            try (var input = connect(release.url()).getInputStream(); var output = Files.newOutputStream(pending)) { byte[] buffer = new byte[65536]; int size; long previous = 0;
                while ((size = input.read(buffer)) >= 0) { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("更新已取消"); count += size; if (count > LIMIT || count > release.size()) throw new IOException("更新包大小超出发布信息"); output.write(buffer, 0, size); digest.update(buffer, 0, size); long now = System.nanoTime(); if (now - previous > 200000000L) { progress.accept("下载更新 " + count * 100 / release.size() + "%"); previous = now; } }
            }
            if (count != release.size() || !HexFormat.of().formatHex(digest.digest()).equals(release.sha256())) throw new IOException("更新包校验失败，原程序保持不变");
            return stage(pending, root, release);
        } finally { Files.deleteIfExists(pending); }
    }
    /// Atomically changes only a user-space pointer after complete payload validation; the bundle is untouched.
    public static synchronized Path stage(Path payload, Path root, Release release) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("更新已取消");
        if (!release.sha256().matches("[a-f0-9]{64}") || Files.size(payload) != release.size() || Files.size(payload) > LIMIT) throw new IOException("更新元数据无效");
        MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (var input = Files.newInputStream(payload)) { byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count); }
        if (!HexFormat.of().formatHex(digest.digest()).equals(release.sha256())) throw new IOException("更新包 SHA-256 不匹配");
        try (JarFile jar = new JarFile(payload.toFile())) { if (jar.getEntry("org/jackhuang/hmcl/Main.class") == null || jar.getEntry("org/jackhuang/hmcl/ui/decorator/OchreShell.class") == null) throw new IOException("文件不是 ØchreCL 更新包"); }
        Files.createDirectories(root); String name = "OchreCL-" + release.sha256() + ".jar"; Path image = root.resolve(name); if (!Files.exists(image)) Files.copy(payload, image); else { MessageDigest existing = MessageDigest.getInstance("SHA-256"); try (var input = Files.newInputStream(image)) { byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) >= 0) existing.update(buffer, 0, count); } if (!HexFormat.of().formatHex(existing.digest()).equals(release.sha256())) throw new IOException("已存在的更新镜像损坏，请删除后重新下载"); }
        Properties pointer = new Properties(); pointer.setProperty("file", name); pointer.setProperty("sha256", release.sha256()); pointer.setProperty("version", release.version());
        Path staged = root.resolve("active.properties.new"), active = root.resolve("active.properties"); if (Files.exists(active)) Files.copy(active, root.resolve("previous.properties"), StandardCopyOption.REPLACE_EXISTING);
        try (var output = Files.newOutputStream(staged)) { pointer.store(output, "Validated OchreCL update; native bundle unchanged"); }
        if (Thread.currentThread().isInterrupted()) { Files.deleteIfExists(staged); throw new InterruptedIOException("更新已取消"); }
        Files.move(staged, active, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); return image;
    }
    /// Removes the selected update to return to the original bundled launcher on next start.
    public static void rollback() throws IOException { Path root = Metadata.HMCL_LOCAL_HOME.resolve("launcher-updates"); Files.deleteIfExists(root.resolve("active.properties")); }
    /// Follows bounded HTTPS redirects only, allowing GitHub's signed release CDN but no protocol downgrade.
    private static HttpURLConnection connect(String url) throws IOException {
        URL current = URI.create(url).toURL();
        for (int i = 0; i < 6; i++) {
            if (!"https".equals(current.getProtocol())) throw new IOException("拒绝不安全的更新重定向");
            var connection = (HttpURLConnection) current.openConnection(); connection.setConnectTimeout(8000); connection.setReadTimeout(12000); connection.setInstanceFollowRedirects(false); connection.setRequestProperty("User-Agent", "OchreCL-Updater"); connection.setRequestProperty("Accept", "application/vnd.github+json, application/octet-stream");
            int code = connection.getResponseCode(); if (code >= 300 && code < 400) { @Nullable String location = connection.getHeaderField("Location"); connection.disconnect(); if (location == null) throw new IOException("更新重定向无目标"); current = new URL(current, location); continue; }
            if (code != 200) { connection.disconnect(); throw new IOException("更新服务 HTTP " + code); } return connection;
        }
        throw new IOException("更新重定向次数过多");
    }
}
