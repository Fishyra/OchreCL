package org.jackhuang.hmcl.server;

import com.jfoenix.controls.JFXButton;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;

/// A single rounded service dialog with a stationary heading/footer and an independently scrolling form.
@NotNullByDefault
public final class ServerServicesDialog extends BorderPane {
    /// Keeps all form painting within the outer dialog's rounded boundary.
    private final Rectangle boundary = new Rectangle();

    /// Builds one continuous surface rather than placing a close button outside a scrolling card.
    public ServerServicesDialog(ServerInstance server) {
        getStyleClass().addAll("jfx-dialog-layout", "ochre-service-dialog"); setPadding(new Insets(20)); setPrefSize(560, 620); setMinSize(280, 240);
        var root = Controllers.getDecorator().getRoot();
        maxHeightProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(() -> Math.max(240, root.getLayoutBounds().getHeight() - 64), root.layoutBoundsProperty()));
        maxWidthProperty().bind(javafx.beans.binding.Bindings.createDoubleBinding(() -> Math.max(280, root.getLayoutBounds().getWidth() - 64), root.layoutBoundsProperty()));
        Label title = new Label("SFTP 与内网穿透"); title.getStyleClass().add("ochre-instance-title"); title.setWrapText(true);
        BorderPane.setMargin(title, new Insets(0, 0, 12, 0)); setTop(title);
        ServerServicesPane form = new ServerServicesPane(server); form.getStyleClass().remove("ochre-server-controls"); form.setPadding(new Insets(4, 8, 12, 0));
        ScrollPane scroll = new ScrollPane(form); scroll.setFitToWidth(true); scroll.setMinSize(0, 0); scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.getStyleClass().add("ochre-service-scroll"); setCenter(scroll);
        JFXButton close = new JFXButton("关闭"); close.setOnAction(event -> fireEvent(new DialogCloseEvent()));
        HBox footer = new HBox(close); footer.setAlignment(Pos.CENTER_RIGHT); footer.setPadding(new Insets(12, 0, 0, 0)); footer.getStyleClass().add("ochre-service-footer"); setBottom(footer);
        boundary.widthProperty().bind(widthProperty()); boundary.heightProperty().bind(heightProperty()); boundary.setArcWidth(40); boundary.setArcHeight(40); setClip(boundary);
    }
}
