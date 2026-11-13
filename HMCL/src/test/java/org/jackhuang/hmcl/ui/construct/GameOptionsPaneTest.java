package org.jackhuang.hmcl.ui.construct;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/// Checks real options-file preservation rather than mirroring control construction.
@NotNullByDefault
public final class GameOptionsPaneTest {
    /// Editing selected vanilla fields must retain comments, ordering, mod keys and original backup.
    @Test public void mergesChangedFieldsAndKeepsBackup(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("options.txt"); String original = "# keep comment\nmodded:some:value\nfullscreen:false\nsoundCategory_master:0.7\n"; Files.writeString(file, original);
        GameOptionsPane.merge(file, Map.of("fullscreen", "true", "renderDistance", "16"));
        assertEquals(original, Files.readString(directory.resolve("options.txt.ochre-backup")));
        assertEquals("some:value", GameOptionsPane.read(file).get("modded")); assertTrue(Files.readString(file).startsWith("# keep comment"));
        assertEquals("true", GameOptionsPane.read(file).get("fullscreen")); assertEquals("0.7", GameOptionsPane.read(file).get("soundCategory_master")); assertEquals("16", GameOptionsPane.read(file).get("renderDistance"));
    }
}
