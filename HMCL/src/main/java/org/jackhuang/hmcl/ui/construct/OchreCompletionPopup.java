package org.jackhuang.hmcl.ui.construct;

import javafx.animation.PauseTransition;
import javafx.geometry.Bounds;
import javafx.geometry.Rectangle2D;
import javafx.scene.Parent;
import javafx.scene.control.*;
import javafx.scene.control.skin.TextAreaSkin;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.stage.Popup;
import javafx.stage.Screen;
import javafx.util.Duration;
import org.jackhuang.hmcl.script.ScriptCompletion;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Keyboard-first local completion popup anchored to the text caret; it never acquires editor focus.
@NotNullByDefault
final class OchreCompletionPopup {
    /// Native source buffer and undo model.
    private final TextArea area;
    /// Owned popup automatically hides with the source window.
    private final Popup popup = new Popup();
    /// Non-focusable candidates keep all typing in the source buffer.
    private final ListView<ScriptCompletion.Item> list = new ListView<>();
    /// Selected command description wraps without horizontal scrolling.
    private final Label detail = new Label();
    /// Popup root receives the same theme stylesheets as the editor.
    private final BorderPane surface = new BorderPane();
    /// Persistent argument hint displayed beneath the source.
    private final Label hint;
    /// Small delay avoids flashing during multi-character edits and IME composition.
    private final PauseTransition delay = new PauseTransition(Duration.millis(140));
    /// Last source-bound suggestions, absent when nothing can be accepted.
    private ScriptCompletion.@Nullable Result result;
    /// Internal edits must not reopen their own completion list.
    private boolean accepting;
    /// Protected visual-line edge used when popup content changes height on a later pulse.
    private double popupLineEdge;
    /// Below-caret lists grow down; above-caret lists must grow away from the source line.
    private boolean popupBelow;
    /// Suppresses automatic completion until IME text is committed.
    private boolean composing;

    /// Installs keyboard handling ahead of the editor's existing indentation/newline shortcuts.
    OchreCompletionPopup(TextArea area, Label hint) {
        this.area=area;this.hint=hint;surface.getStyleClass().addAll("root","ochre-completion");surface.setId("ochre-completion-popup");surface.setCenter(list);surface.setBottom(detail);detail.setWrapText(true);detail.setMinWidth(0);detail.setMaxWidth(Double.MAX_VALUE);detail.getStyleClass().add("ochre-completion-detail");list.setFocusTraversable(false);list.setFixedCellSize(32);list.getStyleClass().add("ochre-completion-list");
        list.setCellFactory(view->new ListCell<>() {
            /// Displays a complete qualified name; full usage remains visible in the detail panel.
            @Override protected void updateItem(ScriptCompletion.@Nullable Item item,boolean empty){super.updateItem(item,empty);setText(empty||item==null?null:item.name());setAccessibleText(empty||item==null?"":item.signature());}
        });
        list.getSelectionModel().selectedItemProperty().addListener((obs,old,item)->detail.setText(item==null?"":item.signature()+"\n"+item.description()+"\n↑↓ 选择 · Tab / Enter 补全 · Esc 关闭"));
        list.addEventFilter(MouseEvent.MOUSE_PRESSED,event->{if(event.getButton()!=MouseButton.PRIMARY)return;@Nullable javafx.scene.Node node=event.getPickResult().getIntersectedNode();while(node!=null&&!(node instanceof ListCell<?>))node=node.getParent();if(node instanceof ListCell<?> cell&&!cell.isEmpty()){list.getSelectionModel().select(cell.getIndex());accept();event.consume();}});
        popup.heightProperty().addListener(obs->{if(popup.isShowing())popup.setY(popupBelow?popupLineEdge:popupLineEdge-popup.getHeight());});
        popup.getContent().add(surface);popup.setAutoFix(false);popup.setAnchorLocation(javafx.stage.PopupWindow.AnchorLocation.WINDOW_TOP_LEFT);popup.setAutoHide(true);popup.setHideOnEscape(true);popup.setOnHidden(event->result=null);delay.setOnFinished(event->refresh(false));
        area.textProperty().addListener((obs,old,text)->{updateHint();if(!accepting&&!composing)delay.playFromStart();});area.caretPositionProperty().addListener((obs,old,value)->{updateHint();if(popup.isShowing()&&!accepting)delay.playFromStart();});
        area.focusedProperty().addListener((obs,old,focused)->{if(!focused)hide();});area.sceneProperty().addListener((obs,old,scene)->{if(old!=scene)hide();});area.widthProperty().addListener(obs->hide());area.heightProperty().addListener(obs->hide());area.addEventFilter(ScrollEvent.SCROLL,event->hide());area.addEventFilter(MouseEvent.MOUSE_PRESSED,event->hide());
        area.addEventFilter(InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,event->{composing=!event.getComposed().isEmpty();if(composing)hide();else delay.playFromStart();});area.addEventFilter(KeyEvent.KEY_PRESSED,this::key);
    }
    /// Handles only completion shortcuts, leaving ordinary editing to JavaFX and the code editor.
    private void key(KeyEvent event) {
        if(event.getCode()==KeyCode.SPACE&&event.isControlDown()){delay.stop();refresh(true);event.consume();return;}
        if(!popup.isShowing())return;
        if(event.getCode()==KeyCode.ESCAPE){hide();event.consume();}
        else if((event.getCode()==KeyCode.TAB||event.getCode()==KeyCode.ENTER)&&!event.isShiftDown()&&!event.isAltDown()&&!event.isShortcutDown()){accept();event.consume();}
        else if((event.getCode()==KeyCode.UP||event.getCode()==KeyCode.DOWN)&&!event.isAltDown()&&!event.isShortcutDown()){int size=list.getItems().size(),at=list.getSelectionModel().getSelectedIndex();if(size>0){int next=Math.floorMod(at+(event.getCode()==KeyCode.DOWN?1:-1),size);list.getSelectionModel().select(next);list.scrollTo(next);}event.consume();}
        else if(event.getCode()==KeyCode.LEFT||event.getCode()==KeyCode.RIGHT||event.isShortcutDown())hide();
    }
    /// Measures the caret in screen coordinates and fits the list to the current editor/window.
    private void refresh(boolean explicit) {
        if(composing||(!explicit&&!area.isFocused())||area.getScene()==null||area.getScene().getWindow()==null||!area.getScene().getWindow().isShowing()||area.getAnchor()!=area.getCaretPosition()){hide();return;}
        var next=ScriptCompletion.suggest(area.getText(),area.getCaretPosition(),explicit);if(next.items().isEmpty()){hide();return;}result=next;list.getItems().setAll(next.items());list.getSelectionModel().selectFirst();double width=Math.max(120,Math.min(420,area.getWidth()-16));surface.setPrefWidth(width);surface.setMaxWidth(width);list.setPrefHeight(Math.min(7,next.items().size())*32+4);detail.setMaxHeight(120);
        surface.getStylesheets().setAll(area.getScene().getStylesheets());for(@Nullable Parent parent=area;parent!=null;parent=parent.getParent())for(String sheet:parent.getStylesheets())if(!surface.getStylesheets().contains(sheet))surface.getStylesheets().add(sheet);
        // Finish wrapping and footer layout before measuring the current visual line.
        area.getScene().getRoot().applyCss();area.getScene().getRoot().layout();
        @Nullable Bounds bounds=area.getSkin() instanceof TextAreaSkin skin?area.localToScreen(skin.getCaretBounds()):null;
        if(bounds==null){hide();return;}
        Rectangle2D screen=Screen.getScreensForRectangle(bounds.getMinX(),bounds.getMinY(),Math.max(1,bounds.getWidth()),Math.max(1,bounds.getHeight())).stream().findFirst().orElse(Screen.getPrimary()).getVisualBounds();
        double lineTop=bounds.getMinY()-8,lineBottom=bounds.getMaxY()+8;
        double below=screen.getMaxY()-8-lineBottom,above=lineTop-screen.getMinY()-8;
        boolean down=below>=300||below>=above;
        double available=Math.min(300,down?below:above);
        if(available<110){hide();return;}
        width=Math.min(width,screen.getWidth()-48);
        surface.setMinWidth(width);surface.setPrefWidth(width);surface.setMaxWidth(width);
        double height=Math.min(available-32,Math.min(6,next.items().size())*32+104);
        surface.setMinHeight(0);surface.setPrefHeight(height);surface.setMaxHeight(height);
        list.setMinHeight(32);list.setPrefHeight(Math.min(6,next.items().size())*32+4);
        detail.setMinHeight(0);detail.setMaxHeight(80);
        double x=Math.max(screen.getMinX()+8,Math.min(bounds.getMinX(),screen.getMaxX()-width-8));
        popupBelow=down;popupLineEdge=down?lineBottom:lineTop;
        // Disable Popup's automatic edge correction: it can move a below-caret popup over the text.
        if(!popup.isShowing())popup.show(area,x,down?lineBottom:lineTop-height);
        surface.applyCss();surface.layout();
        popup.setX(Math.max(screen.getMinX()+8,Math.min(x,screen.getMaxX()-popup.getWidth()-8)));popup.setY(down?lineBottom:lineTop-popup.getHeight());
    }
    /// Accepts one atomic replacement and leaves the caret ready to supply function arguments.
    private void accept() {var snapshot=result;@Nullable var item=list.getSelectionModel().getSelectedItem();if(snapshot==null||item==null)return;@Nullable var edit=ScriptCompletion.accept(snapshot,item,area.getText(),area.getCaretPosition());hide();if(edit==null)return;accepting=true;try{area.replaceText(edit.start(),edit.end(),edit.replacement());area.selectRange(edit.anchor(),edit.caret());area.requestFocus();}finally{accepting=false;updateHint();}}
    /// Cancels pending work as well as an already-visible popup.
    private void hide(){delay.stop();popup.hide();result=null;}
    /// Shows the enclosing call's real parameter signature without adding any source characters.
    private void updateHint(){String text=ScriptCompletion.signature(area.getText(),area.getCaretPosition());hint.setText(text);hint.setVisible(!text.isEmpty());}
}
