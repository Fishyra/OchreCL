package org.jackhuang.hmcl.bridge;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises the same framing used by the separate game JVM without requiring a socket or game process.
@NotNullByDefault
public final class BridgeWireTest {
    /// Round-trips UTF-8, and rejects non-protocol traffic and advertised oversized fields before allocation.
    @Test public void boundsAndRoundTrip() throws Exception {
        var bytes = new ByteArrayOutputStream(); var fields = Map.of("protocol", "1", "command", "options.set", "caption", "声音与视频"); BridgeWire.write(bytes, fields); assertEquals(fields, BridgeWire.read(new ByteArrayInputStream(bytes.toByteArray())));
        assertThrows(IOException.class, () -> BridgeWire.read(new ByteArrayInputStream("GET / HTTP/1.1".getBytes())));
        bytes.reset(); var data = new DataOutputStream(bytes); data.writeInt(0x4f434852); data.writeInt(1); data.writeInt(Integer.MAX_VALUE);
        assertThrows(IOException.class, () -> BridgeWire.read(new ByteArrayInputStream(bytes.toByteArray())));
    }
    /// The homepage capability gate uses a real 26.x version family, not instance naming conventions.
    @Test public void only26Family() {
        assertTrue(BridgeManager.is26("26.2")); assertTrue(BridgeManager.is26("26.3.1")); assertTrue(BridgeManager.is26("26.1-pre-1"));
        assertFalse(BridgeManager.is26("1.21.8")); assertFalse(BridgeManager.is26("1.26.2")); assertFalse(BridgeManager.is26("macOS Metal")); assertFalse(BridgeManager.is26("126.2"));
    }
}
