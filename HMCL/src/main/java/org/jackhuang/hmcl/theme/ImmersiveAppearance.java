package org.jackhuang.hmcl.theme;

import com.jfoenix.controls.JFXButton;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.setting.*;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicBoolean;

/// Transactional entry to experimental glass appearance: an image must be selected and validated first.
@NotNullByDefault
public final class ImmersiveAppearance {
    /// Persisted mode key, kept separate from normal light/dark/automatic values.
    public static final String MODE = "immersive";
    /// Player-facing caption shared by settings and the first-run screen.
    public static final String LABEL = "沉浸式透明（实验）";
    /// Utility class.
    private ImmersiveAppearance() {}
    /// Returns whether a direct appearance override currently enables the glass surfaces.
    public static boolean isEnabled() { return MODE.equals(SettingsManager.settings().themeBrightnessModeProperty().get()) && SettingsManager.settings().getThemeAppearanceOverrides().contains(LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE); }
    /// Observes mode and inheritance together, so reverting to an inherited theme also restores color controls.
    public static javafx.beans.binding.BooleanBinding enabledBinding() { return javafx.beans.binding.Bindings.createBooleanBinding(ImmersiveAppearance::isEnabled, SettingsManager.settings().themeBrightnessModeProperty(), SettingsManager.settings().getThemeAppearanceOverrides()); }
    /// Captures direct wallpaper settings before a temporary immersive selection, across restarts.
    private static final Path SNAPSHOT = Metadata.HMCL_LOCAL_HOME.resolve("ochre-backgrounds/before-immersive.properties");
    /// Installs restoration once for this process.
    private static @Nullable LauncherSettings installedSettings;
    /// Restores wallpaper settings when leaving immersive mode, including after an application restart.
    public static void install() {
        var settings = SettingsManager.settings();
        if (installedSettings == settings) return;
        installedSettings = settings;
        settings.themeBrightnessModeProperty().addListener((observable, before, after) -> {
            if (MODE.equals(before) && !MODE.equals(after)) restoreBackground();
        });
    }
    /// Persists the original wallpaper once; selecting a second immersive picture must not overwrite it.
    private static void saveBackground() throws java.io.IOException {
        if (isEnabled()) return;
        var settings = SettingsManager.settings(); var values = new java.util.Properties();
        values.setProperty("type", settings.backgroundTypeProperty().get().name());
        values.setProperty("path", java.util.Objects.toString(settings.customBackgroundImagePathProperty().get(), ""));
        values.setProperty("builtin", java.util.Objects.toString(settings.builtinBackgroundIdProperty().get(), BuiltinBackground.FALLBACK.id()));
        values.setProperty("network", java.util.Objects.toString(settings.networkBackgroundImageUrlProperty().get(), ""));
        values.setProperty("opacity", Double.toString(settings.backgroundOpacityProperty().get()));
        values.setProperty("backgroundOverride", Boolean.toString(settings.getThemeAppearanceOverrides().contains(LauncherSettings.THEME_APPEARANCE_BACKGROUND)));
        values.setProperty("opacityOverride", Boolean.toString(settings.getThemeAppearanceOverrides().contains(LauncherSettings.THEME_APPEARANCE_BACKGROUND_OPACITY)));
        Files.createDirectories(SNAPSHOT.getParent());
        try (var output = Files.newOutputStream(SNAPSHOT)) { values.store(output, "Original wallpaper before immersive mode"); }
    }
    /// Restores only wallpaper properties, preserving the newly selected normal appearance mode and theme color.
    private static void restoreBackground() {
        if (!Files.isRegularFile(SNAPSHOT)) return;
        try {
            var values = new java.util.Properties(); try (var input = Files.newInputStream(SNAPSHOT)) { values.load(input); }
            var settings = SettingsManager.settings();
            settings.customBackgroundImagePathProperty().set(values.getProperty("path", ""));
            settings.builtinBackgroundIdProperty().set(values.getProperty("builtin", BuiltinBackground.FALLBACK.id()));
            settings.networkBackgroundImageUrlProperty().set(values.getProperty("network", ""));
            settings.backgroundTypeProperty().set(BackgroundType.valueOf(values.getProperty("type")));
            settings.backgroundOpacityProperty().set(Double.parseDouble(values.getProperty("opacity", "1")));
            for (String key : new String[]{LauncherSettings.THEME_APPEARANCE_BACKGROUND, LauncherSettings.THEME_APPEARANCE_BACKGROUND_OPACITY}) {
                String flag = key.equals(LauncherSettings.THEME_APPEARANCE_BACKGROUND) ? "backgroundOverride" : "opacityOverride";
                if (Boolean.parseBoolean(values.getProperty(flag))) settings.getThemeAppearanceOverrides().add(key); else settings.getThemeAppearanceOverrides().remove(key);
            }
            Files.delete(SNAPSHOT);
        } catch (Exception error) { org.jackhuang.hmcl.server.ServerManager.notice("原背景恢复失败，已保留恢复记录：" + error.getMessage()); }
    }
    /// Applies glass after a validated image source and durable restoration record are ready.
    private static void enableImage(BackgroundType type, @Nullable String path, Runnable enabled) throws java.io.IOException {
        saveBackground(); var settings = SettingsManager.settings();
        if (path != null) settings.customBackgroundImagePathProperty().set(path);
        settings.backgroundTypeProperty().set(type); settings.backgroundOpacityProperty().set(1);
        settings.getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BACKGROUND);
        settings.getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BACKGROUND_OPACITY);
        settings.getThemeAppearanceOverrides().add(LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE);
        settings.themeBrightnessModeProperty().set(MODE); enabled.run();
    }
    /// Reuses a selected image first, otherwise asks for a local, built-in or Bing image.
    public static void requestEnable(Runnable enabled, Runnable cancelled) {
        install();
        try {
            var resolved = ThemePackManager.resolveCurrentBackground(ThemePackManager.currentResolveContext());
            if (!isEnabled() && java.util.Set.of(BackgroundType.BUILTIN, BackgroundType.CUSTOM, BackgroundType.NETWORK, BackgroundType.BING).contains(resolved.type())) {
                enableImage(resolved.type(), null, enabled); return;
            }
        } catch (Exception error) { org.jackhuang.hmcl.util.logging.Logger.LOG.warning("Cannot reuse current wallpaper", error); }
        VBox pane = new VBox(14); pane.setPadding(new Insets(24)); pane.setPrefWidth(520); pane.setMaxWidth(620); pane.getStyleClass().add("jfx-dialog-layout");
        Label title = new Label(LABEL); title.setGraphic(SVG.OCHRE_SCREENSHOT.createIcon(24)); title.getStyleClass().add("ochre-instance-title");
        Label hint = new Label("选择一张背景图片。主题色将自动跟随图片，界面使用半透明毛玻璃；文字与图标保持清晰。"); hint.setWrapText(true);
        Label status = new Label(); status.setWrapText(true); ProgressIndicator spinner = new ProgressIndicator(); spinner.setMaxSize(24, 24); spinner.setVisible(false); spinner.managedProperty().bind(spinner.visibleProperty());
        AtomicBoolean finished = new AtomicBoolean();
        JFXButton local = new JFXButton("选择本地图片"), bing = new JFXButton("使用必应壁纸"), cancel = new JFXButton("取消");
        local.setGraphic(SVG.FOLDER.createIcon(18)); bing.setGraphic(SVG.GLOBE_BOOK.createIcon(18));
        java.util.function.BiConsumer<BackgroundType, @Nullable Path> load = (type, source) -> {
            local.setDisable(true); bing.setDisable(true); spinner.setVisible(true); status.setText("正在准备背景图片…");
            Schedulers.io().execute(() -> {
                try {
                    @Nullable Path selected = source;
                    if (type == BackgroundType.BING) selected = BingWallpaper.refresh();
                    if (selected == null || !Files.isRegularFile(selected) || Files.size(selected) > 32 * 1024 * 1024) throw new java.io.IOException("请选择不超过 32 MiB 的图片文件");
                    Image image = new Image(selected.toUri().toString(), 1920, 1080, true, true); if (image.isError() || image.getWidth() < 1) throw new java.io.IOException("所选文件不是可读取的图片");
                    Path destination = Metadata.HMCL_LOCAL_HOME.resolve("ochre-backgrounds/immersive-" + java.util.UUID.randomUUID() + ".image");
                    if (type != BackgroundType.BING) { Files.createDirectories(destination.getParent()); Files.copy(selected, destination.resolveSibling(destination.getFileName() + ".new"), StandardCopyOption.REPLACE_EXISTING); Files.move(destination.resolveSibling(destination.getFileName() + ".new"), destination, StandardCopyOption.REPLACE_EXISTING); }
                    Platform.runLater(() -> {
                        if (!finished.compareAndSet(false, true)) return;
                        try { enableImage(type, type == BackgroundType.BING ? null : destination.toString(), () -> { pane.fireEvent(new DialogCloseEvent()); enabled.run(); }); }
                        catch (java.io.IOException error) { finished.set(false); local.setDisable(false); bing.setDisable(false); spinner.setVisible(false); status.setText(error.getMessage()); }
                    });
                } catch (Exception error) { Platform.runLater(() -> { if (finished.get()) return; local.setDisable(false); bing.setDisable(false); spinner.setVisible(false); status.setText(error.getMessage()); }); }
            });
        };
        local.setOnAction(event -> { FileChooser chooser = new FileChooser(); chooser.setTitle("选择沉浸式背景图片"); chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("背景图片", "*.png", "*.jpg", "*.jpeg", "*.webp")); @Nullable var file = Controllers.showOpenDialog(chooser); if (file != null) load.accept(BackgroundType.CUSTOM, file); });
        bing.setOnAction(event -> load.accept(BackgroundType.BING, null));
        cancel.setOnAction(event -> { finished.set(true); pane.fireEvent(new DialogCloseEvent()); cancelled.run(); });
        ComboBox<String> builtin = new ComboBox<>(); builtin.getItems().setAll(BuiltinBackground.BUILTIN_BACKGROUND_IDS); builtin.setConverter(BuiltinBackground.converter()); builtin.getSelectionModel().selectFirst();
        JFXButton useBuiltin = new JFXButton("使用内置图片"); useBuiltin.setGraphic(SVG.OCHRE_SCREENSHOT.createIcon(18));
        useBuiltin.setOnAction(event -> {
            try { enableImage(BackgroundType.BUILTIN, null, () -> { SettingsManager.settings().builtinBackgroundIdProperty().set(builtin.getValue()); finished.set(true); pane.fireEvent(new DialogCloseEvent()); enabled.run(); }); }
            catch (java.io.IOException error) { status.setText(error.getMessage()); }
        });
        pane.getChildren().setAll(title, hint, new HBox(10, builtin, useBuiltin), new HBox(10, local, bing), new HBox(10, spinner, status), cancel); Controllers.dialog(pane);
    }
}
