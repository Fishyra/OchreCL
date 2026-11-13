package io.ochrecl.bootstrap;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

/// Starts the Windows portable distribution with data rooted beside the executable.
public final class PortableLauncher {
    /// Utility entry point only.
    private PortableLauncher() {}

    /// Resolves paths from the installed JAR, never from a shortcut's working directory.
    public static void main(String[] args) throws Exception {
        Path root = Path.of(PortableLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent().getParent();
        Path data = root.resolve("data");
        Files.createDirectories(data.resolve("home"));
        Files.createDirectories(data.resolve("local"));
        System.setProperty("user.dir", root.toString());
        System.setProperty("hmcl.home", data.resolve("home").toString());
        System.setProperty("hmcl.dir", data.resolve("local").toString());
        System.setProperty("hmcl.dependencies.dir", data.resolve("local/dependencies").toString());
        System.setProperty("hmcl.version.override", "0.1.0-preview.1");
        if (Arrays.asList(args).contains("--portable-check")) {
            System.out.println("ROOT=" + root);
            System.out.println("HOME=" + System.getProperty("hmcl.home"));
            System.out.println("LOCAL=" + System.getProperty("hmcl.dir"));
            System.out.println("JAVA=" + System.getProperty("java.home"));
            System.out.println("JAVAFX=" + Class.forName("javafx.application.Application").getModule().getDescriptor().version().orElseThrow());
            System.out.println("LAUNCHER=" + Class.forName("org.jackhuang.hmcl.Main").getName());
            return;
        }
        UpdateBootstrap.launch(args);
    }
}
