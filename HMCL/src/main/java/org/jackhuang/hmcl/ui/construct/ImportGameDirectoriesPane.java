package org.jackhuang.hmcl.ui.construct;

import com.jfoenix.controls.JFXButton;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import org.jackhuang.hmcl.setting.*;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.nio.file.*;

/// Rounded game-directory cards with the add control inside the list, matching the first-run sketch.
@NotNullByDefault
public final class ImportGameDirectoriesPane extends BorderPane {
    /// Cards and the final add tile share one bounded, vertically scrolling surface.
    private final VBox rows = new VBox(9);
    /// Existing references are not silently copied or moved during import.
    private final Label status = new Label();
    /// Live count reflects registered directories, not fabricated detected installations.
    private final Label count = new Label();
    /// Retains a deliberate current selection indicator between refreshes.
    private final Label selection = new Label();
    /// Creates a responsive illustration and a themed directory list without a platform ListView skin.
    public ImportGameDirectoriesPane() {
        getStyleClass().add("ochre-import-workspace"); setMinSize(0, 0); setPrefHeight(300);
        var art = SVG.FOLDER_COPY.createIcon(85); Label caption = new Label("让旧世界，\n继续在这里。"); caption.setStyle("-fx-font-size: 19px;");
        VBox illustration = new VBox(22, art, caption); illustration.setAlignment(Pos.CENTER); illustration.setPrefWidth(190); illustration.setMinWidth(150); illustration.setPadding(new Insets(15));
        illustration.visibleProperty().bind(widthProperty().greaterThanOrEqualTo(620)); illustration.managedProperty().bind(illustration.visibleProperty());
        count.getStyleClass().add("subtitle-label"); Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS); HBox heading = new HBox(12, new Label("已有游戏目录"), gap, count); heading.setAlignment(Pos.CENTER_LEFT);
        rows.setFillWidth(true); rows.setPadding(new Insets(2)); rows.setMinWidth(0);
        ScrollPane viewport = new ScrollPane(rows); viewport.setFitToWidth(true); viewport.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); viewport.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED); viewport.setMinSize(0, 80); viewport.setPrefHeight(230); viewport.getStyleClass().add("ochre-import-scroll"); VBox.setVgrow(viewport, Priority.ALWAYS);
        status.setWrapText(true); status.visibleProperty().bind(status.textProperty().isNotEmpty()); status.managedProperty().bind(status.visibleProperty()); status.getStyleClass().add("subtitle-label"); selection.visibleProperty().bind(selection.textProperty().isNotEmpty()); selection.managedProperty().bind(selection.visibleProperty()); selection.setWrapText(true); selection.setMinWidth(0);
        VBox library = new VBox(12, heading, viewport, createAddButton(), selection, status); library.setMinWidth(0); library.setPadding(new Insets(18)); library.getStyleClass().add("ochre-import-library"); HBox.setHgrow(library, Priority.ALWAYS);
        HBox workspace = new HBox(18, illustration, library); workspace.setMinWidth(0); setCenter(workspace); refresh();
    }
    /// Renders a friendly name, shortened path and actual directory availability for each saved reference.
    private void refresh() {
        rows.getChildren().clear(); var directories = GameDirectoryManager.getGameDirectories(); count.setText(directories.size() + " 个目录");
        for (var directory : directories) {
            Path path = directory.getPath().toPath().toAbsolutePath().normalize(); boolean exists = Files.isDirectory(path.resolve("versions"));
            Label name = new Label(GameDirectoryManager.getGameDirectoryDisplayName(directory)); name.getStyleClass().add("ochre-import-name"); name.setMinWidth(0); name.setMaxWidth(Double.MAX_VALUE);
            Label location = new Label(path.toString()); location.setTextOverrun(OverrunStyle.CENTER_ELLIPSIS); location.setMinWidth(0); location.setMaxWidth(Double.MAX_VALUE); location.getStyleClass().add("subtitle-label"); location.setTooltip(new Tooltip(path.toString()));
            VBox text = new VBox(5, name, location); text.setMinWidth(0); HBox.setHgrow(text, Priority.ALWAYS);
            Label state = new Label(exists ? "可用" : "待添加游戏"); state.getStyleClass().add("ochre-instance-tag"); state.setMinWidth(Region.USE_PREF_SIZE);
            HBox line = new HBox(12, SVG.FOLDER.createIcon(25), text, state); line.setMinWidth(0); line.setAlignment(Pos.CENTER_LEFT);
            JFXButton row = new JFXButton(); row.setGraphic(line); row.setMinWidth(0); row.setMaxWidth(Double.MAX_VALUE); row.setAccessibleText("选择游戏目录：" + path); row.getStyleClass().add("ochre-import-directory"); line.prefWidthProperty().bind(row.widthProperty().subtract(28));
            row.pseudoClassStateChanged(javafx.css.PseudoClass.getPseudoClass("selected"), directory.equals(GameDirectoryManager.getSelectedGameDirectory()));
            row.setOnAction(event -> { if (!exists) { status.setText("该目录中尚无 versions 文件夹，可以用下面的＋添加已有游戏目录。"); return; } GameDirectoryManager.setSelectedGameDirectory(directory); selection.setText("已选择：" + GameDirectoryManager.getGameDirectoryDisplayName(directory)); refresh(); }); rows.getChildren().add(row);
        }
    }
    /// Keeps the plus control fixed inside the rounded library even while directory cards scroll.
    private JFXButton createAddButton() {
        JFXButton add = new JFXButton(); add.setId("ochre-import-add"); add.setAccessibleText("添加游戏目录"); add.getStyleClass().add("ochre-import-add"); add.setMinWidth(0); add.setMaxWidth(Double.MAX_VALUE);
        StackPane circle = new StackPane(SVG.ADD.createIcon(26)); circle.getStyleClass().add("ochre-import-plus"); circle.setMinSize(42, 42); circle.setPrefSize(42, 42); circle.setMaxSize(42, 42);
        HBox addFace = new HBox(10, circle, new Label("添加 .minecraft 目录")); addFace.setAlignment(Pos.CENTER); add.setGraphic(addFace); add.setOnAction(event -> choose()); return add;
    }
    /// Registers a validated existing directory only after an explicit folder selection.
    private void choose() {
        DirectoryChooser chooser = new DirectoryChooser(); chooser.setTitle("选择包含 versions 文件夹的 .minecraft 目录"); @Nullable Path path = Controllers.showDialog(chooser); if (path == null) return;
        Path normalized = path.toAbsolutePath().normalize(); if (!Files.isDirectory(normalized.resolve("versions"))) { status.setText("所选目录中没有 versions 文件夹，请选择实际的 .minecraft 游戏目录。"); return; }
        for (var existing : GameDirectoryManager.getGameDirectories()) if (existing.getPath().toPath().toAbsolutePath().normalize().equals(normalized)) { GameDirectoryManager.setSelectedGameDirectory(existing); selection.setText("此目录已在列表中，已为你选中。"); refresh(); return; }
        try { var directory = new GameDirectory(GameDirectoryManager.newGameDirectoryId(), null, org.jackhuang.hmcl.util.PortablePath.of(normalized.toString())); GameDirectoryManager.addUserGameDirectory(directory); GameDirectoryManager.setSelectedGameDirectory(directory); selection.setText("已添加：" + normalized); status.setText("正在读取已有游戏。原文件保留在原来的位置。"); refresh(); } catch (Exception error) { status.setText("添加失败：" + error.getMessage()); }
    }
}
