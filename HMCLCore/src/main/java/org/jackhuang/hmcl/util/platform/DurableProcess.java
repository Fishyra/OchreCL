package org.jackhuang.hmcl.util.platform;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;

/// A reconnectable child process supervised by a small independent JVM with a loopback command channel.
/// Credentials and the launch command travel over stdin, never into the session descriptor.
@NotNullByDefault
public final class DurableProcess extends Process {
    /// Session descriptor directory, containing a bounded output file and process identity.
    private final Path directory;
    /// Validated current process handle; PID alone is never trusted on recovery.
    private final ProcessHandle child;
    /// Private loopback endpoint and capability generated for this run.
    private final Properties descriptor;
    /// Stream offset belongs to this launcher attachment, not the running process.
    private final InputStream output;
    /// Streams commands through a new authenticated loopback connection per flush.
    private final OutputStream input = new ByteArrayOutputStream() {
        /// Flushes bytes without closing the server's stdin when a launcher detaches.
        @Override public synchronized void flush() throws IOException { if (size() > 0) { request("stdin", toByteArray()); reset(); } }
    };
    /// Validates the process identity before exposing control or metrics.
    private DurableProcess(Path directory, Properties descriptor, ProcessHandle child) throws IOException {
        this.directory = directory; this.descriptor = descriptor; this.child = child;
        var file = new RandomAccessFile(directory.resolve("console.log").toFile(), "r");
        file.seek(Math.max(0, file.length() - 180000));
        output = new InputStream() {
            /// Bounds final-flush waiting when a supervisor was terminated unexpectedly.
            private long deadSince;
            /// Tails bounded disk output while the managed process is alive.
            @Override public int read() throws IOException {
                while (true) {
                    if (file.getFilePointer() > file.length()) file.seek(0);
                    int value = file.read(); if (value >= 0) return value;
                    if (!child.isAlive()) {
                        if (deadSince == 0) deadSince = System.nanoTime();
                        if (Files.isRegularFile(directory.resolve("exit.code")) || System.nanoTime() - deadSince > TimeUnit.SECONDS.toNanos(3)) return -1;
                    }
                    try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new InterruptedIOException(); }
                }
            }
            /// Uses bulk reads for console consumers rather than one file operation per character.
            @Override public int read(byte[] bytes, int offset, int length) throws IOException {
                if (length == 0) return 0;
                int first = read(); if (first < 0) return -1; bytes[offset] = (byte) first;
                int rest = file.read(bytes, offset + 1, length - 1); return 1 + Math.max(0, rest);
            }
            /// Detaches this reader only; the supervisor retains output ownership.
            @Override public void close() throws IOException { file.close(); }
        };
    }
    /// Returns a matching live session, or empty when the descriptor is stale or malformed.
    public static Optional<DurableProcess> reconnect(Path directory) {
        try {
            Properties data = new Properties(); try (var stream = Files.newInputStream(directory.resolve("process.properties"))) { data.load(stream); }
            long pid = Long.parseLong(data.getProperty("pid"));
            @Nullable ProcessHandle child = ProcessHandle.of(pid).orElse(null);
            if (child == null || !child.isAlive() || !child.info().startInstant().map(Instant::toString).orElse("").equals(data.getProperty("start"))) return Optional.empty();
            DurableProcess process = new DurableProcess(directory, data, child);
            try { process.request("ping", new byte[0]); return Optional.of(process); } catch (IOException error) { process.getInputStream().close(); return Optional.empty(); }
        } catch (Exception ignored) { return Optional.empty(); }
    }
    /// Starts a detached supervisor and hands it the original launch environment through a pipe.
    public static DurableProcess start(ProcessBuilder builder, Path directory) throws IOException { return start(builder, directory, null); }
    /// Allows owner-only transient tool credentials to be removed by the supervisor after child exit.
    public static DurableProcess start(ProcessBuilder builder, Path directory, @Nullable Path cleanup) throws IOException {
        Files.createDirectories(directory);
        if (Files.getFileStore(directory).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(directory, java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));
        var existing = reconnect(directory);
        if (existing.isPresent()) { existing.get().getInputStream().close(); throw new IOException("此实例已有正在运行的进程，请先停止它"); }
        Files.deleteIfExists(directory.resolve("process.properties")); Files.deleteIfExists(directory.resolve("exit.code"));
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java").toString();
        String classpath;
        try { classpath = Path.of(DurableProcess.class.getProtectionDomain().getCodeSource().getLocation().toURI()) + File.pathSeparator + System.getProperty("java.class.path"); }
        catch (java.net.URISyntaxException error) { throw new IOException(error); }
        Process supervisor = new ProcessBuilder(executable, "-Xms16m", "-Xmx64m", "-XX:+UseSerialGC", "-Djava.awt.headless=true", "-cp", classpath, DurableProcess.class.getName(), directory.toAbsolutePath().toString())
                .redirectErrorStream(true).redirectOutput(directory.resolve("supervisor.log").toFile()).start();
        try (var payload = new DataOutputStream(supervisor.getOutputStream())) {
            writeText(payload, builder.directory() == null ? System.getProperty("user.dir") : builder.directory().getAbsolutePath());
            payload.writeInt(builder.command().size()); for (String value : builder.command()) writeText(payload, value);
            payload.writeBoolean(builder.redirectOutput() == ProcessBuilder.Redirect.DISCARD); writeText(payload, cleanup == null ? "" : cleanup.toAbsolutePath().toString());
            payload.writeInt(builder.environment().size()); for (var entry : builder.environment().entrySet()) { writeText(payload, entry.getKey()); writeText(payload, entry.getValue()); }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            var connected = reconnect(directory); if (connected.isPresent()) return connected.get();
            if (!supervisor.isAlive()) throw new IOException("进程管理器启动失败，请查看 " + directory.resolve("supervisor.log"));
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); supervisor.destroy(); throw new InterruptedIOException(); }
        }
        supervisor.destroy(); throw new IOException("进程管理器启动超时");
    }
    /// Encodes long modpack classpaths without DataOutputStream.writeUTF's 65535-byte limit.
    private static void writeText(DataOutputStream stream, String value) throws IOException { byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8); stream.writeInt(bytes.length); stream.write(bytes); }
    /// Accepts only complete, bounded startup strings from the parent pipe.
    private static String readText(DataInputStream stream) throws IOException { int length = stream.readInt(); if (length < 0 || length > 16 * 1024 * 1024) throw new IOException("启动参数长度无效"); byte[] bytes = stream.readNBytes(length); if (bytes.length != length) throw new EOFException(); return new String(bytes, java.nio.charset.StandardCharsets.UTF_8); }
    /// Performs a bounded capability-authenticated request without ever accepting external network traffic.
    private void request(String operation, byte[] bytes) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), Integer.parseInt(descriptor.getProperty("port"))), 1500); socket.setSoTimeout(1500);
            var stream = new DataOutputStream(socket.getOutputStream()); stream.writeUTF(descriptor.getProperty("token")); stream.writeUTF(operation); stream.writeInt(bytes.length); stream.write(bytes); stream.flush();
            if (socket.getInputStream().read() != 1) throw new IOException("进程管理器拒绝请求");
        }
    }
    /// Exposes a command sink retained by the independent supervisor.
    @Override public OutputStream getOutputStream() { return input; }
    /// Exposes the persisted combined console stream.
    @Override public InputStream getInputStream() { return output; }
    /// Error output is merged into the persisted console.
    @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
    /// Waits for the actual game/server to exit without depending on the launching JVM.
    @Override public int waitFor() throws InterruptedException { while (isAlive()) Thread.sleep(50); awaitExitRecord(System.nanoTime() + TimeUnit.SECONDS.toNanos(3)); return exitValue(); }
    /// Waits up to the caller's requested timeout.
    @Override public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException { long end = System.nanoTime() + unit.toNanos(timeout); while (isAlive() && System.nanoTime() < end) Thread.sleep(50); if (!isAlive()) awaitExitRecord(Math.min(end, System.nanoTime() + TimeUnit.SECONDS.toNanos(3))); return !isAlive(); }
    /// Allows final output and the recorded exit status to be committed before reporting termination.
    private void awaitExitRecord(long deadline) throws InterruptedException { while (!Files.isRegularFile(directory.resolve("exit.code")) && System.nanoTime() < deadline) Thread.sleep(20); }
    /// Reads the exit status written by the supervisor after final log output was flushed.
    @Override public int exitValue() { if (isAlive()) throw new IllegalThreadStateException(); try { return Integer.parseInt(Files.readString(directory.resolve("exit.code")).trim()); } catch (Exception ignored) { return -1; } }
    /// Terminates only the validated managed child tree.
    @Override public void destroy() { try { request("stop", new byte[0]); } catch (IOException ignored) { if (isAlive()) child.destroy(); } }
    /// Requests forced termination through the existing supervisor.
    @Override public Process destroyForcibly() { try { request("kill", new byte[0]); } catch (IOException ignored) { if (isAlive()) child.destroyForcibly(); } return this; }
    /// Uses the real child PID for charts, notifications and process ownership checks.
    @Override public long pid() { return child.pid(); }
    /// Exposes the child's OS identity and measured CPU time.
    @Override public ProcessHandle toHandle() { return child; }
    /// Detects exits even if the UI has not received another console line yet.
    @Override public boolean isAlive() { return child.isAlive() && !Files.isRegularFile(directory.resolve("exit.code")); }

    /// Runs without JavaFX; remains alive when the launcher UI exits and owns stdin/output until child exit.
    public static void main(String[] args) throws Exception {
        Path directory = Path.of(args[0]);
        try (var owner = java.nio.channels.FileChannel.open(directory.resolve("owner.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = owner.tryLock()) {
            if (lock == null) throw new IOException("实例进程管理器已运行");
            var payload = new DataInputStream(System.in); Path working = Path.of(readText(payload));
            int count = payload.readInt(); List<String> command = new ArrayList<>(); for (int i = 0; i < count; i++) command.add(readText(payload));
            ProcessBuilder builder = new ProcessBuilder(command).directory(working.toFile()).redirectErrorStream(true);
            boolean discard = payload.readBoolean(); String cleanupFile = readText(payload);
            int env = payload.readInt(); builder.environment().clear(); for (int i = 0; i < env; i++) builder.environment().put(readText(payload), readText(payload));
            Process child = builder.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { if (child.isAlive()) child.destroy(); if (!cleanupFile.isEmpty()) try { Files.deleteIfExists(Path.of(cleanupFile)); } catch (IOException ignored) { } }));
            try (var log = new RandomAccessFile(directory.resolve("console.log").toFile(), "rw"); ServerSocket endpoint = new ServerSocket(0, 10, InetAddress.getLoopbackAddress())) {
                log.setLength(0); endpoint.setSoTimeout(1000);
                Properties data = new Properties(); data.setProperty("pid", Long.toString(child.pid())); data.setProperty("start", child.info().startInstant().orElseThrow().toString()); data.setProperty("port", Integer.toString(endpoint.getLocalPort())); data.setProperty("token", UUID.randomUUID().toString());
                Path pending = directory.resolve("process.properties.new"); try (var stream = Files.newOutputStream(pending)) { data.store(stream, "OchreCL process identity; contains no launch arguments"); }
                try { Files.setPosixFilePermissions(pending, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
                Files.move(pending, directory.resolve("process.properties"), StandardCopyOption.REPLACE_EXISTING);
                Thread reader = new Thread(() -> {
                    try (var stream = child.getInputStream()) { byte[] buffer = new byte[8192]; int size; while ((size = stream.read(buffer)) >= 0) { if (discard) continue; synchronized (log) { if (log.length() > 32 * 1024 * 1024) { log.setLength(0); log.seek(0); } log.write(buffer, 0, size); } } }
                    catch (IOException ignored) { }
                }, "OchreCL persistent console"); reader.start();
                while (child.isAlive()) {
                    try (Socket socket = endpoint.accept()) {
                        socket.setSoTimeout(1500); var stream = new DataInputStream(socket.getInputStream());
                        if (!data.getProperty("token").equals(stream.readUTF())) continue;
                        String operation = stream.readUTF(); int length = stream.readInt(); if (length < 0 || length > 65536) continue;
                        byte[] bytes = stream.readNBytes(length); if (bytes.length != length) continue;
                        switch (operation) {
                            case "stdin" -> { child.getOutputStream().write(bytes); child.getOutputStream().flush(); }
                            case "stop" -> { child.descendants().forEach(ProcessHandle::destroy); child.destroy(); }
                            case "kill" -> { child.descendants().forEach(ProcessHandle::destroyForcibly); child.destroyForcibly(); }
                            case "ping" -> { }
                            default -> { continue; }
                        }
                        socket.getOutputStream().write(1);
                    } catch (SocketTimeoutException ignored) { } catch (IOException ignored) { }
                }
                int code = child.waitFor(); reader.join(3000); Files.writeString(directory.resolve("exit.code"), Integer.toString(code));
            } finally { if (child.isAlive()) child.destroy(); if (!cleanupFile.isEmpty()) Files.deleteIfExists(Path.of(cleanupFile)); }
        }
    }
}
