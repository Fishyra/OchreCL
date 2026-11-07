package org.jackhuang.hmcl.server;

import javafx.geometry.Rectangle2D;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/// Exercises centered growth, screen-edge clamping and preservation of already-large server windows.
@NotNullByDefault
public final class OchreServerWindowSizingTest {
    /// A window whose enlarged bounds fit retains exactly the same center point.
    @Test public void keepsCenterWhenThereIsRoom() {
        var result = ServerWindowSizing.centered(400, 250, 800, 500, new Rectangle2D(0, 0, 1920, 1080));
        assertEquals(800, result.getMinX() + result.getWidth() / 2); assertEquals(500, result.getMinY() + result.getHeight() / 2);
    }
    /// Small screens cap growth and keep every enlarged edge inside the usable display.
    @Test public void fitsScreenAndNeverShrinksLargerWindows() {
        var screen = new Rectangle2D(-1280, 25, 1280, 775);
        var result = ServerWindowSizing.centered(-1230, 40, 720, 460, screen);
        assertTrue(result.getMinX() >= screen.getMinX()); assertTrue(result.getMinY() >= screen.getMinY());
        assertTrue(result.getMaxX() <= screen.getMaxX()); assertTrue(result.getMaxY() <= screen.getMaxY());
        var large = ServerWindowSizing.centered(100, 100, 1400, 850, new Rectangle2D(0, 0, 1920, 1080));
        assertEquals(1400, large.getWidth()); assertEquals(850, large.getHeight()); assertEquals(100, large.getMinX());
    }
    /// Leaving a server workspace restores its previous size without losing the current window center.
    @Test public void restoresPreviousSizeAtCurrentCenter() {
        var result = ServerWindowSizing.restored(840, 525, 800, 560, new Rectangle2D(0, 0, 1680, 1050));
        assertEquals(800, result.getWidth()); assertEquals(560, result.getHeight());
        assertEquals(840, result.getMinX() + result.getWidth() / 2); assertEquals(525, result.getMinY() + result.getHeight() / 2);
        var small = ServerWindowSizing.restored(-900, 300, 1120, 780, new Rectangle2D(-1000, 0, 1000, 700));
        assertEquals(-1000, small.getMinX()); assertEquals(0, small.getMinY()); assertEquals(1000, small.getWidth()); assertEquals(700, small.getHeight());
    }
}
