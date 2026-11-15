package org.jackhuang.hmcl.script;

import org.jackhuang.hmcl.bridge.BridgeManager;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import java.util.*;

/// Adapts registered DSL operations to authenticated, instance-scoped game-thread bridge commands.
@NotNullByDefault
public final class ScriptGameHost implements OchreScript.Host {
    /// Running game instance directory, never a user-provided network address.
    private final String key;
    /// Unique ownership for temporary movement input; other runs cannot release these keys.
    private final String owner = UUID.randomUUID().toString();
    /// Records whether this run acquired temporary input.
    private boolean acquired;
    /// Caps one run at fifty bridge requests per second, including reads inside tight loops.
    private long lastRequest;
    /// Refreshes leased modifier keys without tying their lifetime to an individual sleep statement.
    private static final java.util.concurrent.ScheduledExecutorService LEASES = java.util.concurrent.Executors.newScheduledThreadPool(1, task -> { Thread thread = new Thread(task, "OchreScript state lease"); thread.setDaemon(true); return thread; });
    /// No heartbeat can reacquire a released agent lease.
    private volatile boolean closed;
    /// Active key state heartbeat, absent until this script requests one.
    private java.util.concurrent.@Nullable ScheduledFuture<?> heartbeat;
    /// Supported setting names and their bridge protocol keys.
    private static final @Unmodifiable Map<String, String> OPTIONS = Map.of("settings.audio.master", "soundCategory_master", "settings.audio.music", "soundCategory_music", "settings.audio.weather", "soundCategory_weather", "settings.video.render_distance", "renderDistance", "settings.video.max_fps", "maxFps", "settings.video.gamma", "gamma", "settings.video.fov", "fov", "settings.video.fullscreen", "fullscreen");
    /// Production UI asks before closing a game interface; headless callers explicitly decline.
    @NotNullByDefault public interface MenuConsent { /// Returns true only after an explicit player decision.
        boolean confirm(String screen, OchreScript.Control control) throws Exception;
    }
    /// Consent provider belongs to the actual script workspace.
    private final MenuConsent consent;
    /// Only the executing script thread may open an interactive consent prompt.
    private final ThreadLocal<OchreScript.@Nullable Control> currentControl = new ThreadLocal<>();
    /// Game-thread control submissions require an unobstructed world; raw input and crafting UI are intentional exceptions.
    private static final @Unmodifiable Set<String> GAMEPLAY_COMMANDS = Set.of("script.look", "script.move", "script.hit", "script.jump", "script.state.set", "script.hotbar", "script.block.mine", "script.block.place");
    /// Binds the adapter to the explicitly selected instance.
    public ScriptGameHost(String key) { this(key, (screen, control) -> false); }
    /// Binds an explicit confirmation provider without granting scripts a way to approve their own prompts.
    public ScriptGameHost(String key, MenuConsent consent) { this.key = key; this.consent = consent; this.foundations = new ScriptFoundationHost(this, key, owner); }
    /// Additional block, observation and game-local input capabilities owned by this run.
    private final ScriptFoundationHost foundations;
    /// Uses the existing private bridge authentication and checks game-version compatibility on every operation.
    synchronized @Unmodifiable Map<String, String> request(String command, Map<String, String> arguments) throws Exception { @Nullable var active = currentControl.get(); if (active != null && GAMEPLAY_COMMANDS.contains(command)) ensureGameplay(active); long remaining = 20_000_000L - (System.nanoTime() - lastRequest); if (remaining > 0) Thread.sleep(Math.max(1, remaining / 1_000_000L)); lastRequest = System.nanoTime(); try { return BridgeManager.request(key, command, arguments); } catch (java.io.IOException error) { @Nullable var control = currentControl.get(); if (control == null || error.getMessage() == null || !error.getMessage().contains("game.control.menu.or.paused")) throw error; ensureGameplay(control); return BridgeManager.request(key, command, arguments); } }
    /// Pauses before world controls and exits game UI only after explicit, world-bound player consent.
    private void ensureGameplay(OchreScript.Control control) throws Exception {
        var context = request("script.context", Map.of()); if ("false".equals(context.get("in_world"))) throw new IllegalArgumentException("game.not.in.world");
        if (!"true".equals(context.get("menu")) && !"true".equals(context.get("paused"))) return;
        control.beginConfirmation();
        try { request("script.release", Map.of("owner", owner)); request("script.input.release", Map.of("owner", owner)); request("script.state.release", Map.of("owner", owner)); request("script.block.stop", Map.of("owner", owner));
            if (!consent.confirm(context.getOrDefault("screen", "game interface"), control)) throw new java.util.concurrent.CancellationException("Player stopped the script at a game interface");
            control.check(); var current = request("script.context", Map.of()); if (!required(context, "world").equals(required(current, "world"))) throw new IllegalArgumentException("Target world changed while paused"); if ("true".equals(current.get("menu"))) request("script.ui.close", Map.of("screen_token", required(context, "screen_token"), "world", required(context, "world")));
        } finally { control.endConfirmation(); }
        request("script.window.focus", Map.of()); control.sleep(.05); var after = request("script.context", Map.of()); if ("true".equals(after.get("menu")) || "true".equals(after.get("paused"))) throw new IllegalArgumentException("Game interface is still open; script remains stopped");
    }
    /// Reads a required protocol value without silently substituting a fabricated game state.
    private static String required(Map<String, String> snapshot, String field) { @Nullable String value = snapshot.get(field); if (value == null) throw new IllegalArgumentException("Game capability unavailable: " + field); return value; }
    /// Reconstructs a numeric record from the bridge snapshot.
    private static @Unmodifiable Map<String, Object> vector(Map<String, String> snapshot, String x, String y, String z) { return Map.of("x", Double.parseDouble(required(snapshot, x)), "y", Double.parseDouble(required(snapshot, y)), "z", Double.parseDouble(required(snapshot, z))); }
    /// Reads current settings, state, inventory or the script-local running flag.
    @Override public Object read(String path) throws Exception {
        if (path.equals("script.running")) return true;
        if (Set.of("player.vitals", "player.container", "game.screen", "game.observation_status").contains(path)) { String kind = switch(path) {case "player.vitals"->"vitals";case "player.container"->"container";case "game.screen"->"screen";default->"status";}; return ScriptFoundationHost.record(request("script.inspect", Map.of("kind",kind))); }
        if (path.equals("player.inputs")) { var data = request("state.read", Map.of()); Map<String, Object> values = new LinkedHashMap<>(); for (String name : List.of("forward", "backward", "left", "right", "jump", "sneak", "sprint", "attack", "use")) values.put(name, Boolean.parseBoolean(required(data, "input." + name))); return Map.copyOf(values); }
        if (path.equals("player.states")) { var state = request("state.read", Map.of()); Map<String, Object> values = new LinkedHashMap<>(); for (String field : List.of("swimming", "sprinting", "crouching", "underwater", "fallflying", "on_ground", "flying", "may_fly", "jumping")) values.put(field, Boolean.parseBoolean(required(state, "state." + field))); return Map.copyOf(values); }
        if (path.equals("player.hotbar")) return Double.parseDouble(required(request("state.read", Map.of()), "state.selected"));
        if (path.equals("player.effects") || path.equals("player.recipes")) { boolean effects = path.equals("player.effects"); var data = request(effects ? "script.effects" : "script.recipes", Map.of()); int count = Integer.parseInt(required(data, "count")); if (count < 0 || count > 64) throw new IllegalArgumentException("Invalid result size"); List<Object> values = new ArrayList<>(); for (int i = 0; i < count; i++) { String prefix = (effects ? "effect." : "recipe.") + i + "."; values.add(effects ? Map.of("id", required(data, prefix + "id"), "duration", Double.parseDouble(required(data, prefix + "duration")), "amplifier", Double.parseDouble(required(data, prefix + "amplifier"))) : Map.of("display_id", Double.parseDouble(required(data, prefix + "display_id")))); } return List.copyOf(values); }
        if (OPTIONS.containsKey(path)) { String name = OPTIONS.get(path), value = required(request("options.get", Map.of()), name); if (name.equals("fullscreen")) return Boolean.parseBoolean(value); double number = Double.parseDouble(value); return name.equals("fov") ? 70 + 40 * number : number; }
        if (path.equals("player.inventory.slots")) { var data = request("script.inventory", Map.of()); List<Object> slots = new ArrayList<>(); int count = Integer.parseInt(required(data, "slots")); if (count < 0 || count > 54) throw new IllegalArgumentException("Invalid inventory size"); for (int i = 0; i < count; i++) { String prefix = "slot." + i; if ("0".equals(required(data, prefix + ".count"))) slots.add(OchreScript.Empty.NONE); else slots.add(Map.of("slot", (double) i, "id", required(data, prefix + ".id"), "count", Double.parseDouble(required(data, prefix + ".count")))); } return List.copyOf(slots); }
        var state = request("state.read", Map.of()); return switch (path) { case "game.language" -> required(state, "language"); case "player.position" -> vector(state, "player.X", "player.Y", "player.Z"); case "player.rotation" -> Map.of("yaw", Double.parseDouble(required(state, "player.YRot")), "pitch", Double.parseDouble(required(state, "player.XRot"))); case "player.health" -> Double.parseDouble(required(state, "player.health")); case "world.day_time" -> Double.parseDouble(required(state, "world.day_time")); case "world.game_time" -> Double.parseDouble(required(state, "world.game_time")); default -> throw new IllegalArgumentException("Unknown game property: " + path); };
    }
    /// Writes one whitelisted setting, preserving the game bridge's range checks and persistence behavior.
    @Override public void write(String path, Object value) throws Exception { @Nullable String name = OPTIONS.get(path); if (name == null) throw new IllegalArgumentException("Setting is not writable: " + path); String text; if (name.equals("fullscreen")) { if (!(value instanceof Boolean)) throw new IllegalArgumentException("fullscreen expects a bool"); text = value.toString(); } else { double number = OchreScript.number(value); text = Double.toString(name.equals("fov") ? (number - 70) / 40 : number); } request("options.set", Map.of("option." + name, text)); }
    /// Validates argument names and positional order before any game side effect.
    private static @Unmodifiable Map<String, Object> bind(List<Object> positional, Map<String, Object> named, String... names) { if (positional.size() > names.length) throw new IllegalArgumentException("Too many arguments"); Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < positional.size(); i++) result.put(names[i], positional.get(i)); for (var item : named.entrySet()) { if (!Arrays.asList(names).contains(item.getKey())) throw new IllegalArgumentException("Unknown argument: " + item.getKey()); if (result.putIfAbsent(item.getKey(), item.getValue()) != null) throw new IllegalArgumentException("Duplicate argument: " + item.getKey()); } return Map.copyOf(result); }
    /// Returns a mandatory argument rather than accepting an absent target.
    private static Object arg(Map<String, Object> args, String name) { @Nullable Object value = args.get(name); if (value == null) throw new IllegalArgumentException("Missing argument: " + name); return value; }
    /// Converts a duration to seconds with a caller-chosen fallback.
    private static double duration(Map<String, Object> args, String name, double fallback) { return args.containsKey(name) ? OchreScript.quantity(arg(args, name), "time") : fallback; }
    /// Runs ordinary client actions and submits commands under the actual server permission rules.
    @Override public Object call(String name, List<Object> positional, Map<String, Object> named, OchreScript.Control control) throws Exception {
        @Nullable var before = currentControl.get(); currentControl.set(control);
        try { return invoke(name, positional, named, control); }
        finally { if (before == null) currentControl.remove(); else currentControl.set(before); }
    }
    /// Dispatches validated actions after any gameplay precondition has been resolved.
    private Object invoke(String name, List<Object> positional, Map<String, Object> named, OchreScript.Control control) throws Exception {
        if (ScriptFoundationHost.supports(name)) return foundations.call(name, positional, named, control);
        if (name.equals("say")) {
            var args = bind(positional, named, "text", "duration"); Object value = arg(args, "text");
            if (!(value instanceof String text) || text.isBlank() || text.length() > 256) throw new IllegalArgumentException("say text must contain 1..256 characters");
            double seconds = duration(args, "duration", 3); if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 30) throw new IllegalArgumentException("say duration must be in (0s, 30s]");
            control.check(); request("overlay.say", Map.of("text", text, "duration", Double.toString(seconds))); control.sleep(seconds); return OchreScript.Empty.NONE;
        }
        if (Set.of("player.jump", "game.command", "player.select_hotbar", "player.move_item", "player.set_state", "player.craft").contains(name)) {
            if (name.equals("player.jump")) { bind(positional, named); request("script.jump", Map.of()); return OchreScript.Empty.NONE; }
            if (name.equals("game.command")) { var args = bind(positional, named, "text"); return required(request("script.command", Map.of("text", String.valueOf(arg(args, "text")))), "status"); }
            if (name.equals("player.select_hotbar")) { var args = bind(positional, named, "slot"); request("script.hotbar", Map.of("slot", integer(arg(args, "slot"), 0, 8))); return OchreScript.Empty.NONE; }
            if (name.equals("player.move_item")) { var args = bind(positional, named, "from", "to"); request("script.inventory.move", Map.of("from", integer(arg(args, "from"), 0, 35), "to", integer(arg(args, "to"), 0, 35))); return OchreScript.Empty.NONE; }
            if (name.equals("player.set_state")) { var args = bind(positional, named, "name", "value"); Object value = arg(args, "value"); if (!(value instanceof Boolean)) throw new IllegalArgumentException("State value expects bool"); var result = request("script.state.set", Map.of("owner", owner, "name", String.valueOf(arg(args, "name")), "value", value.toString())); if ("true".equals(result.get("leased")) && heartbeat == null) heartbeat = LEASES.scheduleWithFixedDelay(() -> { if (!closed) try { request("script.state.heartbeat", Map.of("owner", owner)); } catch (Exception ignored) { } }, 150, 150, java.util.concurrent.TimeUnit.MILLISECONDS); return result.getOrDefault("status", "applied"); }
            var args = bind(positional, named, "display_id"); request("script.craft.place", Map.of("display_id", integer(arg(args, "display_id"), 0, Integer.MAX_VALUE), "owner", owner)); long deadline = control.now() + 2_500_000_000L; while (!"true".equals(request("script.craft.ready", Map.of("owner", owner)).get("ready"))) { if (control.now() >= deadline) throw new IllegalArgumentException("Crafting timed out; check materials and grid size"); control.sleep(.1); } control.check(); request("script.craft.take", Map.of("owner", owner)); return "submitted_to_server";
        }
        if (name.startsWith("world.nearest_")) {
            boolean block = name.equals("world.nearest_block"), player = name.equals("world.nearest_player"); var args = player ? bind(positional, named, "radius") : bind(positional, named, "type", "radius");
            String radius = integer(args.getOrDefault("radius", 16.0), 1, block ? 16 : 64), target = block ? String.valueOf(arg(args, "type")) : String.valueOf(args.getOrDefault("type", ""));
            var data = request("script.nearest", Map.of("kind", block ? "block" : player ? "player" : "entity", "type", target, "radius", radius)); if (!"true".equals(required(data, "found"))) return OchreScript.Empty.NONE;
            Map<String, Object> record = new LinkedHashMap<>(); record.put("position", vector(data, "x", "y", "z")); for (String field : List.of("distance", "yaw", "pitch")) record.put(field, Double.parseDouble(required(data, field))); for (String field : List.of("id", "name", "type")) if (data.containsKey(field)) record.put(field, required(data, field)); return Map.copyOf(record);
        }
        if (name.equals("player.look_at_block")) {
            var args = bind(positional, named, "position", "face", "duration"); Object value = arg(args, "position"); if (!(value instanceof Map<?, ?> position)) throw new IllegalArgumentException("Expected Vec3 block position");
            double x = Math.floor(OchreScript.number(position.get("x"))) + .5, y = Math.floor(OchreScript.number(position.get("y"))) + .5, z = Math.floor(OchreScript.number(position.get("z"))) + .5;
            switch (String.valueOf(arg(args, "face"))) { case "north" -> z -= .499; case "south" -> z += .499; case "east" -> x += .499; case "west" -> x -= .499; case "top", "up" -> y += .499; case "bottom", "down" -> y -= .499; default -> throw new IllegalArgumentException("Unknown block face"); }
            return call("player.look_at", List.of(Map.of("x", x, "y", y, "z", z)), args.containsKey("duration") ? Map.of("duration", arg(args, "duration")) : Map.of(), control);
        }
        if (name.equals("world.find_player") || name.equals("world.nearby_players")) { boolean find = name.equals("world.find_player"); var args = bind(positional, named, find ? "name" : "radius"); double radius = find ? 128 : args.containsKey("radius") ? OchreScript.number(arg(args, "radius")) : 16; if (radius <= 0 || radius > 128) throw new IllegalArgumentException("Radius must be in (0, 128]"); String target = find ? String.valueOf(arg(args, "name")) : ""; var snapshot = request("script.players", Map.of("radius", Double.toString(radius), "name", target)); List<Object> players = new ArrayList<>(); int count = Integer.parseInt(required(snapshot, "players")); if (count < 0 || count > 16) throw new IllegalArgumentException("Invalid player list"); for (int i = 0; i < count; i++) { String p = "entity." + i + "."; players.add(Map.of("id", required(snapshot, p + "id"), "name", required(snapshot, p + "name"), "position", vector(snapshot, p + "x", p + "y", p + "z"))); } return find ? players.isEmpty() ? OchreScript.Empty.NONE : players.get(0) : List.copyOf(players); }
        if (name.equals("player.walk_forward") || name.equals("player.run_forward")) { var args = bind(positional, named, "amount", "timeout"); Object value = arg(args, "amount"); if (!(value instanceof OchreScript.Quantity amount) || !Set.of("time", "distance").contains(amount.dimension()) || amount.value() <= 0 || amount.value() > (amount.dimension().equals("time") ? 60 : 128)) throw new IllegalArgumentException("Expected 0..60s or 0..128blocks"); double timeout = duration(args, "timeout", amount.dimension().equals("time") ? amount.value() + 2 : 30); if (timeout <= 0 || timeout > 60) throw new IllegalArgumentException("Timeout must be in (0s, 60s]"); var initial = request("state.read", Map.of()); double x = Double.parseDouble(required(initial, "player.X")), z = Double.parseDouble(required(initial, "player.Z")); long started = control.now(); try { while (true) { control.check(); double elapsed = (control.now() - started) / 1e9; if (amount.dimension().equals("time") && elapsed >= amount.value()) break; if (elapsed >= timeout) throw new IllegalArgumentException("Movement timed out; no teleport was performed"); var state = request("state.read", Map.of()); if (amount.dimension().equals("distance") && Math.hypot(Double.parseDouble(required(state, "player.X")) - x, Double.parseDouble(required(state, "player.Z")) - z) >= amount.value()) break; request("script.move", Map.of("owner", owner, "sprint", Boolean.toString(name.equals("player.run_forward")))); acquired = true; control.sleep(.1); } } finally { release(); } return OchreScript.Empty.NONE; }
        if (name.equals("player.walk_to")) throw new IllegalArgumentException("Capability unavailable: walk_to requires a pathfinding adapter; no teleport was performed");
        if (name.equals("player.look") || name.equals("player.look_at")) { boolean target = name.equals("player.look_at"); var args = target ? bind(positional, named, "target", "point", "duration") : bind(positional, named, "yaw", "pitch", "duration"); double seconds = duration(args, "duration", 0); if (seconds < 0 || seconds > 10) throw new IllegalArgumentException("Look duration must be in [0s, 10s]"); Map<String, String> fields = new LinkedHashMap<>(); if (target) { Object value = arg(args, "target"); if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Target is missing; check target != none"); if (map.containsKey("id")) fields.put("target", String.valueOf(map.get("id"))); else { for (String axis : List.of("x", "y", "z")) { @Nullable Object coordinate = map.get(axis); if (coordinate == null) throw new IllegalArgumentException("Expected Player or Vec3"); fields.put(axis, Double.toString(OchreScript.number(coordinate))); } } fields.put("point", String.valueOf(args.getOrDefault("point", "eyes"))); } else { fields.put("yaw", Double.toString(angle(arg(args, "yaw")))); fields.put("pitch", Double.toString(angle(arg(args, "pitch")))); }
            var start = request("state.read", Map.of()); double yaw = Double.parseDouble(required(start, "player.YRot")), pitch = Double.parseDouble(required(start, "player.XRot")); int frames = Math.max(1, (int) Math.ceil(seconds * 20)); for (int i = 1; i <= frames; i++) { control.check(); Map<String, String> frame = new LinkedHashMap<>(fields); frame.put("startYaw", Double.toString(yaw)); frame.put("startPitch", Double.toString(pitch)); frame.put("fraction", Double.toString((double) i / frames)); request("script.look", frame); if (i < frames) control.sleep(seconds / frames); } return OchreScript.Empty.NONE; }
        if (name.equals("player.hit")) { var args = bind(positional, named, "count", "interval", "duration"); double count = OchreScript.number(arg(args, "count")); if (count < 1 || count > 100 || count != Math.rint(count)) throw new IllegalArgumentException("Attack count must be an integer in [1, 100]"); if (args.containsKey("interval") && args.containsKey("duration")) throw new IllegalArgumentException("Choose interval or duration, not both"); double spacing = args.containsKey("duration") ? duration(args, "duration", 0) / Math.max(1, count - 1) : duration(args, "interval", 1); if (spacing < .1 || spacing > 60 || spacing * (count - 1) > 120) throw new IllegalArgumentException("Attack schedule exceeds limits"); for (int i = 0; i < count; i++) { control.check(); request("script.hit", Map.of()); if (i + 1 < count) control.sleep(spacing); } return OchreScript.Empty.NONE; }
        throw new IllegalArgumentException("Unknown game function: " + name);
    }
    /// Encodes a bounded integral argument without accepting truncated floats.
    private static String integer(Object value, int min, int max) { double number = value instanceof OchreScript.Quantity ? OchreScript.quantity(value, "distance") : OchreScript.number(value); if (number < min || number > max || number != Math.rint(number)) throw new IllegalArgumentException("Expected integer in [" + min + ", " + max + "]"); return Integer.toString((int) number); }
    /// Accepts degree quantities and plain numeric angles.
    private static double angle(Object value) { return value instanceof OchreScript.Quantity ? OchreScript.quantity(value, "angle") : OchreScript.number(value); }
    /// Releases only input acquired by this run, leaving other runs untouched.
    private void release() throws Exception { if (acquired) { request("script.release", Map.of("owner", owner)); acquired = false; } }
    /// Best-effort explicit release is backed by the agent's independent short input lease.
    @Override public void close() throws Exception { closed = true; @Nullable var active = heartbeat; if (active != null) active.cancel(false); try { release(); } finally { try { if (active != null) request("script.state.release", Map.of("owner", owner)); } finally { foundations.close(); } } }
}
