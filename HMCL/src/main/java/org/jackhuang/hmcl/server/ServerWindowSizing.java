package org.jackhuang.hmcl.server;

import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.geometry.Rectangle2D;
import javafx.scene.Scene;
import javafx.scene.layout.Region;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.Window;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Enlarges undersized server workspaces on entry, preserving window center within the current screen.
@NotNullByDefault
public final class ServerWindowSizing {
    /// Native-window property storing bounds before this server workspace enlarged it.
    private static final String ORIGINAL = ServerWindowSizing.class.getName() + ".original";
    /// Active native bounds transition shared across server pages on the same window.
    private static final String MOTION = ServerWindowSizing.class.getName() + ".motion";
    /// Last navigation target, guarding delayed growth during page transition animations.
    private static final String ACTIVE_PAGE = ServerWindowSizing.class.getName() + ".page";
    /// Coalesces navigation stack clearing with the final page selected in the same UI turn.
    public static void navigationChanged(@Nullable Window window, java.util.function.Supplier<javafx.scene.Node> currentPage) {
        if (!(window instanceof Stage stage)) return;
        stage.getProperties().put(ACTIVE_PAGE, currentPage.get());
        Platform.runLater(() -> {
            if (currentPage.get() instanceof ServersPage || currentPage.get() instanceof ServerWorkspacePage) {
                if (stage.getProperties().get(MOTION) instanceof BoundsTransition motion && motion.restoring) animate(stage, new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()), false);
                return;
            }
            @Nullable Object saved = stage.getProperties().get(ORIGINAL);
            if (!(saved instanceof Rectangle2D before) || stage.isFullScreen() || org.jackhuang.hmcl.ui.decorator.WindowState.isMaximized(stage)) return;
            double centerX = stage.getX() + stage.getWidth() / 2, centerY = stage.getY() + stage.getHeight() / 2;
            var screens = Screen.getScreensForRectangle(centerX, centerY, 1, 1);
            var screen = (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
            var target = restored(centerX, centerY, before.getWidth(), before.getHeight(), screen);
            animate(stage, target, true);
        });
    }
    /// Restores the saved size around the current center while fitting the current display.
    static Rectangle2D restored(double centerX, double centerY, double width, double height, Rectangle2D screen) {
        double w = Math.min(width, screen.getWidth()), h = Math.min(height, screen.getHeight());
        return new Rectangle2D(Math.max(screen.getMinX(), Math.min(centerX - w / 2, screen.getMaxX() - w)),
            Math.max(screen.getMinY(), Math.min(centerY - h / 2, screen.getMaxY() - h)), w, h);
    }

    /// Coalesces navigation/show events until native restoration has settled.
    private final javafx.animation.PauseTransition entryDelay = new javafx.animation.PauseTransition(javafx.util.Duration.millis(180));
    /// Server content node whose scene attachment marks entry to the workspace.
    private final Region page;
    /// Listeners are detached when navigation removes the page.
    private @Nullable Scene scene;
    /// Currently observed native window.
    private @Nullable Window window;
    /// Handles a scene receiving its window after initial construction.
    private final ChangeListener<Window> windowChanged = (observable, previous, current) -> observeWindow(current);
    /// Handles a newly created window becoming visible.
    private final ChangeListener<Boolean> showingChanged = (observable, previous, showing) -> { if (showing) request(); };

    /// Installs entry-only growth; players can still resize the window smaller afterward.
    public static void install(Region page) { if (!page.getProperties().containsKey(ServerWindowSizing.class)) page.getProperties().put(ServerWindowSizing.class, new ServerWindowSizing(page)); }
    /// Connects scene lifecycle without enforcing a permanent larger minimum window size.
    private ServerWindowSizing(Region page) {
        this.page = page; entryDelay.setOnFinished(event -> grow()); page.sceneProperty().addListener((observable, previous, current) -> observeScene(current)); observeScene(page.getScene());
    }
    /// Removes listeners belonging to the previous navigation attachment.
    private void observeScene(@Nullable Scene current) {
        if (scene != null) scene.windowProperty().removeListener(windowChanged);
        entryDelay.stop(); observeWindow(null); scene = current;
        if (current != null) { current.windowProperty().addListener(windowChanged); observeWindow(current.getWindow()); request(); }
    }
    /// Keeps only the current window's showing listener attached.
    private void observeWindow(@Nullable Window current) {
        if (window != null) window.showingProperty().removeListener(showingChanged); window = current;
        if (current != null) { current.showingProperty().addListener(showingChanged); request(); }
    }
    /// Waits until the navigation/layout attachment has settled before changing native bounds once.
    private void request() { Platform.runLater(() -> { if (page.getScene() != null) entryDelay.playFromStart(); }); }
    /// Fits a usable server panel without resizing maximized/fullscreen windows or shrinking larger windows.
    private void grow() {
        if (page.getScene() == null || page.getScene().getWindow() != window || !(window instanceof Stage stage) || !stage.isShowing() || stage.isFullScreen() || org.jackhuang.hmcl.ui.decorator.WindowState.isMaximized(stage)) return;
        if (stage.getProperties().containsKey(ACTIVE_PAGE) && stage.getProperties().get(ACTIVE_PAGE) != page) return;
        double width = stage.getWidth(), height = stage.getHeight();
        var screens = Screen.getScreensForRectangle(stage.getX() + width / 2, stage.getY() + height / 2, 1, 1);
        Rectangle2D bounds = (screens.isEmpty() ? Screen.getPrimary() : screens.get(0)).getVisualBounds();
        Rectangle2D target = centered(stage.getX(), stage.getY(), width, height, bounds);
        if (target.getWidth() <= width + 1 && target.getHeight() <= height + 1) return;
        stage.getProperties().putIfAbsent(ORIGINAL, new Rectangle2D(stage.getX(), stage.getY(), width, height));
        animate(stage, target, false);
    }
    /// Reverses from the current native bounds instead of jumping when navigation interrupts a resize.
    private static void animate(Stage stage, Rectangle2D target, boolean restoring) {
        if (stage.getProperties().get(MOTION) instanceof BoundsTransition previous) previous.stop();
        BoundsTransition motion = new BoundsTransition(stage, target, restoring); stage.getProperties().put(MOTION, motion);
        motion.setOnFinished(event -> {
            if (stage.getProperties().get(MOTION) != motion) return;
            stage.getProperties().remove(MOTION);
            motion.reset();
            if (restoring) stage.getProperties().remove(ORIGINAL);
        });
        motion.play();
    }
    /// Uses a compositor-style scale transition while committing native bounds only once.
    /// Repeated native resize frames corrupt transparent JavaFX backing surfaces on macOS.
    @NotNullByDefault
    private static final class BoundsTransition extends javafx.animation.Transition {
        /// Window controlled only while visible and in normal windowed mode.
        private final Stage stage;
        /// Scene surface whose centered transform supplies the visible window animation.
        private final javafx.scene.Node surface;
        /// Screen-clamped final native bounds.
        private final Rectangle2D target;
        /// Whether native shrinking waits until the visual shrink completes.
        private final boolean restoring;
        /// Current visible scale, retained when quickly reversing navigation.
        private final double startX, startY;
        /// Final scale before committing the smaller native bounds.
        private final double endX, endY;
        /// Captures the currently painted size and prepares the visible zoom without native resize loops.
        private BoundsTransition(Stage stage, Rectangle2D target, boolean restoring) {
            this.stage = stage; this.target = target; this.restoring = restoring; var root = stage.getScene().getRoot();
            surface = root instanceof javafx.scene.layout.Pane pane && !pane.getChildren().isEmpty() ? pane.getChildren().get(0) : root;
            double visibleWidth = stage.getWidth() * surface.getScaleX(), visibleHeight = stage.getHeight() * surface.getScaleY();
            if (restoring) {
                startX = surface.getScaleX(); startY = surface.getScaleY();
                endX = target.getWidth() / stage.getWidth(); endY = target.getHeight() / stage.getHeight();
            } else {
                applyBounds(); startX = visibleWidth / target.getWidth(); startY = visibleHeight / target.getHeight(); endX = endY = 1;
            }
            surface.setScaleX(startX); surface.setScaleY(startY);
            setCycleDuration(javafx.util.Duration.millis(340)); setInterpolator(javafx.animation.Interpolator.EASE_BOTH);
        }
        /// Scales about the surface center; fullscreen/maximize gestures cancel automatic motion.
        @Override protected void interpolate(double fraction) {
            if (!stage.isShowing() || stage.isFullScreen() || org.jackhuang.hmcl.ui.decorator.WindowState.isMaximized(stage)) {
                stop(); reset(); stage.getProperties().remove(MOTION); stage.getProperties().remove(ORIGINAL); return;
            }
            surface.setScaleX(startX + (endX - startX) * fraction); surface.setScaleY(startY + (endY - startY) * fraction);
            if (fraction >= 1 && restoring) { applyBounds(); reset(); }
        }
        /// Commits native geometry once, outside the per-frame rendering loop.
        private void applyBounds() {
            stage.setWidth(target.getWidth()); stage.setHeight(target.getHeight()); stage.setX(target.getMinX()); stage.setY(target.getMinY());
        }
        /// Returns the scene to its normal transform after completion or a native window gesture.
        private void reset() { surface.setScaleX(1); surface.setScaleY(1); }
    }
    /// Computes screen-clamped centered growth independently of the native window toolkit.
    static Rectangle2D centered(double x, double y, double width, double height, Rectangle2D screen) {
        double nextWidth = Math.max(width, Math.min(1120, screen.getWidth()));
        double nextHeight = Math.max(height, Math.min(780, screen.getHeight()));
        double nextX = x + (width - nextWidth) / 2, nextY = y + (height - nextHeight) / 2;
        if (nextWidth <= screen.getWidth()) nextX = Math.max(screen.getMinX(), Math.min(nextX, screen.getMaxX() - nextWidth));
        if (nextHeight <= screen.getHeight()) nextY = Math.max(screen.getMinY(), Math.min(nextY, screen.getMaxY() - nextHeight));
        return new Rectangle2D(nextX, nextY, nextWidth, nextHeight);
    }
}
