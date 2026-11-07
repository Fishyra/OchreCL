package org.jackhuang.hmcl.ui.construct;

import com.jfoenix.controls.JFXButton;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.util.ArrayList;
import java.util.List;

/// Wrapping desktop section buttons and a single content surface, shared by new Øchre workspaces.
@NotNullByDefault
public final class OchreTabs extends VBox {
    /// Theme-aware wrapping section row.
    private final FlowPane header = new FlowPane(8, 8);
    /// Only the active page is attached, suspending inactive log timers.
    private final StackPane content = new StackPane();
    /// Buttons whose selected pseudo-class follows the visible content.
    private final List<JFXButton> buttons = new ArrayList<>();
    /// Builds an unclipped section row with resizable page content.
    public OchreTabs() {
        super(12);
        header.getStyleClass().add("ochre-section-tabs");
        content.setMinSize(0, 0);
        VBox.setVgrow(content, Priority.ALWAYS);
        getChildren().setAll(header, content);
    }
    /// Adds a lazily attached section; the first section is initially selected.
    public void addPage(String title, Node page) { addPage(title, null, page); }
    /// Adds an optional theme-aware icon beside a section title.
    public void addPage(String title, @Nullable Node icon, Node page) {
        JFXButton button = new JFXButton(title);
        button.setGraphic(icon); button.setGraphicTextGap(8);
        button.getStyleClass().add("ochre-section-tab");
        button.setOnAction(event -> select(button, page));
        buttons.add(button); header.getChildren().add(button);
        if (buttons.size() == 1) select(button, page);
    }
    /// Updates section selection without creating any platform tab chrome.
    private void select(JFXButton selected, Node page) {
        var pseudo = PseudoClass.getPseudoClass("selected");
        buttons.forEach(button -> button.pseudoClassStateChanged(pseudo, button == selected));
        content.getChildren().setAll(page);
    }
}
