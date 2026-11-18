package org.jackhuang.hmcl.script;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies permission boundaries and recoverable script management independently of a live world.
@NotNullByDefault
public final class ScriptFoundationsTest {
    /// Block effects require inspection and aiming as well as the specific world permission.
    @Test public void compoundCapabilities() throws Exception {
        var mine = OchreScript.compile("player.mine(vec3(1, 64, 1));"); assertEquals(Set.of("game.read", "player.look", "world.break"), mine.permissions());
        var place = OchreScript.compile("player.place_block(vec3(1, 64, 1), face=\"up\");"); assertEquals(Set.of("game.read", "player.look", "world.place"), place.permissions());
        var input = OchreScript.compile("input.send_key(65, action=\"press\"); input.send_text(\"hello\"); input.text(\"draft\"); chat.send(\"sent\"); print(game.logs());"); assertEquals(Set.of("input.keys", "input.text", "chat.send", "logs.read"), input.permissions());
        assertThrows(SecurityException.class, () -> mine.run(new InertHost(), Set.of("world.break"), line -> {}, new OchreScript.Control()));
    }
    /// Walking alone never requests placement; a dynamic bridge option cannot bypass preflight.
    @Test public void navigationPermissions() {
        Set<String> walking = Set.of("game.read", "player.look", "player.move");
        assertEquals(walking, OchreScript.compile("player.walk_to(vec3(2, 64, 4));").permissions());
        assertEquals(walking, OchreScript.compile("player.walk_to(vec3(2, 64, 4), bridge=false);").permissions());
        var bridge = OchreScript.compile("bool build = true; player.walk_to(vec3(2, 64, 4), bridge=build);");
        assertEquals(Set.of("game.read", "player.look", "player.move", "world.place"), bridge.permissions());
        assertThrows(SecurityException.class, () -> bridge.run(new InertHost(), walking, line -> {}, new OchreScript.Control()));
    }
    /// Record variables and observed event members work with ordinary branching and loops.
    @Test public void recordsAndRunLogs() throws Exception {
        var p = OchreScript.compile("Record sample = player.snapshot(); print(sample.position.x); for (event in player.events()) { if (event.type == \"hotbar\") { print(event.to_slot); } } List lines = script.logs(); print(lines.length);");
        List<String> output = new ArrayList<>(); p.run(new InertHost(), p.permissions(), output::add, new OchreScript.Control()); assertEquals(List.of("3", "2", "2"), output);
        assertThrows(OchreScript.ScriptError.class, () -> OchreScript.compile("Record wrong = 1;").run(new InertHost(), Set.of(), line -> {}, new OchreScript.Control()));
    }
    /// Rename collisions preserve both files; removal retains the exact contents in a recovery folder.
    @Test public void renameAndRecoverableRemoval(@TempDir Path root) throws Exception {
        Path first = root.resolve("first.ochre"), other = root.resolve("other.ochre"); Files.writeString(first, "say(\"A\");"); Files.writeString(other, "B");
        assertThrows(FileAlreadyExistsException.class, () -> ScriptFiles.rename(root, first, "other")); assertEquals("B", Files.readString(other)); assertTrue(Files.exists(first));
        for (String name : List.of("../bad", "", ".hidden", "a/b", "a\\b")) assertThrows(java.io.IOException.class, () -> ScriptFiles.rename(root, first, name));
        Path renamed = ScriptFiles.rename(root, first, "测试脚本"); assertEquals("测试脚本.ochre", renamed.getFileName().toString()); assertFalse(Files.exists(first));
        Path deleted = ScriptFiles.remove(root, renamed); assertFalse(Files.exists(renamed)); assertEquals("say(\"A\");", Files.readString(deleted));
        Path link = root.resolve("alias.ochre"); Files.createSymbolicLink(link, other); assertThrows(java.io.IOException.class, () -> ScriptFiles.remove(root, link)); assertEquals("B", Files.readString(other));
    }
    /// Returning logs cannot expose the common credential forms emitted by third-party launch code.
    @Test public void logRedaction() { String result = ScriptFoundationHost.redact("accessToken=secret refreshToken:abcd Authorization: Bearer very-secret"); assertFalse(result.contains("secret")); assertFalse(result.contains("abcd")); }
    /// Distributed examples compile with their managed module resolver before export.
    @Test public void bundledExpandedExamples() throws Exception {
        Path examples=Path.of(Objects.requireNonNull(System.getProperty("ochre.examples")));
        for(String file:List.of("observe-expanded.ochre","modules-and-checkpoints.ochre")) {
            var program=OchreScript.compile(Files.readString(examples.resolve(file)),name->Files.readString(examples.resolve(name)));
            assertTrue(program.permissions().contains("game.read"));
        }
    }
    /// Deterministic records exercise the interpreter rather than Minecraft reflection.
    @NotNullByDefault private static final class InertHost implements OchreScript.Host {
        /// This fixture exposes no writable or readable external state.
        public Object read(String path) { throw new AssertionError(path); }
        /// A fixture never mutates game options.
        public void write(String path, Object value) { throw new AssertionError(path); }
        /// Returns one snapshot and one event only for the two registered observation calls.
        public Object call(String name, List<Object> args, Map<String, Object> named, OchreScript.Control control) { return switch (name) { case "player.snapshot" -> Map.of("position", Map.of("x", 3.0)); case "player.events" -> List.of(Map.of("type", "hotbar", "to_slot", 2.0)); default -> throw new AssertionError(name); }; }
        /// The fixture owns no external resources.
        public void close() { }
    }
}
