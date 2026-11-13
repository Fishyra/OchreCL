package org.jackhuang.hmcl.ui.construct;

import javafx.collections.*;
import javafx.beans.property.*;
import org.jetbrains.annotations.NotNullByDefault;
import java.util.UUID;

/// UI-thread task registry shared by progress overlays and the floating task launcher.
@NotNullByDefault
public final class OchreTasks {
    /// Active installation/deployment operations, removed on completion or cancellation.
    public static final ObservableList<Entry> ACTIVE = FXCollections.observableArrayList();
    /// Hidden progress state; changing it triggers the floating button ripple.
    public static final ReadOnlyIntegerWrapper SILENT_REVISION = new ReadOnlyIntegerWrapper();
    /// Thread-safe readiness flag prevents deferred options writes during game launch.
    private static volatile boolean launching;
    static { ACTIVE.addListener((javafx.collections.ListChangeListener<Entry>) change -> launching = ACTIVE.stream().anyMatch(Entry::launching)); }
    /// Returns whether any registered launch is still preparing a game.
    public static boolean isLaunching() { return launching; }
    /// Prevents construction of the shared registry.
    private OchreTasks() {}
    /// One live operation and its explicit action to reopen its original progress pane.
    @NotNullByDefault
    public record Entry(String id, boolean launching, java.util.function.Supplier<String> title, Runnable open) {}
    /// Registers once and keeps the displayed title live through a supplier.
    public static Entry add(java.util.function.Supplier<String> title, Runnable open) { return add(false, title, open); }
    /// Registers an operation with an explicit launch or installation kind.
    public static Entry add(boolean launching, java.util.function.Supplier<String> title, Runnable open) { Entry entry = new Entry(UUID.randomUUID().toString(), launching, title, open); ACTIVE.add(entry); return entry; }
    /// Reopens only after the closing dialog animation has detached its content node.
    public static void reopen(javafx.scene.layout.Region content) {
        if (Boolean.TRUE.equals(content.getProperties().get("ochre.reopening"))) return;
        content.getProperties().put("ochre.reopening", true);
        var timer = new javafx.animation.Timeline(); int[] attempts = {0};
        timer.getKeyFrames().add(new javafx.animation.KeyFrame(javafx.util.Duration.millis(50), event -> {
            if (content.getParent() == null) { timer.stop(); content.getProperties().remove("ochre.reopening"); org.jackhuang.hmcl.ui.Controllers.dialog(content); }
            else if (++attempts[0] > 40) { timer.stop(); content.getProperties().remove("ochre.reopening"); }
        })); timer.setCycleCount(javafx.animation.Animation.INDEFINITE); timer.play();
    }
    /// Returns control to the most recently started operation.
    public static boolean openLatest() { if (ACTIVE.isEmpty()) return false; ACTIVE.get(ACTIVE.size() - 1).open().run(); return true; }
}
