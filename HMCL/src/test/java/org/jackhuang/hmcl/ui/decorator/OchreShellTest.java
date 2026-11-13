// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.ui.decorator;

import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.ButtonBase;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import org.jackhuang.hmcl.JavaFXLauncher;
import org.jackhuang.hmcl.ui.animation.ContainerAnimations;
import org.jackhuang.hmcl.ui.main.RootPage;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises actual JavaFX navigation reflow and home-only inspector visibility.
@NotNullByDefault
public final class OchreShellTest {
    /// Pinning reserves space; nested navigation hides the inspector and exposes the compact launch action.
    @Test
    public void pinnedNavigationReservesSpaceAndInspectorOnlyAppearsAtHome() throws Exception {
        java.nio.file.Path isolated = java.nio.file.Files.createTempDirectory("ochre-shell-test");
        System.setProperty("hmcl.dir", isolated.resolve("local").toString());
        System.setProperty("hmcl.home", isolated.resolve("home").toString());
        JavaFXLauncher.start();
        assertTrue(JavaFXLauncher.isStarted());
        CompletableFuture<Void> result = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                try { org.jackhuang.hmcl.setting.SettingsManager.settings(); } catch (IllegalStateException notLoaded) { org.jackhuang.hmcl.setting.SettingsManager.init(); }
                org.jackhuang.hmcl.setting.GameDirectoryManager.init();
                Decorator decorator = new Decorator(new RootPage(), stage -> {});
                Region root = (Region) decorator.getRoot();
                new Scene(root, 1000, 700);
                root.applyCss();
                root.resize(1000, 700);
                root.layout();
                OchreShell shell = (OchreShell) root.lookup(".ochre-shell");
                assertNotNull(shell);
                java.lang.reflect.Method refresh = OchreShell.class.getDeclaredMethod("refreshPage");
                refresh.setAccessible(true);
                refresh.invoke(shell);
                BorderPane layout = (BorderPane) shell.getChildren().get(0);
                assertNotNull(layout.getRight());
                java.lang.reflect.Field hoverField = OchreShell.class.getDeclaredField("hoverDelay");
                hoverField.setAccessible(true);
                assertEquals(1000.0, ((javafx.animation.PauseTransition) hoverField.get(shell)).getDuration().toMillis());
                java.lang.reflect.Method expand = OchreShell.class.getDeclaredMethod("expand", boolean.class);
                expand.setAccessible(true);
                Region rail = (Region) shell.lookup(".ochre-rail");
                double fixedRailWidth = rail.getPrefWidth();
                double reservedBeforeHover = ((Region) layout.getLeft()).getPrefWidth();
                expand.invoke(shell, true);
                assertEquals(fixedRailWidth, rail.getPrefWidth());
                assertEquals(reservedBeforeHover, ((Region) layout.getLeft()).getPrefWidth());
                expand.invoke(shell, false);
                double before = ((Region) layout.getLeft()).getPrefWidth();
                ButtonBase pin = (ButtonBase) shell.lookup(".ochre-rail").lookup(".jfx-button");
                pin.fire();
                double after = ((Region) layout.getLeft()).getPrefWidth();
                assertNotEquals(before, after);
                assertEquals(60.0, Math.min(before, after));
                assertEquals(174.0, Math.max(before, after));
                decorator.getNavigator().navigate(new Region(), ContainerAnimations.NONE);
                assertNull(layout.getRight());
                assertTrue(shell.lookup(".ochre-launch-circle").isVisible());
                ButtonBase floating = (ButtonBase) shell.lookup(".ochre-launch-circle");
                java.util.concurrent.atomic.AtomicInteger launches = new java.util.concurrent.atomic.AtomicInteger();
                floating.setOnAction(event -> launches.incrementAndGet());
                var positionField = OchreShell.class.getDeclaredField("floatingPosition"); positionField.setAccessible(true); positionField.set(shell, new FloatingButtonPosition(1, 1));
                var place = OchreShell.class.getDeclaredMethod("placeFloatingButton"); place.setAccessible(true); place.invoke(shell);
                javafx.geometry.Point2D press = floating.localToScene(28, 28);
                double oldX = floating.getLayoutX();
                double oldY = floating.getLayoutY();
                floating.fireEvent(mouse(javafx.scene.input.MouseEvent.MOUSE_PRESSED, press, true));
                javafx.geometry.Point2D moved = press.add(-90, -80);
                floating.fireEvent(mouse(javafx.scene.input.MouseEvent.MOUSE_DRAGGED, moved, true));
                floating.fireEvent(mouse(javafx.scene.input.MouseEvent.MOUSE_RELEASED, moved, false));
                assertEquals(0, launches.get(), "Dragging must not start a game");
                assertTrue(floating.getLayoutX() < oldX || floating.getLayoutY() < oldY);
                root.resize(720, 460);
                root.layout();
                assertTrue(floating.getLayoutX() >= 0);
                assertTrue(floating.getLayoutX() + 56 <= shell.getWidth());
                assertTrue(floating.getLayoutY() + 56 <= shell.getHeight());
                decorator.getNavigator().clear();
                assertNotNull(layout.getRight());
                assertFalse(shell.lookup(".ochre-launch-circle").isVisible());
                java.lang.reflect.Method reveal = OchreShell.class.getDeclaredMethod("revealCompact", boolean.class, boolean.class);
                reveal.setAccessible(true);
                reveal.invoke(shell, true, true);
                assertEquals(0.0, floating.getOpacity());
                assertEquals(.65, floating.getScaleX());
                java.lang.reflect.Field motionField = OchreShell.class.getDeclaredField("buttonMotion");
                motionField.setAccessible(true);
                javafx.animation.Timeline motion = (javafx.animation.Timeline) motionField.get(shell);
                motion.pause();
                motion.jumpTo(javafx.util.Duration.millis(200));
                assertEquals(1.0, floating.getOpacity(), .001);
                assertEquals(1.0, floating.getScaleX(), .001);
                motion.stop();
                pin.fire();
                org.jackhuang.hmcl.ui.construct.OchreLaunchPane logs = new org.jackhuang.hmcl.ui.construct.OchreLaunchPane();
                javafx.scene.layout.StackPane logRoot = new javafx.scene.layout.StackPane(logs);
                Scene logScene = new Scene(logRoot);
                logs.appendGameLog("[INFO] Real process output");
                java.lang.reflect.Field pumpField = logs.getClass().getDeclaredField("pump");
                pumpField.setAccessible(true);
                ((javafx.animation.AnimationTimer) pumpField.get(logs)).handle(0);
                java.lang.reflect.Field outputField = logs.getClass().getDeclaredField("output");
                outputField.setAccessible(true);
                javafx.scene.control.TextArea output = (javafx.scene.control.TextArea) outputField.get(logs);
                assertTrue(output.getText().contains("Real process output"));
                for (int i = 0; i < 1500; i++) logs.appendGameLog("line " + i);
                java.lang.reflect.Field queueField = logs.getClass().getDeclaredField("pending");
                queueField.setAccessible(true);
                assertEquals(1000, ((java.util.Queue<?>) queueField.get(logs)).size());
                logScene.setRoot(new javafx.scene.layout.StackPane());
                assertEquals(0, ((java.util.Queue<?>) queueField.get(logs)).size());
                verifyFloatingTaskMenu(shell);
                verifyNotificationMotion(shell);
                verifyServerAndCrashPanels();
                verifyCoreCardSizing();
                verifySmallWindowFrame();
                verifyAddonFilters();
                verifyAddonTransitionCancellation();
                verifyBeta02Layouts();
                verifyServerSizeDoesNotBecomeStartupPreference();
                verifyImmersiveAppearance(decorator);
                verifyNewWorkspaces();
                result.complete(null);
            } catch (Throwable error) {
                result.completeExceptionally(error);
            }
        });
        result.get(30, TimeUnit.SECONDS);
    }
    /// Rapid forward/back navigation settles on the newest page when its window closes mid-transition.
    private static void verifyAddonTransitionCancellation() throws Exception {
        org.jackhuang.hmcl.server.ServersPage.SELECTED.set(null);
        var page = new org.jackhuang.hmcl.server.ServerAddonsPage();
        var stage = new javafx.stage.Stage();
        var scene = new Scene(page, 800, 600); org.jackhuang.hmcl.setting.StyleSheets.init(scene);
        stage.setScene(scene);
        try {
            stage.show(); page.applyCss(); page.layout();
            var swap = page.getClass().getDeclaredMethod("showBody", javafx.scene.layout.VBox.class, boolean.class); swap.setAccessible(true);
            var first = new javafx.scene.layout.VBox(new javafx.scene.control.Label("List"));
            var detail = new javafx.scene.layout.VBox(new javafx.scene.control.Label("Detail"));
            var newest = new javafx.scene.layout.VBox(new javafx.scene.control.Label("New results"));
            swap.invoke(page, first, true); swap.invoke(page, detail, true); swap.invoke(page, newest, false);
            var loading = (org.jackhuang.hmcl.ui.construct.SpinnerPane) page.getCenter();
            var pane = (org.jackhuang.hmcl.ui.animation.TransitionPane) loading.getContent();
            var current = (javafx.scene.control.ScrollPane) pane.getCurrentNode();
            assertSame(newest, current.getContent());
            stage.hide();
            // Visibility cancellation runs on the animation timer's next pulse.
            var active = org.jackhuang.hmcl.ui.animation.TransitionPane.class.getDeclaredField("activeTransition"); active.setAccessible(true);
            if (active.get(pane) instanceof javafx.animation.AnimationTimer timer) timer.handle(0);
            assertEquals(1, pane.getChildren().size(), "Interrupted transitions release the outgoing page");
            assertFalse(pane.isMouseTransparent(), "Animation cancellation must restore input");
            assertEquals(0, current.getTranslateX(), .01);
            assertEquals(1, current.getOpacity(), .01);
        } finally { stage.close(); scene.setRoot(new javafx.scene.layout.StackPane()); }
    }
    /// Server selection, query and facets remain usable through narrow/wide layout transitions.
    private static void verifyAddonFilters() throws Exception {
        org.jackhuang.hmcl.server.ServersPage.SELECTED.set(null);
        var page = new org.jackhuang.hmcl.server.ServerAddonsPage();
        var directory = java.nio.file.Files.createTempDirectory("ochre-addon-layout-");
        java.nio.file.Files.writeString(directory.resolve("ochre-server.properties"), "core=vanilla\nversion=1.21.8\nname=test\n");
        var fixture = new org.jackhuang.hmcl.server.ServerInstance(directory);
        var targetField = page.getClass().getDeclaredField("target"); targetField.setAccessible(true);
        @SuppressWarnings("unchecked")
        var target = (javafx.scene.control.ComboBox<org.jackhuang.hmcl.server.ServerInstance>) targetField.get(page);
        target.setValue(fixture);
        Scene scene = new Scene(page, 1000, 650);
        org.jackhuang.hmcl.setting.StyleSheets.init(scene);
        for (double width : new double[]{1000, 648, 647, 560, 360, 1000, 360}) {
            page.resize(width, 650); page.applyCss(); page.layout(); page.layout();
            var filters = (javafx.scene.layout.GridPane) page.lookup(".ochre-addon-filters");
            assertNotNull(filters);
            assertEquals(filters.getWidth() >= 850 ? 4 : filters.getWidth() >= 480 ? 2 : 1, filters.getColumnConstraints().size());
            for (var node : filters.getChildren()) {
                var bounds = node.localToParent(node.getLayoutBounds());
                assertTrue(bounds.getMaxX() <= filters.getWidth() + .5, "Filter cannot overflow its card");
                if (node instanceof javafx.scene.control.Label label)
                    assertTrue(label.getWidth() >= label.prefWidth(-1) - .5, "Filter labels cannot become ellipses");
                else if (node instanceof Region control) {
                    assertTrue(control.getWidth() >= 145, "Query and dropdowns must retain usable width");
                    assertEquals(34, control.getHeight(), .5, "Filter controls share one height");
                    if (control instanceof javafx.scene.control.ComboBox<?> combo) {
                        var cell = combo.lookup(".list-cell");
                        assertNotNull(cell);
                        var text = cell.lookup(".text");
                        assertNotNull(text);
                        var glyphs = text.localToScene(text.getBoundsInLocal());
                        var area = cell.localToScene(cell.getLayoutBounds());
                        assertTrue(glyphs.getMinY() >= area.getMinY() - .5, "Selected text cannot be clipped at its top");
                        assertTrue(glyphs.getMaxY() <= area.getMaxY() + .5, "Selected text cannot be clipped at its bottom");
                        var outer = combo.localToScene(combo.getLayoutBounds());
                        assertTrue(glyphs.getMinY() >= outer.getMinY() && glyphs.getMaxY() <= outer.getMaxY(), "Selected text stays inside the whole control");
                    }
                }
            }
        }
        scene.setRoot(new javafx.scene.layout.StackPane());
    }
    /// Content minimums cannot push the title bar or fixed launch footer outside a small scene.
    private static void verifySmallWindowFrame() {
        Decorator decorator = new Decorator(new RootPage(), stage -> {});
        Region root = (Region) decorator.getRoot();
        Scene scene = new Scene(root, 720, 460);
        org.jackhuang.hmcl.setting.StyleSheets.init(scene);
        for (double height : new double[]{700, 460, 400, 700, 460}) {
            root.resize(720, height); root.applyCss(); root.layout();
            OchreShell shell = (OchreShell) root.lookup(".ochre-shell");
            BorderPane layout = (BorderPane) shell.getChildren().get(0);
            Region inspector = (Region) layout.getRight();
            assertNotNull(inspector);
            var shellBounds = shell.localToScene(shell.getLayoutBounds());
            assertTrue(shellBounds.getMinY() >= 0, "Content cannot displace the title bar above the scene");
            assertTrue(shellBounds.getMaxY() <= height + .5, "Content must fit the actual scene height");
            Region launch = (Region) inspector.lookup(".ochre-launch-wide");
            assertNotNull(launch);
            var launchBounds = launch.localToScene(launch.getLayoutBounds());
            assertTrue(launchBounds.getMaxY() <= height + .5, "Launch footer stays inside the small window");
            var title = root.lookup(".jfx-tool-bar");
            assertNotNull(title);
            assertTrue(title.localToScene(title.getLayoutBounds()).getMinY() >= 0, "Window controls remain visible");
        }
        scene.setRoot(new javafx.scene.layout.StackPane());
    }
    /// Verifies icon-bearing entrance/exit motion and that an older callback cannot hide a replacement.
    private static void verifyNotificationMotion(OchreShell shell) throws Exception {
        var kind = org.jackhuang.hmcl.server.ServerManager.NoticeKind.class;
        var show = OchreShell.class.getDeclaredMethod("showNotification", String.class, kind, boolean.class); show.setAccessible(true);
        var hide = OchreShell.class.getDeclaredMethod("hideNotification", long.class, boolean.class); hide.setAccessible(true);
        var nodeField = OchreShell.class.getDeclaredField("notification"); nodeField.setAccessible(true);
        var motionField = OchreShell.class.getDeclaredField("notificationMotion"); motionField.setAccessible(true);
        var generationField = OchreShell.class.getDeclaredField("notificationGeneration"); generationField.setAccessible(true);
        var label = (javafx.scene.control.Label) nodeField.get(shell);
        var motion = (javafx.animation.Timeline) motionField.get(shell);
        show.invoke(shell, "启动失败", org.jackhuang.hmcl.server.ServerManager.NoticeKind.ERROR, true);
        assertNotNull(label.getGraphic()); assertEquals(0, label.getOpacity(), .001); assertEquals(16, label.getTranslateX(), .001);
        motion.pause(); motion.jumpTo(javafx.util.Duration.millis(180));
        assertEquals(1, label.getOpacity(), .001); assertEquals(0, label.getTranslateX(), .001);
        long previous = generationField.getLong(shell); hide.invoke(shell, previous, true);
        var obsoleteExit = motion.getOnFinished();
        show.invoke(shell, "请先停止服务器", org.jackhuang.hmcl.server.ServerManager.NoticeKind.WARNING, true);
        obsoleteExit.handle(new javafx.event.ActionEvent());
        assertTrue(label.isVisible()); assertEquals("请先停止服务器", label.getText());
        hide.invoke(shell, generationField.getLong(shell), true);
        motion.pause(); motion.jumpTo(javafx.util.Duration.millis(140));
        assertEquals(0, label.getOpacity(), .001);
        motion.getOnFinished().handle(new javafx.event.ActionEvent()); assertFalse(label.isVisible()); motion.stop();
        var delay = OchreShell.class.getDeclaredField("notificationDelay"); delay.setAccessible(true); ((javafx.animation.PauseTransition) delay.get(shell)).stop();
    }

    /// Asserts new workspaces and crash UI are scene content, never extra native windows.
    private static void verifyServerAndCrashPanels() throws Exception {
        int windows = javafx.stage.Window.getWindows().size();
        var directory = java.nio.file.Files.createTempDirectory("ochre-ui-server-");
        var properties = new java.util.Properties();
        properties.setProperty("core", "Forge"); properties.setProperty("version", "1.21.8");
        try (var file = java.nio.file.Files.newOutputStream(directory.resolve("ochre-server.properties"))) { properties.store(file, "test fixture"); }
        var server = new org.jackhuang.hmcl.server.ServerInstance(directory);
        org.jackhuang.hmcl.server.ServerManager.SERVERS.add(server);
        org.jackhuang.hmcl.server.ServersPage.SELECTED.set(server);
        var page = org.jackhuang.hmcl.server.ServersPage.getInstance();
        var serverScene = new Scene(page, 900, 600); org.jackhuang.hmcl.setting.StyleSheets.init(serverScene); page.applyCss(); page.resize(900, 600); page.layout();
        assertNotNull(page.lookup(".ochre-runtime-console"));
        assertEquals(4, page.lookupAll(".ochre-metric-card").size());
        var address = page.lookupAll(".text-field").stream().filter(node -> "当前服务器连接地址".equals(node.getAccessibleText())).findFirst().orElseThrow();
        assertFalse(((javafx.scene.control.TextField) address).isEditable());
        assertTrue(((javafx.scene.layout.HBox) address.getParent()).getChildren().stream()
                .anyMatch(node -> "复制当前所选地址".equals(node.getAccessibleText())));
        assertNotNull(page.lookup(".ochre-server-controls"));
        var powerPanel = page.lookup(".ochre-server-power");
        var functionScroll = (javafx.scene.control.ScrollPane) page.lookup(".ochre-server-function-scroll");
        assertNotNull(powerPanel); assertNotNull(functionScroll);
        for (javafx.scene.Node ancestor = powerPanel.getParent(); ancestor != null; ancestor = ancestor.getParent()) assertNotSame(functionScroll, ancestor);
        var powerPosition = powerPanel.localToScene(0, 0); functionScroll.setVvalue(1); page.layout();
        assertEquals(powerPosition, powerPanel.localToScene(0, 0), "Function scrolling must not move power controls");

        var artifact = new org.jackhuang.hmcl.server.ServerCatalog.Artifact("Fixture", "https://example.com/server.jar", "", "", "Paper", "1.21.1", 21);
        var deployment = new org.jackhuang.hmcl.server.ServerDeploymentPage("Paper", "1.21.1", () -> {}, () -> java.util.List.of(artifact));
        var deploymentScene = new Scene(deployment, 800, 500); org.jackhuang.hmcl.setting.StyleSheets.init(deploymentScene); deployment.applyCss(); deployment.resize(800, 500); deployment.layout();
        var footer = deployment.lookup(".ochre-deployment-footer");
        var form = (javafx.scene.control.ScrollPane) deployment.lookup(".ochre-deployment-form");
        assertNotNull(footer); assertNotNull(form);
        for (javafx.scene.Node ancestor = footer.getParent(); ancestor != null; ancestor = ancestor.getParent()) assertNotSame(form, ancestor);
        var footerPosition = footer.localToScene(0, 0); form.setVvalue(1); deployment.layout();
        assertEquals(footerPosition, footer.localToScene(0, 0), "Deployment actions must stay fixed while settings scroll");
        assertTrue(footer.lookupAll(".button").stream().anyMatch(node -> node instanceof ButtonBase button && button.getText().contains("部署")));

        assertFalse(javafx.stage.Stage.class.isAssignableFrom(org.jackhuang.hmcl.ui.GameCrashWindow.class));
        var gameDirectory = new org.jackhuang.hmcl.setting.GameDirectory(org.jackhuang.hmcl.setting.GameDirectoryID.generate(),
                org.jackhuang.hmcl.util.i18n.LocalizedText.plain("Crash test"), org.jackhuang.hmcl.util.PortablePath.of(directory.resolve("game").toString()));
        var repository = new org.jackhuang.hmcl.game.HMCLGameRepository(gameDirectory);
        var id = new org.jackhuang.hmcl.game.GameInstanceID("ochre-crash-test");
        try (var draft = repository.openDraft()) { draft.put(new org.jackhuang.hmcl.game.GameInstanceManifest(id)); draft.commit(); }
        var instance = repository.getInstance(id);
        var options = new org.jackhuang.hmcl.game.LaunchOptions.Builder().setInstanceId(id).setGameDir(directory)
                .setJava(org.jackhuang.hmcl.java.JavaRuntime.getDefault()).create();
        var process = new ProcessBuilder(org.jackhuang.hmcl.java.JavaRuntime.getDefault().getBinary().toString(), "-version").start();
        process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        var managed = new org.jackhuang.hmcl.util.platform.ManagedProcess(process, java.util.List.of("java", "-version"));
        var crash = new org.jackhuang.hmcl.ui.GameCrashWindow(managed, org.jackhuang.hmcl.launch.ProcessListener.ExitType.APPLICATION_ERROR,
                instance, options, java.util.List.of(new org.jackhuang.hmcl.game.Log("Test exception", org.jackhuang.hmcl.util.Log4jLevel.ERROR)));
        var crashScene = new Scene(crash, 700, 420); org.jackhuang.hmcl.setting.StyleSheets.init(crashScene); crash.applyCss(); crash.resize(700, 420); crash.layout();
        assertNotNull(crash.lookup(".ochre-section-tabs"));
        assertEquals(windows, javafx.stage.Window.getWindows().size());
        assertFalse(server.acceptedEula());
    }

    /// Repeated narrow/wide layout must keep core rows compact and their text beside the artwork.
    private static void verifyCoreCardSizing() throws Exception {
        var page = new org.jackhuang.hmcl.server.ServerCoreSearch(() -> {}, next -> {});
        var factory = page.getClass().getDeclaredMethod("card", org.jackhuang.hmcl.server.ServerCoreCatalog.Core.class, String.class, String.class, String.class, Runnable.class); factory.setAccessible(true);
        var core = new org.jackhuang.hmcl.server.ServerCoreCatalog.Core("arclight-forge", "Arclight · Forge", "", "", "MSL", "");
        var activations = new java.util.concurrent.atomic.AtomicInteger();
        var row = (javafx.scene.layout.HBox) factory.invoke(page, core, core.title(), "插件与模组混合核心 · MSL 服务端镜像", "MSL · 服务端镜像", (Runnable) activations::incrementAndGet);
        var content = new javafx.scene.layout.VBox(row); var scroll = new javafx.scene.control.ScrollPane(content); scroll.setFitToWidth(true);
        var scene = new Scene(scroll, 400, 600); org.jackhuang.hmcl.setting.StyleSheets.init(scene); scroll.applyCss();
        for (double width : new double[]{400, 1000, 360, 900, 400}) {
            scroll.resize(width, 600); scroll.layout(); content.layout(); row.layout();
            assertTrue(row.getHeight() <= 112, "Core cards must not grow to fill a viewport");
            assertTrue(row.prefHeight(width) <= 112);
            var artwork = row.getChildren().get(1); var text = row.getChildren().get(0);
            assertTrue(artwork.getBoundsInParent().getMinX() > text.getBoundsInParent().getMaxX());
            assertTrue(Math.abs(text.getBoundsInParent().getCenterY() - artwork.getBoundsInParent().getCenterY()) < 3, "Artwork and text stay on one horizontal row");
        }
        assertNotNull(row.lookup(".ochre-core-server-badge"));
        for (String name : java.util.List.of("原版", "Paper", "Fabric", "Forge", "Arclight · Forge")) {
            var icon = new org.jackhuang.hmcl.server.ServerCoreArtwork(name, "");
            assertEquals(56, icon.prefWidth(-1));
            assertNotNull(icon.lookup(".ochre-core-server-badge"));
            assertEquals(2, icon.getChildren().size(), "Local core logo and independent server badge");
        }
        row.executeAccessibleAction(javafx.scene.AccessibleAction.FIRE);
        assertEquals(1, activations.get());
    }

    /// Checks real welcome/task layouts and captures independent UI fixtures for visual review.
    private static void verifyBeta02Layouts() throws Exception {
        var welcome = new org.jackhuang.hmcl.ui.construct.OchreWelcomePane();
        var root = new javafx.scene.layout.StackPane(welcome); var scene = new Scene(root, 760, 560); org.jackhuang.hmcl.setting.StyleSheets.init(scene); root.applyCss(); root.resize(760, 560); root.layout();
        assertTrue(welcome.getBoundsInParent().getWidth() <= 760); assertTrue(welcome.getBoundsInParent().getHeight() <= 560);
        var stripDirectory = java.nio.file.Files.createTempDirectory("ochre-grid-"); java.nio.file.Files.writeString(stripDirectory.resolve("ochre-server.properties"), "name=Test\ncore=Paper\nversion=1.21.1\n");
        var server = new org.jackhuang.hmcl.server.ServerInstance(stripDirectory);
        var strip = new org.jackhuang.hmcl.server.ServerStatusStrip(server);
        for (double width : new double[]{500, 1000, 600}) {
            strip.resize(width, 180); strip.layout(); int expected = width >= 840 ? 4 : 2; assertEquals(expected, strip.getColumnConstraints().size());
            for (int i = 0; i < 4; i++) { assertEquals(i % expected, javafx.scene.layout.GridPane.getColumnIndex(strip.getChildren().get(i))); assertEquals(i / expected, javafx.scene.layout.GridPane.getRowIndex(strip.getChildren().get(i))); }
        }
        var client = new org.jackhuang.hmcl.ui.construct.TaskExecutorDialogPane(org.jackhuang.hmcl.util.TaskCancellationAction.NO_CANCEL);
        client.setTitle("安装 Minecraft · 界面测试"); root.getChildren().setAll(client); root.applyCss(); root.layout();
        assertTrue(client.lookupAll(".button").stream().anyMatch(node -> node instanceof ButtonBase button && button.getText().equals("静默安装")));
        var output = java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), "ochre-beta02-install.png");
        var picture = root.snapshot(null, null); var reader = picture.getPixelReader(); var bitmap = new java.awt.image.BufferedImage((int) picture.getWidth(), (int) picture.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) bitmap.setRGB(x, y, reader.getArgb(x, y)); javax.imageio.ImageIO.write(bitmap, "png", output.toFile());
        scene.setRoot(new javafx.scene.layout.StackPane());
    }

    /// Closing during an enlarged server workspace retains the normal startup size across stage replacement.
    private static void verifyServerSizeDoesNotBecomeStartupPreference() throws Exception {
        var settings = org.jackhuang.hmcl.setting.SettingsManager.state(); double savedWidth = settings.getWidth(), savedHeight = settings.getHeight();
        var page = new javafx.scene.layout.VBox(); org.jackhuang.hmcl.server.ServerWindowSizing.install(page);
        var stage = new javafx.stage.Stage(); stage.setScene(new Scene(page, 720, 480)); stage.show();
        try {
            settings.setWidth(720); settings.setHeight(480);
            var sizing = page.getProperties().get(org.jackhuang.hmcl.server.ServerWindowSizing.class); var grow = sizing.getClass().getDeclaredMethod("grow"); grow.setAccessible(true); grow.invoke(sizing);
            assertTrue(org.jackhuang.hmcl.server.ServerWindowSizing.hasTemporaryBounds(stage));
            assertTrue(stage.getWidth() > 720);
            org.jackhuang.hmcl.server.ServerWindowSizing.restoreSavedPreference(stage);
            assertEquals(720, settings.getWidth()); assertEquals(480, settings.getHeight());
            var replacement = new javafx.stage.Stage(); org.jackhuang.hmcl.server.ServerWindowSizing.transfer(stage, replacement);
            assertTrue(org.jackhuang.hmcl.server.ServerWindowSizing.hasTemporaryBounds(replacement));
            settings.setWidth(1300); settings.setHeight(900); org.jackhuang.hmcl.server.ServerWindowSizing.restoreSavedPreference(replacement);
            assertEquals(720, settings.getWidth()); assertEquals(480, settings.getHeight()); replacement.close();
        } finally { stage.close(); stage.getScene().setRoot(new javafx.scene.layout.StackPane()); settings.setWidth(savedWidth); settings.setHeight(savedHeight); }
    }

    /// Sends the real context-menu event after drag setup and verifies live rows, actual morph geometry and jump actions.
    private static void verifyFloatingTaskMenu(OchreShell shell) throws Exception {
        var homeField = OchreShell.class.getDeclaredField("showingHome"); homeField.setAccessible(true); boolean previousHome = homeField.getBoolean(shell); homeField.setBoolean(shell, true);
        var launching = org.jackhuang.hmcl.ui.construct.OchreTasks.add(true, () -> "启动测试", () -> {});
        var button = (ButtonBase) shell.lookup(".ochre-launch-circle");
        assertTrue(button.getGraphic() instanceof LaunchOrbit, "Launch tasks need an animated readiness indicator, not a download arrow");
        assertTrue(button.isVisible()); assertEquals(1, button.getOpacity(), .001);
        org.jackhuang.hmcl.ui.construct.OchreTasks.ACTIVE.remove(launching); homeField.setBoolean(shell, previousHome);
        var activated = new java.util.concurrent.atomic.AtomicInteger();
        var entry = org.jackhuang.hmcl.ui.construct.OchreTasks.add(() -> "测试安装任务", activated::incrementAndGet);
        var anchor = (ButtonBase) shell.lookup(".ochre-launch-circle");
        try {
            anchor.fireEvent(new javafx.scene.input.ContextMenuEvent(javafx.scene.input.ContextMenuEvent.CONTEXT_MENU_REQUESTED, 5, 5, 5, 5, false, null));
            var menu = (FloatingTaskSurface) shell.lookup(".ochre-task-menu"); assertNotNull(menu, "Drag initialization must not replace the task-menu handler");
            var extentField = FloatingTaskSurface.class.getDeclaredField("extent"); extentField.setAccessible(true); var extent = (javafx.beans.property.DoubleProperty) extentField.get(menu);
            var motionField = FloatingTaskSurface.class.getDeclaredField("motion"); motionField.setAccessible(true); var motion = (javafx.animation.Timeline) motionField.get(menu); motion.stop();
            extent.set(0); assertEquals(56, menu.getWidth(), .01); assertEquals(56, menu.getHeight(), .01);
            extent.set(.5); double halfWidth = menu.getWidth(); assertTrue(halfWidth > 56); assertEquals(1, menu.getScaleX(), .01, "Morph changes geometry instead of scaling a rectangular popup");
            extent.set(1); menu.applyCss(); menu.layout(); assertTrue(menu.getWidth() > halfWidth); assertTrue(menu.getLayoutX() >= 0); assertTrue(menu.getLayoutX() + menu.getWidth() <= shell.getWidth());
            assertEquals(0, anchor.getOpacity(), .01);
            var second = org.jackhuang.hmcl.ui.construct.OchreTasks.add(() -> "第二个实时任务", () -> {});
            assertEquals(2, menu.lookupAll(".ochre-task-row").size()); menu.applyCss(); menu.layout(); saveSnapshot(menu, "ochre-beta02-task-menu.png"); org.jackhuang.hmcl.ui.construct.OchreTasks.ACTIVE.remove(second); assertEquals(1, menu.lookupAll(".ochre-task-row").size());
            var shortcut = (ButtonBase) menu.lookup(".ochre-task-row"); shortcut.fire(); motion.jumpTo(javafx.util.Duration.millis(190)); motion.stop(); motion.getOnFinished().handle(new javafx.event.ActionEvent());
            assertEquals(1, activated.get()); assertNull(shell.lookup(".ochre-task-menu")); assertEquals(1, anchor.getOpacity(), .01); assertFalse(anchor.isMouseTransparent());
        } finally { org.jackhuang.hmcl.ui.construct.OchreTasks.ACTIVE.remove(entry); }
    }

    /// Verifies image-derived color, glass-only blur, normal-mode restoration and the first-run mode chooser.
    private static void verifyImmersiveAppearance(Decorator decorator) throws Exception {
        var settings = org.jackhuang.hmcl.setting.SettingsManager.settings(); var overrides = new java.util.HashSet<>(settings.getThemeAppearanceOverrides());
        String oldMode = settings.themeBrightnessModeProperty().get(), oldPath = settings.customBackgroundImagePathProperty().get(); var oldType = settings.backgroundTypeProperty().get(); var oldSeed = settings.customThemeColorProperty().get(); var oldColorType = settings.themeColorTypeProperty().get();
        var colorField = org.jackhuang.hmcl.theme.Themes.class.getDeclaredField("wallpaperThemeColor"); colorField.setAccessible(true);
        @SuppressWarnings("unchecked") var loadedColor = (javafx.beans.property.ReadOnlyObjectWrapper<org.jackhuang.hmcl.theme.ThemeColor>) colorField.get(null);
        @org.jetbrains.annotations.Nullable var previousColor = loadedColor.get();
        var stage = new javafx.stage.Stage();
        try {
            var image = new javafx.scene.image.WritableImage(320, 200);
            for (int y = 0; y < 200; y++) for (int x = 0; x < 320; x++) image.getPixelWriter().setColor(x, y, (x / 25 + y / 25) % 2 == 0 ? javafx.scene.paint.Color.web("#246B58") : javafx.scene.paint.Color.web("#8ABBA5"));
            var extract = org.jackhuang.hmcl.theme.Themes.class.getDeclaredMethod("extractWallpaperThemeColor", javafx.scene.image.Image.class); extract.setAccessible(true);
            var seed = (org.jackhuang.hmcl.theme.ThemeColor) extract.invoke(null, image); assertNotNull(seed);
            settings.customThemeColorProperty().set(org.jackhuang.hmcl.theme.ThemeColor.of(javafx.scene.paint.Color.RED)); settings.themeColorTypeProperty().set(org.jackhuang.hmcl.setting.ThemeColorType.CUSTOM); settings.getThemeAppearanceOverrides().add(org.jackhuang.hmcl.setting.LauncherSettings.THEME_APPEARANCE_COLOR);
            settings.themeBrightnessModeProperty().set("immersive"); settings.getThemeAppearanceOverrides().add(org.jackhuang.hmcl.setting.LauncherSettings.THEME_APPEARANCE_BRIGHTNESS_MODE); loadedColor.set(seed);
            assertEquals(seed.color(), org.jackhuang.hmcl.theme.Themes.getColorScheme().getPrimaryColorSeed(), "Glass appearance must ignore the manual seed and derive it from the wallpaper");
            var scene = decorator.attachStage(stage); org.jackhuang.hmcl.setting.StyleSheets.init(scene); stage.setWidth(1060); stage.setHeight(760); stage.show();
            var window = decorator.getDialogContainer(); window.applyCss(); window.layout();
            var background = (javafx.scene.layout.Region) window.getChildren().get(0); background.setBackground(new javafx.scene.layout.Background(new javafx.scene.layout.BackgroundImage(image, javafx.scene.layout.BackgroundRepeat.NO_REPEAT, javafx.scene.layout.BackgroundRepeat.NO_REPEAT, javafx.scene.layout.BackgroundPosition.CENTER, new javafx.scene.layout.BackgroundSize(100, 100, true, true, false, true)))); background.setOpacity(1);
            var glass = (GlassBackdrop) window.lookup(".ochre-glass-backdrop"); assertNotNull(glass); assertTrue(glass.isVisible()); assertTrue(glass.getEffect() instanceof javafx.scene.effect.GaussianBlur);
            var mask = GlassBackdrop.class.getDeclaredMethod("updateMask"); mask.setAccessible(true); mask.invoke(glass);
            assertTrue(scene.getStylesheets().contains("/assets/css/ochre-glass.css"));
            var rail = (javafx.scene.layout.Region) window.lookup(".ochre-rail"); assertTrue(((javafx.scene.paint.Color) rail.getBackground().getFills().get(0).getFill()).getOpacity() < 1);
            assertNull(window.lookup(".ochre-brand").getEffect(), "Text must never inherit a blur effect");
            saveSnapshot(window, "ochre-beta02-glass.png");
            var welcome = new org.jackhuang.hmcl.ui.construct.OchreWelcomePane(); var step = welcome.getClass().getDeclaredField("step"); step.setAccessible(true); step.setInt(welcome, 1); var show = welcome.getClass().getDeclaredMethod("show"); show.setAccessible(true); show.invoke(welcome);
            var welcomeScene = new Scene(new javafx.scene.layout.StackPane(welcome), 760, 600); org.jackhuang.hmcl.setting.StyleSheets.init(welcomeScene); welcomeScene.getRoot().applyCss();
            var pagesField = welcome.getClass().getDeclaredField("pages"); pagesField.setAccessible(true); var current = ((org.jackhuang.hmcl.ui.animation.TransitionPane) pagesField.get(welcome)).getCurrentNode();
            if (current instanceof javafx.scene.control.ScrollPane viewport) current = viewport.getContent();
            var chooser = (javafx.scene.control.ComboBox<?>) current.lookup("#ochre-welcome-appearance"); assertNotNull(chooser); assertTrue(chooser.getItems().contains("immersive")); assertTrue(current.lookup("#ochre-welcome-colors").isDisabled());
            settings.themeBrightnessModeProperty().set("light"); window.applyCss(); assertFalse(glass.isVisible()); assertFalse(scene.getStylesheets().contains("/assets/css/ochre-glass.css"));
            var cache = org.jackhuang.hmcl.Metadata.HMCL_LOCAL_HOME.resolve("bing-wallpaper"); java.nio.file.Files.createDirectories(cache);
            var bitmap = new java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB); javax.imageio.ImageIO.write(bitmap, "png", cache.resolve("daily.jpg").toFile()); var properties = new java.util.Properties(); properties.setProperty("checked", java.time.LocalDate.now().toString()); properties.setProperty("copyright", "Fixture credit"); try (var output = java.nio.file.Files.newOutputStream(cache.resolve("metadata.properties"))) { properties.store(output, "offline fixture"); }
            assertEquals(cache.resolve("daily.jpg"), org.jackhuang.hmcl.theme.BingWallpaper.refresh(), "A current daily cache must be usable without making a network request");
        } finally {
            decorator.detachStage(); stage.close(); settings.themeBrightnessModeProperty().set(oldMode); settings.customBackgroundImagePathProperty().set(oldPath); settings.backgroundTypeProperty().set(oldType); settings.customThemeColorProperty().set(oldSeed); settings.themeColorTypeProperty().set(oldColorType); loadedColor.set(previousColor); settings.getThemeAppearanceOverrides().clear(); settings.getThemeAppearanceOverrides().addAll(overrides);
        }
    }
    /// Renders the new settings and onboarding layouts with real CSS at normal and narrow dimensions.
    private static void verifyNewWorkspaces() throws Exception {
        var animation = org.jackhuang.hmcl.setting.SettingsManager.settings().animationDisabledProperty(); @org.jetbrains.annotations.Nullable Boolean priorAnimation = animation.get(); animation.set(true);
        var stage = new javafx.stage.Stage(); var host = new javafx.scene.layout.StackPane(); host.setStyle("-fx-background-color: -ochre-panel;"); var scene = new Scene(host, 800, 580); org.jackhuang.hmcl.setting.StyleSheets.init(scene); stage.setScene(scene); stage.show();
        try {
            for (int i = 0; i < 4; i++) {
                var welcome = new org.jackhuang.hmcl.ui.construct.OchreWelcomePane();
                var step = welcome.getClass().getDeclaredField("step"); step.setAccessible(true); var show = welcome.getClass().getDeclaredMethod("show"); show.setAccessible(true);
                step.setInt(welcome, i); show.invoke(welcome); host.getChildren().setAll(welcome); host.applyCss(); host.layout();
                if (i == 2) assertNotNull(welcome.lookup("#ochre-import-add"), "The import list must expose a real plus button");
                saveSnapshot(host, "ochre-beta03-welcome-" + i + ".png"); assertNotNull(welcome.getBottom());
            }
            var directory = java.nio.file.Files.createTempDirectory("ochre-new-workspaces-"); java.nio.file.Files.writeString(directory.resolve("ochre-server.properties"), "core=vanilla\nname=Test\nport=25565\n"); var server = new org.jackhuang.hmcl.server.ServerInstance(directory);
            var workspace = new org.jackhuang.hmcl.server.ServerWorkspacePage(server, "实例设置"); host.getChildren().setAll(workspace); host.applyCss(); host.layout(); saveSnapshot(host, "ochre-beta03-instance.png");
            var picker = new org.jackhuang.hmcl.server.ServerAddressPicker(server); host.getChildren().setAll(picker); host.applyCss(); host.layout(); assertTrue(picker.getChildren().size() >= 3);
            var constructor = org.jackhuang.hmcl.server.ServerNetworkPane.class.getDeclaredConstructor(org.jackhuang.hmcl.server.ServerInstance.class, boolean.class); constructor.setAccessible(true); var network = constructor.newInstance(server, false); host.getChildren().setAll(network); host.applyCss(); host.layout(); saveSnapshot(host, "ochre-beta03-network.png");
            var options = new org.jackhuang.hmcl.ui.construct.GameOptionsPane(directory.toString()); host.getChildren().setAll(options); host.applyCss(); host.layout(); saveSnapshot(host, "ochre-beta03-options.png");
            var completed = new java.util.concurrent.CountDownLatch(1);
            var taskConstructor = org.jackhuang.hmcl.server.ServerTaskDialog.class.getDeclaredConstructor(String.class, org.jackhuang.hmcl.server.ServerTaskDialog.Work.class, java.util.function.Consumer.class, Runnable.class); taskConstructor.setAccessible(true);
            var deployment = (javafx.scene.layout.VBox) taskConstructor.newInstance("部署 测试核心", (org.jackhuang.hmcl.server.ServerTaskDialog.Work<String>) progress -> { completed.await(10, java.util.concurrent.TimeUnit.SECONDS); return "done"; }, (java.util.function.Consumer<String>) value -> {}, (Runnable) () -> {});
            host.getChildren().setAll(deployment); host.applyCss(); host.layout(); saveSnapshot(host, "ochre-beta03-deployment.png"); completed.countDown();
            var settings = org.jackhuang.hmcl.setting.SettingsManager.settings(); var mode = settings.themeBrightnessModeProperty().get(); var background = settings.backgroundTypeProperty().get(); double opacity = settings.backgroundOpacityProperty().get(); var flags = new java.util.HashSet<>(settings.getThemeAppearanceOverrides());
            try {
                settings.themeBrightnessModeProperty().set("light"); settings.backgroundTypeProperty().set(org.jackhuang.hmcl.setting.BackgroundType.BUILTIN); settings.getThemeAppearanceOverrides().add(org.jackhuang.hmcl.setting.LauncherSettings.THEME_APPEARANCE_BACKGROUND); settings.backgroundOpacityProperty().set(.65);
                var enabled = new java.util.concurrent.atomic.AtomicBoolean(); org.jackhuang.hmcl.theme.ImmersiveAppearance.requestEnable(() -> enabled.set(true), () -> {}); assertTrue(enabled.get(), "Built-in wallpaper must enable glass without requiring a second file chooser");
                settings.themeBrightnessModeProperty().set("dark"); assertEquals(.65, settings.backgroundOpacityProperty().get(), .001, "Original background opacity must survive entering and exiting glass");
                for (var builtin : org.jackhuang.hmcl.theme.BuiltinBackground.values()) { try (var stream = OchreShellTest.class.getResourceAsStream(builtin.resource())) { assertNotNull(stream); var image = new javafx.scene.image.Image(stream); assertFalse(image.isError()); } }
            } finally { settings.themeBrightnessModeProperty().set(mode); settings.backgroundTypeProperty().set(background); settings.backgroundOpacityProperty().set(opacity); settings.getThemeAppearanceOverrides().clear(); settings.getThemeAppearanceOverrides().addAll(flags); }
        } finally { stage.close(); scene.setRoot(new javafx.scene.layout.StackPane()); animation.set(priorAnimation); }
    }
    /// Writes an actual JavaFX rendering for visual review without capturing the user's desktop.
    private static void saveSnapshot(javafx.scene.Node node, String name) throws Exception {
        var image = node.snapshot(null, null); var bitmap = new java.awt.image.BufferedImage((int) image.getWidth(), (int) image.getHeight(), java.awt.image.BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < bitmap.getHeight(); y++) for (int x = 0; x < bitmap.getWidth(); x++) bitmap.setRGB(x, y, image.getPixelReader().getArgb(x, y));
        javax.imageio.ImageIO.write(bitmap, "png", java.nio.file.Path.of(System.getProperty("java.io.tmpdir"), name).toFile());
    }

    /// Creates real primary pointer events to exercise click-versus-drag behavior without a game process.
    private static javafx.scene.input.MouseEvent mouse(javafx.event.EventType<javafx.scene.input.MouseEvent> type,
                                                       javafx.geometry.Point2D point, boolean down) {
        return new javafx.scene.input.MouseEvent(type, point.getX(), point.getY(), point.getX(), point.getY(),
                javafx.scene.input.MouseButton.PRIMARY, 1, false, false, false, false,
                down, false, false, false, false, true, null);
    }
}
