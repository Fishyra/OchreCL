package org.jackhuang.hmcl.server;

import com.jfoenix.controls.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jetbrains.annotations.NotNullByDefault;

/// Mainstream core versions and community-core search share one consistently aligned toolbar.
@NotNullByDefault
public final class ServerDownloadPage extends BorderPane {
    /// Mainstream provider selected before a game version.
    private final ComboBox<String> core = new JFXComboBox<>();
    /// Searches community server cores, independently of mainstream game-version listings.
    private final TextField search = new JFXTextField();
    /// Status of the current mainstream provider request.
    private final Label status = new Label();
    /// Mainstream provider version rows.
    private final VBox rows = new VBox(8);
    /// Retained mainstream list so returning from search preserves the selected provider.
    private final SpinnerPane versions = new SpinnerPane();
    /// Community results are rendered below the same toolbar.
    private final ServerCoreSearch community;
    /// Retains outgoing and incoming pages during the standard download transition.
    private final TransitionPane pages = new TransitionPane();
    /// Discards replies from previously selected providers.
    private long generation;

    /// Creates aligned alternative provider/search controls and loads the initial mainstream provider.
    public ServerDownloadPage() {
        getStyleClass().add("ochre-server-download"); setPadding(new Insets(14));
        core.getItems().setAll("原版", "Paper", "Purpur", "Fabric", "Forge");
        core.getStyleClass().add("ochre-core-selector"); core.setPrefWidth(108); core.setMinWidth(88);
        search.getStyleClass().add("ochre-core-query"); search.setMinWidth(65);
        search.setPromptText("搜索非主流服务器核心…"); search.setTooltip(new Tooltip("输入核心名称，按回车搜索")); search.setOnAction(event -> searchCommunity());
        JFXButton submit = new JFXButton(); submit.setGraphic(SVG.SEARCH.createIcon(18)); submit.setAccessibleText("搜索非主流服务器核心"); submit.setTooltip(new Tooltip("搜索")); submit.setOnAction(event -> searchCommunity());
        JFXButton refresh = new JFXButton("刷新"); refresh.setOnAction(event -> { if (search.getText().isBlank()) load(); else searchCommunity(); });
        HBox filters = new HBox(8, new Label("下载"), core, new Label("或"), search, submit, refresh);
        filters.setAlignment(Pos.CENTER_LEFT); filters.setFillHeight(true); HBox.setHgrow(search, Priority.ALWAYS);
        filters.setPadding(new Insets(12)); filters.getStyleClass().addAll("ochre-server-controls", "ochre-core-toolbar");
        for (Region control : new Region[]{core, search, submit, refresh}) { control.setMinHeight(44); control.setPrefHeight(44); control.setMaxHeight(44); }
        BorderPane.setMargin(filters, new Insets(0, 0, 12, 0)); toolbar = filters; setTop(filters);
        ScrollPane scroll = new ScrollPane(rows); scroll.setFitToWidth(true);
        VBox list = new VBox(12, status, scroll); VBox.setVgrow(scroll, Priority.ALWAYS);
        versions.setContent(list); versions.setOnFailedAction(event -> load());
        community = new ServerCoreSearch(this::showVersions, page -> { setTop(page instanceof ServerCoreSearch ? toolbar : null); showPage(page, !(page instanceof ServerCoreSearch)); });
        search.textProperty().addListener((observable, previous, value) -> { if (value.isBlank() && pages.getCurrentNode() == community) showVersions(); });
        core.valueProperty().addListener((observable, previous, value) -> { showVersions(); load(); });
        pages.setMinSize(0, 0); setCenter(pages); showPage(versions, true); core.setValue("原版");
    }
    /// Uses opposite directions for entering a subpage and returning to the retained list.
    private void showPage(Region page, boolean forward) {
        pages.setContent(page, forward ? ContainerAnimations.SWIPE_LEFT : ContainerAnimations.SWIPE_RIGHT);
    }
    /// Submits the shared text box directly to community-core search, never filtering Minecraft versions.
    private void searchCommunity() {
        String query = search.getText().strip(); if (query.isEmpty()) { showVersions(); return; }
        showPage(community, true); community.search(query);
    }
    /// Returns to retained mainstream versions and invalidates a pending community response.
    private void showVersions() { community.cancelSearch(); showPage(versions, false); search.clear(); }
    /// Populates selectable game versions and moves directly into build/configuration selection.
    private void load() {
        long request = ++generation; String provider = core.getValue(); rows.getChildren().clear(); status.setText(""); versions.setFailedReason(null); versions.showSpinner();
        Schedulers.io().execute(() -> {
            try { var available = ServerCatalog.versions(provider); Platform.runLater(() -> {
                if (request != generation) return; versions.hideSpinner(); status.setText(available.isEmpty() ? "暂无可用版本" : "选择游戏版本，然后配置并部署");
                for (String version : available) {
                    JFXButton row = new JFXButton(); row.setAccessibleText("Minecraft " + version); row.setMaxWidth(Double.MAX_VALUE); row.setPadding(new Insets(16)); row.getStyleClass().add("ochre-server-controls");
                    Label title = new Label("Minecraft " + version); Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
                    HBox line = new HBox(14, new ServerCoreArtwork(provider, ""), title, gap, new Label("→")); line.setAlignment(Pos.CENTER_LEFT); line.prefWidthProperty().bind(row.widthProperty().subtract(34)); row.setGraphic(line);
                    row.setOnAction(event -> { setTop(null); showPage(new ServerDeploymentPage(provider, version, () -> { setTop(toolbar); showPage(versions, false); }), true); });
                    rows.getChildren().add(row);
                }
            }); } catch (Exception exception) { Platform.runLater(() -> { if (request == generation) { versions.setLoading(false); versions.setFailedReason("获取失败，点击重试：" + exception.getMessage()); } }); }
        });
    }
    /// Retained toolbar survives the deployment subpage without rebuilding controls.
    private final Region toolbar;
}
