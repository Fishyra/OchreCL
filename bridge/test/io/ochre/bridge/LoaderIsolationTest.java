package io.ochre.bridge;

import java.lang.instrument.Instrumentation;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;

/// Uses real installed ASM and Fabric artifacts to verify namespace isolation and fail-open class transformation.
public final class LoaderIsolationTest {
    private static final String GUI = "net/minecraft/client/gui/Gui";
    private static byte[] fixture(boolean matching) {
        ClassWriter writer = new ClassWriter(0); writer.visit(Opcodes.V17, Opcodes.ACC_PUBLIC, GUI, null, "java/lang/Object", null);
        writer.visitField(Opcodes.ACC_PUBLIC, "minecraft", matching ? "Lnet/minecraft/client/Minecraft;" : "Ljava/lang/Object;", null, null).visitEnd();
        writer.visitField(Opcodes.ACC_PUBLIC, "guiRenderState", "Lnet/minecraft/client/renderer/state/gui/GuiRenderState;", null, null).visitEnd();
        MethodVisitor method = writer.visitMethod(Opcodes.ACC_PUBLIC, "extractRenderState", "(Lnet/minecraft/client/DeltaTracker;ZZ)V", null, null); method.visitCode(); method.visitInsn(Opcodes.RETURN); method.visitMaxs(0, 4); method.visitEnd(); writer.visitEnd(); return writer.toByteArray();
    }
    public static void main(String[] args) throws Exception {
        Path agent = Path.of(args[0]), asmRoot = Path.of(args[1]), fabric = Path.of(args[2]); int tested = 0;
        try (var versions = Files.list(asmRoot)) {
            for (Path version : versions.sorted().toList()) {
                Path asm = version.resolve("asm-" + version.getFileName() + ".jar"); if (!Files.isRegularFile(asm)) continue;
                try (URLClassLoader loader = new URLClassLoader(new URL[]{agent.toUri().toURL(), asm.toUri().toURL(), fabric.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
                    Class<?> publicAsm = loader.loadClass("org.objectweb.asm.ClassReader"), privateAsm = loader.loadClass("io.ochre.bridge.internal.asm.ClassReader");
                    check(!publicAsm.equals(privateAsm), "ASM class identities differ");
                    check(publicAsm.getProtectionDomain().getCodeSource().getLocation().equals(asm.toUri().toURL()), "loader ASM remains its own revision");
                    check(privateAsm.getProtectionDomain().getCodeSource().getLocation().equals(agent.toUri().toURL()), "bridge ASM remains private");
                    check(Collections.list(loader.getResources("org/objectweb/asm/ClassReader.class")).size() == 1, "one public ASM resource");
                    loader.loadClass("net.fabricmc.loader.impl.util.LoaderUtil").getMethod("verifyClasspath").invoke(null);
                    Class<?> transformerClass = loader.loadClass("io.ochre.bridge.OverlayTransformer"); Object transformer = transformerClass.getConstructor(Instrumentation.class).newInstance(new Object[]{null});
                    var transform = transformerClass.getMethod("transform", ClassLoader.class, String.class, Class.class, java.security.ProtectionDomain.class, byte[].class);
                    byte[] input = fixture(true); byte[] output = (byte[]) transform.invoke(transformer, loader, GUI, null, null, input);
                    check(output != null && output.length > input.length, "private ASM can insert hook");
                    check(transform.invoke(transformer, loader, GUI, null, null, fixture(false)) == null, "mismatched signatures keep original bytes");
                    check(transform.invoke(transformer, loader, "net/fabricmc/loader/Loader", null, null, input) == null, "loader classes are not transformed");
                    try (URLClassLoader denied = new URLClassLoader(new URL[0], null) { @Override public Class<?> loadClass(String name) throws ClassNotFoundException { if (name.startsWith("io.ochre.")) throw new ClassNotFoundException(name); return super.loadClass(name); } }) {
                        check(transform.invoke(transformer, denied, GUI, null, null, input) == null, "invisible callback leaves game bytes untouched");
                    }
                    System.out.println("PASS: isolated ASM " + version.getFileName() + ", Fabric verifyClasspath, private transformer and fail-open behavior"); tested++;
                }
            }
        }
        check(tested >= 3, "multiple mainstream loader dependency generations tested");
        System.out.println("PASS: " + tested + " ASM dependency generations");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
