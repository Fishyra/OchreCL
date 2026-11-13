package org.jackhuang.hmcl.ui.decorator;

import javafx.animation.*;
import javafx.scene.Group;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.*;
import javafx.util.Duration;
import org.jetbrains.annotations.NotNullByDefault;

/// Small orbiting particles around a launch glyph, with no platform progress-control skin.
@NotNullByDefault
public final class LaunchOrbit extends StackPane {
    /// Rotates the particle orbit only while this graphic is attached to a visible scene.
    private final RotateTransition rotation;
    /// Provides a slow breathing accent on the central launch symbol.
    private final FadeTransition breathing;
    /// Creates an accessible launch-state animation using the launcher's vector colors.
    public LaunchOrbit() {
        setMinSize(32, 32); setPrefSize(32, 32); setMaxSize(32, 32); setAccessibleText("启动中");
        Circle track = new Circle(14); track.setFill(Color.TRANSPARENT); track.getStyleClass().add("ochre-orbit-track"); track.setStrokeWidth(1.3);
        Group particles = new Group();
        for (int i = 0; i < 4; i++) { double angle = i * Math.PI / 2; Circle dot = new Circle(14 * Math.cos(angle), 14 * Math.sin(angle), i == 0 ? 2.5 : 1.6); dot.getStyleClass().add("svg"); dot.setOpacity(1 - i * .18); particles.getChildren().add(dot); }
        SVGPath glyph = new SVGPath(); glyph.setContent("M-3 -5 L5 0 L-3 5 Z"); glyph.getStyleClass().add("svg");
        getChildren().setAll(track, particles, glyph);
        rotation = new RotateTransition(Duration.seconds(1.5), particles); rotation.setByAngle(360); rotation.setInterpolator(Interpolator.LINEAR); rotation.setCycleCount(Animation.INDEFINITE);
        breathing = new FadeTransition(Duration.seconds(.8), glyph); breathing.setFromValue(.4); breathing.setToValue(1); breathing.setAutoReverse(true); breathing.setCycleCount(Animation.INDEFINITE);
        sceneProperty().addListener((observable, previous, scene) -> { if (scene == null) { rotation.stop(); breathing.stop(); } else if (!org.jackhuang.hmcl.setting.SettingsManager.settings().isAnimationDisabled()) { rotation.play(); breathing.play(); } });
    }
}
