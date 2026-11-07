// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.ui.decorator;

import javafx.geometry.Point2D;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

/// Ensures dragging, resizing and corrupt saved coordinates cannot lose the launch action off-screen.
@NotNullByDefault
public final class FloatingButtonPositionTest {
    /// Arbitrary drag endpoints clamp to the content area, avoiding pinned navigation.
    @Test
    public void clampsDragsAndRoundTripsNormalizedPositions() {
        FloatingButtonPosition clamped = FloatingButtonPosition.fromPixels(-100, 9999, 720, 400, 174);
        Point2D actual = clamped.pixels(720, 400, 174);
        assertEquals(186, actual.getX());
        assertEquals(332, actual.getY());
        FloatingButtonPosition position = FloatingButtonPosition.fromPixels(360, 160, 1000, 600, 60);
        Point2D roundTrip = position.pixels(1000, 600, 60);
        assertEquals(360, roundTrip.getX(), .001);
        assertEquals(160, roundTrip.getY(), .001);
        Point2D smaller = position.pixels(720, 400, 174);
        assertTrue(smaller.getX() >= 186 && smaller.getX() <= 652);
        assertTrue(smaller.getY() >= 12 && smaller.getY() <= 332);
    }

    /// Damaged preferences and nonfinite values safely resolve to visible positions.
    @Test
    public void invalidPreferencesFallBackWithoutThrowing() {
        Properties properties = new Properties();
        properties.setProperty("launch.x", "invalid");
        assertEquals(new FloatingButtonPosition(1, 1), FloatingButtonPosition.read(properties));
        assertEquals(new FloatingButtonPosition(1, 1), new FloatingButtonPosition(Double.NaN, Double.POSITIVE_INFINITY));
        assertEquals(new Point2D(12, 12), new FloatingButtonPosition(0, 0).pixels(40, 40, 174));
    }
}
