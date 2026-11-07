package org.jackhuang.hmcl.server;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.zip.*;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises archive isolation and configuration escaping without downloading or exposing a service.
@NotNullByDefault
public final class OchreToolInstallerTest {
    /// Archive member paths are never used as filesystem output paths.
    @Test public void extractsOnlyExpectedBinary(@TempDir Path root) throws Exception {
        Path zip = root.resolve("tool.zip"), output = root.resolve("rclone");
        try (var archive = new ZipOutputStream(Files.newOutputStream(zip))) {
            archive.putNextEntry(new ZipEntry("../../unrelated")); archive.write("do not extract".getBytes()); archive.closeEntry();
            archive.putNextEntry(new ZipEntry("release/rclone")); archive.write("executable fixture".getBytes()); archive.closeEntry();
        }
        ServerToolInstaller.extract(zip, "rclone", output);
        assertEquals("executable fixture", Files.readString(output)); assertFalse(Files.exists(root.resolve("unrelated")));
        assertThrows(java.io.IOException.class, () -> ServerToolInstaller.extract(zip, "frpc", root.resolve("frpc")));
    }
    /// Truncated tar headers and linked binaries cannot produce a usable installation.
    @Test public void rejectsLinkedAndTruncatedTar(@TempDir Path root) throws Exception {
        Path tar = root.resolve("tool.tar.gz");
        byte[] header = new byte[512]; System.arraycopy("release/frpc".getBytes(), 0, header, 0, 12);
        System.arraycopy("00000000000".getBytes(), 0, header, 124, 11); header[156] = '2';
        try (var output = new GZIPOutputStream(Files.newOutputStream(tar))) { output.write(header); output.write(new byte[1024]); }
        assertThrows(java.io.IOException.class, () -> ServerToolInstaller.extract(tar, "frpc", root.resolve("frpc")));
        assertFalse(Files.exists(root.resolve("frpc")));
    }
    /// Malformed ports and credential newlines cannot inject extra configuration properties.
    @Test public void validatesPortsAndEscapesSecrets() throws Exception {
        ServerServices.checkPort(25565); assertThrows(java.io.IOException.class, () -> ServerServices.checkPort(0));
        assertEquals("\"host\\\"\\nsecret\"", ServerServices.quote("host\"\nsecret"));
        assertFalse(ServerServices.quote("token\n[[proxies]]").contains("\n"));
    }
}
