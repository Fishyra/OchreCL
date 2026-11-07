package org.jackhuang.hmcl.ui.decorator;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises real zip panorama lookup and user cover precedence without installed game data.
@NotNullByDefault
public final class InstanceCoverLoaderTest {
    /// Generates differently shaped images so source selection is unambiguous.
    @Test
    public void usesVersionPanoramaUntilCustomCoverIsSet(@TempDir Path directory) throws Exception {
        Path jar = directory.resolve("client.jar");
        try (var output = new ZipOutputStream(Files.newOutputStream(jar))) {
            output.putNextEntry(new ZipEntry("assets/minecraft/textures/gui/title/background/panorama_0.png"));
            ImageIO.write(new BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", output);
        }
        var panorama = InstanceCoverLoader.load(null, jar);
        assertNotNull(panorama);
        assertEquals(1, panorama.getWidth() / panorama.getHeight());
        Path custom = directory.resolve("cover.png");
        ImageIO.write(new BufferedImage(64, 16, BufferedImage.TYPE_INT_RGB), "png", custom.toFile());
        var cover = InstanceCoverLoader.load(custom, jar);
        assertNotNull(cover);
        assertEquals(4, cover.getWidth() / cover.getHeight());
        assertNull(InstanceCoverLoader.load(null, directory.resolve("missing.jar")));
    }
    /// Modern installations must use real asset objects instead of the client's one-pixel jar placeholder.
    @Test public void usesModernAssetObject(@TempDir Path root) throws Exception {
        System.setProperty("hmcl.dir", root.resolve("launcher-local").toString()); System.setProperty("hmcl.home", root.resolve("launcher-home").toString());
        org.jackhuang.hmcl.JavaFXLauncher.start(); var initialized = new java.util.concurrent.CompletableFuture<Void>();
        javafx.application.Platform.runLater(() -> { try { try { org.jackhuang.hmcl.setting.SettingsManager.settings(); } catch (IllegalStateException notLoaded) { org.jackhuang.hmcl.setting.SettingsManager.init(); } initialized.complete(null); } catch (Exception exception) { initialized.completeExceptionally(exception); } }); initialized.get(20, java.util.concurrent.TimeUnit.SECONDS);
        var directory = new org.jackhuang.hmcl.setting.GameDirectory(org.jackhuang.hmcl.setting.GameDirectoryID.generate(), org.jackhuang.hmcl.util.i18n.LocalizedText.plain("Cover fixture"), org.jackhuang.hmcl.util.PortablePath.of(root.toString()));
        var repository = new org.jackhuang.hmcl.game.HMCLGameRepository(directory); var id = new org.jackhuang.hmcl.game.GameInstanceID("cover-fixture");
        try (var draft = repository.openDraft()) { draft.put(new org.jackhuang.hmcl.game.GameInstanceManifest(id).withAssetIndex(new org.jackhuang.hmcl.game.AssetIndexInfo("fixture", "https://invalid.example/fixture.json"))); draft.commit(); }
        var instance = repository.getInstance(id); Path jar = instance.getInstanceJarFile(); Files.createDirectories(jar.getParent());
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) { zip.putNextEntry(new ZipEntry("assets/minecraft/textures/gui/title/background/panorama_0.png")); ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", zip); }
        assertNull(InstanceCoverLoader.load(null, jar));
        String hash = "a".repeat(40); Path image = root.resolve("assets/objects/aa/" + hash); Files.createDirectories(image.getParent()); ImageIO.write(new BufferedImage(128, 128, BufferedImage.TYPE_INT_RGB), "png", image.toFile());
        Path index = root.resolve("assets/indexes/fixture.json"); Files.createDirectories(index.getParent()); Files.writeString(index, "{\"objects\":{\"minecraft/textures/gui/title/background/panorama_0.png\":{\"hash\":\"" + hash + "\",\"size\":100}}}");
        var cover = InstanceCoverLoader.load(null, instance); assertNotNull(cover); assertEquals(128, cover.getWidth());
    }

}
