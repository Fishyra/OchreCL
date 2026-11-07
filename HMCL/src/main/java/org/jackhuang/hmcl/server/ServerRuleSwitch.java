package org.jackhuang.hmcl.server;

import com.jfoenix.controls.JFXToggleButton;
import javafx.application.Platform;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.task.Schedulers;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.util.function.BooleanSupplier;

/// One live server-rule switch that reads actual values and confirms changes instead of assuming a default.
@NotNullByDefault
public final class ServerRuleSwitch extends HBox {
    /// Selected installation owns the command stream.
    private final ServerInstance server;
    /// Legacy rule key, translated only when commands are sent.
    private final String key;
    /// Resolves automatic/manual naming on the JavaFX thread.
    private final BooleanSupplier modern;
    /// Switch remains unavailable while a rule is unknown or an operation is pending.
    private final JFXToggleButton toggle = new JFXToggleButton();
    /// Explains unknown, pending and confirmed states.
    private final Label status = new Label("等待读取");
    /// Ignores stale replies after navigation, stop or a naming-mode change.
    private long generation;
    /// Last confirmed server value; absence is displayed explicitly.
    private @Nullable Boolean confirmed;
    /// Weakly subscribed lifecycle listener does not retain old settings pages.
    private final ChangeListener<String> lifecycle = (observable, before, after) -> refresh();

    /// Creates a rounded toggle row and loads a value only when attached to the visible workspace.
    public ServerRuleSwitch(ServerInstance server, String title, String key, BooleanSupplier modern) {
        super(12); this.server = server; this.key = key; this.modern = modern;
        setPadding(new Insets(14)); setAlignment(Pos.CENTER_LEFT); setPrefWidth(245); getStyleClass().add("ochre-server-controls");
        Label heading = new Label(title); heading.setWrapText(true); heading.setMinWidth(0); status.getStyleClass().add("subtitle-label"); status.setWrapText(true);
        VBox captions = new VBox(5, heading, status); captions.setMinWidth(0); HBox.setHgrow(captions, Priority.ALWAYS);
        toggle.setMinSize(60, 38); toggle.setPrefSize(60, 38); toggle.setMaxSize(60, 38); toggle.setAccessibleText(title); toggle.setDisable(true);
        toggle.setOnAction(event -> change(toggle.isSelected())); getChildren().setAll(captions, toggle);
        sceneProperty().addListener((observable, before, scene) -> { if (scene == null) generation++; else refresh(); });
        server.session.stateProperty().addListener(new WeakChangeListener<>(lifecycle));
    }
    /// Reads the current rule after entering the page or changing naming mode.
    public void refresh() { update(null); }
    /// Sends a requested switch value only when the server is actually ready.
    private void change(boolean enabled) { update(enabled); }
    /// Applies a change if present, then always reads back the real server state.
    private void update(@Nullable Boolean desired) {
        long request = ++generation;
        if (getScene() == null) return;
        toggle.setDisable(true);
        if (!server.session.isRunning() || !server.session.stateProperty().get().equals("运行中")) { confirmed = null; status.setText("服务器未就绪"); return; }
        boolean naming = modern.getAsBoolean(); status.setText(desired == null ? "读取中…" : "正在应用…");
        Schedulers.io().execute(() -> {
            try {
                if (desired != null) ServerCommands.applyRule(server, key, desired, naming);
                boolean actual = ServerCommands.readRule(server, key, naming);
                Platform.runLater(() -> {
                    if (request != generation || getScene() == null) return;
                    confirmed = actual; toggle.setSelected(actual); toggle.setDisable(false); status.setText(actual ? "已开启" : "已关闭");
                    if (desired != null && actual != desired) ServerManager.notice(ServerManager.NoticeKind.WARNING, "服务器未应用此更改，已恢复实际状态");
                });
            } catch (Exception exception) {
                Platform.runLater(() -> {
                    if (request != generation || getScene() == null) return;
                    if (confirmed != null) toggle.setSelected(confirmed); toggle.setDisable(true); status.setText("读取失败 · 可刷新重试");
                    if (desired != null) ServerManager.notice(ServerManager.NoticeKind.ERROR, "更改失败：" + exception.getMessage());
                });
            }
        });
    }
}
