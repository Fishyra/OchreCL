// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.ui.decorator;

import com.jfoenix.controls.JFXButton;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Interpolator;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.Point2D;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.MouseButton;
import javafx.css.PseudoClass;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;
import javafx.scene.shape.Rectangle;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.game.HMCLGameInstance;
import org.jackhuang.hmcl.game.GameComponentType;
import org.jackhuang.hmcl.setting.GameDirectoryManager;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.SVG;
import org.jackhuang.hmcl.ui.construct.Navigator;
import org.jackhuang.hmcl.ui.instances.Instances;
import org.jackhuang.hmcl.ui.main.RootPage;
import org.jackhuang.hmcl.ui.main.AboutPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.jackhuang.hmcl.util.logging.Logger.LOG;

/// Persistent desktop navigation, home inspector and compact launch control around the upstream navigator.
@NotNullByDefault
final class OchreShell extends StackPane {
    /// Compact rail width in logical pixels.
    private static final double COLLAPSED = 60;
    /// Space reserved when navigation is pinned open.
    private static final double EXPANDED = 174;
    /// Owning window decorator.
    private final Decorator decorator;
    /// Existing page navigator; all launch and download functionality stays attached to it.
    private final Navigator navigator;
    /// Main content grid whose first column reserves pinned navigation space.
    private final BorderPane layout = new BorderPane();
    /// Navigation overlay, independent of reserved layout width while hovering.
    private final VBox rail = new VBox(6);
    /// Layout reservation for the navigation rail.
    private final Region reservation = new Region();
    /// Stable page surface; pinning only reflows this once rather than on every animation frame.
    private final StackPane pages = new StackPane();
    /// Scrollable compatibility viewport for upstream pages in small windows.
    private final ScrollPane pageViewport = new ScrollPane();
    /// Reveal clip for the fixed-width navigation texture.
    private final DoubleProperty railReveal = new SimpleDoubleProperty(COLLAPSED - 12);
    /// Appearance animation dedicated to the floating action, independent of sidebar changes.
    private final Timeline buttonMotion = new Timeline();
    /// Compositor-only compensation after a pinned layout change.
    private final Timeline contentMotion = new Timeline();
    /// Saved normalized location of the draggable launch action.
    private FloatingButtonPosition floatingPosition = new FloatingButtonPosition(1, 1);
    /// Press origin in shell coordinates, or null when no pointer gesture is active.
    private @Nullable Point2D pressOrigin;
    /// Button location captured at pointer press.
    private double pressButtonX;
    /// Button location captured at pointer press.
    private double pressButtonY;
    /// Prevents a completed drag from triggering a game launch.
    private boolean dragged;
    /// Whether the home-only instance metadata has been built for the current selection.
    private boolean inspectorDirty = true;
    /// Home-only instance inspector.
    private final VBox inspector = new VBox(12);
    /// Floating action for all non-home pages.
    private final JFXButton compactLaunch = new JFXButton();
    /// Interruptible motion for navigation width, label opacity and reserved layout width.
    private final Timeline railMotion = new Timeline();
    /// Interruptible motion for the home inspector and compact launch button.
    private final Timeline pageMotion = new Timeline();
    /// Animated layout extent for the home inspector.
    private final DoubleProperty inspectorExtent = new SimpleDoubleProperty(0);
    /// Whether the current destination is home.
    private boolean showingHome = true;
    /// Delay before temporary expansion.
    private final PauseTransition hoverDelay = new PauseTransition(Duration.seconds(1));
    /// Brief grace period when the pointer leaves the navigation rail.
    private final PauseTransition closeDelay = new PauseTransition(Duration.millis(180));
    /// Labels revealed by navigation expansion.
    private final List<Label> labels = new ArrayList<>();
    /// Navigation buttons and corresponding page actions.
    private final List<JFXButton> buttons = new ArrayList<>();
    /// Local desktop preferences, independent of user-wide HMCL settings.
    private final Properties preferences = new Properties();
    /// File storing navigation pin state.
    private final Path preferenceFile = Metadata.HMCL_LOCAL_HOME.resolve("ochre-ui.properties");
    /// Whether expansion reserves width permanently.
    private boolean pinned;
    /// Whether the temporary or pinned rail is open.
    private boolean expanded;

    /// Cached server workspace retains switcher and console selection across navigation.
    private @Nullable org.jackhuang.hmcl.server.ServersPage serversPage;
    /// Latest observed game-running state; polling does not rebuild an unchanged panel.
    private boolean gameRunning;
    /// Lightweight lifecycle polling, separate from background load sampling.
    private final Timeline runtimeWatch = new Timeline();
    /// Top-right lifecycle notification, excluded from layout.
    private final Label notification = new Label();
    /// Automatically dismisses a notification without blocking any controls.
    private final PauseTransition notificationDelay = new PauseTransition(Duration.seconds(7));

    /// Coalesced fade/slide motion for the current notification.
    private final Timeline notificationMotion = new Timeline();
    /// Prevents a previous message's delayed exit from hiding a newer message.
    private long notificationGeneration;

    /// Wraps the real navigator with the approved Øchre desktop shell.
    OchreShell(Decorator decorator) {
        this.decorator = decorator;
        navigator = decorator.getNavigator();
        getStyleClass().add("ochre-shell");
        // Content scrolls inside the frame; its preferred height must never enlarge the window chrome.
        setMinSize(0, 0); layout.setMinSize(0, 0); pages.setMinSize(0, 0);
        pageViewport.setMinSize(0, 0); inspector.setMinHeight(0); rail.setMinHeight(0);
        readPreferences();
        pinned = Boolean.parseBoolean(preferences.getProperty("navigation.pinned", "false"));
        expanded = pinned;
        floatingPosition = FloatingButtonPosition.read(preferences);
        reservation.setMinWidth(0);
        layout.setLeft(reservation);
        pageViewport.setContent(navigator);
        pageViewport.setFitToWidth(true);
        pageViewport.setFitToHeight(true);
        pageViewport.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        pageViewport.getStyleClass().add("ochre-page-viewport");
        pages.getChildren().setAll(pageViewport);
        pages.getStyleClass().add("ochre-pages");
        layout.setCenter(pages);
        inspector.getStyleClass().add("ochre-inspector");
        inspector.prefWidthProperty().bind(inspectorExtent);
        inspector.maxWidthProperty().bind(inspectorExtent);
        inspector.setMinWidth(0);
        Rectangle inspectorClip = new Rectangle();
        inspectorClip.widthProperty().bind(inspector.widthProperty());
        inspectorClip.heightProperty().bind(inspector.heightProperty());
        inspectorClip.setArcWidth(36);
        inspectorClip.setArcHeight(36);
        inspector.setClip(inspectorClip);
        rail.getStyleClass().add("ochre-rail");
        rail.setMaxHeight(Double.MAX_VALUE);
        rail.setPrefWidth(EXPANDED - 12);
        rail.setMinWidth(EXPANDED - 12);
        rail.setMaxWidth(EXPANDED - 12);
        Rectangle railClip = new Rectangle();
        railClip.widthProperty().bind(railReveal);
        railClip.heightProperty().bind(rail.heightProperty());
        railClip.setArcWidth(32);
        railClip.setArcHeight(32);
        rail.setClip(railClip);
        StackPane.setAlignment(rail, Pos.TOP_LEFT);
        StackPane.setMargin(rail, new Insets(8, 0, 8, 6));
        nav("固定展开", SVG.MENU, () -> {
            pinned = !pinned;
            preferences.setProperty("navigation.pinned", Boolean.toString(pinned));
            writePreferences();
            expand(pinned);
        });
        nav("主页", SVG.HOME, () -> navigator.clear());
        nav("账户", SVG.PERSON, () -> open(Controllers.getAccountListPage()));
        nav("游戏库", SVG.FORMAT_LIST_BULLETED, () -> open(Controllers.getGameListPage()));
        nav("实例管理", SVG.EXTENSION, () -> {
            @Nullable HMCLGameInstance instance = selected();
            if (instance == null) open(Controllers.getGameListPage());
            else Instances.modifyGameSettings(instance);
        });
        nav("下载", SVG.DOWNLOAD, () -> open(Controllers.getDownloadPage()));
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        rail.getChildren().add(spacer);
        nav("设置", SVG.SETTINGS, () -> {
            Controllers.getSettingsPage().showGameSettings(GameDirectoryManager.getSelectedRepository());
            open(Controllers.getSettingsPage());
        });
        nav("联机服务器", SVG.OCHRE_SERVER, () -> {
            if (serversPage == null) serversPage = org.jackhuang.hmcl.server.ServersPage.getInstance();
            open(serversPage);
        });
        nav("多人联机", SVG.GRAPH2, () -> open(Controllers.getTerracottaPage()));
        nav("关于", SVG.INFO, () -> open(new AboutPage()));
        hoverDelay.setOnFinished(event -> expand(true));
        closeDelay.setOnFinished(event -> { if (!pinned) expand(false); });
        rail.setOnMouseEntered(event -> { closeDelay.stop(); if (!expanded) hoverDelay.playFromStart(); });
        rail.setOnMouseExited(event -> { hoverDelay.stop(); if (!pinned) closeDelay.playFromStart(); });
        compactLaunch.setGraphic(playIcon());
        compactLaunch.getStyleClass().add("ochre-launch-circle");
        compactLaunch.setAccessibleText("启动所选游戏");
        compactLaunch.setOnAction(event -> { if (!dragged) launch(); });
        compactLaunch.setManaged(false);
        configureDragging();
        notification.getStyleClass().add("ochre-runtime-notification");
        notification.setWrapText(true);
        notification.setMaxWidth(320);
        notification.setVisible(false);
        notification.setMouseTransparent(true);
        StackPane.setAlignment(notification, Pos.TOP_RIGHT);
        StackPane.setMargin(notification, new Insets(16));
        org.jackhuang.hmcl.server.ServerManager.NOTICE.addListener((observable, oldText, text) -> {
            if (text.isBlank()) return;
            showNotification(text, org.jackhuang.hmcl.server.ServerManager.NOTICE_KIND.get(), animate());
        });
        getChildren().setAll(layout, compactLaunch, rail, notification);
        runtimeWatch.getKeyFrames().setAll(new KeyFrame(Duration.seconds(1), event -> refreshRuntime()));
        runtimeWatch.setCycleCount(Timeline.INDEFINITE);
        sceneProperty().addListener((observable, oldScene, scene) -> { if (scene == null) runtimeWatch.stop(); else runtimeWatch.play(); });
        navigator.addEventHandler(Navigator.NavigationEvent.NAVIGATED, event -> refreshPage());
        GameDirectoryManager.selectedInstanceProperty().addListener(observable -> {
            inspectorDirty = true;
            if (showingHome) refreshInspector();
        });
        widthProperty().addListener(observable -> { updateInspectorWidth(); placeFloatingButton(); });
        heightProperty().addListener(observable -> {
            pseudoClassStateChanged(PseudoClass.getPseudoClass("compact"), getHeight() < 520);
            placeFloatingButton();
        });
        expand(pinned);
        refreshInspector();
        Platform.runLater(this::refreshPage);
    }

    /// Replaces the current message and animates in only transform/opacity properties.
    private void showNotification(String text, org.jackhuang.hmcl.server.ServerManager.NoticeKind kind, boolean motion) {
        long generation = ++notificationGeneration;
        notificationDelay.stop(); notificationMotion.stop(); notificationMotion.setOnFinished(null);
        notification.setText(text);
        SVG icon = switch (kind) {
            case SUCCESS -> SVG.CHECK_CIRCLE;
            case WARNING -> SVG.WARNING;
            case ERROR -> SVG.ERROR;
            case INFO -> SVG.INFO;
        };
        notification.setGraphic(icon.createIcon(22)); notification.setGraphicTextGap(10);
        notification.setAccessibleText(kind + ": " + text);
        notification.pseudoClassStateChanged(PseudoClass.getPseudoClass("error"), kind == org.jackhuang.hmcl.server.ServerManager.NoticeKind.ERROR);
        notification.setVisible(true);
        notification.setOpacity(motion ? 0 : 1); notification.setTranslateX(motion ? 16 : 0);
        if (motion) {
            notificationMotion.getKeyFrames().setAll(new KeyFrame(Duration.millis(180),
                    new KeyValue(notification.opacityProperty(), 1, Interpolator.EASE_OUT),
                    new KeyValue(notification.translateXProperty(), 0, Interpolator.EASE_OUT)));
            notificationMotion.play();
        }
        notificationDelay.setOnFinished(event -> hideNotification(generation, animate()));
        notificationDelay.playFromStart();
    }
    /// Dismisses only the current message; stale callbacks are harmless after a replacement.
    private void hideNotification(long generation, boolean motion) {
        if (generation != notificationGeneration) return;
        notificationMotion.stop(); notificationMotion.setOnFinished(null);
        if (!motion) { notification.setVisible(false); notification.setOpacity(1); notification.setTranslateX(0); return; }
        notificationMotion.getKeyFrames().setAll(new KeyFrame(Duration.millis(140),
                new KeyValue(notification.opacityProperty(), 0, Interpolator.EASE_IN),
                new KeyValue(notification.translateXProperty(), 12, Interpolator.EASE_IN)));
        notificationMotion.setOnFinished(event -> { if (generation == notificationGeneration) notification.setVisible(false); });
        notificationMotion.play();
    }

    /// Returns the selected game instance without caching stale repository snapshots.
    private @Nullable HMCLGameInstance selected() {
        return GameDirectoryManager.getSelectedRepository().getSelectedInstance();
    }

    /// Adds an icon-first navigation action with keyboard and screen-reader labels.
    private void nav(String text, SVG icon, Runnable action) {
        JFXButton button = new JFXButton();
        Label label = new Label(text);
        label.setMinWidth(100);
        label.setOpacity(0);
        labels.add(label);
        HBox content = new HBox(13, icon.createIcon(21), label);
        content.setAlignment(Pos.CENTER_LEFT);
        button.setGraphic(content);
        button.setAccessibleText(text);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(event -> action.run());
        buttons.add(button);
        rail.getChildren().add(button);
    }

    /// Returns whether motion is enabled and this shell is displayed in a real window.
    private boolean animate() {
        return !org.jackhuang.hmcl.setting.SettingsManager.settings().isAnimationDisabled()
                && getScene() != null && getScene().getWindow() != null && getScene().getWindow().isShowing();
    }

    /// Reveals a fixed-width rail with clipping and opacity; hover never invalidates page layout.
    private void expand(boolean open) {
        expanded = open;
        railMotion.stop();
        double target = open ? EXPANDED - 12 : COLLAPSED - 12;
        double reserved = pinned ? EXPANDED : COLLAPSED;
        double previousReserved = Math.max(COLLAPSED, reservation.getPrefWidth());
        boolean reflow = Double.compare(previousReserved, reserved) != 0;
        reservation.setMinWidth(reserved);
        reservation.setPrefWidth(reserved);
        reservation.setMaxWidth(reserved);
        labels.get(0).setText(pinned ? "取消固定" : "固定展开");
        List<KeyValue> values = new ArrayList<>();
        values.add(new KeyValue(railReveal, target, Interpolator.EASE_BOTH));
        for (Label label : labels) {
            label.setManaged(true);
            label.setVisible(true);
            values.add(new KeyValue(label.opacityProperty(), open ? 1 : 0, Interpolator.EASE_BOTH));
        }
        railMotion.getKeyFrames().setAll(new KeyFrame(Duration.millis(160), values.toArray(KeyValue[]::new)));
        railMotion.setOnFinished(null);
        if (animate()) railMotion.play();
        else {
            railReveal.set(target);
            for (Label label : labels) label.setOpacity(open ? 1 : 0);
        }
        if (reflow) {
            contentMotion.stop();
            if (animate()) {
                pages.setTranslateX(previousReserved - reserved);
                pages.setOpacity(.92);
                contentMotion.getKeyFrames().setAll(new KeyFrame(Duration.millis(160),
                        new KeyValue(pages.translateXProperty(), 0, Interpolator.EASE_OUT),
                        new KeyValue(pages.opacityProperty(), 1, Interpolator.EASE_OUT)));
                contentMotion.play();
            } else { pages.setTranslateX(0); pages.setOpacity(1); }
            updateInspectorWidth();
            placeFloatingButton();
        }
    }

    /// Opens a top-level destination after clearing the previous top-level navigation stack.
    private void open(Node page) {
        navigator.clear();
        Controllers.navigate(page);
    }

    /// Switches the content layout once and animates only pixels, never the whole page width each frame.
    private void refreshPage() {
        updateLaunchIcon();
        boolean home = navigator.getCurrentPage() instanceof RootPage;
        boolean changed = showingHome != home || (home && layout.getRight() == null);
        showingHome = home;
        navigator.setMinWidth(home ? 0 : 660);
        if (getScene() != null) org.jackhuang.hmcl.server.ServerWindowSizing.navigationChanged(getScene().getWindow(), navigator::getCurrentPage);
        navigator.getStyleClass().removeAll("ochre-home-canvas", "ochre-content-panel");
        navigator.getStyleClass().add(home ? "ochre-home-canvas" : "ochre-content-panel");
        if (!changed) return;
        pageMotion.stop();
        layout.setRight(home ? inspector : null);
        if (home) {
            if (inspectorDirty) refreshInspector();
            inspectorExtent.set(targetInspectorWidth());
            if (animate()) {
                inspector.setOpacity(0);
                inspector.setTranslateX(14);
                pageMotion.getKeyFrames().setAll(new KeyFrame(Duration.millis(180),
                        new KeyValue(inspector.opacityProperty(), 1, Interpolator.EASE_OUT),
                        new KeyValue(inspector.translateXProperty(), 0, Interpolator.EASE_OUT)));
                pageMotion.setOnFinished(null);
                pageMotion.play();
            } else { inspector.setOpacity(1); inspector.setTranslateX(0); }
        }
        revealCompact(!home, animate());
        placeFloatingButton();
    }

    /// Starts a distinct fade-and-scale appearance every time the action leaves its hidden home state.
    private void revealCompact(boolean visible, boolean motion) {
        buttonMotion.stop();
        compactLaunch.setManaged(false);
        compactLaunch.setMouseTransparent(!visible);
        if (!motion) {
            compactLaunch.setVisible(visible);
            compactLaunch.setOpacity(visible ? 1 : 0);
            compactLaunch.setScaleX(visible ? 1 : .65);
            compactLaunch.setScaleY(visible ? 1 : .65);
            return;
        }
        if (visible && !compactLaunch.isVisible()) {
            compactLaunch.setOpacity(0);
            compactLaunch.setScaleX(.65);
            compactLaunch.setScaleY(.65);
        }
        compactLaunch.setVisible(true);
        buttonMotion.getKeyFrames().setAll(new KeyFrame(Duration.millis(200),
                new KeyValue(compactLaunch.opacityProperty(), visible ? 1 : 0, Interpolator.EASE_OUT),
                new KeyValue(compactLaunch.scaleXProperty(), visible ? 1 : .65, Interpolator.EASE_OUT),
                new KeyValue(compactLaunch.scaleYProperty(), visible ? 1 : .65, Interpolator.EASE_OUT)));
        buttonMotion.setOnFinished(event -> compactLaunch.setVisible(visible));
        buttonMotion.play();
    }

    /// Keeps drag and click gestures distinct, and saves only the completed drag endpoint.
    private void configureDragging() {
        FXUtils.installFastTooltip(compactLaunch, "启动游戏 · 拖动可移动");
        compactLaunch.setOnKeyPressed(event -> dragged = false);
        compactLaunch.addEventFilter(MouseEvent.MOUSE_PRESSED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) {
                dragged = false;
                pressOrigin = null;
                return;
            }
            dragged = false;
            pressOrigin = sceneToLocal(event.getSceneX(), event.getSceneY());
            pressButtonX = compactLaunch.getLayoutX();
            pressButtonY = compactLaunch.getLayoutY();
        });
        compactLaunch.addEventFilter(MouseEvent.MOUSE_DRAGGED, event -> {
            @Nullable Point2D origin = pressOrigin;
            if (origin == null) return;
            Point2D current = sceneToLocal(event.getSceneX(), event.getSceneY());
            if (!dragged && current.distance(origin) < 5) return;
            dragged = true;
            compactLaunch.disarm();
            floatingPosition = FloatingButtonPosition.fromPixels(pressButtonX + current.getX() - origin.getX(),
                    pressButtonY + current.getY() - origin.getY(), getWidth(), getHeight(), reservation.getPrefWidth());
            placeFloatingButton();
            event.consume();
        });
        compactLaunch.addEventFilter(MouseEvent.MOUSE_RELEASED, event -> {
            if (event.getButton() != MouseButton.PRIMARY) return;
            pressOrigin = null;
            if (!dragged) return;
            compactLaunch.disarm();
            saveFloatingPosition();
            event.consume();
        });
        compactLaunch.addEventFilter(MouseEvent.MOUSE_CLICKED, event -> { if (dragged) event.consume(); });
        ContextMenu menu = new ContextMenu();
        MenuItem reset = new MenuItem("恢复右下角位置");
        reset.setOnAction(event -> { floatingPosition = new FloatingButtonPosition(1, 1); placeFloatingButton(); saveFloatingPosition(); });
        menu.getItems().add(reset);
        compactLaunch.setOnContextMenuRequested(event -> menu.show(compactLaunch, event.getScreenX(), event.getScreenY()));
    }

    /// Repositions without resizing or relaying out any underlying page.
    private void placeFloatingButton() {
        Point2D point = floatingPosition.pixels(getWidth(), getHeight(), reservation.getPrefWidth());
        compactLaunch.resizeRelocate(point.getX(), point.getY(), 56, 56);
    }

    /// Persists normalized coordinates so a saved position remains on-screen at every window size.
    private void saveFloatingPosition() {
        preferences.setProperty("launch.x", Double.toString(floatingPosition.x()));
        preferences.setProperty("launch.y", Double.toString(floatingPosition.y()));
        writePreferences();
    }

    /// Computes a safe inspector width for both normal and pinned navigation states.
    private double targetInspectorWidth() {
        double available = getWidth() - (pinned ? EXPANDED : COLLAPSED) - 30;
        return gameRunning && runtimeReady() ? Math.max(210, available * .72) : Math.max(210, Math.min(310, available * (pinned ? .35 : .4)));
    }

    /// Adapts to a real resize or pin change once; it does not start or cancel button animations.
    private void updateInspectorWidth() {
        if (showingHome && layout.getRight() != null) inspectorExtent.set(targetInspectorWidth());
    }

    /// Expands optional instance information with a height and opacity transition.
    private void toggleDetails(VBox details) {
        boolean opening = !Boolean.TRUE.equals(details.getProperties().get("ochre.expanded"));
        details.getProperties().put("ochre.expanded", opening);
        @Nullable Timeline previous = (Timeline) details.getProperties().get("ochre.motion");
        if (previous != null) previous.stop();
        if (!animate()) { details.setVisible(opening); details.setManaged(opening); return; }
        double start = details.isManaged() ? details.getHeight() : 0;
        details.setVisible(true);
        details.setManaged(true);
        details.applyCss();
        details.setMinHeight(0);
        details.setMaxHeight(Region.USE_PREF_SIZE);
        double end = opening ? details.prefHeight(Math.max(120, inspector.getWidth() - 24)) : 0;
        details.setPrefHeight(start);
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(details.widthProperty());
        clip.heightProperty().bind(details.heightProperty());
        details.setClip(clip);
        Timeline motion = new Timeline(new KeyFrame(Duration.millis(180),
                new KeyValue(details.prefHeightProperty(), end, Interpolator.EASE_BOTH),
                new KeyValue(details.opacityProperty(), opening ? 1 : 0, Interpolator.EASE_BOTH)));
        details.getProperties().put("ochre.motion", motion);
        motion.setOnFinished(event -> {
            details.setVisible(opening);
            details.setManaged(opening);
            details.setPrefHeight(Region.USE_COMPUTED_SIZE);
        });
        motion.play();
    }

    /// Builds real instance metadata and the persisted cover without synthetic sample information.
    private void refreshInspector() {
        inspectorDirty = false;
        inspector.getChildren().clear();
        @Nullable HMCLGameInstance instance = selected();
        if (instance != null && gameRunning && runtimeReady()) {
            var session = gameSession(instance);
            Label heading = new Label(instance.getId().toString());
            heading.getStyleClass().add("ochre-instance-title");
            var panel = new org.jackhuang.hmcl.server.RuntimePanel(session);
            VBox.setVgrow(panel, Priority.ALWAYS);
            JFXButton stop = new JFXButton("停止游戏");
            stop.setGraphic(stopIcon());
            stop.getStyleClass().add("ochre-launch-wide"); stop.setMaxWidth(Double.MAX_VALUE);
            stop.setOnAction(event -> launch());
            inspector.getChildren().setAll(heading, panel, stop);
            updateInspectorWidth();
            return;
        }
        StackPane cover = new StackPane();
        cover.getStyleClass().add("ochre-cover");
        cover.setMinWidth(0);
        cover.setPrefWidth(240);
        cover.setMaxWidth(Double.MAX_VALUE);
        cover.setMinHeight(170);
        cover.setPrefHeight(170);
        cover.setMaxHeight(170);
        cover.setAccessibleText("点击设置实例封面");
        cover.setFocusTraversable(true);
        cover.setOnKeyPressed(event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ENTER || event.getCode() == javafx.scene.input.KeyCode.SPACE) {
                chooseCover();
                event.consume();
            }
        });
        cover.setOnMouseClicked(event -> {
            if (event.getButton() == javafx.scene.input.MouseButton.PRIMARY) chooseCover();
        });
        @Nullable Path path = instance == null ? null : coverPath(instance);
        Label hint = new Label(instance == null ? "未选择游戏" : "正在读取版本封面…");
        hint.getStyleClass().add("subtitle-label");
        cover.getChildren().add(hint);
        if (instance != null) {
            org.jackhuang.hmcl.task.Schedulers.io().execute(() -> {
                @Nullable Image image = InstanceCoverLoader.load(path, instance);
                Platform.runLater(() -> {
                    // The completion belongs to this cover node, never a newer selected instance.
                    if (image != null) cover.getChildren().setAll(new OchreCoverView(image));
                    else hint.setText("版本背景不可用 · 点击设置封面");
                });
            });
        }
        ContextMenu menu = new ContextMenu();
        MenuItem clear = new MenuItem("恢复版本默认封面");
        clear.setOnAction(event -> {
            @Nullable HMCLGameInstance current = selected();
            if (current != null) {
                try { Files.deleteIfExists(coverPath(current)); refreshInspector(); }
                catch (IOException e) { LOG.warning("Unable to remove instance cover", e); }
            }
        });
        menu.getItems().add(clear);
        cover.setOnContextMenuRequested(event -> menu.show(cover, event.getScreenX(), event.getScreenY()));
        Label title = new Label(instance == null ? "未选择游戏" : instance.getId().toString());
        title.setWrapText(true);
        title.getStyleClass().add("ochre-instance-title");
        Label version = new Label(instance == null ? "从游戏库选择或安装一个版本" : "Minecraft " + instance.getVersion());
        version.getStyleClass().add("subtitle-label");
        FlowPane tags = new FlowPane(5, 5);
        if (instance != null) {
            for (GameComponentType component : GameComponentType.ALL) {
                if (component != GameComponentType.GAME && instance.getAnalyzer().has(component)) {
                    Label tag = new Label(component.toString());
                    tag.getStyleClass().add("ochre-instance-tag");
                    tags.getChildren().add(tag);
                }
            }
        }
        Label runtime = new Label("运行环境\n按实例设置选择 Java");
        runtime.setWrapText(true);
        runtime.getStyleClass().add("ochre-runtime");
        VBox details = new VBox(8);
        details.getStyleClass().add("ochre-instance-details");
        details.setVisible(false);
        details.setManaged(false);
        JFXButton more = new JFXButton("更多信息");
        more.setOnAction(event -> toggleDetails(details));
        if (instance != null) {
            Label directory = new Label(instance.getInstanceRoot().toString());
            directory.setWrapText(true);
            JFXButton manage = new JFXButton("管理此实例");
            manage.setOnAction(event -> Instances.modifyGameSettings(instance));
            details.getChildren().setAll(directory, manage);
        }
        Region spacer = new Region();
        VBox.setVgrow(spacer, Priority.ALWAYS);
        JFXButton launch = new JFXButton(instance == null ? "安装游戏" : "启动游戏");
        launch.getStyleClass().add("ochre-launch-wide");
        launch.setMaxWidth(Double.MAX_VALUE);
        launch.setOnAction(event -> launch());
        JFXButton switchGame = new JFXButton();
        switchGame.setGraphic(SVG.ARROW_DROP_UP.createIcon(24));
        switchGame.setAccessibleText("切换游戏实例");
        switchGame.setOnAction(event -> open(Controllers.getGameListPage()));
        HBox launchBar = new HBox(5, launch, switchGame);
        HBox.setHgrow(launch, Priority.ALWAYS);
        VBox information = new VBox(12, cover, title, version, tags, runtime, more, details);
        ScrollPane scrolling = new ScrollPane(information);
        scrolling.setMinSize(0, 0);
        scrolling.setFitToWidth(true);
        scrolling.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrolling.getStyleClass().add("ochre-inspector-scroll");
        VBox.setVgrow(scrolling, Priority.ALWAYS);
        inspector.getChildren().setAll(scrolling, launchBar);
    }

    /// Starts the actual upstream launch flow, or opens installation if no game is selected.
    private void launch() {
        if (navigator.getCurrentPage() instanceof org.jackhuang.hmcl.server.ServersPage) {
            org.jackhuang.hmcl.server.ServersPage.join(); return;
        }
        @Nullable HMCLGameInstance instance = selected();
        if (instance == null) open(Controllers.getDownloadPage());
        else if (gameSession(instance).isRunning()) Controllers.confirm("是否停止当前游戏？请先保存游戏进度。", "停止游戏", () -> gameSession(instance).stop(), null);
        else Instances.launch(instance, helper -> helper.setKeep());
    }

    /// Resolves the selected game's process registry entry.
    private org.jackhuang.hmcl.server.RuntimeSession gameSession(HMCLGameInstance instance) {
        return org.jackhuang.hmcl.server.RuntimeSession.game(instance.getInstanceRoot().toAbsolutePath().normalize().toString());
    }
    /// Reports real process liveness for the current selection.
    private boolean runningGame() { @Nullable HMCLGameInstance instance = selected(); return instance != null && gameSession(instance).isRunning(); }
    /// Waits for the game window readiness signal, rather than expanding behind the launch progress popup.
    private boolean runtimeReady() {
        @Nullable HMCLGameInstance instance = selected();
        return instance != null && gameSession(instance).isRunning() && gameSession(instance).stateProperty().get().equals("运行中");
    }
    /// Rebuilds only when readiness changes, deferring visible expansion until the launch modal has closed.
    private void refreshRuntime() {
        boolean running = runtimeReady();
        if (running == gameRunning) return;
        @Nullable Object dialogs = decorator.getDialogContainer().getProperties().get(org.jackhuang.hmcl.ui.DialogUtils.PROPERTY_DIALOG_PANE_INSTANCE);
        if (running && dialogs instanceof org.jackhuang.hmcl.ui.construct.JFXDialogPane pane && pane.peek().isPresent()) return;
        gameRunning = running;
        updateLaunchIcon();
        inspectorDirty = true;
        if (showingHome) {
            double previous = inspectorExtent.get();
            refreshInspector();
            double target = targetInspectorWidth();
            if (animate()) {
                inspectorExtent.set(previous);
                pageMotion.stop();
                inspector.setOpacity(.85);
                pageMotion.getKeyFrames().setAll(new KeyFrame(Duration.millis(280),
                        new KeyValue(inspectorExtent, target, Interpolator.EASE_OUT),
                        new KeyValue(inspector.opacityProperty(), 1, Interpolator.EASE_OUT)));
                pageMotion.play();
            } else inspectorExtent.set(target);
        }
    }
    /// Uses the server join symbol on its workspace and a stop square for a running client.
    private void updateLaunchIcon() {
        boolean server = navigator.getCurrentPage() instanceof org.jackhuang.hmcl.server.ServersPage;
        compactLaunch.setGraphic(server ? wirelessIcon() : runningGame() ? stopIcon() : playIcon());
        compactLaunch.setAccessibleText(server ? "加入所选服务器" : runningGame() ? "停止游戏" : "启动所选游戏");
    }
    /// Creates the standard stop square.
    private Node stopIcon() {
        javafx.scene.shape.Rectangle square = new javafx.scene.shape.Rectangle(17, 17);
        square.getStyleClass().add("svg"); return square;
    }
    /// Creates a wireless connection glyph consistent with the compact action's theme.
    private Node wirelessIcon() {
        SVGPath icon = new SVGPath();
        icon.setContent("M1 8Q12 -1 23 8L21 11Q12 4 3 11ZM5 13Q12 7 19 13L17 16Q12 12 7 16ZM9 18Q12 15 15 18L12 22Z");
        icon.getStyleClass().add("svg"); return icon;
    }

    /// Creates the compact triangular launch symbol.
    private Node playIcon() {
        SVGPath triangle = new SVGPath();
        triangle.setContent("M 0 0 L 17 11 L 0 22 Z");
        triangle.getStyleClass().add("svg");
        return triangle;
    }

    /// Returns a stable cover location scoped to both game directory and instance identity.
    private Path coverPath(HMCLGameInstance instance) {
        String key = UUID.nameUUIDFromBytes(instance.getInstanceRoot().toAbsolutePath().normalize().toString().getBytes(StandardCharsets.UTF_8)).toString();
        return Metadata.HMCL_LOCAL_HOME.resolve("ochre-covers").resolve(key + ".image");
    }

    /// Copies a validated PNG or JPEG into the launcher's own cover store.
    private void chooseCover() {
        @Nullable HMCLGameInstance instance = selected();
        if (instance == null) { open(Controllers.getGameListPage()); return; }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("设置实例封面");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("图片", "*.png", "*.jpg", "*.jpeg"));
        java.io.@Nullable File file = chooser.showOpenDialog(Controllers.getStage());
        if (file == null) return;
        try {
            if (Files.size(file.toPath()) > 20 * 1024 * 1024) throw new IOException("图片不能超过 20 MB");
            Image check = new Image(file.toURI().toString(), 640, 400, true, true);
            if (check.isError()) throw new IOException("无法读取所选图片", check.getException());
            Path target = coverPath(instance);
            Files.createDirectories(target.getParent());
            Files.copy(file.toPath(), target, StandardCopyOption.REPLACE_EXISTING);
            refreshInspector();
        } catch (IOException e) {
            LOG.warning("Unable to save instance cover", e);
            Controllers.showToast("无法设置封面：" + e.getMessage());
        }
    }

    /// Reads optional desktop preferences without touching upstream configuration.
    private void readPreferences() {
        if (!Files.isRegularFile(preferenceFile)) return;
        try (InputStream stream = Files.newInputStream(preferenceFile)) { preferences.load(stream); }
        catch (IOException e) { LOG.warning("Unable to read desktop preferences", e); }
    }

    /// Queues coalesced preference writes on the existing background saver rather than blocking JavaFX.
    private void writePreferences() {
        try {
            StringWriter snapshot = new StringWriter();
            preferences.store(snapshot, "OchreCL desktop preferences");
            org.jackhuang.hmcl.util.FileSaver.save(preferenceFile, snapshot.toString());
        } catch (IOException e) { LOG.warning("Unable to save desktop preferences", e); }
    }
}
