package org.jackhuang.hmcl.bridge;

import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.util.Duration;
import org.jackhuang.hmcl.server.RuntimeSession;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.theme.Themes;
import org.jackhuang.hmcl.ui.construct.OchreScriptDrafts;
import org.glavo.monetfx.ColorRole;
import org.jetbrains.annotations.NotNullByDefault;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/// Supplies in-game scripts/theme and handles explicitly clicked run/stop events over the authenticated bridge.
@NotNullByDefault
public final class GameOverlayService {
    /// Coalesces polls so a missing game never accumulates network workers.
    private static final AtomicBoolean BUSY = new AtomicBoolean();
    /// One lifecycle poll shared across launcher windows.
    private static final Timeline TIMER = new Timeline();
    /// These maps are touched only by the single coalesced IO task.
    private static final Map<String, Integer> PAGES = new HashMap<>();
    /// Failed or old components are retried slowly, without interrupting the user.
    private static final Map<String, Long> RETRY = new HashMap<>();
    /// Utility class.
    private GameOverlayService() { }
    /// Captures observable UI state on JavaFX and performs network requests on IO workers.
    public static void initialize() {
        if (!TIMER.getKeyFrames().isEmpty()) return;
        TIMER.getKeyFrames().add(new KeyFrame(Duration.millis(250), event -> {
            if (!BUSY.compareAndSet(false, true)) return;
            List<String> keys = List.copyOf(RuntimeSession.runningGames().keySet());
            if (keys.isEmpty()) { BUSY.set(false); return; }
            var palette = org.jackhuang.hmcl.theme.OchrePalette.colors(Themes.getColorScheme());
            var color = palette.get("-ochre-action");
            String theme = String.format(Locale.ROOT, "#%02x%02x%02x", (int)Math.round(color.getRed()*255), (int)Math.round(color.getGreen()*255), (int)Math.round(color.getBlue()*255));
            Map<String, Map<String, String>> grants = new LinkedHashMap<>(); for (String key : keys) grants.put(key, OchreScriptDrafts.grantState(key));
            Schedulers.io().execute(() -> { try {
                List<String> library = OchreScriptDrafts.libraryNames();
                for (String key : keys) {
                    if (RETRY.getOrDefault(key, 0L) > System.nanoTime()) continue;
                    try {
                        if (!BridgeManager.installed(key)) continue;
                        int page = PAGES.getOrDefault(key, 0); boolean reset = page * 8 >= library.size(); if (reset) page = 0;
                        int offset = page * 8, count = Math.min(8, library.size()-offset);
                        Map<String, String> fields = new LinkedHashMap<>(OchreScriptDrafts.overlayState(key)); if ("warning".equals(fields.get("status"))) { var context = BridgeManager.request(key, "script.context", Map.of()); if ("true".equals(context.get("in_world"))) { OchreScriptDrafts.clearWarning(key); fields.putAll(OchreScriptDrafts.overlayState(key)); } } fields.putAll(grants.getOrDefault(key, Map.of())); fields.put("theme", theme); for (String role : List.of("ink", "card", "accent")) { var value = role.equals("accent") ? org.jackhuang.hmcl.theme.OchrePalette.color(Themes.getColorScheme(), ColorRole.PRIMARY) : palette.get("-ochre-"+role); fields.put(role, String.format(Locale.ROOT,"#%02x%02x%02x",(int)Math.round(value.getRed()*255),(int)Math.round(value.getGreen()*255),(int)Math.round(value.getBlue()*255))); } fields.put("total", Integer.toString(library.size())); fields.put("page", Integer.toString(page)); fields.put("count", Integer.toString(count)); if (reset) fields.put("resetPage", "true");
                        for (int i = 0; i < count; i++) fields.put("script."+i, library.get(offset+i));
                        var response = BridgeManager.request(key, "overlay.sync", fields); PAGES.put(key, Integer.parseInt(response.getOrDefault("page", "0")));
                        String action = response.getOrDefault("event", "");
                        if (action.startsWith("grant:")) Platform.runLater(() -> OchreScriptDrafts.grantAction(key, action));
                        if (action.equals("resume")) OchreScriptDrafts.resumeFor(key);
                        if (action.equals("stop")) OchreScriptDrafts.stopFor(key);
                        else if (action.startsWith("run:")) { String name = action.substring(4); if (library.contains(name)) Platform.runLater(() -> OchreScriptDrafts.runFromOverlay(key, name)); }
                    } catch (Exception error) { RETRY.put(key, System.nanoTime()+5_000_000_000L); }
                }
            } finally { BUSY.set(false); } });
        })); TIMER.setCycleCount(Timeline.INDEFINITE); TIMER.play();
    }
}
