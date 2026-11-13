package org.jackhuang.hmcl.ui.construct;

import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.upgrade.OchreUpdater;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

/// User-initiated fork update UI; no upstream overwrite or automatic account requests.
@NotNullByDefault
public final class OchreUpdatePane extends VBox {
    /// Most recently validated newer release, absent until a successful check.
    private OchreUpdater.@Nullable Release available;
    /// Reports network failures distinctly from a feed with no eligible update image.
    private final Label status = new Label("更新源：GitHub Releases · Fishyra/OchreCL");
    /// Read-only release notes supplied by this project's publisher.
    private final TextArea notes = new TextArea();
    /// Opens a bounded update dialog without initiating any install.
    public static void show() { Controllers.dialog(new OchreUpdatePane()); }
    /// Builds explicit check, download, rollback and close controls.
    public OchreUpdatePane() {
        super(14); setPadding(new Insets(24)); setPrefSize(680, 450); setMinSize(300, 260); setMaxSize(820, 600); getStyleClass().add("jfx-dialog-layout");
        Label title = new Label("启动器更新"); title.setGraphic(SVG.DOWNLOAD.createIcon(24)); title.getStyleClass().add("ochre-instance-title"); status.setWrapText(true);
        Label version = new Label("当前版本：" + Metadata.VERSION); notes.setEditable(false); notes.setWrapText(true); notes.setMinHeight(60); notes.setPromptText("检查更新后在这里查看发布说明。"); VBox.setVgrow(notes, Priority.ALWAYS);
        Label policy = new Label("下载后校验 SHA-256，写入用户目录，下次重新打开启动器生效。不会强制停止游戏或服务器，也不会修改应用包。原运行环境无需改变的更新才适用此通道。"); policy.setWrapText(true);
        JFXButton check = new JFXButton("检查更新"), install = new JFXButton("下载并准备更新"), rollback = new JFXButton("下次使用内置版本"), close = new JFXButton("关闭"); check.setGraphic(SVG.SEARCH.createIcon(18)); install.setGraphic(SVG.DOWNLOAD.createIcon(18)); install.setDisable(true);
        check.setOnAction(event -> { check.setDisable(true); install.setDisable(true); status.setText("正在检查 GitHub 发布…"); Schedulers.io().execute(() -> {
            try { @Nullable var release = OchreUpdater.check(); Platform.runLater(() -> { available = release; status.setText(release == null ? "未发现比当前版本更新且提供可校验自动更新包的发布。" : "可用更新：" + release.version() + " · " + Math.round(release.size() / 1048576.0) + " MiB"); notes.setText(release == null ? "发布者需要将 OchreCL-update.jar 上传到 Fishyra/OchreCL Releases。GitHub 提供的 SHA-256 元数据必须存在。蓝奏云完整安装包仍可从官网下载。" : release.notes()); install.setDisable(release == null); check.setDisable(false); }); }
            catch (Exception error) { Platform.runLater(() -> { status.setText("检查失败：" + error.getMessage()); check.setDisable(false); }); }
        }); });
        install.setOnAction(event -> { @Nullable var release = available; if (release == null) return; install.setDisable(true);
            org.jackhuang.hmcl.server.ServerTaskDialog.show("更新 ØchreCL · " + release.version(), progress -> OchreUpdater.download(release, progress), file -> { status.setText("更新已就绪，退出并重新打开启动器后生效。"); notes.setText("已验证并保存在：" + file + "\n游戏和服务器进程保持运行。需要时可在此返回内置版本。"); install.setDisable(true); }, () -> install.setDisable(false));
        });
        rollback.setOnAction(event -> Controllers.confirm("下次打开将使用应用程序原有版本。账号、游戏与服务器数据保留。是否继续？", "返回内置版本", () -> { try { OchreUpdater.rollback(); status.setText("下次打开将使用内置版本"); } catch (java.io.IOException error) { status.setText(error.getMessage()); } }, null));
        JFXButton website = new JFXButton("完整安装包 ↗"); website.setOnAction(event -> FXUtils.openLink("https://fishyra.github.io/ochrecl/#download"));
        close.setOnAction(event -> fireEvent(new DialogCloseEvent())); FlowPane actions = new FlowPane(10, 8, check, install, website, rollback, close);
        getChildren().setAll(title, version, status, policy, notes, actions);
        @Nullable String fallback = System.getProperty("ochre.updateFallback"); if (fallback != null) status.setText("上次更新校验未通过，当前已使用内置版本。" + fallback);
    }
}
