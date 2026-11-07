package org.jackhuang.hmcl.server;

import com.jfoenix.controls.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/// Launcher-styled, cancellable OpenFrp account authorization and compatible tunnel selection.
@NotNullByDefault
public final class OpenFrpLoginDialog extends VBox {
    /// Pending background authorization, cancelled when this overlay detaches.
    private @Nullable Future<?> pending;
    /// Prevents late worker replies from reopening a cancelled login.
    private volatile boolean closed;
    /// Ephemeral authorization session owned only by this overlay.
    private final OpenFrpAccount account = new OpenFrpAccount();
    /// Builds the login overlay; account passwords are entered exclusively on the official website.
    public OpenFrpLoginDialog(int port, Consumer<OpenFrpAccount.Tunnel> selected, Runnable cancelled) {
        super(14); setPadding(new Insets(24)); setPrefWidth(520); setMaxWidth(560);
        getStyleClass().addAll("jfx-dialog-layout", "ochre-service-dialog");
        Label heading = new Label("登录 OpenFrp"); heading.setStyle("-fx-font-size: 22px;");
        Label description = new Label("使用自己的 OpenFrp 账号在官方页面登录，完成授权后在这里选择隧道。密码不会保存在启动器中。"); description.setWrapText(true);
        Label status = new Label("尚未登录"); status.setWrapText(true);
        ComboBox<OpenFrpAccount.Tunnel> tunnels = new JFXComboBox<>(); tunnels.setMaxWidth(Double.MAX_VALUE); tunnels.setPromptText("选择隧道 · 本机端口 " + port);
        JFXButton login = new JFXButton("登录 OpenFrp ↗"); login.getStyleClass().add("ochre-primary-action");
        JFXButton use = new JFXButton("使用此隧道"); use.disableProperty().bind(tunnels.valueProperty().isNull());
        JFXButton close = new JFXButton("取消");
        Hyperlink register = new Hyperlink("没有账号？前往 OpenFrp 官方注册 ↗"); register.setOnAction(event -> FXUtils.openLink(OpenFrpAccount.PORTAL));
        Hyperlink manage = new Hyperlink("管理或创建隧道 ↗"); manage.setOnAction(event -> FXUtils.openLink(OpenFrpAccount.PORTAL));
        boolean[] accepted = {false};
        login.setOnAction(event -> {
            login.setDisable(true); status.setText("正在请求官方授权…");
            pending = Schedulers.io().submit(() -> {
                try {
                    String url = account.begin(); if (Thread.currentThread().isInterrupted()) return; Platform.runLater(() -> { if (closed) return; FXUtils.openLink(url); status.setText("请在官方页面登录并授权，正在等待…"); });
                    long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(5);
                    while (System.nanoTime() < deadline) {
                        Thread.sleep(5000);
                        if (!account.poll()) continue;
                        var result = account.tunnels(port);
                        if (Thread.currentThread().isInterrupted()) return;
                        Platform.runLater(() -> { if (closed) return; tunnels.getItems().setAll(result); tunnels.getSelectionModel().selectFirst(); status.setText(result.isEmpty() ? "登录成功。请先在官方面板创建 TCP 隧道，本地地址 127.0.0.1，端口 " + port + "，然后重新登录刷新。" : "登录成功，请选择隧道"); login.setText("重新登录 / 刷新"); login.setDisable(false); });
                        return;
                    }
                    throw new java.io.IOException("授权已超时，请重新登录");
                } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                catch (Exception exception) { Platform.runLater(() -> { if (closed) return; status.setText("登录未完成，请重试或检查网络连接"); login.setDisable(false); }); }
                finally { account.clear(); }
            });
        });
        use.setOnAction(event -> { @Nullable var tunnel = tunnels.getValue(); if (tunnel == null) return; accepted[0] = true; selected.accept(tunnel); fireEvent(new DialogCloseEvent()); });
        close.setOnAction(event -> fireEvent(new DialogCloseEvent()));
        sceneProperty().addListener((observable, before, scene) -> { if (scene == null && before != null) { closed = true; if (pending != null) pending.cancel(true); account.clear(); if (!accepted[0]) cancelled.run(); } });
        HBox footer = new HBox(12, use, close); footer.setPadding(new Insets(10, 0, 0, 0));
        getChildren().setAll(heading, description, login, status, tunnels, manage, register, footer);
    }
    /// Opens the account dialog in the existing launcher window.
    public static void show(int port, Consumer<OpenFrpAccount.Tunnel> selected, Runnable cancelled) { Controllers.dialog(new OpenFrpLoginDialog(port, selected, cancelled)); }
}
