package org.jackhuang.hmcl.script;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;

/// A bounded, interpreted English DSL; scripts cannot access Java, files, processes or arbitrary network APIs.
@NotNullByDefault
public final class OchreScript {
    /// Distinct sentinels avoid conflating an absent target and an uninitialized variable.
    @NotNullByDefault public enum Empty { NONE, UNINITIALIZED }
    /// Dimensioned literal; time uses seconds, distance uses blocks and angles use degrees.
    @NotNullByDefault public record Quantity(double value, String dimension) { }
    /// An error carries the original script position, rather than a generated Java stack trace.
    @NotNullByDefault public static final class ScriptError extends RuntimeException {
        /// Constructs a user-facing positional diagnostic.
        public ScriptError(String message, int line, int column) { super("Line " + line + ":" + column + ": " + message); }
    }
    /// The small game interface is supplied by the launcher, never resolved reflectively from script text.
    @NotNullByDefault public interface Host {
        /// Reads a documented property on the current game.
        Object read(String path) throws Exception;
        /// Writes a documented property after permission verification.
        void write(String path, Object value) throws Exception;
        /// Executes one registered action synchronously, respecting the cancellation control.
        Object call(String name, List<Object> positional, Map<String, Object> named, Control control) throws Exception;
        /// Releases input owned by this run even after an error or cancellation.
        void close() throws Exception;
    }
    /// Statement failure behavior; every retry remains cancellable and budgeted.
    @NotNullByDefault public enum FailurePolicy { STOP, RETRY, SKIP, RETRY_COUNT }
    /// Per-run cancellation, finite execution budget and interruptible waits; no script runs on the UI thread.
    @NotNullByDefault public static final class Control {
        /// Policy is snapshotted when a run begins, never changed halfway through execution.
        private final FailurePolicy policy;
        /// Additional attempts after the initial failure.
        private final int repeats;
        /// Active function/module label, empty for the editor's main source.
        private String sourceLabel="";
        /// Lets the UI distinguish imported function lines from the editor's main file.
        public String sourceLabel(){return sourceLabel;}
        /// Receives the source line before each statement or retry.
        private java.util.function.IntConsumer trace = line -> { };
        /// Reports repeated failures separately from the current-statement observer.
        private java.util.function.BiConsumer<Integer, Integer> failures = (line, count) -> { };
        /// Receives statements that completed successfully after their effects and checks.
        private java.util.function.IntConsumer recovered = line -> { };
        /// Installs a recovery observer independently of retries.
        public void observeRecovery(java.util.function.IntConsumer observer) { recovered = observer; }
        /// Installs bounded failure feedback for the launcher overlay.
        public void observeFailures(java.util.function.BiConsumer<Integer, Integer> observer) { failures = observer; }
        /// Preserves stop-on-error for callers that do not explicitly configure a policy.
        public Control() { this(FailurePolicy.STOP, 3); }
        /// Creates a bounded failure policy for this execution.
        public Control(FailurePolicy policy, int repeats) { if (repeats < 0 || repeats > 100) throw new IllegalArgumentException("script_repeat_count must be in [0, 100]"); this.policy = policy; this.repeats = repeats; }
        /// Installs an execution observer; UI observers must dispatch to their own thread.
        public void observe(java.util.function.IntConsumer observer) { trace = observer; }
        /// Cancellation is visible immediately to the worker.
        private volatile boolean cancelled;
        /// Maximum script runtime includes waits and host operations except explicit user confirmation.
        private final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
        /// Time spent waiting for the player's decision does not consume action deadlines.
        private long confirmationNanos, confirmationStarted;
        /// Returns a monotonic execution clock with explicit confirmation waits removed.
        public long now() { long actual = System.nanoTime(); return actual - confirmationNanos - (confirmationStarted == 0 ? 0 : actual - confirmationStarted); }
        /// Pauses the execution clock while leaving cancellation and instruction limits active.
        public void beginConfirmation() { if (confirmationStarted == 0) confirmationStarted = System.nanoTime(); }
        /// Restores the clock after consent, denial, cancellation or a dialog failure.
        public void endConfirmation() { if (confirmationStarted != 0) { confirmationNanos += System.nanoTime() - confirmationStarted; confirmationStarted = 0; } }
        /// Bounds tight loops and expression evaluation.
        private int remaining = 200_000;
        /// Requests cooperative cancellation without affecting the game process.
        public void cancel() { cancelled = true; }
        /// Checks cancellation and consumes one interpreter instruction.
        public void check() { if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException("Script stopped"); if (--remaining < 0 || now() > deadline) throw new IllegalStateException("Script execution budget exceeded"); }
        /// Sleeps in short, cancellable increments; duration is bounded to one minute per call.
        public void sleep(double seconds) throws InterruptedException { if (!Double.isFinite(seconds) || seconds < 0 || seconds > 60) throw new IllegalArgumentException("Duration must be between 0s and 60s"); long end = now() + (long) (seconds * 1_000_000_000L); do { check(); long left = end - now(); if (left <= 0) return; Thread.sleep(Math.min(25, Math.max(1, left / 1_000_000))); } while (true); }
    }
    /// Compiled, immutable script and its conservative permission declaration, including untaken branches.
    @NotNullByDefault public static final class Program {
        /// Parsed top-level code, detached from the editor buffer.
        private final @Unmodifiable List<Stmt> statements;
        /// All game capabilities referenced by the script.
        private final @Unmodifiable Set<String> permissions;
        /// Captures a completed parse.
        private final @Unmodifiable Map<String, FunctionDef> functions;
        /// Captures functions and effects after parsing all imports.
        private Program(List<Stmt> statements, Set<String> permissions, Map<String,FunctionDef> functions) { this.statements = List.copyOf(statements); this.permissions = Set.copyOf(permissions); this.functions = Map.copyOf(functions); }
        /// Exposes the permissions that must be granted before execution.
        public @Unmodifiable Set<String> permissions() { return permissions; }
        /// Executes with isolated variables and always releases the run's resources.
        public void run(Host host, Set<String> granted, Consumer<String> output, Control control) throws Exception {
            @Nullable Throwable failure = null;
            try { if (!granted.containsAll(permissions)) throw new SecurityException("Permission denied: " + String.join(", ", permissions.stream().filter(p -> !granted.contains(p)).sorted().toList())); Context context = new Context(host, granted, output, control); context.functions = functions; for (Stmt statement : statements) statement.run(context); }
            catch (Exception | Error error) { failure = error; throw error; }
            finally { try { host.close(); } catch (Exception cleanup) { if (failure != null) failure.addSuppressed(cleanup); else throw cleanup; } }
        }
    }
    /// Utility class.
    private OchreScript() { }
    /// Parses completely before allowing effects; limits source size, token count and recursion depth.
    public static Program compile(String source) { if (source.length() > 65_536) throw new ScriptError("Script exceeds 64 KiB", 1, 1); return new Parser(source, new Imports(null), "main").parse(); }
    /// Resolves only an explicitly named managed script; the core interpreter never opens paths.
    @NotNullByDefault public interface SourceResolver { /// Reads a managed module before authorization or execution.
        String read(String name) throws Exception;
    }
    /// Compiles with bounded, declaration-only modules and an explicit library resolver.
    public static Program compile(String source, SourceResolver resolver) { return new Parser(source,new Imports(resolver),"main").parse(); }
    /// Shared import budget and cycle detection across one immutable compilation.
    @NotNullByDefault private static final class Imports {
        /// Missing resolver deliberately disables imports for headless callers.
        final @Nullable SourceResolver resolver;
        /// Cycle and duplicate tracking uses names, never filesystem paths.
        final Set<String> loading=new HashSet<>(), loaded=new HashSet<>();
        /// Repeated imports reuse the same immutable declarations.
        final Map<String,Program> cache=new HashMap<>();
        /// Limits total merged source length.
        int remaining=65_536;
        /// Creates a compilation-scoped resolver.
        Imports(@Nullable SourceResolver resolver){this.resolver=resolver;}
        /// Loads declaration-only modules and rejects cycles before any script executes.
        Program load(String name){if(resolver==null)throw new IllegalArgumentException("Imports require a managed library resolver");if(!name.matches("[A-Za-z0-9_][A-Za-z0-9_. -]{0,95}\\.ochre"))throw new IllegalArgumentException("Invalid managed module name");if(loading.contains(name))throw new IllegalArgumentException("Import cycle: "+name);if(loaded.contains(name))return Objects.requireNonNull(cache.get(name));if(loading.size()>=8||loaded.size()>=16)throw new IllegalArgumentException("Import limit exceeded");loading.add(name);try{Program module=new Parser(resolver.read(name),this,name).parse();if(!module.statements.isEmpty())throw new IllegalArgumentException("Imported modules may only contain function declarations");loaded.add(name);cache.put(name,module);return module;}catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalArgumentException("Import failed: "+name+": "+e.getMessage());}finally{loading.remove(name);}}
    }
    /// User-defined functions share explicit capabilities and bounded execution, but not caller locals.
    @NotNullByDefault private record FunctionDef(@Unmodifiable List<String> parameters, Stmt body, String source) { }
    /// Internal return flow cannot be mistaken for a retryable statement failure.
    @NotNullByDefault private static final class ReturnValue extends RuntimeException {
        /// Returned DSL value, never a host object.
        final Object value;
        /// Constructs stackless interpreter control flow.
        ReturnValue(Object value){super(null,null,false,false);this.value=value;}
    }
    /// Formats values without exposing host objects or private bridge tokens.
    public static String display(Object value) { if (value == Empty.NONE) return "none"; if (value instanceof Double number && number == Math.rint(number)) return Long.toString(number.longValue()); return String.valueOf(value); }
    /// A lexical token including its original location.
    @NotNullByDefault private record Token(String text, int line, int column, boolean string) { }
    /// A typed, possibly uninitialized local variable.
    @NotNullByDefault private static final class Variable {
        /// Declared type name.
        final String type;
        /// Current script value, never Java null.
        Object value;
        /// Initializes a variable slot.
        Variable(String type, Object value) { this.type = type; this.value = value; }
    }
    /// Per-run environment; permissions are checked again at every game operation.
    @NotNullByDefault private static final class Context {
        /// Explicit host boundary.
        final Host host;
        /// Player-approved capabilities for this run only.
        final @Unmodifiable Set<String> granted;
        /// Bounded UI output receiver.
        final Consumer<String> output;
        /// This run retains at most two hundred output lines for script.logs().
        final ArrayDeque<String> logs = new ArrayDeque<>();
        /// Cancellation and limits.
        final Control control;
        /// Lexically scoped variables.
        final Deque<Map<String, Variable>> scopes = new ArrayDeque<>();
        /// Immutable declarations copied from the compiled program.
        @Unmodifiable Map<String,FunctionDef> functions=Map.of();
        /// Function recursion is separately bounded before Java stack exhaustion.
        int callDepth;
        /// Calls a declaration with positional/named arguments and an isolated local scope.
        Object invoke(String name,List<Object> values,Map<String,Object> named)throws Exception{FunctionDef fn=Objects.requireNonNull(functions.get(name));if(values.size()>fn.parameters.size())throw new IllegalArgumentException("Too many arguments: "+name);Map<String,Object> args=new LinkedHashMap<>();for(int i=0;i<values.size();i++)args.put(fn.parameters.get(i),values.get(i));for(var e:named.entrySet()){if(!fn.parameters.contains(e.getKey())||args.putIfAbsent(e.getKey(),e.getValue())!=null)throw new IllegalArgumentException("Invalid or duplicate argument: "+e.getKey());}if(args.size()!=fn.parameters.size())throw new IllegalArgumentException("Missing function arguments: "+name);if(callDepth>=16)throw new IllegalArgumentException("Function recursion exceeds 16 calls");callDepth++;Deque<Map<String,Variable>> saved=new ArrayDeque<>(scopes);String label=control.sourceLabel;scopes.clear();scopes.addLast(saved.getLast());Map<String,Variable> locals=new HashMap<>();args.forEach((key,value)->locals.put(key,new Variable("any",value)));scopes.push(locals);control.sourceLabel=fn.source+" / "+name;try{fn.body.run(this);return Empty.NONE;}catch(ReturnValue returned){return returned.value;}finally{control.sourceLabel=label;scopes.clear();scopes.addAll(saved);callDepth--;}}

        /// Creates a fresh root scope.
        Context(Host host, Set<String> granted, Consumer<String> output, Control control) { this.host = host; this.granted = Set.copyOf(granted); this.output = line -> { logs.addLast(line); while (logs.size() > 200) logs.removeFirst(); output.accept(line); }; this.control = control; scopes.push(new HashMap<>()); }
        /// Requires a capability immediately before crossing the host boundary.
        void permit(String permission) { control.check(); if (!granted.contains(permission)) throw new SecurityException("Permission denied: " + permission); }
        /// Finds a local without leaking undefined Java values.
        @Nullable Variable find(String name) { for (var scope : scopes) { @Nullable Variable value = scope.get(name); if (value != null) return value; } return null; }
        /// Reads a local or a known game property.
        Object get(String path) throws Exception {
            if (path.equals("script_repeat_count")) return (double) control.repeats;
            String @Unmodifiable [] parts = path.split("\\."); @Nullable Variable variable = find(parts[0]);
            if (variable != null) { Object value = variable.value; if (value == Empty.UNINITIALIZED) throw new IllegalArgumentException("Variable is not initialized: " + parts[0]); for (int i = 1; i < parts.length; i++) { if (parts[i].equals("length") && value instanceof List<?> list) value = (double) list.size(); else if (value instanceof Map<?, ?> map) { @Nullable Object member = map.get(parts[i]); if (member == null) throw new IllegalArgumentException("Unknown property: " + path); value = member; } else throw new IllegalArgumentException("Unknown property: " + path); } return value; }
            if (path.equals("script.running")) { control.check(); return true; }
            if (path.startsWith("body.")) { if (!Set.of("body.eyes", "body.center", "body.feet", "body.right_arm", "body.left_arm").contains(path)) throw new IllegalArgumentException("Unknown body point: " + path); return path.substring(5); }
            for (String base : READS) if (path.startsWith(base + ".")) { permit("game.read"); Object value = host.read(base); for (String part : path.substring(base.length() + 1).split("\\.")) { if (part.equals("length") && value instanceof List<?> list) value = (double) list.size(); else if (value instanceof Map<?, ?> map) { @Nullable Object member = map.get(part); if (member == null) throw new IllegalArgumentException("Unknown property: " + path); value = member; } else throw new IllegalArgumentException("Unknown property: " + path); } return value; }
            if (!READS.contains(path) && !WRITES.contains(path)) throw new IllegalArgumentException("Unknown property: " + path); permit("game.read"); return host.read(path);
        }
        /// Assigns only declared locals or explicitly writable game settings.
        void set(String path, Object value) throws Exception { @Nullable Variable variable = find(path); if (variable != null) { type(variable.type, value); variable.value = value; return; } if (!WRITES.contains(path)) throw new IllegalArgumentException("Unknown or read-only property: " + path); permit("settings.write"); host.write(path, value); }
    }
    /// Expressions are evaluated only by this interpreter.
    @NotNullByDefault private interface Expr { /// Evaluates one expression.
        Object get(Context context) throws Exception; }
    /// Statements receive a positional wrapper for readable errors.
    @NotNullByDefault private interface Stmt { /// Executes one statement.
        void run(Context context) throws Exception; }
    /// Literal expression.
    @NotNullByDefault private record Literal(Object value) implements Expr { /// Returns its immutable value.
        public Object get(Context context) { context.control.check(); return value; } }
    /// A named local, record member or game property.
    @NotNullByDefault private record Name(String path) implements Expr { /// Reads through the allowlisted context.
        public Object get(Context context) throws Exception { context.control.check(); return context.get(path); } }
    /// Unary arithmetic and Boolean negation.
    @NotNullByDefault private record Unary(String operator, Expr operand) implements Expr { /// Rejects implicit truthiness and coercion.
        public Object get(Context context) throws Exception { Object value = operand.get(context); if (operator.equals("!")) return !bool(value); if (value instanceof Quantity q) return new Quantity(-q.value, q.dimension); return -number(value); } }
    /// Binary operations preserve units and short-circuit logical operators.
    @NotNullByDefault private record Binary(Expr left, String operator, Expr right) implements Expr { /// Computes one bounded expression.
        public Object get(Context context) throws Exception {
            context.control.check(); Object a = left.get(context); if (operator.equals("&&") && !bool(a)) return false; if (operator.equals("||") && bool(a)) return true; Object b = right.get(context);
            if (operator.equals("&&") || operator.equals("||")) return bool(b);
            if (operator.equals("==")) return a.equals(b); if (operator.equals("!=")) return !a.equals(b);
            if (operator.equals("+") && a instanceof String text && b instanceof String other) { if (text.length() + other.length() > 8192) throw new IllegalArgumentException("String exceeds 8192 characters"); return text + other; }
            if (a instanceof Quantity qa || b instanceof Quantity) { if (a instanceof Quantity x && b instanceof Quantity y && x.dimension.equals(y.dimension)) { if (Set.of("<", "<=", ">", ">=").contains(operator)) return compare(x.value, y.value, operator); if (operator.equals("+") || operator.equals("-")) return new Quantity(finite(operator.equals("+") ? x.value + y.value : x.value - y.value), x.dimension); } throw new IllegalArgumentException("Incompatible units"); }
            double x = number(a), y = number(b); return switch (operator) { case "+" -> finite(x + y); case "-" -> finite(x - y); case "*" -> finite(x * y); case "/" -> { if (y == 0) throw new IllegalArgumentException("Division by zero"); yield finite(x / y); } case "%" -> { if (y == 0) throw new IllegalArgumentException("Division by zero"); yield finite(x % y); } default -> compare(x, y, operator); };
        }
    }
    /// A builtin function with positional and named argument expressions.
    @NotNullByDefault private record Call(String name, @Unmodifiable List<Expr> positional, @Unmodifiable Map<String, Expr> named) implements Expr { /// Executes only a parser-validated builtin.
        public Object get(Context context) throws Exception {
            context.control.check(); List<Object> arguments = new ArrayList<>(); for (Expr expr : positional) arguments.add(expr.get(context)); Map<String, Object> keywords = new LinkedHashMap<>(); for (var entry : named.entrySet()) keywords.put(entry.getKey(), entry.getValue().get(context));
            if(context.functions.containsKey(name))return context.invoke(name,List.copyOf(arguments),Map.copyOf(keywords));
            if (name.equals("script.logs")) { if (!arguments.isEmpty() || !keywords.isEmpty()) throw new IllegalArgumentException("script.logs expects no arguments"); return List.copyOf(context.logs); }
            if (name.equals("print")) { if (!keywords.isEmpty()) throw new IllegalArgumentException("print does not accept named arguments"); context.output.accept(arguments.stream().map(OchreScript::display).collect(java.util.stream.Collectors.joining(" "))); return Empty.NONE; }
            if (name.equals("say")) {
                if (arguments.isEmpty() || arguments.size() > 2 || !keywords.keySet().stream().allMatch(key -> key.equals("duration")) || (arguments.size() == 2 && keywords.containsKey("duration"))) throw new IllegalArgumentException("say expects text and an optional duration");
                if (!(arguments.get(0) instanceof String text) || text.isBlank() || text.length() > 256) throw new IllegalArgumentException("say text must contain 1..256 characters");
                Object value = arguments.size() == 2 ? arguments.get(1) : keywords.getOrDefault("duration", new Quantity(3, "time"));
                double seconds = quantity(value, "time"); if (seconds <= 0 || seconds > 30) throw new IllegalArgumentException("say duration must be in (0s, 30s]");
                return context.host.call("say", List.of(text, new Quantity(seconds, "time")), Map.of(), context.control);
            }
            if (name.equals("sleep")) { if (arguments.size() != 1 || !keywords.isEmpty()) throw new IllegalArgumentException("sleep expects one duration"); context.control.sleep(quantity(arguments.get(0), "time")); return Empty.NONE; }
            if (name.equals("vec3")) { if (arguments.size() != 3 || !keywords.isEmpty()) throw new IllegalArgumentException("vec3 expects x, y, z"); return Map.of("x", number(arguments.get(0)), "y", number(arguments.get(1)), "z", number(arguments.get(2))); }
            for (String permission : additionalPermissions(name)) context.permit(permission);
            if (mayBuildBridge(name, positional, named)) context.permit("world.place");
            context.permit(CALLS.getOrDefault(name, "unsupported")); Object result = context.host.call(name, List.copyOf(arguments), Map.copyOf(keywords), context.control); context.control.check(); return result;
        }
    }
    /// Wraps runtime errors with the source statement's location, preserving cancellation.
    @NotNullByDefault private record Located(Token token, Stmt statement) implements Stmt { /// Runs a single checked statement.
        public void run(Context context) throws Exception {
            int attempts = 0;
            while (true) { context.control.check(); context.control.trace.accept(token.line);
                try { statement.run(context); context.control.recovered.accept(token.line); return; }
                catch (ScriptError | CancellationException | ReturnValue error) { throw error; }
                catch (Exception error) {
                    context.control.failures.accept(token.line, attempts + 1);
                    // A denied grant is never bypassed by a retry/skip preference.
                    if (error instanceof SecurityException || context.control.policy == FailurePolicy.STOP || (context.control.policy == FailurePolicy.RETRY_COUNT && attempts >= context.control.repeats))
                        throw new ScriptError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), token.line, token.column);
                    context.output.accept("Line " + token.line + ": " + error.getMessage());
                    if (context.control.policy == FailurePolicy.SKIP) return;
                    attempts++; context.control.sleep(.25);
                }
            }
        }
    }
    /// A block owns its local scope even when it exits exceptionally.
    @NotNullByDefault private record Block(@Unmodifiable List<Stmt> statements) implements Stmt { /// Executes a lexical block.
        public void run(Context context) throws Exception { context.scopes.push(new HashMap<>()); try { for (Stmt statement : statements) statement.run(context); } finally { context.scopes.pop(); } } }
    /// Supported readable values; arbitrary names never reach the bridge.
    private static final @Unmodifiable Set<String> READS = Set.of("game.language", "world.day_time", "world.game_time", "player.position", "player.rotation", "player.health", "player.inventory.slots", "player.effects", "player.recipes", "player.states", "player.inputs", "player.vitals", "player.container", "game.screen", "game.observation_status", "player.hotbar", "script.running");
    /// Writable settings are intentionally smaller than the readable world state.
    private static final @Unmodifiable Set<String> WRITES = Set.of("settings.audio.master", "settings.audio.music", "settings.audio.weather", "settings.video.render_distance", "settings.video.max_fps", "settings.video.gamma", "settings.video.fov", "settings.video.fullscreen");
    /// Registered host calls and their required player grants.
    private static final @Unmodifiable Map<String, String> CALLS = Map.ofEntries(Map.entry("world.find_player", "game.read"), Map.entry("world.nearby_players", "game.read"), Map.entry("world.nearest_player", "game.read"), Map.entry("world.nearest_entity", "game.read"), Map.entry("world.nearest_block", "game.read"), Map.entry("player.look", "player.look"), Map.entry("player.look_at", "player.look"), Map.entry("player.look_at_block", "player.look"), Map.entry("player.walk_forward", "player.move"), Map.entry("player.run_forward", "player.move"), Map.entry("player.walk_to", "player.move"), Map.entry("player.hit", "player.attack"), Map.entry("player.jump", "player.move"), Map.entry("player.set_state", "player.state"), Map.entry("player.move_item", "inventory.write"), Map.entry("player.select_hotbar", "inventory.write"), Map.entry("player.craft", "inventory.write"), Map.entry("game.command", "game.command"), Map.entry("player.events", "game.read"), Map.entry("player.snapshot", "game.read"), Map.entry("world.block", "game.read"), Map.entry("world.target_block", "game.read"), Map.entry("player.lock_block", "player.look"), Map.entry("player.mine", "world.break"), Map.entry("player.place_block", "world.place"), Map.entry("game.logs", "logs.read"), Map.entry("input.key", "input.keys"), Map.entry("input.send_key", "input.keys"), Map.entry("input.key_down", "input.keys"), Map.entry("input.key_up", "input.keys"), Map.entry("input.text", "input.text"), Map.entry("input.send_text", "input.text"), Map.entry("chat.send", "chat.send"), Map.entry("task.load", "storage.read"), Map.entry("task.save", "storage.write"), Map.entry("task.clear", "storage.write"), Map.entry("world.target", "game.read"), Map.entry("world.collision", "game.read"), Map.entry("world.can_stand", "game.read"), Map.entry("world.path_clear", "game.read"), Map.entry("player.item_tags", "game.read"), Map.entry("player.item_is", "game.read"), Map.entry("player.container_slots", "game.read"), Map.entry("player.container_move", "inventory.write"), Map.entry("player.container_swap", "inventory.write"), Map.entry("player.container_quick_move", "inventory.write"), Map.entry("script.wait_event", "game.read"), Map.entry("input.snapshot", "game.read"), Map.entry("input.pressed_keys", "game.read"), Map.entry("input.is_key_down", "game.read"));
    /// Converts only finite numeric values, never strings or booleans.
    public static double number(Object value) { if (!(value instanceof Number n)) throw new IllegalArgumentException("Expected a number"); return finite(n.doubleValue()); }
    /// Enforces a dimension instead of silently mixing seconds and blocks.
    public static double quantity(Object value, String dimension) { if (!(value instanceof Quantity q) || !q.dimension.equals(dimension)) throw new IllegalArgumentException("Expected " + dimension + " unit"); return q.value; }
    /// Rejects numeric overflow at its origin.
    private static double finite(double value) { if (!Double.isFinite(value)) throw new IllegalArgumentException("Number must be finite"); return value; }
    /// Uses strict Boolean expressions.
    private static boolean bool(Object value) { if (!(value instanceof Boolean b)) throw new IllegalArgumentException("Expected true or false"); return b; }
    /// Compares numeric operands.
    private static boolean compare(double a, double b, String operator) { return switch (operator) { case "<" -> a < b; case "<=" -> a <= b; case ">" -> a > b; case ">=" -> a >= b; default -> throw new IllegalArgumentException("Unknown operator: " + operator); }; }
    /// Compound actions require both inspection and camera control before any side effect.
    private static @Unmodifiable Set<String> additionalPermissions(String name) { return Set.of("player.lock_block", "player.mine", "player.place_block", "player.walk_to").contains(name) ? Set.of("game.read", "player.look") : Set.of(); }
    /// Requests placement permission up front whenever a walk may enable bridging.
    private static boolean mayBuildBridge(String name, List<Expr> positional, Map<String, Expr> named) {
        if (!name.equals("player.walk_to")) return false;
        @Nullable Expr bridge = named.get("bridge");
        if (bridge == null && positional.size() > 2) bridge = positional.get(2);
        return bridge != null && !(bridge instanceof Literal literal && Boolean.FALSE.equals(literal.value()));
    }
    /// Checks declared types on initialization and every later assignment.
    private static void type(String type, Object value) { if (value == Empty.UNINITIALIZED) return; boolean valid = switch (type) { case "int" -> value instanceof Double d && d == Math.rint(d) && Math.abs(d) <= 2_147_483_647; case "float" -> value instanceof Double; case "bool" -> value instanceof Boolean; case "string" -> value instanceof String; case "Player" -> value == Empty.NONE || value instanceof Map<?, ?> map && map.containsKey("id"); case "Vec3" -> value instanceof Map<?, ?> map && map.keySet().containsAll(Set.of("x", "y", "z")); case "any" -> true; case "Record" -> value == Empty.NONE || value instanceof Map<?, ?>; case "List" -> value instanceof List<?>; default -> false; }; if (!valid) throw new IllegalArgumentException("Value does not match type " + type); }
    /// Recursive-descent parser with explicit allowlists and bounded recursion.
    @NotNullByDefault private static final class Parser {
        /// Source tokens including a terminal marker.
        final @Unmodifiable List<Token> tokens;
        /// Conservative permission requirements collected during parsing.
        final Set<String> permissions = new HashSet<>();
        /// Current token cursor.
        int at;
        /// Combined expression and statement recursion depth.
        int depth;
        /// Lexes one source buffer.
        /// Shared source budget and declaration registry include imported modules.
        final Imports imports;
        /// Original library filename for function diagnostics.
        final String sourceLabel;
        /// Declarations can be referenced before their textual definition.
        final Set<String> declaredFunctions=new HashSet<>();
        /// Completed declarations are immutable in the resulting Program.
        final Map<String,FunctionDef> functions=new LinkedHashMap<>();
        /// Return is legal only inside a top-level function declaration.
        boolean inFunction;
        Parser(String source,Imports imports,String label) { this.imports=imports;this.sourceLabel=label;if(source.length()>imports.remaining)throw new ScriptError("Merged source exceeds 64 KiB",1,1);imports.remaining-=source.length();tokens=lex(source);for(int i=0;i+1<tokens.size();i++)if(tokens.get(i).text.equals("function")&&!tokens.get(i).string)declaredFunctions.add(tokens.get(i+1).text); }
        /// Requires a completely parsed input before producing executable code.
        Program parse() { List<Stmt> statements=new ArrayList<>();while(match("import")){Token file=peek();if(!file.string)throw error("Expected module filename string");at++;need(";");Program module=imports.load(file.text);permissions.addAll(module.permissions);for(var e:module.functions.entrySet()){@Nullable var existing=functions.putIfAbsent(e.getKey(),e.getValue());if(existing!=null&&existing!=e.getValue())throw error("Duplicate imported function: "+e.getKey());declaredFunctions.add(e.getKey());}}while(!peek().text.equals("<eof>")){if(match("function")){String name=identifier();if(CALLS.containsKey(name)||Set.of("print","sleep","say","vec3","game","world","player","script","input","settings","chat","task").contains(name))throw error("Reserved function name");need("(");List<String> args=new ArrayList<>();if(!match(")")){do{String parameter=identifier();if(Set.of("game","world","player","script","input","settings","chat","task","script_repeat_count").contains(parameter))throw error("Reserved parameter name");if(args.contains(parameter))throw error("Duplicate parameter");args.add(parameter);if(args.size()>16)throw error("Too many parameters");}while(match(","));need(")");}inFunction=true;Stmt body;try{body=statement();}finally{inFunction=false;}if(!(body instanceof Located located&&located.statement instanceof Block))throw error("Function body requires braces");if(functions.putIfAbsent(name,new FunctionDef(List.copyOf(args),body,sourceLabel))!=null)throw error("Duplicate function: "+name);}else statements.add(statement());}return new Program(statements,permissions,functions); }
        /// Current token.
        Token peek() { return tokens.get(at); }
        /// Consumes a token of the expected spelling.
        Token need(String text) { Token token = peek(); if (!match(text)) throw error("Expected '" + text + "'"); return token; }
        /// Optionally consumes one spelling.
        boolean match(String text) { if (peek().text.equals(text) && !peek().string) { at++; return true; } return false; }
        /// Constructs a positional parse failure.
        ScriptError error(String message) { return new ScriptError(message, peek().line, peek().column); }
        /// Consumes an English identifier.
        String identifier() { Token token = peek(); if (token.string || !token.text.matches("[A-Za-z_][A-Za-z_0-9]*")) throw error("Expected English identifier"); at++; return token.text; }
        /// Bounds parser recursion before descending into nested syntax.
        void enter() { if (++depth > 80) throw error("Syntax nesting limit exceeded"); }
        /// Parses a statement including its source-location wrapper.
        Stmt statement() { enter(); try { Token start = peek(); return new Located(start, statementBody()); } finally { depth--; } }
        /// Parses blocks, declarations, conditions and bounded-runtime loops.
        Stmt statementBody() {
            if (match("return")) { if(!inFunction)throw error("return requires a function");Expr value=peek().text.equals(";")?new Literal(Empty.NONE):expression();need(";");return context->{throw new ReturnValue(value.get(context));}; }
            if (match("{")) { List<Stmt> body = new ArrayList<>(); while (!match("}")) { if (peek().text.equals("<eof>")) throw error("Unclosed block"); body.add(statement()); } return new Block(List.copyOf(body)); }
            if (match("if")) { need("("); Expr test = expression(); need(")"); Stmt yes = statement(); Stmt no = match("else") ? statement() : context -> {}; return context -> { if (bool(test.get(context))) yes.run(context); else no.run(context); }; }
            if (match("while")) { need("("); Expr test = expression(); need(")"); Stmt body = statement(); return context -> { while (bool(test.get(context))) { context.control.check(); body.run(context); } }; }
            if (match("for")) { need("("); if (at + 1 < tokens.size() && tokens.get(at + 1).text.equals("in")) { String name = identifier(); need("in"); Expr items = expression(); need(")"); Stmt body = statement(); return context -> { Object value = items.get(context); if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected a list"); context.scopes.push(new HashMap<>()); try { for (Object item : list) { context.control.check(); context.scopes.peek().put(name, new Variable("item", item)); body.run(context); } } finally { context.scopes.pop(); } }; }
                Stmt initial = simple(); need(";"); Expr test = expression(); need(";"); Stmt next = simple(); need(")"); Stmt body = statement(); return context -> { context.scopes.push(new HashMap<>()); try { initial.run(context); while (bool(test.get(context))) { context.control.check(); body.run(context); next.run(context); } } finally { context.scopes.pop(); } }; }
            Stmt result = simple(); need(";"); return result;
        }
        /// Parses a declaration, assignment, increment or standalone call.
        Stmt simple() {
            if (Set.of("int", "float", "bool", "string", "Player", "Vec3", "List", "Record").contains(peek().text)) { String declared = identifier(), name = identifier(); if (Set.of("game", "world", "player", "settings", "body", "script", "input", "chat", "task", "script_repeat_count", "true", "false", "none").contains(name)) throw error("Reserved variable name"); Expr initial = match("=") ? expression() : new Literal(Empty.UNINITIALIZED); return context -> { if (context.scopes.peek().containsKey(name)) throw new IllegalArgumentException("Duplicate variable: " + name); Object value = initial.get(context); type(declared, value); context.scopes.peek().put(name, new Variable(declared, value)); }; }
            Expr expr = expression(); if (expr instanceof Name name && (match("=") || match("++") || match("--"))) { String operator = tokens.get(at - 1).text; Expr value = operator.equals("=") ? expression() : new Binary(name, operator.equals("++") ? "+" : "-", new Literal(1.0)); if (name.path.startsWith("settings.")) { if (!WRITES.contains(name.path)) throw error("Setting is not writable"); permissions.add("settings.write"); } return context -> context.set(name.path, value.get(context)); } if (!(expr instanceof Call)) throw error("Expected assignment or function call"); return context -> expr.get(context);
        }
        /// Starts a precedence-climbing expression parse.
        Expr expression() { return binary(1); }
        /// Parses operators without evaluating short-circuited branches.
        Expr binary(int minimum) { enter(); try { Expr left = unary(); while (true) { int precedence = switch (peek().text) { case "||" -> 1; case "&&" -> 2; case "==", "!=" -> 3; case "<", "<=", ">", ">=" -> 4; case "+", "-" -> 5; case "*", "/", "%" -> 6; default -> 0; }; if (precedence < minimum) return left; String operator = tokens.get(at++).text; left = new Binary(left, operator, binary(precedence + 1)); } } finally { depth--; } }
        /// Parses prefix operators with a separate recursion guard.
        Expr unary() { enter(); try { if (match("!") || match("-")) return new Unary(tokens.get(at - 1).text, unary()); return atom(); } finally { depth--; } }
        /// Parses literals, names and registered builtin invocations.
        Expr atom() {
            Token token = peek(); if (token.string) { at++; return new Literal(token.text); } if (match("true")) return new Literal(true); if (match("false")) return new Literal(false); if (match("none")) return new Literal(Empty.NONE);
            if (match("(")) { Expr expr = expression(); need(")"); return expr; }
            if (token.text.matches("[0-9]+(?:\\.[0-9]+)?")) { at++; double value = finite(Double.parseDouble(token.text)); String unit = peek().text; if (Set.of("s", "ms", "blocks", "deg").contains(unit)) { at++; return new Literal(new Quantity(unit.equals("ms") ? value / 1000 : value, unit.equals("s") || unit.equals("ms") ? "time" : unit.equals("blocks") ? "distance" : "angle")); } return new Literal(value); }
            StringBuilder path = new StringBuilder(identifier()); while (match(".")) path.append('.').append(identifier()); String name = path.toString();
            if (match("(")) { if (!CALLS.containsKey(name) && !declaredFunctions.contains(name) && !Set.of("print", "sleep", "vec3", "say", "script.logs").contains(name)) throw error("Unknown function: " + name); if (CALLS.containsKey(name)) permissions.add(CALLS.get(name)); permissions.addAll(additionalPermissions(name)); List<Expr> positional = new ArrayList<>(); Map<String, Expr> named = new LinkedHashMap<>(); if (!match(")")) { do { if (at + 1 < tokens.size() && tokens.get(at + 1).text.equals("=")) { String key = identifier(); need("="); if (named.containsKey(key)) throw error("Duplicate argument: " + key); named.put(key, expression()); } else { if (!named.isEmpty()) throw error("Positional argument follows named argument"); positional.add(expression()); } } while (match(",")); need(")"); } if (mayBuildBridge(name, positional, named)) permissions.add("world.place"); return new Call(name, List.copyOf(positional), Collections.unmodifiableMap(named)); }
            if (!name.equals("script_repeat_count") && (READS.contains(name) || WRITES.contains(name) || READS.stream().anyMatch(base -> name.startsWith(base + ".")))) permissions.add("game.read"); return new Name(name);
        }
    }
    /// Tokenizes only documented symbols, English identifiers and quoted strings; comments may contain any language.
    private static @Unmodifiable List<Token> lex(String source) {
        List<Token> tokens = new ArrayList<>(); int at = 0, line = 1, column = 1;
        while (at < source.length()) { char ch = source.charAt(at); if (Character.isWhitespace(ch)) { at++; if (ch == '\n') { line++; column = 1; } else column++; continue; }
            if (ch == '#' || ch == '/' && at + 1 < source.length() && source.charAt(at + 1) == '/') { while (at < source.length() && source.charAt(at) != '\n') { at++; column++; } continue; }
            int start = at, origin = column; boolean string = false; String text;
            if (ch == '"') { string = true; at++; column++; StringBuilder value = new StringBuilder(); boolean closed = false; while (at < source.length()) { char current = source.charAt(at++); column++; if (current == '"') { closed = true; break; } if (current == '\n') throw new ScriptError("Unclosed string", line, origin); if (current == '\\') { if (at >= source.length()) break; char escape = source.charAt(at++); column++; current = switch (escape) { case 'n' -> '\n'; case 't' -> '\t'; case '"' -> '"'; case '\\' -> '\\'; default -> throw new ScriptError("Unknown escape", line, column); }; } value.append(current); if (value.length() > 8192) throw new ScriptError("String exceeds 8192 characters", line, origin); } if (!closed) throw new ScriptError("Unclosed string", line, origin); text = value.toString(); }
            else if (ch >= '0' && ch <= '9') { while (at < source.length() && source.charAt(at) >= '0' && source.charAt(at) <= '9') at++; if (at + 1 < source.length() && source.charAt(at) == '.' && Character.isDigit(source.charAt(at + 1))) { at++; while (at < source.length() && Character.isDigit(source.charAt(at))) at++; } text = source.substring(start, at); column += at - start; }
            else if (ch >= 'A' && ch <= 'Z' || ch >= 'a' && ch <= 'z' || ch == '_') { while (at < source.length() && (source.charAt(at) >= 'A' && source.charAt(at) <= 'Z' || source.charAt(at) >= 'a' && source.charAt(at) <= 'z' || source.charAt(at) >= '0' && source.charAt(at) <= '9' || source.charAt(at) == '_')) at++; text = source.substring(start, at); column += at - start; }
            else { String pair = at + 1 < source.length() ? source.substring(at, at + 2) : ""; if (Set.of("==", "!=", "<=", ">=", "&&", "||", "++", "--").contains(pair)) { text = pair; at += 2; column += 2; } else if ("{}();,.=+-*/%!<>".indexOf(ch) >= 0) { text = Character.toString(ch); at++; column++; } else throw new ScriptError("Unexpected character; use English syntax and units", line, column); }
            tokens.add(new Token(text, line, origin, string)); if (tokens.size() > 20_000) throw new ScriptError("Token limit exceeded", line, column);
        }
        tokens.add(new Token("<eof>", line, column, false)); return List.copyOf(tokens);
    }
}
