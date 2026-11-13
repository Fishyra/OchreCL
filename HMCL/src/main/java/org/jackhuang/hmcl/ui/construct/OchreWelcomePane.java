package org.jackhuang.hmcl.ui.construct;

import com.jfoenix.controls.JFXButton;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.jackhuang.hmcl.setting.*;
import org.jackhuang.hmcl.theme.*;
import org.jackhuang.hmcl.ui.*;
import org.jackhuang.hmcl.ui.animation.*;
import org.jetbrains.annotations.NotNullByDefault;

/// First-run disclosure, optional color selection and a short animated feature tour.
@NotNullByDefault
public final class OchreWelcomePane extends BorderPane {
    /// Retains outgoing content until the normal launcher slide animation finishes.
    private final TransitionPane pages = new TransitionPane();
    /// Current tour step; acceptance is stored only after the user's explicit action.
    private int step;
    /// Creates an in-window welcome screen without accepting Minecraft's separate EULA.
    public OchreWelcomePane() {
        getStyleClass().addAll("jfx-dialog-layout", "ochre-welcome"); setPadding(new Insets(24)); setPrefSize(780, 570); setMaxSize(920, 700); setMinSize(280, 240);
        setCenter(pages); show();
    }
    /// Displays short task-oriented pages with the same navigation motion as download pages.
    private void show() {
        VBox content = new VBox(16); content.setPadding(new Insets(8));
        Label title = new Label(step == 0 ? "欢迎使用 ØchreCL" : step == 1 ? "选择外观与颜色" : step == 2 ? "导入你的游戏" : "准备好了，出发吧"); title.getStyleClass().add("ochre-instance-title");
        content.getChildren().add(title);
        if (step == 0) {
            javafx.scene.image.ImageView cube = new javafx.scene.image.ImageView(FXUtils.newBuiltinImage("/assets/img/icon.png")); cube.setFitWidth(110); cube.setFitHeight(110); cube.setPreserveRatio(true);
            HBox welcomeArt = new HBox(24, cube, new Label("欢迎来到你的游戏工作区。\n接下来，花一分钟让它更适合你。")); welcomeArt.setAlignment(javafx.geometry.Pos.CENTER_LEFT); content.getChildren().add(welcomeArt);
            Label disclosure = new Label("ØchreCL 是 Fishyra 基于 HMCL 开发的独立启动器，与 Mojang、Microsoft 和 HMCL 官方没有隶属关系。\n\n账号授权由对应服务处理。游戏、服务器、日志和设置保存在本机；使用下载、微软登录或穿透时，会连接你选择的平台。\n\n请保留重要世界的备份。Minecraft、服务器核心、插件及第三方服务的条款分别适用。源码及上游许可可在关于页面查看。"); disclosure.setWrapText(true);
            ScrollPane scroll = new ScrollPane(disclosure); scroll.setFitToWidth(true); scroll.setMinHeight(0); VBox.setVgrow(scroll, Priority.ALWAYS); content.getChildren().add(scroll);
            JFXButton accept = new JFXButton("了解并继续"); accept.setGraphic(SVG.CHECK.createIcon(18)); accept.getStyleClass().add("ochre-primary-action"); accept.setOnAction(event -> { SettingsManager.userState().agreementVersionProperty().set(2); step++; show(); });
            JFXButton decline = new JFXButton("退出"); decline.setOnAction(event -> org.jackhuang.hmcl.Launcher.stopApplication()); content.getChildren().add(new HBox(12, accept, decline));
        } else if (step == 1) {
            var settings = SettingsManager.settings();
            com.jfoenix.controls.JFXComboBox<String> mode = new com.jfoenix.controls.JFXComboBox<>(); mode.setId("ochre-welcome-appearance"); mode.getItems().setAll("auto", "light", "dark", ImmersiveAppearance.MODE); mode.setMaxWidth(Double.MAX_VALUE);
            mode.setConverter(new javafx.util.StringConverter<>() {
                /// Displays the same mode names as the full settings screen.
                @Override public String toString(@org.jetbrains.annotations.Nullable String value) { return "immersive".equals(value) ? ImmersiveAppearance.LABEL : "light".equals(value) ? "浅色模式" : "dark".equals(value) ? "深色模式" : "跟随系统设置"; }
                /// Restores only supported persisted appearance keys.
                @Override public String fromString(String value) { return value; }
            });
            mode.setValue(java.util.Objects.requireNonNullElse(settings.themeBrightnessModeProperty().get(), "auto"));
            boolean[] changing = {false}; mode.valueProperty().addListener((observable, before, value) -> {
                if (changing[0]) return;
                if (ImmersiveAppearance.MODE.equals(value)) {
                    changing[0] = true; mode.setValue(before); changing[0] = false;
                    ImmersiveAppearance.requestEnable(() -> { changing[0] = true; mode.setValue(ImmersiveAppearance.MODE); changing[0] = false; }, () -> {});
                } else { settings.themeBrightnessModeProperty().set(value); settings.getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE); }
            });
            Label hint = new Label(); hint.setWrapText(true); hint.textProperty().bind(javafx.beans.binding.Bindings.when(ImmersiveAppearance.enabledBinding()).then("主题色：跟随背景图片（自动提取）。可在下方更换图片。").otherwise("主题色：默认米白，也可以选择下面的颜色。"));
            JFXButton picture = new JFXButton("更换背景图片"); picture.setGraphic(SVG.OCHRE_SCREENSHOT.createIcon(18)); picture.visibleProperty().bind(ImmersiveAppearance.enabledBinding()); picture.managedProperty().bind(picture.visibleProperty()); picture.setOnAction(event -> ImmersiveAppearance.requestEnable(() -> {}, () -> {}));
            VBox appearancePane = new VBox(12, new Label("外观模式"), mode, hint, picture); appearancePane.setMinWidth(0); HBox.setHgrow(appearancePane, Priority.ALWAYS);
            FlowPane colors = new FlowPane(12, 12); colors.setId("ochre-welcome-colors"); colors.disableProperty().bind(ImmersiveAppearance.enabledBinding());
            for (String color : new String[]{"#F7F1E5", "#7CAEB1", "#9BAE8B", "#B6A2C7"}) {
                JFXButton choice = new JFXButton(color.equals("#F7F1E5") ? "米白" : color.equals("#7CAEB1") ? "青色" : color.equals("#9BAE8B") ? "鼠尾草" : "淡紫");
                var swatch = new javafx.scene.shape.Circle(9, javafx.scene.paint.Color.web(color)); choice.setGraphic(swatch);
                choice.setOnAction(event -> { var appearance = SettingsManager.settings(); appearance.themeColorTypeProperty().set(color.equals("#F7F1E5") ? ThemeColorType.DEFAULT : ThemeColorType.CUSTOM); appearance.customThemeColorProperty().set(ThemeColor.of(javafx.scene.paint.Color.web(color))); appearance.getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_COLOR); }); colors.getChildren().add(choice);
            }
            appearancePane.getChildren().add(colors);
            javafx.scene.shape.SVGPath palette = new javafx.scene.shape.SVGPath(); palette.setContent("M55 8 C22 8 5 33 8 62 C11 89 40 110 57 98 C69 89 52 78 67 73 C78 68 103 81 109 57 C115 31 88 8 55 8 Z"); palette.getStyleClass().add("ochre-welcome-palette");
            javafx.scene.Group dots = new javafx.scene.Group(palette); int colorIndex = 0; for (String color : new String[]{"#7CAEB1", "#9BAE8B", "#B6A2C7", "#D4A05C"}) { double angle = Math.PI + .5 + colorIndex++ * .65; dots.getChildren().add(new javafx.scene.shape.Circle(57 + 32 * Math.cos(angle), 56 + 32 * Math.sin(angle), 7, javafx.scene.paint.Color.web(color))); }
            VBox artwork = new VBox(18, dots, new Label("挑选你的颜色。")); artwork.setAlignment(javafx.geometry.Pos.CENTER); artwork.setPrefWidth(170); artwork.setMinWidth(150); artwork.visibleProperty().bind(content.widthProperty().greaterThan(570)); artwork.managedProperty().bind(artwork.visibleProperty());
            HBox split = new HBox(24, artwork, appearancePane); split.setMinWidth(0); split.setAlignment(javafx.geometry.Pos.CENTER); content.getChildren().add(split);
            JFXButton next = new JFXButton("下一步"); next.setGraphic(SVG.ARROW_FORWARD.createIcon(18)); next.setOnAction(event -> { step++; show(); }); content.getChildren().add(next);
        } else if (step == 2) {
            Label hint = new Label("选择已有 .minecraft 游戏目录（包含 versions 文件夹）。这里只添加目录引用，不移动或覆盖原文件，也可以稍后在游戏库添加。"); hint.setWrapText(true);
            ImportGameDirectoriesPane directories = new ImportGameDirectoriesPane(); content.getChildren().addAll(hint, directories); VBox.setVgrow(directories, Priority.ALWAYS);
            JFXButton next = new JFXButton("下一步 / 稍后导入"); next.setGraphic(SVG.ARROW_FORWARD.createIcon(18)); next.setOnAction(event -> { step++; show(); }); content.getChildren().add(next);
        } else {
            TilePane guide = new TilePane(12, 12); guide.setPrefColumns(3); guide.setPrefTileWidth(210);
            guide.getChildren().setAll(tip(SVG.PERSON, "账户与登录", "添加微软账号或离线账号；微软登录需配置本项目 Client ID。"), tip(SVG.DOWNLOAD, "下载与游戏库", "安装游戏、整合包、模组与资源，调整实例 Java。"), tip(SVG.OCHRE_SERVER, "本地服务器", "下载核心并分步部署，管理日志、玩家和备份。"), tip(SVG.OCHRE_PLAY, "悬浮任务", "收起安装进度；右键悬浮球可切换任务。"), tip(SVG.SETTINGS, "外观与游戏设置", "选择透明背景；在操作游戏中调整声音和视频。")); content.getChildren().add(guide);
            JFXButton done = new JFXButton("开始使用"); done.setGraphic(SVG.CHECK.createIcon(18)); done.getStyleClass().add("ochre-primary-action"); done.setOnAction(event -> { SettingsManager.state().getShownTips().put("ochre.onboarding.v2", true); fireEvent(new DialogCloseEvent()); }); content.getChildren().add(done);
        }
        javafx.scene.Node navigation = content.getChildren().remove(content.getChildren().size() - 1);
        ProgressBar progress = new com.jfoenix.controls.JFXProgressBar(step / 3.0); progress.setMaxWidth(Double.MAX_VALUE);
        Label count = new Label("第 " + (step + 1) + " / 4 步"); count.getStyleClass().add("subtitle-label");
        HBox footerActions = new HBox(12); footerActions.setAlignment(javafx.geometry.Pos.CENTER_RIGHT); if (step > 0) { JFXButton back = new JFXButton("上一步"); back.setGraphic(SVG.ARROW_BACK.createIcon(16)); back.setOnAction(event -> { step--; show(); }); footerActions.getChildren().add(back); } footerActions.getChildren().add(navigation);
        VBox footer = new VBox(8, progress, count, footerActions); footer.setPadding(new Insets(12, 0, 0, 0)); setBottom(footer);
        ScrollPane viewport = new ScrollPane(content); viewport.setFitToWidth(true); viewport.setMinSize(0, 0); pages.setContent(viewport, ContainerAnimations.SWIPE_LEFT);
    }
    /// Creates one icon-labelled guide card with wrapping text.
    private static VBox tip(SVG icon, String name, String description) {
        var graphic = icon.createIcon(34); Label title = new Label(name); title.setStyle("-fx-font-weight: bold;"); Label text = new Label(description); text.setWrapText(true);
        VBox box = new VBox(12, graphic, title, text); box.setMinWidth(0); box.setPrefWidth(210); box.setPadding(new Insets(14)); box.getStyleClass().add("ochre-server-controls");
        var motion = new javafx.animation.TranslateTransition(javafx.util.Duration.seconds(1.2), graphic); motion.setFromY(0); motion.setToY(-5); motion.setAutoReverse(true); motion.setCycleCount(javafx.animation.Animation.INDEFINITE);
        box.sceneProperty().addListener((observable, before, scene) -> { if (scene != null && !SettingsManager.settings().isAnimationDisabled()) motion.play(); else motion.stop(); }); return box;
    }
}
