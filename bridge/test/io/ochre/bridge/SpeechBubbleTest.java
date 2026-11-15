package io.ochre.bridge;

import java.util.List;
import java.util.function.ToIntFunction;

/// Deterministic text bounds, Unicode layout, expiry and replacement behavior without creating a game process.
public final class SpeechBubbleTest {
    public static void main(String[] args) {
        SpeechBubble bubble = new SpeechBubble(); long now = 100;
        bubble.show("Hello", 3, now); check(bubble.visible(now), "visible immediately"); check(bubble.opacity(now) == 0, "fade starts transparent");
        check(bubble.opacity(now+500_000_000) == 1, "readable plateau"); check(!bubble.visible(now+3_000_000_000L), "expires on time");
        ToIntFunction<String> font = text -> text.codePointCount(0, text.length())*6;
        bubble.show("ab👋c完成", 5, now); List<String> wrapped = bubble.lines(font, 18, 8);
        check(String.join("", wrapped).equals("ab👋c完成"), "Unicode code points preserved"); check(wrapped.stream().allMatch(line -> font.applyAsInt(line)<=18), "bounded measured width");
        bubble.show("first\nsecond\nthird", 5, now); check(bubble.lines(font, 100, 2).equals(List.of("first", "second…")), "multiline bounds include ellipsis");
        bubble.show("new", 1, now+10_000_000_000L); check(bubble.lines(font, 100, 8).equals(List.of("new")), "latest message replaces previous layout"); check(!bubble.visible(now+11_000_000_000L), "replacement gets its own duration");
        for (double duration : new double[]{0, -1, 31, Double.NaN, Double.POSITIVE_INFINITY}) rejected(() -> bubble.show("text", duration, now));
        for (String text : List.of("", " ", "x".repeat(257), "bad\u0000text")) rejected(() -> bubble.show(text, 3, now));
        System.out.println("PASS: speech bounds, fades, expiry, Unicode wrapping, multiline truncation and replacement");
    }
    private static void rejected(Runnable action) { try { action.run(); throw new AssertionError("Invalid bubble accepted"); } catch (IllegalArgumentException expected) { } }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
