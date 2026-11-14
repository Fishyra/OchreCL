package io.ochre.bridge;

import java.io.IOException;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/// Explicit game-thread script actions using public 26.x APIs; no packet spoofing, commands or teleportation.
public final class ScriptActions {
    /// Current short-lived movement owner; accessed on the game thread only.
    private static String owner = "";
    /// Last game-thread heartbeat deadline.
    private static long expires;
    /// Keys changed by the current lease, retained so disconnect can still release them.
    private static Object forward, sprint;
    /// Client executor used only to queue one outstanding cleanup operation.
    private static volatile Executor clientExecutor;
    /// Watchdog work remains bounded even if the client thread stalls.
    private static final AtomicBoolean queued = new AtomicBoolean();
    /// Independent lease watchdog prevents orphaned input after launcher exit or socket failures.
    private static final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(task -> { Thread thread = new Thread(task, "OchreBridge input lease"); thread.setDaemon(true); return thread; });
    static { watchdog.scheduleWithFixedDelay(() -> { Executor executor = clientExecutor; if (executor != null && queued.compareAndSet(false, true)) try { executor.execute(() -> { try { if (!owner.isEmpty() && System.nanoTime() >= expires) release(); } catch (Exception ignored) { } finally { queued.set(false); } }); } catch (Exception ignored) { queued.set(false); } }, 100, 100, TimeUnit.MILLISECONDS); }
    /// Utility class.
    private ScriptActions() { }
    /// Reads public, no-argument methods without making inaccessible members accessible.
    private static Object call(Object target, String method) throws Exception { return target.getClass().getMethod(method).invoke(target); }
    /// Reads a public client field.
    private static Object field(Object target, String name) throws Exception { return target.getClass().getField(name).get(target); }
    /// Requires a real loaded player rather than fabricating coordinates outside a world.
    private static Object player(Object client) throws Exception { Object player = field(client, "player"); if (player == null) throw new IOException("game.not.in.world"); return player; }
    /// Converts a public numeric property.
    private static double value(Object object, String name) throws Exception { return ((Number) call(object, name)).doubleValue(); }
    /// Requires finite numeric protocol text and a bounded interval.
    private static double numeric(Map<String, String> request, String name, double min, double max) throws IOException { double value; try { value = Double.parseDouble(request.getOrDefault(name, "NaN")); } catch (NumberFormatException error) { throw new IOException("argument.invalid:" + name); } if (!Double.isFinite(value) || value < min || value > max) throw new IOException("argument.out.of.range:" + name); return value; }
    /// Reads actual health and clock state, omitting capabilities that this version cannot supply.
    public static void state(Object client, Map<String, String> response) throws Exception {
        Object player = field(client, "player"), level = field(client, "level");
        if (player != null) response.put("player.health", String.valueOf(call(player, "getHealth")));
        if (level != null) { try { response.put("world.day_time", String.valueOf(call(level, "getDefaultClockTime"))); } catch (ReflectiveOperationException ignored) { } try { response.put("world.game_time", String.valueOf(call(call(level, "getLevelData"), "getGameTime"))); } catch (ReflectiveOperationException ignored) { } }
    }
    /// Routes only a fixed set of documented script requests, all invoked on the Minecraft thread.
    public static void handle(Object client, Map<String, String> request, Map<String, String> response) throws Exception {
        String command = request.get("command");
        if (!Set.of("script.release", "script.inventory", "script.players", "script.look", "script.move", "script.hit").contains(command)) throw new IOException("command.unsupported");
        if (command.equals("script.release")) { if (owner.equals(request.get("owner"))) release(); return; }
        Object player = player(client);
        switch (command) {
            case "script.inventory" -> inventory(player, response);
            case "script.players" -> players(client, player, request, response);
            case "script.look" -> look(client, player, request);
            case "script.move" -> move(client, request);
            case "script.hit" -> hit(client, player);
            default -> throw new IOException("command.unsupported");
        }
    }
    /// Serializes an actual inventory into bounded slots; identifiers are registry names, not translated labels.
    private static void inventory(Object player, Map<String, String> response) throws Exception {
        Object inventory = call(player, "getInventory"); int count = ((Number) call(inventory, "getContainerSize")).intValue(); if (count < 0 || count > 54) throw new IOException("inventory.layout.unsupported"); response.put("slots", Integer.toString(count));
        for (int i = 0; i < count; i++) { Object stack = inventory.getClass().getMethod("getItem", int.class).invoke(inventory, i); int amount = (Boolean) call(stack, "isEmpty") ? 0 : ((Number) call(stack, "getCount")).intValue(); String prefix = "slot." + i; response.put(prefix + ".count", Integer.toString(amount)); if (amount != 0) { Object item = call(stack, "getItem"); response.put(prefix + ".id", String.valueOf(call(call(call(item, "builtInRegistryHolder"), "key"), "identifier"))); } }
    }
    /// Returns nearby loaded players sorted by real distance; at most sixteen fit in a bounded protocol response.
    private static void players(Object client, Object self, Map<String, String> request, Map<String, String> response) throws Exception {
        double radius = numeric(request, "radius", .01, 128); String name = request.getOrDefault("name", ""); Object level = field(client, "level"); if (level == null) throw new IOException("game.not.in.world"); List<?> entities = (List<?>) call(level, "players"); List<Object> nearby = new ArrayList<>();
        for (Object target : entities) if (target != self && distance(self, target) <= radius && (name.isEmpty() || name.equals(call(call(target, "getName"), "getString")))) nearby.add(target);
        Map<Object, Double> distances = new IdentityHashMap<>(); for (Object target : nearby) distances.put(target, distance(self, target)); nearby.sort(Comparator.comparingDouble(distances::get)); if (nearby.size() > 16) nearby = nearby.subList(0, 16); response.put("players", Integer.toString(nearby.size()));
        for (int i = 0; i < nearby.size(); i++) { Object target = nearby.get(i); String prefix = "entity." + i + "."; response.put(prefix + "id", String.valueOf(call(target, "getUUID"))); response.put(prefix + "name", String.valueOf(call(call(target, "getName"), "getString"))); for (String axis : List.of("X", "Y", "Z")) response.put(prefix + axis.toLowerCase(Locale.ROOT), String.valueOf(call(target, "get" + axis))); }
    }
    /// Computes a real three-dimensional distance.
    private static double distance(Object a, Object b) throws Exception { double x = value(a, "getX") - value(b, "getX"), y = value(a, "getY") - value(b, "getY"), z = value(a, "getZ") - value(b, "getZ"); return Math.sqrt(x * x + y * y + z * z); }
    /// Rejects controls when a menu is open or the game is paused; unsupported versions fail explicitly.
    private static void interactive(Object client) throws Exception { if ((Boolean) call(client, "isPaused") || call(field(client, "gui"), "screen") != null) throw new IOException("game.control.menu.or.paused"); }
    /// Rotates the real player, optionally tracking a previously resolved UUID; unsupported limb poses are not approximated silently.
    private static void look(Object client, Object player, Map<String, String> request) throws Exception {
        interactive(client); double yaw, pitch;
        if (request.containsKey("target") || request.containsKey("x")) { double x, y, z; if (request.containsKey("target")) { Object target = null; Object level = field(client, "level"); for (Object entity : (List<?>) call(level, "players")) if (String.valueOf(call(entity, "getUUID")).equals(request.get("target"))) { target = entity; break; } if (target == null || distance(player, target) > 128) throw new IOException("target.not.available"); String point = request.getOrDefault("point", "eyes"); if (!Set.of("eyes", "center", "feet").contains(point)) throw new IOException("target.point.unsupported:" + point); x = value(target, "getX"); z = value(target, "getZ"); y = value(target, "getY"); if (point.equals("eyes")) y = value(target, "getEyeY"); if (point.equals("center")) y += value(target, "getBbHeight") / 2; } else { x = numeric(request, "x", -30_000_000, 30_000_000); y = numeric(request, "y", -30_000_000, 30_000_000); z = numeric(request, "z", -30_000_000, 30_000_000); }
            double dx = x - value(player, "getX"), dz = z - value(player, "getZ"), dy = y - value(player, "getEyeY"); if (Math.hypot(dx, dz) < 1e-8 && Math.abs(dy) < 1e-8) throw new IOException("target.coincides.with.camera"); yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90; pitch = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        } else { yaw = numeric(request, "yaw", -360_000, 360_000); pitch = numeric(request, "pitch", -90, 90); }
        double startYaw = numeric(request, "startYaw", -360_000, 360_000), startPitch = numeric(request, "startPitch", -90, 90), fraction = numeric(request, "fraction", 0, 1); double delta = ((yaw - startYaw) % 360 + 540) % 360 - 180;
        player.getClass().getMethod("setYRot", float.class).invoke(player, (float) (startYaw + delta * fraction)); player.getClass().getMethod("setXRot", float.class).invoke(player, (float) (startPitch + (pitch - startPitch) * fraction));
    }
    /// Acquires only idle forward/sprint keys and refreshes a short lease; concurrent owners are rejected.
    private static void move(Object client, Map<String, String> request) throws Exception {
        interactive(client); String next = request.getOrDefault("owner", ""); if (!next.matches("[a-f0-9-]{36}")) throw new IOException("control.owner.invalid"); if (!owner.isEmpty() && System.nanoTime() >= expires) release(); if (!owner.isEmpty() && !owner.equals(next)) throw new IOException("control.busy"); String sprinting = request.getOrDefault("sprint", ""); if (!sprinting.equals("true") && !sprinting.equals("false")) throw new IOException("argument.invalid:sprint");
        if (owner.isEmpty()) { Object options = field(client, "options"); forward = field(options, "keyUp"); sprint = field(options, "keySprint"); if ((Boolean) call(forward, "isDown") || (Boolean) call(sprint, "isDown")) { forward = null; sprint = null; throw new IOException("control.key.already.held"); } owner = next; }
        clientExecutor = (Executor) client; expires = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(750); setKey(forward, true); setKey(sprint, Boolean.parseBoolean(sprinting));
    }
    /// Sets ordinary and toggle-style keys idempotently; repeated heartbeats must never toggle sprint off.
    private static void setKey(Object key, boolean down) throws Exception { if ((Boolean) call(key, "isDown") == down) return; key.getClass().getMethod("setDown", boolean.class).invoke(key, down); if ((Boolean) call(key, "isDown") != down) key.getClass().getMethod("setDown", boolean.class).invoke(key, true); if ((Boolean) call(key, "isDown") != down) throw new IOException("control.key.mode.unsupported"); }
    /// Releases both retained keys and keeps ownership for watchdog retries if a key API fails.
    private static void release() throws Exception { Exception failure = null; for (Object key : new Object[]{forward, sprint}) if (key != null) try { setKey(key, false); } catch (Exception error) { if (failure == null) failure = error; else failure.addSuppressed(error); } if (failure != null) throw failure; forward = null; sprint = null; owner = ""; expires = 0; }
    /// Performs an ordinary attack against the actual crosshair entity, honoring reach, cooldown and spectator state.
    private static void hit(Object client, Object player) throws Exception {
        interactive(client); if ((Boolean) call(player, "isSpectator")) throw new IOException("permission.attack.spectator"); if (((Number) player.getClass().getMethod("getAttackStrengthScale", float.class).invoke(player, 0f)).doubleValue() < .99) throw new IOException("attack.cooldown"); Object hit = field(client, "hitResult"); if (hit == null || !hit.getClass().getName().equals("net.minecraft.world.phys.EntityHitResult")) throw new IOException("attack.no.entity.under.crosshair"); Object target = call(hit, "getEntity"); ClassLoader loader = client.getClass().getClassLoader(); Class<?> entityType = Class.forName("net.minecraft.world.entity.Entity", false, loader), playerType = Class.forName("net.minecraft.world.entity.player.Player", false, loader); if (!(Boolean) player.getClass().getMethod("isWithinEntityInteractionRange", entityType, double.class).invoke(player, target, 0d)) throw new IOException("attack.out.of.reach"); Object mode = field(client, "gameMode"); if (mode == null) throw new IOException("game.not.ready"); mode.getClass().getMethod("attack", playerType, entityType).invoke(mode, player, target);
    }
}
