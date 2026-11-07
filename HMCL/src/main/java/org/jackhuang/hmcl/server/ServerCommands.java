package org.jackhuang.hmcl.server;

import org.jetbrains.annotations.NotNullByDefault;
import java.io.IOException;

/// Version-aware administrative commands using Mojang's renamed 1.21.11 game-rule registry.
@NotNullByDefault
public final class ServerCommands {
    /// Utility class.
    private ServerCommands() {}
    /// Detects release-format game-rule naming; snapshots can be manually overridden in the UI.
    public static boolean modern(String version) {
        try { String[] parts = version.split("\\."); int major = Integer.parseInt(parts[0]); return major >= 26 || major == 1 && (Integer.parseInt(parts[1]) > 21 || Integer.parseInt(parts[1]) == 21 && parts.length > 2 && Integer.parseInt(parts[2]) >= 11); }
        catch (RuntimeException exception) { return false; }
    }
    /// Builds one supported game-rule command, translating renamed rules and integer fire-spread behavior.
    public static String rule(String key, boolean enabled, boolean modern) {
        String name = modern ? switch (key) { case "keepInventory" -> "keep_inventory"; case "doDaylightCycle" -> "advance_time"; case "doWeatherCycle" -> "advance_weather"; case "doMobSpawning" -> "spawn_mobs"; case "doFireTick" -> "fire_spread_radius_around_player"; default -> throw new IllegalArgumentException("不支持此快捷规则"); } : key;
        return "gamerule " + (modern ? "minecraft:" : "") + name + " " + (modern && key.equals("doFireTick") ? enabled ? "128" : "0" : Boolean.toString(enabled));
    }
    /// Reads a real rule response; the caller must run this blocking operation on an I/O worker.
    public static boolean readRule(ServerInstance server, String key, boolean modern) throws Exception {
        if (key.equals("white-list")) {
            java.util.Properties values = new java.util.Properties(); try (var input = java.nio.file.Files.newInputStream(server.directory.resolve("server.properties"))) { values.load(input); }
            return Boolean.parseBoolean(values.getProperty("white-list", "false"));
        }
        String command = rule(key, true, modern); command = command.substring(0, command.lastIndexOf(' '));
        String name = command.substring(command.indexOf(' ') + 1).replace("minecraft:", "").toLowerCase(java.util.Locale.ROOT);
        var response = server.session.nextLine(line -> line.toLowerCase(java.util.Locale.ROOT).contains(name) && line.toLowerCase(java.util.Locale.ROOT).contains("currently") && hasRuleValue(line), 4);
        try { ServerManager.command(server, command); return responseValue(response.get(5, java.util.concurrent.TimeUnit.SECONDS)); }
        finally { response.cancel(false); }
    }
    /// Applies a console change; whitelist acknowledgement precedes reading the persisted state.
    public static void applyRule(ServerInstance server, String key, boolean enabled, boolean modern) throws Exception {
        if (!key.equals("white-list")) { ServerManager.command(server, rule(key, enabled, modern)); return; }
        var response = server.session.nextLine(line -> line.toLowerCase(java.util.Locale.ROOT).contains("whitelist"), 4);
        try { ServerManager.command(server, "whitelist " + (enabled ? "on" : "off")); response.get(5, java.util.concurrent.TimeUnit.SECONDS); }
        finally { response.cancel(false); }
    }
    /// Recognizes final boolean/integer values in Minecraft's game-rule output without guessing unknown states.
    private static boolean hasRuleValue(String line) { return java.util.regex.Pattern.compile("(?i)(true|false|-?\\d+)\\s*[.!]?\\s*$").matcher(line).find(); }
    /// Parses the final server-reported value, including numeric fire-spread radius.
    static boolean responseValue(String line) throws IOException {
        var matcher = java.util.regex.Pattern.compile("(?i)(true|false|-?\\d+)\\s*[.!]?\\s*$").matcher(line);
        if (!matcher.find()) throw new IOException("服务器没有返回可识别的规则状态");
        String value = matcher.group(1); if (value.equalsIgnoreCase("true")) return true; if (value.equalsIgnoreCase("false")) return false;
        try { return Integer.parseInt(value) != 0; } catch (NumberFormatException exception) { throw new IOException("规则状态无效", exception); }
    }
    /// Rejects selectors, whitespace and multiline input in player-specific administrative actions.
    public static String player(String name) throws IOException { if (!name.matches("[A-Za-z0-9_]{1,16}")) throw new IOException("玩家名称应为 1–16 位字母、数字或下划线"); return name; }
}
