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
    /// Per-run cancellation, finite execution budget and interruptible waits; no script runs on the UI thread.
    @NotNullByDefault public static final class Control {
        /// Cancellation is visible immediately to the worker.
        private volatile boolean cancelled;
        /// Maximum script runtime includes waits and host operations.
        private final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MINUTES.toNanos(10);
        /// Bounds tight loops and expression evaluation.
        private int remaining = 200_000;
        /// Requests cooperative cancellation without affecting the game process.
        public void cancel() { cancelled = true; }
        /// Checks cancellation and consumes one interpreter instruction.
        public void check() { if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException("Script stopped"); if (--remaining < 0 || System.nanoTime() > deadline) throw new IllegalStateException("Script execution budget exceeded"); }
        /// Sleeps in short, cancellable increments; duration is bounded to one minute per call.
        public void sleep(double seconds) throws InterruptedException { if (!Double.isFinite(seconds) || seconds < 0 || seconds > 60) throw new IllegalArgumentException("Duration must be between 0s and 60s"); long end = System.nanoTime() + (long) (seconds * 1_000_000_000L); do { check(); long left = end - System.nanoTime(); if (left <= 0) return; Thread.sleep(Math.min(25, Math.max(1, left / 1_000_000))); } while (true); }
    }
    /// Compiled, immutable script and its conservative permission declaration, including untaken branches.
    @NotNullByDefault public static final class Program {
        /// Parsed top-level code, detached from the editor buffer.
        private final @Unmodifiable List<Stmt> statements;
        /// All game capabilities referenced by the script.
        private final @Unmodifiable Set<String> permissions;
        /// Captures a completed parse.
        private Program(List<Stmt> statements, Set<String> permissions) { this.statements = List.copyOf(statements); this.permissions = Set.copyOf(permissions); }
        /// Exposes the permissions that must be granted before execution.
        public @Unmodifiable Set<String> permissions() { return permissions; }
        /// Executes with isolated variables and always releases the run's resources.
        public void run(Host host, Set<String> granted, Consumer<String> output, Control control) throws Exception {
            @Nullable Throwable failure = null;
            try { if (!granted.containsAll(permissions)) throw new SecurityException("Permission denied: " + String.join(", ", permissions.stream().filter(p -> !granted.contains(p)).sorted().toList())); Context context = new Context(host, granted, output, control); for (Stmt statement : statements) statement.run(context); }
            catch (Exception | Error error) { failure = error; throw error; }
            finally { try { host.close(); } catch (Exception cleanup) { if (failure != null) failure.addSuppressed(cleanup); else throw cleanup; } }
        }
    }
    /// Utility class.
    private OchreScript() { }
    /// Parses completely before allowing effects; limits source size, token count and recursion depth.
    public static Program compile(String source) { if (source.length() > 65_536) throw new ScriptError("Script exceeds 64 KiB", 1, 1); return new Parser(source).parse(); }
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
        /// Cancellation and limits.
        final Control control;
        /// Lexically scoped variables.
        final Deque<Map<String, Variable>> scopes = new ArrayDeque<>();
        /// Creates a fresh root scope.
        Context(Host host, Set<String> granted, Consumer<String> output, Control control) { this.host = host; this.granted = Set.copyOf(granted); this.output = output; this.control = control; scopes.push(new HashMap<>()); }
        /// Requires a capability immediately before crossing the host boundary.
        void permit(String permission) { control.check(); if (!granted.contains(permission)) throw new SecurityException("Permission denied: " + permission); }
        /// Finds a local without leaking undefined Java values.
        @Nullable Variable find(String name) { for (var scope : scopes) { @Nullable Variable value = scope.get(name); if (value != null) return value; } return null; }
        /// Reads a local or a known game property.
        Object get(String path) throws Exception {
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
            if (name.equals("print")) { if (!keywords.isEmpty()) throw new IllegalArgumentException("print does not accept named arguments"); context.output.accept(arguments.stream().map(OchreScript::display).collect(java.util.stream.Collectors.joining(" "))); return Empty.NONE; }
            if (name.equals("sleep")) { if (arguments.size() != 1 || !keywords.isEmpty()) throw new IllegalArgumentException("sleep expects one duration"); context.control.sleep(quantity(arguments.get(0), "time")); return Empty.NONE; }
            if (name.equals("vec3")) { if (arguments.size() != 3 || !keywords.isEmpty()) throw new IllegalArgumentException("vec3 expects x, y, z"); return Map.of("x", number(arguments.get(0)), "y", number(arguments.get(1)), "z", number(arguments.get(2))); }
            context.permit(CALLS.getOrDefault(name, "unsupported")); Object result = context.host.call(name, List.copyOf(arguments), Map.copyOf(keywords), context.control); context.control.check(); return result;
        }
    }
    /// Wraps runtime errors with the source statement's location, preserving cancellation.
    @NotNullByDefault private record Located(Token token, Stmt statement) implements Stmt { /// Runs a single checked statement.
        public void run(Context context) throws Exception { context.control.check(); try { statement.run(context); } catch (ScriptError | CancellationException error) { throw error; } catch (Exception error) { throw new ScriptError(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage(), token.line, token.column); } }
    }
    /// A block owns its local scope even when it exits exceptionally.
    @NotNullByDefault private record Block(@Unmodifiable List<Stmt> statements) implements Stmt { /// Executes a lexical block.
        public void run(Context context) throws Exception { context.scopes.push(new HashMap<>()); try { for (Stmt statement : statements) statement.run(context); } finally { context.scopes.pop(); } } }
    /// Supported readable values; arbitrary names never reach the bridge.
    private static final @Unmodifiable Set<String> READS = Set.of("game.language", "world.day_time", "world.game_time", "player.position", "player.rotation", "player.health", "player.inventory.slots", "script.running");
    /// Writable settings are intentionally smaller than the readable world state.
    private static final @Unmodifiable Set<String> WRITES = Set.of("settings.audio.master", "settings.audio.music", "settings.audio.weather", "settings.video.render_distance", "settings.video.max_fps", "settings.video.gamma", "settings.video.fov", "settings.video.fullscreen");
    /// Registered host calls and their required player grants.
    private static final @Unmodifiable Map<String, String> CALLS = Map.of("world.find_player", "game.read", "world.nearby_players", "game.read", "player.look", "player.look", "player.look_at", "player.look", "player.walk_forward", "player.move", "player.run_forward", "player.move", "player.walk_to", "player.move", "player.hit", "player.attack");
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
    /// Checks declared types on initialization and every later assignment.
    private static void type(String type, Object value) { if (value == Empty.UNINITIALIZED) return; boolean valid = switch (type) { case "int" -> value instanceof Double d && d == Math.rint(d) && Math.abs(d) <= 2_147_483_647; case "float" -> value instanceof Double; case "bool" -> value instanceof Boolean; case "string" -> value instanceof String; case "Player" -> value == Empty.NONE || value instanceof Map<?, ?> map && map.containsKey("id"); case "Vec3" -> value instanceof Map<?, ?> map && map.keySet().containsAll(Set.of("x", "y", "z")); case "List" -> value instanceof List<?>; default -> false; }; if (!valid) throw new IllegalArgumentException("Value does not match type " + type); }
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
        Parser(String source) { tokens = lex(source); }
        /// Requires a completely parsed input before producing executable code.
        Program parse() { List<Stmt> statements = new ArrayList<>(); while (!peek().text.equals("<eof>")) statements.add(statement()); return new Program(statements, permissions); }
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
            if (match("{")) { List<Stmt> body = new ArrayList<>(); while (!match("}")) { if (peek().text.equals("<eof>")) throw error("Unclosed block"); body.add(statement()); } return new Block(List.copyOf(body)); }
            if (match("if")) { need("("); Expr test = expression(); need(")"); Stmt yes = statement(); Stmt no = match("else") ? statement() : context -> {}; return context -> { if (bool(test.get(context))) yes.run(context); else no.run(context); }; }
            if (match("while")) { need("("); Expr test = expression(); need(")"); Stmt body = statement(); return context -> { while (bool(test.get(context))) { context.control.check(); body.run(context); } }; }
            if (match("for")) { need("("); if (at + 1 < tokens.size() && tokens.get(at + 1).text.equals("in")) { String name = identifier(); need("in"); Expr items = expression(); need(")"); Stmt body = statement(); return context -> { Object value = items.get(context); if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected a list"); context.scopes.push(new HashMap<>()); try { for (Object item : list) { context.control.check(); context.scopes.peek().put(name, new Variable("item", item)); body.run(context); } } finally { context.scopes.pop(); } }; }
                Stmt initial = simple(); need(";"); Expr test = expression(); need(";"); Stmt next = simple(); need(")"); Stmt body = statement(); return context -> { context.scopes.push(new HashMap<>()); try { initial.run(context); while (bool(test.get(context))) { context.control.check(); body.run(context); next.run(context); } } finally { context.scopes.pop(); } }; }
            Stmt result = simple(); need(";"); return result;
        }
        /// Parses a declaration, assignment, increment or standalone call.
        Stmt simple() {
            if (Set.of("int", "float", "bool", "string", "Player", "Vec3", "List").contains(peek().text)) { String declared = identifier(), name = identifier(); if (Set.of("game", "world", "player", "settings", "body", "script", "true", "false", "none").contains(name)) throw error("Reserved variable name"); Expr initial = match("=") ? expression() : new Literal(Empty.UNINITIALIZED); return context -> { if (context.scopes.peek().containsKey(name)) throw new IllegalArgumentException("Duplicate variable: " + name); Object value = initial.get(context); type(declared, value); context.scopes.peek().put(name, new Variable(declared, value)); }; }
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
            if (match("(")) { if (!CALLS.containsKey(name) && !Set.of("print", "sleep", "vec3").contains(name)) throw error("Unknown function: " + name); if (CALLS.containsKey(name)) permissions.add(CALLS.get(name)); List<Expr> positional = new ArrayList<>(); Map<String, Expr> named = new LinkedHashMap<>(); if (!match(")")) { do { if (at + 1 < tokens.size() && tokens.get(at + 1).text.equals("=")) { String key = identifier(); need("="); if (named.containsKey(key)) throw error("Duplicate argument: " + key); named.put(key, expression()); } else { if (!named.isEmpty()) throw error("Positional argument follows named argument"); positional.add(expression()); } } while (match(",")); need(")"); } return new Call(name, List.copyOf(positional), Collections.unmodifiableMap(named)); }
            if (READS.contains(name) || WRITES.contains(name) || READS.stream().anyMatch(base -> name.startsWith(base + "."))) permissions.add("game.read"); return new Name(name);
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
