// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.util.i18n;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Properties;
import java.util.Objects;
import static org.junit.jupiter.api.Assertions.*;

/// Guards ordinary popup branding while keeping the upstream authors and source URL intact.
@NotNullByDefault
public final class OchreBrandingTest {
    /// The Java-download prompt uses the fork name without modifying copyright attribution.
    @Test
    public void runtimePromptsUseForkNameAndAttributionIsRetained() throws Exception {
        Properties messages = new Properties();
        try (InputStreamReader base = new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/assets/lang/I18N.properties")), StandardCharsets.UTF_8)) {
            messages.load(base);
        }
        try (InputStreamReader reader = new InputStreamReader(Objects.requireNonNull(
                getClass().getResourceAsStream("/assets/lang/I18N_zh_Hans.properties")), StandardCharsets.UTF_8)) {
            messages.load(reader);
        }
        assertTrue(messages.getProperty("launch.advice.require_newer_java_version").contains("ØchreCL"));
        assertFalse(messages.getProperty("launch.advice.require_newer_java_version").contains("HMCL"));
        assertTrue(messages.getProperty("launch.advice.require_newer_java_version").contains("%s"));
        assertTrue(messages.getProperty("about.copyright.statement").contains("HMCL"));
        assertTrue(messages.getProperty("about.fork.origin").contains("HMCL"));
        assertTrue(messages.getProperty("about.fork.origin.statement").contains("%s"));
        assertFalse(messages.getProperty("account.methods.microsoft.snapshot").contains("HMCL"));
    }
}
