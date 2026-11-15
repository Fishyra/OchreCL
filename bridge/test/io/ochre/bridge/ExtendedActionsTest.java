package io.ochre.bridge;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.Executor;

/// Tests permission, input ownership and normal client submissions without a real world or account.
public final class ExtendedActionsTest {
    public static final class Gui { public Object screen() { return null; } }
    public static final class Key { boolean down; public boolean isDown() { return down; } public void setDown(boolean value) { down = value; } }
    public static final class Options { public final Key keyShift = new Key(), keySprint = new Key(); }
    public static final class Abilities { public boolean mayfly, flying; }
    public static final class Inventory { int slot; public void setSelectedSlot(int value) { slot = value; } public int getSelectedSlot() { return slot; } }
    public static final class Food { public int getFoodLevel() { return 20; } }
    public static final class RecipeBook { public List<Object> getCollections() { return List.of(); } }
    public static final class Connection { String command = ""; public void sendCommand(String value) { command = value; } }
    public static final class Player {
        final Abilities abilities = new Abilities(); final Inventory inventory = new Inventory(); boolean grounded = true; int jumps, updates;
        public final Object inventoryMenu = new Object(), containerMenu = new Object();
        public boolean onGround() { return grounded; } public void jumpFromGround() { jumps++; }
        public Abilities getAbilities() { return abilities; } public void onUpdateAbilities() { updates++; }
        public Inventory getInventory() { return inventory; } public Food getFoodData() { return new Food(); }
        public boolean isCreative() { return false; } public boolean isInWater() { return false; }
        public boolean isSwimming() { return false; } public boolean isSprinting() { return false; }
        public boolean isCrouching() { return false; } public boolean isUnderWater() { return false; } public boolean isFallFlying() { return false; }
        public Collection<Object> getActiveEffects() { return List.of(); } public RecipeBook getRecipeBook() { return new RecipeBook(); }
    }
    public static final class Client implements Executor {
        public Player player = new Player(); public final Options options = new Options(); public final Gui gui = new Gui(); public final Connection connection = new Connection(); boolean paused;
        public boolean isPaused() { return paused; } public Connection getConnection() { return connection; } public void execute(Runnable work) { work.run(); }
    }
    public static void main(String[] args) throws Exception {
        Client client = new Client(); Map<String, String> result = new HashMap<>(); String owner = UUID.randomUUID().toString(), other = UUID.randomUUID().toString();
        ExtendedActions.handle(client, Map.of("command", "script.jump"), result); check(client.player.jumps == 1, "grounded jump");
        client.player.grounded = false; rejected(client, Map.of("command", "script.jump"), "jump.not.on.ground");
        client.paused = true; rejected(client, Map.of("command", "script.hotbar", "slot", "2"), "game.control.menu.or.paused"); client.paused = false;
        rejected(client, Map.of("command", "script.state.set", "name", "flying", "value", "true"), "permission.flight.not.granted.by.server"); check(!client.player.abilities.flying, "no fabricated flight permission");
        client.player.abilities.mayfly = true; ExtendedActions.handle(client, Map.of("command", "script.state.set", "name", "flying", "value", "true"), result); check(client.player.abilities.flying && client.player.updates == 1, "authorized ability update");
        ExtendedActions.handle(client, Map.of("command", "script.hotbar", "slot", "8"), result); check(client.player.inventory.slot == 8, "hotbar switch"); rejected(client, Map.of("command", "script.hotbar", "slot", "9"), "argument.invalid:slot");
        ExtendedActions.handle(client, Map.of("command", "script.command", "text", "/time query daytime"), result); check(client.connection.command.equals("time query daytime") && result.get("status").equals("submitted_to_server"), "command sent without fabricated success"); rejected(client, Map.of("command", "script.command", "text", "say a\nsay b"), "command.text.invalid");
        ExtendedActions.handle(client, Map.of("command", "script.effects"), result); check(result.get("count").equals("0"), "empty effects"); ExtendedActions.handle(client, Map.of("command", "script.recipes"), result); check(result.get("count").equals("0"), "no invented recipes");
        rejected(client, Map.of("command", "script.craft.place", "owner", owner, "display_id", "1"), "recipe.not.unlocked");
        rejected(client, Map.of("command", "script.inventory.move", "from", "0", "to", "1"), "inventory.external.container.open");
        ExtendedActions.handle(client, Map.of("command", "script.state.set", "owner", owner, "name", "crouching", "value", "true"), result); check(client.options.keyShift.down, "modifier acquired");
        rejected(client, Map.of("command", "script.state.set", "owner", other, "name", "crouching", "value", "true"), "control.busy");
        ExtendedActions.handle(client, Map.of("command", "script.state.release", "owner", other), result); check(client.options.keyShift.down, "foreign owner cannot release");
        long deadline = System.nanoTime() + 3_000_000_000L; while (client.options.keyShift.down && System.nanoTime() < deadline) Thread.sleep(20); check(!client.options.keyShift.down, "orphaned state input expires");
        ExtendedActions.handle(client, Map.of("command", "script.state.heartbeat", "owner", owner), result); check(!client.options.keyShift.down, "heartbeat cannot reacquire expired lease");
        client.options.keyShift.down = true; rejected(client, Map.of("command", "script.state.set", "owner", owner, "name", "crouching", "value", "true"), "control.key.already.held"); client.options.keyShift.down = false;
        client.player = null; rejected(client, Map.of("command", "script.jump"), "game.not.in.world");
        System.out.println("PASS: jump, flight permissions, hotbar, command submission, inventory/recipe rejection, state leases and cleanup");
    }
    private static void rejected(Client client, Map<String, String> request, String expected) throws Exception { try { ExtendedActions.handle(client, request, new HashMap<>()); throw new AssertionError("Accepted " + expected); } catch (IOException error) { check(expected.equals(error.getMessage()), "Expected " + expected + ", got " + error.getMessage()); } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
