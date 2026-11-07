package org.jackhuang.hmcl.server;

import com.google.gson.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import java.io.IOException;
import java.util.*;

/// Server-scoped Modrinth catalog with pagination, categories, project details and exact release selection.
@NotNullByDefault
public final class ServerAddons {
    /// Prevents construction of the catalog adapter.
    private ServerAddons() {}
    /// Project metadata for result rows and detail headers.
    @NotNullByDefault
    public record Project(String id, String slug, String title, String description, String icon, @Unmodifiable List<String> categories, long downloads) {}
    /// One actual provider result page.
    @NotNullByDefault
    public record Page(@Unmodifiable List<Project> projects, int total) {}
    /// An exact upstream version, preserving changelog and primary download identity.
    @NotNullByDefault
    public record Version(String id, String name, String number, String channel, String published, String changelog,
                          @Unmodifiable List<String> gameVersions, @Unmodifiable List<String> loaders, ServerCatalog.Artifact file) {}
    /// Determines the compatible server loader family from installation metadata.
    public static String loader(ServerInstance server) {
        String core = server.settings.getProperty("core", "").toLowerCase(Locale.ROOT);
        if (core.contains("neoforge") || core.equals("youer")) return "neoforge";
        if (core.contains("forge") || core.equals("mohist") || core.equals("catserver")) return "forge";
        if (core.contains("fabric") || core.equals("banner")) return "fabric";
        return Set.of("paper", "purpur", "leaf", "leaves", "spigot", "bukkit", "folia", "pufferfish", "pufferfish purpur").contains(core) ? "paper" : "vanilla";
    }
    /// Returns immutable string arrays from optional JSON metadata.
    private static @Unmodifiable List<String> strings(JsonObject object, String key) {
        List<String> result = new ArrayList<>(); if (object.has(key)) object.getAsJsonArray(key).forEach(value -> result.add(value.getAsString())); return List.copyOf(result);
    }
    /// Reads optional provider text without exposing JSON null values to controls.
    private static String text(JsonObject object, String key) { return object.has(key) && !object.get(key).isJsonNull() ? object.get(key).getAsString() : ""; }
    /// Performs a paged search, always binding game version and loader to the target server.
    public static Page search(ServerInstance server, String query, String category, String sort, int page) throws IOException {
        String loader = loader(server);
        if (loader.equals("vanilla")) return new Page(List.of(), 0);
        JsonArray facets = new JsonArray();
        facets.add(array("project_type:mod", "project_type:plugin")); facets.add(array("server_side:required", "server_side:optional"));
        facets.add(array("versions:" + server.settings.getProperty("version")));
        facets.add(loader.equals("paper") ? array("categories:paper", "categories:spigot", "categories:bukkit") : array("categories:" + loader));
        if (!category.isBlank()) facets.add(array("categories:" + category));
        String url = "https://api.modrinth.com/v2/search?limit=20&offset=" + Math.max(0, page) * 20 + "&index=" + ServerCatalog.encode(sort)
                + "&query=" + ServerCatalog.encode(query) + "&facets=" + ServerCatalog.encode(facets.toString());
        var data = ServerCatalog.json(url).getAsJsonObject(); List<Project> projects = new ArrayList<>();
        for (var entry : data.getAsJsonArray("hits")) {
            var hit = entry.getAsJsonObject(); projects.add(new Project(text(hit, "project_id"), text(hit, "slug"), text(hit, "title"), text(hit, "description"), text(hit, "icon_url"), strings(hit, "categories"), hit.get("downloads").getAsLong()));
        }
        return new Page(List.copyOf(projects), data.get("total_hits").getAsInt());
    }
    /// Builds one OR group of required search facets.
    private static JsonArray array(String... values) { JsonArray result = new JsonArray(); for (String value : values) result.add(value); return result; }
    /// Retrieves all exact releases; the page separates compatible releases from other game versions.
    public static @Unmodifiable List<Version> versions(Project project) throws IOException {
        List<Version> result = new ArrayList<>();
        for (var element : ServerCatalog.json("https://api.modrinth.com/v2/project/" + ServerCatalog.encode(project.id()) + "/version").getAsJsonArray()) {
            var version = element.getAsJsonObject();
            for (var value : version.getAsJsonArray("files")) {
                var file = value.getAsJsonObject();
                if (!file.get("primary").getAsBoolean() || !text(file, "filename").endsWith(".jar")) continue;
                result.add(new Version(text(version, "id"), text(version, "name"), text(version, "version_number"), text(version, "version_type"),
                        text(version, "date_published"), text(version, "changelog"), strings(version, "game_versions"), strings(version, "loaders"),
                        new ServerCatalog.Artifact(text(file, "filename"), text(file, "url"), "SHA-512", text(file.getAsJsonObject("hashes"), "sha512"), "", "", 0))); break;
            }
        }
        return List.copyOf(result);
    }
    /// Checks exact loader and game compatibility before enabling installation.
    public static boolean compatible(Version version, ServerInstance server) {
        String loader = loader(server);
        return version.gameVersions().contains(server.settings.getProperty("version")) && version.loaders().stream()
                .anyMatch(value -> value.equals(loader) || loader.equals("paper") && (value.equals("spigot") || value.equals("bukkit")));
    }
}
