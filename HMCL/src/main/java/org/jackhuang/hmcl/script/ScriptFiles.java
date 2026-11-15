package org.jackhuang.hmcl.script;

import org.jetbrains.annotations.NotNullByDefault;
import java.io.IOException;
import java.nio.file.*;
import java.util.UUID;

/// Managed script operations reject traversal, symlinks and replacement of existing scripts.
@NotNullByDefault
public final class ScriptFiles {
    /// Static file operations own no persistent state.
    private ScriptFiles() { }
    /// Resolves one existing regular file directly inside the managed library.
    private static Path managed(Path root, Path path) throws IOException { Path base = root.toAbsolutePath().normalize(), file = path.toAbsolutePath().normalize(); if (!base.equals(file.getParent()) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("无效脚本文件"); return file; }
    /// Renames without overwriting another script; the caller saves editor changes first.
    public static Path rename(Path root, Path path, String name) throws IOException { Path source = managed(root, path); String clean = name.strip(); if (clean.isBlank() || clean.length() > 100 || clean.startsWith(".") || clean.endsWith(".") || clean.codePoints().anyMatch(c -> Character.isISOControl(c) || "/\\:*?\"<>|".indexOf(c) >= 0)) throw new IOException("名称不能为空，不能包含路径或特殊符号"); if (!clean.endsWith(".ochre") && !clean.endsWith(".txt")) clean += ".ochre"; Path target = source.resolveSibling(clean); if (source.equals(target)) return source; return Files.move(source, target); }
    /// Moves removed scripts into a private recovery folder instead of destroying their contents.
    public static Path remove(Path root, Path path) throws IOException { Path source = managed(root, path), trash = root.resolve(".trash"); if (Files.isSymbolicLink(trash)) throw new IOException("无效回收目录"); Files.createDirectories(trash); return Files.move(source, trash.resolve(UUID.randomUUID() + "-" + source.getFileName())); }
}
