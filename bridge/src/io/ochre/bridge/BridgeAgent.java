package io.ochre.bridge;

import java.io.*;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.*;
import java.net.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/// Optional loader-free client bridge. It observes public client APIs and does not rewrite the Minecraft JAR.
public final class BridgeAgent {
    /// Instrumentation supplies the actual game class loader, including optional third-party loaders.
    private static Instrumentation instrumentation;
    /// Launch authorization is read from a private file, never passed as a command-line token.
    private static String token;
    /// Game-thread tasks are cancelled if their caller times out before execution.
    private static final Set<String> OPTIONS = Set.of("soundCategory_master", "soundCategory_music", "soundCategory_weather", "renderDistance", "maxFps", "gamma", "fov", "fullscreen");
    /// Utility entry point.
    private BridgeAgent() {}
    /// Starts only when explicitly installed/enabled for this game instance.
    public static void premain(String argument, Instrumentation supplied) {
        try {
            instrumentation = supplied; Path config = Path.of(new String(Base64.getUrlDecoder().decode(argument), java.nio.charset.StandardCharsets.UTF_8));
            Properties values = new Properties(); try (var input = Files.newInputStream(config)) { values.load(input); }
            token = values.getProperty("token", ""); if (!"true".equals(values.getProperty("enabled")) || !token.matches("[a-f0-9]{64}")) return;
            ServerSocket server = new ServerSocket(); server.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 8);
            Path endpoint = config.resolveSibling("connection.properties"); Properties connection = new Properties(); connection.setProperty("port", Integer.toString(server.getLocalPort())); connection.setProperty("pid", Long.toString(ProcessHandle.current().pid())); connection.setProperty("protocol", "1");
            Path staged = endpoint.resolveSibling("connection.properties.new"); try (var output = Files.newOutputStream(staged)) { connection.store(output, "Local OchreCL bridge endpoint"); } restrict(staged); Files.move(staged, endpoint, StandardCopyOption.REPLACE_EXISTING);
            ThreadPoolExecutor handlers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(4), task -> { Thread thread = new Thread(task, "OchreBridge request"); thread.setDaemon(true); return thread; });
            Thread listener = new Thread(() -> { while (!server.isClosed()) try { Socket socket = server.accept(); try { handlers.execute(() -> serve(socket)); } catch (RejectedExecutionException busy) { socket.close(); } } catch (IOException stopped) { break; } }, "OchreBridge loopback"); listener.setDaemon(true); listener.start();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { try { server.close(); handlers.shutdownNow(); Properties current = new Properties(); if (Files.isRegularFile(endpoint)) { try (var input = Files.newInputStream(endpoint)) { current.load(input); } if (Long.toString(ProcessHandle.current().pid()).equals(current.getProperty("pid"))) Files.deleteIfExists(endpoint); } } catch (IOException ignored) { } }, "OchreBridge close"));
            System.out.println("[OchreBridge] Local bridge enabled; protocol 1");
        } catch (Exception error) { System.err.println("[OchreBridge] Bridge disabled: " + error.getClass().getSimpleName()); }
    }
    /// Handles one authenticated, bounded request and closes the connection after its response.
    private static void serve(Socket socket) {
        try (socket) {
            socket.setSoTimeout(2000); Map<String, String> request = Wire.read(socket.getInputStream()); Map<String, String> response = new LinkedHashMap<>(); response.put("protocol", "1");
            if (!MessageDigest.isEqual(token.getBytes(java.nio.charset.StandardCharsets.US_ASCII), request.getOrDefault("token", "").getBytes(java.nio.charset.StandardCharsets.US_ASCII))) { response.put("ok", "false"); response.put("error", "unauthorized"); }
            else if (!"1".equals(request.get("protocol"))) { response.put("ok", "false"); response.put("error", "protocol.unsupported"); }
            else try { response.putAll(dispatch(request)); response.put("ok", "true"); } catch (Exception error) { response.put("ok", "false"); response.put("error", error instanceof TimeoutException ? "game.thread.timeout" : error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage()); }
            Wire.write(socket.getOutputStream(), response);
        } catch (Exception ignored) { /* Unauthenticated malformed frames receive no information or game operations. */ }
    }
    /// Finds the actual named 26.x client, never inventing support for obfuscated or incompatible versions.
    private static Object client() throws Exception {
        for (Class<?> type : instrumentation.getAllLoadedClasses()) if (type.getName().equals("net.minecraft.client.Minecraft")) { Object client = type.getMethod("getInstance").invoke(null); if (client != null) return client; }
        throw new IOException("game.not.ready");
    }
    /// Runs only registered commands and only mutates settings on the Minecraft client thread.
    private static Map<String, String> dispatch(Map<String, String> request) throws Exception {
        Object client = client(); if (!(client instanceof Executor executor)) throw new IOException("game.executor.unsupported");
        var future = new CompletableFuture<Map<String, String>>(); AtomicBoolean cancelled = new AtomicBoolean();
        executor.execute(() -> { if (cancelled.get()) return; try { future.complete(onGameThread(client, request)); } catch (Exception error) { future.completeExceptionally(error); } });
        try { return future.get(1500, TimeUnit.MILLISECONDS); } catch (ExecutionException error) { throw new IOException(error.getCause().getMessage(), error.getCause()); } finally { cancelled.set(true); }
    }
    /// Resolves capabilities from actual public methods rather than version strings alone.
    private static Map<String, String> onGameThread(Object client, Map<String, String> request) throws Exception {
        ClassLoader loader = client.getClass().getClassLoader(); Class<?> constants = Class.forName("net.minecraft.SharedConstants", false, loader); Object version = constants.getMethod("getCurrentVersion").invoke(null); String game = String.valueOf(call(version, "name"));
        if (!game.matches("26\\..*")) throw new IOException("game.version.unsupported");
        Object options = client.getClass().getField("options").get(client); Map<String, String> response = new LinkedHashMap<>(); response.put("gameVersion", game); response.put("bridgeVersion", "0.1.0-alpha");
        StringBuilder capabilities = new StringBuilder("state.read");
        for (String name : OPTIONS) try { option(options, name); capabilities.append(',').append("option.").append(name); } catch (ReflectiveOperationException ignored) { }
        response.put("capabilities", capabilities.toString());
        String command = request.getOrDefault("command", "hello");
        switch (command) {
            case "hello" -> { }
            case "options.get" -> { for (String name : OPTIONS) try { Object value = call(option(options, name), "get"); response.put(name, name.equals("fov") ? Double.toString((Double.parseDouble(value.toString()) - 70) / 40) : value.toString()); } catch (ReflectiveOperationException ignored) { } }
            case "options.set" -> {
                Map<Object, Object> next = new LinkedHashMap<>(), original = new LinkedHashMap<>();
                for (var entry : request.entrySet()) if (entry.getKey().startsWith("option.")) {
                    String name = entry.getKey().substring(7); if (!OPTIONS.contains(name)) throw new IOException("option.unsupported:" + name);
                    Object target = option(options, name); Object value = validated(name, entry.getValue()); next.put(target, value); original.put(target, call(target, "get"));
                }
                if (next.isEmpty()) throw new IOException("options.empty");
                try { for (var entry : next.entrySet()) entry.getKey().getClass().getMethod("set", Object.class).invoke(entry.getKey(), entry.getValue()); call(options, "save"); }
                catch (Exception error) { for (var entry : original.entrySet()) try { entry.getKey().getClass().getMethod("set", Object.class).invoke(entry.getKey(), entry.getValue()); } catch (Exception ignored) { } throw error; }
                response.put("applied", "true");
            }
            case "state.read" -> {
                response.put("language", String.valueOf(options.getClass().getField("languageCode").get(options)));
                Object player = client.getClass().getField("player").get(client); response.put("inWorld", Boolean.toString(player != null));
                if (player != null) { for (String property : new String[]{"X", "Y", "Z", "YRot", "XRot"}) response.put("player." + property, String.valueOf(call(player, "get" + property))); response.put("player.id", String.valueOf(call(player, "getUUID"))); }
            }
            default -> throw new IOException("command.unsupported");
        }
        return response;
    }
    /// Maps protocol keys to typed vanilla option objects without accessing private fields.
    private static Object option(Object options, String key) throws ReflectiveOperationException {
        if (key.startsWith("soundCategory_")) { Class<?> category = Class.forName("net.minecraft.sounds.SoundSource", false, options.getClass().getClassLoader()); @SuppressWarnings({"unchecked", "rawtypes"}) Object value = Enum.valueOf((Class) category, key.substring(14).toUpperCase(Locale.ROOT)); return options.getClass().getMethod("getSoundSourceOptionInstance", category).invoke(options, value); }
        return call(options, key.equals("maxFps") ? "framerateLimit" : key);
    }
    /// Checks all setting ranges before any mutation, and rejects non-finite values and unknown boolean text.
    private static Object validated(String name, String text) throws IOException {
        if (name.equals("fullscreen")) { if (!text.equals("true") && !text.equals("false")) throw new IOException("option.invalid"); return Boolean.valueOf(text); }
        double number; try { number = Double.parseDouble(text); } catch (NumberFormatException error) { throw new IOException("option.invalid"); }
        double min = name.equals("fov") ? -1 : name.equals("renderDistance") ? 2 : name.equals("maxFps") ? 10 : 0, max = name.equals("fov") || name.equals("gamma") || name.startsWith("sound") ? 1 : name.equals("renderDistance") ? 32 : 260;
        if (!Double.isFinite(number) || number < min || number > max) throw new IOException("option.out.of.range");
        if (name.equals("fov")) return Integer.valueOf((int) Math.round(70 + 40 * number));
        if (name.equals("renderDistance") || name.equals("maxFps")) return Integer.valueOf((int) Math.round(number)); return Double.valueOf(number);
    }
    /// Calls an exposed no-argument client API.
    private static Object call(Object target, String name) throws ReflectiveOperationException { return target.getClass().getMethod(name).invoke(target); }
    /// Restricts endpoint metadata on filesystems supporting POSIX permissions.
    private static void restrict(Path file) throws IOException { try { Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { } }
}
