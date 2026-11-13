package io.ochre.bridge;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/// Version-one framed key/value protocol; limits are enforced before allocations or game operations.
public final class Wire {
    /// Distinguishes this endpoint from HTTP/browser traffic.
    private static final int MAGIC = 0x4f434852;
    /// Utility class.
    private Wire() {}
    /// Reads at most 256 fields and 128 KiB, rejecting duplicate keys and incomplete frames.
    public static Map<String, String> read(InputStream stream) throws IOException {
        DataInputStream input = new DataInputStream(stream); if (input.readInt() != MAGIC) throw new IOException("Invalid protocol");
        int count = input.readInt(); if (count < 0 || count > 256) throw new IOException("Too many fields");
        Map<String, String> result = new LinkedHashMap<>(); int[] budget = {128 * 1024};
        for (int i = 0; i < count; i++) { String key = text(input, budget); if (key.length() > 128 || result.containsKey(key)) throw new IOException("Invalid field"); result.put(key, text(input, budget)); } return result;
    }
    /// Sends a complete bounded frame without Java object deserialization.
    public static void write(OutputStream stream, Map<String, String> values) throws IOException {
        if (values.size() > 256) throw new IOException("Too many fields"); DataOutputStream output = new DataOutputStream(stream); output.writeInt(MAGIC); output.writeInt(values.size()); int remaining = 128 * 1024;
        for (var entry : values.entrySet()) for (String text : new String[]{entry.getKey(), entry.getValue()}) { byte[] data = text.getBytes(StandardCharsets.UTF_8); remaining -= data.length; if (data.length > 32768 || remaining < 0) throw new IOException("Frame too large"); output.writeInt(data.length); output.write(data); } output.flush();
    }
    /// Rejects oversized text before reading its content.
    private static String text(DataInputStream input, int[] budget) throws IOException { int size = input.readInt(); if (size < 0 || size > 32768 || size > budget[0]) throw new IOException("Field too large"); budget[0] -= size; byte[] data = input.readNBytes(size); if (data.length != size) throw new EOFException(); return new String(data, StandardCharsets.UTF_8); }
}
