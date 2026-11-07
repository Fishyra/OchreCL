package org.jackhuang.hmcl.server;

import com.jfoenix.controls.JFXButton;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import javafx.util.Duration;
import org.jackhuang.hmcl.task.Schedulers;
import org.jetbrains.annotations.NotNullByDefault;

/// Live bounded console and measured metrics, polled only while attached and visible.
@NotNullByDefault
public final class RuntimePanel extends VBox {
    /// Process observed by this panel.
    private final RuntimeSession session;
    /// Non-editable console with optional automatic following.
    private final TextArea console = new TextArea();
    /// Displays actual system and process measurements.
    private final ResourceDashboard metrics = new ResourceDashboard();
    /// Player choice to follow the log tail.
    private final CheckBox follow = new com.jfoenix.controls.JFXCheckBox("跟随日志");
    /// Quarter-second console cadence; expensive load sampling is separately limited to two seconds.
    private final Timeline timer;
    /// Prevents overlapping sampling tasks.
    private boolean sampling;
    /// Last console revision rendered by this panel.
    private long revision = -1;
    /// Last completed load sample time.
    private long lastMetrics;
    /// Latest measured load text, reused between samples.
    private volatile @org.jetbrains.annotations.Nullable RuntimeSession.Snapshot cachedMetrics;

    /// Creates a reusable panel that suspends itself when removed from the scene.
    public RuntimePanel(RuntimeSession session) {
        super(10);
        this.session = session; setMinSize(0, 0);
        setPadding(new Insets(12));
        getStyleClass().add("ochre-runtime-panel");

        Label status = new Label();
        status.textProperty().bind(session.stateProperty());
        follow.setSelected(true);
        JFXButton copy = new JFXButton("复制日志");
        copy.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent(); content.putString(console.getText()); Clipboard.getSystemClipboard().setContent(content);
        });
        JFXButton copyLoad = new JFXButton("复制负载");
        copyLoad.setOnAction(event -> {
            var sample = cachedMetrics; if (sample == null) return;
            ClipboardContent content = new ClipboardContent(); content.putString(sample.report()); Clipboard.getSystemClipboard().setContent(content);
        });
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        FlowPane toolbar = new FlowPane(10, 8, new Label("实时日志"), status, follow, copy, copyLoad);
        toolbar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        console.setEditable(false);
        console.setWrapText(false);
        console.setMinHeight(72);
        console.getStyleClass().add("ochre-runtime-console");
        VBox.setVgrow(console, Priority.ALWAYS);
        ScrollPane resources = new ScrollPane(metrics); resources.setFitToWidth(true);
        resources.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); resources.setMinHeight(60);
        resources.getStyleClass().add("ochre-resource-scroll");
        resources.prefHeightProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(
                () -> Math.max(60, Math.min(getWidth() >= 708 ? 156 : 280, getHeight() - toolbar.prefHeight(Math.max(100, getWidth() - 24)) - 116)), widthProperty(), heightProperty()));
        resources.setMaxHeight(Region.USE_PREF_SIZE);
        getChildren().setAll(resources, toolbar, console);
        timer = new Timeline(new KeyFrame(Duration.millis(250), event -> sample()));
        timer.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((observable, oldScene, scene) -> {
            if (scene == null) timer.stop(); else { timer.play(); sample(); }
        });
        visibleProperty().addListener((observable, oldValue, visible) -> { if (!visible) timer.stop(); else if (getScene() != null) { timer.play(); sample(); } });
    }
    /// Copies snapshots off-thread and applies at most one bounded log update per interval.
    private void sample() {
        if (sampling || getScene() == null || !isVisible() || getScene().getWindow() == null || !getScene().getWindow().isShowing()) return;
        sampling = true;
        Schedulers.io().execute(() -> {
            long now = System.nanoTime();
            boolean measured = now - lastMetrics >= 2000000000L;
            if (measured) { cachedMetrics = session.snapshot(); lastMetrics = now; }
            var load = cachedMetrics;
            long next = session.revision();
            String log = next == revision ? "" : session.log();
            Platform.runLater(() -> {
                try {
                    if (measured && load != null) metrics.update(load);
                    if (next != revision) {
                        double scroll = console.getScrollTop();
                        console.setText(log);
                        if (follow.isSelected()) console.setScrollTop(Double.MAX_VALUE); else console.setScrollTop(scroll);
                        revision = next;
                    }
                } finally { sampling = false; }
            });
        });
    }
}
