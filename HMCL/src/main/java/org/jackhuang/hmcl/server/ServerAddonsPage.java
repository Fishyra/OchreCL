package org.jackhuang.hmcl.server;

import com.jfoenix.controls.*;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.animation.TransitionPane;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.util.*;

/// Server-targeted add-on browser, exact-version details and transactional install dialogs.
@NotNullByDefault
public final class ServerAddonsPage extends BorderPane {
    /// Target version and loader are always derived from this installed server.
    private final ComboBox<ServerInstance> target = new JFXComboBox<>();
    /// Query and optional category facet.
    private final TextField query = new JFXTextField();
    /// Server-compatible categories supplied by Modrinth.
    private final ComboBox<String> category = new JFXComboBox<>();
    /// Provider sort order shown with translated captions.
    private final ComboBox<String> sort = new JFXComboBox<>();
    /// Current results or project detail surface.
    private VBox body = new VBox(10);
    /// Holds previous and incoming result/detail pages until their slide transition settles.
    private final TransitionPane pages = new TransitionPane();
    /// Current page and empty/error status.
    private final Label status = new Label("选择服务器后搜索插件与服务端模组");
    /// Result paging position.
    private int page;
    /// Provider result count for the selected query.
    private int total;
    /// Prevents stale requests from replacing a newer target/query or detail page.
    private long generation;

    /// Builds filters matching the desktop mod browser without a separate game-version field.
    public ServerAddonsPage() {
        getStyleClass().add("ochre-server-download");
        setPadding(new Insets(14)); ServerManager.load();
        target.setItems(ServerManager.SERVERS); target.setValue(ServersPage.SELECTED.get()); target.setPromptText("选择服务器"); target.setMaxWidth(Double.MAX_VALUE);
        query.setPromptText("搜索插件与服务端模组"); category.getItems().setAll("全部", "实用工具", "性能优化", "管理", "世界生成", "科技", "冒险"); category.setValue("全部");
        sort.getItems().setAll("相关度", "下载量", "关注数", "最近更新", "最新发布"); sort.setValue("相关度");
        JFXButton search = button("搜索", () -> { page = 0; search(); }); query.setOnAction(event -> search.fire());
        GridPane filters = new GridPane(); filters.setHgap(12); filters.setVgap(10); filters.setPadding(new Insets(14)); filters.getStyleClass().add("ochre-server-controls");
        filters.getStyleClass().add("ochre-addon-filters");
        for (Region control : new Region[]{target, query, category, sort}) {
            control.setMinWidth(0); control.setMaxWidth(Double.MAX_VALUE);
            control.setMinHeight(40); control.setPrefHeight(40); control.setMaxHeight(40);
        }
        arrangeFilters(filters);
        filters.widthProperty().addListener((observable, before, after) -> arrangeFilters(filters));
        status.setWrapText(true); status.setMinWidth(0); status.setMaxWidth(Double.MAX_VALUE);
        var paging = new FlowPane(8, 8, button("第一页", () -> change(0)), button("上一页", () -> change(Math.max(0, page - 1))), button("下一页", () -> change(page + 1)), button("最后一页", () -> change(Math.max(0, (total - 1) / 20))), search);
        paging.setAlignment(Pos.CENTER_LEFT);
        VBox header = new VBox(10, filters, status, paging); header.setMinWidth(0); header.setPadding(new Insets(0, 0, 12, 0)); setTop(header);
        pages.setMinSize(0, 0); setCenter(pages); showBody(body, true);
        target.valueProperty().addListener((observable, old, value) -> { page = 0; search(); });
        if (target.getValue() != null) search();
    }
    /// Reflows label/control pairs at a width breakpoint, keeping feedback outside the sizing grid.
    private void arrangeFilters(GridPane filters) {
        boolean wide = filters.getWidth() >= 620;
        int columns = wide ? 4 : 2;
        if (filters.getColumnConstraints().size() == columns) return;
        filters.getChildren().clear(); filters.getColumnConstraints().clear();
        for (int i = 0; i < columns; i++) {
            ColumnConstraints column = new ColumnConstraints();
            if (i % 2 == 0) { column.setMinWidth(Region.USE_PREF_SIZE); column.setPrefWidth(58); }
            else { column.setMinWidth(0); column.setPrefWidth(0); column.setHgrow(Priority.ALWAYS); column.setFillWidth(true); }
            filters.getColumnConstraints().add(column);
        }
        // Equal growing columns share the width left after the fixed labels and gaps.
        filters.add(new Label("服务器"), 0, 0); filters.add(target, 1, 0, wide ? 3 : 1, 1);
        filters.add(new Label("名称"), 0, 1); filters.add(query, 1, 1);
        filters.add(new Label("类别"), wide ? 2 : 0, wide ? 1 : 2); filters.add(category, wide ? 3 : 1, wide ? 1 : 2);
        filters.add(new Label("排序"), 0, wide ? 2 : 3); filters.add(sort, 1, wide ? 2 : 3);
        for (Node node : filters.getChildren()) if (node instanceof Label label) label.setMinWidth(Region.USE_PREF_SIZE);
    }
    /// Gives each page its own scroll surface so the outgoing list stays intact during motion.
    private void showBody(VBox next, boolean forward) {
        body = next;
        ScrollPane scroll = new ScrollPane(next); scroll.setFitToWidth(true); scroll.setMinSize(0, 0);
        pages.setContent(scroll, forward ? ContainerAnimations.SWIPE_LEFT : ContainerAnimations.SWIPE_RIGHT);
    }
    /// Clamps paging to the actual server-compatible result count.
    private void change(int requested) { if (requested < 0 || requested > Math.max(0, (total - 1) / 20)) return; boolean forward = requested >= page; page = requested; search(forward); }
    /// Fetches one real provider result page off the JavaFX thread.
    private void search() { search(true); }
    /// Loads results while retaining the preceding page, then animates in the requested paging direction.
    private void search(boolean forward) {
        long request = ++generation; @Nullable ServerInstance server = target.getValue();
        if (server == null) { status.setText("请先选择服务器"); showBody(new VBox(10), forward); return; }
        if (ServerAddons.loader(server).equals("vanilla")) { status.setText("原版核心不支持插件或模组，请选择 Paper、Fabric 或 Forge 服务器"); showBody(new VBox(10), forward); return; }
        String term = query.getText(), facet = switch (category.getValue()) { case "实用工具" -> "utility"; case "性能优化" -> "optimization"; case "管理" -> "management"; case "世界生成" -> "worldgen"; case "科技" -> "technology"; case "冒险" -> "adventure"; default -> ""; };
        String index = switch (sort.getValue()) { case "下载量" -> "downloads"; case "关注数" -> "follows"; case "最近更新" -> "updated"; case "最新发布" -> "newest"; default -> "relevance"; }; int requestedPage = page;
        status.setText("正在搜索 · Minecraft " + server.settings.getProperty("version"));
        Schedulers.io().execute(() -> {
            try {
                var result = ServerAddons.search(server, term, facet, index, requestedPage);
                Platform.runLater(() -> { if (request != generation) return; total = result.total(); status.setText((page + 1) + " / " + Math.max(1, (total + 19) / 20) + " 页 · " + total + " 项");
                    VBox next = new VBox(10);
                    for (var project : result.projects()) next.getChildren().add(projectRow(project, () -> details(project, server)));
                    if (result.projects().isEmpty()) next.getChildren().add(new Label("没有与此服务器版本和核心兼容的项目"));
                    showBody(next, forward);
                });
            } catch (Exception exception) { fail(request, exception); }
        });
    }
    /// Opens a version-grouped project page while retaining the selected server target.
    private void details(ServerAddons.Project project, ServerInstance server) {
        long request = ++generation; VBox detail = new VBox(10); detail.getChildren().setAll(button("返回搜索结果", () -> search(false)), projectRow(project, () -> FXUtils.openLink("https://modrinth.com/project/" + project.slug())), new Label("正在获取版本…")); showBody(detail, true);
        Schedulers.io().execute(() -> {
            try {
                var versions = ServerAddons.versions(project);
                Platform.runLater(() -> {
                    if (request != generation) return;
                    detail.getChildren().remove(detail.getChildren().size() - 1);
                    VBox compatible = new VBox(8); Map<String, List<ServerAddons.Version>> groups = new LinkedHashMap<>();
                    for (var version : versions) {
                        if (ServerAddons.compatible(version, server)) compatible.getChildren().add(versionRow(version, () -> installDialog(project, version, server)));
                        else { String key = String.join(" · ", version.gameVersions()); groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(version); }
                    }
                    detail.getChildren().add(new Label("推荐版本 · Minecraft " + server.settings.getProperty("version")));
                    detail.getChildren().add(compatible.getChildren().isEmpty() ? new Label("没有兼容的发布版本") : compatible);
                    groups.forEach((game, releases) -> {
                        VBox rows = new VBox(8); TitledPane group = new TitledPane("Minecraft " + game + " · " + releases.size() + " 个版本", rows); group.setExpanded(false);
                        group.expandedProperty().addListener((observable, before, expanded) -> { if (expanded && rows.getChildren().isEmpty()) for (var version : releases) rows.getChildren().add(versionRow(version, () -> installDialog(project, version, server))); }); detail.getChildren().add(group);
                    });
                });
            } catch (Exception exception) { fail(request, exception); }
        });
    }
    /// Shows release metadata/changelog and explicit target installation alongside local download.
    private void installDialog(ServerAddons.Project project, ServerAddons.Version version, ServerInstance server) {
        VBox dialog = new VBox(14); dialog.setPadding(new Insets(24)); dialog.setPrefSize(700, 540); dialog.setMaxSize(820, 650); dialog.getStyleClass().add("jfx-dialog-layout");
        Label title = new Label("服务器附加项下载 · " + version.name()); title.setWrapText(true); title.getStyleClass().add("ochre-instance-title");
        TextArea changelog = new JFXTextArea(version.changelog()); changelog.setEditable(false); changelog.setWrapText(true); VBox.setVgrow(changelog, Priority.ALWAYS);
        JFXButton install = button("安装到服务器 · " + server, () -> {
            dialog.fireEvent(new DialogCloseEvent());
            ServerTaskDialog.show("安装服务器附加项", progress -> {
                ServerCatalog.installAddonVersion(new ServerCatalog.Project(project.id(), project.title()), version.id(), server, progress); return project.title();
            }, name -> ServerManager.notice("已安装 " + name + " → " + server));
        }); install.getStyleClass().add("ochre-primary-action"); install.setDisable(!ServerAddons.compatible(version, server));
        JFXButton local = button("下载到本地文件夹", () -> {
            FileChooser chooser = new FileChooser(); chooser.setTitle("保存服务器附加项"); chooser.setInitialFileName(version.file().label());
            @Nullable var destination = Controllers.showSaveDialog(chooser); if (destination == null) return;
            dialog.fireEvent(new DialogCloseEvent());
            ServerTaskDialog.show("下载服务器附加项", progress -> { ServerCatalog.download(version.file(), destination, progress); return destination; }, path -> ServerManager.notice("下载完成：" + path));
        });
        FlowPane actions = new FlowPane(10, 10, install, local, button("取消", () -> dialog.fireEvent(new DialogCloseEvent())));
        dialog.getChildren().setAll(title, new Label(version.channel() + " · " + version.published() + " · " + String.join(" / ", version.loaders())), button("官方页面 ↗", () -> FXUtils.openLink("https://modrinth.com/project/" + project.slug() + "/version/" + version.id())), changelog, actions);
        if (!ServerAddons.compatible(version, server)) dialog.getChildren().add(2, new Label("此版本与所选服务器不兼容，可以下载到本地，但不能直接安装。"));
        Controllers.dialog(dialog);
    }
    /// Renders a project with a proportional icon, category tags and bounded description.
    private Node projectRow(ServerAddons.Project project, Runnable action) {
        Label title = new Label(project.title() + "   " + String.join(" · ", project.categories())); title.setWrapText(true);
        Label description = new Label(project.description()); description.setWrapText(true); description.setMaxHeight(48);
        VBox text = new VBox(5, title, description); HBox.setHgrow(text, Priority.ALWAYS);
        HBox row = new HBox(12, text); row.setPadding(new Insets(14)); row.getStyleClass().add("ochre-server-controls");
        if (project.icon().startsWith("https://")) { ImageView icon = new ImageView(new Image(project.icon(), 56, 56, true, true, true)); icon.setFitWidth(56); icon.setFitHeight(56); icon.setPreserveRatio(true); row.getChildren().add(0, icon); }
        row.setOnMouseClicked(event -> action.run()); row.setCursor(javafx.scene.Cursor.HAND); return row;
    }
    /// Renders an exact, selectable release without silently choosing the latest file.
    private Node versionRow(ServerAddons.Version version, Runnable action) {
        JFXButton row = button(version.name() + "  ·  " + version.channel() + "\n" + version.published(), action); row.setWrapText(true); row.setMaxWidth(Double.MAX_VALUE); row.setPadding(new Insets(16)); row.getStyleClass().add("ochre-server-controls"); return row;
    }
    /// Reports only the current request's failure and keeps navigation usable.
    private void fail(long request, Exception exception) { Platform.runLater(() -> { if (request == generation) status.setText("获取失败：" + exception.getMessage()); }); }
    /// Creates launcher-styled actions with explicit handlers.
    private static JFXButton button(String label, Runnable action) { JFXButton button = new JFXButton(label); button.setOnAction(event -> action.run()); return button; }
}
