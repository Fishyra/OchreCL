package org.jackhuang.hmcl.server;

import javafx.application.Platform;
import org.jackhuang.hmcl.task.Schedulers;
import org.jetbrains.annotations.NotNullByDefault;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;

/// Explicitly enabled local scheduled restarts and snapshots; schedules operate only while the launcher is open.
@NotNullByDefault
public final class ServerAutomation {
    /// Last successful or attempted daily restart prevents repeating within a minute.
    private static final Map<ServerInstance, LocalDate> RESTARTS = new ConcurrentHashMap<>();
    /// Last snapshot schedule attempt, independent of UI navigation.
    private static final Map<ServerInstance, Instant> BACKUPS = new ConcurrentHashMap<>();
    /// Worker ownership prevents duplicate timers.
    private static boolean started;
    /// Utility class.
    private ServerAutomation() {}
    /// Starts a single daemon clock with no effect until the player enables a schedule.
    public static synchronized void start() {
        if (started) return; started = true;
        var clock = Executors.newSingleThreadScheduledExecutor(task -> { Thread thread = new Thread(task, "Ochre server schedules"); thread.setDaemon(true); return thread; });
        clock.scheduleWithFixedDelay(() -> Platform.runLater(() -> tick(List.copyOf(ServerManager.SERVERS))), 15, 30, TimeUnit.SECONDS);
    }
    /// Reads server settings on their UI thread and submits file/process work to an I/O worker.
    private static void tick(List<ServerInstance> servers) {
        LocalDateTime now = LocalDateTime.now();
        for (var server : servers) {
            if (server.maintenance || !server.session.isRunning() || !server.session.stateProperty().get().equals("运行中")) continue;
            if (Boolean.parseBoolean(server.settings.getProperty("restart.enabled", "false"))) {
                try {
                    LocalTime time = LocalTime.parse(server.settings.getProperty("restart.time", "05:00"));
                    if (now.getHour() == time.getHour() && now.getMinute() == time.getMinute() && !now.toLocalDate().equals(RESTARTS.get(server))) { RESTARTS.put(server, now.toLocalDate()); ServerManager.notice("计划重启：" + server); ServerManager.stop(server, true); }
                } catch (Exception ignored) {}
            }
            if (Boolean.parseBoolean(server.settings.getProperty("backup.enabled", "false"))) {
                int minutes;
                try { minutes = Integer.parseInt(server.settings.getProperty("backup.minutes", "60")); } catch (NumberFormatException exception) { continue; }
                if (minutes < 5) continue;
                Instant previous = BACKUPS.putIfAbsent(server, Instant.now());
                if (previous != null && Duration.between(previous, Instant.now()).toMinutes() >= minutes) {
                    BACKUPS.put(server, Instant.now()); Schedulers.io().execute(() -> { try { ServerSnapshots.create(server, progress -> {}); ServerManager.notice("自动快照已完成：" + server); } catch (Exception exception) { ServerManager.notice(ServerManager.NoticeKind.ERROR, "自动快照失败：" + exception.getMessage()); } });
                }
            }
        }
    }
}
