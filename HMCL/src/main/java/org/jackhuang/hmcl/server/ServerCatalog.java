package org.jackhuang.hmcl.server;

import com.google.gson.*;
import javafx.application.Platform;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.util.io.HttpRequest;
import org.jackhuang.hmcl.util.io.NetworkUtils;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Consumer;

/// Provider adapters for official core downloads and version-compatible Modrinth server add-ons.
@NotNullByDefault
public final class ServerCatalog {
    /// Identifies this independent local development distribution in provider requests.
    private static final String AGENT = "OchreCL/0.1 (HMCL-derived local build; https://github.com/HMCL-dev/HMCL)";
    /// Prevents construction of the catalog utility.
    private ServerCatalog() {}
    /// A concrete immutable download selected by the player, with an upstream content checksum.
    @NotNullByDefault
    public record Artifact(String label, String url, String algorithm, String hash, String core, String version, int javaMajor) {
        /// Displays the exact chosen build rather than a mutable latest alias.
        @Override public String toString() { return label; }
    }
    /// One server-compatible Modrinth project search result.
    @NotNullByDefault
    public record Project(String id, String title) {
        /// Displays the project title in the search list.
        @Override public String toString() { return title; }
    }
    /// Fetches provider metadata over the launcher's configured network/proxy layer.
    static JsonElement json(String url) throws IOException {
        return JsonParser.parseString(HttpRequest.GET(url).header("User-Agent", AGENT).getString());
    }
    /// Escapes a query parameter without using shell commands.
    static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    /// Lists only game releases the selected provider actually offers.
    public static @Unmodifiable List<String> versions(String core) throws IOException {
        ArrayList<String> result = new ArrayList<>();
        switch (core) {
            case "Paper" -> {
                var groups = json("https://fill.papermc.io/v3/projects/paper").getAsJsonObject().getAsJsonObject("versions");
                groups.entrySet().forEach(group -> group.getValue().getAsJsonArray().forEach(value -> result.add(value.getAsString())));
            }
            case "Forge" -> {
                var promos = json("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json").getAsJsonObject().getAsJsonObject("promos");
                for (String key : promos.keySet()) {
                    if (key.endsWith("-latest")) result.add(key.substring(0, key.length() - 7));
                }
                Collections.reverse(result);
            }
            case "Purpur" -> json("https://api.purpurmc.org/v2/purpur").getAsJsonObject().getAsJsonArray("versions").forEach(value -> result.add(value.getAsString()));
            default -> {
                for (var entry : json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json").getAsJsonObject().getAsJsonArray("versions")) {
                    var version = entry.getAsJsonObject();
                    if (version.get("type").getAsString().equals("release")) result.add(version.get("id").getAsString());
                }
            }
        }
        if (core.equals("Purpur")) Collections.reverse(result);
        return List.copyOf(result);
    }
    /// Gets the exact Mojang version metadata needed for server URL, hash and Java requirements.
    private static JsonObject gameVersion(String version) throws IOException {
        for (var entry : json("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json").getAsJsonObject().getAsJsonArray("versions")) {
            var value = entry.getAsJsonObject();
            if (value.get("id").getAsString().equals(version)) return json(value.get("url").getAsString()).getAsJsonObject();
        }
        throw new IOException("找不到此游戏版本");
    }
    /// Lists stable Paper builds or one immutable current build for other selected core types.
    public static @Unmodifiable List<Artifact> builds(String core, String version) throws IOException {
        var game = gameVersion(version);
        int java = game.has("javaVersion") ? game.getAsJsonObject("javaVersion").get("majorVersion").getAsInt() : 8;
        List<Artifact> values = new ArrayList<>();
        if (core.equals("Paper")) {
            for (var element : json("https://fill.papermc.io/v3/projects/paper/versions/" + encode(version) + "/builds").getAsJsonArray()) {
                var build = element.getAsJsonObject();
                if (!build.get("channel").getAsString().equals("STABLE")) continue;
                var file = build.getAsJsonObject("downloads").getAsJsonObject("server:default");
                values.add(new Artifact("Paper " + version + " · #" + build.get("id").getAsString(), file.get("url").getAsString(), "SHA-256",
                        file.getAsJsonObject("checksums").get("sha256").getAsString(), core, version, java));
            }
        } else if (core.equals("Purpur")) {
            var build = json("https://api.purpurmc.org/v2/purpur/" + encode(version) + "/latest").getAsJsonObject();
            String id = build.get("build").getAsString();
            values.add(new Artifact("Purpur " + version + " · #" + id,
                    "https://api.purpurmc.org/v2/purpur/" + encode(version) + "/" + id + "/download", "MD5", build.get("md5").getAsString(), core, version, java));
        } else if (core.equals("Forge")) {
            var promos = json("https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json").getAsJsonObject().getAsJsonObject("promos");
            for (String channel : List.of("recommended", "latest")) {
                if (!promos.has(version + "-" + channel)) continue;
                String forge = promos.get(version + "-" + channel).getAsString();
                String coordinate = version + "-" + forge;
                String url = "https://maven.minecraftforge.net/net/minecraftforge/forge/" + coordinate + "/forge-" + coordinate + "-installer.jar";
                String hash = HttpRequest.GET(url + ".sha1").header("User-Agent", AGENT).getString().strip().split("\\s+")[0];
                values.add(new Artifact("Forge " + version + " · " + forge + " · " + (channel.equals("recommended") ? "推荐" : "最新"), url, "SHA-1", hash, core, version, java));
            }
        } else if (core.equals("Fabric")) {
            var loaders = json("https://meta.fabricmc.net/v2/versions/loader/" + encode(version)).getAsJsonArray();
            String loader = "";
            for (var element : loaders) {
                var item = element.getAsJsonObject().getAsJsonObject("loader");
                if (item.get("stable").getAsBoolean()) { loader = item.get("version").getAsString(); break; }
            }
            if (loader.isEmpty()) throw new IOException("此版本没有稳定 Fabric 服务端");
            String installer = json("https://meta.fabricmc.net/v2/versions/installer").getAsJsonArray().get(0).getAsJsonObject().get("version").getAsString();
            String url = "https://meta.fabricmc.net/v2/versions/loader/" + encode(version) + "/" + encode(loader) + "/" + encode(installer) + "/server/jar";
            values.add(new Artifact("Fabric " + version + " · " + loader, url, "", "", core, version, java));
        } else {
            var downloads = game.getAsJsonObject("downloads");
            if (!downloads.has("server")) throw new IOException("此版本未提供独立服务端");
            var file = downloads.getAsJsonObject("server");
            values.add(new Artifact("原版 " + version, file.get("url").getAsString(), "SHA-1", file.get("sha1").getAsString(), "原版", version, java));
        }
        return List.copyOf(values);
    }
    /// Downloads to a staging file, validates metadata hashes, then atomically publishes a complete file.
    public static void download(Artifact artifact, Path destination, Consumer<String> progress) throws Exception {
        if (!artifact.url().startsWith("https://")) throw new IOException("仅支持 HTTPS 下载");
        var connection = NetworkUtils.createHttpConnection(artifact.url());
        connection.setRequestProperty("User-Agent", AGENT);
        connection.setConnectTimeout(20000);
        connection.setReadTimeout(30000);
        connection = NetworkUtils.resolveConnection(connection);
        if (connection.getResponseCode() != 200) throw new IOException("下载响应 " + connection.getResponseCode());
        Path pending = destination.resolveSibling(destination.getFileName() + ".part");
        var digest = MessageDigest.getInstance(artifact.algorithm().isEmpty() ? "SHA-256" : artifact.algorithm());
        long size = connection.getContentLengthLong(), count = 0, last = 0;
        try {
            try (var input = connection.getInputStream(); var output = Files.newOutputStream(pending)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("下载已取消");
                    count += n;
                    if (count > 2L * 1024 * 1024 * 1024) throw new IOException("下载文件超过 2 GiB 限制");
                    output.write(buffer, 0, n);
                    digest.update(buffer, 0, n);
                    long now = System.nanoTime();
                    if (now - last > 250000000L) {
                        progress.accept(size > 0 ? "下载中 " + count * 100 / size + "%" : "已下载 " + count / 1048576 + " MiB"); last = now;
                    }
                }
            }
            if (size >= 0 && count != size) throw new IOException("下载不完整");
            if (!artifact.hash().isEmpty() && !HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(artifact.hash())) throw new IOException("文件校验失败");
            Files.move(pending, destination, StandardCopyOption.ATOMIC_MOVE);
        } finally { connection.disconnect(); Files.deleteIfExists(pending); }
    }
    /// Deploys into a new unique directory, without accepting EULA or launching downloaded code.
    public static ServerInstance deploy(Artifact artifact, String name, Consumer<String> progress) throws Exception {
        return deploy(artifact, name, new Properties(), progress);
    }
    /// Applies validated user deployment settings before publishing a newly installed server.
    public static ServerInstance deploy(Artifact artifact, String name, Properties options, Consumer<String> progress) throws Exception {
        return deployInternal(artifact, name, options, progress, true);
    }
    /// Prepares a replacement core without publishing a temporary installation in the server selector.
    static ServerInstance deployTemporary(Artifact artifact, String name, Consumer<String> progress) throws Exception {
        return deployInternal(artifact, name, new Properties(), progress, false);
    }
    /// Owns the staging transaction for both new and replacement installations.
    private static ServerInstance deployInternal(Artifact artifact, String name, Properties options, Consumer<String> progress, boolean register) throws Exception {
        ServerConfiguration.validate(options);
        Files.createDirectories(ServerManager.ROOT);
        String installationId = UUID.randomUUID().toString();
        Path directory = Files.createDirectory(ServerManager.ROOT.resolve(".install-" + installationId));
        try {
            progress.accept("阶段:0");
            download(artifact, directory.resolve(artifact.core().equals("Forge") ? "installer.jar" : "server.jar"), progress);
            progress.accept("阶段:1"); progress.accept("正在校验服务器 JAR 与启动入口…");
            try (var archive = new java.util.jar.JarFile(directory.resolve(artifact.core().equals("Forge") ? "installer.jar" : "server.jar").toFile())) {
                if (archive.getManifest() == null || archive.getManifest().getMainAttributes().getValue("Main-Class") == null) throw new IOException("下载内容不是可独立启动的服务器 JAR；插件、模组需安装到已有服务器");
            }
            progress.accept("阶段:2"); progress.accept("正在准备服务器运行环境…");
            Properties settings = new Properties();
            settings.setProperty("name", name.isBlank() ? artifact.label() : name.strip());
            settings.setProperty("core", artifact.core());
            settings.setProperty("version", artifact.version());
            settings.setProperty("javaMajor", Integer.toString(artifact.javaMajor()));
            settings.setProperty("memory", "2048");
            settings.setProperty("port", "25565");
            try (var output = Files.newOutputStream(directory.resolve("ochre-server.properties"))) { settings.store(output, "OchreCL local server"); }
            Files.writeString(directory.resolve("eula.txt"), "# Read https://www.minecraft.net/eula\neula=false\n");
            ServerInstance server = new ServerInstance(directory);
            if (artifact.core().equals("Forge")) {
                progress.accept("正在运行 Forge 官方安装器并部署服务端…");
                Path javaExecutable = ServerManager.javaBinary(server);
                Process installer = new ProcessBuilder(javaExecutable.toString(), "-jar", "installer.jar", "--installServer")
                        .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve("forge-install.log").toFile()).start();
                try {
                    if (!installer.waitFor(10, java.util.concurrent.TimeUnit.MINUTES)) throw new IOException("Forge 安装超时，请检查网络");
                    if (installer.exitValue() != 0) throw new IOException("Forge 安装器失败（代码 " + installer.exitValue() + "）");
                } finally { if (installer.isAlive()) { installer.descendants().forEach(ProcessHandle::destroy); installer.destroyForcibly(); } }
                configureForgeLaunch(server);
                server.save();
            }
            progress.accept("阶段:3"); progress.accept("正在写入实例设置与 EULA 选择…");
            ServerConfiguration.apply(server, options);
            Path completed = ServerManager.ROOT.resolve(installationId);
            Files.move(directory, completed, StandardCopyOption.ATOMIC_MOVE);
            ServerInstance installed = new ServerInstance(completed);
            if (register) Platform.runLater(() -> ServerManager.SERVERS.add(installed));
            return installed;
        } catch (Exception e) {
            Path installLog = directory.resolve("forge-install.log");
            if (Files.isRegularFile(installLog)) {
                Path savedLog = ServerManager.ROOT.resolve("forge-install-failed-" + directory.getFileName() + ".log");
                Files.copy(installLog, savedLog);
                e.addSuppressed(new IOException("Forge 安装日志：" + savedLog));
            }
            try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
            throw e;
        }
    }
    /// Detects installer-produced launch arguments on modern Forge or a legacy executable jar.
    static void configureForgeLaunch(ServerInstance server) throws IOException {
        Path library = server.directory.resolve("libraries/net/minecraftforge/forge");
        if (Files.isDirectory(library)) {
            try (var files = Files.walk(library)) {
                var arguments = files.filter(path -> path.getFileName().toString().equals("unix_args.txt")).findFirst();
                if (arguments.isPresent()) {
                    server.settings.setProperty("args", server.directory.relativize(arguments.get()).toString());
                    return;
                }
            }
        }
        try (var files = Files.list(server.directory)) {
            var jar = files.filter(path -> path.getFileName().toString().startsWith("forge-")
                    && path.toString().endsWith(".jar") && !path.toString().endsWith("-installer.jar")).findFirst();
            if (jar.isPresent()) { server.settings.setProperty("jar", jar.get().getFileName().toString()); return; }
        }
        throw new IOException("Forge 安装后没有生成服务端启动文件");
    }

    /// Searches only server-side compatible projects for the selected loader and game version.
    public static @Unmodifiable List<Project> search(String query, ServerInstance target) throws IOException {
        String core = target.settings.getProperty("core", "");
        if (core.equals("原版")) throw new IOException("原版核心不加载插件或模组，请选择 Paper、Purpur 或 Fabric");
        String loaders = core.equals("Forge") ? "[\"categories:forge\"]" : core.equals("Fabric") ? "[\"categories:fabric\"]" : "[\"categories:paper\",\"categories:spigot\",\"categories:bukkit\"]";
        String facets = "[[\"project_type:mod\",\"project_type:plugin\"],[\"server_side:required\",\"server_side:optional\"],[\"versions:" + target.settings.getProperty("version") + "\"]," + loaders + "]";
        var data = json("https://api.modrinth.com/v2/search?limit=30&query=" + encode(query) + "&facets=" + encode(facets)).getAsJsonObject();
        List<Project> projects = new ArrayList<>();
        for (var element : data.getAsJsonArray("hits")) {
            var hit = element.getAsJsonObject();
            projects.add(new Project(hit.get("project_id").getAsString(), hit.get("title").getAsString()));
        }
        return List.copyOf(projects);
    }
    /// Resolves a release compatible with the target game version and loader family.
    private static JsonObject addonVersion(String project, String version, String loader) throws IOException {
        String loaders = loader.equals("paper") ? "[\"paper\",\"spigot\",\"bukkit\"]" : "[\"" + loader + "\"]";
        var versions = json("https://api.modrinth.com/v2/project/" + encode(project) + "/version?loaders=" + encode(loaders)
                + "&game_versions=" + encode("[\"" + version + "\"]")).getAsJsonArray();
        for (var value : versions) {
            var candidate = value.getAsJsonObject();
            if (candidate.get("version_type").getAsString().equals("release")) return candidate;
        }
        throw new IOException("没有与目标服务器兼容的正式版本：" + project);
    }
    /// An add-on file staged before any existing installation is changed.
    @NotNullByDefault
    private record AddonFile(String filename, Artifact artifact) {}
    /// Resolves required dependencies recursively, rejecting incompatible pinned dependencies and cycles.
    private static void planAddon(JsonObject version, String game, String loader, Set<String> visited, List<AddonFile> files) throws IOException {
        String id = version.get("id").getAsString();
        if (!visited.add(id)) return;
        if (visited.size() > 64) throw new IOException("项目依赖超过 64 项，请使用整合包安装方式");
        boolean supportsGame = false, supportsLoader = false;
        for (var value : version.getAsJsonArray("game_versions")) if (value.getAsString().equals(game)) supportsGame = true;
        for (var value : version.getAsJsonArray("loaders")) {
            String name = value.getAsString();
            if (name.equals(loader) || loader.equals("paper") && (name.equals("spigot") || name.equals("bukkit"))) supportsLoader = true;
        }
        if (!supportsGame || !supportsLoader) throw new IOException("必需依赖与目标游戏版本或加载器不兼容");
        for (var value : version.getAsJsonArray("dependencies")) {
            var dependency = value.getAsJsonObject();
            if (!dependency.get("dependency_type").getAsString().equals("required")) continue;
            JsonObject required;
            if (dependency.has("version_id") && !dependency.get("version_id").isJsonNull()) required = json("https://api.modrinth.com/v2/version/" + encode(dependency.get("version_id").getAsString())).getAsJsonObject();
            else if (dependency.has("project_id") && !dependency.get("project_id").isJsonNull()) required = addonVersion(dependency.get("project_id").getAsString(), game, loader);
            else throw new IOException("必需依赖没有可自动解析的项目或版本 ID");
            planAddon(required, game, loader, visited, files);
        }
        for (var value : version.getAsJsonArray("files")) {
            var file = value.getAsJsonObject();
            if (!file.get("primary").getAsBoolean()) continue;
            String filename = file.get("filename").getAsString();
            if (!filename.endsWith(".jar") || filename.contains("/") || filename.contains("\\") || filename.contains(":")) throw new IOException("不支持的附件文件名");
            files.add(new AddonFile(filename, new Artifact(version.get("name").getAsString(), file.get("url").getAsString(), "SHA-512",
                    file.getAsJsonObject("hashes").get("sha512").getAsString(), "", "", 0)));
            return;
        }
        throw new IOException("项目未提供主要 jar 文件");
    }
    /// Installs the selected project and required dependencies transactionally into a stopped server.
    public static void installAddon(Project project, ServerInstance target, Consumer<String> progress) throws Exception {
        installAddon(project, target, null, progress);
    }
    /// Installs the exact player-selected release, retaining dependency and target compatibility checks.
    public static void installAddonVersion(Project project, String versionId, ServerInstance target, Consumer<String> progress) throws Exception {
        installAddon(project, target, versionId, progress);
    }
    /// Shares transactional installation between recommended and explicitly selected releases.
    private static void installAddon(Project project, ServerInstance target, @org.jetbrains.annotations.Nullable String versionId, Consumer<String> progress) throws Exception {
        synchronized (ServerManager.class) {
            if (target.busy || target.maintenance || target.session.isRunning()) throw new IOException("请先停止目标服务器，并等待维护完成");
            target.busy = true;
        }
        List<Path> published = new ArrayList<>();
        @org.jetbrains.annotations.Nullable Path staging = null;
        try {
            staging = Files.createTempDirectory(target.directory, ".ochre-addons-");
            String core = target.settings.getProperty("core");
            if (!Set.of("Paper", "Purpur", "Forge", "Fabric").contains(core)) throw new IOException("此核心尚未声明插件或模组兼容性");
            String loader = core.equals("Forge") ? "forge" : core.equals("Fabric") ? "fabric" : "paper";
            String game = target.settings.getProperty("version");
            List<AddonFile> plan = new ArrayList<>();
            JsonObject release = versionId == null ? addonVersion(project.id(), game, loader)
                    : json("https://api.modrinth.com/v2/version/" + encode(versionId)).getAsJsonObject();
            if (!release.get("project_id").getAsString().equals(project.id())) throw new IOException("版本不属于所选项目");
            planAddon(release, game, loader, new HashSet<>(), plan);
            Path folder = target.directory.resolve(loader.equals("paper") ? "plugins" : "mods");
            Files.createDirectories(folder);
            Set<String> names = new HashSet<>();
            for (var file : plan) {
                if (!names.add(file.filename())) throw new IOException("依赖存在文件名冲突：" + file.filename());
                Path existing = folder.resolve(file.filename());
                if (Files.exists(existing)) {
                    var digest = MessageDigest.getInstance("SHA-512");
                    try (var input = Files.newInputStream(existing)) {
                        byte[] buffer = new byte[65536]; int n;
                        while ((n = input.read(buffer)) != -1) digest.update(buffer, 0, n);
                    }
                    if (!HexFormat.of().formatHex(digest.digest()).equalsIgnoreCase(file.artifact().hash())) throw new IOException("已有同名但内容不同的文件：" + file.filename());
                } else download(file.artifact(), staging.resolve(file.filename()), progress);
            }
            for (var file : plan) {
                Path source = staging.resolve(file.filename());
                if (!Files.exists(source)) continue;
                Path destination = folder.resolve(file.filename());
                Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE); published.add(destination);
            }
        } catch (Exception e) {
            for (Path path : published) Files.deleteIfExists(path);
            throw e;
        } finally {
            try { if (staging != null) try (var paths = Files.walk(staging)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } }
            finally { target.busy = false; }
        }
    }
}
