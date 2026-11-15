package io.ochre.bridge;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;

/// Normal 26.2 client interactions and bounded loaded-world queries; never grants server abilities.
public final class ExtendedActions {
    /// Keys held by a script are independent from physical player input and expire on disconnect.
    private static final Map<Object, Boolean> keys = new IdentityHashMap<>();
    /// Lease identity and game-thread deadline.
    private static String owner = "";
    private static long expires;
    private static Executor executor;
    /// Owner-scoped crafting request, checked again before collecting its result.
    private static String craftOwner = "";
    private static Object craftMenu;
    private static long craftExpires;
    /// One outstanding watchdog task keeps a stalled client queue bounded.
    private static final java.util.concurrent.atomic.AtomicBoolean queued = new java.util.concurrent.atomic.AtomicBoolean();
    private static final ScheduledExecutorService WATCH = Executors.newSingleThreadScheduledExecutor(task -> { Thread thread = new Thread(task, "OchreBridge extended lease"); thread.setDaemon(true); return thread; });
    static { WATCH.scheduleWithFixedDelay(() -> { Executor target = executor; if (target != null && queued.compareAndSet(false, true)) try { target.execute(() -> { try { if (!owner.isEmpty() && System.nanoTime() > expires) release(); } catch (Exception ignored) { } finally { queued.set(false); } }); } catch (Exception ignored) { queued.set(false); } }, 100, 100, TimeUnit.MILLISECONDS); }
    private ExtendedActions() { }
    private static Object call(Object target, String name) throws Exception { return target.getClass().getMethod(name).invoke(target); }
    private static Object field(Object target, String name) throws Exception { return target.getClass().getField(name).get(target); }
    private static Class<?> type(Object client, String name) throws Exception { return Class.forName(name, false, client.getClass().getClassLoader()); }
    private static double number(Object target, String method) throws Exception { return ((Number) call(target, method)).doubleValue(); }
    private static int integer(Map<String, String> request, String name, int min, int max) throws IOException { try { int value = Integer.parseInt(request.getOrDefault(name, "")); if (value >= min && value <= max) return value; } catch (NumberFormatException ignored) { } throw new IOException("argument.invalid:" + name); }
    private static boolean flag(Map<String, String> request) throws IOException { String value = request.get("value"); if (!"true".equals(value) && !"false".equals(value)) throw new IOException("argument.invalid:value"); return Boolean.parseBoolean(value); }
    private static String owner(Map<String, String> request) throws IOException { String value = request.getOrDefault("owner", ""); if (!value.matches("[a-f0-9-]{36}")) throw new IOException("control.owner.invalid"); return value; }
    private static void interactive(Object client) throws Exception { if ((Boolean) call(client, "isPaused") || call(field(client, "gui"), "screen") != null) throw new IOException("game.control.menu.or.paused"); }
    /// Exposes actual player states; missing world state never becomes a fake false response.
    public static void state(Object player, Map<String, String> response) throws Exception {
        for (String name : List.of("Swimming", "Sprinting", "Crouching", "UnderWater", "FallFlying")) response.put("state." + name.toLowerCase(Locale.ROOT), call(player, "is" + name).toString());
        try { response.put("state.jumping", call(player, "isJumping").toString()); } catch (NoSuchMethodException ignored) { }
        response.put("state.on_ground", call(player, "onGround").toString()); response.put("state.selected", call(call(player, "getInventory"), "getSelectedSlot").toString()); Object abilities = call(player, "getAbilities"); response.put("state.flying", field(abilities, "flying").toString()); response.put("state.may_fly", field(abilities, "mayfly").toString());
    }
    /// Dispatches allowlisted commands, on the Minecraft client thread only.
    public static void handle(Object client, Map<String, String> request, Map<String, String> response) throws Exception {
        String command = request.getOrDefault("command", "");
        if (command.equals("script.state.heartbeat")) { if (owner.equals(owner(request)) && System.nanoTime() <= expires) expires = System.nanoTime() + 750_000_000L; return; }
        if (command.equals("script.state.release")) { if (owner.equals(owner(request))) release(); return; }
        if (!Set.of("script.jump", "script.command", "script.effects", "script.state.set", "script.hotbar", "script.inventory.move", "script.recipes", "script.craft.place", "script.craft.ready", "script.craft.take", "script.nearest").contains(command)) throw new IOException("command.unsupported");
        Object player = field(client, "player"); if (player == null) throw new IOException("game.not.in.world");
        switch (command) {
            case "script.jump" -> { interactive(client); if (!(Boolean) call(player, "onGround")) throw new IOException("jump.not.on.ground"); call(player, "jumpFromGround"); }
            case "script.command" -> { String text = request.getOrDefault("text", ""); if (text.startsWith("/")) text = text.substring(1); if (text.isBlank() || text.length() > 256 || text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0) throw new IOException("command.text.invalid"); Object connection = call(client, "getConnection"); if (connection == null) throw new IOException("game.not.connected"); connection.getClass().getMethod("sendCommand", String.class).invoke(connection, text); response.put("status", "submitted_to_server"); }
            case "script.effects" -> { Collection<?> effects = (Collection<?>) call(player, "getActiveEffects"); if (effects.size() > 64) throw new IOException("effects.limit"); response.put("count", Integer.toString(effects.size())); int i = 0; for (Object effect : effects) { String prefix = "effect." + i++ + "."; Object holder = call(effect, "getEffect"); Optional<?> key = (Optional<?>) call(holder, "unwrapKey"); if (key.isEmpty()) throw new IOException("effect.identifier.unavailable"); response.put(prefix + "id", call(key.get(), "identifier").toString()); response.put(prefix + "duration", call(effect, "getDuration").toString()); response.put(prefix + "amplifier", call(effect, "getAmplifier").toString()); } }
            case "script.hotbar" -> { interactive(client); int slot = integer(request, "slot", 0, 8); Object inventory = call(player, "getInventory"); inventory.getClass().getMethod("setSelectedSlot", int.class).invoke(inventory, slot); }
            case "script.state.set" -> setState(client, player, request, response);
            case "script.inventory.move" -> moveItem(client, player, request);
            case "script.recipes" -> recipes(player, response);
            case "script.craft.place" -> placeRecipe(client, player, request);
            case "script.craft.ready" -> { Object menu = field(player, "containerMenu"); if (!craftOwner.equals(owner(request)) || menu != craftMenu || System.nanoTime() > craftExpires) throw new IOException("craft.request.expired.or.menu.changed"); Object output = menu.getClass().getMethod("getSlot", int.class).invoke(menu, 0); response.put("ready", Boolean.toString(!(Boolean) call(call(output, "getItem"), "isEmpty"))); }
            case "script.craft.take" -> takeRecipe(client, player, request);
            case "script.nearest" -> nearest(client, player, request, response);
            default -> throw new IOException("command.unsupported");
        }
    }
    private static void key(Object key, boolean down) throws Exception { if ((Boolean) call(key, "isDown") == down) return; key.getClass().getMethod("setDown", boolean.class).invoke(key, down); if ((Boolean) call(key, "isDown") != down) key.getClass().getMethod("setDown", boolean.class).invoke(key, true); if ((Boolean) call(key, "isDown") != down) throw new IOException("control.key.mode.unsupported"); }
    private static void release() throws Exception { for (Object key : List.copyOf(keys.keySet())) { key(key, false); keys.remove(key); } owner = ""; expires = 0; }
    private static void setState(Object client, Object player, Map<String, String> request, Map<String, String> response) throws Exception {
        interactive(client); String name = request.getOrDefault("name", ""); boolean value = flag(request);
        if (name.equals("flying")) { Object abilities = call(player, "getAbilities"); if (!(Boolean) field(abilities, "mayfly")) throw new IOException("permission.flight.not.granted.by.server"); abilities.getClass().getField("flying").setBoolean(abilities, value); call(player, "onUpdateAbilities"); return; }
        if (name.equals("swimming")) { if (value && !(Boolean) call(player, "isInWater")) throw new IOException("swimming.requires.water"); player.getClass().getMethod("setSwimming", boolean.class).invoke(player, value); response.put("status", "requested;movement_and_server_may_override"); return; }
        if (!Set.of("crouching", "sprinting").contains(name)) throw new IOException("state.read.only.or.unsupported:" + name);
        String next = owner(request); if (!owner.isEmpty() && System.nanoTime() > expires) release(); if (!owner.isEmpty() && !owner.equals(next)) throw new IOException("control.busy");
        Object key = field(field(client, "options"), name.equals("crouching") ? "keyShift" : "keySprint");
        if (value) { if (!keys.containsKey(key) && (Boolean) call(key, "isDown")) throw new IOException("control.key.already.held"); if (name.equals("sprinting") && !(Boolean) call(player, "isCreative") && ((Number) call(call(player, "getFoodData"), "getFoodLevel")).intValue() <= 6) throw new IOException("sprint.requires.food"); owner = next; executor = (Executor) client; expires = System.nanoTime() + 750_000_000L; keys.put(key, true); key(key, true); response.put("leased", "true"); }
        else if (keys.containsKey(key) && owner.equals(next)) { key(key, false); keys.remove(key); if (keys.isEmpty()) owner = ""; }
    }
    private static Object inventoryMenu(Object player) throws Exception { Object menu = field(player, "containerMenu"); if (menu != field(player, "inventoryMenu")) throw new IOException("inventory.external.container.open"); if (!(Boolean) call(call(menu, "getCarried"), "isEmpty")) throw new IOException("inventory.cursor.not.empty"); return menu; }
    private static Object inventorySlot(Object player, Object menu, int index) throws Exception { Object inventory = call(player, "getInventory"); for (Object slot : (List<?>) field(menu, "slots")) if (field(slot, "container") == inventory && ((Number) call(slot, "getContainerSlot")).intValue() == index) return slot; throw new IOException("inventory.slot.unavailable"); }
    private static void click(Object client, Object player, Object menu, int slot, String action) throws Exception { Class<?> input = type(client, "net.minecraft.world.inventory.ContainerInput"); Object value = input.getField(action).get(null); Object mode = field(client, "gameMode"); mode.getClass().getMethod("handleContainerInput", int.class, int.class, int.class, input, type(client, "net.minecraft.world.entity.player.Player")).invoke(mode, ((Number) field(menu, "containerId")).intValue(), slot, 0, value, player); }
    private static void moveItem(Object client, Object player, Map<String, String> request) throws Exception {
        int from = integer(request, "from", 0, 35), to = integer(request, "to", 0, 35); if (from == to) throw new IOException("inventory.same.slot"); Object menu = inventoryMenu(player); Object source = inventorySlot(player, menu, from), target = inventorySlot(player, menu, to), stack = call(source, "getItem");
        if ((Boolean) call(stack, "isEmpty")) throw new IOException("inventory.source.empty"); if (!(Boolean) call(call(target, "getItem"), "isEmpty")) throw new IOException("inventory.target.must.be.empty");
        if (!(Boolean) source.getClass().getMethod("mayPickup", type(client, "net.minecraft.world.entity.player.Player")).invoke(source, player) || !(Boolean) target.getClass().getMethod("mayPlace", type(client, "net.minecraft.world.item.ItemStack")).invoke(target, stack)) throw new IOException("permission.inventory.slot.denied");
        click(client, player, menu, ((Number) field(source, "index")).intValue(), "PICKUP");
        try { click(client, player, menu, ((Number) field(target, "index")).intValue(), "PICKUP"); }
        finally { if (!(Boolean) call(call(menu, "getCarried"), "isEmpty")) click(client, player, menu, ((Number) field(source, "index")).intValue(), "PICKUP"); }
    }
    private static List<Object> entries(Object player) throws Exception { List<Object> entries = new ArrayList<>(); for (Object collection : (List<?>) call(call(player, "getRecipeBook"), "getCollections")) for (Object entry : (List<?>) call(collection, "getRecipes")) { entries.add(entry); if (entries.size() > 4096) throw new IOException("recipe.limit"); } return entries; }
    private static void recipes(Object player, Map<String, String> response) throws Exception { List<Object> recipes = entries(player); int size = Math.min(64, recipes.size()); response.put("count", Integer.toString(size)); for (int i = 0; i < size; i++) response.put("recipe." + i + ".display_id", call(call(recipes.get(i), "id"), "index").toString()); response.put("truncated", Boolean.toString(recipes.size() > size)); }
    private static void placeRecipe(Object client, Object player, Map<String, String> request) throws Exception {
        String next = owner(request); int id = integer(request, "display_id", 0, Integer.MAX_VALUE); Object selected = null; for (Object entry : entries(player)) if (((Number) call(call(entry, "id"), "index")).intValue() == id) { selected = entry; break; } if (selected == null) throw new IOException("recipe.not.unlocked");
        Object menu = field(player, "containerMenu"); String kind = menu.getClass().getName(); if (!Set.of("net.minecraft.world.inventory.InventoryMenu", "net.minecraft.world.inventory.CraftingMenu").contains(kind)) throw new IOException("craft.requires.inventory.or.crafting.table"); if (!(Boolean) call(call(menu, "getCarried"), "isEmpty")) throw new IOException("inventory.cursor.not.empty"); Object output = menu.getClass().getMethod("getSlot", int.class).invoke(menu, 0); if (!(Boolean) call(call(output, "getItem"), "isEmpty")) throw new IOException("craft.clear.previous.output.first");
        Object mode = field(client, "gameMode"), display = call(selected, "id"); mode.getClass().getMethod("handlePlaceRecipe", int.class, type(client, "net.minecraft.world.item.crafting.display.RecipeDisplayId"), boolean.class).invoke(mode, ((Number) field(menu, "containerId")).intValue(), display, false);
        craftMenu = menu; craftOwner = next; craftExpires = System.nanoTime() + 3_000_000_000L;
    }
    private static void takeRecipe(Object client, Object player, Map<String, String> request) throws Exception {
        Object menu = field(player, "containerMenu"); if (!craftOwner.equals(owner(request)) || menu != craftMenu || System.nanoTime() > craftExpires) throw new IOException("craft.request.expired.or.menu.changed");
        if (!(Boolean) call(call(menu, "getCarried"), "isEmpty")) throw new IOException("inventory.cursor.not.empty"); Object output = menu.getClass().getMethod("getSlot", int.class).invoke(menu, 0); if ((Boolean) call(call(output, "getItem"), "isEmpty")) throw new IOException("craft.no.result.check.materials_and_grid_size");
        click(client, player, menu, 0, "QUICK_MOVE"); craftOwner = ""; craftMenu = null;
    }
    private static double distance(Object self, double x, double y, double z) throws Exception { return Math.sqrt(Math.pow(x - number(self, "getX"), 2) + Math.pow(y - number(self, "getY"), 2) + Math.pow(z - number(self, "getZ"), 2)); }
    private static void position(Object self, double x, double y, double z, Map<String, String> response) throws Exception { response.put("found", "true"); response.put("x", Double.toString(x)); response.put("y", Double.toString(y)); response.put("z", Double.toString(z)); double dx = x - number(self, "getX"), dy = y - number(self, "getEyeY"), dz = z - number(self, "getZ"); response.put("distance", Double.toString(distance(self, x, y, z))); response.put("yaw", Double.toString(Math.toDegrees(Math.atan2(dz, dx)) - 90)); response.put("pitch", Double.toString(-Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz))))); }
    private static String identifier(Object object) throws Exception { return call(call(call(object, "builtInRegistryHolder"), "key"), "identifier").toString(); }
    private static void nearest(Object client, Object self, Map<String, String> request, Map<String, String> response) throws Exception {
        String kind = request.getOrDefault("kind", "entity"), wanted = request.getOrDefault("type", ""); int radius = integer(request, "radius", 1, kind.equals("block") ? 16 : 64); Object level = field(client, "level"); if (level == null) throw new IOException("game.not.in.world"); response.put("found", "false");
        if (kind.equals("block")) {
            if (!wanted.matches("[a-z0-9_.-]+:[a-z0-9_/.-]+")) throw new IOException("block.requires.registry.identifier"); Class<?> pos = type(client, "net.minecraft.core.BlockPos"); int sx = (int) Math.floor(number(self, "getX")), sy = (int) Math.floor(number(self, "getY")), sz = (int) Math.floor(number(self, "getZ")); double px = number(self, "getX"), py = number(self, "getY"), pz = number(self, "getZ"); double best = radius + .001; Object found = null;
            var constructor = pos.getConstructor(int.class, int.class, int.class); var hasChunk = level.getClass().getMethod("hasChunkAt", pos); var getState = level.getClass().getMethod("getBlockState", pos); Map<Object, String> identifiers = new IdentityHashMap<>();
            for (int x = sx-radius; x <= sx+radius; x++) for (int y = sy-radius; y <= sy+radius; y++) for (int z = sz-radius; z <= sz+radius; z++) { double dx=x+.5-px, dy=y+.5-py, dz=z+.5-pz; double distance = Math.sqrt(dx*dx+dy*dy+dz*dz); if (distance >= best) continue; Object location = constructor.newInstance(x,y,z); if (!(Boolean) hasChunk.invoke(level, location)) continue; Object block = call(getState.invoke(level, location), "getBlock"); String id = identifiers.get(block); if (id == null) { id = identifier(block); identifiers.put(block, id); } if (wanted.equals(id)) { best = distance; found = location; } }
            if (found != null) { position(self, number(found, "getX")+.5, number(found, "getY")+.5, number(found, "getZ")+.5, response); response.put("type", wanted); }
        } else {
            if (!kind.equals("entity") && !kind.equals("player")) throw new IOException("target.kind.invalid"); Object best = null; double closest = radius + .001; int inspected = 0;
            Iterable<?> entities = kind.equals("player") ? (Iterable<?>) call(level, "players") : (Iterable<?>) call(level, "entitiesForRendering");
            for (Object target : entities) { if (++inspected > 4096) break; if (target == self || (Boolean) call(target, "isRemoved")) continue; String id = identifier(call(target, "getType")); if (!wanted.isEmpty() && !wanted.equals(id)) continue; double distance = distance(self, number(target,"getX"), number(target,"getY"), number(target,"getZ")); if (distance < closest) { closest = distance; best = target; } }
            if (best != null) { position(self, number(best,"getX"), number(best,"getY"), number(best,"getZ"), response); response.put("id", call(best,"getUUID").toString()); response.put("name", call(call(best,"getName"),"getString").toString()); response.put("type", identifier(call(best,"getType"))); }
        }
    }
}
