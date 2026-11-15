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
    /// Pending grants contain UI callbacks only and are discarded before a run begins.
    private static final Map<String, PendingGrant> GRANTS = new ConcurrentHashMap<>();
    /// One immutable permission list and its explicitly checked boxes, shared by both surfaces.
    @NotNullByDefault private record PendingGrant(String id, List<CheckBox> boxes, JFXButton approve, javafx.stage.Stage stage) { }
    /// Brief completion state drives the game overlay checkmark without retaining execution objects.
    private static final Map<String, Map<String, String>> COMPLETED = new ConcurrentHashMap<>();
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
    private final ListView<Path> list = new ListView<>();
    /// Compilation, execution and persistence status.
    private final Label status = new Label("Øchre Script · 实验运行器 · 导入或选中不会自动执行");
    /// Player-controlled capability switches, cleared after each execution begins.
    private final CheckBox automatic = new com.jfoenix.controls.JFXCheckBox("自动授予全部所需权限");
    /// Compile and run controls.
    private final JFXButton run = new JFXButton("运行脚本"), stop = new JFXButton("停止");
    /// Optional instance-management action; the main workspace supplies its own detach button.
    private final JFXButton detach = new JFXButton("在新窗口中打开");
    /// Finite motions are replaced when users rapidly switch files.
    private javafx.animation.@Nullable ParallelTransition switchMotion;
    /// Builds a script workspace scoped to the actual selected game instance.
    public OchreScriptDrafts(String key) {
        this.key = key; editor.setTextFormatter(new TextFormatter<>(this::limitSource)); setPadding(new Insets(12)); setMinSize(0, 0); getStyleClass().add("ochre-script-workspace"); editor.getStyleClass().add("ochre-script-editor"); console.getStyleClass().add("ochre-script-console"); list.getStyleClass().add("ochre-script-list"); editor.setPromptText(EXAMPLE); editor.setWrapText(true); editor.setStyle("-fx-font-family: 'Menlo', 'Consolas', monospace; -fx-font-size: 12px;"); console.setEditable(false); console.setWrapText(true); console.setPrefRowCount(5); console.setMinHeight(65); status.setWrapText(true); JFXButton help = new JFXButton(); help.setGraphic(SVG.HELP.createIcon(22)); help.setAccessibleText("脚本帮助文档"); help.setTooltip(new Tooltip("打开 Øchre Script 命令参考")); help.setOnAction(event -> org.jackhuang.hmcl.ui.FXUtils.openLink("https://fishyra.github.io/ochrecl/docs/script/")); detach.setVisible(false); detach.setManaged(false); detach.setOnAction(event -> detachEditor()); Region toolbarSpace = new Region(); HBox.setHgrow(toolbarSpace, Priority.ALWAYS); HBox toolbar = new HBox(10, status, toolbarSpace, help, detach); toolbar.setAlignment(javafx.geometry.Pos.CENTER_LEFT); setTop(toolbar); BorderPane.setMargin(toolbar, new Insets(0, 0, 10, 0));
        list.setMinWidth(80); list.setPrefWidth(180); list.setCellFactory(view -> new ListCell<>() {
            { setMinWidth(0); prefWidthProperty().bind(javafx.beans.binding.Bindings.max(0, view.widthProperty().subtract(24))); maxWidthProperty().bind(prefWidthProperty()); setTextOverrun(OverrunStyle.ELLIPSIS); setEllipsisString("…"); }
            /// Shows only filenames rather than private absolute paths.
            @Override protected void updateItem(@Nullable Path path, boolean empty) { super.updateItem(path, empty); setText(path == null || empty ? "" : path.getFileName().toString()); setGraphic(path == null || empty ? null : scriptFileIcon()); setTooltip(path == null || empty ? null : new Tooltip(path.getFileName().toString())); }
        });
        list.getSelectionModel().selectedItemProperty().addListener((observable, before, value) -> { if (value == null) return; try { if (Files.size(value) > 65_536) throw new java.io.IOException("脚本超过 64 KiB"); selected = value; editor.setText(Files.readString(value)); animateScriptChange(); } catch (Exception error) { status.setText("读取失败：" + error.getMessage()); } });
        JFXButton add = new JFXButton(), create = new JFXButton("新建脚本"), save = new JFXButton("保存"), check = new JFXButton("检查语法"); add.setGraphic(SVG.ADD.createIcon(22)); add.setAccessibleText("导入脚本"); add.setTooltip(new Tooltip("导入 .ochre 脚本，不自动执行")); save.setGraphic(SVG.CHECK.createIcon(18)); run.getStyleClass().add("ochre-primary-action"); run.setGraphic(SVG.OCHRE_PLAY.createIcon(18)); stop.setGraphic(SVG.CLOSE.createIcon(18));
        add.setOnAction(event -> { FileChooser chooser = new FileChooser(); chooser.setTitle("导入 Øchre Script"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("Øchre Script", "*.ochre", "*.txt")); @Nullable Path path = Controllers.showOpenDialog(chooser); if (path == null) return; try { if (Files.size(path) > 65_536) throw new java.io.IOException("请选择不超过 64 KiB 的脚本"); importScript(path); } catch (Exception error) { status.setText(error.getMessage()); } });
        create.setOnAction(event -> saveNew(EXAMPLE)); save.setOnAction(event -> { if (selected == null) { saveNew(editor.getText()); return; } try { Files.writeString(selected, editor.getText()); status.setText("脚本已保存，尚未执行保存后的修改"); } catch (Exception error) { status.setText("保存失败：" + error.getMessage()); } });
        check.setOnAction(event -> { try { var program = OchreScript.compile(editor.getText(), OchreScriptDrafts::readModule); status.setText("语法正确 · 所需权限：" + String.join(", ", program.permissions())); } catch (Exception error) { status.setText(error.getMessage()); } });
        automatic.setSelected(org.jackhuang.hmcl.script.ScriptPreferences.autoGrant()); automatic.setOnAction(event -> { try { org.jackhuang.hmcl.script.ScriptPreferences.save(org.jackhuang.hmcl.script.ScriptPreferences.policy(), org.jackhuang.hmcl.script.ScriptPreferences.repeatCount(), automatic.isSelected()); } catch (Exception error) { status.setText(error.getMessage()); } });
        JFXButton rename = new JFXButton("重命名"), delete = new JFXButton("删除"); rename.setGraphic(SVG.EDIT.createIcon(16)); delete.setGraphic(SVG.DELETE.createIcon(16));
        rename.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull()); delete.disableProperty().bind(list.getSelectionModel().selectedItemProperty().isNull());
        rename.setOnAction(event -> renameSelected()); delete.setOnAction(event -> deleteSelected());
        MenuItem renameItem = new MenuItem("重命名"), deleteItem = new MenuItem("删除"); renameItem.setOnAction(event -> renameSelected()); deleteItem.setOnAction(event -> deleteSelected()); list.setContextMenu(new ContextMenu(renameItem, deleteItem));
        Label explanation = new Label("按次授权 · 设置修改会保存 · 关闭面板会停止脚本。新版桥接会在下次启动游戏时加载；输入与方块操作遵守游戏和服务器权限。"); explanation.setWrapText(true);
        run.setOnAction(event -> start()); stop.setOnAction(event -> { @Nullable Running active = ACTIVE.get(key); if (active != null) { active.control.cancel(); status.setText("正在停止并释放脚本输入…"); } }); stop.setDisable(true);
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); HBox heading = new HBox(8, new Label("脚本列表"), spacer, add); VBox library = new VBox(8, heading, list, new FlowPane(4, 4, rename, delete), create); library.getStyleClass().add("ochre-script-library"); library.setPadding(new Insets(12)); library.setMinWidth(80); library.setPrefWidth(190); VBox.setVgrow(list, Priority.ALWAYS);
        output.setText("输出与错误"); output.setContent(console); output.setExpanded(false); output.setAnimated(false); output.getStyleClass().add("ochre-script-output"); OchreCodeEditor code = new OchreCodeEditor(editor); VBox work = new VBox(8, code, output); VBox.setVgrow(code, Priority.ALWAYS); SplitPane split = new SplitPane(library, work); split.getStyleClass().add("ochre-script-split"); split.setDividerPositions(.25); setCenter(split); VBox actions = new VBox(8, explanation, automatic, new FlowPane(8, 8, save, check, run, stop)); setBottom(actions); BorderPane.setMargin(actions, new Insets(10, 0, 0, 0)); refresh(); sceneProperty().addListener((observable, before, after) -> { if (after != null) Platform.runLater(this::animateEntry); });

    }
    /// Enables the shared detach action for an instance-management workspace.
    public void enableDetach() { detach.setVisible(true); detach.setManaged(true); }
    /// Animates content on entry without delaying editing or capturing keyboard input.
    private void animateEntry() { var fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(180), this); fade.setFromValue(.15); fade.setToValue(1); var move = new javafx.animation.TranslateTransition(javafx.util.Duration.millis(180), this); move.setFromY(8); move.setToY(0); new javafx.animation.ParallelTransition(fade, move).play(); }
    /// Replaces an in-flight file transition so rapid selection cannot leave the editor invisible.
    private void animateScriptChange() { if (switchMotion != null) switchMotion.stop(); var fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(140), editor); fade.setFromValue(.35); fade.setToValue(1); var move = new javafx.animation.TranslateTransition(javafx.util.Duration.millis(140), editor); move.setFromX(5); move.setToX(0); switchMotion = new javafx.animation.ParallelTransition(fade, move); switchMotion.play(); }
    /// Moves this exact buffer and run to a new stage and restores it when the stage closes.
    private void detachEditor() { if (!(getParent() instanceof VBox parent)) return; int index = parent.getChildren().indexOf(this); @Nullable var previousScene = getScene(); JFXButton restore = new JFXButton("脚本已在独立窗口打开 · 显示窗口"); parent.getChildren().set(index, restore); var host = new BorderPane(this); host.getStyleClass().add("ochre-content-panel"); host.setPadding(new Insets(16)); var stage = new javafx.stage.Stage(); stage.setTitle("Øchre Script · " + Path.of(key).getFileName()); var scene = new javafx.scene.Scene(host, 1040, 760); if (previousScene != null) { scene.getStylesheets().addAll(previousScene.getStylesheets()); host.setStyle(previousScene.getRoot().getStyle()); } stage.setScene(scene); stage.setMinWidth(620); stage.setMinHeight(450); detach.setDisable(true); restore.setOnAction(event -> { stage.show(); stage.toFront(); }); stage.setOnHidden(event -> { host.setCenter(null); detach.setDisable(false); if (restore.getParent() == parent) parent.getChildren().set(index, this); else dispose(); }); stage.show(); focusEditor(); }
    /// Opens rounded dialogs on this workspace's own window, including detached script editors.
    private javafx.stage.Stage showWorkspaceDialog(Region content) {
        @Nullable var scene = getScene(); @Nullable javafx.stage.Window owner = scene == null ? Controllers.getStage() : scene.getWindow(); var stage = new javafx.stage.Stage(javafx.stage.StageStyle.TRANSPARENT); if (owner != null) { stage.initOwner(owner); stage.initModality(javafx.stage.Modality.WINDOW_MODAL); }
        content.getStyleClass().addAll("ochre-content-panel", "ochre-script-workspace", "ochre-script-confirm"); var root = new StackPane(content); root.setPadding(new Insets(10)); root.setStyle("-fx-background-color: transparent;" + (scene != null ? scene.getRoot().getStyle() : owner != null && owner.getScene() != null ? owner.getScene().getRoot().getStyle() : "") + ";-fx-background-color: transparent;"); var dialogScene = new javafx.scene.Scene(root); dialogScene.setFill(javafx.scene.paint.Color.TRANSPARENT); if (scene != null) dialogScene.getStylesheets().addAll(scene.getStylesheets()); else if (owner != null && owner.getScene() != null) dialogScene.getStylesheets().addAll(owner.getScene().getStylesheets()); stage.setScene(dialogScene); stage.setTitle("Øchre Script");
        content.addEventHandler(DialogCloseEvent.CLOSE, event -> { closeWorkspaceDialog(stage); event.consume(); }); dialogScene.setOnKeyPressed(event -> { if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) { closeWorkspaceDialog(stage); event.consume(); } }); stage.show(); stage.toFront(); content.setOpacity(0); content.setScaleX(.96); content.setScaleY(.96); var fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(160), content); fade.setToValue(1); var scale = new javafx.animation.ScaleTransition(javafx.util.Duration.millis(180), content); scale.setToX(1); scale.setToY(1); new javafx.animation.ParallelTransition(fade, scale).play(); return stage;
    }
    /// Closes every script dialog using the same bounded fade and scale transition.
    private static void closeWorkspaceDialog(javafx.stage.Stage stage) { if (!stage.isShowing() || Boolean.TRUE.equals(stage.getProperties().get("closing"))) return; stage.getProperties().put("closing", true); var node = stage.getScene().getRoot(); var fade = new javafx.animation.FadeTransition(javafx.util.Duration.millis(120), node); fade.setToValue(0); var scale = new javafx.animation.ScaleTransition(javafx.util.Duration.millis(120), node); scale.setToX(.96); scale.setToY(.96); var motion = new javafx.animation.ParallelTransition(fade, scale); motion.setOnFinished(event -> stage.close()); motion.play(); }
    /// Waits cancellably for explicit consent before discarding a game interface's unsubmitted input.
    private boolean confirmExitGameUi(String screen, OchreScript.Control control) throws Exception {
        CompletableFuture<Boolean> answer = new CompletableFuture<>(); @Nullable var waiting = ACTIVE.get(key); if (waiting != null) waiting.resume = answer; Platform.runLater(() -> {
            try { if (answer.isDone()) return; status.setText("脚本已暂停，等待退出游戏界面的确认"); @Nullable var active = ACTIVE.get(key); if (active != null) active.statement = "等待确认：退出 " + screen + " 后继续";
            Label text = new Label("游戏当前处于 " + screen + "。是否退出当前界面并继续执行？未提交的输入可能丢失。"); text.setWrapText(true); JFXButton resume = new JFXButton("退出界面并继续"), cancel = new JFXButton("停止脚本"); resume.getStyleClass().add("ochre-primary-action"); VBox pane = new VBox(14, new Label("脚本已暂停"), text, new FlowPane(8, 8, resume, cancel)); pane.setPadding(new Insets(22)); pane.setPrefWidth(450);
            var stage = showWorkspaceDialog(pane); stage.setOnHidden(event -> answer.complete(false)); resume.setOnAction(event -> { answer.complete(true); closeWorkspaceDialog(stage); status.setText("继续执行脚本…"); }); cancel.setOnAction(event -> { answer.complete(false); closeWorkspaceDialog(stage); }); answer.whenComplete((value, error) -> Platform.runLater(() -> closeWorkspaceDialog(stage))); } catch (Throwable error) { answer.completeExceptionally(error); }
        });
        try { while (true) { control.check(); try { return answer.get(100, TimeUnit.MILLISECONDS); } catch (TimeoutException ignored) { var context = org.jackhuang.hmcl.bridge.BridgeManager.request(key, "script.context", Map.of()); if ("true".equals(context.get("in_world")) && "false".equals(context.get("menu")) && "false".equals(context.get("paused"))) { answer.complete(true); return true; } } } } finally { answer.complete(false); if (waiting != null) waiting.resume = null; }
    }
    /// Uses the same folded-paper document artwork as the operating-system file icon.
    private static javafx.scene.image.ImageView scriptFileIcon() { var image = new javafx.scene.image.ImageView(new javafx.scene.image.Image(java.util.Objects.requireNonNull(OchreScriptDrafts.class.getResourceAsStream("/assets/img/ochre-script-file.png")))); image.setFitWidth(24); image.setFitHeight(24); image.setPreserveRatio(true); return image; }
    /// Saves the current buffer before renaming, and keeps the selection on the new filename.
    private void renameSelected() {
        @Nullable Path path = list.getSelectionModel().getSelectedItem(); if (path == null) return;
        TextField name = new TextField(path.getFileName().toString()); Label message = new Label("请输入脚本名称；无需填写 .ochre 后缀"); JFXButton confirm = new JFXButton("重命名"), cancel = new JFXButton("取消"); VBox dialog = new VBox(12, new Label("重命名脚本"), name, message, new FlowPane(8, 8, confirm, cancel)); dialog.setPadding(new Insets(20)); dialog.setPrefWidth(420);
        confirm.setOnAction(event -> { try { if (path.equals(selected)) Files.writeString(path, editor.getText()); Path renamed = org.jackhuang.hmcl.script.ScriptFiles.rename(ROOT, path, name.getText()); selected = renamed; refresh(); list.getSelectionModel().select(renamed); status.setText("脚本已重命名"); dialog.fireEvent(new DialogCloseEvent()); } catch (Exception error) { message.setText(error.getMessage()); } }); cancel.setOnAction(event -> dialog.fireEvent(new DialogCloseEvent())); name.setOnAction(event -> confirm.fire()); showWorkspaceDialog(dialog); Platform.runLater(() -> { name.requestFocus(); name.selectRange(0, Math.max(0, name.getText().lastIndexOf('.'))); });
    }
    /// Confirms deletion and retains the complete buffer in the local recovery folder.
    private void deleteSelected() {
        @Nullable Path path = list.getSelectionModel().getSelectedItem(); if (path == null) return;
        Label message = new Label("删除 “" + path.getFileName() + "”？文件会移入本地脚本回收目录。"); message.setWrapText(true); JFXButton confirm = new JFXButton("删除脚本"), cancel = new JFXButton("取消"); VBox dialog = new VBox(12, new Label("删除脚本"), message, new FlowPane(8, 8, confirm, cancel)); dialog.setPadding(new Insets(20)); dialog.setPrefWidth(420);
        confirm.setOnAction(event -> { try { if (path.equals(selected)) Files.writeString(path, editor.getText()); org.jackhuang.hmcl.script.ScriptFiles.remove(ROOT, path); selected = null; list.getSelectionModel().clearSelection(); editor.clear(); refresh(); status.setText("脚本已移入本地回收目录"); dialog.fireEvent(new DialogCloseEvent()); } catch (Exception error) { message.setText(error.getMessage()); } }); cancel.setOnAction(event -> dialog.fireEvent(new DialogCloseEvent())); showWorkspaceDialog(dialog);
    }
    /// Rejects oversized typing/paste before building a large editor gutter or executable buffer.
    private @Nullable TextFormatter.Change limitSource(TextFormatter.Change change) { if (change.getControlNewText().length() > 65_536) { status.setText("脚本源码不能超过 64 KiB 字符"); return null; } return change; }
    /// A single game-instance run with bounded, independently retained output.
    @NotNullByDefault private static final class Running {
        /// Cancellation token remains valid after the editor is detached.
        final OchreScript.Control control = new OchreScript.Control(org.jackhuang.hmcl.script.ScriptPreferences.policy(), org.jackhuang.hmcl.script.ScriptPreferences.repeatCount());
        /// Current immutable source statement, shared with the game overlay.
        volatile String statement = "";
        /// Pending explicit resume decision, also available from the in-game surface.
        volatile @Nullable CompletableFuture<Boolean> resume;
        /// Repeated failure outside a loaded world drives a persistent warning icon.
        volatile boolean warning;
        /// At most 1000 output lines are accepted.
        final java.util.concurrent.atomic.AtomicInteger lines = new java.util.concurrent.atomic.AtomicInteger();
    }
    /// Reads only a managed, non-symlink public module before compilation and authorization.
    private static String readModule(String name)throws Exception{Path path=ROOT.resolve(name).normalize();if(!path.getParent().equals(ROOT)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)>65_536)throw new IllegalArgumentException("Invalid managed script module: "+name);return Files.readString(path);}
    /// Compiles before effects, verifies every grant and starts at most one run for this instance.
    private void start() {
        try {
            String snapshot = editor.getText(); var program = OchreScript.compile(snapshot, OchreScriptDrafts::readModule);
            if (org.jackhuang.hmcl.script.ScriptPreferences.autoGrant() || program.permissions().isEmpty()) { execute(program, program.permissions(), snapshot); return; }
            VBox pane = new VBox(14); pane.setPadding(new Insets(24)); pane.setMaxWidth(650); pane.getStyleClass().add("jfx-dialog-layout");
            Label title = new Label("运行脚本所需权限"); title.getStyleClass().add("ochre-instance-title"); pane.getChildren().add(title);
            List<CheckBox> boxes = new ArrayList<>();
            for (String capability : program.permissions().stream().sorted().toList()) { Label name = new Label(capability); Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); CheckBox box = new com.jfoenix.controls.JFXCheckBox(); box.setAccessibleText("允许 " + capability); box.setUserData(capability); boxes.add(box); pane.getChildren().add(new HBox(12, name, spacer, box)); }
            JFXButton approve = new JFXButton("授权并运行"), cancel = new JFXButton("取消"); approve.setDisable(true);
            for (CheckBox box : boxes) box.selectedProperty().addListener((observable, before, after) -> approve.setDisable(boxes.stream().anyMatch(item -> !item.isSelected())));
            approve.setOnAction(event -> { GRANTS.remove(key); pane.fireEvent(new DialogCloseEvent()); execute(program, program.permissions(), snapshot); }); cancel.setOnAction(event -> pane.fireEvent(new DialogCloseEvent()));
            pane.getChildren().add(new FlowPane(10, 8, approve, cancel)); var dialog = showWorkspaceDialog(pane); String grantId = UUID.randomUUID().toString(); var pending = new PendingGrant(grantId, List.copyOf(boxes), approve, dialog); @Nullable var previous = GRANTS.put(key, pending); if (previous != null) closeWorkspaceDialog(previous.stage()); approve.setOnAction(event -> { if (boxes.stream().anyMatch(box -> !box.isSelected()) || !GRANTS.remove(key, pending)) return; pane.fireEvent(new DialogCloseEvent()); execute(program, program.permissions(), snapshot); }); cancel.setOnAction(event -> { GRANTS.remove(key, pending); pane.fireEvent(new DialogCloseEvent()); }); dialog.setOnHidden(event -> GRANTS.remove(key, pending));
        } catch (Exception error) { status.setText(error.getMessage()); }
    }
    /// Executes an immutable editor snapshot after required per-run grants were approved.
    private void execute(OchreScript.Program program, Set<String> allowed, String snapshot) {
        Running running = new Running(); if (ACTIVE.putIfAbsent(key, running) != null) { status.setText("此实例已有脚本正在执行，请先停止"); return; }
        COMPLETED.remove(key); String[] sourceLines = snapshot.split("\\R", -1); running.control.observe(line -> running.statement = !running.control.sourceLabel().isEmpty() ? running.control.sourceLabel() + " · Line " + line : "Line " + line + ": " + (line > 0 && line <= sourceLines.length ? sourceLines[line-1].strip() : ""));
        console.clear(); output.setExpanded(true); run.setDisable(true); stop.setDisable(false); status.setText("正在执行当前代码快照…");
        running.control.observeRecovery(line -> { if (running.warning) try { var context = org.jackhuang.hmcl.bridge.BridgeManager.request(key, "script.context", Map.of()); if ("true".equals(context.get("in_world"))) running.warning = false; } catch (Exception ignored) { } });
        running.control.observeFailures((line, attempts) -> { if (attempts >= 3) try { var context = org.jackhuang.hmcl.bridge.BridgeManager.request(key, "script.context", Map.of()); running.warning = "false".equals(context.get("in_world")); } catch (Exception ignored) { } });
        WORKERS.execute(() -> { String result; try { try { org.jackhuang.hmcl.bridge.BridgeManager.request(key, "script.window.focus", Map.of()); } catch (Exception ignored) { } program.run(new ScriptGameHost(key, this::confirmExitGameUi), allowed, line -> { if (running.lines.incrementAndGet() > 1000) throw new IllegalStateException("输出超过 1000 行限制"); String text = line.length() > 4096 ? line.substring(0, 4096) : line; Platform.runLater(() -> { console.appendText(text + "\n"); if (console.getLength() > 65_536) console.deleteText(0, console.getLength() - 65_536); }); }, running.control); result = "脚本执行完成"; } catch (CancellationException error) { result = "脚本已停止"; } catch (Exception error) { result = "执行失败：" + error.getMessage(); } finally { ACTIVE.remove(key, running); } COMPLETED.put(key, Map.of("status", result.equals("脚本执行完成") ? "success" : "failed", "statement", result, "finished", Long.toString(System.nanoTime()))); String message = result; Platform.runLater(() -> { run.setDisable(false); stop.setDisable(true); status.setText(message); }); });
    }
    /// Gives the main-window editor the same keyboard focus as the detached workspace.
    public void focusEditor() { editor.requestFocus(); }
    /// Explicit ownership cleanup permits moving an editor to a new Stage without cancelling its run.
    public void dispose() { @Nullable Running active = ACTIVE.get(key); if (active != null) active.control.cancel(); }
    /// Exposes only managed basenames, never arbitrary directories or symlink targets.
    public static java.util.@org.jetbrains.annotations.Unmodifiable List<String> libraryNames() { try { if (!Files.isDirectory(ROOT)) return List.of(); try (var files = Files.list(ROOT)) { return files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).filter(path -> path.toString().endsWith(".ochre") || path.toString().endsWith(".txt")).map(path -> path.getFileName().toString()).sorted().limit(4096).toList(); } } catch (Exception error) { return List.of(); } }
    /// The overlay can request only an existing managed script; execution still passes through the same grant prompt.
    public static void runFromOverlay(String key, String name) {
        try { Path path = ROOT.resolve(name).normalize(); if (!path.getParent().equals(ROOT) || !libraryNames().contains(name) || Files.size(path) > 65_536) throw new IllegalArgumentException("无效脚本文件"); OchreScriptDrafts workspace = new OchreScriptDrafts(key); workspace.editor.setText(Files.readString(path)); if (!org.jackhuang.hmcl.script.ScriptPreferences.autoGrant()) { @Nullable var stage = Controllers.getStage(); if (stage != null) { stage.show(); stage.toFront(); } } workspace.start(); } catch (Exception error) { COMPLETED.put(key, Map.of("status", "failed", "statement", error.getMessage() == null ? "无法运行脚本" : error.getMessage(), "finished", Long.toString(System.nanoTime()))); }
    }
    /// Confirms the pending resume request without approving unrelated future prompts.
    public static void resumeFor(String key) { @Nullable var active = ACTIVE.get(key); if (active != null && active.resume != null) active.resume.complete(true); }
    /// Cancels the instance run requested from either the launcher or the in-game surface.
    public static void stopFor(String key) { @Nullable Running active = ACTIVE.get(key); if (active != null) active.control.cancel(); @Nullable var pending = GRANTS.remove(key); if (pending != null) Platform.runLater(() -> closeWorkspaceDialog(pending.stage())); }
    /// Publishes an immutable grant snapshot on the JavaFX thread before bridge polling.
    public static java.util.@org.jetbrains.annotations.Unmodifiable Map<String, String> grantState(String key) { @Nullable var pending = GRANTS.get(key); if (pending == null) return Map.of(); Map<String, String> fields = new LinkedHashMap<>(); fields.put("status", "permission"); fields.put("grant_id", pending.id()); fields.put("grant_count", Integer.toString(pending.boxes().size())); for (int i = 0; i < pending.boxes().size(); i++) { var box = pending.boxes().get(i); fields.put("grant." + i, String.valueOf(box.getUserData())); fields.put("grant_checked." + i, Boolean.toString(box.isSelected())); } return Map.copyOf(fields); }
    /// Handles only a matching pending request; no permission can be toggled after its run starts.
    public static void grantAction(String key, String action) { @Nullable var pending = GRANTS.get(key); if (pending == null) return; String prefix = "grant:" + pending.id() + ":"; if (!action.startsWith(prefix)) return; String value = action.substring(prefix.length()); if (value.equals("approve")) { if (pending.boxes().stream().allMatch(CheckBox::isSelected)) pending.approve().fire(); } else try { int index = Integer.parseInt(value); if (index >= 0 && index < pending.boxes().size()) { var box = pending.boxes().get(index); box.setSelected(!box.isSelected()); } } catch (NumberFormatException ignored) { } }
    /// Clears a warning after a real world is available, preserving any explicit pause state.
    public static void clearWarning(String key) { @Nullable var active = ACTIVE.get(key); if(active != null) active.warning = false; }
    /// Snapshot has no executable source beyond the current short display line.
    public static java.util.@org.jetbrains.annotations.Unmodifiable Map<String, String> overlayState(String key) { @Nullable Running active = ACTIVE.get(key); if (active != null) return Map.of("status", active.warning ? "warning" : active.resume != null ? "paused" : "running", "statement", active.statement); @Nullable var result = COMPLETED.get(key); if (result != null && System.nanoTime() - Long.parseLong(result.get("finished")) < 5_000_000_000L) return result; return Map.of("status", "idle", "statement", ""); }
    /// Imports using the original basename, adding a suffix only when that name already exists.
    public void importScript(Path source) { try { Files.createDirectories(ROOT); String name = source.getFileName().toString(); Path target = ROOT.resolve(name); int suffix = 2; while (Files.exists(target)) { int dot = name.lastIndexOf('.'); String base = dot < 0 ? name : name.substring(0, dot), extension = dot < 0 ? ".ochre" : name.substring(dot); target = ROOT.resolve(base + " (" + suffix++ + ")" + extension); } if (Files.size(source) > 65_536) throw new java.io.IOException("脚本超过 64 KiB"); Files.copy(source, target); refresh(); list.getSelectionModel().select(target); status.setText("脚本已导入：" + target.getFileName()); } catch (Exception error) { status.setText("导入失败：" + error.getMessage()); } }
    /// Creates a unique managed script without overwriting the imported original.
    private void saveNew(String text) { try { Files.createDirectories(ROOT); Path path = ROOT.resolve("script-" + UUID.randomUUID().toString().substring(0, 8) + ".ochre"); Files.writeString(path, text); refresh(); list.getSelectionModel().select(path); status.setText("脚本已保存；点击运行前请检查代码并授权"); } catch (Exception error) { status.setText(error.getMessage()); } }
    /// Keeps legacy drafts visible while excluding unrelated files and symlinks.
    private void refresh() { try { if (!Files.isDirectory(ROOT)) return; try (var files = Files.list(ROOT)) { list.getItems().setAll(files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).filter(path -> path.toString().endsWith(".txt") || path.toString().endsWith(".ochre")).sorted().toList()); } } catch (Exception error) { status.setText("列表读取失败：" + error.getMessage()); } }
}
