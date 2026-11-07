// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.ui.construct;

import com.jfoenix.controls.JFXButton;
import javafx.animation.AnimationTimer;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jetbrains.annotations.NotNullByDefault;

import java.util.concurrent.ArrayBlockingQueue;

/// Actual launch task stages beside a bounded, live view of the sanitized game process output.
@NotNullByDefault
public final class OchreLaunchPane extends TaskExecutorDialogPane {
    /// Bounded queue decoupling process-reader threads from JavaFX pulses.
    private final ArrayBlockingQueue<String> pending = new ArrayBlockingQueue<>(1000);
    /// Read-only process output; its contents are trimmed to prevent unbounded growth.
    private final TextArea output = new TextArea();
    /// Whether newly appended output should follow the end of the log.
    private final CheckBox autoScroll = new CheckBox("自动滚动");
    /// Time of the previous text update, limiting expensive text layout to ten batches per second.
    private long lastUpdate;
    /// Retained pulse timer; stopped as soon as this dialog leaves its scene.
    private final AnimationTimer pump = new AnimationTimer() {
        /// Batches up to 100 pending lines per pulse and bounds the retained log text.
        @Override
        public void handle(long now) {
            if (now != 0 && now - lastUpdate < 100_000_000L) return;
            lastUpdate = now;
            StringBuilder batch = new StringBuilder();
            for (int i = 0; i < 100; i++) {
                @org.jetbrains.annotations.Nullable String line = pending.poll();
                if (line == null) break;
                batch.append(line).append('\n');
            }
            if (batch.isEmpty()) return;
            int caret = output.getCaretPosition();
            double scroll = output.getScrollTop();
            output.appendText(batch.toString());
            int removed = Math.max(0, output.getLength() - 60000);
            if (removed > 0) output.deleteText(0, removed);
            if (autoScroll.isSelected()) {
                output.positionCaret(output.getLength());
                output.setScrollTop(Double.MAX_VALUE);
            } else {
                output.positionCaret(Math.max(0, caret - removed));
                output.setScrollTop(scroll);
            }
        }
    };

    /// Keeps upstream task progress and cancellation controls, and adds the real-time log column.
    public OchreLaunchPane() {
        super(TaskCancellationAction.NORMAL);
        getStyleClass().add("ochre-launch-dialog");
        setMinSize(360, 260);
        setPrefSize(820, 420);
        setMaxSize(820, 420);
        Node progress = java.util.Objects.requireNonNull(getCenter());
        progress.getStyleClass().add("ochre-launch-progress");
        HBox.setHgrow(progress, Priority.SOMETIMES);
        if (progress instanceof Region region) {
            region.setMinWidth(220);
            region.setPrefWidth(285);
        }
        output.setMinSize(120, 100);
        output.setEditable(false);
        output.setWrapText(true);
        output.setPromptText("等待游戏进程输出日志…");
        output.getStyleClass().add("ochre-live-log");
        autoScroll.setSelected(true);
        JFXButton copy = new JFXButton("复制日志");
        copy.setOnAction(event -> {
            ClipboardContent content = new ClipboardContent();
            content.putString(output.getText());
            Clipboard.getSystemClipboard().setContent(content);
        });
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox heading = new HBox(10, new Label("实时日志"), spacer, autoScroll, copy);
        heading.setAlignment(Pos.CENTER_LEFT);
        VBox logs = new VBox(10, heading, output);
        logs.getStyleClass().add("ochre-launch-logs");
        logs.setPadding(new Insets(16));
        VBox.setVgrow(output, Priority.ALWAYS);
        HBox.setHgrow(logs, Priority.ALWAYS);
        HBox columns = new HBox(12, progress, logs);
        columns.setPadding(new Insets(12));
        setCenter(columns);
        sceneProperty().addListener((observable, oldScene, newScene) -> {
            if (newScene == null) { pump.stop(); pending.clear(); }
            else pump.start();
        });
    }

    /// Accepts already-redacted output from any game process reader thread.
    public void appendGameLog(String line) {
        if (!pending.offer(line)) {
            pending.poll();
            pending.offer(line);
        }
    }
}
