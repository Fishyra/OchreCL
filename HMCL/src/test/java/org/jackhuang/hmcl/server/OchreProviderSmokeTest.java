package org.jackhuang.hmcl.server;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.junit.jupiter.api.Assertions.*;

/// Opt-in live provider compatibility check; not part of ordinary offline test runs.
@NotNullByDefault
@EnabledIfEnvironmentVariable(named = "OCHRE_NETWORK_TEST", matches = "true")
public final class OchreProviderSmokeTest {
    /// Isolates every live deployment from real launcher/game data.
    @org.junit.jupiter.api.BeforeAll public static void isolate() throws Exception {
        System.setProperty("hmcl.dir", java.nio.file.Files.createTempDirectory("ochre-provider-smoke-").toString());
        org.jackhuang.hmcl.JavaFXLauncher.start();
    }
    /// Downloads an actual Paper core with its official checksum and atomically deploys it without EULA acceptance.
    @Test public void coreDeployment() throws Exception {
        var artifact = ServerCatalog.builds("Paper", "1.21.8").get(0);
        ServerInstance server = ServerCatalog.deploy(artifact, "Paper deployment test", System.out::println);
        assertTrue(java.nio.file.Files.isRegularFile(server.directory.resolve("server.jar")));
        assertFalse(server.acceptedEula());
        assertFalse(server.session.isRunning());
        try (var archive = new java.util.jar.JarFile(server.directory.resolve("server.jar").toFile())) { assertNotNull(archive.getManifest()); }
        try (var paths = java.nio.file.Files.walk(server.directory)) {
            for (var path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) java.nio.file.Files.deleteIfExists(path);
        }
    }
    /// Reads actual official provider schemas and compatible Modrinth results without installing or starting code.
    @Test public void currentProvidersExposeConcreteDownloads() throws Exception {
        for (String provider : java.util.List.of("原版", "Paper", "Purpur", "Fabric", "Forge")) {
            var versions = ServerCatalog.versions(provider);
            assertTrue(versions.contains("1.21.8"), provider);
            var builds = ServerCatalog.builds(provider, "1.21.8");
            assertFalse(builds.isEmpty(), provider);
            assertTrue(builds.get(0).url().startsWith("https://"));
            System.out.println(provider + ": " + builds.get(0).label());
        }
    }    /// Exercises live Modrinth filtering and a hash-verified add-on install into a disposable fixture.
    @Test public void modrinthDeployment(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        java.util.Properties properties = new java.util.Properties();
        properties.setProperty("core", "Fabric"); properties.setProperty("version", "1.21.8");
        try (var output = java.nio.file.Files.newOutputStream(directory.resolve("ochre-server.properties"))) { properties.store(output, "test fixture"); }
        ServerInstance server = new ServerInstance(directory);
        var projects = ServerCatalog.search("fabric api", server);
        assertFalse(projects.isEmpty());
        ServerCatalog.installAddon(new ServerCatalog.Project("P7dR8mSH", "Fabric API"), server, System.out::println);
        try (var files = java.nio.file.Files.list(directory.resolve("mods"))) { assertTrue(files.anyMatch(path -> path.toString().endsWith(".jar"))); }
        assertFalse(java.nio.file.Files.exists(directory.resolve("eula.txt")));
    }

    /// Exercises the new paged catalog and an exact selected release rather than an implicit latest build.
    @Test public void selectedServerAddonVersion(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        java.util.Properties properties = new java.util.Properties(); properties.setProperty("core", "Fabric"); properties.setProperty("version", "1.21.8");
        try (var output = java.nio.file.Files.newOutputStream(directory.resolve("ochre-server.properties"))) { properties.store(output, "fixture"); }
        ServerInstance server = new ServerInstance(directory);
        var page = ServerAddons.search(server, "fabric api", "", "relevance", 0); assertFalse(page.projects().isEmpty());
        var project = page.projects().stream().filter(value -> value.id().equals("P7dR8mSH")).findFirst().orElseThrow();
        var version = ServerAddons.versions(project).stream().filter(value -> ServerAddons.compatible(value, server)).findFirst().orElseThrow();
        ServerCatalog.installAddonVersion(new ServerCatalog.Project(project.id(), project.title()), version.id(), server, System.out::println);
        try (var files = java.nio.file.Files.list(directory.resolve("mods"))) { assertTrue(files.anyMatch(path -> path.getFileName().toString().equals(version.file().label()))); }
    }
    /// Downloads and verifies official native tools; SFTP is started only on a temporary loopback port.
    @Test public void verifiedToolsAndLoopbackSftp(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        ServerToolInstaller.install(ServerToolInstaller.Tool.RCLONE, System.out::println);
        ServerToolInstaller.install(ServerToolInstaller.Tool.FRPC, System.out::println);
        assertTrue(ServerToolInstaller.available(ServerToolInstaller.Tool.RCLONE)); assertTrue(ServerToolInstaller.available(ServerToolInstaller.Tool.FRPC));
        java.util.Properties properties = new java.util.Properties(); properties.setProperty("name", "SFTP fixture");
        try (var output = java.nio.file.Files.newOutputStream(directory.resolve("ochre-server.properties"))) { properties.store(output, "fixture"); }
        ServerInstance server = new ServerInstance(directory); int port;
        try (var reservation = new java.net.ServerSocket(0, 0, java.net.InetAddress.getLoopbackAddress())) { port = reservation.getLocalPort(); }
        try {
            ServerServices.sftp(server, port, "fixture", "fixture-password-123");
            try (var socket = new java.net.Socket("127.0.0.1", port)) { socket.setSoTimeout(3000); String greeting = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream())).readLine(); assertTrue(greeting.startsWith("SSH-2.0"), greeting); }
        } finally { ServerServices.stop(server, ServerToolInstaller.Tool.RCLONE); }
        assertFalse(ServerServices.running(server, ServerToolInstaller.Tool.RCLONE));
    }

}
