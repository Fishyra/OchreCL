package io.ochre.bridge;

import java.nio.file.Files;
import java.nio.file.Path;
import org.jackhuang.hmcl.script.OchreScript;
import org.jackhuang.hmcl.script.ScriptGameHost;

/// Exercises the complete script against the disposable game, including its real launcher host.
public final class SpawnToPickaxeProbe {
    private SpawnToPickaxeProbe() { }

    public static void main(String[] args) throws Exception {
        var program = OchreScript.compile(Files.readString(Path.of(args[1])));
        program.run(new ScriptGameHost(args[0]), program.permissions(), System.out::println, new OchreScript.Control());
        var game = new ScriptGameHost(args[0]);
        var items = (java.util.List<?>) game.read("player.inventory.slots");
        if (items.stream().noneMatch(item -> item instanceof java.util.Map<?, ?> slot
                && "minecraft:wooden_pickaxe".equals(slot.get("id"))))
            throw new AssertionError("Wooden pickaxe was not found in the survival inventory");
        game.close();
        System.out.println("SPAWN_TO_PICKAXE_OK: empty inventory to verified wooden pickaxe");
    }
}
