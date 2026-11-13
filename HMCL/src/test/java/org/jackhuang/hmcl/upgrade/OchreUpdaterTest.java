package org.jackhuang.hmcl.upgrade;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.jar.*;
import java.util.HexFormat;
import java.security.MessageDigest;
import static org.junit.jupiter.api.Assertions.*;

/// Rejects foreign, tampered and incomplete updates while preserving the previous active selection.
@NotNullByDefault
public final class OchreUpdaterTest {
    /// Uses GitHub's digest metadata and accepts only this fork's explicitly named update asset.
    @Test public void acceptsOnlyNewForkAssets() throws Exception {
        String valid = "[{\"draft\":false,\"tag_name\":\"v0.3.0-beta.1\",\"body\":\"notes\",\"assets\":[{\"name\":\"OchreCL-update.jar\",\"size\":123,\"digest\":\"sha256:" + "a".repeat(64) + "\",\"browser_download_url\":\"https://github.com/Fishyra/OchreCL/releases/download/v0.3.0-beta.1/OchreCL-update.jar\"}]}]";
        assertNotNull(OchreUpdater.parse(valid, "0.2.0-beta.4")); assertNull(OchreUpdater.parse(valid, "0.4.0"));
        assertNull(OchreUpdater.parse(valid.replace("sha256:", "md5:"), "0.2.0")); assertNull(OchreUpdater.parse(valid.replace("\"draft\":false", "\"draft\":true"), "0.2.0"));
        assertThrows(java.io.IOException.class, () -> OchreUpdater.parse(valid.replace("Fishyra/OchreCL/releases", "HMCL-dev/HMCL/releases"), "0.2.0"));
        assertThrows(java.io.IOException.class, () -> OchreUpdater.validateUrl("http://github.com/Fishyra/OchreCL/releases/download/v1/update.jar"));
    }
    /// A changed or non-launcher image cannot replace the active update pointer.
    @Test public void stagesOnlyVerifiedCompleteImages(@TempDir Path directory) throws Exception {
        Path payload = directory.resolve("payload.jar"), root = directory.resolve("updates");
        try (var output = new JarOutputStream(Files.newOutputStream(payload))) { for (String name : new String[]{"org/jackhuang/hmcl/Main.class", "org/jackhuang/hmcl/ui/decorator/OchreShell.class"}) { output.putNextEntry(new JarEntry(name)); output.write(1); output.closeEntry(); } }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(payload))); var release = new OchreUpdater.Release("0.3.0", "", "https://github.com/Fishyra/OchreCL/releases/download/v0.3.0/OchreCL-update.jar", hash, Files.size(payload));
        Path image = OchreUpdater.stage(payload, root, release); assertTrue(Files.isRegularFile(image)); String pointer = Files.readString(root.resolve("active.properties"));
        Files.writeString(payload, "tampered"); assertThrows(java.io.IOException.class, () -> OchreUpdater.stage(payload, root, release)); assertEquals(pointer, Files.readString(root.resolve("active.properties")));
    }
}
