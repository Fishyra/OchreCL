// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.ui.decorator;

import javafx.geometry.Rectangle2D;
import javafx.scene.image.WritableImage;
import javafx.scene.image.ImageView;
import javafx.scene.layout.StackPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/// Reproduces repeated parent measurement with a real image and verifies proportional center cropping.
@NotNullByDefault
public final class OchreCoverViewTest {
    /// Both tall and wide artwork produce a bounded crop with exactly the destination's aspect ratio.
    @Test
    public void centerCropPreservesImageProportions() {
        Rectangle2D wide = OchreCoverView.crop(1600, 900, 280, 170);
        assertEquals(280.0 / 170, wide.getWidth() / wide.getHeight(), .00001);
        assertEquals(900, wide.getHeight(), .00001);
        assertEquals((1600 - wide.getWidth()) / 2, wide.getMinX(), .00001);
        Rectangle2D tall = OchreCoverView.crop(600, 1200, 280, 170);
        assertEquals(600, tall.getWidth(), .00001);
        assertEquals(280.0 / 170, tall.getWidth() / tall.getHeight(), .00001);
        assertEquals((1200 - tall.getHeight()) / 2, tall.getMinY(), .00001);
    }

    /// Two hundred measurement/layout cycles remain stable, including a later shrink of the parent frame.
    @Test
    public void imageAndBlurCannotGrowTheParentWidth() {
        OchreCoverView view = new OchreCoverView(new WritableImage(1600, 900));
        StackPane parent = new StackPane(view);
        parent.setMinWidth(0);
        parent.setPrefWidth(280);
        for (int i = 0; i < 200; i++) {
            parent.resize(280, 170);
            parent.requestLayout();
            parent.layout();
            assertEquals(280, parent.prefWidth(-1), .001);
            assertEquals(280, view.getWidth(), .001);
            assertEquals(0, parent.minWidth(-1), .001);
        }
        parent.resize(210, 170);
        parent.layout();
        assertEquals(210, view.getWidth(), .001);
        ImageView image = (ImageView) view.getChildrenUnmodifiable().get(1);
        assertTrue(image.isPreserveRatio());
        assertEquals(210, image.getFitWidth(), .001);
        assertEquals(210.0 / 170, image.getViewport().getWidth() / image.getViewport().getHeight(), .00001);
        ImageView glow = (ImageView) view.getChildrenUnmodifiable().get(0);
        assertEquals(image.getViewport().getMaxY(), glow.getViewport().getMaxY(), .00001);
        assertTrue(glow.getViewport().getHeight() < image.getViewport().getHeight() / 4);
        assertEquals(30, glow.getFitHeight(), .001);
    }
}
