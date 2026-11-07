package org.jackhuang.hmcl.server;

import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import javafx.scene.shape.SVGPath;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import java.util.Locale;
import java.util.Objects;

/// Fixed-size core artwork with a separate, theme-aware server badge at the bottom right.
@NotNullByDefault
public final class ServerCoreArtwork extends StackPane {
    /// Builds local known-core artwork, provider artwork or a readable initial when no logo is available.
    public ServerCoreArtwork(String name, String remoteIcon) {
        setMinSize(56, 56); setPrefSize(56, 56); setMaxSize(56, 56);
        getStyleClass().add("ochre-core-artwork"); setAccessibleText(name + " 服务器核心图标");
        String key = name.toLowerCase(Locale.ROOT);
        Node artwork;
        if (key.startsWith("arclight")) artwork = image("ochre-arclight.jpg");
        else if (key.equals("原版") || key.startsWith("vanilla")) artwork = image("grass@2x.png");
        else if (key.startsWith("neoforge")) artwork = image("neoforge@2x.png");
        else if (key.startsWith("forge")) artwork = image("forge@2x.png");
        else if (key.startsWith("fabric")) artwork = image("fabric@2x.png");
        else if (key.startsWith("paper")) {
            SVGPath paper = new SVGPath();
            paper.setContent("M2 1 H22 L32 11 V41 H2 Z M22 1 V11 H32 M9 19 H25 M9 26 H25 M9 33 H20");
            paper.getStyleClass().add("ochre-paper-glyph"); artwork = paper;
        } else {
            Label initial = new Label(name.isBlank() ? "?" : name.substring(0, 1).toUpperCase(Locale.ROOT));
            initial.setStyle("-fx-font-size: 26px; -fx-font-weight: bold;"); artwork = initial;
        }
        getChildren().add(artwork);
        if (!remoteIcon.isBlank() && !key.startsWith("arclight")) {
            ImageView remote = new ImageView(new Image(remoteIcon, 48, 48, true, true, true));
            remote.setFitWidth(48); remote.setFitHeight(48); remote.setPreserveRatio(true); getChildren().add(remote);
        }
        StackPane badge = new StackPane(SVG.OCHRE_SERVER.createIcon(16));
        badge.setMinSize(24, 24); badge.setPrefSize(24, 24); badge.setMaxSize(24, 24);
        badge.getStyleClass().add("ochre-core-server-badge"); StackPane.setAlignment(badge, Pos.BOTTOM_RIGHT);
        getChildren().add(badge); setMouseTransparent(true);
    }
    /// Reads bundled logos at a proportional bounded display size, without a network dependency.
    private static ImageView image(String filename) {
        ImageView view = new ImageView(new Image(Objects.requireNonNull(ServerCoreArtwork.class.getResourceAsStream("/assets/img/" + filename)), 48, 48, true, true));
        view.setFitWidth(48); view.setFitHeight(48); view.setPreserveRatio(true); return view;
    }
}
