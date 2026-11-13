package org.jackhuang.hmcl.server;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyStringProperty;
import javafx.beans.property.ReadOnlyStringWrapper;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.concurrent.ConcurrentHashMap;

/// Bounded process console and measured system/process load shared by game and server panels.
@NotNullByDefault
public final class RuntimeSession {
    /// Game processes indexed by canonical instance directory.
    private static final ConcurrentHashMap<String, RuntimeSession> games = new ConcurrentHashMap<>();
    /// User-facing lifecycle state, published on the JavaFX thread.
    private final ReadOnlyStringWrapper state = new ReadOnlyStringWrapper("已停止");
    /// Limited console history; background readers never mutate controls.
    private final ArrayDeque<String> lines = new ArrayDeque<>();
    /// Current owned child process, absent before start.
    private volatile @Nullable Process process;
    /// Time of process start.
    private volatile Instant started = Instant.now();
    /// Prior CPU sample and wall-clock sample for interval utilization.
    private long previousCpu, previousTime;
    /// Final elapsed time retained after a process exits.
    private volatile long stoppedSeconds;
    /// Distinguishes a player-requested game stop from an unexpected process failure.
    private volatile boolean stopRequested;
    /// Monotonic console revision used to avoid unnecessary UI replacement.
    private long revision;
    /// Tracks retained characters without scanning the full buffer per log line.
    private int retainedCharacters;

    /// One-shot output subscriptions for save acknowledgements and player-list responses.
    private final java.util.concurrent.CopyOnWriteArrayList<java.util.function.Consumer<String>> observers = new java.util.concurrent.CopyOnWriteArrayList<>();
    /// Waits for a future matching line without polling stale console text; timeout removes the observer.
    public java.util.concurrent.CompletableFuture<String> nextLine(java.util.function.Predicate<String> predicate, long timeoutSeconds) {
        var future = new java.util.concurrent.CompletableFuture<String>();
        java.util.function.Consumer<String> observer = line -> { if (predicate.test(line)) future.complete(line); };
        observers.add(observer); future.orTimeout(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS).whenComplete((line, error) -> observers.remove(observer));
        return future;
    }
    /// Creates an initially stopped process session.
    public RuntimeSession() {}

    /// Gets the stable session for one installed game instance.
    public static RuntimeSession game(String key) { return games.computeIfAbsent(key, ignored -> { RuntimeSession session = new RuntimeSession(); session.recover(gameDirectory(key)); return session; }); }
    /// Uses the same stable instance key as the core launcher's independent supervisor.
    private static java.nio.file.Path gameDirectory(String key) { return org.jackhuang.hmcl.Metadata.HMCL_LOCAL_HOME.resolve("ochre-runtime").resolve(java.util.UUID.nameUUIDFromBytes(key.getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString()); }
    /// Reattaches a validated owned process and resumes its persisted console on a background reader.
    public void recover(java.nio.file.Path directory) {
        var recovered = org.jackhuang.hmcl.util.platform.DurableProcess.reconnect(directory);
        if (recovered.isEmpty()) {
            try { var file = directory.resolve("console.log"); if (java.nio.file.Files.isRegularFile(file)) { try (var log = new java.io.RandomAccessFile(file.toFile(), "r")) { log.seek(Math.max(0, log.length() - 180000)); byte[] bytes = new byte[(int) (log.length() - log.getFilePointer())]; log.readFully(bytes); for (String line : new String(bytes, java.nio.charset.StandardCharsets.UTF_8).split("\\R")) append(line); } } } catch (java.io.IOException ignored) { }
            return;
        }
        Process child = recovered.get(); attach(child); started = child.info().startInstant().orElse(Instant.now()); status("运行中 · 已重新接管");
        org.jackhuang.hmcl.task.Schedulers.io().execute(() -> {
            try (var reader = child.inputReader(java.nio.charset.StandardCharsets.UTF_8)) { @Nullable String line; while ((line = reader.readLine()) != null) append(line); child.waitFor(); if (process == child) status(wasStopRequested() || child.exitValue() == 0 ? "已停止" : "异常退出"); }
            catch (Exception error) { if (error instanceof InterruptedException) Thread.currentThread().interrupt(); }
        });
    }
    /// Lists currently owned game tasks without exposing mutable registry state.
    public static java.util.@org.jetbrains.annotations.Unmodifiable Map<String, RuntimeSession> runningGames() { java.util.Map<String, RuntimeSession> active = new java.util.LinkedHashMap<>(); games.forEach((key, value) -> { if (value.isRunning()) active.put(key, value); }); return java.util.Map.copyOf(active); }
    /// Resolves the actual game working directory for screenshot/log shortcuts, including isolated instances.
    public static java.nio.file.Path runDirectory(String key) { try { return java.nio.file.Path.of(java.nio.file.Files.readString(gameDirectory(key).resolve("run.path")).strip()); } catch (Exception ignored) { return java.nio.file.Path.of(key); } }
    /// Discovers supervised games before the player selects any instance in the UI.
    public static void restoreGames() {
        var root = org.jackhuang.hmcl.Metadata.HMCL_LOCAL_HOME.resolve("ochre-runtime");
        org.jackhuang.hmcl.task.Schedulers.io().execute(() -> {
            if (!java.nio.file.Files.isDirectory(root)) return;
            try (var directories = java.nio.file.Files.list(root)) { for (var dir : directories.toList()) { var key = dir.resolve("instance.path"); if (java.nio.file.Files.isRegularFile(key)) game(java.nio.file.Files.readString(key).strip()); } }
            catch (java.io.IOException ignored) { }
        });
    }
    /// Finds a game already running under this launcher for server Quick Play handoff.
    public static java.util.Optional<RuntimeSession> activeGame() { return games.values().stream().filter(RuntimeSession::isRunning).findFirst(); }
    /// Associates an actual launched process and resets its console for this run.
    public synchronized void attach(Process value) {
        process = value;
        stopRequested = false;
        started = Instant.now();
        stoppedSeconds = 0;
        previousCpu = 0;
        previousTime = 0;
        lines.clear();
        retainedCharacters = 0;
        revision++;
        status("启动中");
    }
    /// Publishes lifecycle updates without blocking a process output reader.
    public void status(String value) {
        if (process != null && !isRunning()) stoppedSeconds = Math.max(0, Duration.between(started, Instant.now()).getSeconds());
        Platform.runLater(() -> state.set(value));
    }
    /// Exposes current display state.
    public ReadOnlyStringProperty stateProperty() { return state.getReadOnlyProperty(); }
    /// Checks the real OS process rather than a potentially delayed UI flag.
    public boolean isRunning() { var p = process; return p != null && p.isAlive(); }
    /// Returns the owned process for server commands and measurements.
    public @Nullable Process process() { return process; }
    /// Reports whether this run was deliberately stopped by the player.
    public boolean wasStopRequested() { return stopRequested; }
    /// Terminates only this child process; game stop requires UI confirmation.
    public void stop() {
        var p = process;
        if (p != null && p.isAlive()) {
            stopRequested = true;
            status("停止中");
            p.descendants().forEach(ProcessHandle::destroy);
            p.destroy();
        }
    }
    /// Appends bounded redacted output, retaining at most 1,200 lines and 180,000 characters.
    public synchronized void append(String line) {
        String bounded = line.length() > 4000 ? line.substring(0, 4000) : line;
        lines.addLast(bounded);
        retainedCharacters += bounded.length();
        while (lines.size() > 1200 || retainedCharacters > 180000) retainedCharacters -= lines.removeFirst().length();
        revision++;
        observers.forEach(observer -> observer.accept(bounded));
    }
    /// Returns the current console revision.
    public synchronized long revision() { return revision; }
    /// Copies a bounded console snapshot.
    public synchronized String log() { return String.join("\n", lines); }
    /// Immutable measured values; NaN and negative byte counts mean unavailable, never fabricated zeroes.
    @NotNullByDefault
    public record Snapshot(double systemCpu, double processCpu, long usedMemory, long totalMemory,
                           long residentBytes, long uptimeSeconds, boolean running, long processId) {
        /// Produces selectable/exportable text matching the dashboard's units.
        public String report() {
            return "系统 CPU：" + percent(systemCpu) + "\n系统内存：" + bytes(usedMemory) + " / " + bytes(totalMemory)
                    + "\n进程 CPU：" + (running ? percent(processCpu) : "未运行") + "（单核 = 100%）"
                    + "\n进程驻留内存：" + (running ? bytes(residentBytes) : "未运行") + "\n运行时间：" + elapsed(uptimeSeconds);
        }
    }
    /// Formats unavailable percentages without implying the process is idle.
    static String percent(double value) { return Double.isFinite(value) ? String.format("%.1f%%", value) : "不可用"; }
    /// Formats actual memory counts with an explicit unit.
    static String bytes(long value) {
        if (value < 0) return "不可用";
        return value >= 1073741824L ? String.format("%.2f GiB", value / 1073741824.0) : String.format("%.1f MiB", value / 1048576.0);
    }
    /// Formats a monotonic elapsed duration rather than a wall-clock timestamp.
    static String elapsed(long seconds) { return String.format("%02d:%02d:%02d", seconds / 3600, seconds / 60 % 60, seconds % 60); }
    /// Operating-system process samples used when the JDK does not expose CPU time on macOS.
    @NotNullByDefault
    private record ProcessStats(double cpu, long resident) {}
    /// Samples resident memory and the OS CPU estimate in one bounded subprocess, outside the console lock.
    private static ProcessStats processStats(@Nullable Process process) {
        if (process == null || !process.isAlive()) return new ProcessStats(Double.NaN, -1);
        try {
            if (System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win")) return new ProcessStats(Double.NaN, -1);
            Process sample = new ProcessBuilder("ps", "-o", "pcpu=", "-o", "rss=", "-p", Long.toString(process.pid())).start();
            if (!sample.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) { sample.destroyForcibly(); return new ProcessStats(Double.NaN, -1); }
            try (var input = sample.inputReader()) {
                @Nullable String value = input.readLine();
                if (value == null) return new ProcessStats(Double.NaN, -1);
                String[] values = value.strip().split("\\s+");
                return new ProcessStats(Double.parseDouble(values[0].replace(',', '.')), Long.parseLong(values[1]) * 1024);
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return new ProcessStats(Double.NaN, -1); }
        catch (Exception ignored) { return new ProcessStats(Double.NaN, -1); }
    }
    /// Measures load without holding the console mutex during native sampling or process I/O.
    public Snapshot snapshot() {
        long now = System.nanoTime();
        @Nullable Process p = process;
        boolean running = p != null && p.isAlive();
        var stats = processStats(p);
        var duration = p == null ? java.util.Optional.<Duration>empty() : p.info().totalCpuDuration();
        double processCpu = Double.NaN;
        synchronized (this) {
            if (running && duration.isPresent()) {
                long cpu = duration.get().toNanos();
                if (previousTime != 0 && cpu >= previousCpu) processCpu = (cpu - previousCpu) * 100.0 / Math.max(1, now - previousTime);
                previousCpu = cpu;
                previousTime = now;
            } else if (running) processCpu = stats.cpu();
        }
        double systemCpu = Double.NaN;
        long total = -1, used = -1;
        if (ManagementFactory.getOperatingSystemMXBean() instanceof com.sun.management.OperatingSystemMXBean bean) {
            double load = bean.getCpuLoad();
            if (load >= 0) systemCpu = Math.min(100, load * 100);
            total = bean.getTotalMemorySize(); used = Math.max(0, total - bean.getFreeMemorySize());
        }
        long seconds = running ? Duration.between(started, Instant.now()).getSeconds() : stoppedSeconds;
        return new Snapshot(systemCpu, processCpu, used, total, running ? stats.resident() : -1,
                Math.max(0, seconds), running, p == null ? 0 : p.pid());
    }
    /// Retains a text export for existing consumers while the dashboard uses structured measurements.
    public String metrics() { return snapshot().report(); }
}
