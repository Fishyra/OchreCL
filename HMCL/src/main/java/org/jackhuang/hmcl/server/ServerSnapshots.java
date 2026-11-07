package org.jackhuang.hmcl.server;

import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.zip.*;

/// Local full-server snapshots with save acknowledgements, isolated storage and guarded recovery.
@NotNullByDefault
public final class ServerSnapshots {
    /// Prevents overlapping maintenance on the same installation.
    private static final Set<Path> LOCKS = ConcurrentHashMap.newKeySet();
    /// Backups never appear inside an SFTP-served installation.
    private static final Path ROOT = Metadata.HMCL_LOCAL_HOME.resolve("ochre-snapshots");
    /// Utility class.
    private ServerSnapshots() {}
    /// Resolves this installation's backup directory.
    public static Path directory(ServerInstance server) { return ROOT.resolve(server.directory.getFileName().toString()); }
    /// Lists newest completed archives, excluding interrupted partial files.
    public static @Unmodifiable List<Path> list(ServerInstance server) throws IOException {
        Path root = directory(server); if (!Files.isDirectory(root)) return List.of();
        try (var files = Files.list(root)) { return files.filter(file -> file.getFileName().toString().endsWith(".zip") && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).sorted(Comparator.reverseOrder()).toList(); }
    }
    /// Saves live worlds before copying and always re-enables autosaving after the attempt.
    public static Path create(ServerInstance server, Consumer<String> progress) throws Exception {
        if (!LOCKS.add(server.directory)) throw new IOException("此服务器正在进行备份或恢复");
        try { ServerMaintenance.begin(server, true); } catch (Exception exception) { LOCKS.remove(server.directory); throw exception; }
        boolean paused = false;
        try {
            if (server.session.isRunning()) {
                progress.accept("正在保存世界…");
                ServerManager.command(server, "save-off"); paused = true;
                var saved = server.session.nextLine(line -> line.contains("Saved the game") || line.contains("Saved the world"), 30);
                ServerManager.command(server, "save-all flush"); saved.get();
            } else if (server.busy) throw new IOException("请等待服务器启动或停止完成");
            return archive(server, progress);
        } finally {
            if (paused && server.session.isRunning()) try { ServerManager.command(server, "save-on"); } catch (IOException exception) { ServerManager.notice(ServerManager.NoticeKind.ERROR, "备份后恢复自动保存失败，请在控制台执行 save-on"); }
            ServerMaintenance.end(server); LOCKS.remove(server.directory);
        }
    }
    /// Creates a safety backup while a stopped-server maintenance operation already owns the guard.
    static Path backupStopped(ServerInstance server, Consumer<String> progress) throws Exception {
        stopped(server); if (!server.maintenance) throw new IOException("维护锁未持有"); return archive(server, progress);
    }
    /// Copies regular files into a staging archive without following symbolic links.
    private static Path archive(ServerInstance server, Consumer<String> progress) throws Exception {
        Files.createDirectories(directory(server));
        String name = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "-" + UUID.randomUUID().toString().substring(0, 6);
        Path pending = directory(server).resolve(name + ".part"), complete = directory(server).resolve(name + ".zip");
        try {
            long lastProgress = 0;
            try (var zip = new ZipOutputStream(Files.newOutputStream(pending)); var files = Files.walk(server.directory)) {
                for (Path file : files.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)).toList()) {
                    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("备份已取消");
                    String relative = server.directory.relativize(file).toString().replace('\\', '/');
                    if (relative.endsWith("session.lock") || relative.endsWith(".ochre-new")) continue;
                    long now = System.nanoTime(); if (now - lastProgress > 250000000L) { progress.accept("备份：" + relative); lastProgress = now; } zip.putNextEntry(new ZipEntry(relative)); Files.copy(file, zip); zip.closeEntry();
                }
            }
            Files.move(pending, complete, StandardCopyOption.ATOMIC_MOVE); return complete;
        } finally { Files.deleteIfExists(pending); }
    }
    /// Lists relative archive members for individual-file recovery.
    public static @Unmodifiable List<String> entries(Path snapshot) throws IOException {
        try (var zip = new ZipFile(snapshot.toFile())) { return zip.stream().filter(entry -> !entry.isDirectory()).map(ZipEntry::getName).sorted().toList(); }
    }
    /// Restores all files after making a safety snapshot; swapping staged directories avoids half-applied recovery.
    public static void restore(ServerInstance server, Path snapshot, Consumer<String> progress) throws Exception {
        stopped(server); if (!LOCKS.add(server.directory)) throw new IOException("此服务器正在进行备份或恢复");
        try { ServerMaintenance.begin(server, false); } catch (Exception exception) { LOCKS.remove(server.directory); throw exception; }
        Path staging = server.directory.resolveSibling(".restore-" + UUID.randomUUID()), old = server.directory.resolveSibling(".restore-old-" + UUID.randomUUID());
        try {
            archive(server, progress); Files.createDirectory(staging);
            extract(snapshot, staging);
            if (!Files.isRegularFile(staging.resolve("ochre-server.properties"))) throw new IOException("不是完整的服务器备份");
            Properties restored = new Properties(); try (var input = Files.newInputStream(staging.resolve("ochre-server.properties"))) { restored.load(input); }
            ServerConfiguration.validate(restored);
            stopped(server); Files.move(server.directory, old, StandardCopyOption.ATOMIC_MOVE);
            try { Files.move(staging, server.directory, StandardCopyOption.ATOMIC_MOVE); }
            catch (Exception exception) { Files.move(old, server.directory, StandardCopyOption.ATOMIC_MOVE); throw exception; }
            server.settings.clear(); server.settings.putAll(restored); deleteTree(old); progress.accept("服务器已恢复；原数据已保留为恢复前快照");
        } finally { try { if (Files.exists(staging)) deleteTree(staging); } finally { ServerMaintenance.end(server); LOCKS.remove(server.directory); } }
    }
    /// Restores one selected member only after backing up the current installation.
    public static void restoreFile(ServerInstance server, Path snapshot, String member, Consumer<String> progress) throws Exception {
        stopped(server); if (!LOCKS.add(server.directory)) throw new IOException("此服务器正在进行备份或恢复");
        try { ServerMaintenance.begin(server, false); } catch (Exception exception) { LOCKS.remove(server.directory); throw exception; }
        try (var zip = new ZipFile(snapshot.toFile())) {
            Path target = safe(server.directory, member); var entry = zip.getEntry(member);
            if (entry == null || entry.isDirectory()) throw new IOException("备份中找不到此文件"); archive(server, progress);
            Files.createDirectories(target.getParent()); Path temporary = target.resolveSibling(target.getFileName() + ".ochre-new");
            try { try (var input = zip.getInputStream(entry); var output = Files.newOutputStream(temporary, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) { byte[] buffer = new byte[65536]; int count; long bytes = 0; while ((count = input.read(buffer)) != -1) { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("恢复已取消"); bytes += count; if (bytes > 100L * 1024 * 1024 * 1024) throw new IOException("文件超过恢复大小限制"); output.write(buffer, 0, count); } } Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            finally { Files.deleteIfExists(temporary); }
        } finally { ServerMaintenance.end(server); LOCKS.remove(server.directory); }
    }
    /// Rejects recovery while a game server may overwrite the restored data.
    private static void stopped(ServerInstance server) throws IOException { if (server.busy || server.session.isRunning()) throw new IOException("恢复前请先停止服务器"); }
    /// Validates archive paths and refuses symbolic-link traversal in existing destination parents.
    static Path safe(Path root, String member) throws IOException {
        Path base = root.toAbsolutePath().normalize(), file = base.resolve(member.replace('\\', '/')).normalize();
        if (!file.startsWith(base) || file.equals(base)) throw new IOException("备份包含不安全的文件路径");
        for (Path part = file; part != null && part.startsWith(base); part = part.getParent()) if (Files.isSymbolicLink(part)) throw new IOException("不能通过符号链接恢复文件");
        return file;
    }
    /// Extracts a full snapshot with entry/size limits and no archive-controlled filesystem links.
    static void extract(Path snapshot, Path destination) throws IOException {
        long total = 0; int entries = 0;
        try (var zip = new ZipInputStream(Files.newInputStream(snapshot))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 500000) throw new IOException("备份文件数过多");
                Path file = safe(destination, entry.getName()); if (entry.isDirectory()) { Files.createDirectories(file); continue; }
                Files.createDirectories(file.getParent());
                try (var output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[65536]; int read;
                    while ((read = zip.read(buffer)) != -1) { if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("恢复已取消"); total += read; if (total > 100L * 1024 * 1024 * 1024) throw new IOException("备份解压超过 100 GiB"); output.write(buffer, 0, read); }
                }
            }
        }
    }
    /// Deletes only a task-owned staging directory without following links.
    private static void deleteTree(Path root) throws IOException { try (var files = Files.walk(root)) { for (Path file : files.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(file); } }
}
