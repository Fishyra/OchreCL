package org.jackhuang.hmcl.server;

import com.jfoenix.controls.*;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNullByDefault;
import java.util.function.Consumer;

/// Configures CLI-backed server services with consent before first-time installation.
@NotNullByDefault
public final class ServerServicesPane extends VBox {
    /// Creates server-scoped inputs; passwords and relay tokens remain in memory only.
    public ServerServicesPane(ServerInstance server) {
        super(10); setPadding(new Insets(14)); getStyleClass().add("ochre-server-controls");
        CheckBox external = new JFXCheckBox("允许其他设备通过网络访问 SFTP"); external.setWrapText(true); external.setSelected(server.settings.getProperty("service.sftp.bind", "127.0.0.1").equals("0.0.0.0"));
        java.util.concurrent.atomic.AtomicBoolean network = new java.util.concurrent.atomic.AtomicBoolean(external.isSelected()); external.selectedProperty().addListener((observable, before, selected) -> network.set(selected));
        TextField sftpPort = field(server.settings.getProperty("service.sftp.port", "2022"), "SFTP 端口");
        TextField user = field(server.settings.getProperty("service.sftp.user", "ochre"), "SFTP 用户名"); PasswordField password = new JFXPasswordField(); password.setPromptText("SFTP 密码（至少 8 位，不保存）");
        ServerToolSwitch sftp = new ServerToolSwitch("SFTP · rclone", new ServerToolSwitch.Tool() {
            /// Detects the managed native binary on a worker thread.
            public boolean available() { return ServerToolInstaller.available(ServerToolInstaller.Tool.RCLONE); }
            /// Installs only after the player's explicit confirmation.
            public void install(Consumer<String> progress) throws Exception { ServerToolInstaller.install(ServerToolInstaller.Tool.RCLONE, progress); }
            /// Starts with the configuration captured on the UI thread.
            public void enable() throws Exception { var input = snapshot(sftpPort, user, password); ServerServices.sftp(server, network.get() ? "0.0.0.0" : "127.0.0.1", port(input[0]), input[1], input[2]); }
            /// Stops the owned SFTP child only.
            public void disable() throws Exception { ServerServices.stop(server, ServerToolInstaller.Tool.RCLONE); }
        });
        ServerToolSwitch tunnel = OpenFrpControls.create(() -> Integer.parseInt(server.settings.getProperty("port", "25565")),
            selected -> ServerServices.openFrp(server, selected), () -> {
                try { ServerServices.stop(server, ServerToolInstaller.Tool.OPENFRP); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            });
        sftp.setSelected(ServerServices.running(server, ServerToolInstaller.Tool.RCLONE)); tunnel.setSelected(ServerServices.running(server, ServerToolInstaller.Tool.OPENFRP));
        Label local = new Label("默认仅本机访问；开启网络访问后，其他设备可通过此电脑的地址与端口连接。目录为此服务器文件夹，密码仅用于本次启动。"); local.setWrapText(true);
        Label relay = new Label("开启后登录 OpenFrp，选择本机端口匹配的 TCP 隧道。"); relay.setWrapText(true);
        var refresh = new javafx.animation.Timeline(new javafx.animation.KeyFrame(javafx.util.Duration.seconds(1), event -> {
            if (!sftp.isDisabled()) sftp.setSelected(ServerServices.running(server, ServerToolInstaller.Tool.RCLONE));
            if (!tunnel.isDisabled()) tunnel.setSelected(ServerServices.running(server, ServerToolInstaller.Tool.OPENFRP));
        }));
        refresh.setCycleCount(javafx.animation.Animation.INDEFINITE);
        sceneProperty().addListener((observable, previous, scene) -> { if (scene == null) refresh.stop(); else refresh.play(); });
        getChildren().setAll(new Label("文件传输"), sftpPort, user, password, external, local, sftp, new Label("内网穿透"), relay, tunnel);
    }
    /// Captures JavaFX text fields on their owning thread without accessing controls on an I/O thread.
    private static String[] snapshot(TextInputControl... fields) throws Exception {
        var result = new java.util.concurrent.CompletableFuture<String[]>();
        javafx.application.Platform.runLater(() -> result.complete(java.util.Arrays.stream(fields).map(TextInputControl::getText).toArray(String[]::new)));
        return result.get();
    }
    /// Converts a port input into a validated integer.
    private static int port(String text) throws java.io.IOException {
        try { int value = Integer.parseInt(text.trim()); ServerServices.checkPort(value); return value; }
        catch (NumberFormatException exception) { throw new java.io.IOException("端口必须是数字"); }
    }
    /// Creates a compact labelled input using the launcher's controls.
    private static TextField field(String value, String prompt) { var field = new JFXTextField(value); field.setPromptText(prompt); return field; }
}
