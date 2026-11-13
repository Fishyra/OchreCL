package org.jackhuang.hmcl.ui.construct;

import com.jfoenix.controls.JFXButton;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.nio.file.*;

/// Local script-draft library; it never executes code before a language and game bridge are defined.
@NotNullByDefault
public final class OchreScriptDrafts extends BorderPane {
    /// User-owned text drafts, isolated from application code and automatic launch paths.
    private static final Path ROOT = Metadata.HMCL_LOCAL_HOME.resolve("script-drafts");
    /// Explicitly selected draft path.
    private @Nullable Path selected;
    /// Text can be pasted without being evaluated.
    private final TextArea editor = new TextArea();
    /// Available imported and newly created drafts.
    private final ListView<Path> list = new com.jfoenix.controls.JFXListView<>();
    /// Feedback distinguishes saved drafts from executable game automation.
    private final Label status = new Label("beta 0.3 脚本工作区预览：当前仅保存草稿，不执行代码。脚本语言与游戏内配套组件待确定。");
    /// Builds the requested left-hand script library and a pasteable editor.
    public OchreScriptDrafts() {
        setPadding(new Insets(12)); setMinSize(0, 0); editor.setPromptText("在这里粘贴脚本草稿…"); editor.setWrapText(false); status.setWrapText(true); setTop(status); BorderPane.setMargin(status, new Insets(0, 0, 12, 0));
        list.setMinWidth(80); list.setPrefWidth(180); list.setCellFactory(view -> new ListCell<>() { /// Uses filenames only; no script is executed on selection.
            @Override protected void updateItem(@Nullable Path path, boolean empty) { super.updateItem(path, empty); setText(path == null || empty ? "" : path.getFileName().toString()); }
        });
        list.getSelectionModel().selectedItemProperty().addListener((observable, before, value) -> { if (value == null) return; try { if (Files.size(value) > 1024 * 1024) throw new java.io.IOException("草稿超过 1 MiB"); selected = value; editor.setText(Files.readString(value)); } catch (Exception error) { status.setText("读取失败：" + error.getMessage()); } });
        JFXButton add = new JFXButton(), create = new JFXButton("新建草稿"), save = new JFXButton("保存草稿"); save.setGraphic(SVG.CHECK.createIcon(18));
        add.setGraphic(SVG.ADD.createIcon(22)); add.setAccessibleText("导入脚本"); add.setTooltip(new Tooltip("导入脚本文本（不执行）"));
        add.setOnAction(event -> { FileChooser chooser = new FileChooser(); chooser.setTitle("导入脚本文本（不执行）"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("脚本文本", "*.txt", "*.js", "*.lua", "*.py")); @Nullable Path path = Controllers.showOpenDialog(chooser); if (path == null) return; try { if (Files.size(path) > 1024 * 1024) throw new java.io.IOException("请选择不超过 1 MiB 的文本"); saveNew(Files.readString(path)); } catch (Exception error) { status.setText(error.getMessage()); } });
        create.setOnAction(event -> saveNew("# ØchreCL 脚本草稿\\n# 此处文本尚不会执行；读取游戏状态与控制玩家需要配套模组。\\n".replace("\\n", "\n")));
        save.setOnAction(event -> { if (selected == null) { saveNew(editor.getText()); return; } try { Files.writeString(selected, editor.getText()); status.setText("草稿已保存；尚未执行"); } catch (Exception error) { status.setText("保存失败：" + error.getMessage()); } });
        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); HBox heading = new HBox(8, new Label("脚本列表"), spacer, add); heading.setAlignment(javafx.geometry.Pos.CENTER_LEFT); VBox library = new VBox(8, heading, list, create); library.setMinWidth(80); library.setPrefWidth(190); VBox.setVgrow(list, Priority.ALWAYS);
        SplitPane split = new SplitPane(library, editor); split.setDividerPositions(.28); setCenter(split); setBottom(save); BorderPane.setMargin(save, new Insets(12, 0, 0, 0)); refresh();
    }
    /// Creates a unique draft without overwriting an imported source file.
    private void saveNew(String text) { try { Files.createDirectories(ROOT); Path path = ROOT.resolve("script-" + java.util.UUID.randomUUID().toString().substring(0, 8) + ".txt"); Files.writeString(path, text); refresh(); list.getSelectionModel().select(path); status.setText("草稿已保存；尚未执行"); } catch (Exception error) { status.setText(error.getMessage()); } }
    /// Loads only draft files in the managed library.
    private void refresh() { try { if (!Files.isDirectory(ROOT)) return; try (var files = Files.list(ROOT)) { list.getItems().setAll(files.filter(Files::isRegularFile).filter(path -> path.toString().endsWith(".txt")).sorted().toList()); } } catch (Exception error) { status.setText("列表读取失败：" + error.getMessage()); } }
}
