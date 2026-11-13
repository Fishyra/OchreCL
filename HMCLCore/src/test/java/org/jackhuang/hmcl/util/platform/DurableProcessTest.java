package org.jackhuang.hmcl.util.platform;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises real supervisor reconnect, retained console and stdin after the first attachment is discarded.
@NotNullByDefault
class DurableProcessTest {
    /// Isolated runtime storage, never a real game or server directory.
    @TempDir Path temporary;
    /// Verifies PID identity, independent console ownership and graceful command delivery.
    @Test void reconnectRetainsConsoleAndCommands() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path source = temporary.resolve("Echo.java");
        Files.writeString(source, "class Echo { public static void main(String[] a) throws Exception { System.out.println(\"READY\"); var r = new java.io.BufferedReader(new java.io.InputStreamReader(System.in)); String s; while ((s=r.readLine())!=null) { System.out.println(\"ECHO:\"+s); if(s.equals(\"stop\"))break; } } }");
        DurableProcess first = DurableProcess.start(new ProcessBuilder(executable, source.toString(), "x".repeat(70000)), temporary.resolve("run"));
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!Files.readString(temporary.resolve("run/console.log")).contains("READY") && System.nanoTime() < deadline) Thread.sleep(50);
            assertTrue(Files.readString(temporary.resolve("run/console.log")).contains("READY"));
            first.getInputStream().close();
            DurableProcess resumed = DurableProcess.reconnect(temporary.resolve("run")).orElseThrow();
            assertEquals(first.pid(), resumed.pid());
            assertTrue(new String(resumed.getInputStream().readNBytes(6)).contains("READY"));
            resumed.getOutputStream().write("stop\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); resumed.getOutputStream().flush();
            assertTrue(resumed.waitFor(10, TimeUnit.SECONDS));
            assertTrue(Files.readString(temporary.resolve("run/console.log")).contains("ECHO:stop"));
            assertTrue(DurableProcess.reconnect(temporary.resolve("run")).isEmpty());
        } finally { if (first.isAlive()) first.destroyForcibly(); }
    }
    /// Launches a child from a separate launcher JVM, exits that JVM, then reconnects from this test.
    @Test void survivesActualLauncherExitAndRejectsReusedIdentity() throws Exception {
        String executable = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        Path source = temporary.resolve("Stay.java"); Files.writeString(source, "class Stay { public static void main(String[] a) throws Exception { System.out.println(\"ALIVE\"); new java.io.BufferedReader(new java.io.InputStreamReader(System.in)).readLine(); } }");
        String classpath = Path.of(DurableProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()) + java.io.File.pathSeparator + Path.of(DurableProcessTest.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path directory = temporary.resolve("detached");
        Process launcher = new ProcessBuilder(executable, "-cp", classpath, ExitProbe.class.getName(), executable, source.toString(), directory.toString()).redirectErrorStream(true).start();
        assertTrue(launcher.waitFor(20, TimeUnit.SECONDS)); assertEquals(0, launcher.exitValue(), new String(launcher.getInputStream().readAllBytes()));
        DurableProcess recovered = DurableProcess.reconnect(directory).orElseThrow();
        try {
            assertTrue(recovered.isAlive());
            var descriptor = directory.resolve("process.properties"); String valid = Files.readString(descriptor);
            java.util.Properties stale = new java.util.Properties(); try (var stream = Files.newInputStream(descriptor)) { stale.load(stream); } stale.setProperty("start", "1970-01-01T00:00:00Z"); try (var stream = Files.newOutputStream(descriptor)) { stale.store(stream, "stale fixture"); }
            assertTrue(DurableProcess.reconnect(directory).isEmpty(), "PID alone must never authorize taking over a different process");
            Files.writeString(descriptor, valid);
            recovered.getOutputStream().write("exit\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)); recovered.getOutputStream().flush(); assertTrue(recovered.waitFor(15, TimeUnit.SECONDS));
        } finally { if (recovered.isAlive()) recovered.destroyForcibly(); }
    }
    /// Minimal launcher fixture intentionally leaves its supervised child alive when main returns.
    @NotNullByDefault
    public static final class ExitProbe {
        /// Starts only the disposable echo fixture supplied by the test.
        public static void main(String[] arguments) throws Exception { DurableProcess.start(new ProcessBuilder(arguments[0], arguments[1]), Path.of(arguments[2])); }
    }

}
