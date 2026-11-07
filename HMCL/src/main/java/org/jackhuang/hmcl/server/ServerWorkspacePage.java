package org.jackhuang.hmcl.server;

import com.jfoenix.controls.*;
import javafx.application.Platform;
import javafx.beans.property.*;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.OchreTabs;
import org.jackhuang.hmcl.ui.decorator.DecoratorPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

/// Launcher-styled server maintenance workspace with real settings, snapshots and player operations.
@NotNullByDefault
public final class ServerWorkspacePage extends BorderPane implements DecoratorPage {
    /// The installation whose files and commands this page owns.
    private final ServerInstance server;
    /// Navigation title follows the selected function.
    private final ReadOnlyObjectWrapper<State> state;
    /// Main content has one attached scroll surface at a time.
    private final VBox content = new VBox(14);
    /// Rejects stale asynchronous loads after changing sections.
    private long generation;
    /// Opens a concrete function while keeping navigation in the existing launcher window.
    public ServerWorkspacePage(ServerInstance server, String section) {
        this.server = server; ServerWindowSizing.install(this); getStyleClass().add("ochre-server-page"); state = new ReadOnlyObjectWrapper<>(State.fromTitle(section + " · " + server));
        getStyleClass().add("ochre-server-page");
        setPadding(new Insets(18)); content.setPadding(new Insets(16));
        FlowPane header = new FlowPane(8, 8, action("控制台", SVG.SCRIPT, () -> Controllers.navigate(ServersPage.getInstance())), action("实例设置", SVG.TUNE, this::instance), action("游戏设置", SVG.GAMEPAD, this::game), action("玩家管理", SVG.PERSON, this::players), action("自动备份", SVG.ARCHIVE, this::snapshots), action("模组 / 插件", SVG.EXTENSION, this::addons)); header.setPadding(new Insets(0, 0, 14, 0)); setTop(header);
        ScrollPane scroll = new ScrollPane(content); scroll.setFitToWidth(true); setCenter(scroll);
        switch (section) { case "游戏设置" -> game(); case "玩家管理" -> players(); case "自动备份" -> snapshots(); case "模组 / 插件" -> addons(); default -> instance(); }
    }
    /// Exposes the current desktop title.
    @Override public ReadOnlyObjectProperty<State> stateProperty() { return state.getReadOnlyProperty(); }
    /// Resets section content and returns a request generation for background replies.
    private long begin(String title) { state.set(State.fromTitle(title + " · " + server)); content.getChildren().setAll(heading(title)); return ++generation; }
    /// Creates a readable desktop section heading.
    private static Label heading(String text) { Label label = new Label(text); label.getStyleClass().add("ochre-instance-title"); label.setWrapText(true); return label; }
    /// Instance startup configuration, Java selection, automatic restart and recorded output.
    private void instance() {
        begin("实例设置");
        Map<String, TextInputControl> fields = new LinkedHashMap<>(); VBox settings = card("启动配置");
        field(settings, fields, "name", "服务器名称", server.toString()); field(settings, fields, "port", "主端口", server.settings.getProperty("port", "25565")); field(settings, fields, "memory", "最大内存 MiB", server.settings.getProperty("memory", "2048"));
        field(settings, fields, "java", "Java 可执行文件（留空自动选择）", server.settings.getProperty("java", ""));
        ComboBox<org.jackhuang.hmcl.java.JavaRuntime> detectedJava = new JFXComboBox<>(); detectedJava.setPromptText("选择已检测到的 Java 版本"); detectedJava.setMinWidth(0); detectedJava.setMaxWidth(Double.MAX_VALUE);
        detectedJava.setCellFactory(list -> javaCell()); detectedJava.setButtonCell(javaCell());
        @Nullable var runtimes = org.jackhuang.hmcl.java.JavaManager.getAllJavaProperty().get(); if (runtimes != null) detectedJava.getItems().setAll(runtimes);
        javafx.beans.value.ChangeListener<java.util.Collection<org.jackhuang.hmcl.java.JavaRuntime>> runtimeListener = (observable, before, available) -> { if (available != null) detectedJava.getItems().setAll(available); };
        detectedJava.getProperties().put("runtime-listener", runtimeListener); org.jackhuang.hmcl.java.JavaManager.getAllJavaProperty().addListener(new javafx.beans.value.WeakChangeListener<>(runtimeListener));
        detectedJava.setOnAction(event -> { @Nullable var selected = detectedJava.getValue(); if (selected != null) fields.get("java").setText(selected.getBinary().toString()); });
        settings.getChildren().addAll(detectedJava, action("自动选择所需 Java", () -> { detectedJava.getSelectionModel().clearSelection(); fields.get("java").setText(""); }));
        JFXButton choose = action("选择 Java…", () -> { javafx.stage.FileChooser chooser = new javafx.stage.FileChooser(); chooser.setTitle("选择 Java 可执行文件"); @Nullable var path = Controllers.showOpenDialog(chooser); if (path != null) fields.get("java").setText(path.toString()); }); settings.getChildren().add(choose);
        if (server.settings.getProperty("args", "").isBlank()) field(settings, fields, "jar", "服务端 JAR（相对服务器目录）", server.settings.getProperty("jar", "server.jar"));
        TextArea additional = new JFXTextArea(server.settings.getProperty("server.arguments", "")); additional.setPromptText("每行一个服务端参数，参数值也单独一行"); additional.setPrefRowCount(3); fields.put("server.arguments", additional); settings.getChildren().addAll(new Label("附加服务端参数"), additional);
        TextArea jvm = new JFXTextArea(server.settings.getProperty("jvm", "")); jvm.setPromptText("每行一项 JVM 参数"); jvm.setPrefRowCount(4); fields.put("jvm", jvm); settings.getChildren().addAll(new Label("Java 参数"), jvm, action("使用 G1GC 参数", () -> jvm.setText("-XX:+UseG1GC")));
        CheckBox restart = new JFXCheckBox("每天自动保存并重启（启动器需保持打开）"); restart.setSelected(Boolean.parseBoolean(server.settings.getProperty("restart.enabled", "false"))); restart.setWrapText(true); settings.getChildren().add(restart); field(settings, fields, "restart.time", "执行时间 HH:mm", server.settings.getProperty("restart.time", "05:00"));
        settings.getChildren().add(action("保存配置", () -> {
            if (server.busy || server.session.isRunning()) { ServerManager.notice("请先停止服务器再修改启动配置"); return; }
            Properties options = new Properties(); fields.forEach((key, input) -> options.setProperty(key, input.getText().strip())); options.setProperty("restart.enabled", Boolean.toString(restart.isSelected()));
            task("保存实例配置", progress -> { ServerConfiguration.apply(server, options); return "配置已保存"; }, message -> ServerManager.notice(message));
        }));
        TextArea command = new JFXTextArea(); command.setEditable(false); command.setPrefRowCount(3); command.setText("Java " + server.settings.getProperty("javaMajor", "自动") + "  -Xms256M -Xmx" + server.settings.getProperty("memory", "2048") + "M\n" + server.settings.getProperty("jvm", "") + "\n" + (server.settings.getProperty("args", "").isBlank() ? "-jar " + server.settings.getProperty("jar", "server.jar") : "@" + server.settings.getProperty("args")) + " nogui --port " + server.settings.getProperty("port", "25565"));
        content.getChildren().addAll(settings, card("启动参数预览", command), action("实例日志", () -> { TextArea log = new JFXTextArea(server.session.log()); log.setEditable(false); log.setPrefRowCount(18); begin("实例日志"); content.getChildren().add(card("本次运行日志", log)); }), action("文件管理 · 打开文件夹", () -> FXUtils.openFolder(server.directory)), action("SFTP 与内网穿透", () -> { begin("SFTP 与内网穿透"); content.getChildren().add(new ServerServicesPane(server)); }), action("重装服务器核心…", () -> {
            Controllers.confirm("将先创建完整快照，再替换当前核心。世界与配置会保留；若需清空世界，请从备份中恢复或创建新服务器。是否继续选择当前版本的核心构建？", "重装核心", () -> {
                if (server.busy || server.session.isRunning()) { ServerManager.notice("重装前请先停止服务器"); return; }
                task("获取核心构建", progress -> ServerCatalog.builds(server.settings.getProperty("core"), server.settings.getProperty("version")), artifacts -> {
                    if (artifacts.isEmpty()) { ServerManager.notice("当前版本没有可用构建"); return; }
                    VBox dialog = card("选择重新安装的构建"); ComboBox<ServerCatalog.Artifact> selection = new JFXComboBox<>(); selection.getItems().setAll(artifacts); selection.getSelectionModel().selectFirst();
                    dialog.getChildren().addAll(selection, action("备份并重装", () -> { var artifact = selection.getValue(); dialog.fireEvent(new DialogCloseEvent()); task("重装核心", progress -> { ServerSnapshots.create(server, progress); var replacement = ServerCatalog.deployTemporary(artifact, server.toString(), progress); try { return ServerMaintenance.replaceCore(server, replacement, progress); } finally { ServerMaintenance.discardTemporary(replacement); } }, message -> ServerManager.notice(message)); }), action("取消", () -> dialog.fireEvent(new DialogCloseEvent()))); Controllers.dialog(dialog);
                });
            }, null);
        }));
    }
    /// Quick game commands and searchable server.properties groups.
    private void game() {
        long request = begin("游戏设置");
        ComboBox<String> format = new JFXComboBox<>(); format.getItems().setAll("自动检测", "旧版命令", "新版命令"); format.setValue("自动检测");
        FlowPane commands = new FlowPane(12, 12); java.util.List<ServerRuleSwitch> switches = new java.util.ArrayList<>();
        for (String[] rule : List.of(new String[]{"死亡不掉落", "keepInventory"}, new String[]{"昼夜交替", "doDaylightCycle"}, new String[]{"天气变化", "doWeatherCycle"}, new String[]{"怪物生成", "doMobSpawning"}, new String[]{"火焰蔓延", "doFireTick"})) {
            ServerRuleSwitch control = new ServerRuleSwitch(server, rule[0], rule[1], () -> modern(format)); switches.add(control); commands.getChildren().add(control);
        }
        commands.getChildren().addAll(card("游戏时间", new FlowPane(8, 8, action("白天", () -> send("time set day")), action("夜晚", () -> send("time set night")))), card("天气", new FlowPane(8, 8, action("晴天", () -> send("weather clear")), action("雨天", () -> send("weather rain")), action("雷暴", () -> send("weather thunder")))), card("全部在线玩家模式", new FlowPane(8, 8, action("生存", () -> confirmCommand("gamemode survival @a", "将全部在线玩家设为生存模式？")), action("创造", () -> confirmCommand("gamemode creative @a", "将全部在线玩家设为创造模式？")))), card("其他操作", new FlowPane(8, 8, action("清除掉落物", () -> confirmCommand("kill @e[type=minecraft:item]", "清除所有已加载区块的掉落物？")), action("刷新规则状态", () -> switches.forEach(ServerRuleSwitch::refresh)))));
        ServerRuleSwitch whitelist = new ServerRuleSwitch(server, "白名单", "white-list", () -> modern(format)); switches.add(whitelist); commands.getChildren().add(whitelist);
        format.valueProperty().addListener((observable, before, selected) -> switches.forEach(ServerRuleSwitch::refresh));
        for (var node : commands.getChildren()) if (node instanceof javafx.scene.layout.Region region) region.setPrefWidth(245);
        content.getChildren().addAll(new Label("快捷命令需服务器运行中；执行结果显示在控制台。"), format, commands);
        Schedulers.io().execute(() -> {
            try {
                Properties properties = new Properties(); Path file = server.directory.resolve("server.properties"); if (Files.isRegularFile(file)) try (var input = Files.newInputStream(file)) { properties.load(input); }
                Platform.runLater(() -> { if (request == generation) propertyEditor(properties); });
            } catch (Exception exception) { error(exception); }
        });
    }
    /// Shows a friendly installed Java version while keeping its path available in the selected input.
    private static ListCell<org.jackhuang.hmcl.java.JavaRuntime> javaCell() {
        return new ListCell<>() {
            /// Clears virtualized empty cells and renders actual runtime version labels.
            @Override protected void updateItem(@Nullable org.jackhuang.hmcl.java.JavaRuntime runtime, boolean empty) { super.updateItem(runtime, empty); setText(empty || runtime == null ? "" : "Java " + runtime.getVersion()); }
        };
    }
    /// Resolves a manual naming override for prerelease servers.
    private boolean modern(ComboBox<String> choice) { return choice.getValue().equals("新版命令") || choice.getValue().equals("自动检测") && ServerCommands.modern(server.settings.getProperty("version", "")); }
    /// Builds searchable grouped fields from actual server properties, retaining unknown settings.
    private void propertyEditor(Properties properties) {
        TextField search = new JFXTextField(); search.setPromptText("搜索服务器参数…");
        Map<String, TextField> fields = new LinkedHashMap<>(); Map<String, VBox> groups = new LinkedHashMap<>();
        for (String key : properties.stringPropertyNames().stream().sorted().toList()) {
            String group = key.contains("port") || key.contains("ip") || key.contains("network") || key.contains("rcon") || key.contains("query") ? "网络设置" : key.contains("level") || key.contains("world") || key.contains("spawn") || key.contains("seed") ? "世界设置" : key.contains("player") || key.contains("mode") || key.contains("difficulty") || key.contains("pvp") ? "游戏设置" : "其他设置";
            VBox box = groups.computeIfAbsent(group, ignored -> card(group)); TextField field = new JFXTextField(properties.getProperty(key)); fields.put(key, field);
            Region row;
            if (properties.getProperty(key).equals("true") || properties.getProperty(key).equals("false")) {
                JFXToggleButton toggle = new JFXToggleButton(); toggle.setSelected(Boolean.parseBoolean(properties.getProperty(key))); toggle.setAccessibleText(propertyName(key));
                field.textProperty().bind(javafx.beans.binding.Bindings.when(toggle.selectedProperty()).then("true").otherwise("false"));
                Label name = new Label(propertyName(key)); name.setWrapText(true); Label raw = new Label(key); raw.getStyleClass().add("subtitle-label");
                VBox caption = new VBox(4, name, raw); caption.setMinWidth(0); HBox.setHgrow(caption, Priority.ALWAYS);
                HBox booleanRow = new HBox(12, caption, toggle); booleanRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT); booleanRow.setPadding(new Insets(10, 0, 10, 0)); row = booleanRow;
            } else row = new VBox(4, new Label(key), field);
            row.setUserData(key); box.getChildren().add(row);
        }
        search.textProperty().addListener((observable, old, value) -> groups.values().forEach(group -> group.getChildren().stream().skip(1).forEach(row -> { boolean show = row.getUserData().toString().contains(value.strip().toLowerCase(Locale.ROOT)); row.setVisible(show); row.setManaged(show); })));
        content.getChildren().add(search); content.getChildren().addAll(groups.values());
        content.getChildren().add(action("保存 server.properties（重启后生效）", () -> {
            if (server.busy || server.session.isRunning()) { ServerManager.notice("请先停止服务器再保存参数"); return; }
            Properties edited = new Properties(); fields.forEach((key, field) -> edited.setProperty(key, field.getText()));
            task("保存游戏设置", progress -> { ServerMaintenance.saveProperties(server, edited); return "游戏设置已保存"; }, ServerManager::notice);
        }));
    }
    /// Gives common boolean properties readable captions while preserving their exact keys underneath.
    private static String propertyName(String key) {
        return switch (key) {
            case "accepts-transfers" -> "接受服务器转移"; case "allow-flight" -> "允许飞行"; case "allow-nether" -> "允许下界";
            case "broadcast-console-to-ops" -> "向管理员广播控制台消息"; case "broadcast-rcon-to-ops" -> "向管理员广播远程控制消息";
            case "enable-command-block" -> "启用命令方块"; case "enable-query" -> "启用状态查询"; case "enable-rcon" -> "启用远程控制";
            case "enable-code-of-conduct" -> "启用行为准则"; case "enforce-secure-profile" -> "要求安全档案"; case "generate-structures" -> "生成建筑";
            case "hardcore" -> "极限模式"; case "hide-online-players" -> "隐藏在线玩家"; case "online-mode" -> "启用正版验证";
            case "pvp" -> "允许玩家互伤"; case "white-list" -> "启用白名单"; case "enforce-whitelist" -> "强制白名单";
            case "spawn-monsters" -> "生成怪物"; case "spawn-animals" -> "生成动物"; case "spawn-npcs" -> "生成村民";
            case "require-resource-pack" -> "强制使用资源包"; case "log-ips" -> "记录玩家 IP"; default -> key;
        };
    }
    /// Snapshot list, automatic interval settings and guarded whole/single-file recovery.
    private void snapshots() {
        long request = begin("自动备份");
        CheckBox automatic = new JFXCheckBox("自动创建存档快照（启动器打开且服务器运行时）"); automatic.setWrapText(true); automatic.setSelected(Boolean.parseBoolean(server.settings.getProperty("backup.enabled", "false")));
        TextField interval = new JFXTextField(server.settings.getProperty("backup.minutes", "60")); interval.setPromptText("间隔分钟，至少 5 分钟");
        content.getChildren().addAll(card("自动快照", automatic, interval, action("保存计划", () -> {
            try { int minutes = Integer.parseInt(interval.getText()); if (minutes < 5 || minutes > 10080) throw new NumberFormatException(); server.settings.setProperty("backup.enabled", Boolean.toString(automatic.isSelected())); server.settings.setProperty("backup.minutes", Integer.toString(minutes)); server.save(); ServerManager.notice("备份计划已保存"); }
            catch (Exception exception) { ServerManager.notice("备份间隔应为 5–10080 分钟"); }
        })), action("立即创建完整快照", () -> task("创建服务器快照", progress -> ServerSnapshots.create(server, progress), path -> snapshots())));
        Schedulers.io().execute(() -> {
            try { var snapshots = ServerSnapshots.list(server); Platform.runLater(() -> { if (request != generation) return;
                if (snapshots.isEmpty()) content.getChildren().add(new Label("暂无快照"));
                for (Path path : snapshots) content.getChildren().add(card(path.getFileName().toString(), new FlowPane(8, 8, action("完整恢复", () -> Controllers.confirm("先备份当前数据，再恢复此快照。请先停止服务器。", "恢复服务器", () -> task("恢复服务器", progress -> { ServerSnapshots.restore(server, path, progress); return "恢复完成"; }, ServerManager::notice), null)), action("找回单个文件", () -> snapshotFiles(path)), action("打开备份文件夹", () -> FXUtils.openFolder(path.getParent())))));
            }); } catch (Exception exception) { error(exception); }
        });
    }
    /// Selects a real archive member rather than accepting unvalidated path text.
    private void snapshotFiles(Path snapshot) {
        task("读取快照文件列表", progress -> ServerSnapshots.entries(snapshot), members -> {
            VBox dialog = card("找回单个文件"); ListView<String> list = new JFXListView<>(); list.getItems().setAll(members); list.setPrefSize(580, 360);
            dialog.getChildren().addAll(list, action("恢复所选文件", () -> { @Nullable String member = list.getSelectionModel().getSelectedItem(); if (member == null) return; Controllers.confirm("恢复 " + member + "？会先创建当前服务器快照。", "恢复文件", () -> { dialog.fireEvent(new DialogCloseEvent()); task("恢复文件", progress -> { ServerSnapshots.restoreFile(server, snapshot, member, progress); return "文件已恢复"; }, ServerManager::notice); }, null); }), action("关闭", () -> dialog.fireEvent(new DialogCloseEvent()))); Controllers.dialog(dialog);
        });
    }
    /// Opens local player records; player actions are rendered in this same window.
    private void players() {
        long request = begin("玩家管理"); content.getChildren().add(action("刷新玩家列表", SVG.REFRESH, this::players));
        Schedulers.io().execute(() -> {
            try { var players = ServerPlayers.list(server); Platform.runLater(() -> { if (request != generation) return; for (var player : players) content.getChildren().add(action(player.name() + "  " + (player.operator() ? "OP  " : "") + (player.whitelisted() ? "白名单" : ""), SVG.PERSON, () -> player(player))); if (players.isEmpty()) content.getChildren().add(new Label("暂无本地玩家记录；玩家首次加入后会出现在这里。")); }); }
            catch (Exception exception) { error(exception); }
        });
    }
    /// Player commands, permissions, backed-up inventory and identity data migration.
    private void player(ServerPlayers.Player player) {
        try { ServerCommands.player(player.name()); } catch (Exception exception) { error(exception); return; }
        long request = begin(player.name());
        TextField uuid = new JFXTextField(player.uuid().toString()); uuid.setEditable(false); uuid.setOnMouseClicked(event -> uuid.selectAll());
        content.getChildren().addAll(uuid, new FlowPane(8, 8, action("复制 UUID", SVG.CONTENT_COPY, () -> { javafx.scene.input.ClipboardContent copy = new javafx.scene.input.ClipboardContent(); copy.putString(uuid.getText()); javafx.scene.input.Clipboard.getSystemClipboard().setContent(copy); }), action("返回玩家列表", SVG.ARROW_BACK, this::players)));
        FlowPane usual = new FlowPane(8, 8);
        for (String[] operation : List.of(new String[]{"私聊", "msg", "消息"}, new String[]{"传送", "tp", "坐标 x y z 或另一玩家名称"}, new String[]{"给予物品", "give", "物品 ID [数量]"}, new String[]{"添加效果", "effect give", "效果 ID [秒数] [等级]"})) usual.getChildren().add(action(operation[0], switch (operation[1]) { case "msg" -> SVG.CHAT; case "tp" -> SVG.EXPLORE; case "give" -> SVG.PACKAGE; default -> SVG.STYLE; }, () -> prompt(operation[0], operation[2], text -> playerCommand(operation[1] + " " + player.name() + " " + text))));
        usual.getChildren().addAll(action("清除效果", SVG.UNDO, () -> playerCommand("effect clear " + player.name())), action("生存模式", SVG.GAMEPAD, () -> playerCommand("gamemode survival " + player.name())), action("创造模式", SVG.DEPLOYED_CODE, () -> playerCommand("gamemode creative " + player.name())), action("设为 OP", SVG.PERSON, () -> confirmCommand("op " + player.name(), "授予此玩家管理员权限？")), action("撤销 OP", SVG.PERSON, () -> playerCommand("deop " + player.name())), action("加入白名单", SVG.ADD_CIRCLE, () -> playerCommand("whitelist add " + player.name())), action("移出白名单", SVG.CANCEL, () -> playerCommand("whitelist remove " + player.name())));
        FlowPane penalties = new FlowPane(8, 8);
        for (String[] operation : List.of(new String[]{"踢出", "kick"}, new String[]{"封禁账号", "ban"}, new String[]{"封禁 IP", "ban-ip"}, new String[]{"击杀", "kill"}, new String[]{"清空背包", "clear"})) penalties.getChildren().add(action(operation[0], switch (operation[1]) { case "kick" -> SVG.OUTPUT; case "ban", "ban-ip" -> SVG.CANCEL; case "kill" -> SVG.WARNING; default -> SVG.DELETE; }, () -> confirmCommand(operation[1] + " " + player.name(), "确认对 " + player.name() + " 执行“" + operation[0] + "”？")));
        penalties.getChildren().add(action("重置玩家存档", SVG.RESTORE, () -> Controllers.confirm("先备份服务器，再移除此玩家的存档、成就和统计。需要停止服务器。", "重置玩家数据", () -> task("重置玩家数据", progress -> { ServerPlayers.reset(server, player.uuid(), progress); return "玩家数据已重置，原数据保留在快照中"; }, ServerManager::notice), null)));
        content.getChildren().addAll(card("常用操作", usual), card("处罚与重置", penalties), card("数据搬家", new Label("切换正版 / 离线身份时复制玩家存档；来源保留，迁移前自动备份。"), action("显示离线 UUID", SVG.VISIBILITY, () -> uuid.setText(ServerPlayers.offlineUUID(player.name()).toString())), action("迁移到另一个 UUID…", SVG.FOLDER_COPY, () -> prompt("玩家数据搬家", "目标 UUID", text -> { try { UUID destination = UUID.fromString(text.strip()); task("迁移玩家数据", progress -> { ServerPlayers.migrate(server, player.uuid(), destination, progress); return "玩家数据已复制到新身份"; }, ServerManager::notice); } catch (IllegalArgumentException exception) { error(exception); } }))));
        OchreTabs tabs = new OchreTabs(); VBox inventory = new VBox(10), ender = new VBox(10); TextArea stats = new JFXTextArea(); stats.setEditable(false); stats.setPrefRowCount(18);
        tabs.addPage("随身背包", SVG.PACKAGE.createIcon(18), inventory); tabs.addPage("末影箱", SVG.ARCHIVE.createIcon(18), ender); tabs.addPage("统计信息", SVG.FORMAT_LIST_BULLETED.createIcon(18), stats); content.getChildren().add(tabs);
        Schedulers.io().execute(() -> {
            try { var items = ServerPlayers.items(server, player.uuid(), false); var enderItems = ServerPlayers.items(server, player.uuid(), true); String statistics = ServerPlayers.statistics(server, player.uuid()); Platform.runLater(() -> { if (request != generation) return; inventoryGrid(inventory, player, items, false); inventoryGrid(ender, player, enderItems, true); stats.setText(statistics); }); }
            catch (Exception exception) { Platform.runLater(() -> { if (request == generation) inventory.getChildren().add(new Label("暂无可读取的玩家存档：" + exception.getMessage())); }); }
        });
    }
    /// Renders true saved item slots; file edits are unavailable while the server owns the world.
    private void inventoryGrid(VBox box, ServerPlayers.Player player, List<ServerPlayers.Item> items, boolean ender) {
        box.getChildren().setAll(new Label("显示最近保存的存档；点击格子修改物品，修改前需停止服务器，且会自动备份。"));
        TilePane grid = new TilePane(8, 8); grid.setPrefColumns(9);
        List<Integer> slots = new ArrayList<>(); for (int slot = 0; slot < (ender ? 27 : 36); slot++) slots.add(slot); if (!ender) slots.addAll(List.of(100, 101, 102, 103, 150));
        for (int slot : slots) {
            @Nullable var existing = items.stream().filter(item -> item.slot() == slot).findFirst().orElse(null);
            JFXButton cell = action(existing == null ? "+\n" + slot : existing.id().replace("minecraft:", "") + "\n×" + existing.count(), () -> {
                VBox dialog = card("编辑物品格 " + slot); TextField id = new JFXTextField(existing == null ? "minecraft:stone" : existing.id()), count = new JFXTextField(existing == null ? "1" : Integer.toString(existing.count()));
                dialog.getChildren().addAll(new Label("物品 ID"), id, new Label("数量（0 表示移除）"), count, action("备份并保存", () -> { try { String item = id.getText().strip(); int amount = Integer.parseInt(count.getText()); dialog.fireEvent(new DialogCloseEvent()); task("修改玩家物品", progress -> { ServerPlayers.editItem(server, player.uuid(), ender, slot, item, amount, progress); return "物品已保存"; }, message -> player(player)); } catch (Exception exception) { error(exception); } }), action("取消", () -> dialog.fireEvent(new DialogCloseEvent()))); Controllers.dialog(dialog);
            }); cell.setPrefSize(98, 72); cell.setWrapText(true); cell.getStyleClass().add("ochre-server-controls"); grid.getChildren().add(cell);
        }
        box.getChildren().add(grid);
    }
    /// Lists actual extension jars with stopped-server enable/disable toggles.
    private void addons() {
        long request = begin("模组 / 插件");
        content.getChildren().add(action("下载服务器附加项", () -> { ServersPage.SELECTED.set(server); Controllers.getDownloadPage().showServerAddons(); Controllers.navigate(Controllers.getDownloadPage()); }));
        Schedulers.io().execute(() -> {
            try { for (String folder : List.of("mods", "plugins")) { Path root = server.directory.resolve(folder); if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) continue; try (var files = Files.list(root)) { var jars = files.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && (file.toString().endsWith(".jar") || file.toString().endsWith(".jar.disabled"))).sorted().toList(); Platform.runLater(() -> { if (request != generation) return; for (Path file : jars) content.getChildren().add(card(file.getFileName().toString(), action(file.toString().endsWith(".disabled") ? "启用" : "禁用", () -> task("切换附加项状态", progress -> { if (server.busy || server.session.isRunning()) throw new IOException("请先停止服务器"); String name = file.getFileName().toString(); Files.move(file, file.resolveSibling(name.endsWith(".disabled") ? name.substring(0, name.length() - 9) : name + ".disabled")); return true; }, ignored -> addons())))); }); } } }
            catch (Exception exception) { error(exception); }
        });
    }
    /// Validates the known player name before any parameterized command.
    private void playerCommand(String command) { send(command); }
    /// Sends one command on a worker thread and leaves the server response in the live console.
    private void send(String command) { Schedulers.io().execute(() -> { try { ServerManager.command(server, command); ServerManager.notice("命令已发送，结果见控制台"); } catch (Exception exception) { error(exception); } }); }
    /// Requires an explicit confirmation for broad or destructive administrative commands.
    private void confirmCommand(String command, String message) { Controllers.confirm(message, "确认操作", () -> send(command), null); }
    /// Creates an input overlay using launcher components instead of native Java dialog chrome.
    private void prompt(String title, String hint, Consumer<String> result) { VBox dialog = card(title); TextField input = new JFXTextField(); input.setPromptText(hint); dialog.getChildren().addAll(input, action("确定", () -> { String text = input.getText(); if (text.isBlank() || text.contains("\n") || text.contains("\r")) return; dialog.fireEvent(new DialogCloseEvent()); result.accept(text); }), action("取消", () -> dialog.fireEvent(new DialogCloseEvent()))); Controllers.dialog(dialog); }
    /// Executes a real server task in the independent progress overlay.
    private static <T> void task(String title, ServerTaskDialog.Work<T> work, Consumer<T> completed) { ServerTaskDialog.show(title, work, completed); }
    /// Reports operation failures without spawning a native window.
    private static void error(Exception exception) { ServerManager.notice(ServerManager.NoticeKind.ERROR, "操作失败：" + exception.getMessage()); }
    /// Creates a rounded content card with consistent spacing.
    private static VBox card(String title, Node... children) { VBox box = new VBox(10, new Label(title)); box.setPadding(new Insets(16)); box.getStyleClass().add("ochre-server-controls"); box.getChildren().addAll(children); return box; }
    /// Adds one labelled text setting.
    private static void field(VBox box, Map<String, TextInputControl> fields, String key, String label, String value) { TextField input = new JFXTextField(value); fields.put(key, input); box.getChildren().addAll(new Label(label), input); }
    /// Adds a theme-colored operation icon without changing command handling.
    private static JFXButton action(String title, SVG icon, Runnable action) { JFXButton button = action(title, action); button.setGraphic(icon.createIcon(18)); button.setGraphicTextGap(8); return button; }
    /// Creates a standard launcher action button.
    private static JFXButton action(String title, Runnable action) { JFXButton button = new JFXButton(title); button.setOnAction(event -> action.run()); button.setWrapText(true); return button; }
}
