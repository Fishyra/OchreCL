package org.jackhuang.hmcl.theme;

import javafx.scene.paint.Color;
import org.glavo.monetfx.*;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/// Prevents generated tones from darkening the designed default ivory palette.
@NotNullByDefault
public final class OchrePaletteTest {
    /// Only the default light seed receives exact brand surfaces.
    @Test
    public void ivoryIsExactWhileDarkAndCustomColorsRemainAdaptive() {
        var light = new ResolvedTheme(ThemeColor.DEFAULT, Brightness.LIGHT, ColorStyle.TONAL_SPOT, Contrast.DEFAULT).toColorScheme();
        assertEquals(Color.web("#F7F1E5"), OchrePalette.color(light, ColorRole.SURFACE_CONTAINER));
        assertEquals(Color.web("#EEDAB5"), OchrePalette.color(light, ColorRole.PRIMARY_CONTAINER));
        var dark = new ResolvedTheme(ThemeColor.DEFAULT, Brightness.DARK, ColorStyle.TONAL_SPOT, Contrast.DEFAULT).toColorScheme();
        assertEquals(dark.getColor(ColorRole.SURFACE_CONTAINER), OchrePalette.color(dark, ColorRole.SURFACE_CONTAINER));
        var custom = new ResolvedTheme(new ThemeColor("custom", Color.BLUE), Brightness.LIGHT, ColorStyle.TONAL_SPOT, Contrast.DEFAULT).toColorScheme();
        assertEquals(custom.getColor(ColorRole.PRIMARY_CONTAINER), OchrePalette.color(custom, ColorRole.PRIMARY_CONTAINER));
    }
}
