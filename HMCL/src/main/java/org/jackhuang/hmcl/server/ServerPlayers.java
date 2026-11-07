package org.jackhuang.hmcl.server;

import com.google.gson.*;
import org.glavo.nbt.io.NBTCodec;
import org.glavo.nbt.tag.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;

/// Server-local player identities, backed-up offline inventory edits and UUID migration.
@NotNullByDefault
public final class ServerPlayers {
    /// A known identity assembled from local caches and permission lists.
    @NotNullByDefault
    public record Player(String name, UUID uuid, boolean operator, boolean whitelisted) { /// Friendly name in selectors.
        @Override public String toString() { return name; }
    }
    /// A saved inventory slot, including data components retained when editing counts.
    @NotNullByDefault
    public record Item(int slot, String id, int count) {}
    /// Utility class.
    private ServerPlayers() {}
    /// Loads known identities without sending player data to a third-party service.
    public static @Unmodifiable List<Player> list(ServerInstance server) throws IOException {
        Map<UUID, Player> result = new LinkedHashMap<>();
        for (String file : List.of("usercache.json", "whitelist.json", "ops.json")) {
            Path path = server.directory.resolve(file); if (!Files.isRegularFile(path)) continue;
            try (var reader = Files.newBufferedReader(path)) {
                for (var value : JsonParser.parseReader(reader).getAsJsonArray()) {
                    var object = value.getAsJsonObject(); UUID uuid = UUID.fromString(object.get("uuid").getAsString()); @Nullable Player old = result.get(uuid);
                    result.put(uuid, new Player(object.get("name").getAsString(), uuid, file.equals("ops.json") || old != null && old.operator(), file.equals("whitelist.json") || old != null && old.whitelisted()));
                }
            } catch (RuntimeException exception) { throw new IOException("无法读取 " + file, exception); }
        }
        return result.values().stream().sorted(Comparator.comparing(Player::name, String.CASE_INSENSITIVE_ORDER)).toList();
    }
    /// Resolves the actual world folder while rejecting traversal and external symbolic links.
    static Path world(ServerInstance server) throws IOException {
        Properties properties = new Properties(); Path file = server.directory.resolve("server.properties");
        if (Files.isRegularFile(file)) try (var input = Files.newInputStream(file)) { properties.load(input); }
        return ServerSnapshots.safe(server.directory, properties.getProperty("level-name", "world"));
    }
    /// Reads a compound player file using the launcher's NBT codec.
    private static CompoundTag data(ServerInstance server, UUID uuid) throws IOException {
        Path file = ServerSnapshots.safe(world(server), "playerdata/" + uuid + ".dat");
        if (Files.size(file) > 16 * 1024 * 1024) throw new IOException("玩家数据文件过大");
        return NBTCodec.of().readTag(file, CompoundTag.class);
    }
    /// Resolves both legacy and modern field spellings without modifying unknown fields.
    private static @Nullable ListTag<?> inventory(CompoundTag root, boolean ender) {
        @Nullable Tag tag = root.get(ender ? "EnderItems" : "Inventory"); if (tag == null) tag = root.get(ender ? "ender_items" : "inventory");
        return tag instanceof ListTag<?> list ? list : null;
    }
    /// Lists actual saved slots; running servers may have newer unsaved inventory data.
    public static @Unmodifiable List<Item> items(ServerInstance server, UUID uuid, boolean ender) throws IOException {
        @Nullable var list = inventory(data(server, uuid), ender); if (list == null) return List.of();
        List<Item> result = new ArrayList<>();
        for (Tag tag : list) if (tag instanceof CompoundTag item) {
            int slot = slot(item); int count = item.get("count") instanceof IntTag value ? value.get() : item.getByteOrElse("Count", (byte) 1) & 255;
            result.add(new Item(slot, item.getStringOrEmpty("id"), count));
        }
        return List.copyOf(result);
    }
    /// Supports both byte and integer slot representations.
    private static int slot(CompoundTag item) { @Nullable Tag slot = item.get("Slot"); if (slot == null) slot = item.get("slot"); return slot instanceof ByteTag value ? value.getUnsigned() : slot instanceof IntTag value ? value.get() : -1; }
    /// Changes one saved slot after a safety snapshot; components of an existing item remain intact.
    public static void editItem(ServerInstance server, UUID uuid, boolean ender, int slot, String id, int count, Consumer<String> progress) throws Exception {
        stopped(server); if (slot < 0 || slot > 150 || count < 0 || count > 99 || count > 0 && !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) throw new IOException("物品 ID 或数量无效");
        ServerMaintenance.begin(server, false);
        try {
        CompoundTag root = data(server, uuid); @Nullable ListTag<?> items = inventory(root, ender); if (items == null) throw new IOException("此版本没有可识别的背包列表");
        @Nullable CompoundTag selected = null;
        for (Tag tag : items) if (tag instanceof CompoundTag item && slot(item) == slot) { selected = item; break; }
        boolean modern = selected != null ? selected.get("count") != null : modernItems(server.settings.getProperty("version", ""));
        if (selected != null) items.removeTag(selected);
        if (count > 0) {
            CompoundTag item = selected != null ? selected.clone() : new CompoundTag();
            put(item, new StringTag(id).setName("id"));
            put(item, new ByteTag((byte) slot).setName(item.get("slot") != null ? "slot" : "Slot"));
            put(item, modern ? new IntTag(count).setName("count") : new ByteTag((byte) count).setName("Count"));
            @SuppressWarnings("unchecked") ListTag<Tag> mutable = (ListTag<Tag>) items; mutable.addTag(item);
        }
        ServerSnapshots.backupStopped(server, progress); stopped(server); write(ServerSnapshots.safe(world(server), "playerdata/" + uuid + ".dat"), root);
        } finally { ServerMaintenance.end(server); }
    }
    /// Matches the modern item stack count format introduced in 1.20.5.
    private static boolean modernItems(String version) { try { String[] parts = version.split("\\."); return Integer.parseInt(parts[0]) > 1 || Integer.parseInt(parts[1]) > 20 || Integer.parseInt(parts[1]) == 20 && parts.length > 2 && Integer.parseInt(parts[2]) >= 5; } catch (Exception exception) { return true; } }
    /// Replaces one named tag while preserving the rest of the player's data.
    private static void put(CompoundTag root, Tag value) { @Nullable Tag prior = root.get(value.getName()); if (prior != null) root.removeTag(prior); root.addTag(value); }
    /// Writes a compressed player file atomically after the original data has been backed up.
    private static void write(Path file, CompoundTag root) throws IOException {
        Path pending = file.resolveSibling(file.getFileName() + ".ochre-new");
        try { try (var output = new GZIPOutputStream(Files.newOutputStream(pending))) { NBTCodec.of().writeTag(output, root); } Files.move(pending, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
        finally { Files.deleteIfExists(pending); }
    }
    /// Produces the UUID used by trusted offline-mode servers for a player name.
    public static UUID offlineUUID(String name) { return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8)); }
    /// Copies saved player data, advancements and statistics to an explicitly selected new UUID, retaining the source.
    public static void migrate(ServerInstance server, UUID source, UUID target, Consumer<String> progress) throws Exception {
        stopped(server); if (source.equals(target)) throw new IOException("来源与目标 UUID 相同");
        ServerMaintenance.begin(server, false);
        try {
        List<Path> sources = new ArrayList<>(), targets = new ArrayList<>();
        for (String kind : List.of("playerdata", "advancements", "stats")) {
            String suffix = kind.equals("playerdata") ? ".dat" : ".json";
            Path from = ServerSnapshots.safe(world(server), kind + "/" + source + suffix), to = ServerSnapshots.safe(world(server), kind + "/" + target + suffix);
            if (Files.isRegularFile(from)) { if (Files.exists(to)) throw new IOException("目标 UUID 已有数据，请先备份并处理目标数据"); sources.add(from); targets.add(to); }
        }
        if (sources.isEmpty()) throw new IOException("来源 UUID 没有保存的数据"); ServerSnapshots.backupStopped(server, progress); stopped(server);
        List<Path> published = new ArrayList<>();
        try {
            for (int index = 0; index < sources.size(); index++) { Path from = sources.get(index), to = targets.get(index); Files.copy(from, to); published.add(to); if (to.toString().endsWith(".dat")) { CompoundTag root = NBTCodec.of().readTag(to, CompoundTag.class); @Nullable Tag old = root.get("UUID"); if (old != null) root.removeTag(old); root.addUUID("UUID", target); write(to, root); } }
        } catch (Exception exception) { for (Path path : published) Files.deleteIfExists(path); throw exception; }
        } finally { ServerMaintenance.end(server); }
    }
    /// Resets only this UUID's saved data after a recoverable full snapshot.
    public static void reset(ServerInstance server, UUID uuid, Consumer<String> progress) throws Exception {
        stopped(server); ServerMaintenance.begin(server, false);
        try { ServerSnapshots.backupStopped(server, progress); stopped(server);
        for (String kind : List.of("playerdata", "advancements", "stats")) Files.deleteIfExists(ServerSnapshots.safe(world(server), kind + "/" + uuid + (kind.equals("playerdata") ? ".dat" : ".json")));
        } finally { ServerMaintenance.end(server); }
    }
    /// Returns actual local saved statistics as formatted JSON.
    public static String statistics(ServerInstance server, UUID uuid) throws IOException { Path file = ServerSnapshots.safe(world(server), "stats/" + uuid + ".json"); return Files.isRegularFile(file) ? new GsonBuilder().setPrettyPrinting().create().toJson(JsonParser.parseString(Files.readString(file))) : "暂无保存的统计信息"; }
    /// Offline mutations require a fully stopped server to prevent lost writes.
    private static void stopped(ServerInstance server) throws IOException { if (server.busy || server.session.isRunning()) throw new IOException("修改玩家存档前请先停止服务器"); }
}
