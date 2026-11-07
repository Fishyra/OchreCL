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
    /// Explicit user cancellation also covers the interval before the worker starts.
    private final AtomicBoolean cancelled = new AtomicBoolean();
    /// Current task thread, absent until execution begins.
    private volatile @Nullable Thread worker;
    /// Current phase and actual download progress.
    private final Label phase = new Label("准备中…");
    /// Progress remains indeterminate when the provider has no total size.
    private final ProgressBar progress = new ProgressBar(-1);
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
        super(12); setPadding(new Insets(22)); setPrefSize(660, 380); setMinSize(300, 220); setMaxSize(740, 460);
        getStyleClass().addAll("jfx-dialog-layout", "ochre-server-task");
        Label heading = new Label(title); heading.getStyleClass().add("ochre-instance-title"); phase.setWrapText(true);
        progress.setMaxWidth(Double.MAX_VALUE); output.setEditable(false); output.getStyleClass().add("ochre-runtime-console");
        VBox.setVgrow(output, Priority.ALWAYS);
        cancel.setOnAction(event -> { cancelled.set(true); phase.setText("正在取消并清理…"); cancel.setDisable(true); var thread = worker; if (thread != null) thread.interrupt(); });
        getChildren().setAll(heading, phase, progress, output, cancel);
        Schedulers.io().execute(() -> {
            worker = Thread.currentThread();
            try {
                if (cancelled.get()) throw new InterruptedException();
                T result = operation.run(this::report);
                Platform.runLater(() -> {
                    phase.setText("操作完成"); progress.setProgress(1);
                    cancel.setDisable(false); cancel.setText("完成");
                    cancel.setOnAction(event -> { fireEvent(new DialogCloseEvent()); completed.accept(result); });
                });
            } catch (Exception exception) {
                boolean stopped = cancelled.get() || exception instanceof InterruptedException;
                Platform.runLater(() -> {
                    phase.setText(stopped ? "操作已取消" : "操作失败：" + exception.getMessage()); progress.setProgress(0);
                    cancel.setDisable(false); cancel.setText("关闭"); cancel.setOnAction(event -> { fireEvent(new DialogCloseEvent()); aborted.run(); });
                });
            } finally { worker = null; Thread.interrupted(); }
        });
    }
    /// Publishes bounded diagnostic lines and measured download percentages.
    private void report(String text) {
        if (cancelled.get()) throw new java.util.concurrent.CancellationException();
        Platform.runLater(() -> {
            phase.setText(text);
            var match = PERCENT.matcher(text); progress.setProgress(match.find() ? Math.min(1, Integer.parseInt(match.group(1)) / 100.0) : -1);
            String log = output.getText() + text + "\n"; output.setText(log.length() > 16000 ? log.substring(log.length() - 16000) : log); output.setScrollTop(Double.MAX_VALUE);
        });
    }
}
