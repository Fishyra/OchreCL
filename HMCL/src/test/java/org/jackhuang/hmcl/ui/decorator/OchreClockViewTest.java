package org.jackhuang.hmcl.ui.decorator;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.JavaFXLauncher;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
/// Exercises the real clock layout at narrow and wide sizes with a long account name.
@NotNullByDefault public final class OchreClockViewTest {
    /// Large numerals fit the card; separate seconds and accessible time remain accurate.
    @Test public void responsiveClock()throws Exception {JavaFXLauncher.start();CompletableFuture<Void> done=new CompletableFuture<>();Platform.runLater(()->{try{var clock=new OchreClockView();var root=new StackPane(clock);var scene=new Scene(root); var css=java.nio.file.Files.createTempFile("clock-theme-",".css"); java.nio.file.Files.writeString(css,".label { -fx-font-size: 13px; }");scene.getStylesheets().add(css.toUri().toString()); root.setStyle("-ochre-ink: #39362F; -ochre-muted-ink: #777064; -ochre-action-ink: #80633D;"); clock.setStyle("-fx-background-color: #F0E8D8; -fx-background-radius: 18;");clock.update(LocalDateTime.of(2026,11,15,16,48,3),"LongPlayerName".repeat(4));for(double width:new double[]{220,280,420}){clock.resize(width,212);clock.applyCss();clock.layout();clock.applyCss();var time=(Label)clock.lookup(".ochre-clock-time");assertTrue(time.getFont().getSize()>45);assertEquals("16:48",time.getText());for(var node:clock.getChildrenUnmodifiable())assertTrue(node.getBoundsInParent().getMaxX()<=width+1,node.toString());}assertTrue(clock.getAccessibleText().startsWith("16:48:03"));assertEquals("03",((Label)clock.lookup(".ochre-clock-seconds")).getText());clock.resize(300,212); clock.layout(); var image=clock.snapshot(null,null); var png=new java.awt.image.BufferedImage((int)image.getWidth(),(int)image.getHeight(),java.awt.image.BufferedImage.TYPE_INT_ARGB); for(int y=0;y<png.getHeight();y++) for(int x=0;x<png.getWidth();x++) png.setRGB(x,y,image.getPixelReader().getArgb(x,y)); javax.imageio.ImageIO.write(png,"png",java.nio.file.Path.of(System.getProperty("java.io.tmpdir"),"ochre-clock-redesign.png").toFile()); done.complete(null);}catch(Throwable error){done.completeExceptionally(error);}});done.get(15,TimeUnit.SECONDS);}
}
