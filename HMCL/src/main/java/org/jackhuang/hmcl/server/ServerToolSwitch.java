package org.jackhuang.hmcl.server;

import com.jfoenix.controls.JFXCheckBox;
import javafx.application.Platform;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jetbrains.annotations.NotNullByDefault;
import java.util.function.Consumer;

/// Requires explicit installation consent before enabling a missing external server tool.
@NotNullByDefault
public final class ServerToolSwitch extends JFXCheckBox {
    /// External-tool operations are supplied by the platform-specific integration.
    @NotNullByDefault
    public interface Tool {
        /// Returns whether a usable executable is available; called off the UI thread.
        boolean available() throws Exception;
        /// Downloads and verifies the executable, reporting progress; called only after consent.
        void install(Consumer<String> progress) throws Exception;
        /// Starts the configured service; called off the UI thread after availability is verified.
        void enable() throws Exception;
        /// Stops the service owned by this integration; called off the UI thread.
        void disable() throws Exception;
    }
    /// Name used in confirmation and error messages.
    private final String title;
    /// Service operations bound to this switch.
    private final Tool tool;

    /// Creates a disabled-by-default service switch without initiating downloads or network exposure.
    public ServerToolSwitch(String title, Tool tool) {
        super(title); this.title = title; this.tool = tool;
        setWrapText(true);
        setOnAction(event -> {
            boolean requested = isSelected();
            setSelected(!requested); setDisable(true);
            Schedulers.io().execute(() -> {
                try {
                    if (!requested) { tool.disable(); finish(false); return; }
                    if (tool.available()) { tool.enable(); finish(true); return; }
                    Platform.runLater(this::askInstall);
                } catch (Exception exception) { failed(exception); }
            });
        });
    }
    /// Shows consent only for a missing executable; cancellation restores an available, unchecked switch.
    private void askInstall() {
        Controllers.confirm("未检测到 " + title + " 工具。是否自动下载并安装？安装后将按当前配置启用。", "安装 " + title,
            () -> ServerTaskDialog.show("安装 " + title, progress -> {
                tool.install(progress);
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (!tool.available()) throw new java.io.IOException("安装完成后仍无法检测到可用工具");
                tool.enable();
                if (Thread.currentThread().isInterrupted()) { tool.disable(); throw new InterruptedException(); }
                return Boolean.TRUE;
            }, enabled -> finish(enabled), () -> finish(false)),
            () -> finish(false));
    }
    /// Restores interaction and publishes only the actual service state.
    private void finish(boolean enabled) {
        Platform.runLater(() -> { setSelected(enabled); setDisable(false); });
    }
    /// Leaves the service disabled when detection, installation or startup fails.
    private void failed(Exception exception) {
        finish(false); ServerManager.notice(ServerManager.NoticeKind.ERROR, title + " 启用失败：" + exception.getMessage());
    }
}
