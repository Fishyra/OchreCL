package org.jackhuang.hmcl.ui.construct;

import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.script.OchreScript;
import org.jackhuang.hmcl.script.ScriptGameHost;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/// Instance-scoped English script editor with explicit per-run permissions and a cancellable worker.
@NotNullByDefault
public final class OchreScriptDrafts extends BorderPane {
    /// User-owned scripts; never evaluated on import, selection or application startup.
    private static final Path ROOT = Metadata.HMCL_LOCAL_HOME.resolve("script-drafts");
    /// A run belongs to an instance even when its editor is reopened.
    private static final Map<String, Running> ACTIVE = new ConcurrentHashMap<>();
    /// Isolated daemon workers prevent a script from blocking JavaFX or shutdown.
    private static final ExecutorService WORKERS = Executors.newCachedThreadPool(task -> { Thread thread = new Thread(task, "OchreScript"); thread.setDaemon(true); return thread; });
    /// Default example uses only state reads and never moves or attacks automatically.
    private static final String EXAMPLE = "// English syntax; authorize game.read before running.\nprint(game.language);\nprint(player.position);\nfor (int i = 0; i < 3; i++) {\n    print(i);\n    sleep(200ms);\n}\n";
    /// Selected file in the managed library.
    private @Nullable Path selected;
    /// The game instance chosen by the surrounding operation workspace.
    private final String key;
    /// Editable source remains separate from a running immutable program.
    private final TextArea editor = new TextArea();
    /// Visible diagnostics and bounded runtime output.
    private final TextArea console = new TextArea();
    /// Collapsed output keeps short windows usable until a script actually runs.
    private final TitledPane output = new TitledPane();
    /// Saved script library, preserving older text drafts.
    private final ListView<Path> list = new com.jfoenix.controls.JFXListView<>();
    /// Compilation, execution and persistence status.
    private final Label status = new Label("Øchre Script · 实验运行器 · 导入或选中不会自动执行");
    /// Player-controlled capability switches, cleared after each execution begins.
    private final Map<String, CheckBox> grants = new LinkedHashMap<>();
    /// Compile and run controls.
    private final JFXButton run = new JFXButton("运行脚本"), stop = new JFXButton("停止");
    /// Builds a script workspace scoped to the actual selected game instance.
    public OchreScriptDrafts(String key) {
        this.key = key; editor.setTextFormatter(new TextFormatter<>(this::limitSource)); setPadding(new Insets(12)); setMinSize(0, 0); getStyleClass().add("ochre-script-workspace"); editor.getStyleClass().add("ochre-script-editor"); console.getStyleClass().add("ochre-script-console"); list.getStyleClass().add("ochre-script-list"); editor.setPromptText(EXAMPLE); editor.setWrapText(false); editor.setStyle("-fx-font-family: 'Menlo', 'Consolas', monospace; -fx-font-size: 12px;"); console.setEditable(false); console.setWrapText(true); console.setPrefRowCount(5); console.setMinHeight(65); status.setWrapText(true); setTop(status); BorderPane.setMargin(status, new Insets(0, 0, 10, 0));
        list.setMinWidth(80); list.setPrefWidth(180); list.setCellFactory(view -> new ListCell<>() { /// Shows only filenames rather than private absolute paths.
            @Override protected void updateItem(@Nullable Path path, boolean empty) { super.updateItem(path, empty); setText(path == null || empty ? "" : path.getFileName().toString()); }
        });
        list.getSelectionModel().selectedItemProperty().addListener((observable, before, value) -> { if (value == null) return; try { if (Files.size(value) > 65_536) throw new java.io.IOException("脚本超过 64 KiB"); selected = value; editor.setText(Files.readString(value)); grants.values().forEach(box -> box.setSelected(false)); } catch (Exception error) { status.setText("读取失败：" + error.getMessage()); } });
        JFXButton add = new JFXButton(), create = new JFXButton("新建脚本"), save = new JFXButton("保存"), check = new JFXButton("检查语法"); add.setGraphic(SVG.ADD.createIcon(22)); add.setAccessibleText("导入脚本"); add.setTooltip(new Tooltip("导入 .ochre 脚本，不自动执行")); save.setGraphic(SVG.CHECK.createIcon(18)); run.getStyleClass().add("ochre-primary-action"); run.setGraphic(SVG.OCHRE_PLAY.createIcon(18)); stop.setGraphic(SVG.CLOSE.createIcon(18));
        add.setOnAction(event -> { FileChooser chooser = new FileChooser(); chooser.setTitle("导入 Øchre Script"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Øchre Script", "*.ochre", "*.txt")); @Nullable Path path = Controllers.showOpenDialog(chooser); if (path == null) return; try { if (Files.size(path) > 65_536) throw new java.io.IOException("请选择不超过 64 KiB 的脚本"); saveNew(Files.readString(path)); } catch (Exception error) { status.setText(error.getMessage()); } });
        create.setOnAction(event -> saveNew(EXAMPLE)); save.setOnAction(event -> { if (selected == null) { saveNew(editor.getText()); return; } try { Files.writeString(selected, editor.getText()); status.setText("脚本已保存，尚未执行保存后的修改"); } catch (Exception error) { status.setText("保存失败：" + error.getMessage()); } });
        check.setOnAction(event -> { try { var program = OchreScript.compile(editor.getText()); status.setText("语法正确 · 所需权限：" + String.join(", ", program.permissions())); } catch (Exception error) { status.setText(error.getMessage()); } });
        FlowPane permissions = new FlowPane(12, 8); for (String capability : List.of("game.read", "settings.write", "player.look", "player.move", "player.attack")) { CheckBox box = new com.jfoenix.controls.JFXCheckBox(capability); grants.put(capability, box); permissions.getChildren().add(box); }
        Label explanation = new Label("按次授权 · 设置修改会保存 · 关闭面板会停止脚本。新版桥接需重新安装并重启游戏；移动/攻击仍需世界内实测。"); explanation.setWrapText(true);
        run.setOnAction(event -> start()); stop.setOnAction(event -> { @Nullable Running active = ACTIVE.get(key); if (active != null) { active.control.cancel(); status.setText("正在停止并释放脚本输入…"); } }); stop.setDisable(true);
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); HBox heading = new HBox(8, new Label("脚本列表"), spacer, add); VBox library = new VBox(8, heading, list, create); library.getStyleClass().add("ochre-script-library"); library.setPadding(new Insets(12)); library.setMinWidth(80); library.setPrefWidth(190); VBox.setVgrow(list, Priority.ALWAYS);
        output.setText("输出与错误"); output.setContent(console); output.setExpanded(false); output.setAnimated(false); output.getStyleClass().add("ochre-script-output"); OchreCodeEditor code = new OchreCodeEditor(editor); VBox work = new VBox(8, code, output); VBox.setVgrow(code, Priority.ALWAYS); SplitPane split = new SplitPane(library, work); split.getStyleClass().add("ochre-script-split"); split.setDividerPositions(.25); setCenter(split); VBox actions = new VBox(8, explanation, permissions, new FlowPane(8, 8, save, check, run, stop)); setBottom(actions); BorderPane.setMargin(actions, new Insets(10, 0, 0, 0)); refresh();
        sceneProperty().addListener((observable, before, after) -> { if (before != null && after == null) { @Nullable Running active = ACTIVE.get(key); if (active != null) active.control.cancel(); } });
    }
    /// Rejects oversized typing/paste before building a large editor gutter or executable buffer.
    private @Nullable TextFormatter.Change limitSource(TextFormatter.Change change) { if (change.getControlNewText().length() > 65_536) { status.setText("脚本源码不能超过 64 KiB 字符"); return null; } return change; }
    /// A single game-instance run with bounded, independently retained output.
    @NotNullByDefault private static final class Running {
        /// Cancellation token remains valid after the editor is detached.
        final OchreScript.Control control = new OchreScript.Control();
        /// At most 1000 output lines are accepted.
        final java.util.concurrent.atomic.AtomicInteger lines = new java.util.concurrent.atomic.AtomicInteger();
    }
    /// Compiles before effects, verifies every grant and starts at most one run for this instance.
    private void start() {
        try { var program = OchreScript.compile(editor.getText()); Set<String> allowed = new HashSet<>(); grants.forEach((name, box) -> { if (box.isSelected()) allowed.add(name); }); if (!allowed.containsAll(program.permissions())) throw new SecurityException("请先授权：" + String.join(", ", program.permissions().stream().filter(p -> !allowed.contains(p)).sorted().toList())); Running running = new Running(); if (ACTIVE.putIfAbsent(key, running) != null) throw new IllegalStateException("此实例已有脚本正在执行，请先停止");
            console.clear(); output.setExpanded(true); run.setDisable(true); stop.setDisable(false); status.setText("正在执行当前代码快照…"); grants.values().forEach(box -> box.setSelected(false));
            WORKERS.execute(() -> { String result; try { program.run(new ScriptGameHost(key), allowed, line -> { if (running.lines.incrementAndGet() > 1000) throw new IllegalStateException("输出超过 1000 行限制"); String text = line.length() > 4096 ? line.substring(0, 4096) : line; Platform.runLater(() -> { console.appendText(text + "\n"); if (console.getLength() > 65_536) console.deleteText(0, console.getLength() - 65_536); }); }, running.control); result = "脚本执行完成"; } catch (CancellationException error) { result = "脚本已停止"; } catch (Exception error) { result = "执行失败：" + error.getMessage(); } finally { ACTIVE.remove(key, running); } String message = result; Platform.runLater(() -> { run.setDisable(false); stop.setDisable(true); status.setText(message); }); });
        } catch (Exception error) { status.setText(error.getMessage()); }
    }
    /// Creates a unique managed script without overwriting the imported original.
    private void saveNew(String text) { try { Files.createDirectories(ROOT); Path path = ROOT.resolve("script-" + UUID.randomUUID().toString().substring(0, 8) + ".ochre"); Files.writeString(path, text); refresh(); list.getSelectionModel().select(path); status.setText("脚本已保存；点击运行前请检查代码并授权"); } catch (Exception error) { status.setText(error.getMessage()); } }
    /// Keeps legacy drafts visible while excluding unrelated files and symlinks.
    private void refresh() { try { if (!Files.isDirectory(ROOT)) return; try (var files = Files.list(ROOT)) { list.getItems().setAll(files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).filter(path -> path.toString().endsWith(".txt") || path.toString().endsWith(".ochre")).sorted().toList()); } } catch (Exception error) { status.setText("列表读取失败：" + error.getMessage()); } }
}
