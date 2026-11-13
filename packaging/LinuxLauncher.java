package io.ochrecl.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/// Starts the Linux distribution with data under the user's XDG locations, independent of extraction directory.
public final class LinuxLauncher {
    /// Utility entry point.
    private LinuxLauncher() {}
    /// Sets persistent user paths before initializing the launcher, with a read-only dependency check mode.
    public static void main(String[] args) throws Exception {
        String xdg = System.getenv("XDG_DATA_HOME");
        Path root = xdg == null || xdg.isBlank() ? Path.of(System.getProperty("user.home"), ".local", "share") : Path.of(xdg);
        Path data = root.resolve("ochrecl");
        System.setProperty("hmcl.home", data.resolve("home").toString());
        System.setProperty("hmcl.dir", data.resolve("local").toString());
        System.setProperty("user.dir", data.toString());
        System.setProperty("hmcl.version.override", "0.2.0-beta.2");
        if (Arrays.asList(args).contains("--package-check")) {
            System.out.println("DATA=" + data);
            System.out.println("JAVA=" + System.getProperty("java.home"));
            System.out.println("JAVAFX=" + Class.forName("javafx.application.Application").getModule().getDescriptor().version().orElseThrow());
            Class.forName("org.jackhuang.hmcl.Main"); return;
        }
        Files.createDirectories(data.resolve("home")); Files.createDirectories(data.resolve("local"));
        UpdateBootstrap.launch(args);
    }
}
