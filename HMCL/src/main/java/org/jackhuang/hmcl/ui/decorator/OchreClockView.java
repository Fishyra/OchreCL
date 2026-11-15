package org.jackhuang.hmcl.ui.decorator;

import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import org.jetbrains.annotations.NotNullByDefault;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/// A responsive typographic clock: oversized hours/minutes, raised seconds and an offset greeting.
@NotNullByDefault
final class OchreClockView extends Region {
    /// Primary time carries the visual emphasis and keeps a stable width between ticks.
    private final Label time = label("ochre-clock-time");
    /// Separate seconds are readable without crowding the dominant hours/minutes.
    private final Label seconds = label("ochre-clock-seconds");
    /// Calendar context sits lightly above the dominant numerals.
    private final Label date = label("ochre-clock-date");
    /// Greeting and account name remain present, including when they require ellipsis.
    private final Label greeting = label("ochre-clock-greeting");
    /// A quiet minute track and advancing fill visually connect the seconds to the card.
    private final Rectangle track = new Rectangle(), progress = new Rectangle();
    /// Current second controls the decorative progress, never the actual time source.
    private int second;
    /// Creates noninteractive artwork so the parent cover context menu remains available.
    OchreClockView(){setMouseTransparent(true);getStyleClass().add("ochre-clock");setMinSize(0,212);setPrefSize(280,212);setMaxSize(Double.MAX_VALUE,212);track.setStyle("-fx-fill: -ochre-muted-ink;");track.setOpacity(.12);progress.setStyle("-fx-fill: -ochre-action-ink;");progress.setOpacity(.6);getChildren().setAll(date,time,seconds,greeting,track,progress);}
    /// Labels are manually laid out, so their intrinsic width never widens the inspector.
    private static Label label(String style){Label label=new Label();label.getStyleClass().add(style);label.setManaged(false);label.setMinWidth(0);label.setTextOverrun(OverrunStyle.ELLIPSIS);label.setStyle("-fx-text-fill: -ochre-ink;");return label;}
    /// Updates from the system's current local time, including day rollover and account changes.
    void update(LocalDateTime now,String name){time.setText(now.format(DateTimeFormatter.ofPattern("HH:mm")));seconds.setText(now.format(DateTimeFormatter.ofPattern("ss")));date.setText(now.format(DateTimeFormatter.ofPattern("M月d日 · EEEE",Locale.SIMPLIFIED_CHINESE)));int hour=now.getHour();greeting.setText((hour>=5&&hour<11?"早上好":hour>=11&&hour<18?"中午好":"晚上好")+"，"+name);second=now.getSecond();setAccessibleText(now.format(DateTimeFormatter.ofPattern("HH:mm:ss"))+"，"+greeting.getText());requestLayout();}
    /// Fits the large numerals into the available width, reserving a separate column for seconds.
    @Override protected void layoutChildren(){double w=getWidth(),h=getHeight(),pad=Math.min(20,w*.07),available=Math.max(1,w-2*pad);double size=Math.min(88,Math.max(30,available*.32));Text measure=new Text(time.getText());measure.setFont(Font.font("System",FontWeight.BOLD,size));double reserve=52;if(measure.getLayoutBounds().getWidth()+reserve>available)size*=Math.max(.3,(available-reserve)/measure.getLayoutBounds().getWidth());time.setFont(Font.font("System",FontWeight.BOLD,size));seconds.setFont(Font.font("System",FontWeight.MEDIUM,Math.max(17,size*.29)));date.setFont(Font.font("System",11));date.setOpacity(.65);greeting.setFont(Font.font("System",14));greeting.setOpacity(.85);double timeWidth=Math.max(1,available-reserve);date.resizeRelocate(pad,19,available,20);time.resizeRelocate(pad-3,43,timeWidth,107);seconds.resizeRelocate(pad+timeWidth+7,62,45,42);greeting.resizeRelocate(pad+10,157,Math.max(1,available-10),27);track.setX(pad);track.setY(h-15);track.setWidth(available);track.setHeight(2);progress.setX(pad);progress.setY(h-15);progress.setWidth(available*(second+1)/60.0);progress.setHeight(2);}
}
