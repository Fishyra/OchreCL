package org.jackhuang.hmcl.ui.construct;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.stage.*;
import org.jackhuang.hmcl.JavaFXLauncher;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
/// Exercises real editor focus, popup selection and key acceptance in an isolated temporary window.
@NotNullByDefault public final class EditorCompletionTest {
    /// Executes assertions on the JavaFX thread and forwards failures to JUnit.
    private static <T> T fx(Callable<T> call)throws Exception {CompletableFuture<T> future=new CompletableFuture<>();Platform.runLater(()->{try{future.complete(call.call());}catch(Throwable e){future.completeExceptionally(e);}});return future.get(15,TimeUnit.SECONDS);}
    /// Constructs actual key events, including the non-platform-specific Ctrl+Space command.
    private static KeyEvent key(KeyCode code,boolean control){return new KeyEvent(KeyEvent.KEY_PRESSED,"","",code,false,control,false,false);}
    /// Finds this editor's live popup rather than relying on a mock list model.
    private static Popup popup(){return (Popup)Window.getWindows().stream().filter(w->w instanceof Popup p&&p.isShowing()&&p.getContent().stream().anyMatch(n->"ochre-completion-popup".equals(n.getId()))).findFirst().orElseThrow();}
    /// Enter and Tab insert a single call, Escape leaves source untouched, and focus remains in the source buffer.
    @Test public void realKeyboardAcceptance()throws Exception {JavaFXLauncher.start();Platform.setImplicitExit(false);TextArea area=new TextArea();Stage stage=fx(()->{var window=new Stage();window.setTitle("ØchreCL · 编辑器验证");window.setScene(new Scene(new OchreCodeEditor(area),650,380));try { org.jackhuang.hmcl.setting.SettingsManager.settings(); } catch (IllegalStateException missing) { org.jackhuang.hmcl.setting.SettingsManager.init(); } org.jackhuang.hmcl.setting.StyleSheets.init(window.getScene()); window.show();area.requestFocus();return window;});try{for(int i=0;i<20&&!fx(()->area.getScene().getFocusOwner()==area);i++){fx(()->{stage.requestFocus();area.requestFocus();return true;});Thread.sleep(50);}assertTrue(fx(()->area.getScene().getFocusOwner()==area));fx(()->{area.setText("player.ju");area.positionCaret(9);area.fireEvent(key(KeyCode.SPACE,true));assertTrue(popup().isShowing()); var preview=popup().getContent().get(0); var picture=preview.snapshot(null,null); var bitmap=new java.awt.image.BufferedImage((int)picture.getWidth(),(int)picture.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB); for(int y=0;y<bitmap.getHeight();y++) for(int x=0;x<bitmap.getWidth();x++) bitmap.setRGB(x,y,picture.getPixelReader().getArgb(x,y)); javax.imageio.ImageIO.write(bitmap,"png",java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"ochre-completion-preview.png").toFile());area.fireEvent(key(KeyCode.TAB,false));assertEquals("player.jump()",area.getText());assertSame(area,area.getScene().getFocusOwner());area.setText("player.ju");area.positionCaret(9);area.fireEvent(key(KeyCode.SPACE,true));area.fireEvent(key(KeyCode.ESCAPE,false));assertEquals("player.ju",area.getText());area.fireEvent(key(KeyCode.SPACE,true));area.fireEvent(key(KeyCode.ENTER,false));assertEquals("player.jump()",area.getText());area.setText("input.");area.positionCaret(6);area.fireEvent(key(KeyCode.SPACE,true));var view=(ListView<?>)popup().getContent().get(0).lookup(".list-view");int before=view.getSelectionModel().getSelectedIndex();area.fireEvent(key(KeyCode.DOWN,false));assertNotEquals(before,view.getSelectionModel().getSelectedIndex());area.fireEvent(key(KeyCode.ESCAPE,false));return true;});}finally{fx(()->{stage.close();return true;});}}
    /// Edge placement and wrapped arguments must leave the active visual line unobscured without resizing the source.
    @Test public void avoidsCaretAtScreenEdges() throws Exception {
        JavaFXLauncher.start();Platform.setImplicitExit(false);
        TextArea area=new TextArea();
        Stage stage=fx(()->{
            var window=new Stage();window.setScene(new Scene(new OchreCodeEditor(area),520,360));
            var screen=Screen.getPrimary().getVisualBounds();window.setX(screen.getMaxX()-540);window.setY(screen.getMaxY()-390);
            window.show();area.requestFocus();return window;
        });
        try {
            for(int i=0;i<20&&!fx(()->area.isFocused());i++){fx(()->{stage.requestFocus();area.requestFocus();return true;});Thread.sleep(50);}
            fx(()->{area.setText("\n".repeat(20)+"player.");area.positionCaret(area.getLength());area.getScene().getRoot().applyCss();area.getScene().getRoot().layout();return true;});
            Thread.sleep(200);
            fx(()->{area.fireEvent(key(KeyCode.SPACE,true));return true;});Thread.sleep(200);
            fx(()->{
                var shown=popup();var skin=(javafx.scene.control.skin.TextAreaSkin)area.getSkin();
                var line=area.localToScreen(skin.getCaretBounds());
                assertTrue(shown.getY()+shown.getHeight()<=line.getMinY()-4||shown.getY()>=line.getMaxY()+4,"Completion covers the active line");
                var screen=Screen.getScreensForRectangle(line.getMinX(),line.getMinY(),1,1).get(0).getVisualBounds();
                assertTrue(shown.getX()>=screen.getMinX());assertTrue(shown.getX()+shown.getWidth()<=screen.getMaxX()+1);
                assertTrue(shown.getY()>=screen.getMinY());assertTrue(shown.getY()+shown.getHeight()<=screen.getMaxY()+1);
                area.fireEvent(key(KeyCode.ESCAPE,false));
                var editor=(OchreCodeEditor)area.getScene().getRoot();
                assertTrue(editor.getBottom().getLayoutBounds().getHeight()<=30.1,"Empty hint must not reserve an extra blank row");
                double height=area.getHeight();area.setText("chat.send(");area.positionCaret(area.getLength());area.getScene().getRoot().layout();
                assertEquals(height,area.getHeight(),0.1,"Argument hint must not push the text/caret around");
                var footer=(javafx.scene.layout.StackPane)editor.getBottom();
                var location=(Label)footer.getChildren().get(0);var hint=(Label)footer.getChildren().get(1);
                assertTrue(hint.isVisible());assertFalse(location.isVisible());assertTrue(hint.getText().contains("chat.send"));
                assertEquals(hint.getText(),hint.getTooltip().getText());
                area.setText("player.position");area.positionCaret(area.getLength());area.getScene().getRoot().layout();
                assertFalse(hint.isVisible());assertTrue(location.isVisible());assertEquals(height,area.getHeight(),0.1);
                area.setText("// "+"long wrapped text ".repeat(30)+"\nplayer.");area.positionCaret(area.getLength());return true;
            });
            Thread.sleep(200);fx(()->{area.fireEvent(key(KeyCode.SPACE,true));return true;});Thread.sleep(200);
            fx(()->{area.setText("input.");area.positionCaret(area.getLength());area.fireEvent(key(KeyCode.SPACE,true));return true;});Thread.sleep(200);
            fx(()->{var shown=popup();var skin=(javafx.scene.control.skin.TextAreaSkin)area.getSkin();var line=area.localToScreen(skin.getCaretBounds());assertTrue(shown.getY()+shown.getHeight()<=line.getMinY()-4||shown.getY()>=line.getMaxY()+4);return true;});
        } finally {fx(()->{stage.close();return true;});}
    }

}
