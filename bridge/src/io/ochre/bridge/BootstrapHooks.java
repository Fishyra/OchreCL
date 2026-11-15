package io.ochre.bridge;

import java.lang.instrument.Instrumentation;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.jar.JarFile;

/// Publishes only a tiny JDK-only trampoline to isolated loaders, leaving the agent and private ASM outside them.
public final class BootstrapHooks {
    public static final String NAME = "io.ochre.bridge.bootstrap.v1.OverlayHooks";
    /// The JVM may lazily read appended bootstrap archives throughout the game process lifetime.
    private static JarFile archive;
    private BootstrapHooks() { }
    public static void install(Instrumentation instrumentation, Path directory) throws Exception {
        byte[] bytes; try (var input = Objects.requireNonNull(BootstrapHooks.class.getResourceAsStream("/assets/overlay-hooks.jar"))) { bytes = input.readAllBytes(); }
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); Path file = directory.resolve("overlay-hooks-" + hash + ".jar");
        if (!Files.isRegularFile(file) || !MessageDigest.isEqual(bytes, Files.readAllBytes(file))) { Path pending = file.resolveSibling(file.getFileName() + ".new"); Files.write(pending, bytes); Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING); }
        archive = new JarFile(file.toFile()); instrumentation.appendToBootstrapClassLoaderSearch(archive);
        Class<?> hooks = Class.forName(NAME, true, null); var lookup = MethodHandles.lookup();
        hooks.getMethod("configure", java.lang.invoke.MethodHandle.class, java.lang.invoke.MethodHandle.class, java.lang.invoke.MethodHandle.class).invoke(null,
            lookup.findStatic(GameOverlay.class, "render", MethodType.methodType(void.class, Object.class, Object.class)),
            lookup.findStatic(GameOverlay.class, "button", MethodType.methodType(boolean.class, Object.class, Object.class, int.class)),
            lookup.findStatic(GameOverlay.class, "move", MethodType.methodType(boolean.class, Object.class, double.class, double.class)));
        hooks.getMethod("configureObservation", java.lang.invoke.MethodHandle.class, java.lang.invoke.MethodHandle.class).invoke(null,
            lookup.findStatic(ActionObservations.class,"action",MethodType.methodType(void.class,Object.class,Object.class,Object.class,String.class)),
            lookup.findStatic(ActionObservations.class,"input",MethodType.methodType(void.class,Object.class,double.class,double.class,int.class)));
    }
}
