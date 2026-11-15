package io.ochre.bridge;

import java.util.*;
import java.util.function.ToIntFunction;

/// One bounded local speech message, replacing older messages rather than growing an unbounded queue.
public final class SpeechBubble {
    private String text = "";
    private long started, duration;
    private int cachedWidth = -1, cachedLines;
    private List<String> cached = List.of();
    /// Time is explicit for deterministic tests; text never becomes a chat message or a game command.
    public void show(String value, double seconds, long now) {
        if (value.isBlank() || value.length() > 256 || value.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) throw new IllegalArgumentException("bubble.text.invalid");
        if (!Double.isFinite(seconds) || seconds <= 0 || seconds > 30) throw new IllegalArgumentException("bubble.duration.invalid");
        text = value.replace("\r\n", "\n").replace('\r', '\n').replace('\t', ' '); started = now; duration = (long) (seconds * 1_000_000_000L); cachedWidth = -1;
    }
    public boolean visible(long now) { return duration > 0 && now - started >= 0 && now - started < duration; }
    /// A short rise/fade-in and fade-out preserve readable text during the rest of the requested duration.
    public double opacity(long now) { if (!visible(now)) return 0; return Math.max(0, Math.min(1, Math.min((now - started) / 160_000_000.0, (duration - (now - started)) / 250_000_000.0))); }
    public double entrance(long now) { return 1 - Math.pow(1 - Math.min(1, Math.max(0, (now - started) / 180_000_000.0)), 3); }
    /// Measures real glyph widths, preserves Unicode code points and bounds visible lines to the available screen space.
    public List<String> lines(ToIntFunction<String> measure, int width, int limit) {
        if (width < 8 || limit < 1 || limit > 8) throw new IllegalArgumentException("bubble.layout.invalid");
        if (width == cachedWidth && limit == cachedLines) return cached;
        List<String> result = new ArrayList<>(); StringBuilder line = new StringBuilder();
        for (int offset = 0; offset < text.length();) {
            int codepoint = text.codePointAt(offset); offset += Character.charCount(codepoint);
            if (codepoint == '\n') { result.add(line.toString()); line.setLength(0); continue; }
            String character = new String(Character.toChars(codepoint));
            if (!line.isEmpty() && measure.applyAsInt(line + character) > width) { result.add(line.toString()); line.setLength(0); }
            line.append(character);
        }
        result.add(line.toString());
        if (result.size() > limit) {
            String last = result.get(limit - 1);
            while (!last.isEmpty() && measure.applyAsInt(last + "…") > width) last = last.substring(0, last.offsetByCodePoints(last.length(), -1));
            result = new ArrayList<>(result.subList(0, limit)); result.set(limit - 1, last + "…");
        }
        cached = List.copyOf(result); cachedWidth = width; cachedLines = limit; return cached;
    }
}
