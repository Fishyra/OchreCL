package org.jackhuang.hmcl.server;

import com.jfoenix.controls.*;
import javafx.application.Platform;
import javafx.geometry.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jetbrains.annotations.*;
import java.util.*;

/// Dedicated core search with illustrated project/version cards and selectable real core providers.
@NotNullByDefault
public final class ServerCoreSearch extends VBox {
    /// Rejects late replies after changing source, query or selected project.
    private long generation;
    /// Current query retained when changing provider.
    private String term = "";
    /// Provider choice; both sources contain only verified server-core identities.
    private final ComboBox<String> source = new JFXComboBox<>();
    /// Search, version-list and provider-error feedback.
    private final Label status = new Label();
    /// Scroll content containing responsive project or version cards.
    private final VBox results = new VBox(12);
    /// Shares the standard download-page spinner and content transition.
    private final SpinnerPane loading = new SpinnerPane();
    /// Responsive card grid, rebuilding only on column-count changes.
    private final GridPane grid = new GridPane();
    /// Retained card nodes for responsive reflow without reloading images.
    private final List<Region> cards = new ArrayList<>();
    /// Number of currently rendered columns.
    private int columns;
    /// Replaces the surrounding version list with the full deployment form and a working back action.
    private final java.util.function.Consumer<Region> openDeployment;
    /// Creates the core-only search surface, provider attribution and shared parent query handling.
    public ServerCoreSearch(Runnable back, java.util.function.Consumer<Region> openDeployment) {
        super(12); this.openDeployment = openDeployment; setPadding(new Insets(12)); getStyleClass().add("ochre-server-download");
        source.getItems().setAll("MSL 服务端镜像", "官方发布 · GitHub"); source.getSelectionModel().selectFirst(); source.setPrefWidth(190);
        source.setOnAction(event -> search(term));
        Region gap = new Region(); HBox.setHgrow(gap, Priority.ALWAYS);
        HBox toolbar = new HBox(10, action("← 返回主流核心版本", back), gap, new Label("供应商"), source); toolbar.setAlignment(Pos.CENTER_LEFT);
        grid.setHgap(12); grid.setVgap(12); grid.setMaxHeight(Region.USE_PREF_SIZE); widthProperty().addListener((observable, before, after) -> arrange()); status.setWrapText(true);
        ScrollPane scroll = new ScrollPane(results); scroll.setFitToWidth(true); scroll.setFitToHeight(false); scroll.setMinSize(0, 0); loading.setContent(scroll); VBox.setVgrow(loading, Priority.ALWAYS); loading.setOnFailedAction(event -> search(term));
        ImageView logo = new ImageView(new Image(Objects.requireNonNull(ServerCoreSearch.class.getResourceAsStream("/assets/img/msl-logo.png")), 24, 24, true, true)); logo.setFitHeight(24); logo.setFitWidth(24); logo.setPreserveRatio(true);
        Hyperlink credit = new Hyperlink("本服务由MSL开服器提供 · 官网 ↗"); credit.setOnAction(event -> FXUtils.openLink("https://www.mslmc.cn/"));
        HBox attribution = new HBox(8, logo, credit); attribution.setAlignment(Pos.CENTER_LEFT); attribution.visibleProperty().bind(source.valueProperty().isEqualTo("MSL 服务端镜像")); attribution.managedProperty().bind(attribution.visibleProperty());
        getChildren().setAll(toolbar, status, loading, attribution);
    }
    /// Stops stale searches from replacing the mainstream core list.
    public void cancelSearch() { generation++; loading.hideSpinner(); }
    /// Searches real server cores; plugins and mods can no longer masquerade as core results.
    public void search(String query) {
        loading.setOnFailedAction(event -> search(term)); term = query; long request = ++generation; boolean mirror = source.getSelectionModel().getSelectedIndex() == 0;
        cards.clear(); grid.getChildren().clear(); results.getChildren().setAll(grid); status.setText(""); loading.setFailedReason(null); loading.showSpinner();
        Schedulers.io().execute(() -> {
            try {
                var cores = ServerCoreCatalog.search(query, mirror ? "MSL" : "GitHub");
                Platform.runLater(() -> {
                    if (generation != request) return; loading.hideSpinner();
                    status.setText(cores.isEmpty() ? "此供应商没有找到匹配核心，可切换供应商重试。" : cores.size() + " 个服务器核心 · 选择后查看游戏版本与构建");
                    cores.forEach(core -> cards.add(coreCard(core))); columns = 0; arrange();
                });
            } catch (Exception exception) { fail(request, exception); }
        });
    }
    /// Reflows only at the responsive column breakpoint or when results change.
    private void arrange() {
        int next = getWidth() >= 850 ? 2 : 1; if (next == columns) return; columns = next;
        grid.getChildren().clear(); grid.getColumnConstraints().clear();
        for (int i = 0; i < columns; i++) { var column = new ColumnConstraints(); column.setPercentWidth(100.0 / columns); column.setMinWidth(0); grid.getColumnConstraints().add(column); }
        for (int i = 0; i < cards.size(); i++) grid.add(cards.get(i), i % columns, i / columns);
    }
    /// Shows project artwork, the exact core identity and its real source instead of bare text rows.
    private Region coreCard(ServerCoreCatalog.Core core) {
        return card(core, core.title(), core.description(), core.source().equals("MSL") ? "MSL · 服务端镜像" : "GitHub · 官方发布", () -> versions(core));
    }
    /// Loads game versions without consuming a download-link quota for every visible row.
    private void versions(ServerCoreCatalog.Core core) {
        long request = ++generation; results.getChildren().setAll(action("← 返回搜索结果", () -> search(term)), coreCardHeader(core)); status.setText(""); loading.setFailedReason(null); loading.showSpinner(); loading.setOnFailedAction(event -> versions(core));
        Schedulers.io().execute(() -> {
            try {
                var versions = ServerCoreCatalog.versions(core);
                Platform.runLater(() -> {
                    if (generation != request) return; loading.hideSpinner();
                    status.setText(versions.isEmpty() ? "供应商尚未提供可部署的服务端 JAR" : "选择 Minecraft 版本，然后配置并部署");
                    for (String version : versions) results.getChildren().add(card(core, "Minecraft " + version, core.title(), "Java " + ServerCoreCatalog.javaMajor(version) + " · " + core.source(), () -> {
                        var page = new ServerDeploymentPage(core.title(), version, () -> openDeployment.accept(this), () -> core.source().equals("MSL")
                            ? List.of(ServerCoreCatalog.mirrorRelease(core, version).artifact())
                            : ServerCoreCatalog.releases(core).stream().map(ServerCoreCatalog.Release::artifact).filter(file -> file.version().equals(version)).toList());
                        openDeployment.accept(page);
                    }));
                });
            } catch (Exception exception) { fail(request, exception); }
        });
    }
    /// Keeps project identity and official link above the selectable game-version cards.
    private Region coreCardHeader(ServerCoreCatalog.Core core) { return card(core, core.title(), core.description(), "供应商：" + core.source() + " · 项目页面 ↗", () -> FXUtils.openLink(core.website())); }
    /// Builds a rounded keyboard-accessible illustrated row with bounded text and a clear action affordance.
    private Region card(ServerCoreCatalog.Core core, String heading, String summary, String metadata, Runnable action) {
        ServerCoreArtwork artwork = new ServerCoreArtwork(core.id(), core.icon());
        Label title = new Label(heading); title.setWrapText(false); title.setMinWidth(0); title.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");
        Label description = new Label(summary); description.setWrapText(true); description.setMaxHeight(34); description.setMinWidth(0);
        Label badge = new Label(metadata); badge.getStyleClass().add("ochre-core-source"); badge.setWrapText(false); badge.setMinWidth(0);
        VBox detail = new VBox(7, title, description, badge); detail.setMinWidth(0); HBox.setHgrow(detail, Priority.ALWAYS);
        CoreCard row = new CoreCard(action, detail, artwork, SVG.ARROW_FORWARD.createIcon(18)); row.setAlignment(Pos.CENTER_LEFT); row.setPadding(new Insets(12));
        row.setMinWidth(0); row.setMaxWidth(Double.MAX_VALUE); row.setMinHeight(108); row.setPrefHeight(108); row.setMaxHeight(108);
        detail.setAlignment(Pos.CENTER_LEFT); row.getStyleClass().add("ochre-core-card");
        row.setAccessibleText(heading); return row;
    }
    /// A compact accessible card without a button skin or a graphic-width/pref-height feedback loop.
    @NotNullByDefault
    private static final class CoreCard extends HBox {
        /// Activation shared by mouse, keyboard and accessibility actions.
        private final Runnable action;
        /// Builds one horizontal content layout whose height cannot expand with its parent viewport.
        private CoreCard(Runnable action, javafx.scene.Node... children) {
            super(12, children); this.action = action; setFocusTraversable(true); setAccessibleRole(javafx.scene.AccessibleRole.BUTTON); setCursor(javafx.scene.Cursor.HAND);
            setOnMouseClicked(event -> action.run());
            setOnKeyPressed(event -> { if (event.getCode() == javafx.scene.input.KeyCode.ENTER || event.getCode() == javafx.scene.input.KeyCode.SPACE) { action.run(); event.consume(); } });
        }
        /// Exposes normal button activation to native accessibility clients without nesting layouts in a label skin.
        @Override public void executeAccessibleAction(javafx.scene.AccessibleAction command, Object... parameters) {
            if (command == javafx.scene.AccessibleAction.FIRE) { if (!isDisabled()) action.run(); }
            else super.executeAccessibleAction(command, parameters);
        }
    }

    /// Gives only the active request permission to show an error.
    private void fail(long request, Exception exception) { Platform.runLater(() -> { if (generation == request) { loading.setLoading(false); loading.setFailedReason("获取失败，点击重试：" + exception.getMessage()); } }); }
    /// Creates a compact secondary action using the launcher's normal controls.
    private static JFXButton action(String label, Runnable action) { JFXButton button = new JFXButton(label); button.setOnAction(event -> action.run()); return button; }
}
