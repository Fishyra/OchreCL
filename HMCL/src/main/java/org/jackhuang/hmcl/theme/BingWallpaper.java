package org.jackhuang.hmcl.theme;

import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.scene.image.Image;
import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.Properties;

/// Retrieves Bing's daily picture with bounded downloads, local caching and retained attribution.
@NotNullByDefault
public final class BingWallpaper {
    /// Application-owned image cache, containing no account data.
    private static final Path CACHE = Metadata.HMCL_LOCAL_HOME.resolve("bing-wallpaper");
    /// Credit displayed on the background; changes are published on the FX thread.
    private static final ReadOnlyStringWrapper credit = new ReadOnlyStringWrapper("Bing 每日壁纸");
    /// Official picture-information endpoint; the server supplies attribution and the image path.
    public static final String ENDPOINT = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=1&mkt=zh-CN";
    /// Last successfully checked service date; stale caches remain eligible for a later retry.
    private static volatile String checkedDay = "";
    /// Ensures only one low-frequency daily-refresh clock exists per launcher process.
    private static final java.util.concurrent.atomic.AtomicBoolean clockStarted = new java.util.concurrent.atomic.AtomicBoolean();
    /// Starts a daemon clock only after Bing is actually requested, without polling network endpoints.
    private static void startClock() {
        if (!clockStarted.compareAndSet(false, true)) return;
        var clock = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> { Thread thread = new Thread(task, "Ochre daily wallpaper"); thread.setDaemon(true); return thread; });
        clock.scheduleWithFixedDelay(() -> Platform.runLater(() -> { if (org.jackhuang.hmcl.setting.SettingsManager.settings().backgroundTypeProperty().get() == org.jackhuang.hmcl.setting.BackgroundType.BING && !LocalDate.now().toString().equals(checkedDay)) Themes.refreshBackground(); }), 1, 1, java.util.concurrent.TimeUnit.HOURS);
    }
    /// Utility class.
    private BingWallpaper() {}
    /// Observable attribution for the loaded daily picture.
    public static ReadOnlyStringProperty creditProperty() { return credit.getReadOnlyProperty(); }
    /// Opens only the requested HTTPS resource with bounded connection/read timeouts.
    private static InputStream open(URI uri) throws IOException { var connection = uri.toURL().openConnection(); connection.setConnectTimeout(6000); connection.setReadTimeout(8000); connection.setRequestProperty("User-Agent", "OchreCL/0.2 wallpaper"); return connection.getInputStream(); }
    /// Loads a cached daily image, refreshing at most once a day and keeping the old image on network failure.
    public static synchronized Path refresh() throws IOException {
        Files.createDirectories(CACHE); Path image = CACHE.resolve("daily.jpg"), metadata = CACHE.resolve("metadata.properties");
        Properties saved = new Properties(); if (Files.isRegularFile(metadata)) try (var stream = Files.newInputStream(metadata)) { saved.load(stream); } catch (IOException ignored) { }
        if (!LocalDate.now().toString().equals(saved.getProperty("checked")) || !Files.isRegularFile(image)) {
            try {
                String json; try (var stream = open(URI.create(ENDPOINT))) { byte[] bytes = stream.readNBytes(262145); if (bytes.length > 262144) throw new IOException("必应图片信息过大"); json = new String(bytes, java.nio.charset.StandardCharsets.UTF_8); }
                var picture = JsonParser.parseString(json).getAsJsonObject().getAsJsonArray("images").get(0).getAsJsonObject();
                String path = picture.get("url").getAsString(); URI url = URI.create("https://www.bing.com").resolve(path);
                if (!"https".equals(url.getScheme()) || !"www.bing.com".equals(url.getHost())) throw new IOException("必应图片来源无效");
                Path pending = CACHE.resolve("daily.jpg.part");
                try (var stream = open(url); var output = Files.newOutputStream(pending)) { byte[] buffer = new byte[65536]; int count, total = 0; while ((count = stream.read(buffer)) >= 0) { total += count; if (total > 16 * 1024 * 1024) throw new IOException("必应图片超过大小限制"); output.write(buffer, 0, count); } }
                Image decoded = new Image(pending.toUri().toString(), 1920, 1080, true, true); if (decoded.isError() || decoded.getWidth() < 1) throw new IOException("无法读取必应图片");
                Files.move(pending, image, StandardCopyOption.REPLACE_EXISTING);
                saved.setProperty("checked", LocalDate.now().toString()); saved.setProperty("copyright", picture.get("copyright").getAsString()); saved.setProperty("url", url.toString());
                try (var output = Files.newOutputStream(metadata)) { saved.store(output, "Bing daily image attribution"); }
            } catch (Exception error) { if (!Files.isRegularFile(image)) throw new IOException("必应壁纸暂不可用，请选择本地图片或稍后重试", error); }
        }
        checkedDay = saved.getProperty("checked", "");
        String text = saved.getProperty("copyright", "Bing 每日壁纸"); Platform.runLater(() -> credit.set(text)); return image;
    }
    /// Returns a proportionally decoded image suitable for the cached glass background layer.
    public static Image load() throws IOException { startClock(); Image image = new Image(refresh().toUri().toString(), 1920, 1080, true, true); if (image.isError()) throw new IOException("必应壁纸缓存无法读取"); return image; }
}
