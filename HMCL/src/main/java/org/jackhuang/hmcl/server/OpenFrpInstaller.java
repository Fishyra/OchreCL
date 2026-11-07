package org.jackhuang.hmcl.server;

import org.jetbrains.annotations.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/// Installs a pinned official OpenFrp release with reviewed archive digests, independently of upstream frpc.
@NotNullByDefault
public final class OpenFrpInstaller {
    /// Official release tested for the OpenFrp simplified startup protocol.
    private static final String VERSION = "OF_0.68.0_37f78258_260326";
    /// SHA-256 values measured from the official HTTPS distribution on 2026-11-06.
    private static final @Unmodifiable Map<String, String> HASHES = Map.of(
        "darwin_arm64", "f64f98d05d5da0f81ba56fbfa6e4e80d64a6cc3ef387242bada4a49210d1578e",
        "darwin_amd64", "27a0fb49dbc852196cb8f8b855fa8c482ea50fea196c150ec39d576042674dc6",
        "windows_arm64", "012d0bca3f65e55d8b0bffe979cda675b480b31fbbd7b207446d7be8bb807443",
        "windows_amd64", "48705664b9d9246098c93a6b51a8d27409fd5d9f6b475808032c4a946e4be08d",
        "linux_arm64", "34f2d6a40b4fcfad4a6c58a640f3ca47d40f2b5974a62c0d77b8a9fef79f215a",
        "linux_amd64", "1d227512e0fcd27f0da5747fcf85ecd425545d55961111459ad8c8b976014e63");
    /// Static installer utility.
    private OpenFrpInstaller() {}
    /// Downloads one matching desktop archive after the caller has obtained user consent.
    public static void install(Consumer<String> progress) throws Exception {
        String platform = ServerToolInstaller.platform(); if (platform.equals("osx")) platform = "darwin";
        String stem = "frpc_" + platform + "_" + ServerToolInstaller.architecture();
        String name = stem + (platform.equals("windows") ? ".zip" : ".tar.gz");
        String wanted = stem + (platform.equals("windows") ? ".exe" : "");
        @Nullable String hash = HASHES.get(platform + "_" + ServerToolInstaller.architecture());
        if (hash == null) throw new java.io.IOException("此平台暂无 OpenFrp 自动安装支持");
        Path destination = ServerToolInstaller.path(ServerToolInstaller.Tool.OPENFRP); Files.createDirectories(destination.getParent());
        Path staging = Files.createTempDirectory(destination.getParent(), ".install-");
        try {
            Path archive = staging.resolve(name), binary = staging.resolve("binary");
            ServerCatalog.download(new ServerCatalog.Artifact(name, "https://staticassets.naids.com/client/" + VERSION + "/" + name, "SHA-256", hash, "OpenFrp", VERSION, 0), archive, progress);
            ServerToolInstaller.extract(archive, wanted, binary);
            if (!binary.toFile().setExecutable(true, true) && !Files.isExecutable(binary)) throw new java.io.IOException("无法设置 OpenFrp 执行权限");
            Files.move(binary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            progress.accept("OpenFrp 安装完成");
        } finally { try (var files = Files.walk(staging)) { for (var file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); } }
    }
}
