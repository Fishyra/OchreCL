package io.ochrecl.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/// Starts a distributable macOS application with per-user data outside the application bundle.
public final class DesktopLauncher {
    /// Utility entry point.
    private DesktopLauncher() {}
    /// Resolves persistent paths without inheriting developer launch arguments.
    public static void main(String[] args) throws Exception {
        Path data = Path.of(System.getProperty("user.home"), "Library", "Application Support", "OchreCL");
        System.setProperty("hmcl.home", data.resolve("home").toString());
        System.setProperty("hmcl.dir", data.resolve("local").toString());
        System.setProperty("user.dir", data.toString());
        System.setProperty("hmcl.version.override", "0.1.0-preview.2");
        if (Arrays.asList(args).contains("--package-check")) {
            System.out.println("DATA=" + data);
            System.out.println("JAVA=" + System.getProperty("java.home"));
            System.out.println("JAVAFX=" + Class.forName("javafx.application.Application").getModule().getDescriptor().version().orElseThrow());
            Class.forName("org.jackhuang.hmcl.Main");
            return;
        }
        Files.createDirectories(data.resolve("home"));
        Files.createDirectories(data.resolve("local"));
        Class.forName("org.jackhuang.hmcl.Main").getMethod("main", String[].class).invoke(null, (Object) args);
    }
}
