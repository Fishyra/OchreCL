package io.ochre.bridge;

import java.util.*;
import java.util.concurrent.Executor;

/// Standalone fake-client contract test; never loads a Minecraft account, world or network connection.
public final class ScriptActionsTest {
    /// Public shape matching the 26.2 GUI screen accessor.
    public static final class Gui { public Object screen() { return null; } }
    /// A controllable key records lease acquisition and release.
    public static class Key { volatile boolean down; public boolean isDown() { return down; } public void setDown(boolean value) { down = value; } }
    /// Mirrors vanilla toggle sprint: false is ignored and each true flips the state.
    public static final class ToggleKey extends Key { public void setDown(boolean value) { if (value) down = !down; } }
    /// Only the forward/sprint keys are exposed to script actions.
    public static final class Options { public final Key keyUp = new Key(), keySprint = new ToggleKey(); }
    /// A minimal clock snapshot with the same public method names as 26.2.
    public static final class Clock { public long getGameTime() { return 42; } }
    /// Public component text needed for player selection.
    public static final class Name { final String value; Name(String value) { this.value = value; } public String getString() { return value; } }
    /// Public inventory registry holder chain.
    public static final class RegistryKey { public String identifier() { return "minecraft:stone"; } }
    /// Public item holder.
    public static final class Holder { public RegistryKey key() { return new RegistryKey(); } }
    /// Public item identity.
    public static final class Item { public Holder builtInRegistryHolder() { return new Holder(); } }
    /// One deterministic inventory stack.
    public static final class Stack { public boolean isEmpty() { return false; } public int getCount() { return 12; } public Item getItem() { return new Item(); } }
    /// Fixed-size inventory supports exactly the methods read by the bridge.
    public static final class Inventory { public int getContainerSize() { return 1; } public Stack getItem(int slot) { return new Stack(); } }
    /// Fake player preserves yaw/pitch mutations while exposing real-shaped public state.
    public static final class Player {
        final UUID id = UUID.randomUUID(); final String name; final double x; float yaw, pitch;
        Player(String name, double x) { this.name = name; this.x = x; }
        public double getX() { return x; } public double getY() { return 64; } public double getZ() { return 0; } public double getEyeY() { return 65.6; } public double getBbHeight() { return 1.8; } public float getHealth() { return 20; } public UUID getUUID() { return id; } public Name getName() { return new Name(name); } public Inventory getInventory() { return new Inventory(); } public void setYRot(float value) { yaw = value; } public void setXRot(float value) { pitch = value; }
    }
    /// Loaded player collection and current clock.
    public static final class Level { final List<Player> players; Level(List<Player> players) { this.players = players; } public List<Player> players() { return players; } public long getDefaultClockTime() { return 123; } public Clock getLevelData() { return new Clock(); } }
    /// Immediate executor models the caller's game-thread requirement without creating a real game.
    public static final class Client implements Executor { public final Options options = new Options(); public final Gui gui = new Gui(); public final Player player = new Player("Self", 0); public final Level level = new Level(List.of(player, new Player("Far", 10), new Player("BetaF1sh", 3))); public boolean isPaused() { return false; } public void execute(Runnable action) { action.run(); } }
    /// Runs state, selection, inventory, rotation and independent lease-expiry assertions.
    public static void main(String[] args) throws Exception {
        Client client = new Client(); Map<String, String> response = new HashMap<>(); ScriptActions.state(client, response); check(response.get("world.game_time").equals("42"), "clock"); check(response.get("player.health").equals("20.0"), "health");
        ScriptActions.handle(client, Map.of("command", "script.inventory"), response); check(response.get("slot.0.id").equals("minecraft:stone"), "inventory registry name");
        response.clear(); ScriptActions.handle(client, Map.of("command", "script.players", "radius", "16"), response); check(response.get("entity.0.name").equals("BetaF1sh"), "nearest first"); check(response.get("players").equals("2"), "self excluded");
        ScriptActions.handle(client, Map.of("command", "script.look", "yaw", "90", "pitch", "20", "startYaw", "0", "startPitch", "0", "fraction", ".5"), response); check(client.player.yaw == 45 && client.player.pitch == 10, "interpolated look");
        try { ScriptActions.handle(client, Map.of("command", "script.look", "target", client.level.players.get(2).id.toString(), "point", "right_arm"), response); throw new AssertionError("unsupported limb accepted"); } catch (java.io.IOException error) { check(error.getMessage().startsWith("target.point.unsupported"), "explicit limb failure"); }
        String owner = UUID.randomUUID().toString(), other = UUID.randomUUID().toString(); ScriptActions.handle(client, Map.of("command", "script.move", "owner", owner, "sprint", "true"), response); check(client.options.keyUp.isDown() && client.options.keySprint.isDown(), "input acquired"); ScriptActions.handle(client, Map.of("command", "script.move", "owner", owner, "sprint", "true"), response); check(client.options.keySprint.isDown(), "toggle sprint survives heartbeat");
        ScriptActions.handle(client, Map.of("command", "script.release", "owner", other), response); check(client.options.keyUp.isDown(), "wrong owner cannot release");
        try { ScriptActions.handle(client, Map.of("command", "script.move", "owner", other, "sprint", "true"), response); throw new AssertionError("concurrent owner accepted"); } catch (java.io.IOException error) { check(error.getMessage().equals("control.busy"), "busy lease"); }
        long end = System.nanoTime() + 3_000_000_000L; while (client.options.keyUp.isDown() && System.nanoTime() < end) Thread.sleep(20); check(!client.options.keyUp.isDown() && !client.options.keySprint.isDown(), "orphaned input expires");
        client.options.keyUp.setDown(true); try { ScriptActions.handle(client, Map.of("command", "script.move", "owner", owner, "sprint", "false"), response); throw new AssertionError("manual input overwritten"); } catch (java.io.IOException error) { check(error.getMessage().equals("control.key.already.held"), "manual input protected"); } client.options.keyUp.setDown(false);
        System.out.println("PASS: state, inventory, selection, rotation, ownership, input expiry and manual-input protection");
    }
    /// Fails a behavioral assertion independently of the JVM's optional -ea flag.
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
