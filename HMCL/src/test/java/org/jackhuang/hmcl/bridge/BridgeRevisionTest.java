package org.jackhuang.hmcl.bridge;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Already-consented old components are refreshed for the next JVM without changing running images or disabled consent.
@NotNullByDefault
public final class BridgeRevisionTest {
    /// Disposable instance directory, unrelated to real game data.
    @TempDir Path directory;
    /// Models a previous consented image using its actual checksum.
    private Path previous(boolean enabled, boolean corrupt) throws Exception {
        Path folder = directory.resolve(".ochre-bridge"); Files.createDirectories(folder);
        byte[] old = "old-component-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(old));
        Path image = folder.resolve("bridge-" + hash + ".jar"); Files.write(image, old);
        Properties settings = new Properties(); settings.setProperty("enabled", Boolean.toString(enabled)); settings.setProperty("agent", image.getFileName().toString()); settings.setProperty("sha256", corrupt ? "0".repeat(64) : hash);
        try (var output = Files.newOutputStream(folder.resolve("settings.properties"))) { settings.store(output, "test fixture"); }
        return image;
    }
    /// A normal update chooses a distinct validated image while retaining the previous file for a running JVM.
    @Test public void refreshConsentedRevision() throws Exception {
        Path old = previous(true, false); Path updated = BridgeManager.imageForLaunch(directory.toString());
        assertNotEquals(old, updated); assertTrue(Files.isRegularFile(old)); assertTrue(BridgeManager.current(directory.toString()));
        try (var input = Objects.requireNonNull(BridgeManager.class.getResourceAsStream("/assets/bridge/OchreBridge.jar"))) { assertArrayEquals(input.readAllBytes(), Files.readAllBytes(updated)); }
        assertEquals(updated, BridgeManager.imageForLaunch(directory.toString()));
    }
    /// A stopped consent or corrupt image never silently enables or repairs itself.
    @Test public void disabledAndDamagedImagesAreRejected() throws Exception {
        previous(false, false); assertThrows(java.io.IOException.class, () -> BridgeManager.imageForLaunch(directory.toString()));
        previous(true, true); assertThrows(java.io.IOException.class, () -> BridgeManager.imageForLaunch(directory.toString()));
    }
}
