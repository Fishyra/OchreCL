package org.jackhuang.hmcl.ui.decorator;

import javafx.animation.AnimationTimer;
import javafx.beans.binding.Bindings;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.effect.GaussianBlur;
import javafx.scene.layout.Region;
import javafx.scene.layout.Pane;
import javafx.scene.shape.Rectangle;
import org.jackhuang.hmcl.setting.SettingsManager;
import org.jackhuang.hmcl.theme.ImmersiveAppearance;
import org.jetbrains.annotations.NotNullByDefault;
import java.util.ArrayList;

/// Shares one cached blurred wallpaper layer under all major glass surfaces; text and controls are never blurred.
@NotNullByDefault
public final class GlassBackdrop extends Region {
    /// Main window, including its in-window dialog stack.
    private final Pane owner;
    /// Union of rounded panel footprints, leaving uncovered home wallpaper sharp.
    private final Group mask = new Group();
    /// Avoids rebuilding identical clipping geometry when the window is idle.
    private String geometry = "";
    /// Scans only while attached and enabled; there is one blur pass, not one per control.
    private final AnimationTimer timer = new AnimationTimer() {
        /// Limits discovery work to ten samples per second while the rendered background stays cached.
        @Override public void handle(long now) { if (now - lastSample < 100000000L) return; lastSample = now; updateMask(); }
    };
    /// Last successful clipping-discovery cadence.
    private long lastSample;
    /// Binds a second, cached rendering of the original wallpaper to the glass panel mask.
    public GlassBackdrop(Pane owner, Region original) {
        this.owner = owner; getStyleClass().add("ochre-glass-backdrop"); setMouseTransparent(true); setCache(true); setCacheHint(javafx.scene.CacheHint.SPEED); setEffect(new GaussianBlur(22)); setClip(mask);
        backgroundProperty().bind(original.backgroundProperty()); opacityProperty().bind(original.opacityProperty());
        visibleProperty().bind(Bindings.createBooleanBinding(ImmersiveAppearance::isEnabled, SettingsManager.settings().themeBrightnessModeProperty(), SettingsManager.settings().getThemeAppearanceOverrides()));
        managedProperty().bind(visibleProperty());
        visibleProperty().addListener((observable, previous, visible) -> { if (visible && getScene() != null) timer.start(); else timer.stop(); geometry = ""; });
        sceneProperty().addListener((observable, previous, current) -> { if (current != null && isVisible()) timer.start(); else timer.stop(); });
    }
    /// Recomputes the visible surface union without snapshotting controls, logs or video content.
    private void updateMask() {
        if (getScene() == null || getScene().getWindow() == null || !getScene().getWindow().isShowing()) return;
        var rectangles = new ArrayList<Node>(); StringBuilder key = new StringBuilder();
        for (String selector : new String[]{".ochre-rail", ".ochre-content-panel", ".ochre-inspector", ".jfx-tool-bar", ".jfx-dialog-layout", ".ochre-launch-dialog", ".ochre-task-menu"}) {
            for (Node node : owner.lookupAll(selector)) {
                if (!node.isVisible() || node.getScene() != getScene()) continue;
                var bounds = sceneToLocal(node.localToScene(node.getClip() == null ? node.getLayoutBounds() : node.getClip().getBoundsInParent()));
                if (bounds.getWidth() <= 0 || bounds.getHeight() <= 0) continue;
                double x = Math.rint(bounds.getMinX()), y = Math.rint(bounds.getMinY()), width = Math.ceil(bounds.getWidth()), height = Math.ceil(bounds.getHeight());
                key.append(x).append(',').append(y).append(',').append(width).append(',').append(height).append(';');
                Rectangle rectangle = new Rectangle(x, y, width, height); rectangle.setArcWidth(32); rectangle.setArcHeight(32); rectangles.add(rectangle);
            }
        }
        if (!geometry.contentEquals(key)) { geometry = key.toString(); mask.getChildren().setAll(rectangles); }
    }
}
