package org.jackhuang.hmcl.script;

import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;
import java.io.IOException;
import java.nio.file.*;
import java.util.Properties;

/// Persistent script defaults, snapshotted separately for each execution.
@NotNullByDefault
public final class ScriptPreferences {
    /// User data remains outside installation bundles.
    private static final Path FILE = Metadata.HMCL_LOCAL_HOME.resolve("script-settings.properties");
    /// Validated defaults loaded once on demand.
    private static final Properties VALUES = load();
    /// Utility class.
    private ScriptPreferences() { }
    /// Reads a small preferences file, retaining safe defaults after a damaged file.
    private static Properties load() { Properties value = new Properties(); try { if (Files.isRegularFile(FILE) && Files.size(FILE) < 16384) try (var input = Files.newInputStream(FILE)) { value.load(input); } } catch (IOException | IllegalArgumentException ignored) { } return value; }
    /// Returns the selected failure behavior or the stop default for unknown values.
    public static synchronized OchreScript.FailurePolicy policy() { try { return OchreScript.FailurePolicy.valueOf(VALUES.getProperty("failure", "STOP")); } catch (IllegalArgumentException error) { return OchreScript.FailurePolicy.STOP; } }
    /// Additional attempts, with a hard safety bound independent of the UI.
    public static synchronized int repeatCount() { try { return Math.max(0, Math.min(100, Integer.parseInt(VALUES.getProperty("script_repeat_count", "3")))); } catch (NumberFormatException error) { return 3; } }
    /// Automatic grants affect launcher capabilities only, never server permissions.
    public static synchronized boolean autoGrant() { return Boolean.parseBoolean(VALUES.getProperty("auto_grant", "false")); }
    /// Persists a complete configuration atomically before replacing the current defaults.
    public static synchronized void save(OchreScript.FailurePolicy policy, int repeats, boolean autoGrant) throws IOException {
        if (repeats < 0 || repeats > 100) throw new IllegalArgumentException("script_repeat_count must be in [0, 100]");
        Properties next = new Properties(); next.setProperty("failure", policy.name()); next.setProperty("script_repeat_count", Integer.toString(repeats)); next.setProperty("auto_grant", Boolean.toString(autoGrant));
        Files.createDirectories(FILE.getParent()); Path pending = FILE.resolveSibling(FILE.getFileName() + ".new"); try (var output = Files.newOutputStream(pending)) { next.store(output, "Ochre Script execution defaults"); }
        try { Files.move(pending, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); } catch (AtomicMoveNotSupportedException error) { Files.move(pending, FILE, StandardCopyOption.REPLACE_EXISTING); } VALUES.clear(); VALUES.putAll(next);
    }
}
