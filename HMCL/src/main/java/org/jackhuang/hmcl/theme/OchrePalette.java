// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.theme;

import javafx.scene.paint.Color;
import org.glavo.monetfx.ColorRole;
import org.glavo.monetfx.Brightness;
import org.glavo.monetfx.ColorScheme;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Unmodifiable;
import java.util.LinkedHashMap;
import java.util.Map;

/// Product surfaces derived from the current theme, never from fixed light-only colors.
@NotNullByDefault
public final class OchrePalette {
    /// Prevents construction of the stateless palette adapter.
    private OchrePalette() {}

    /// Returns coherent light/dark surfaces and matching text/accent roles for every product component.
    public static @Unmodifiable Map<String, Color> colors(ColorScheme scheme) {
        Map<String, Color> colors = new LinkedHashMap<>();
        colors.put("-ochre-rail", alpha(color(scheme, ColorRole.SURFACE_CONTAINER), .97));
        colors.put("-ochre-panel", alpha(color(scheme, ColorRole.SURFACE), .98));
        colors.put("-ochre-inspector", alpha(color(scheme, ColorRole.SURFACE_CONTAINER_LOW), .98));
        colors.put("-ochre-muted-surface", color(scheme, ColorRole.SURFACE_CONTAINER_HIGH));
        colors.put("-ochre-card", color(scheme, ColorRole.SURFACE_CONTAINER_LOW));
        colors.put("-ochre-log", color(scheme, ColorRole.SURFACE_CONTAINER_LOWEST));
        colors.put("-ochre-ink", color(scheme, ColorRole.ON_SURFACE));
        colors.put("-ochre-muted-ink", color(scheme, ColorRole.ON_SURFACE_VARIANT));
        colors.put("-ochre-action", color(scheme, ColorRole.PRIMARY_CONTAINER));
        colors.put("-ochre-action-ink", color(scheme, ColorRole.ON_PRIMARY_CONTAINER));
        colors.put("-ochre-selected", color(scheme, ColorRole.SECONDARY_CONTAINER));
        colors.put("-ochre-selected-ink", color(scheme, ColorRole.ON_SECONDARY_CONTAINER));
        return Map.copyOf(colors);
    }

    /// Appends looked-up colors to the same stylesheet that carries the resolved Monet color roles.
    public static void appendCss(StringBuilder builder, ColorScheme scheme) {
        if (isIvoryLight(scheme)) {
            for (ColorRole role : ColorRole.ALL) {
                builder.append("  ").append(role.getVariableName()).append(": ")
                        .append(ThemeColor.getColorDisplayName(color(scheme, role))).append(";\n");
            }
        }
        colors(scheme).forEach((name, color) -> builder.append("  ").append(name).append(": ")
                .append(ThemeColor.getColorDisplayNameWithOpacity(color, color.getOpacity())).append(";\n"));
    }

    /// Preserves the designed ivory values only for the default light seed.
    private static boolean isIvoryLight(ColorScheme scheme) {
        return scheme.getBrightness() == Brightness.LIGHT
                && scheme.getPrimaryColorSeed().equals(ThemeColor.DEFAULT.color());
    }

    /// Uses explicit low-chroma brand surfaces; custom colors and dark schemes remain generated.
    public static Color color(ColorScheme scheme, ColorRole role) {
        if (!isIvoryLight(scheme)) return scheme.getColor(role);
        if (role == ColorRole.SURFACE || role == ColorRole.SURFACE_CONTAINER_LOWEST) return Color.web("#FCF8EF");
        if (role == ColorRole.SURFACE_CONTAINER_LOW) return Color.web("#FAF6EC");
        if (role == ColorRole.SURFACE_CONTAINER || role == ColorRole.BACKGROUND) return Color.web("#F7F1E5");
        if (role == ColorRole.SURFACE_CONTAINER_HIGH || role == ColorRole.SURFACE_CONTAINER_HIGHEST) return Color.web("#EFE8DA");
        if (role == ColorRole.PRIMARY_CONTAINER) return Color.web("#EEDAB5");
        if (role == ColorRole.SECONDARY_CONTAINER) return Color.web("#EBE8DC");
        if (role == ColorRole.PRIMARY) return Color.web("#80633D");
        if (role == ColorRole.ON_PRIMARY_CONTAINER || role == ColorRole.ON_SURFACE || role == ColorRole.ON_SECONDARY_CONTAINER) return Color.web("#39362F");
        if (role == ColorRole.ON_SURFACE_VARIANT) return Color.web("#716B5F");
        return scheme.getColor(role);
    }

    /// Applies surface translucency while preserving the current mode's actual color.
    private static Color alpha(Color color, double opacity) {
        return new Color(color.getRed(), color.getGreen(), color.getBlue(), opacity);
    }
}
