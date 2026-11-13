package org.jackhuang.hmcl.server;

import com.jfoenix.controls.JFXButton;
import javafx.beans.property.*;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.game.QuickPlayOption;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.nio.file.*;
import java.io.IOException;

/// Local server dashboard with a server switcher, live console, power controls and editable settings.
@NotNullByDefault
public final class ServersPage extends BorderPane implements DecoratorPage {
    /// Shared workspace avoids accumulating listeners and hidden console timers on repeated navigation.
    private static @Nullable ServersPage instance;
    /// Returns the single server workspace for the current launcher process.
    public static ServersPage getInstance() { if (instance == null) instance = new ServersPage(); return instance; }
    /// Current server shared with the compact join button and download add-on target.
    public static final ObjectProperty<@Nullable ServerInstance> SELECTED = new SimpleObjectProperty<>();
    /// Standalone navigation title.
    private final ReadOnlyObjectWrapper<State> state = new ReadOnlyObjectWrapper<>(State.fromTitle("联机服务器"));
    /// Currently rendered server; absent for the empty state.
    private @Nullable ServerInstance displayed;

    /// Creates the dashboard and reacts to added installations without losing the current selection.
    public ServersPage() {
        ServerManager.load(); ServerWindowSizing.install(this);
        getStyleClass().add("ochre-server-page");
        setPadding(new Insets(14));
        ComboBox<ServerInstance> switcher = new com.jfoenix.controls.JFXComboBox<>(); switcher.setItems(ServerManager.SERVERS);
        switcher.setPromptText("选择服务器");
        switcher.setMaxWidth(360);
        switcher.valueProperty().bindBidirectional(SELECTED);
        JFXButton download = new JFXButton("下载服务器");
        download.setOnAction(event -> { Controllers.getDownloadPage().showServerDownloads(); Controllers.navigate(Controllers.getDownloadPage()); });
        JFXButton importServer = new JFXButton("导入服务器"); importServer.setGraphic(org.jackhuang.hmcl.ui.SVG.FOLDER_COPY.createIcon(18)); importServer.setOnAction(event -> ServerImporter.show());
        JFXButton deleted = new JFXButton("已删除服务器"); deleted.setOnAction(event -> showDeleted());
        JFXButton diagnostics = new JFXButton("网络检测"); diagnostics.setGraphic(org.jackhuang.hmcl.ui.SVG.OCHRE_CONNECT.createIcon(18)); diagnostics.setOnAction(event -> { @Nullable var server = SELECTED.get(); if (server != null) ServerNetworkPane.show(server); else ServerManager.notice("请先选择服务器"); });
        FlowPane header = new FlowPane(12, 10, new Label("联机服务器"), switcher, download, importServer, deleted, diagnostics);
        header.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        header.setPadding(new Insets(0, 0, 14, 0));
        setTop(header);
        SELECTED.addListener((observable, oldValue, value) -> render(value));
        ServerManager.SERVERS.addListener((javafx.collections.ListChangeListener<ServerInstance>) change -> {
            if (SELECTED.get() == null && !ServerManager.SERVERS.isEmpty()) SELECTED.set(ServerManager.SERVERS.get(0));
        });
        if (SELECTED.get() == null && !ServerManager.SERVERS.isEmpty()) SELECTED.set(ServerManager.SERVERS.get(0));
        render(SELECTED.get());
    }
    /// Rebuilds controls only when a different installation is selected.
    private void render(@Nullable ServerInstance server) {
        displayed = server;
        if (server == null) { setCenter(new Label("暂无服务器 · 从下载页面安装原版、Paper、Purpur、Fabric 或 Forge")); return; }
        RuntimePanel console = new RuntimePanel(server.session);
        TextField command = new com.jfoenix.controls.JFXTextField(); command.setPromptText("输入服务器命令，不加 / ");
        JFXButton send = new JFXButton("发送");
        Runnable sendCommand = () -> {
            String text = command.getText();
            Schedulers.io().execute(() -> {
                try { ServerManager.command(server, text); }
                catch (IOException e) { ServerManager.notice(e.getMessage()); }
            });
            command.clear();
        };
        send.setOnAction(event -> sendCommand.run()); command.setOnAction(event -> sendCommand.run());
        HBox entry = new HBox(8, command, send); HBox.setHgrow(command, Priority.ALWAYS);
        VBox main = new VBox(8, new ServerStatusStrip(server), console, entry); main.setMinSize(0, 0); VBox.setVgrow(console, Priority.ALWAYS);
        ServerAddressPicker addressBar = new ServerAddressPicker(server);
        Label type = new Label(server.settings.getProperty("core") + " · Minecraft " + server.settings.getProperty("version")); type.setWrapText(true);
        Label status = new Label(); status.textProperty().bind(server.session.stateProperty());
        JFXButton start = new JFXButton("启动服务器"); start.getStyleClass().add("ochre-primary-action");
        start.textProperty().bind(javafx.beans.binding.Bindings.createStringBinding(
                () -> server.session.isRunning() ? "保存并停止服务器" : "启动服务器", server.session.stateProperty()));
        JFXButton restart = new JFXButton("重启"); restart.setOnAction(event -> ServerManager.stop(server, true));
        JFXButton force = new JFXButton("强制结束"); force.setOnAction(event -> Controllers.confirm("未保存的世界进度可能丢失，仍要强制结束吗？", "强制结束服务器", () -> {
            server.stopping = true; server.session.stop();
        }, null));
        JFXButton join = new JFXButton("加入服务器"); join.setOnAction(event -> join());
        TextField name = field(server.toString(), "服务器名称");
        TextField memory = field(server.settings.getProperty("memory", "2048"), "最大内存 MiB");
        TextField port = field(server.settings.getProperty("port", "25565"), "端口");
        TextField javaPath = field(server.settings.getProperty("java", ""), "自动选择 Java / 可执行文件路径");
        JFXButton chooseJava = new JFXButton("选择 Java"); chooseJava.setOnAction(event -> {
            FileChooser chooser = new FileChooser(); chooser.setTitle("选择 Java 可执行文件");
            var file = Controllers.showOpenDialog(chooser); if (file != null) javaPath.setText(file.toString());
        });
        CheckBox onlineMode = new com.jfoenix.controls.JFXCheckBox("启用正版验证（online-mode）");
        onlineMode.setWrapText(true);
        onlineMode.setSelected(!server.settings.getProperty("online-mode", "true").equals("false"));
        Label offlineNote = new Label("关闭正版验证仅用于可信的本地离线测试；不要将未验证的服务器直接公开。");
        offlineNote.setWrapText(true);
        JFXButton save = new JFXButton("保存设置"); save.setOnAction(event -> {
            if (server.busy || server.session.isRunning()) { ServerManager.notice("请停止服务器后修改设置"); return; }
            try {
                int ram = Integer.parseInt(memory.getText()), number = Integer.parseInt(port.getText());
                if (ram < 512 || ram > 65536 || number < 1 || number > 65535 || name.getText().isBlank()) throw new IllegalArgumentException();
                server.settings.setProperty("name", name.getText().strip()); server.settings.setProperty("memory", Integer.toString(ram));
                server.settings.setProperty("port", Integer.toString(number)); server.settings.setProperty("java", javaPath.getText().strip());
                server.settings.setProperty("online-mode", Boolean.toString(onlineMode.isSelected()));
                java.util.Properties properties = new java.util.Properties();
                var file = server.directory.resolve("server.properties");
                if (Files.isRegularFile(file)) try (var input = Files.newInputStream(file)) { properties.load(input); }
                properties.setProperty("server-port", Integer.toString(number));
                properties.setProperty("online-mode", Boolean.toString(onlineMode.isSelected()));
                try (var output = Files.newOutputStream(file)) { properties.store(output, "Updated by OchreCL"); }
                server.save();
                addressBar.refresh(); ServerManager.notice("服务器设置已保存");
            } catch (Exception e) { ServerManager.notice("设置无效：内存 512–65536 MiB，端口 1–65535，名称不能为空"); }
        });
        Hyperlink agreement = new Hyperlink("阅读 Minecraft EULA"); agreement.setOnAction(event -> FXUtils.openLink("https://www.minecraft.net/eula"));
        CheckBox eula = new com.jfoenix.controls.JFXCheckBox("我已阅读并同意 EULA"); eula.setWrapText(false); eula.setSelected(server.acceptedEula());
        start.setOnAction(event -> {
            if (server.session.isRunning()) { ServerManager.stop(server, false); return; }
            if (!eula.isSelected()) { ServerManager.notice("请先阅读并同意 Minecraft EULA"); return; }
            try {
                if (!server.acceptedEula()) Files.writeString(server.directory.resolve("eula.txt"), "eula=true\n");
                ServerManager.start(server);
            } catch (IOException e) { ServerManager.notice("无法保存 EULA：" + e.getMessage()); }
        });
        JFXButton folder = new JFXButton("打开服务器文件夹"); folder.setOnAction(event -> FXUtils.openFolder(server.directory));
        JFXButton remove = new JFXButton("删除服务器");
        remove.setOnAction(event -> {
            if (server.busy || server.session.isRunning()) { ServerManager.notice("请先停止服务器，等待保存完成后再删除"); return; }
            Controllers.confirm("将“" + server + "”从服务器列表移除？世界、模组和配置会保留在已删除目录中，可通过“已删除服务器”恢复。", "删除服务器", () -> {
                remove.setDisable(true);
                Schedulers.io().execute(() -> {
                    try { ServerManager.remove(server); }
                    catch (IOException exception) { ServerManager.notice("删除失败：" + exception.getMessage()); }
                    finally { javafx.application.Platform.runLater(() -> remove.setDisable(false)); }
                });
            }, null);
        });
        JFXButton services = new JFXButton("SFTP 与内网穿透"); services.setOnAction(event -> Controllers.dialog(new ServerServicesDialog(server)));
        start.graphicProperty().bind(javafx.beans.binding.Bindings.createObjectBinding(() -> (server.session.isRunning() ? org.jackhuang.hmcl.ui.SVG.OCHRE_STOP : org.jackhuang.hmcl.ui.SVG.OCHRE_PLAY).createIcon(18), server.session.stateProperty()));
        join.setGraphic(org.jackhuang.hmcl.ui.SVG.OCHRE_CONNECT.createIcon(18));
        restart.setGraphic(org.jackhuang.hmcl.ui.SVG.REFRESH.createIcon(18));
        force.setGraphic(org.jackhuang.hmcl.ui.SVG.OCHRE_STOP.createIcon(18));
        Region powerGap = new Region(); HBox.setHgrow(powerGap, Priority.ALWAYS);
        HBox powerHeading = new HBox(8, new Label("电源控制"), powerGap, status); powerHeading.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        GridPane powerActions = new GridPane(); powerActions.setHgap(8); powerActions.setVgap(8);
        for (int column = 0; column < 2; column++) { ColumnConstraints constraint = new ColumnConstraints(); constraint.setPercentWidth(50); constraint.setMinWidth(0); powerActions.getColumnConstraints().add(constraint); }
        powerActions.addRow(0, start, restart); powerActions.addRow(1, force, join);
        eula.setText(""); eula.setAccessibleText("我已阅读并同意 EULA");
        Label eulaCaption = new Label("我已阅读并同意 EULA"); eulaCaption.setWrapText(true); eulaCaption.setOnMouseClicked(event -> eula.setSelected(!eula.isSelected()));
        HBox eulaRow = new HBox(8, eula, eulaCaption); eulaRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        VBox power = new VBox(10, powerHeading, addressBar, powerActions, agreement, eulaRow);
        power.setPadding(new Insets(14)); power.setMaxHeight(Region.USE_PREF_SIZE); power.getStyleClass().addAll("ochre-server-controls", "ochre-server-power");
        if (server.acceptedEula()) { eulaRow.setVisible(false); eulaRow.setManaged(false); }
        VBox functions = new VBox(8); functions.setMinWidth(0); functions.getStyleClass().add("ochre-server-function-actions");
        for (String section : java.util.List.of("实例设置", "游戏设置", "玩家管理", "自动备份", "模组 / 插件")) {
            JFXButton function = new JFXButton(section + "  →"); function.setMaxWidth(Double.MAX_VALUE); function.setOnAction(event -> Controllers.navigate(new ServerWorkspacePage(server, section))); functions.getChildren().add(function);
        }
        JFXButton network = new JFXButton("异常监测与应急"); network.setGraphic(org.jackhuang.hmcl.ui.SVG.OCHRE_CONNECT.createIcon(18)); network.setMaxWidth(Double.MAX_VALUE); network.setOnAction(event -> ServerNetworkPane.showEmergency(server));
        functions.getChildren().addAll(network, services, folder, remove);
        VBox quickSettings = new VBox(10, new Label("名称"), name, new Label("最大内存（MiB）"), memory, new Label("端口"), port, new Label("Java"), javaPath, chooseJava, onlineMode, offlineNote, save);
        TitledPane quick = new TitledPane("快速配置", quickSettings); quick.setExpanded(false); functions.getChildren().add(quick);
        ScrollPane functionScroll = new ScrollPane(functions); functionScroll.setFitToWidth(true); functionScroll.setMinSize(0, 70); functionScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); functionScroll.getStyleClass().add("ochre-server-function-scroll");
        VBox functionPanel = new VBox(10, new Label("功能面板"), functionScroll); functionPanel.setPadding(new Insets(14)); functionPanel.setMinSize(0, 110); functionPanel.getStyleClass().addAll("ochre-server-controls", "ochre-server-functions"); VBox.setVgrow(functionScroll, Priority.ALWAYS);
        VBox controls = new VBox(14, power, functionPanel); controls.setMinWidth(0); controls.setPrefWidth(300); VBox.setVgrow(functionPanel, Priority.ALWAYS); power.setMinWidth(0); eula.setMinWidth(0); onlineMode.setMinWidth(0);
        eula.setMinWidth(24); eula.setPrefWidth(24); eula.setMaxWidth(24);
        for (var node : java.util.List.of(start, restart, force, join)) { node.setMinWidth(0); node.setMaxWidth(Double.MAX_VALUE); node.setWrapText(true); }
        for (var section : java.util.List.of(power, functions)) for (var node : section.getChildren()) if (node instanceof javafx.scene.control.Labeled label) { label.setMinWidth(0); label.setWrapText(!(label instanceof CheckBox)); }
        for (var node : functions.getChildren()) if (node instanceof javafx.scene.control.ButtonBase button) button.setMaxWidth(Double.MAX_VALUE);
        SplitPane split = new SplitPane(main, controls); split.setDividerPositions(.72);
        split.orientationProperty().bind(javafx.beans.binding.Bindings.createObjectBinding(() -> getWidth() < 690 ? Orientation.VERTICAL : Orientation.HORIZONTAL, widthProperty()));
        controls.maxWidthProperty().bind(javafx.beans.binding.Bindings.when(split.orientationProperty().isEqualTo(Orientation.HORIZONTAL)).then(340).otherwise(Double.MAX_VALUE));
        controls.minWidthProperty().bind(javafx.beans.binding.Bindings.when(split.orientationProperty().isEqualTo(Orientation.HORIZONTAL)).then(250).otherwise(0));
        split.orientationProperty().addListener((observable, before, after) -> javafx.application.Platform.runLater(() -> split.setDividerPositions(after == Orientation.HORIZONTAL ? .68 : .48)));
        setCenter(split);
    }
    /// Opens a recovery picker within the main window; listing and restoration never block the UI thread.
    private void showDeleted() {
        Schedulers.io().execute(() -> {
            try {
                var deleted = ServerManager.deletedServers();
                javafx.application.Platform.runLater(() -> {
                    VBox dialog = new VBox(14); dialog.setPadding(new Insets(20));
                    dialog.getStyleClass().addAll("jfx-dialog-layout", "ochre-server-recovery"); dialog.setMaxWidth(500);
                    Label title = new Label("已删除服务器");
                    ComboBox<ServerManager.DeletedServer> choices = new com.jfoenix.controls.JFXComboBox<>();
                    choices.getItems().setAll(deleted); choices.setMaxWidth(Double.MAX_VALUE); choices.getSelectionModel().selectFirst();
                    Label hint = new Label(deleted.isEmpty() ? "暂无已删除的服务器" : "恢复后会保留原有世界、模组、插件和配置。"); hint.setWrapText(true);
                    JFXButton restore = new JFXButton("恢复所选服务器"); restore.setDisable(deleted.isEmpty());
                    restore.setOnAction(event -> {
                        @Nullable var selected = choices.getValue(); if (selected == null) return;
                        restore.setDisable(true);
                        Schedulers.io().execute(() -> {
                            try {
                                ServerManager.restore(selected);
                                javafx.application.Platform.runLater(() -> dialog.fireEvent(new org.jackhuang.hmcl.ui.construct.DialogCloseEvent()));
                            } catch (IOException exception) { ServerManager.notice("恢复失败：" + exception.getMessage()); javafx.application.Platform.runLater(() -> restore.setDisable(false)); }
                        });
                    });
                    JFXButton close = new JFXButton("关闭"); close.setOnAction(event -> dialog.fireEvent(new org.jackhuang.hmcl.ui.construct.DialogCloseEvent()));
                    dialog.getChildren().setAll(title, choices, hint, new HBox(10, restore, close)); Controllers.dialog(dialog);
                });
            } catch (IOException exception) { ServerManager.notice("读取已删除服务器失败：" + exception.getMessage()); }
        });
    }

    /// Creates one editable settings field.
    private static TextField field(String value, String prompt) { TextField field = new com.jfoenix.controls.JFXTextField(value); field.setPromptText(prompt); return field; }
    /// Launches directly into the selected running server, or offers an honest restart for a running client.
    public static void join() {
        @Nullable ServerInstance server = SELECTED.get();
        if (server == null || !server.session.stateProperty().get().equals("运行中")) { ServerManager.notice("请先启动服务器并等待启动成功"); return; }
        @Nullable HMCLGameInstance game = GameDirectoryManager.selectedInstanceProperty().get();
        if (game == null) { Controllers.navigate(Controllers.getGameListPage()); return; }
        RuntimeSession session = RuntimeSession.activeGame().orElseGet(() -> RuntimeSession.game(game.getInstanceRoot().toAbsolutePath().normalize().toString()));
        Runnable launch = () -> Instances.launch(game, helper -> { helper.setKeep(); helper.setQuickPlayOption(new QuickPlayOption.MultiPlayer(server.address())); });
        if (!session.isRunning()) { launch.run(); return; }
        Controllers.confirm("运行中的原版 Minecraft 不支持外部直接切服。是否关闭当前游戏并重新启动，直接加入 " + server.address() + "？请先保存游戏进度。", "重新启动并加入", () -> {
            session.stop();
            Schedulers.io().execute(() -> {
                try {
                    var process = session.process();
                    if (process != null && !process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) { ServerManager.notice("游戏尚未退出，请保存并退出后重试"); return; }
                    javafx.application.Platform.runLater(launch);
                } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
        }, null);
    }
    /// Exposes the standalone navigation title.
    @Override public ReadOnlyObjectProperty<State> stateProperty() { return state.getReadOnlyProperty(); }
}
