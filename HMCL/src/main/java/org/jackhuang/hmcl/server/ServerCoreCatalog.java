package org.jackhuang.hmcl.server;

import com.google.gson.*;
import org.jetbrains.annotations.*;
import java.io.IOException;
import java.util.*;

/// Dedicated Minecraft server-core providers; plugin/mod search is deliberately kept separate.
@NotNullByDefault
public final class ServerCoreCatalog {
    /// Official MSL v4 mirror API.
    private static final String MSL = "https://api.mslmc.cn/v4";
    /// Small bounded metadata cache avoids repeatedly consuming provider quotas.
    private static final Map<String, Cached> CACHE = new LinkedHashMap<>();
    /// Cached public response and monotonic expiry time.
    @NotNullByDefault
    private record Cached(JsonElement value, long expires) {}
    /// A verified core identity from a provider's core catalog, never a search hit for a plugin.
    @NotNullByDefault
    public record Core(String id, String title, String description, String icon, String source, String repository) {
        /// Public project link selected from the fixed provider identity.
        public String website() { return source.equals("MSL") ? "https://dl.mslmc.cn/" : "https://github.com/" + repository; }
    }
    /// Exact downloadable core build and its visible release metadata.
    @NotNullByDefault
    public record Release(ServerCatalog.Artifact artifact, String published, String filename) {}
    /// Official repositories whose executable server artifacts have been checked.
    private static final @Unmodifiable List<Core> OFFICIAL = List.of(
        new Core("arclight", "Arclight", "支持插件与模组的混合服务器核心，可分别选择 Fabric、Forge 或 NeoForge 构建。", "", "GitHub", "IzzelAliz/Arclight"),
        new Core("leaf", "Leaf", "兼容 Paper 插件的服务器核心，侧重性能、原版机制与稳定性。", "https://avatars.githubusercontent.com/Winds-Studio", "GitHub", "Winds-Studio/Leaf"),
        new Core("leaves", "Leaves", "基于 Paper、注重原版机制的服务器核心。", "https://avatars.githubusercontent.com/LeavesMC", "GitHub", "LeavesMC/Leaves"));
    /// Static catalog adapter.
    private ServerCoreCatalog() {}
    /// Searches only provider-declared Java plugin/hybrid cores, excluding clients, plugins and native/proxy servers.
    public static @Unmodifiable List<Core> search(String term, String source) throws IOException {
        if (!source.equals("MSL")) return OFFICIAL.stream().filter(core -> matches(core.id() + " " + core.title(), term)).toList();
        var data = msl("/mirrors").getAsJsonObject(); List<Core> result = new ArrayList<>();
        for (String group : List.of("pluginsCore", "pluginsAndModsCore_Forge", "pluginsAndModsCore_Fabric")) {
            if (!data.has(group)) continue;
            for (var entry : data.getAsJsonArray(group)) {
                String id = entry.getAsString();
                if (!matches(id + (id.startsWith("arclight") ? " 弧光" : ""), term)) continue;
                String kind = group.equals("pluginsCore") ? "插件服务器核心" : "插件与模组混合核心";
                result.add(new Core(id, title(id), kind + " · MSL 服务端镜像", icon(id), "MSL", ""));
            }
        }
        return List.copyOf(result);
    }
    /// Case-insensitive, whitespace-tolerant name matching without fuzzy unrelated plugin results.
    static boolean matches(String name, String term) {
        String normalized = name.toLowerCase(Locale.ROOT).replace('-', ' ').replace('_', ' ');
        return Arrays.stream(term.toLowerCase(Locale.ROOT).trim().split("\\s+")).allMatch(normalized::contains);
    }
    /// Gives mirror identities readable loader-specific titles.
    private static String title(String id) { return Arrays.stream(id.replace('_', ' ').split("-")).map(word -> word.equals("neoforge") ? "NeoForge" : word.substring(0, 1).toUpperCase(Locale.ROOT) + word.substring(1)).collect(java.util.stream.Collectors.joining(" · ")); }
    /// Uses verified project-owner artwork where available; other cores use the launcher's server illustration.
    private static String icon(String id) { return OFFICIAL.stream().filter(core -> id.equals(core.id()) || id.startsWith(core.id() + "-")).map(Core::icon).findFirst().orElse(""); }
    /// Reads available versions without requesting quota-limited download URLs until one is selected.
    public static @Unmodifiable List<String> versions(Core core) throws IOException {
        if (core.source().equals("MSL")) {
            List<String> versions = new ArrayList<>();
            msl("/mirrors/" + ServerCatalog.encode(core.id())).getAsJsonObject().getAsJsonArray("versions").forEach(value -> versions.add(value.getAsString()));
            return List.copyOf(versions);
        }
        return releases(core).stream().map(release -> release.artifact().version()).distinct().toList();
    }
    /// Resolves one selected mirror build only, respecting the provider's hourly download-link quota.
    public static Release mirrorRelease(Core core, String version) throws IOException {
        var data = msl("/download/server/" + ServerCatalog.encode(core.id()) + "/" + ServerCatalog.encode(version)).getAsJsonObject();
        String url = text(data, "url"), hash = text(data, "sha256");
        if (!url.startsWith("https://")) throw new IOException("供应商未提供 HTTPS 服务端下载地址");
        if (!hash.matches("[a-fA-F0-9]{64}")) hash = "";
        return new Release(new ServerCatalog.Artifact(core.title() + " " + version, url, hash.isEmpty() ? "" : "SHA-256", hash, core.title(), version, javaMajor(version)), "", core.id() + "-" + version + ".jar");
    }
    /// Reads exact official GitHub release assets with provider-supplied hashes, omitting source/dev jars.
    public static @Unmodifiable List<Release> releases(Core core) throws IOException {
        return parseReleases(core, cached("https://api.github.com/repos/" + core.repository() + "/releases?per_page=100").getAsJsonArray());
    }
    /// Parses immutable published assets; Minecraft versions come from the artifact filename rather than a release nickname.
    static @Unmodifiable List<Release> parseReleases(Core core, JsonArray releases) {
        List<Release> result = new ArrayList<>();
        var pattern = java.util.regex.Pattern.compile("(?:^|[-_])(1\\.\\d+(?:\\.\\d+)?|2[6-9]\\.\\d+(?:\\.\\d+)?)(?=[-_\\.]|$)");
        for (var entry : releases) {
            var release = entry.getAsJsonObject(); if (release.has("draft") && release.get("draft").getAsBoolean()) continue;
            for (var value : release.getAsJsonArray("assets")) {
                var file = value.getAsJsonObject(); String filename = text(file, "name");
                if (!filename.endsWith(".jar") || filename.contains("sources") || filename.contains("javadoc") || filename.contains("-dev")) continue;
                var match = pattern.matcher(filename); if (!match.find()) continue;
                String version = match.group(1), hash = text(file, "digest");
                String flavor = filename.contains("neoforge") ? "NeoForge" : filename.contains("forge") ? "Forge" : filename.contains("fabric") ? "Fabric" : "";
                String type = core.title() + (flavor.isEmpty() ? "" : " · " + flavor);
                if (!hash.matches("sha256:[a-fA-F0-9]{64}")) hash = "";
                result.add(new Release(new ServerCatalog.Artifact(filename.replaceFirst("\\.jar$", ""), text(file, "browser_download_url"), hash.isEmpty() ? "" : "SHA-256", hash.isEmpty() ? "" : hash.substring(7), type, version, javaMajor(version)), text(release, "published_at"), filename));
            }
        }
        return List.copyOf(result);
    }
    /// Matches current Mojang Java generation requirements; deployment still allows explicit user selection.
    static int javaMajor(String version) {
        if (version.startsWith("2")) return 25;
        try { var parts = version.split("\\."); int minor = Integer.parseInt(parts[1]), patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0; return minor > 20 || minor == 20 && patch >= 5 ? 21 : minor >= 18 ? 17 : minor == 17 ? 16 : 8; }
        catch (RuntimeException ignored) { return 21; }
    }
    /// Validates the mirror response envelope and gives an actionable quota error.
    private static JsonElement msl(String path) throws IOException {
        var result = cached(MSL + path).getAsJsonObject();
        if (!result.has("code") || result.get("code").getAsInt() != 200 || !result.has("data")) throw new IOException("MSL 暂时无法提供此资源，请稍后重试或切换官方源");
        return result.get("data");
    }
    /// Reuses public metadata for ten minutes and caps cached requests, without crawling the catalog.
    private static JsonElement cached(String url) throws IOException {
        synchronized (CACHE) { @Nullable Cached hit = CACHE.get(url); if (hit != null && hit.expires() > System.nanoTime()) return hit.value(); }
        JsonElement value = ServerCatalog.json(url);
        synchronized (CACHE) { if (CACHE.size() >= 32) CACHE.remove(CACHE.keySet().iterator().next()); CACHE.put(url, new Cached(value, System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(10))); }
        return value;
    }
    /// Reads optional provider text without accepting JSON null as a visible value.
    private static String text(JsonObject value, String key) { return value.has(key) && !value.get(key).isJsonNull() ? value.get(key).getAsString() : ""; }
}
