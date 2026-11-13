package io.ochrecl.bootstrap;

import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;

/// Loads a checksum-verified user-space update while keeping the signed/native application bundle intact.
public final class UpdateBootstrap {
    /// Utility entry point.
    private UpdateBootstrap() {}
    /// Allows the development app to use the same update path while preserving its existing Java options.
    public static void main(String[] args) throws Exception { launch(args); }
    /// Validates the complete update before loading any update classes, otherwise uses the bundled launcher.
    public static void launch(String[] args) throws Exception {
        Path root = Path.of(System.getProperty("hmcl.dir", Path.of(System.getProperty("user.dir"), ".hmcl").toString())).resolve("launcher-updates");
        Path marker = root.resolve("active.properties"); ClassLoader loader = UpdateBootstrap.class.getClassLoader();
        String version = System.getProperty("hmcl.version.override", "");
        if (Files.isRegularFile(marker)) try {
            Properties values = new Properties(); try (var input = Files.newInputStream(marker)) { values.load(input); }
            String name = values.getProperty("file", ""); String expected = values.getProperty("sha256", "");
            if (!name.matches("OchreCL-[a-f0-9]{64}\\.jar") || !expected.matches("[a-f0-9]{64}")) throw new java.io.IOException("Invalid update filename or digest");
            Path jar = root.resolve(name); if (Files.size(jar) > 160L * 1024 * 1024 || !digest(jar).equals(expected)) throw new java.io.IOException("Update checksum mismatch");
            try (JarFile archive = new JarFile(jar.toFile())) { if (archive.getEntry("org/jackhuang/hmcl/Main.class") == null || archive.getEntry("org/jackhuang/hmcl/ui/decorator/OchreShell.class") == null) throw new java.io.IOException("Not an OchreCL payload"); }
            loader = new PayloadLoader(jar.toUri().toURL(), loader); Class.forName("org.jackhuang.hmcl.Main", false, loader);
            System.setProperty("hmcl.version.override", values.getProperty("version", version));
            System.setProperty("ochre.activeUpdate", name);
        } catch (Exception | LinkageError failure) {
            loader = UpdateBootstrap.class.getClassLoader(); System.setProperty("hmcl.version.override", version); System.setProperty("ochre.updateFallback", failure.toString());
            System.err.println("OchreCL update rejected; using bundled launcher: " + failure.getMessage());
        }
        if (Arrays.asList(args).contains("--update-check")) { System.out.println("VERSION=" + System.getProperty("hmcl.version.override")); System.out.println("UPDATE=" + System.getProperty("ochre.activeUpdate", "bundled")); System.out.println("FALLBACK=" + System.getProperty("ochre.updateFallback", "none")); return; }
        Thread.currentThread().setContextClassLoader(loader);
        Class.forName("org.jackhuang.hmcl.Main", true, loader).getMethod("main", String[].class).invoke(null, (Object) args);
    }
    /// Streams hashes without allocating another full launcher image.
    public static String digest(Path file) throws Exception { MessageDigest digest = MessageDigest.getInstance("SHA-256"); try (var input = Files.newInputStream(file)) { byte[] buffer = new byte[65536]; int count; while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count); } return HexFormat.of().formatHex(digest.digest()); }
    /// Isolates updated launcher/dependency classes while sharing JDK and boot-layer JavaFX modules.
    private static final class PayloadLoader extends URLClassLoader {
        /// Creates the isolated payload namespace, retaining the original bootstrap as fallback.
        private PayloadLoader(URL payload, ClassLoader parent) { super(new URL[]{payload}, parent); }
        /// Uses child-first resolution for payload code and parent-first for the runtime/toolkit.
        @Override protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            Class<?> loaded = findLoadedClass(name);
            if (loaded == null && !(name.startsWith("java.") || name.startsWith("javax.") || name.startsWith("javafx.") || name.startsWith("com.sun.") || name.startsWith("sun.") || name.startsWith("jdk.") || name.startsWith("io.ochrecl.bootstrap."))) try { loaded = findClass(name); } catch (ClassNotFoundException ignored) { }
            if (loaded == null) loaded = super.loadClass(name, false); if (resolve) resolveClass(loaded); return loaded;
        }
        /// Resolves updated resources such as CSS and artwork from the same image as the updated classes.
        @Override public URL getResource(String name) { URL found = findResource(name); return found != null ? found : super.getResource(name); }
        /// Keeps service providers and other enumerated resources in payload-first order without duplicates.
        @Override public Enumeration<URL> getResources(String name) throws java.io.IOException { LinkedHashSet<URL> resources = new LinkedHashSet<>(); findResources(name).asIterator().forEachRemaining(resources::add); getParent().getResources(name).asIterator().forEachRemaining(resources::add); return Collections.enumeration(resources); }
    }
}
