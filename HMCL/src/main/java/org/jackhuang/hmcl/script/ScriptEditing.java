package org.jackhuang.hmcl.script;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import java.util.*;

/// Pure, testable source-edit operations; each result describes one atomic undoable replacement.
@NotNullByDefault
public final class ScriptEditing {
    /// A replacement range and resulting selection in the updated document.
    @NotNullByDefault public record Edit(int start, int end, String replacement, int anchor, int caret) { }
    /// Utility class.
    private ScriptEditing() { }
    /// Produces a replacement with a collapsed caret after its text.
    private static Edit insert(int start, int end, String text) { return new Edit(start, end, text, start + text.length(), start + text.length()); }
    /// Determines whether automatic delimiters would be inside a string or a line comment.
    private static int context(String source, int end) { boolean string = false, escaped = false, comment = false; for (int i = 0; i < end; i++) { char c = source.charAt(i); if (c == '\n') { comment = false; escaped = false; continue; } if (comment) continue; if (string) { if (escaped) escaped = false; else if (c == '\\') escaped = true; else if (c == '"') string = false; } else if (c == '#' || c == '/' && i + 1 < end && source.charAt(i + 1) == '/') comment = true; else if (c == '"') string = true; } return comment ? 2 : string ? escaped ? 3 : 1 : 0; }
    /// Pairs brackets and quotes, wraps selected code, or skips a matching closing delimiter.
    public static @Nullable Edit typed(String source, int anchor, int caret, char typed) { int start = Math.min(anchor, caret), end = Math.max(anchor, caret), state = context(source, start); if (state == 2) return null; if ((state == 0 || state == 1 && typed == '"') && start == end && end < source.length() && source.charAt(end) == typed && ")]}\"".indexOf(typed) >= 0) return new Edit(end, end, "", end + 1, end + 1); if (state != 0) return null; char close = switch (typed) { case '(' -> ')'; case '{' -> '}'; case '[' -> ']'; case '"' -> '"'; default -> 0; }; if (close == 0) return null; String selected = source.substring(start, end); return new Edit(start, end, typed + selected + close, start + 1, start + 1 + selected.length()); }
    /// Removes both halves of an adjacent empty delimiter pair.
    public static @Nullable Edit backspace(String source, int anchor, int caret) { if (anchor != caret || caret == 0 || caret == source.length()) return null; String pair = source.substring(caret - 1, caret + 1); return Set.of("()", "{}", "[]", "\"\"").contains(pair) ? insert(caret - 1, caret + 1, "") : null; }
    /// Adds inherited indentation and inserts a matching closing-brace line when appropriate.
    public static Edit newline(String source, int anchor, int caret) { int start = Math.min(anchor, caret), end = Math.max(anchor, caret), line = start == 0 ? 0 : source.lastIndexOf('\n', start - 1) + 1; String before = source.substring(line, start); String indent = before.substring(0, before.length() - before.stripLeading().length()); boolean block = context(source, start) == 0 && before.stripTrailing().endsWith("{"); String text = "\n" + indent + (block ? "    " : ""); if (block && source.substring(end).stripLeading().startsWith("}")) return new Edit(start, end, text + "\n" + indent, start + text.length(), start + text.length()); return insert(start, end, text); }
    /// Returns the first selected source line.
    private static int first(String source, int anchor, int caret) { int position = Math.min(anchor, caret); return position == 0 ? 0 : source.lastIndexOf('\n', position - 1) + 1; }
    /// Returns the final selected line's end, excluding the terminating line break.
    private static int last(String source, int anchor, int caret) { int position = Math.max(anchor, caret); if (position > Math.min(anchor, caret) && position > 0 && source.charAt(position - 1) == '\n') return position - 1; int end = source.indexOf('\n', position); return end < 0 ? source.length() : end; }
    /// Indents or outdents selected lines using four spaces.
    public static Edit indent(String source, int anchor, int caret, boolean reverse) { if (anchor == caret && !reverse) return insert(caret, caret, "    "); int start = first(source, anchor, caret), end = last(source, anchor, caret); List<String> lines = new ArrayList<>(); for (String line : source.substring(start, end).split("\n", -1)) { int count = 0; while (count < Math.min(4, line.length()) && line.charAt(count) == ' ') count++; lines.add(reverse ? line.startsWith("\t") ? line.substring(1) : line.substring(count) : "    " + line); } String text = String.join("\n", lines); return new Edit(start, end, text, start, start + text.length()); }
    /// Toggles English line comments across the selected lines without consuming indentation.
    public static Edit comment(String source, int anchor, int caret) { int start = first(source, anchor, caret), end = last(source, anchor, caret); String @Unmodifiable [] lines = source.substring(start, end).split("\n", -1); boolean remove = Arrays.stream(lines).filter(line -> !line.isBlank()).allMatch(line -> line.stripLeading().startsWith("//")); List<String> result = new ArrayList<>(); for (String line : lines) { int indent = line.length() - line.stripLeading().length(); if (line.isBlank()) result.add(line); else if (remove) { int cut = indent + 2; if (cut < line.length() && line.charAt(cut) == ' ') cut++; result.add(line.substring(0, indent) + line.substring(cut)); } else result.add(line.substring(0, indent) + "// " + line.substring(indent)); } String text = String.join("\n", result); return new Edit(start, end, text, start, start + text.length()); }
    /// Duplicates the current or selected lines in one undoable edit.
    public static Edit duplicate(String source, int anchor, int caret) { int start = first(source, anchor, caret), end = last(source, anchor, caret); String text = source.substring(start, end); return new Edit(end, end, "\n" + text, end + 1, end + 1 + text.length()); }
    /// Moves a complete selection one line up or down, retaining the moved block's selection.
    public static @Nullable Edit move(String source, int anchor, int caret, boolean down) { int start = first(source, anchor, caret), end = last(source, anchor, caret); String block = source.substring(start, end); if (down) { if (end >= source.length()) return null; int next = source.indexOf('\n', end + 1); if (next < 0) next = source.length(); String neighbor = source.substring(end + 1, next); return new Edit(start, next, neighbor + "\n" + block, start + neighbor.length() + 1, start + neighbor.length() + 1 + block.length()); } if (start == 0) return null; int previous = start < 2 ? 0 : source.lastIndexOf('\n', start - 2) + 1; String neighbor = source.substring(previous, start - 1); return new Edit(previous, end, block + "\n" + neighbor, previous, previous + block.length()); }
}
