package org.jackhuang.hmcl.server;

import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/// A main-window deployment/task overlay with real phase output, bounded history and cancellation.
@NotNullByDefault
public final class ServerTaskDialog<T> extends VBox {
    /// One checked operation, executed off the UI thread.
    @NotNullByDefault
    @FunctionalInterface public interface Work<T> { /// Runs the operation and emits actual phase/progress messages.
        T run(Consumer<String> progress) throws Exception;
    }
    /// Registry entry keeps deployment visible in the global floating task list.
    private org.jackhuang.hmcl.ui.construct.OchreTasks.@Nullable Entry taskEntry;
    /// A hidden task may reopen its original progress overlay.
    private boolean hidden;
    /// Explicit user cancellation also covers the interval before the worker starts.
    private final AtomicBoolean cancelled = new AtomicBoolean();
    /// Current task thread, absent until execution begins.
    private volatile @Nullable Thread worker;
    /// Explicit deployment phases, advanced by actual operation milestones.
    private final VBox steps = new VBox(6);
    /// Step labels retain completed/current state independently from verbose log output.
    private final java.util.List<Label> stepLabels = new java.util.ArrayList<>();
    /// Index of the last entered deployment stage.
    private int stageIndex;
    /// True only for server deployment operations, rather than unrelated backup tasks.
    private final boolean deployment;
    /// Current phase and actual download progress.
    private final Label phase = new Label("准备中…");
    /// Progress remains indeterminate when the provider has no total size.
    private final ProgressBar progress = new com.jfoenix.controls.JFXProgressBar(-1);
    /// Bounded diagnostic output within the dialog.
    private final TextArea output = new TextArea();
    /// Changes to a close action after the operation terminates.
    private final JFXButton cancel = new JFXButton("取消");
    /// Matches real upstream percentage updates; no synthetic time-based progress.
    private static final Pattern PERCENT = Pattern.compile("(\\d{1,3})%");

    /// Shows a cancellable operation as an independent overlay without opening a native window.
    public static <T> void show(String title, Work<T> operation, Consumer<T> completed) {
        show(title, operation, completed, () -> {});
    }
    /// Also notifies the caller when a failed or cancelled operation is dismissed.
    public static <T> void show(String title, Work<T> operation, Consumer<T> completed, Runnable aborted) {
        Controllers.dialog(new ServerTaskDialog<>(title, operation, completed, aborted));
    }
    /// Creates the overlay and starts its background work only after all controls are configured.
    private ServerTaskDialog(String title, Work<T> operation, Consumer<T> completed, Runnable aborted) {
        super(12); deployment = title.startsWith("部署 ") || title.startsWith("导入 "); setPadding(new Insets(22)); setPrefSize(deployment ? 800 : 660, 460); setMinSize(300, 280); setMaxSize(960, 620);
        getStyleClass().addAll("jfx-dialog-layout", "ochre-server-task");
        Label heading = new Label(title); heading.setGraphic(org.jackhuang.hmcl.ui.SVG.OCHRE_SERVER.createIcon(26)); heading.getStyleClass().add("ochre-instance-title"); phase.setWrapText(true);
        progress.setMaxWidth(Double.MAX_VALUE); output.setEditable(false); output.getStyleClass().add("ochre-runtime-console");
        VBox.setVgrow(output, Priority.ALWAYS);
        cancel.setOnAction(event -> { cancelled.set(true); phase.setText("正在取消并清理…"); cancel.setDisable(true); var thread = worker; if (thread != null) thread.interrupt(); });
        phase.setGraphic(new org.jackhuang.hmcl.ui.decorator.LaunchOrbit());
        Label explanation = new Label("下载 → 验证 → 安装 → 写入配置"); explanation.getStyleClass().add("subtitle-label");
        Label consoleTitle = new Label("部署记录"); consoleTitle.setGraphic(org.jackhuang.hmcl.ui.SVG.INFO.createIcon(18));
        VBox details = new VBox(10, consoleTitle, output); details.setPadding(new Insets(12)); details.getStyleClass().add("ochre-server-controls"); VBox.setVgrow(details, Priority.ALWAYS); VBox.setVgrow(output, Priority.ALWAYS);
        cancel.setGraphic(org.jackhuang.hmcl.ui.SVG.CLOSE.createIcon(18)); HBox actions = new HBox(cancel); actions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT);
        if (deployment) {
            for (String name : title.startsWith("导入 ") ? new String[]{"复制已有服务端", "验证启动文件", "读取运行配置", "注册服务器"} : new String[]{"下载服务器核心", "校验文件", "安装运行环境", "写入配置与完成部署"}) { Label label = new Label(name); label.setWrapText(true); label.getStyleClass().add("ochre-step-row"); stepLabels.add(label); }
            steps.getChildren().setAll(stepLabels); updateSteps(0);
            Label caption = new Label("当前步骤进度"); caption.getStyleClass().add("ochre-step-caption");
            VBox stages = new VBox(10, steps, phase, caption, progress); stages.setMinWidth(220); stages.setPrefWidth(300); stages.setPadding(new Insets(10));
            HBox columns = new HBox(16, stages, details); HBox.setHgrow(details, Priority.ALWAYS); VBox.setVgrow(columns, Priority.ALWAYS);
            ScrollPane scroll = new ScrollPane(columns); scroll.setFitToWidth(true); scroll.setMinSize(0, 0); VBox.setVgrow(scroll, Priority.ALWAYS);
            getChildren().setAll(heading, scroll, actions);
        } else getChildren().setAll(heading, explanation, phase, progress, details, actions);
        taskEntry = org.jackhuang.hmcl.ui.construct.OchreTasks.add(() -> title, () -> { if (hidden) { hidden = false; org.jackhuang.hmcl.ui.construct.OchreTasks.reopen(this); } });
        JFXButton background = new JFXButton("后台部署"); background.setGraphic(org.jackhuang.hmcl.ui.SVG.DOWNLOAD.createIcon(18)); background.setOnAction(event -> { hidden = true; fireEvent(new DialogCloseEvent()); org.jackhuang.hmcl.ui.construct.OchreTasks.SILENT_REVISION.set(org.jackhuang.hmcl.ui.construct.OchreTasks.SILENT_REVISION.get() + 1); }); actions.getChildren().add(0, background);
        Schedulers.io().execute(() -> {
            worker = Thread.currentThread();
            try {
                if (cancelled.get()) throw new InterruptedException();
                T result = operation.run(this::report);
                Platform.runLater(() -> {
                    if (taskEntry != null) org.jackhuang.hmcl.ui.construct.OchreTasks.ACTIVE.remove(taskEntry); taskEntry = null; background.setDisable(true);
                    if (hidden) { completed.accept(result); org.jackhuang.hmcl.server.ServerManager.notice("操作完成：" + title); }
                    phase.setGraphic(org.jackhuang.hmcl.ui.SVG.CHECK_CIRCLE.createIcon(20)); phase.setText("操作完成"); progress.setProgress(1); if (deployment) updateSteps(4);
                    cancel.setDisable(false); cancel.setText("完成");
                    cancel.setOnAction(event -> { fireEvent(new DialogCloseEvent()); if (!hidden) completed.accept(result); });
                });
            } catch (Exception exception) {
                boolean stopped = cancelled.get() || exception instanceof InterruptedException;
                Platform.runLater(() -> {
                    if (taskEntry != null) org.jackhuang.hmcl.ui.construct.OchreTasks.ACTIVE.remove(taskEntry); taskEntry = null;
                    background.setDisable(true);
                    if (hidden) { hidden = false; org.jackhuang.hmcl.ui.construct.OchreTasks.reopen(this); }
                    phase.setGraphic(org.jackhuang.hmcl.ui.SVG.INFO.createIcon(20)); phase.setText(stopped ? "操作已取消" : "操作失败：" + exception.getMessage()); progress.setProgress(0);
                    cancel.setDisable(false); cancel.setText("关闭"); cancel.setOnAction(event -> { if (taskEntry != null) org.jackhuang.hmcl.ui.construct.OchreTasks.ACTIVE.remove(taskEntry); taskEntry = null; fireEvent(new DialogCloseEvent()); aborted.run(); });
                });
            } finally { worker = null; Thread.interrupted(); }
        });
    }
    /// Marks only phases that the worker actually reached; completion is never inferred from a timer.
    private void updateSteps(int current) {
        stageIndex = current;
        for (int i = 0; i < stepLabels.size(); i++) {
            Label label = stepLabels.get(i); label.setGraphic((i < current ? org.jackhuang.hmcl.ui.SVG.CHECK : i == current ? org.jackhuang.hmcl.ui.SVG.ARROW_FORWARD : org.jackhuang.hmcl.ui.SVG.INFO).createIcon(18));
            label.setOpacity(i > current ? .5 : 1); label.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("active"), i == current);
        }
    }
    /// Publishes bounded diagnostic lines and measured download percentages.
    private void report(String text) {
        if (cancelled.get()) throw new java.util.concurrent.CancellationException();
        Platform.runLater(() -> {
            if (text.startsWith("阶段:")) { updateSteps(Integer.parseInt(text.substring(3, 4))); return; }
            phase.setText(text);
            var match = PERCENT.matcher(text); progress.setProgress(match.find() ? Math.min(1, Integer.parseInt(match.group(1)) / 100.0) : -1);
            String log = output.getText() + text + "\n"; output.setText(log.length() > 16000 ? log.substring(log.length() - 16000) : log); output.setScrollTop(Double.MAX_VALUE);
        });
    }
}
