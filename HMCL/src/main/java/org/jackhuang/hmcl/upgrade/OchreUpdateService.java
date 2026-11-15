package org.jackhuang.hmcl.upgrade;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectProperty;
import javafx.beans.property.ReadOnlyObjectWrapper;
import org.jackhuang.hmcl.task.Schedulers;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.atomic.AtomicBoolean;

/// One non-blocking startup check shared with the home update shortcut.
@NotNullByDefault
public final class OchreUpdateService {
    /// At most one automatic check is started during this launcher process.
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    /// Only a verified eligible release can make the home shortcut visible.
    private static final ReadOnlyObjectWrapper<OchreUpdater.@Nullable Release> AVAILABLE = new ReadOnlyObjectWrapper<>();
    /// Utility class.
    private OchreUpdateService() { }
    /// Observes successful update metadata; network errors leave the homepage unobstructed.
    public static ReadOnlyObjectProperty<OchreUpdater.@Nullable Release> availableProperty() { return AVAILABLE.getReadOnlyProperty(); }
    /// Starts after the shell exists without delaying startup or installing anything.
    public static void initialize() { if (!STARTED.compareAndSet(false, true)) return; Schedulers.io().execute(() -> { try { @Nullable var release = OchreUpdater.check(); Platform.runLater(() -> AVAILABLE.set(release)); } catch (Exception error) { org.jackhuang.hmcl.util.logging.Logger.LOG.debug("Startup update check unavailable: " + error.getClass().getSimpleName()); } }); }
}
