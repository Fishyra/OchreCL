package org.jackhuang.hmcl.ui.decorator;

import com.jfoenix.controls.JFXButton;
import javafx.animation.*;
import javafx.beans.property.*;
import javafx.beans.value.ChangeListener;
import javafx.event.EventHandler;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.util.List;

/// One continuous circle-to-task-panel surface, with independent scrolling and reversible real bounds animation.
@NotNullByDefault
public final class FloatingTaskSurface extends VBox {
    /// Window surface used to clamp the expanded menu and position its circle origin.
    private final Pane host;
    /// Floating action whose appearance and input are suspended while its surface is expanded.
    private final JFXButton anchor;
    /// Releases the shell's current-menu reference once the close animation has finished.
    private final Runnable closed;
    /// Live task rows, kept separate from the fixed heading and footer.
    private final VBox entries = new VBox(6);
    /// The rectangular menu content fades in after the circle starts expanding.
    private final VBox content = new VBox(10);
    /// Continuous bounds interpolation factor; reversing never jumps back to the circle.
    private final DoubleProperty extent = new SimpleDoubleProperty();
    /// Only one active animation controls this surface.
    private final Timeline motion = new Timeline();
    /// Child clipping follows the same animated radius as the surface fill.
    private final Rectangle clipping = new Rectangle();
    /// Original button fill and final menu fill, resolved from the active theme.
    private Color circleFill = Color.web("#EBD8B3"), panelFill = Color.web("#F7F1E5");
    /// True after a dismissal begins, avoiding duplicate actions from repeated pointer events.
    private boolean closing;
    /// Keeps a menu inside the visible window when the player resizes it while open.
    private final ChangeListener<Number> resized = (observable, before, after) -> draw();
    /// Dismisses outside presses without stealing their normal page action.
    private final EventHandler<MouseEvent> outside = event -> {
        if (inside(event.getTarget() instanceof Node node ? node : null)) return;
        if (event.getTarget() instanceof Node node && (isAnchor(node))) event.consume();
        close(() -> {});
    };
    /// Builds a fixed heading/footer around the scrollable task list.
    public FloatingTaskSurface(Pane host, JFXButton anchor, Runnable closed, Runnable resetPosition) {
        this.host = host; this.anchor = anchor; this.closed = closed;
        getStyleClass().add("ochre-task-menu"); setManaged(false); setMinSize(0, 0); setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE); setClip(clipping);
        Label title = new Label("正在执行的任务"); title.setGraphic(SVG.INFO.createIcon(18)); title.getStyleClass().add("ochre-task-heading");
        ScrollPane scroll = new ScrollPane(entries); scroll.setFitToWidth(true); scroll.setMinSize(0, 0); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.getStyleClass().add("ochre-task-scroll"); VBox.setVgrow(scroll, Priority.ALWAYS);
        JFXButton reset = new JFXButton("恢复悬浮球位置"); reset.setGraphic(SVG.RESTORE.createIcon(16));
        reset.setOnAction(event -> close(resetPosition));
        JFXButton collapse = new JFXButton("收起"); collapse.setGraphic(SVG.CLOSE.createIcon(16)); collapse.setOnAction(event -> close(() -> {}));
        HBox footer = new HBox(8, reset, collapse); footer.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        content.setPadding(new Insets(16)); content.setMinSize(0, 0); content.getChildren().setAll(title, scroll, footer); getChildren().setAll(content); VBox.setVgrow(content, Priority.ALWAYS);
        extent.addListener((observable, before, after) -> draw());
        setFocusTraversable(true); setOnKeyPressed(event -> { if (event.getCode() == KeyCode.ESCAPE) { close(() -> {}); event.consume(); } });
        reset.setId("ochre-task-reset");
    }
    /// Replaces only changed rows; heading/footer and scroll position survive normal timer updates.
    public void setEntries(List<Node> rows) { entries.getChildren().setAll(rows); draw(); }
    /// Begins from the exact floating circle's bounds and color; there is no scaled rectangle pop-up.
    public void open() {
        host.getChildren().add(this); applyCss(); anchor.applyCss();
        circleFill = fill(anchor, circleFill); panelFill = fill(this, panelFill);
        anchor.setOpacity(0); anchor.setMouseTransparent(true);
        host.widthProperty().addListener(resized); host.heightProperty().addListener(resized);
        if (host.getScene() != null) host.getScene().addEventFilter(MouseEvent.MOUSE_PRESSED, outside);
        draw(); requestFocus();
        motion.getKeyFrames().setAll(new KeyFrame(Duration.millis(260), new KeyValue(extent, 1, Interpolator.EASE_BOTH))); motion.play();
    }
    /// Reverses to the current circle location and runs navigation only after safely detaching the menu.
    public void close(Runnable action) {
        if (closing) return; closing = true; motion.stop();
        motion.getKeyFrames().setAll(new KeyFrame(Duration.millis(190), new KeyValue(extent, 0, Interpolator.EASE_BOTH)));
        motion.setOnFinished(event -> {
            host.widthProperty().removeListener(resized); host.heightProperty().removeListener(resized);
            if (host.getScene() != null) host.getScene().removeEventFilter(MouseEvent.MOUSE_PRESSED, outside);
            host.getChildren().remove(this); anchor.setOpacity(1); anchor.setMouseTransparent(false); closed.run(); action.run();
        }); motion.play();
    }
    /// Identifies pointer presses on the hidden circle or its graphic.
    private boolean isAnchor(Node node) { for (@Nullable Node current = node; current != null; current = current.getParent()) if (current == anchor) return true; return false; }
    /// Checks ancestry without relying on screen coordinates or stealing scrollbar gestures.
    private boolean inside(@Nullable Node node) { for (@Nullable Node current = node; current != null; current = current.getParent()) if (current == this) return true; return false; }
    /// Resolves CSS fills while retaining support for unusual non-color theme paints.
    private static Color fill(Region region, Color fallback) { var background = region.getBackground(); return background != null && !background.getFills().isEmpty() && background.getFills().get(0).getFill() instanceof Color color ? color : fallback; }
    /// Computes actual width/height, position, clipping and radius for every animation frame.
    private void draw() {
        double amount = extent.get(), availableWidth = Math.max(56, host.getWidth() - 24), availableHeight = Math.max(56, host.getHeight() - 24);
        double targetWidth = Math.min(360, availableWidth), targetHeight = Math.min(Math.min(460, availableHeight), Math.max(160, 108 + entries.getChildren().size() * 66));
        double targetX = Math.max(12, Math.min(host.getWidth() - targetWidth - 12, anchor.getLayoutX() + 56 - targetWidth));
        double targetY = Math.max(12, Math.min(host.getHeight() - targetHeight - 12, anchor.getLayoutY() + 56 - targetHeight));
        double width = 56 + (targetWidth - 56) * amount, height = 56 + (targetHeight - 56) * amount;
        resizeRelocate(anchor.getLayoutX() + (targetX - anchor.getLayoutX()) * amount, anchor.getLayoutY() + (targetY - anchor.getLayoutY()) * amount, width, height);
        double radius = 28 - 10 * amount; clipping.setWidth(width); clipping.setHeight(height); clipping.setArcWidth(radius * 2); clipping.setArcHeight(radius * 2);
        setBackground(new Background(new BackgroundFill(circleFill.interpolate(panelFill, amount), new CornerRadii(radius), Insets.EMPTY)));
        content.setOpacity(Math.max(0, (amount - .35) / .65)); content.setMouseTransparent(closing || amount < .98); content.resize(width, height); requestLayout();
    }
}
