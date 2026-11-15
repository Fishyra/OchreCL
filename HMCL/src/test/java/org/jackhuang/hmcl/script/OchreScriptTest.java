package org.jackhuang.hmcl.script;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/// Behavioral language, permission and cancellation tests use an inert host and never touch a real instance.
@NotNullByDefault
public final class OchreScriptTest {
    /// Observable host boundary records effects without accessing files, sockets or game processes.
    @NotNullByDefault private static final class Host implements OchreScript.Host {
        /// Deterministic reads including a real-shaped inventory.
        final Map<String, Object> state = new HashMap<>(Map.of("game.language", "en_us", "player.health", 8.0, "player.inventory.slots", List.of(OchreScript.Empty.NONE, Map.of("id", "minecraft:stone", "count", 12.0))));
        /// Names of effects requested by the interpreter.
        final List<String> effects = new ArrayList<>();
        /// Arguments received by the last host call.
        @Unmodifiable List<Object> arguments = List.of();
        /// Named arguments preserve dimensioned values.
        @Unmodifiable Map<String, Object> keywords = Map.of();
        /// Close count establishes cleanup on success, failure and rejected permission.
        int closes;
        /// Provides fixture values only.
        public Object read(String path) { effects.add("read:" + path); return state.getOrDefault(path, OchreScript.Empty.NONE); }
        /// Records writes without changing game data.
        public void write(String path, Object value) { effects.add("write:" + path); state.put(path, value); }
        /// Records calls, returning an absent player rather than manufacturing a target.
        public Object call(String name, List<Object> positional, Map<String, Object> named, OchreScript.Control control) { effects.add(name); arguments = List.copyOf(positional); keywords = Map.copyOf(named); return OchreScript.Empty.NONE; }
        /// Records guaranteed cleanup.
        public void close() { closes++; }
    }
    /// Runs basic arithmetic, typed variables, scopes and C-style control flow.
    @Test public void loopsAndConditions() throws Exception {
        Host host = new Host(); List<String> output = new ArrayList<>();
        OchreScript.compile("int total = 0; for (int i = 0; i < 4; i++) { total = total + i; } if (total == 6 && true) { print(total); } else { print(0); } while (total > 4) { total--; } print(total);").run(host, Set.of(), output::add, new OchreScript.Control());
        assertEquals(List.of("6", "4"), output); assertEquals(1, host.closes); assertTrue(host.effects.isEmpty());
    }
    /// Quantities and keyword arguments survive parsing without Chinese units or implicit numeric coercion.
    @Test public void unitsAndNamedArguments() throws Exception {
        Host host = new Host(); var program = OchreScript.compile("player.walk_forward(5s); player.run_forward(3blocks); int a = 4; player.hit(count=a, interval=3s);");
        assertEquals(Set.of("player.move", "player.attack"), program.permissions()); program.run(host, program.permissions(), value -> {}, new OchreScript.Control());
        assertEquals(List.of("player.walk_forward", "player.run_forward", "player.hit"), host.effects); assertEquals(4.0, host.keywords.get("count")); assertEquals(new OchreScript.Quantity(3, "time"), host.keywords.get("interval"));
    }
    /// Missing grants fail before reads, writes or actions, even when the relevant branch would not execute.
    @Test public void permissionPreflight() {
        Host host = new Host(); var program = OchreScript.compile("print(1); if (false) { player.hit(count=1); }");
        assertThrows(SecurityException.class, () -> program.run(host, Set.of(), value -> fail("Output before authorization"), new OchreScript.Control())); assertTrue(host.effects.isEmpty()); assertEquals(1, host.closes);
    }
    /// Explicit state reads and writes use separate permissions.
    @Test public void settingsAndReads() throws Exception {
        Host host = new Host(); List<String> output = new ArrayList<>(); var program = OchreScript.compile("print(game.language); settings.audio.master = 0.6; if (player.health < 10) { print(\"Low health\"); }");
        program.run(host, program.permissions(), output::add, new OchreScript.Control()); assertEquals(List.of("en_us", "Low health"), output); assertEquals(.6, host.state.get("settings.audio.master"));
    }
    /// Iteration exposes typed record members and distinguishes empty slots from initialized items.
    @Test public void inventoryIteration() throws Exception {
        Host host = new Host(); List<String> output = new ArrayList<>(); var program = OchreScript.compile("for (item in player.inventory.slots) { if (item != none) { print(item.id, item.count); } }"); program.run(host, program.permissions(), output::add, new OchreScript.Control()); assertEquals(List.of("minecraft:stone 12"), output);
    }
    /// Nested properties and typed collections expose records without arbitrary host reflection.
    @Test public void nestedPropertiesAndLists() throws Exception { Host host = new Host(); host.state.put("player.position", Map.of("x", 3.0, "y", 64.0, "z", 0.0)); List<String> output = new ArrayList<>(); var program = OchreScript.compile("List slots = player.inventory.slots; print(slots.length); print(player.position.x);"); program.run(host, program.permissions(), output::add, new OchreScript.Control()); assertEquals(List.of("2", "3"), output); }
    /// Named target selection and body enums compile; absent players are safely guarded.
    @Test public void targetSelection() throws Exception {
        Host host = new Host(); var program = OchreScript.compile("Player target = world.find_player(\"BetaF1sh\"); if (target != none) { player.look_at(target, point=body.right_arm, duration=500ms); }"); program.run(host, program.permissions(), value -> {}, new OchreScript.Control()); assertEquals(List.of("world.find_player"), host.effects);
    }
    /// Unit-aware comparisons reject mixed dimensions rather than treating seconds as distance.
    @Test public void dimensionChecks() throws Exception {
        Host host = new Host(); List<String> output = new ArrayList<>(); OchreScript.compile("if (500ms < 1s) { print(true); }").run(host, Set.of(), output::add, new OchreScript.Control()); assertEquals(List.of("true"), output);
        assertThrows(OchreScript.ScriptError.class, () -> OchreScript.compile("if (3s < 2blocks) { print(1); }").run(host, Set.of(), value -> {}, new OchreScript.Control()));
    }
    /// Assignments enforce declared types and preserve an uninitialized declaration until explicitly set.
    @Test public void strictTypes() {
        for (String source : List.of("int a; print(a);", "int a = 1.5;", "bool a = 1;", "int a = 1; a = \"wrong\";")) assertThrows(OchreScript.ScriptError.class, () -> OchreScript.compile(source).run(new Host(), Set.of(), value -> {}, new OchreScript.Control()));
    }
    /// Parse failures include a stable line number and reject all source before execution.
    @Test public void syntaxErrorsAndExternalCode() {
        for (String source : List.of("player.walk_forward(3格);", "System.exit(0);", "eval(\"anything\");", "print(1)", "if (true) { print(1);", "player.hit(count=1, count=2);")) assertThrows(OchreScript.ScriptError.class, () -> OchreScript.compile(source));
        assertTrue(assertThrows(OchreScript.ScriptError.class, () -> OchreScript.compile("print(1);\n@bad;")).getMessage().contains("Line 2:"));
    }
    /// Short-circuiting avoids invalid right-side evaluation and zero division fails explicitly.
    @Test public void shortCircuitAndFiniteNumbers() throws Exception {
        Host host = new Host(); OchreScript.compile("if (false && 1 / 0 == 0) { print(1); }").run(host, Set.of(), value -> fail("Unexpected branch"), new OchreScript.Control()); assertThrows(OchreScript.ScriptError.class, () -> OchreScript.compile("print(1 / 0);").run(host, Set.of(), value -> {}, new OchreScript.Control()));
    }
    /// Tight loops cannot monopolize a worker indefinitely.
    @Test public void instructionBudget() {
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(3), () -> assertThrows(OchreScript.ScriptError.class, () -> OchreScript.compile("while (true) { }").run(new Host(), Set.of(), value -> {}, new OchreScript.Control())));
    }
    /// Cancellation interrupts a long wait promptly and still calls host cleanup exactly once.
    @Test public void cancellationAndCleanup() throws Exception {
        Host host = new Host(); OchreScript.Control control = new OchreScript.Control(); CountDownLatch entered = new CountDownLatch(1); var program = OchreScript.compile("print(1); sleep(60s); print(2);"); ExecutorService executor = Executors.newSingleThreadExecutor();
        try { Future<?> future = executor.submit(() -> { assertThrows(CancellationException.class, () -> program.run(host, Set.of(), value -> { if (value.equals("1")) entered.countDown(); else fail("Continued after cancellation"); }, control)); }); assertTrue(entered.await(2, TimeUnit.SECONDS)); control.cancel(); future.get(2, TimeUnit.SECONDS); assertEquals(1, host.closes); } finally { control.cancel(); executor.shutdownNow(); }
    }
    /// Deep nesting and oversized input fail before Java stack exhaustion or unbounded allocation.
    @Test public void sourceLimits() { assertThrows(OchreScript.ScriptError.class, () -> OchreScript.compile(" ".repeat(65_537))); assertThrows(OchreScript.ScriptError.class, () -> OchreScript.compile("print(" + "(".repeat(100) + "1" + ")".repeat(100) + ");")); }
    /// The adapter validates malformed actions locally without reading any real bridge configuration.
    @Test public void adapterArgumentValidation() {
        ScriptGameHost host = new ScriptGameHost("unused-test-instance"); OchreScript.Control control = new OchreScript.Control();
        assertThrows(IllegalArgumentException.class, () -> host.call("player.walk_forward", List.of(3.0), Map.of(), control)); assertThrows(IllegalArgumentException.class, () -> host.call("player.hit", List.of(), Map.of("count", 1.5), control)); assertThrows(IllegalArgumentException.class, () -> host.call("player.hit", List.of(), Map.of("count", 2.0, "interval", new OchreScript.Quantity(1, "time"), "duration", new OchreScript.Quantity(1, "time")), control));
    }
    /// Retry counts represent additional attempts, and successful recovery continues once.
    @Test public void boundedFailureRetries() throws Exception {
        java.util.concurrent.atomic.AtomicInteger attempts = new java.util.concurrent.atomic.AtomicInteger();
        OchreScript.Host flaky = new OchreScript.Host() {
            /// Supplies an inert value for deterministic recovery tests.
            public Object read(String path) { return 0.0; }
            /// Never changes game data.
            public void write(String path, Object value) { }
            /// Fails twice, then records successful recovery.
            public Object call(String name, List<Object> args, Map<String, Object> named, OchreScript.Control control) { if (attempts.incrementAndGet() < 3) throw new IllegalStateException("Temporary failure"); return OchreScript.Empty.NONE; }
            /// The fixture owns no resources.
            public void close() { }
        };
        List<String> output = new ArrayList<>(); var program = OchreScript.compile("player.jump(); print(script_repeat_count);");
        program.run(flaky, program.permissions(), output::add, new OchreScript.Control(OchreScript.FailurePolicy.RETRY_COUNT, 2));
        assertEquals(3, attempts.get()); assertEquals("2", output.get(output.size() - 1));
        attempts.set(0);
        assertThrows(OchreScript.ScriptError.class, () -> program.run(flaky, program.permissions(), value -> {}, new OchreScript.Control(OchreScript.FailurePolicy.RETRY_COUNT, 1)));
        assertEquals(2, attempts.get());
    }
    /// Skipping reports a failed statement and continues, while stop and zero retries terminate.
    @Test public void skipAndStopFailures() throws Exception {
        List<String> output = new ArrayList<>(); var program = OchreScript.compile("print(1 / 0); print(7);");
        program.run(new Host(), Set.of(), output::add, new OchreScript.Control(OchreScript.FailurePolicy.SKIP, 3));
        assertTrue(output.get(0).contains("Line 1:")); assertEquals("7", output.get(1));
        for (var policy : List.of(OchreScript.FailurePolicy.STOP, OchreScript.FailurePolicy.RETRY_COUNT))
            assertThrows(OchreScript.ScriptError.class, () -> program.run(new Host(), Set.of(), value -> fail("Continued after failure"), new OchreScript.Control(policy, 0)));
        assertThrows(IllegalArgumentException.class, () -> new OchreScript.Control(OchreScript.FailurePolicy.RETRY_COUNT, 101));
    }
    /// Persistent retries remain immediately cancellable instead of swallowing the stop signal.
    @Test public void unboundedRetryCancellation() throws Exception {
        var control = new OchreScript.Control(OchreScript.FailurePolicy.RETRY, 3); CountDownLatch failed = new CountDownLatch(1); ExecutorService executor = Executors.newSingleThreadExecutor(); Host host = new Host();
        try {
            Future<?> future = executor.submit(() -> assertThrows(CancellationException.class, () -> OchreScript.compile("print(1 / 0);").run(host, Set.of(), value -> failed.countDown(), control)));
            assertTrue(failed.await(2, TimeUnit.SECONDS)); control.cancel(); future.get(2, TimeUnit.SECONDS); assertEquals(1, host.closes);
        } finally { control.cancel(); executor.shutdownNow(); }
    }
    /// Capability discovery covers new builtins without requiring a game read for local retry settings.
    @Test public void extendedCapabilitiesAndStatementObserver() throws Exception {
        var program = OchreScript.compile("player.jump(); player.set_state(\"flying\", true); player.move_item(0, 1); player.select_hotbar(2); player.craft(1); game.command(\"time set day\"); player.look_at_block(vec3(1, 2, 3), face=\"north\"); print(player.effects); print(world.nearest_block(\"minecraft:stone\"));");
        assertEquals(Set.of("player.move", "player.state", "inventory.write", "game.command", "player.look", "game.read"), program.permissions());
        List<Integer> lines = new ArrayList<>(); OchreScript.Control control = new OchreScript.Control(); control.observe(lines::add);
        var local = OchreScript.compile("print(script_repeat_count);\nprint(2);"); assertTrue(local.permissions().isEmpty()); local.run(new Host(), Set.of(), value -> {}, control); assertEquals(List.of(1, 2), lines);
        assertThrows(SecurityException.class, () -> program.run(new Host(), Set.of(), value -> {}, new OchreScript.Control(OchreScript.FailurePolicy.SKIP, 3)));
    }

    /// Local speech uses no game-control permission and preserves English builtin syntax with Unicode message content.
    @Test public void speechBuiltinAndDuration() throws Exception {
        Host host = new Host(); var program = OchreScript.compile("say(\"Hello!\"); say(\"完成\", duration=5s);");
        assertTrue(program.permissions().isEmpty()); program.run(host, Set.of(), value -> {}, new OchreScript.Control());
        assertEquals(List.of("say", "say"), host.effects); assertEquals(List.of("完成", new OchreScript.Quantity(5, "time")), host.arguments); assertTrue(host.keywords.isEmpty());
        Host defaults = new Host(); OchreScript.compile("say(\"Ready\");").run(defaults, Set.of(), value -> {}, new OchreScript.Control());
        assertEquals(new OchreScript.Quantity(3, "time"), defaults.arguments.get(1));
        Host positional = new Host(); OchreScript.compile("say(\"Ready\", 500ms);").run(positional, Set.of(), value -> {}, new OchreScript.Control());
        assertEquals(new OchreScript.Quantity(.5, "time"), positional.arguments.get(1));
    }
    /// Malformed bubble commands fail before reaching the game adapter.
    @Test public void speechValidation() {
        for (String source : List.of("say();", "say(3);", "say(\"\");", "say(\"a\", 0s);", "say(\"a\", 31s);", "say(\"a\", 3);", "say(\"a\", 1s, duration=2s);", "say(\"a\", unknown=1);", "say(\"" + "x".repeat(257) + "\");")) {
            Host host = new Host(); assertThrows(OchreScript.ScriptError.class, () -> OchreScript.compile(source).run(host, Set.of(), value -> {}, new OchreScript.Control())); assertTrue(host.effects.isEmpty());
        }
    }

    /// Functions support forward calls, isolated locals, named arguments and return through loops.
    @Test public void functionScopesAndReturns() throws Exception {
        Host host=new Host(); List<String> output=new ArrayList<>();
        var program=OchreScript.compile("int global = 3; print(add(b=4, a=2)); function add(a,b) { int local = a + b; for (int i=0; i<2; i++) { return local + global; } return 0; } print(global);");
        program.run(host,Set.of(),output::add,new OchreScript.Control());assertEquals(List.of("9","3"),output);
        assertThrows(OchreScript.ScriptError.class,()->OchreScript.compile("return 1;"));
        assertThrows(OchreScript.ScriptError.class,()->OchreScript.compile("function bad(player) { return player; }"));
        assertThrows(OchreScript.ScriptError.class,()->OchreScript.compile("function f(a) { return a; } f();").run(new Host(),Set.of(),value->{},new OchreScript.Control()));
        assertThrows(OchreScript.ScriptError.class,()->OchreScript.compile("function f() { return f(); } f();").run(new Host(),Set.of(),value->{},new OchreScript.Control()));
    }
    /// Managed imports are resolved once before authorization and reject cycles, paths and top-level effects.
    @Test public void managedModulesAndPermissions() throws Exception {
        Map<String,String> modules=Map.of("common.ochre","function answer() { return 42; } function unused() { player.jump(); }", "left.ochre","import \"common.ochre\";", "right.ochre","import \"common.ochre\";");
        var program=OchreScript.compile("import \"left.ochre\"; import \"right.ochre\"; print(answer());",name->Objects.requireNonNull(modules.get(name)));
        assertEquals(Set.of("player.move"),program.permissions());Host host=new Host();assertThrows(SecurityException.class,()->program.run(host,Set.of(),value->{},new OchreScript.Control()));assertTrue(host.effects.isEmpty());
        List<String> output=new ArrayList<>();program.run(new Host(),program.permissions(),output::add,new OchreScript.Control());assertEquals(List.of("42"),output);
        for(String name:List.of("../bad.ochre","/bad.ochre"))assertThrows(IllegalArgumentException.class,()->OchreScript.compile("import \""+name+"\";",file->""));
        assertThrows(IllegalArgumentException.class,()->OchreScript.compile("import \"a.ochre\";",file->"import \"a.ochre\";"));
        assertThrows(IllegalArgumentException.class,()->OchreScript.compile("import \"a.ochre\";",file->"say(\"No auto effects\");"));
    }
}
