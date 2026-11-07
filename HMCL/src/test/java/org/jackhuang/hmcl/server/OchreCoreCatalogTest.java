package org.jackhuang.hmcl.server;

import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/// Checks core-only matching, game/loader identity and artifact validation against representative official metadata.
@NotNullByDefault
public final class OchreCoreCatalogTest {
    /// Keywords match actual core names and loader variants rather than unrelated plugin descriptions.
    @Test public void matchesCoreNames() {
        assertTrue(ServerCoreCatalog.matches("arclight-neoforge", "Arclight NeoForge"));
        assertFalse(ServerCoreCatalog.matches("pv-addon-nickname", "arclight"));
        assertEquals(25, ServerCoreCatalog.javaMajor("26.1.2")); assertEquals(21, ServerCoreCatalog.javaMajor("1.21.1"));
        assertEquals(17, ServerCoreCatalog.javaMajor("1.20.1"));
    }
    /// Release nicknames never become Minecraft versions, and source JARs cannot become deployable builds.
    @Test public void preservesExactPublishedCoreAssets() {
        var core = new ServerCoreCatalog.Core("arclight", "Arclight", "", "", "GitHub", "IzzelAliz/Arclight");
        var json = JsonParser.parseString("""
            [{"tag_name":"Dipper/1.1.0-SNAPSHOT","draft":false,"published_at":"2026-11-01T00:00:00Z","assets":[
            {"name":"arclight-neoforge-1.21.1-1.1.0-abc.jar","browser_download_url":"https://example.com/core.jar","digest":"sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"},
            {"name":"arclight-neoforge-1.21.1-sources.jar","browser_download_url":"https://example.com/sources.jar"},
            {"name":"server.zip","browser_download_url":"https://example.com/archive.zip"}]}]
            """).getAsJsonArray();
        var releases = ServerCoreCatalog.parseReleases(core, json);
        assertEquals(1, releases.size()); var artifact = releases.get(0).artifact();
        assertEquals("1.21.1", artifact.version()); assertEquals("Arclight · NeoForge", artifact.core());
        assertEquals("SHA-256", artifact.algorithm()); assertEquals(64, artifact.hash().length());
    }
    /// Exercises live MSL core lookup/version/download metadata without installing or accepting an EULA.
    @Test @org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="OCHRE_NETWORK_TEST", matches="true")
    public void mirrorProvidesRealArclightDownload() throws Exception {
        var cores = ServerCoreCatalog.search("arclight", "MSL");
        assertTrue(cores.stream().allMatch(core -> core.id().startsWith("arclight-"))); assertTrue(cores.size() >= 3);
        var forge = cores.stream().filter(core -> core.id().equals("arclight-forge")).findFirst().orElseThrow();
        assertTrue(ServerCoreCatalog.versions(forge).contains("1.20.1"));
        var file = ServerCoreCatalog.mirrorRelease(forge, "1.20.1").artifact();
        assertTrue(file.url().startsWith("https://")); assertEquals("1.20.1", file.version());
    }
}
