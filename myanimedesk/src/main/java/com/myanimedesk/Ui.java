package com.myanimedesk;

import javafx.animation.*;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.*;
import javafx.scene.shape.Rectangle;
import javafx.util.Duration;

final class Ui {
    private Ui() { }
    static Label label(String text, String style) {
        Label label = new Label(text); label.getStyleClass().add(style); label.setWrapText(true); return label;
    }
    static Button button(String text, String style, Runnable action) {
        Button button = new Button(text); button.getStyleClass().add(style);
        button.setOnAction(e -> action.run()); return button;
    }
    static Region spacer() { Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS); return spacer; }
    static HBox row(Node... nodes) { HBox row = new HBox(12, nodes); row.setAlignment(Pos.CENTER_LEFT); return row; }
    static VBox panel(Node... nodes) { VBox panel = new VBox(14, nodes); panel.getStyleClass().add("panel"); return panel; }
    static ScrollPane scroll(Node content) {
        ScrollPane scroll = new ScrollPane(content); scroll.setFitToWidth(true);
        scroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); scroll.setMinHeight(0);
        VBox.setVgrow(scroll, Priority.ALWAYS);
        scroll.addEventFilter(ScrollEvent.SCROLL, e -> {
            if (e.isDirect() || e.isInertia() || e.isControlDown() || e.getDeltaY() == 0) return;
            // The nearest scrolling control owns the wheel; don't steal it from nested menus.
            for (Node node = e.getTarget() instanceof Node n ? n : null; node != null && node != scroll; node = node.getParent()) {
                if (node instanceof ScrollPane nested && nested.getVbarPolicy() != ScrollPane.ScrollBarPolicy.NEVER) return;
                if (node instanceof ComboBoxBase<?> || node instanceof Spinner<?> || node instanceof ListView<?>) return;
            }
            double excess = content.getLayoutBounds().getHeight() - scroll.getViewportBounds().getHeight();
            if (excess > 0) {
                scroll.setVvalue(Math.max(0, Math.min(1, scroll.getVvalue() - e.getDeltaY() * 2.35 / excess)));
                e.consume();
            }
        });
        return scroll;
    }
    static void entrance(Node node, int index) {
        node.setOpacity(0); node.setTranslateY(14);
        FadeTransition fade = new FadeTransition(Duration.millis(180), node); fade.setToValue(1);
        TranslateTransition move = new TranslateTransition(Duration.millis(220), node); move.setToY(0); move.setInterpolator(Interpolator.EASE_OUT);
        ParallelTransition transition = new ParallelTransition(fade, move);
        transition.setDelay(Duration.millis(Math.min(360, index * 28L))); transition.play();
    }
    static StackPane image(CoverCache cache, String url, int width, int height, int radius) {
        StackPane box = new StackPane(label("✦", "image-placeholder"));
        box.setMinSize(width, height); box.setPrefSize(width, height); box.setMaxSize(width, height);
        box.getStyleClass().add("image-frame");
        ImageView view = new ImageView(); view.setFitWidth(width); view.setFitHeight(height);
        box.getChildren().add(view);
        Rectangle clip = new Rectangle(width, height); clip.setArcWidth(radius); clip.setArcHeight(radius); box.setClip(clip);
        cache.load(url, width * 2, height * 2, image -> {
            view.setImage(image);
            double target = width / (double) height, actual = image.getWidth() / image.getHeight();
            double w = image.getWidth(), h = image.getHeight();
            if (actual > target) w = h * target; else h = w / target;
            view.setViewport(new Rectangle2D((image.getWidth() - w) / 2, (image.getHeight() - h) / 2, w, h));
        });
        return box;
    }
}
