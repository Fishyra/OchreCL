package io.ochre.bridge;

import java.io.IOException;
import java.util.*;
import static io.ochre.bridge.ActionSupport.*;

/// Plans short routes in loaded terrain and runs one checked movement pulse per request.
final class Navigation {
    /// Integer feet cell; navigation aims for its horizontal center.
    private record Cell(int x, int y, int z) {
        Cell offset(int dx, int dy, int dz) { return new Cell(x + dx, y + dy, z + dz); }
        int horizontal(Cell other) { return Math.abs(x - other.x) + Math.abs(z - other.z); }
        String wire() { return x + "," + y + "," + z; }
    }

    /// A parent edge may contain a complete short bridge between two existing landings.
    private record Node(Cell cell, Node previous, int cost, int gap) { }

    /// Distinguishes full supporting blocks, clear air and cells we cannot safely use.
    private enum Tile { AIR, FLOOR, BLOCKED, UNLOADED }

    /// Restricts bridge construction to ordinary full, non-interactive building blocks.
    private static final Set<String> BRIDGE_BLOCKS = Set.of("minecraft:cobblestone", "minecraft:cobbled_deepslate",
            "minecraft:stone", "minecraft:dirt", "minecraft:netherrack", "minecraft:oak_planks",
            "minecraft:spruce_planks", "minecraft:birch_planks", "minecraft:jungle_planks",
            "minecraft:acacia_planks", "minecraft:dark_oak_planks", "minecraft:cherry_planks");

    /// Cached block sampling during a single game-thread request.
    private static final class Terrain {
        final Object level, border;
        final java.lang.reflect.Constructor<?> position;
        final java.lang.reflect.Method loaded, state, inside;
        final Map<Cell, Tile> tiles = new HashMap<>();
        final Map<Class<?>, java.lang.reflect.Method> shapes = new HashMap<>();
        final Class<?> getter, posType;

        Terrain(Object client) throws Exception {
            level = field(client, "level");
            border = call(level, "getWorldBorder");
            posType = type(client, "net.minecraft.core.BlockPos");
            getter = type(client, "net.minecraft.world.level.BlockGetter");
            position = posType.getConstructor(int.class, int.class, int.class);
            loaded = level.getClass().getMethod("hasChunkAt", posType);
            state = level.getClass().getMethod("getBlockState", posType);
            inside = border.getClass().getMethod("isWithinBounds", posType);
        }

        Tile tile(Cell cell) throws Exception {
            Tile cached = tiles.get(cell);
            if (cached != null) return cached;
            Object pos = position.newInstance(cell.x, cell.y, cell.z);
            Tile tile;
            if (!(Boolean) loaded.invoke(level, pos)) tile = Tile.UNLOADED;
            else if (!(Boolean) inside.invoke(border, pos)) tile = Tile.BLOCKED;
            else {
                Object block = state.invoke(level, pos);
                if ((Boolean) call(block, "isAir")) tile = Tile.AIR;
                else if (!(Boolean) call(call(block, "getFluidState"), "isEmpty")) tile = Tile.BLOCKED;
                else {
                    Class<?> kind = block.getClass();
                    var full = shapes.get(kind);
                    if (full == null) {
                        full = kind.getMethod("isCollisionShapeFullBlock", getter, posType);
                        shapes.put(kind, full);
                    }
                    String name = call(block, "getBlock").getClass().getSimpleName();
                    tile = !name.contains("Magma") && !name.contains("Ice") && !name.contains("Slime")
                            && !name.contains("Honey") && !name.contains("TNT") && !name.contains("SculkShrieker")
                            && (Boolean) full.invoke(block, level, pos) ? Tile.FLOOR : Tile.BLOCKED;
                }
            }
            tiles.put(cell, tile);
            return tile;
        }

        boolean air(Cell cell) throws Exception { return tile(cell) == Tile.AIR; }
        boolean room(Cell cell) throws Exception { return air(cell) && air(cell.offset(0, 1, 0)); }
        boolean stand(Cell cell) throws Exception { return room(cell) && tile(cell.offset(0, -1, 0)) == Tile.FLOOR; }
        boolean edge(Cell from, Cell to) throws Exception {
            return stand(to) && switch (to.y - from.y) {
                case 0 -> true;
                case 1 -> air(from.offset(0, 2, 0)) && air(to.offset(0, 2, 0));
                case -1 -> air(to.offset(0, 2, 0));
                default -> false;
            };
        }
    }

    private Navigation() { }

    /// Uses A* with a small fixed search volume and no diagonal corner cutting.
    static void plan(Object client, Map<String, String> request, Map<String, String> response) throws Exception {
        sameWorld(client, request);
        Object player = player(client);
        Cell start = current(player), goal = cell(request, "goal");
        if (start.horizontal(goal) > 24 || Math.abs(start.y - goal.y) > 6) throw new IOException("walk_to.target_out_of_range");
        if (!(Boolean) call(player, "onGround")) throw new IOException("walk_to.start_requires_ground");
        Terrain terrain = new Terrain(client);
        if (!terrain.stand(start)) throw new IOException("walk_to.unsupported_start_surface");
        if ("true".equals(request.get("straight"))) {
            if (start.horizontal(goal) != 1 || start.y != goal.y)
                throw new IOException("walk_to.straight_requires_adjacent_cell_at_same_height");
            boolean supported = terrain.stand(goal);
            if (!supported) {
                if (!"true".equals(request.get("bridge")) || !terrain.room(goal) || !terrain.air(goal.offset(0, -1, 0)))
                    throw new IOException("walk_to.straight_step_blocked");
                if (bridgeMaterials(player) < 1) throw new IOException("walk_to.select_enough_solid_bridge_blocks");
            }
            response.put("start", start.wire());
            response.put("route", goal.wire() + "," + !supported);
            return;
        }
        if (!terrain.stand(goal)) throw new IOException("walk_to.destination_has_no_safe_footing");
        boolean bridge = "true".equals(request.get("bridge"));
        Node result = search(terrain, start, goal, false);
        if (result == null && bridge) {
            result = search(terrain, start, goal, true);
            if (result != null && bridgeMaterials(player) < result.gap)
                throw new IOException("walk_to.select_enough_solid_bridge_blocks");
        }
        if (result == null) throw new IOException(bridge ? "walk_to.no_route_within_loaded_area_and_three_block_bridge_limit"
                : "walk_to.no_walkable_route_in_loaded_area");
        Deque<String> steps = new ArrayDeque<>();
        for (Node node = result; node.previous != null; node = node.previous) {
            Cell from = node.previous.cell, to = node.cell;
            steps.addFirst(to.wire() + ",false");
            int dx = Integer.signum(to.x - from.x), dz = Integer.signum(to.z - from.z);
            for (int index = from.horizontal(to) - 1; index > 0; index--)
                steps.addFirst(from.offset(index * dx, 0, index * dz).wire() + ",true");
        }
        if (steps.size() > 64) throw new IOException("walk_to.route_too_long");
        response.put("start", start.wire());
        response.put("route", String.join(";", steps));
    }

    private static Node search(Terrain terrain, Cell start, Cell goal, boolean bridge) throws Exception {
        var open = new PriorityQueue<Node>(Comparator.comparingInt(node -> node.cost + 10 * node.cell.horizontal(goal)));
        Map<Cell, Integer> best = new HashMap<>(), afterBridge = new HashMap<>();
        offer(open, best, new Node(start, null, 0, 0));
        int expanded = 0;
        while (!open.isEmpty() && expanded < 768) {
            Node node = open.remove();
            Cell from = node.cell;
            if (node.cost != (node.gap == 0 ? best : afterBridge).get(from)) continue;
            if (from.equals(goal)) return node;
            expanded++;
            for (int direction = 0; direction < 4; direction++) {
                int dx = direction == 0 ? 1 : direction == 1 ? -1 : 0;
                int dz = direction == 2 ? 1 : direction == 3 ? -1 : 0;
                for (int dy = -1; dy <= 1; dy++) {
                    Cell to = from.offset(dx, dy, dz);
                    if (within(start, goal, to) && terrain.edge(from, to))
                        offer(open, node.gap == 0 ? best : afterBridge, new Node(to, node, node.cost + 10 + 4 * Math.abs(dy), node.gap));
                }
                if (!bridge || node.gap != 0) continue;
                for (int length = 1; length <= 4; length++) {
                    Cell to = from.offset(dx * length, 0, dz * length);
                    if (!within(start, goal, to) || !terrain.room(to)) break;
                    if (terrain.stand(to)) {
                        if (length > 1) offer(open, afterBridge, new Node(to, node, node.cost + 30 * length, length - 1));
                        break;
                    }
                    if (!terrain.air(to.offset(0, -1, 0))) break;
                }
            }
        }
        return null;
    }

    private static boolean within(Cell start, Cell goal, Cell cell) {
        return cell.horizontal(start) <= 30 && cell.horizontal(goal) <= 30 && Math.abs(cell.y - start.y) <= 6;
    }

    private static void offer(PriorityQueue<Node> open, Map<Cell, Integer> best, Node node) {
        if (node.cost < best.getOrDefault(node.cell, Integer.MAX_VALUE)) {
            best.put(node.cell, node.cost);
            open.add(node);
        }
    }

    /// Checks current footing, position and the same world before moving or placing anything.
    static void step(Object client, Map<String, String> request, Map<String, String> response) throws Exception {
        String owner = owner(request);
        try {
            stepChecked(client, request, response, owner);
        } catch (Exception error) {
            try { ScriptActions.stopNavigation(owner); } catch (Exception cleanup) { error.addSuppressed(cleanup); }
            throw error;
        }
    }

    private static void stepChecked(Object client, Map<String, String> request, Map<String, String> response, String owner) throws Exception {
        sameWorld(client, request);
        interactive(client);
        Object player = player(client);
        Cell from = cell(request, "from"), to = cell(request, "to");
        boolean center = from.equals(to), bridge = "true".equals(request.get("bridge"));
        if (!center && (from.horizontal(to) != 1 || Math.abs(from.y - to.y) > 1)) throw new IOException("walk_to.invalid_step");
        if ((Boolean) call(player, "isSpectator") || (Boolean) field(call(player, "getAbilities"), "flying")
                || (Boolean) call(player, "isInWater") || (Boolean) call(player, "isInLava"))
            throw new IOException("walk_to.requires_ground_movement");
        double x = number(player, "getX"), y = number(player, "getY"), z = number(player, "getZ");
        boolean grounded = (Boolean) call(player, "onGround");
        if (x < Math.min(from.x, to.x) - .03 || x > Math.max(from.x, to.x) + 1.03
                || z < Math.min(from.z, to.z) - .03 || z > Math.max(from.z, to.z) + 1.03
                || y < Math.min(from.y, to.y) - .2 || y > Math.max(from.y, to.y) + 1.35)
            throw new IOException("walk_to.player_left_the_route");
        Object options = field(client, "options");
        for (String key : List.of("keyLeft", "keyRight", "keyDown", "keyJump"))
            if ((Boolean) call(field(options, key), "isDown")) throw new IOException("walk_to.manual_movement_detected");
        Terrain terrain = new Terrain(client);
        double dx = to.x + .5 - x, dz = to.z + .5 - z;
        if (grounded && Math.abs(y - to.y) < .2 && Math.hypot(dx, dz) < .26 && terrain.stand(to)) {
            ScriptActions.stopNavigation(owner);
            response.put("status", "arrived");
            return;
        }
        if (!grounded && Math.hypot(dx, dz) < .24 && terrain.stand(to)) {
            ScriptActions.stopNavigation(owner);
            response.put("status", "moving");
            return;
        }
        if (!terrain.stand(from)) throw new IOException("walk_to.starting_footing_changed");
        double yaw = Math.toDegrees(Math.atan2(dz, dx)) - 90;
        if (bridge && from.y == to.y && terrain.room(to) && terrain.air(to.offset(0, -1, 0))) {
            if ("true".equals(request.get("placed"))) throw new IOException("walk_to.bridge_placement_not_confirmed");
            if (!grounded || bridgeMaterials(player) == 0) throw new IOException("walk_to.bridge_requires_ground_and_selected_blocks");
            int vx = to.x - from.x, vz = to.z - from.z;
            double overEdge = (x - from.x - .5) * vx + (z - from.z - .5) * vz - .5;
            if (overEdge < .06 || !(Boolean) call(player, "isCrouching")) {
                aim(player, Math.toDegrees(Math.atan2(vz, vx)) - 90, 0);
                ScriptActions.navigate(client, owner, true, overEdge < .16);
            } else {
                ScriptActions.navigate(client, owner, true, false);
                Cell support = from.offset(0, -1, 0);
                String face = vx > 0 ? "east" : vx < 0 ? "west" : vz > 0 ? "south" : "north";
                double rx = from.x + .5 + .499 * vx - x, rz = from.z + .5 + .499 * vz - z;
                aim(player, Math.toDegrees(Math.atan2(rz, rx)) - 90,
                        -Math.toDegrees(Math.atan2(from.y - .25 - number(player, "getEyeY"), Math.hypot(rx, rz))));
                Map<String, String> placed = new HashMap<>();
                BlockActions.handle(client, Map.of("command", "script.block.place", "world", world(client), "face", face,
                        "x", String.valueOf(support.x), "y", String.valueOf(support.y), "z", String.valueOf(support.z)), placed);
                response.put("status", "placed");
                return;
            }
        } else {
            if (!terrain.edge(from, to)) throw new IOException("walk_to.route_blocked_or_footing_changed");
            aim(player, yaw, 0);
            ScriptActions.navigate(client, owner, bridge, true);
            if (to.y > from.y && grounded && Math.abs(y - from.y) < .2) call(player, "jumpFromGround");
        }
        response.put("status", "moving");
    }

    private static int bridgeMaterials(Object player) throws Exception {
        Object stack = call(player, "getMainHandItem");
        return BRIDGE_BLOCKS.contains(id(call(stack, "getItem"))) ? (int) number(stack, "getCount") : 0;
    }

    private static void aim(Object player, double yaw, double pitch) throws Exception {
        player.getClass().getMethod("setYRot", float.class).invoke(player, (float) yaw);
        player.getClass().getMethod("setXRot", float.class).invoke(player, (float) pitch);
    }

    private static Cell current(Object player) throws Exception {
        return new Cell((int) Math.floor(number(player, "getX")), (int) Math.floor(number(player, "getY") + .05),
                (int) Math.floor(number(player, "getZ")));
    }

    private static Cell cell(Map<String, String> request, String key) throws IOException {
        String[] parts = request.getOrDefault(key, "").split(",", -1);
        try {
            if (parts.length == 3) {
                int x = Integer.parseInt(parts[0]), y = Integer.parseInt(parts[1]), z = Integer.parseInt(parts[2]);
                if (x >= -30_000_000 && x <= 30_000_000 && z >= -30_000_000 && z <= 30_000_000
                        && y >= -2048 && y <= 2048) return new Cell(x, y, z);
            }
        } catch (NumberFormatException ignored) { }
        throw new IOException("walk_to.invalid_cell:" + key);
    }
}
