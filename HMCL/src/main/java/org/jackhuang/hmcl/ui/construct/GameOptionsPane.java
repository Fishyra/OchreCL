package org.jackhuang.hmcl.ui.construct;

import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.server.RuntimeSession;
import org.jackhuang.hmcl.server.ServerManager;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/// Edits supported vanilla options without overwriting a running game's options file.
@NotNullByDefault
public final class GameOptionsPane extends VBox {
    /// Durable queue survives closing a settings dialog or restarting the launcher.
    private static final Path QUEUE = Metadata.HMCL_LOCAL_HOME.resolve("game-option-changes");
    /// Starts only one polling worker for deferred changes in this launcher process.
    private static boolean initialized;
    /// Changed options only, merged with the most recent game-written file after exit.
    private final Map<String, String> changes = new LinkedHashMap<>();
    /// Shows whether changes were applied or deferred until the game exits.
    private final Label feedback = new Label();
    /// Builds sound and video controls scoped to the actual run directory.
    public GameOptionsPane(String key) { this(key, null); }
    /// Rebuilds controls from a game-thread snapshot after an explicit live refresh.
    private GameOptionsPane(String key, @Nullable Map<String, String> actual) {
        super(12); getStyleClass().add("ochre-game-options"); setPadding(new Insets(12)); initialize(); Path directory = RuntimeSession.runDirectory(key);
        Label note = new Label("声音与视频设置将写入此实例的 options.txt。游戏运行中会先保存为待应用设置，退出后写入，下次启动生效；安装桥接组件并重启游戏后，可读取并实时应用受支持的设置。"); note.setWrapText(true);
        Map<String, String> current;
        try { current = actual == null ? read(directory.resolve("options.txt")) : actual; } catch (java.io.IOException error) { current = Map.of(); feedback.setText("暂时无法读取设置：" + error.getMessage()); }
        VBox sound = section("声音", SVG.INFO), video = section("视频", SVG.OCHRE_SCREENSHOT);
        slider(sound, current, "soundCategory_master", "主音量", 0, 1, 1, false); slider(sound, current, "soundCategory_music", "音乐", 0, 1, 1, false); slider(sound, current, "soundCategory_weather", "天气", 0, 1, 1, false);
        slider(video, current, "renderDistance", "渲染距离", 2, 32, 12, true); slider(video, current, "maxFps", "最大帧率", 10, 260, 120, true); slider(video, current, "gamma", "亮度", 0, 1, .5, false); slider(video, current, "fov", "视野（°）", -1, 1, 0, true);
        CheckBox fullscreen = new com.jfoenix.controls.JFXCheckBox("全屏"); fullscreen.setSelected(Boolean.parseBoolean(current.getOrDefault("fullscreen", "false"))); fullscreen.selectedProperty().addListener((observable, before, value) -> changes.put("fullscreen", value.toString())); video.getChildren().add(fullscreen);
        TilePane cards = new TilePane(12, 12, sound, video); cards.setPrefColumns(2); cards.setPrefTileWidth(310); cards.setMinWidth(0);
        ScrollPane scroll = new ScrollPane(cards); scroll.setFitToWidth(true); scroll.setMinSize(0, 0); VBox.setVgrow(scroll, Priority.ALWAYS);
        JFXButton save = new JFXButton("保存游戏设置"); save.setGraphic(SVG.CHECK.createIcon(18)); save.getStyleClass().add("ochre-primary-action");
        save.setOnAction(event -> {
            if (changes.isEmpty()) { feedback.setText("没有更改的设置"); return; }
            var patch = Map.copyOf(changes); save.setDisable(true);
            Schedulers.io().execute(() -> { try {
                boolean running = RuntimeSession.game(key).isRunning(); boolean live = false;
                if (running && org.jackhuang.hmcl.bridge.BridgeManager.installed(key)) {
                    Map<String, String> arguments = new LinkedHashMap<>(); patch.forEach((name, value) -> arguments.put("option." + name, value)); org.jackhuang.hmcl.bridge.BridgeManager.request(key, "options.set", arguments); live = true;
                } else { queue(key, directory, patch); if (!running) applyPending(); }
                boolean appliedLive = live;
                Platform.runLater(() -> { feedback.setText(appliedLive ? "游戏已确认应用并保存这些设置" : running ? "已保存；退出游戏后自动写入，下次启动生效。请让启动器保持开启，或下次打开时继续应用。" : "已写入游戏设置，下次启动生效"); changes.clear(); save.setDisable(false); });
            } catch (Exception error) { Platform.runLater(() -> { feedback.setText("保存失败：" + error.getMessage()); save.setDisable(false); }); } });
        });
        JFXButton refresh = new JFXButton("读取游戏当前设置"); refresh.setGraphic(SVG.OCHRE_CONNECT.createIcon(18)); refresh.setOnAction(event -> { refresh.setDisable(true); Schedulers.io().execute(() -> {
            try { var response = org.jackhuang.hmcl.bridge.BridgeManager.request(key, "options.get", Map.of()); Platform.runLater(() -> { GameOptionsPane updated = new GameOptionsPane(key, response); getChildren().setAll(updated); VBox.setVgrow(updated, Priority.ALWAYS); }); }
            catch (Exception error) { Platform.runLater(() -> { feedback.setText("尚未连接游戏：" + error.getMessage()); refresh.setDisable(false); }); }
        }); });
        feedback.setWrapText(true); getChildren().setAll(note, scroll, feedback, new FlowPane(10, 8, refresh, save));
    }
    /// Starts bounded queue checks; no game process is stopped by this editor.
    public static synchronized void initialize() {
        if (initialized) return; initialized = true;
        var scheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(task -> { Thread thread = new Thread(task, "Ochre-option-changes"); thread.setDaemon(true); return thread; });
        scheduler.scheduleWithFixedDelay(() -> { try { applyPending(); } catch (Exception error) { org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Pending game settings could not be applied", error); } }, 3, 3, java.util.concurrent.TimeUnit.SECONDS);
    }
    /// Copies only explicit changed keys to a per-game durable queue file.
    static synchronized void queue(String key, Path directory, Map<String, String> patch) throws java.io.IOException {
        Files.createDirectories(QUEUE); Path file = QUEUE.resolve(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)) + ".properties"); Properties values = new Properties();
        if (Files.isRegularFile(file)) try (var input = Files.newInputStream(file)) { values.load(input); }
        values.setProperty("game", key); values.setProperty("directory", directory.toAbsolutePath().toString()); patch.forEach((name, value) -> values.setProperty("option." + name, value));
        Path pending = file.resolveSibling(file.getFileName() + ".new"); try (var output = Files.newOutputStream(pending)) { values.store(output, "Explicit deferred game options"); } Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING);
    }
    /// Merges queued values only after the owned game session has ended, backing up the latest file first.
    static synchronized void applyPending() throws java.io.IOException {
        if (OchreTasks.isLaunching() || !Files.isDirectory(QUEUE)) return;
        try (var files = Files.newDirectoryStream(QUEUE, "*.properties")) { for (Path queued : files) {
            Properties values = new Properties(); try (var input = Files.newInputStream(queued)) { values.load(input); }
            if (RuntimeSession.game(values.getProperty("game")).isRunning()) continue;
            Path options = Path.of(values.getProperty("directory")).resolve("options.txt"); if (!Files.isDirectory(options.getParent())) continue;
            Map<String, String> patch = new LinkedHashMap<>(); for (String name : values.stringPropertyNames()) if (name.startsWith("option.")) patch.put(name.substring(7), values.getProperty(name));
            merge(options, patch); Files.delete(queued);
        } }
    }
    /// Reads colon-separated game settings while preserving unknown values when writing.
    public static @Unmodifiable Map<String, String> read(Path file) throws java.io.IOException {
        if (!Files.isRegularFile(file)) return Map.of(); if (Files.size(file) > 4 * 1024 * 1024) throw new java.io.IOException("游戏设置文件过大");
        Map<String, String> result = new LinkedHashMap<>(); for (String line : Files.readAllLines(file)) { int separator = line.indexOf(':'); if (separator > 0) result.put(line.substring(0, separator), line.substring(separator + 1)); } return Map.copyOf(result);
    }
    /// Rewrites only changed keys, preserving comments, ordering and unsupported mod options.
    public static void merge(Path file, Map<String, String> patch) throws java.io.IOException {
        read(file); var remaining = new LinkedHashMap<>(patch); var output = new ArrayList<String>();
        if (Files.isRegularFile(file)) for (String line : Files.readAllLines(file)) { int separator = line.indexOf(':'); String key = separator > 0 ? line.substring(0, separator) : ""; @Nullable String value = remaining.remove(key); output.add(value != null ? key + ":" + value : line); }
        remaining.forEach((key, value) -> output.add(key + ":" + value));
        Path staged = file.resolveSibling("options.txt.ochre-new"); Files.write(staged, output); if (Files.exists(file)) Files.copy(file, file.resolveSibling("options.txt.ochre-backup"), StandardCopyOption.REPLACE_EXISTING); Files.move(staged, file, StandardCopyOption.REPLACE_EXISTING);
    }
    /// Groups controls in one wrapping card.
    private static VBox section(String title, SVG icon) { Label label = new Label(title); label.setGraphic(icon.createIcon(20)); VBox box = new VBox(12, label); box.setPadding(new Insets(16)); box.setPrefWidth(310); box.setMinWidth(0); box.getStyleClass().add("ochre-server-controls"); return box; }
    /// Adds a bounded setting slider with an explicit numeric readout.
    private void slider(VBox group, Map<String, String> current, String key, String caption, double min, double max, double fallback, boolean integer) {
        double initial; try { initial = Double.parseDouble(current.getOrDefault(key, Double.toString(fallback))); if (!Double.isFinite(initial)) initial = fallback; } catch (NumberFormatException error) { initial = fallback; }
        if (key.equals("fov")) { initial = 70 + 40 * initial; min = 30; max = 110; }
        Slider slider = new com.jfoenix.controls.JFXSlider(min, max, Math.max(min, Math.min(max, initial))); slider.setAccessibleText(caption); slider.setDisable(!current.isEmpty() && current.containsKey("capabilities") && !current.containsKey(key)); Label value = new Label(); value.textProperty().bind(slider.valueProperty().asString(integer ? "%.0f" : "%.2f"));
        slider.valueProperty().addListener((observable, before, after) -> changes.put(key, key.equals("fov") ? Double.toString((Math.round(after.doubleValue()) - 70) / 40.0) : integer ? Long.toString(Math.round(after.doubleValue())) : Double.toString(after.doubleValue())));
        Region space = new Region(); HBox.setHgrow(space, Priority.ALWAYS); group.getChildren().add(new VBox(6, new HBox(8, new Label(caption), space, value), slider));
    }
}
