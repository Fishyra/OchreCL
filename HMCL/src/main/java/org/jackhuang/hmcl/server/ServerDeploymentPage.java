package org.jackhuang.hmcl.server;

import com.jfoenix.controls.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jetbrains.annotations.NotNullByDefault;
import java.util.*;

/// Build selection beside actual server deployment settings, followed by an independent progress overlay.
@NotNullByDefault
public final class ServerDeploymentPage extends BorderPane {
    /// Exact immutable builds available for the chosen game version.
    private final ListView<ServerCatalog.Artifact> builds = new JFXListView<>();
    /// Status for provider/build failures.
    private final org.jackhuang.hmcl.ui.construct.SpinnerPane loading = new org.jackhuang.hmcl.ui.construct.SpinnerPane();
    /// Status of the selected provider request.
    private final Label status = new Label("正在获取构建…");
    /// Builds configuration before any core binary is downloaded.
    public ServerDeploymentPage(String core, String version, Runnable back) {
        this(core, version, back, () -> ServerCatalog.builds(core, version));
    }
    /// Reuses the full deployment form for a dedicated provider's selected immutable core builds.
    public ServerDeploymentPage(String core, String version, Runnable back, java.util.concurrent.Callable<@org.jetbrains.annotations.Unmodifiable List<ServerCatalog.Artifact>> source) {
        getStyleClass().add("ochre-server-download");
        setPadding(new Insets(6));
        Label heading = new Label(core + " · Minecraft " + version); heading.setWrapText(true); heading.setMinWidth(0); status.setWrapText(true);
        loading.setContent(builds); loading.showSpinner(); loading.setMinSize(0, 0);
        VBox left = new VBox(10, action("← 返回版本列表", back), heading, status, loading); VBox.setVgrow(loading, Priority.ALWAYS); left.setMinWidth(160);
        left.setPadding(new Insets(14)); left.getStyleClass().add("ochre-deployment-panel"); builds.setCellFactory(list -> new com.jfoenix.controls.JFXListCell<>()); builds.getStyleClass().add("jfx-list-view-float"); builds.setMinHeight(0); builds.getStyleClass().add("ochre-deployment-builds"); roundPanel(left);
        VBox fields = new VBox(12); fields.setPadding(new Insets(18)); fields.getStyleClass().add("ochre-server-controls");
        Map<String, TextInputControl> values = new LinkedHashMap<>();
        input(fields, values, "name", "服务器名称", core + " " + version);
        input(fields, values, "port", "主端口", "25565"); input(fields, values, "memory", "最大内存（MiB）", "2048");
        input(fields, values, "max-players", "最大玩家数", "20"); input(fields, values, "view-distance", "视距（区块）", "10"); input(fields, values, "simulation-distance", "模拟距离（区块）", "10");
        CheckBox online = new JFXCheckBox("启用正版验证"); online.setSelected(true);
        CheckBox restart = new JFXCheckBox("每天自动保存并重启"); input(fields, values, "restart.time", "重启时间（本地时间 HH:mm）", "05:00");
        TextArea jvm = new JFXTextArea(); jvm.setPromptText("每行一项，例如 -XX:+UseG1GC"); jvm.setPrefRowCount(3); values.put("jvm", jvm);
        fields.getChildren().addAll(online, restart, new Label("Java 参数"), jvm);
        VBox serviceFields = new VBox(10);
        CheckBox networkSftp = new JFXCheckBox("允许其他设备通过网络访问 SFTP"); networkSftp.setWrapText(true);
        ServerToolSwitch sftp = plannedTool("部署后启用 SFTP", ServerToolInstaller.Tool.RCLONE);
        java.util.concurrent.atomic.AtomicReference<OpenFrpAccount.@org.jetbrains.annotations.Nullable Tunnel> selectedTunnel = new java.util.concurrent.atomic.AtomicReference<>();
        ServerToolSwitch tunnel = OpenFrpControls.create(() -> Integer.parseInt(values.get("port").getText().trim()), selectedTunnel::set, () -> selectedTunnel.set(null));
        input(serviceFields, values, "sftp.port", "SFTP 本机端口", "2022"); input(serviceFields, values, "sftp.user", "SFTP 用户名", "ochre");
        PasswordField sftpPassword = new JFXPasswordField(); sftpPassword.setPromptText("SFTP 密码（不保存）");


        serviceFields.getChildren().addAll(sftpPassword, networkSftp, sftp, tunnel);
        TitledPane serviceOptions = new TitledPane("SFTP 与内网穿透", serviceFields); serviceOptions.setExpanded(false); fields.getChildren().add(serviceOptions);
        CheckBox eula = new JFXCheckBox("我已阅读并同意 Minecraft EULA"); eula.setWrapText(true);
        Hyperlink read = new Hyperlink("阅读 EULA ↗"); read.setOnAction(event -> FXUtils.openLink("https://www.minecraft.net/eula"));
        JFXButton deploy = action("部署 →", () -> {
            var artifact = builds.getSelectionModel().getSelectedItem(); if (artifact == null) return;
            Properties options = new Properties(); values.forEach((key, field) -> options.setProperty(key, field.getText().strip()));
            options.setProperty("online-mode", Boolean.toString(online.isSelected())); options.setProperty("restart.enabled", Boolean.toString(restart.isSelected())); options.setProperty("eula", Boolean.toString(eula.isSelected()));
            boolean enableSftp = sftp.isSelected(), enableTunnel = tunnel.isSelected(), externalSftp = networkSftp.isSelected();
            String password = sftpPassword.getText();
            OpenFrpAccount.@org.jetbrains.annotations.Nullable Tunnel plannedTunnel = selectedTunnel.get();
            Properties service = new Properties();
            for (String key : List.of("sftp.port", "sftp.user")) { service.setProperty(key, options.getProperty(key)); options.remove(key); }
            try { ServerConfiguration.validate(options); } catch (Exception exception) { status.setText(exception.getMessage()); return; }
            ServerTaskDialog.show("部署 " + core + " " + version, progress -> {
                var installed = ServerCatalog.deploy(artifact, options.getProperty("name"), options, progress);
                if (enableSftp) try { ServerServices.sftp(installed, externalSftp ? "0.0.0.0" : "127.0.0.1", Integer.parseInt(service.getProperty("sftp.port")), service.getProperty("sftp.user"), password); } catch (Exception exception) { progress.accept("SFTP 未启用：" + exception.getMessage()); ServerManager.notice(ServerManager.NoticeKind.ERROR, "服务器已部署，但 SFTP 启动失败：" + exception.getMessage()); }
                if (enableTunnel) try { if (plannedTunnel == null) throw new java.io.IOException("请重新登录 OpenFrp"); ServerServices.openFrp(installed, plannedTunnel); } catch (Exception exception) { progress.accept("内网穿透未启用：" + exception.getMessage()); ServerManager.notice(ServerManager.NoticeKind.ERROR, "服务器已部署，但穿透启动失败：" + exception.getMessage()); }
                return installed;
            }, server -> {
                ServersPage.SELECTED.set(server); Controllers.navigate(ServersPage.getInstance()); ServerManager.notice("服务器部署完成：" + server);
            });
        }); deploy.getStyleClass().add("ochre-primary-action"); deploy.disableProperty().bind(eula.selectedProperty().not().or(builds.getSelectionModel().selectedItemProperty().isNull()));
        ScrollPane scroll = new ScrollPane(fields); scroll.setFitToWidth(true); scroll.setMinSize(0, 0); scroll.getStyleClass().add("ochre-deployment-form");
        Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
        HBox actions = new HBox(12, read, gap, deploy); actions.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox footer = new VBox(8, eula, actions); footer.setPadding(new Insets(14, 18, 14, 18)); footer.getStyleClass().addAll("ochre-server-controls", "ochre-deployment-footer");
        BorderPane configuration = new BorderPane(scroll); configuration.setBottom(footer); configuration.setMinWidth(200); configuration.getStyleClass().add("ochre-deployment-panel"); roundPanel(configuration);
        SplitPane split = new SplitPane(left, configuration); split.getStyleClass().add("ochre-deployment-split"); split.setDividerPositions(.34); setCenter(split);
        loading.setOnFailedAction(event -> loadBuilds(source)); loadBuilds(source);
    }
    /// Uses the normal download spinner for each retry rather than leaving a textual loading label.
    private void loadBuilds(java.util.concurrent.Callable<@org.jetbrains.annotations.Unmodifiable List<ServerCatalog.Artifact>> source) {
        loading.setFailedReason(null); loading.showSpinner();
        Schedulers.io().execute(() -> {
            try { var items = source.call(); Platform.runLater(() -> { loading.hideSpinner(); builds.getItems().setAll(items); builds.getSelectionModel().selectFirst(); status.setText(items.isEmpty() ? "没有可用构建" : "已自动选择推荐构建"); }); }
            catch (Exception exception) { Platform.runLater(() -> { loading.setLoading(false); loading.setFailedReason("获取失败：" + exception.getMessage()); }); }
        });
    }
    /// Clips scrolling children to the same rounded outline as their containing panel.
    private static void roundPanel(Region panel) {
        var clip = new javafx.scene.shape.Rectangle(); clip.widthProperty().bind(panel.widthProperty()); clip.heightProperty().bind(panel.heightProperty());
        clip.setArcWidth(40); clip.setArcHeight(40); panel.setClip(clip);
    }
    /// Installs an optional tool only after consent; service startup waits until deployment succeeds.
    private static ServerToolSwitch plannedTool(String title, ServerToolInstaller.Tool tool) {
        return new ServerToolSwitch(title, new ServerToolSwitch.Tool() {
            /// Detects the verified launcher-managed binary.
            public boolean available() { return ServerToolInstaller.available(tool); }
            /// Downloads only after the confirmation dialog's affirmative action.
            public void install(java.util.function.Consumer<String> progress) throws Exception { ServerToolInstaller.install(tool, progress); }
            /// Marks this optional service for startup after successful deployment.
            public void enable() {}
            /// Cancels planned startup without changing other server services.
            public void disable() {}
        });
    }
    /// Adds a named compact setting without creating a platform dialog.
    private static void input(VBox box, Map<String, TextInputControl> values, String key, String label, String initial) { TextField field = new JFXTextField(initial); values.put(key, field); box.getChildren().addAll(new Label(label), field); }
    /// Creates a styled desktop action.
    private static JFXButton action(String text, Runnable action) { JFXButton button = new JFXButton(text); button.setOnAction(event -> action.run()); return button; }
}
