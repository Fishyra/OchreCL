package org.jackhuang.hmcl.ui.construct;

import com.google.gson.JsonParser;
import org.jackhuang.hmcl.script.OchreScript;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;

import javax.net.ssl.HttpsURLConnection;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

/// Fetches versioned Øchre modules from the official catalogue without running them.
@NotNullByDefault
final class OchrePluginCatalog {
    /// Official site root; the catalogue cannot override the origin of a module download.
    private static final URI SITE = URI.create("https://fishyra.github.io/ochrecl/script-plugins/");
    /// Upper bound for both catalogue responses and individual module payloads.
    private static final int LIMIT = 65_536;

    /// One downloadable module and its display metadata.
    @NotNullByDefault
    record Plugin(String file, String name, String description, String version, String requires, String usage, String sha256) {
        /// Builds a ready to paste import and example.
        String example() { return "import \"" + file + "\";\n\n" + usage; }
    }

    /// Utility class.
    private OchrePluginCatalog() { }

    /// Parses the live catalogue with bounds on both entry count and display text.
    static @Unmodifiable List<Plugin> load() throws IOException {
        return parse(fetch("catalog.json"));
    }

    /// Validates catalogue bytes before any entry can be used for a download.
    static @Unmodifiable List<Plugin> parse(byte[] bytes) throws IOException {
        if (bytes.length > LIMIT) throw new IOException("目录超过 64 KiB");
        var array = JsonParser.parseString(utf8(bytes)).getAsJsonArray();
        if (array.size() > 100) throw new IOException("插件目录条目过多");
        var files = new HashSet<String>();
        var result = new ArrayList<Plugin>();
        for (var element : array) {
            var item = element.getAsJsonObject();
            var plugin = new Plugin(item.get("file").getAsString(), item.get("name").getAsString(),
                    item.get("description").getAsString(), item.get("version").getAsString(),
                    item.get("requires").getAsString(), item.get("usage").getAsString(), item.get("sha256").getAsString());
            if (!plugin.file().matches("[a-z0-9][a-z0-9_-]{0,70}\\.ochre") || !files.add(plugin.file())
                    || !plugin.sha256().matches("[0-9a-f]{64}") || plugin.name().isBlank() || plugin.name().length() > 80
                    || plugin.description().length() > 480 || plugin.version().length() > 24
                    || plugin.requires().length() > 40 || plugin.usage().length() > 1600)
                throw new IOException("插件目录包含无效条目");
            result.add(plugin);
        }
        return List.copyOf(result);
    }

    /// Downloads, validates and installs a declaration-only module under its stable filename.
    static Path install(Path library, Plugin plugin) throws Exception {
        return installVerified(library, plugin, fetch(plugin.file()));
    }

    /// Checks the downloaded bytes and refuses to replace an existing script.
    static Path installVerified(Path library, Plugin plugin, byte[] data) throws Exception {
        if (!plugin.file().matches("[a-z0-9][a-z0-9_-]{0,70}\\.ochre") || data.length > LIMIT)
            throw new IOException("无效插件文件");
        if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)).equals(plugin.sha256()))
            throw new IOException("插件校验失败，请刷新目录后重试");
        String source = utf8(data);
        OchreScript.compile("import \"" + plugin.file() + "\";", name -> {
            if (!name.equals(plugin.file())) throw new IOException("此插件不是独立模块");
            return source;
        });
        Files.createDirectories(library);
        Path file = library.resolve(plugin.file());
        if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("脚本库已有同名文件；请先备份或重命名它");
        Path staged = Files.createTempFile(library, ".plugin-", ".tmp");
        try {
            Files.write(staged, data);
            return Files.move(staged, file);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    /// Reads a bounded response from the exact HTTPS origin without following redirects.
    private static byte[] fetch(String name) throws IOException {
        if (!name.equals("catalog.json") && !name.matches("[a-z0-9][a-z0-9_-]{0,70}\\.ochre")) throw new IOException("无效插件文件名");
        var connection = (HttpsURLConnection) SITE.resolve(name).toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(6000);
        connection.setReadTimeout(6000);
        try {
            int status = connection.getResponseCode();
            if (status != 200) throw new IOException("官网返回 HTTP " + status);
            if (connection.getContentLengthLong() > LIMIT) throw new IOException("文件超过 64 KiB");
            try (var input = connection.getInputStream()) {
                byte[] data = input.readNBytes(LIMIT + 1);
                if (data.length > LIMIT) throw new IOException("文件超过 64 KiB");
                return data;
            }
        } finally {
            connection.disconnect();
        }
    }

    /// Rejects malformed source instead of quietly replacing characters in downloaded code.
    private static String utf8(byte[] bytes) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
    }
}
