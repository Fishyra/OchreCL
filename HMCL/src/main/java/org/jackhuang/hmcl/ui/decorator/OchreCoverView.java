// SPDX-FileCopyrightText: Copyright (C) 2026 The HMCL Authors
// SPDX-License-Identifier: GPL-3.0-or-later WITH AdditionRef-HMCL
package org.jackhuang.hmcl.ui.decorator;

import javafx.geometry.Rectangle2D;
import javafx.scene.effect.BoxBlur;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.shape.Rectangle;
import org.jetbrains.annotations.NotNullByDefault;

/// Cover artwork with aspect-fill cropping and an unmanaged glow that cannot affect parent measurements.
@NotNullByDefault
final class OchreCoverView extends Region {
    /// Logical height of the visible image, excluding its purely decorative glow.
    private static final double HEIGHT = 170;
    /// The decoded source image; asynchronous dimensions can arrive after construction.
    private final Image image;
    /// Visible cropped image, excluded from intrinsic-size calculations.
    private final ImageView artwork;
    /// Blurred sample of only the bottom edge, excluded from layout measurements.
    private final ImageView glow;
    /// Rounded clip applied only to the visible artwork.
    private final Rectangle roundedClip = new Rectangle();

    /// Creates a view whose source dimensions never determine its parent's preferred width.
    OchreCoverView(Image image) {
        this.image = image;
        setMinSize(0, HEIGHT);
        setPrefSize(240, HEIGHT);
        setMaxSize(Double.MAX_VALUE, HEIGHT);
        setMouseTransparent(true);
        artwork = new ImageView(image);
        artwork.setManaged(false);
        artwork.setPreserveRatio(true);
        artwork.setClip(roundedClip);
        glow = new ImageView(image);
        glow.setManaged(false);
        glow.setPreserveRatio(true);
        glow.setMouseTransparent(true);
        glow.setOpacity(.3);
        glow.setEffect(new BoxBlur(28, 28, 3));
        getChildren().setAll(glow, artwork);
        image.widthProperty().addListener(observable -> requestLayout());
        image.heightProperty().addListener(observable -> requestLayout());
    }

    /// Crops to the frame's aspect ratio before mapping the crop to the frame, preserving all proportions.
    @Override
    protected void layoutChildren() {
        double width = getWidth();
        if (width <= 0 || image.getWidth() <= 0 || image.getHeight() <= 0) return;
        Rectangle2D crop = crop(image.getWidth(), image.getHeight(), width, HEIGHT);
        artwork.setViewport(crop);
        artwork.setFitWidth(width);
        artwork.setFitHeight(HEIGHT);
        artwork.relocate(0, 0);
        roundedClip.setWidth(width);
        roundedClip.setHeight(HEIGHT);
        roundedClip.setArcWidth(28);
        roundedClip.setArcHeight(28);
        double stripHeight = 30;
        double sourceStripHeight = crop.getHeight() * stripHeight / HEIGHT;
        Rectangle2D bottomEdge = new Rectangle2D(crop.getMinX(), crop.getMaxY() - sourceStripHeight,
                crop.getWidth(), sourceStripHeight);
        glow.setViewport(bottomEdge);
        glow.setFitWidth(width);
        glow.setFitHeight(stripHeight);
        glow.relocate(0, HEIGHT - stripHeight / 2);
    }

    /// Returns a centered source crop with the same aspect ratio as the destination frame.
    static Rectangle2D crop(double imageWidth, double imageHeight, double frameWidth, double frameHeight) {
        if (imageWidth <= 0 || imageHeight <= 0 || frameWidth <= 0 || frameHeight <= 0)
            return new Rectangle2D(0, 0, 0, 0);
        double scale = Math.max(frameWidth / imageWidth, frameHeight / imageHeight);
        double width = frameWidth / scale;
        double height = frameHeight / scale;
        return new Rectangle2D((imageWidth - width) / 2, (imageHeight - height) / 2, width, height);
    }
}
