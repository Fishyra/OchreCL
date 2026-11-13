package org.jackhuang.hmcl.server;

import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.function.Consumer;
import java.util.jar.JarFile;

/// Copies a stopped existing server into a fresh managed installation without changing its source.
@NotNullByDefault
public final class ServerImporter {
    /// Utility class.
    private ServerImporter() {}
    /// Builds the header action's import dialog, with explicit source and launch configuration.
    public static void show() {
        VBox pane = new VBox(12); pane.setPadding(new Insets(24)); pane.setPrefSize(620, 540); pane.setMinSize(300, 300); pane.setMaxSize(780, 680); pane.getStyleClass().add("jfx-dialog-layout");
        Label title = new Label("导入已有服务器"); title.setGraphic(SVG.FOLDER_COPY.createIcon(24)); title.getStyleClass().add("ochre-instance-title");
        Label hint = new Label("选择服务端文件夹。导入会复制到新的管理目录，不移动或覆盖原文件；不会自动执行 run.sh、BAT 或其他脚本。"); hint.setWrapText(true);
        TextField source = new TextField(); source.setEditable(false); source.setPromptText("选择包含服务端 JAR、世界与配置的文件夹"); source.setMinWidth(0); HBox.setHgrow(source, Priority.ALWAYS);
        TextField name = new TextField(), version = new TextField(), port = new TextField("25565"), memory = new TextField("2048"), java = new TextField(); java.setPromptText("留空自动选择；未知版本请指定匹配的 Java");
        ComboBox<String> core = new ComboBox<>(); core.getItems().setAll("原版", "Paper", "Purpur", "Fabric", "Forge", "自定义"); core.setValue("自定义"); core.setMaxWidth(Double.MAX_VALUE);
        ComboBox<String> jar = new ComboBox<>(); jar.setMaxWidth(Double.MAX_VALUE); jar.setPromptText("选择启动 JAR 或 Forge 参数文件");
        Label feedback = new Label(); feedback.setWrapText(true); CheckBox stopped = new com.jfoenix.controls.JFXCheckBox("原服务器已停止，允许复制世界与配置"); stopped.setWrapText(true);
        JFXButton choose = new JFXButton("选择文件夹"); choose.setGraphic(SVG.FOLDER.createIcon(18)); choose.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("选择已有服务器目录"); @Nullable Path directory = Controllers.showDialog(chooser); if (directory == null) return;
            choose.setDisable(true); feedback.setText("正在检查启动文件…");
            Schedulers.io().execute(() -> { try {
                List<String> jars; try (var files = Files.list(directory)) { jars = files.filter(Files::isRegularFile).map(file -> file.getFileName().toString()).filter(file -> file.endsWith(".jar") && !file.endsWith("-installer.jar")).sorted().toList(); }
                var choices = new ArrayList<>(jars); if (Files.isDirectory(directory.resolve("libraries/net/minecraftforge/forge"))) choices.add("（Forge 参数文件）"); Properties game = properties(directory.resolve("server.properties")); Properties previous = properties(directory.resolve("ochre-server.properties"));
                Platform.runLater(() -> { source.setText(directory.toAbsolutePath().normalize().toString()); name.setText(previous.getProperty("name", directory.getFileName().toString())); version.setText(previous.getProperty("version", "")); java.setText(previous.getProperty("java", "")); memory.setText(previous.getProperty("memory", "2048")); core.setValue(previous.getProperty("core", "自定义")); jar.getItems().setAll(choices); jar.getSelectionModel().selectFirst(); port.setText(game.getProperty("server-port", "25565")); feedback.setText(choices.isEmpty() ? "未找到顶层启动 JAR 或 Forge 参数文件，请选择实际服务端目录。" : "请确认核心与版本；导入保留原 EULA 状态。未知版本可填“未知”，并手动指定 Java。"); choose.setDisable(false); });
            } catch (Exception error) { Platform.runLater(() -> { feedback.setText(error.getMessage()); choose.setDisable(false); }); } });
        });
        JFXButton chooseJava = new JFXButton("选择 Java…"); chooseJava.setOnAction(event -> { var chooser = new javafx.stage.FileChooser(); chooser.setTitle("选择 Java 可执行文件"); @Nullable var path = Controllers.showOpenDialog(chooser); if (path != null) java.setText(path.toString()); });
        VBox fields = new VBox(9, new Label("来源目录"), new HBox(8, source, choose), new Label("服务器名称"), name, new Label("核心类型"), core, new Label("Minecraft 版本"), version, new Label("启动入口"), jar, new Label("端口 / 最大内存 MiB"), new HBox(10, port, memory), new Label("Java"), java, chooseJava, stopped, feedback); ScrollPane scroll = new ScrollPane(fields); scroll.setFitToWidth(true); scroll.setMinSize(0, 0); VBox.setVgrow(scroll, Priority.ALWAYS);
        JFXButton start = new JFXButton("复制并导入"), close = new JFXButton("取消"); start.setGraphic(SVG.FOLDER_COPY.createIcon(18)); start.getStyleClass().add("ochre-primary-action"); start.disableProperty().bind(stopped.selectedProperty().not().or(source.textProperty().isEmpty()).or(jar.valueProperty().isNull()));
        start.setOnAction(event -> {
            Properties settings = new Properties(); settings.setProperty("name", name.getText()); settings.setProperty("core", core.getValue()); settings.setProperty("version", version.getText().isBlank() ? "未知" : version.getText().strip()); settings.setProperty("port", port.getText()); settings.setProperty("memory", memory.getText()); settings.setProperty("java", java.getText().strip()); settings.setProperty("jar", jar.getValue());
            try { ServerConfiguration.validate(settings); } catch (IOException error) { feedback.setText(error.getMessage()); return; }
            Path directory = Path.of(source.getText()); pane.fireEvent(new DialogCloseEvent());
            ServerTaskDialog.show("导入 " + name.getText(), progress -> importDirectory(directory, settings, progress), installed -> { ServerManager.SERVERS.add(installed); ServersPage.SELECTED.set(installed); Controllers.navigate(ServersPage.getInstance()); ServerManager.notice("服务器已导入，原目录保持不变"); });
        });
        close.setOnAction(event -> pane.fireEvent(new DialogCloseEvent())); pane.getChildren().setAll(title, hint, scroll, new HBox(12, start, close)); Controllers.dialog(pane);
    }
    /// Copies into a staging directory, validates its entry point, then publishes a complete installation.
    public static ServerInstance importDirectory(Path original, Properties options, Consumer<String> progress) throws Exception {
        ServerConfiguration.validate(options); Path source = original.toRealPath(); Files.createDirectories(ServerManager.ROOT);
        if (ServerManager.ROOT.toRealPath().startsWith(source)) throw new IOException("不能把包含启动器管理目录的父文件夹作为服务器导入");
        if (ServerManager.SERVERS.stream().anyMatch(server -> server.directory.toAbsolutePath().normalize().equals(source) && (server.session.isRunning() || server.busy))) throw new IOException("请先停止原服务器");
        Path staging = Files.createDirectory(ServerManager.ROOT.resolve(".import-" + UUID.randomUUID()));
        try {
            Properties game = properties(source.resolve("server.properties")); Path world = source.resolve(game.getProperty("level-name", "world")).normalize(); if (!world.startsWith(source)) throw new IOException("世界目录超出服务端目录，请先整理后再导入");
            Path lock = world.resolve("session.lock"); if (Files.isSymbolicLink(world) || Files.isSymbolicLink(lock)) throw new IOException("世界目录或锁文件为符号链接，请先整理后再导入");
            if (Files.isRegularFile(lock)) { try (FileChannel channel = FileChannel.open(lock, StandardOpenOption.READ); FileLock held = channel.tryLock(0, Long.MAX_VALUE, true)) { if (held == null) throw new IOException("原世界仍被运行中的服务器占用"); copy(source, staging, progress); } }
            else copy(source, staging, progress);
            progress.accept("阶段:1"); progress.accept("正在验证启动入口…"); String selected = options.getProperty("jar", "");
            if (!selected.equals("（Forge 参数文件）")) { Path entry = staging.resolve(selected).normalize(); if (!entry.startsWith(staging) || !Files.isRegularFile(entry)) throw new IOException("启动 JAR 无效"); try (JarFile archive = new JarFile(entry.toFile())) { if (archive.getManifest() == null || archive.getManifest().getMainAttributes().getValue("Main-Class") == null) throw new IOException("启动文件没有 Main-Class，不能作为独立服务端运行"); } }
            progress.accept("阶段:2"); Properties metadata = properties(staging.resolve("ochre-server.properties")); metadata.putAll(options); metadata.setProperty("online-mode", game.getProperty("online-mode", "true")); metadata.setProperty("javaMajor", options.getProperty("version", "").startsWith("26.") ? "25" : "21"); metadata.setProperty("import.source", source.toString());
            try (var output = Files.newOutputStream(staging.resolve("ochre-server.properties"))) { metadata.store(output, "Imported server copy; source preserved"); }
            ServerInstance server = new ServerInstance(staging); if (selected.equals("（Forge 参数文件）")) { server.settings.remove("jar"); ServerCatalog.configureForgeLaunch(server); server.save(); }
            progress.accept("阶段:3"); ServerConfiguration.apply(server, options); if (selected.equals("（Forge 参数文件）")) { server.settings.remove("jar"); server.save(); }
            Path completed = ServerManager.ROOT.resolve(UUID.randomUUID().toString()); Files.move(staging, completed, StandardCopyOption.ATOMIC_MOVE); return new ServerInstance(completed);
        } catch (Exception error) { if (Files.exists(staging)) try (var files = Files.walk(staging)) { for (Path path : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); } throw error; }
    }
    /// Copies only regular files and directories; rejects links and detects files changed during copying.
    private static void copy(Path source, Path target, Consumer<String> progress) throws IOException {
        progress.accept("阶段:0"); progress.accept("正在复制已有服务端，原目录保持不变…"); long[] count = {0};
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            /// Refuses interrupted work and skips runtime supervision records from an earlier launcher instance.
            @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws IOException { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("导入已取消"); String name = directory.getFileName().toString(); if (name.equals(".ochre-runtime") || name.equals(".ochre-services")) return FileVisitResult.SKIP_SUBTREE; Files.createDirectories(target.resolve(source.relativize(directory))); return FileVisitResult.CONTINUE; }
            /// Publishes each copied file only into the new private staging directory.
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes before) throws IOException { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("导入已取消"); if (!before.isRegularFile()) throw new IOException("不支持导入符号链接或特殊文件：" + source.relativize(file)); if (file.getFileName().toString().equals("session.lock")) return FileVisitResult.CONTINUE; Files.copy(file, target.resolve(source.relativize(file)), StandardCopyOption.COPY_ATTRIBUTES); var after = Files.readAttributes(file, BasicFileAttributes.class); if (after.size() != before.size() || !after.lastModifiedTime().equals(before.lastModifiedTime())) throw new IOException("文件正在变化，请停止原服务端后重试"); if (++count[0] % 100 == 0) progress.accept("已复制 " + count[0] + " 个文件"); return FileVisitResult.CONTINUE; }
        });
    }
    /// Reads optional existing server properties with a bounded size.
    private static Properties properties(Path file) throws IOException { Properties values = new Properties(); if (Files.isRegularFile(file)) { if (Files.size(file) > 1024 * 1024) throw new IOException("配置文件过大"); try (var input = Files.newInputStream(file)) { values.load(input); } } return values; }
}
