package org.jackhuang.hmcl.ui.construct;

import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.script.ScriptEditing;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.util.Locale;

/// Dependency-free code editing conveniences around JavaFX's native undo/redo-capable text model.
@NotNullByDefault
public final class OchreCodeEditor extends BorderPane {
    /// The workspace's source buffer remains the single source of truth.
    private final TextArea area;
    /// Noninteractive gutter shares font metrics and vertical scrolling with the source.
    private final TextArea numbers = new TextArea("1");
    /// Local, case-insensitive search; no source is sent outside the application.
    private final TextField query = new TextField();
    /// Search UI is hidden until explicitly requested.
    private final HBox search = new HBox(8);
    /// Visible caret location and shortcut discoverability.
    private final Label position = new Label();
    /// Search match feedback is independent of script execution status.
    private final Label matches = new Label();
    /// Attaches editing shortcuts without replacing the native clipboard, selection and undo handlers.
    public OchreCodeEditor(TextArea area) {
        this.area = area; getStyleClass().add("ochre-code-editor"); numbers.getStyleClass().add("ochre-code-gutter"); numbers.setEditable(false); numbers.setFocusTraversable(false); numbers.setMouseTransparent(true); numbers.setWrapText(false); numbers.setMinWidth(48); numbers.setPrefWidth(48); numbers.setMaxWidth(48); numbers.setStyle("-fx-font-family: 'Menlo', 'Consolas', monospace; -fx-font-size: 12px;");
        area.scrollTopProperty().addListener((observable, before, value) -> numbers.setScrollTop(value.doubleValue())); area.textProperty().addListener((observable, before, after) -> { updateLines(); location(); }); area.caretPositionProperty().addListener((observable, before, after) -> location());
        query.setPromptText("查找代码…"); HBox.setHgrow(query, Priority.ALWAYS); JFXButton previous = new JFXButton("上一个"), next = new JFXButton("下一个"), close = new JFXButton("关闭查找"); previous.setOnAction(event -> find(false)); next.setOnAction(event -> find(true)); close.setOnAction(event -> hideSearch()); query.setOnAction(event -> find(true)); query.addEventFilter(KeyEvent.KEY_PRESSED, event -> { if (event.getCode() == KeyCode.ESCAPE) { hideSearch(); event.consume(); } }); search.getChildren().setAll(query, previous, next, matches, close); search.setPadding(new Insets(6)); search.setVisible(false); search.setManaged(false); setTop(search);
        BorderPane input = new BorderPane(area); input.setLeft(numbers); setCenter(input); position.setPadding(new Insets(5, 10, 5, 10)); position.setTooltip(new Tooltip("Tab / Shift+Tab：缩进\n⌘或Ctrl+/：注释\n⌘或Ctrl+D：复制行\nAlt+↑/↓：移动行\n⌘或Ctrl+F：查找\n⌘或Ctrl+Z：撤销")); setBottom(position);
        area.addEventFilter(KeyEvent.KEY_TYPED, event -> { if (event.isShortcutDown() || event.isAltDown() || event.getCharacter().length() != 1) return; @Nullable ScriptEditing.Edit edit = ScriptEditing.typed(area.getText(), area.getAnchor(), area.getCaretPosition(), event.getCharacter().charAt(0)); if (edit != null) { apply(edit); event.consume(); } });
        area.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            @Nullable ScriptEditing.Edit edit = null;
            if (event.isShortcutDown() && event.getCode() == KeyCode.F) { search.setVisible(true); search.setManaged(true); if (!area.getSelectedText().isEmpty()) query.setText(area.getSelectedText()); query.requestFocus(); query.selectAll(); event.consume(); return; }
            if (event.getCode() == KeyCode.F3) { find(!event.isShiftDown()); event.consume(); return; }
            if (event.getCode() == KeyCode.TAB) edit = ScriptEditing.indent(area.getText(), area.getAnchor(), area.getCaretPosition(), event.isShiftDown());
            else if (event.isShortcutDown() && event.getCode() == KeyCode.SLASH) edit = ScriptEditing.comment(area.getText(), area.getAnchor(), area.getCaretPosition());
            else if (event.isShortcutDown() && event.getCode() == KeyCode.D) edit = ScriptEditing.duplicate(area.getText(), area.getAnchor(), area.getCaretPosition());
            else if (event.isAltDown() && (event.getCode() == KeyCode.UP || event.getCode() == KeyCode.DOWN)) edit = ScriptEditing.move(area.getText(), area.getAnchor(), area.getCaretPosition(), event.getCode() == KeyCode.DOWN);
            else if (event.getCode() == KeyCode.ENTER && !event.isShortcutDown()) edit = ScriptEditing.newline(area.getText(), area.getAnchor(), area.getCaretPosition());
            else if (event.getCode() == KeyCode.BACK_SPACE) edit = ScriptEditing.backspace(area.getText(), area.getAnchor(), area.getCaretPosition());
            if (edit != null) { apply(edit); event.consume(); }
        }); updateLines(); location();
    }
    /// Applies one atomic edit so generated delimiters and indentation can be undone normally.
    private void apply(ScriptEditing.Edit edit) { if (edit.start() != edit.end() || !edit.replacement().isEmpty()) area.replaceText(edit.start(), edit.end(), edit.replacement()); area.selectRange(edit.anchor(), edit.caret()); }
    /// Rebuilds a bounded gutter and restores its scroll after the next layout pulse.
    private void updateLines() { int count = 1; String text = area.getText(); for (int i = 0; i < text.length(); i++) if (text.charAt(i) == '\n') count++; StringBuilder labels = new StringBuilder(); for (int i = 1; i <= count; i++) { if (i > 1) labels.append('\n'); labels.append(i); } numbers.setText(labels.toString()); Platform.runLater(() -> numbers.setScrollTop(area.getScrollTop())); }
    /// Reports one-based line and column positions without changing the selection.
    private void location() { String text = area.getText(); int caret = Math.min(area.getCaretPosition(), text.length()), line = 1; for (int i = 0; i < caret; i++) if (text.charAt(i) == '\n') line++; int start = caret == 0 ? 0 : text.lastIndexOf('\n', caret - 1) + 1; position.setText("Ln " + line + "  ·  Col " + (caret - start + 1) + "     |     Tab 缩进 · ⌘/Ctrl+/ 注释 · ⌘/Ctrl+F 查找"); }
    /// Hides the search row without clearing its text.
    private void hideSearch() { search.setVisible(false); search.setManaged(false); area.requestFocus(); }
    /// Finds the next or previous literal match, wrapping once through this document.
    private void find(boolean forward) { String needle = query.getText().toLowerCase(Locale.ROOT), text = area.getText().toLowerCase(Locale.ROOT); if (needle.isEmpty()) return; int at; if (forward) { at = text.indexOf(needle, area.getSelection().getEnd()); if (at < 0) at = text.indexOf(needle); } else { at = text.lastIndexOf(needle, area.getSelection().getStart() - 1); if (at < 0) at = text.lastIndexOf(needle); } if (at < 0) matches.setText("未找到"); else { area.selectRange(at, at + needle.length()); matches.setText("已选中匹配项"); } }
}
