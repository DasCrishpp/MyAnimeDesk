package com.myanimedesk;

import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.scene.CacheHint;
import javafx.scene.Node;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

/** Expands the detail surface from a cover and returns it to the same point on dismissal. */
final class AnimeDetailMotion {
    private static final double PLAYBACK_RATE = 1.17;

    private record Target(double x, double y, double scaleX, double scaleY) {}

    private final StackPane overlay;
    private final Region surface;
    private final StackPane origin;
    private final Rectangle shade = new Rectangle();
    private final ImageView cover = new ImageView();
    private Timeline motion;
    private boolean closing;

    AnimeDetailMotion(StackPane overlay, StackPane origin) {
        this.overlay = overlay;
        this.origin = origin;
        surface = (Region) overlay.getChildren().getFirst();

        shade.setFill(Color.rgb(5, 3, 13, .82));
        shade.setManaged(false);
        shade.setMouseTransparent(true);
        shade.widthProperty().bind(overlay.widthProperty());
        shade.heightProperty().bind(overlay.heightProperty());

        cover.setId("detail-motion-cover");
        cover.setManaged(false);
        cover.setMouseTransparent(true);
        cover.setSmooth(true);
        overlay.getChildren().addFirst(shade);
        overlay.getChildren().add(cover);
    }

    void open() {
        Target origin = target();
        animateSurface(true);
        surface.setScaleX(origin.scaleX());
        surface.setScaleY(origin.scaleY());
        surface.setTranslateX(origin.x());
        surface.setTranslateY(origin.y());
        surface.setOpacity(cover.getImage() == null ? .16 : 0);
        shade.setOpacity(0);
        cover.setOpacity(.96);
        motion = new Timeline(
            new KeyFrame(Duration.millis(40), new KeyValue(surface.opacityProperty(), surface.getOpacity())),
            new KeyFrame(Duration.millis(190),
                new KeyValue(surface.opacityProperty(), 1, Interpolator.EASE_OUT),
                new KeyValue(cover.opacityProperty(), 0, Interpolator.EASE_IN)),
            new KeyFrame(Duration.millis(280), new KeyValue(shade.opacityProperty(), 1, Interpolator.EASE_OUT)),
            new KeyFrame(Duration.millis(340),
                new KeyValue(surface.scaleXProperty(), 1, Interpolator.EASE_OUT),
                new KeyValue(surface.scaleYProperty(), 1, Interpolator.EASE_OUT),
                new KeyValue(surface.translateXProperty(), 0, Interpolator.EASE_OUT),
                new KeyValue(surface.translateYProperty(), 0, Interpolator.EASE_OUT),
                new KeyValue(cover.translateXProperty(), -origin.x(), Interpolator.EASE_OUT),
                new KeyValue(cover.translateYProperty(), -origin.y(), Interpolator.EASE_OUT),
                new KeyValue(cover.scaleXProperty(), 1.7, Interpolator.EASE_OUT),
                new KeyValue(cover.scaleYProperty(), 1.7, Interpolator.EASE_OUT)));
        motion.setOnFinished(event -> {
            animateSurface(false);
            cover.setImage(null);
            surface.requestFocus();
        });
        motion.setRate(PLAYBACK_RATE);
        motion.play();
    }

    void close(Runnable finished) {
        if (closing) return;
        closing = true;
        if (motion != null) motion.pause();
        Target destination = target();
        animateSurface(true);
        cover.setOpacity(0);
        cover.setTranslateX(surface.getTranslateX() - destination.x());
        cover.setTranslateY(surface.getTranslateY() - destination.y());
        cover.setScaleX(1.5);
        cover.setScaleY(1.5);

        motion = new Timeline(
            new KeyFrame(Duration.millis(65),
                new KeyValue(surface.opacityProperty(), surface.getOpacity()),
                new KeyValue(cover.opacityProperty(), 0)),
            new KeyFrame(Duration.millis(245),
                new KeyValue(shade.opacityProperty(), 0, Interpolator.EASE_OUT),
                new KeyValue(surface.opacityProperty(), 0, Interpolator.EASE_IN),
                new KeyValue(surface.scaleXProperty(), destination.scaleX(), Interpolator.EASE_BOTH),
                new KeyValue(surface.scaleYProperty(), destination.scaleY(), Interpolator.EASE_BOTH),
                new KeyValue(surface.translateXProperty(), destination.x(), Interpolator.EASE_BOTH),
                new KeyValue(surface.translateYProperty(), destination.y(), Interpolator.EASE_BOTH),
                new KeyValue(cover.opacityProperty(), .88, Interpolator.EASE_IN),
                new KeyValue(cover.translateXProperty(), 0, Interpolator.EASE_BOTH),
                new KeyValue(cover.translateYProperty(), 0, Interpolator.EASE_BOTH),
                new KeyValue(cover.scaleXProperty(), 1, Interpolator.EASE_BOTH),
                new KeyValue(cover.scaleYProperty(), 1, Interpolator.EASE_BOTH)));
        motion.setOnFinished(event -> finished.run());
        motion.setRate(PLAYBACK_RATE);
        motion.play();
    }

    void dispose() {
        if (motion != null) motion.stop();
        animateSurface(false);
        cover.setImage(null);
    }

    private void animateSurface(boolean animating) {
        surface.setMouseTransparent(animating);
        surface.setCache(animating);
        surface.setCacheHint(animating ? CacheHint.SCALE : CacheHint.DEFAULT);
    }

    private Target target() {
        cover.setImage(null);
        if (origin == null || origin.getScene() != overlay.getScene() || !origin.isVisible())
            return new Target(0, 18, .91, .94);
        Bounds originBounds = overlay.sceneToLocal(origin.localToScene(origin.getLayoutBounds()));
        double width = surface.getWidth();
        double height = surface.getHeight();
        if (width <= 0 || height <= 0 || originBounds.getWidth() <= 1 || originBounds.getHeight() <= 1
            || !overlay.getLayoutBounds().contains(originBounds.getCenterX(), originBounds.getCenterY())) {
            return new Target(0, 18, .91, .94);
        }

        for (Node node : origin.getChildren()) {
            if (node instanceof ImageView artwork && artwork.getImage() != null) {
                cover.setImage(artwork.getImage());
                cover.setViewport(artwork.getViewport());
                cover.setFitWidth(originBounds.getWidth());
                cover.setFitHeight(originBounds.getHeight());
                cover.relocate(originBounds.getMinX(), originBounds.getMinY());
                Rectangle clip = new Rectangle(originBounds.getWidth(), originBounds.getHeight());
                clip.setArcWidth(20);
                clip.setArcHeight(20);
                cover.setClip(clip);
                break;
            }
        }

        // The surface is a direct child of the overlay, so use its undisplaced layout centre.
        Point2D centre = new Point2D(surface.getLayoutX() + width / 2, surface.getLayoutY() + height / 2);
        return new Target(originBounds.getCenterX() - centre.getX(), originBounds.getCenterY() - centre.getY(),
            Math.min(.95, originBounds.getWidth() / width), Math.min(.95, originBounds.getHeight() / height));
    }
}
