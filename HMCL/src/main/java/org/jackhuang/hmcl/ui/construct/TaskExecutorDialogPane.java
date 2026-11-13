// SPDX-FileCopyrightText: Copyright (C) 2021 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.ui.construct;

import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.task.FetchTask;
import org.jackhuang.hmcl.task.TaskExecutor;
import org.jackhuang.hmcl.task.TaskListener;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.util.TaskCancellationAction;
import org.jackhuang.hmcl.util.i18n.I18n;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.ui.FXUtils.runInFX;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public class TaskExecutorDialogPane extends BorderPane {
    /// Registry item survives hiding the progress overlay until the operation ends.
    private @org.jetbrains.annotations.Nullable OchreTasks.Entry backgroundEntry;
    /// Whether the original progress pane is currently hidden by the player.
    private boolean silent;
    /// Executor is absent until the caller attaches an installation task.
    private @org.jetbrains.annotations.Nullable TaskExecutor executor;
    private TaskCancellationAction onCancel;
    @SuppressWarnings({"unused", "FieldCanBeLocal"})
    private final Consumer<FetchTask.SpeedEvent> speedEventHandler;

    private final Label lblTitle;
    private final Label lblProgress;
    private final JFXButton btnCancel;
    private final TaskListPane taskListPane;

    public TaskExecutorDialogPane(@NotNull TaskCancellationAction cancel) {
        this.getStyleClass().addAll("task-executor-dialog-layout", "ochre-install-dialog");

        FXUtils.setLimitWidth(this, 620);
        FXUtils.setLimitHeight(this, 380);

        VBox center = new VBox();
        this.setCenter(center);
        center.setPadding(new Insets(16));
        {
            lblTitle = new Label();
            lblTitle.setStyle("-fx-font-size: 20px; -fx-font-weight: BOLD;");
            lblTitle.setGraphic((this instanceof OchreLaunchPane ? org.jackhuang.hmcl.ui.SVG.OCHRE_PLAY : org.jackhuang.hmcl.ui.SVG.DOWNLOAD).createIcon(26)); lblTitle.setWrapText(true);

            taskListPane = new TaskListPane();
            VBox.setVgrow(taskListPane, Priority.ALWAYS);

            Label subtitle = new Label(this instanceof OchreLaunchPane ? "正在准备游戏环境，启动后可查看实时日志" : "任务完成后会自动更新实例列表"); subtitle.getStyleClass().add("subtitle-label");
            VBox tasks = new VBox(taskListPane); tasks.setPadding(new Insets(12)); tasks.getStyleClass().add("ochre-server-controls"); VBox.setVgrow(taskListPane, Priority.ALWAYS); VBox.setVgrow(tasks, Priority.ALWAYS);
            center.setSpacing(12); center.getChildren().setAll(lblTitle, subtitle, tasks);
        }

        HBox bottom = new HBox();
        bottom.setAlignment(Pos.CENTER_LEFT);
        bottom.setPadding(new Insets(0, 8, 8, 8));
        bottom.setSpacing(8);
        this.setBottom(bottom);
        {
            lblProgress = new Label();
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            btnCancel = new JFXButton(i18n("button.cancel"));
            btnCancel.getStyleClass().add("dialog-cancel");
            JFXButton background = new JFXButton(this instanceof OchreLaunchPane ? "后台启动" : "静默安装"); background.setGraphic(org.jackhuang.hmcl.ui.SVG.DOWNLOAD.createIcon(18));
            background.setOnAction(event -> { if (backgroundEntry != null) { silent = true; fireEvent(new DialogCloseEvent()); OchreTasks.SILENT_REVISION.set(OchreTasks.SILENT_REVISION.get() + 1); } });
            btnCancel.setGraphic(org.jackhuang.hmcl.ui.SVG.CLOSE.createIcon(18));
            bottom.getChildren().setAll(lblProgress, spacer, background, btnCancel);
        }

        setCancel(cancel);

        btnCancel.setDisable(!onCancel.isEnabled());
        btnCancel.setOnAction(e -> {
            if (executor != null)
                executor.cancel();
            onCancel.accept(this);
        });

        speedEventHandler = FetchTask.SPEED_EVENT.registerWeak(speedEvent -> {
            if (this instanceof OchreLaunchPane) return;
            String message = I18n.formatSpeed(speedEvent.getSpeed());
            Platform.runLater(() -> lblProgress.setText(message));
        });

        onEscPressed(this, btnCancel::fire);
    }

    public void setExecutor(@org.jetbrains.annotations.Nullable TaskExecutor executor) {
        setExecutor(executor, true);
    }

    public void setExecutor(@org.jetbrains.annotations.Nullable TaskExecutor executor, boolean autoClose) {
        if (backgroundEntry != null) { OchreTasks.ACTIVE.remove(backgroundEntry); backgroundEntry = null; }
        this.executor = executor;

        if (executor != null) {
            taskListPane.setExecutor(executor);
            if (backgroundEntry != null) OchreTasks.ACTIVE.remove(backgroundEntry);
            var registered = OchreTasks.add(this instanceof OchreLaunchPane, () -> java.util.Objects.requireNonNullElse(getTitle(), "后台任务"), () -> { if (silent && backgroundEntry != null) { silent = false; org.jackhuang.hmcl.ui.construct.OchreTasks.reopen(this); } });
            backgroundEntry = registered;
            executor.addTaskListener(new TaskListener() {
                @Override public void onStop(boolean success, TaskExecutor completed) { Platform.runLater(() -> { OchreTasks.ACTIVE.remove(registered); if (backgroundEntry == registered) backgroundEntry = null; if (silent) org.jackhuang.hmcl.server.ServerManager.notice(success ? "任务完成：" + getTitle() : "任务未完成：" + getTitle()); }); }
            });

            if (autoClose)
                executor.addTaskListener(new TaskListener() {
                    @Override
                    public void onStop(boolean success, TaskExecutor executor) {
                        Platform.runLater(() -> {
                            if (TaskExecutorDialogPane.this.executor != executor) return;
                            fireEvent(new DialogCloseEvent());

                            if (Controllers.AUTO_TRIM_HEAP) {
                                // Trim heap memory when tasks finish, allowing GC to return memory to the OS faster,
                                // prevent excessive peak memory from consuming system resources.
                                Platform.runLater(Controllers::trimHeap);
                            }
                        });
                    }
                });
        }
    }

    public StringProperty titleProperty() {
        return lblTitle.textProperty();
    }

    public String getTitle() {
        return lblTitle.getText();
    }

    public void setTitle(String currentState) {
        lblTitle.setText(currentState);
    }

    public void setCancel(TaskCancellationAction onCancel) {
        this.onCancel = onCancel;

        runInFX(() -> btnCancel.setDisable(onCancel == null));
    }
}
