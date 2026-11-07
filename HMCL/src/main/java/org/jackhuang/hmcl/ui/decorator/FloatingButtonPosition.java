// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.ui.decorator;

import javafx.geometry.Point2D;
import org.jetbrains.annotations.NotNullByDefault;
import java.util.Properties;

/// Resolution-independent floating button position, constrained to the usable content area.
/// @param x horizontal fraction of the available travel
/// @param y vertical fraction of the available travel
@NotNullByDefault
record FloatingButtonPosition(double x, double y) {
    /// Clamps corrupt or out-of-range saved coordinates to safe defaults.
    FloatingButtonPosition {
        x = unit(x);
        y = unit(y);
    }

    /// Reads normalized coordinates while tolerating old or malformed preference files.
    static FloatingButtonPosition read(Properties properties) {
        try {
            return new FloatingButtonPosition(Double.parseDouble(properties.getProperty("launch.x", "1")),
                    Double.parseDouble(properties.getProperty("launch.y", "1")));
        } catch (NumberFormatException exception) {
            return new FloatingButtonPosition(1, 1);
        }
    }

    /// Converts to screen-independent pixels, keeping the entire 56-pixel button inside the content.
    Point2D pixels(double width, double height, double railWidth) {
        double maxX = Math.max(12, width - 68);
        double minX = Math.min(maxX, Math.max(12, railWidth + 12));
        double maxY = Math.max(12, height - 68);
        return new Point2D(minX + x * (maxX - minX), 12 + y * (maxY - 12));
    }

    /// Converts a drag endpoint into a stable position that remains valid after resizing.
    static FloatingButtonPosition fromPixels(double x, double y, double width, double height, double railWidth) {
        double maxX = Math.max(12, width - 68);
        double minX = Math.min(maxX, Math.max(12, railWidth + 12));
        double maxY = Math.max(12, height - 68);
        return new FloatingButtonPosition(maxX > minX ? (x - minX) / (maxX - minX) : 1,
                maxY > 12 ? (y - 12) / (maxY - 12) : 1);
    }

    /// Returns a finite normalized fraction, defaulting to the lower-right edge on invalid input.
    private static double unit(double value) {
        return Double.isFinite(value) ? Math.max(0, Math.min(1, value)) : 1;
    }
}
