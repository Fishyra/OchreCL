package org.jackhuang.hmcl.ui.decorator;

import javafx.scene.image.Image;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.zip.ZipFile;

/// Reads a custom cover or the selected version's title panorama without changing game files.
@NotNullByDefault
final class InstanceCoverLoader {
    /// Utility class.
    private InstanceCoverLoader() {}

    /// Modern clients store real panoramas in asset objects; jar textures can be 1×1 placeholders.
    static @Nullable Image load(@Nullable Path custom, org.jackhuang.hmcl.game.HMCLGameInstance instance) {
        if (custom != null && Files.isRegularFile(custom)) {
            @Nullable Image customImage = load(custom, instance.getInstanceJarFile());
            if (customImage != null) return customImage;
        }
        try {
            var index = instance.getResolvedManifest().getAssetIndex();
            if (index != null) {
                var asset = instance.getAssetObject(index.getId(), "minecraft/textures/gui/title/background/panorama_0.png");
                if (asset.isPresent() && Files.isRegularFile(asset.get())) {
                    try (var input = Files.newInputStream(asset.get())) {
                        Image image = new Image(input);
                        if (!image.isError() && image.getWidth() >= 32 && image.getHeight() >= 32) return image;
                    }
                }
            }
        } catch (IOException | RuntimeException ignored) { }
        @Nullable Image bundled = load(null, instance.getInstanceJarFile());
        return bundled != null ? bundled : fetchMissing(instance);
    }

    /// Downloads only the missing title texture into a launcher-owned cache, never altering game files.
    private static synchronized @Nullable Image fetchMissing(org.jackhuang.hmcl.game.HMCLGameInstance instance) {
        try {
            var info = instance.getResolvedManifest().getAssetIndex(); if (info == null) return null;
            org.jackhuang.hmcl.game.AssetIndex index;
            try { index = instance.getAssetIndex(info.getId()); }
            catch (IOException exception) {
                String key = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(info.getUrl().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                Path metadata = cache("index-" + key + ".json");
                fetch(info.getUrl(), info.getSha1() == null ? "" : info.getSha1(), metadata);
                index = org.jackhuang.hmcl.util.gson.JsonUtils.fromNonNullJson(Files.readString(metadata), org.jackhuang.hmcl.game.AssetIndex.class);
            }
            @Nullable var object = index.getObjects().get("minecraft/textures/gui/title/background/panorama_0.png");
            if (object == null || !object.hash().matches("[0-9a-fA-F]{40}")) return null;
            Path image = cache(object.hash() + ".png");
            fetch("https://resources.download.minecraft.net/" + object.getLocation(), object.hash(), image);
            try (var input = Files.newInputStream(image)) { Image result = new Image(input, 1024, 1024, true, true); return !result.isError() && result.getWidth() >= 32 && result.getHeight() >= 32 ? result : null; }
        } catch (Exception exception) { return null; }
    }
    /// Creates the independent cache outside installed game directories.
    private static Path cache(String filename) throws IOException { Path root = org.jackhuang.hmcl.Metadata.HMCL_LOCAL_HOME.resolve("ochre-covers"); Files.createDirectories(root); return root.resolve(filename); }
    /// Fetches bounded HTTPS metadata/images with checksum verification and atomic cache publication.
    private static void fetch(String url, String expected, Path destination) throws Exception {
        if (Files.isRegularFile(destination)) {
            if (expected.isBlank() || org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-1", destination).equalsIgnoreCase(expected)) return;
        }
        if (!url.startsWith("https://")) throw new IOException("封面资源地址不是 HTTPS");
        var connection = org.jackhuang.hmcl.util.io.NetworkUtils.createHttpConnection(url); connection.setConnectTimeout(10000); connection.setReadTimeout(10000);
        connection = org.jackhuang.hmcl.util.io.NetworkUtils.resolveConnection(connection);
        Path temporary = Files.createTempFile(destination.getParent(), ".cover-", ".part");
        try {
            if (!connection.getURL().getProtocol().equals("https") || connection.getResponseCode() != 200) throw new IOException("无法下载版本背景");
            try (var input = connection.getInputStream(); var output = Files.newOutputStream(temporary)) {
                byte[] bytes = new byte[32768]; int count; long total = 0;
                while ((count = input.read(bytes)) != -1) { total += count; if (total > 16 * 1024 * 1024 || Thread.currentThread().isInterrupted()) throw new IOException("封面下载取消或超出大小限制"); output.write(bytes, 0, count); }
            }
            if (!expected.isBlank() && !org.jackhuang.hmcl.util.DigestUtils.digestToString("SHA-1", temporary).equalsIgnoreCase(expected)) throw new IOException("封面资源校验失败");
            Files.move(temporary, destination, java.nio.file.StandardCopyOption.ATOMIC_MOVE, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } finally { connection.disconnect(); Files.deleteIfExists(temporary); }
    }

    /// Decodes off the UI thread; unavailable assets leave an editable placeholder.
    static @Nullable Image load(@Nullable Path custom, Path jar) {
        if (custom != null && Files.isRegularFile(custom)) {
            try (var input = Files.newInputStream(custom)) {
                Image image = new Image(input, 1024, 1024, true, true);
                if (!image.isError()) return image;
            } catch (IOException ignored) { }
        }
        if (!Files.isRegularFile(jar)) return null;
        try (var zip = new ZipFile(jar.toFile())) {
            for (String name : java.util.List.of(
                    "assets/minecraft/textures/gui/title/background/panorama_0.png",
                    "title/bg/panorama0.png")) {
                @Nullable var entry = zip.getEntry(name);
                if (entry == null) continue;
                try (var input = zip.getInputStream(entry)) {
                    Image image = new Image(input);
                    if (!image.isError() && image.getWidth() >= 32 && image.getHeight() >= 32) return image;
                }
            }
        } catch (IOException ignored) { }
        return null;
    }
}
