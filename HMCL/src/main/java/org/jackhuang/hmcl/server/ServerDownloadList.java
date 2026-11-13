package org.jackhuang.hmcl.server;

import com.jfoenix.controls.JFXListView;
import com.jfoenix.controls.JFXListCell;
import javafx.scene.Node;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// Uses the launcher's virtualized Material list cells for download results and keyboard selection.
@NotNullByDefault
public final class ServerDownloadList extends JFXListView<Node> {
    /// Creates a bounded list whose scrolling/selection behavior matches existing download pages.
    public ServerDownloadList() {
        setMinSize(0, 0); getStyleClass().add("jfx-list-view-float"); getStyleClass().add("ochre-download-list");
        setCellFactory(list -> new JFXListCell<>() {
            /// Clears recycled cell graphics to avoid retaining artwork from previous rows.
            @Override protected void updateItem(@Nullable Node item, boolean empty) { super.updateItem(item, empty); setText(null); setGraphic(empty ? null : item); if (!getStyleClass().contains("md-list-cell")) getStyleClass().add("md-list-cell"); }
        });
    }
}
