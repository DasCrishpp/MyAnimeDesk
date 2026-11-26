package com.myanimedesk;

import java.util.EnumMap;
import java.util.List;
import java.util.Objects;

import javafx.geometry.Pos;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Arc;
import javafx.scene.shape.ArcType;
import javafx.scene.shape.Circle;

final class DashboardStats extends VBox {
    private static final int CHART_SIZE = 118;
    private static final double CENTER = CHART_SIZE / 2.0;
    private static final double RADIUS = 51;

    private record Reading(String label, int count, String color) {}

    DashboardStats(String hours, List<Anime> library, boolean english) {
        getStyleClass().add("dashboard-stats");
        getStylesheets().add(Objects.requireNonNull(
            DashboardStats.class.getResource("dashboard-stats.css")).toExternalForm());
        setMinWidth(300);
        setPrefWidth(350);
        setMaxWidth(385);
        setMinHeight(340);
        setPrefHeight(354);

        EnumMap<Anime.Status, Integer> totals = new EnumMap<>(Anime.Status.class);
        int favourites = 0;
        int rewatched = 0;
        for (Anime anime : library) {
            totals.merge(Objects.requireNonNullElse(anime.status, Anime.Status.TO_WATCH), 1, Integer::sum);
            if (anime.favorite) favourites++;
            if (anime.viewCount() > 1) rewatched++;
        }
        List<Reading> statuses = List.of(
            new Reading(english ? "Completed" : "Visti", totals.getOrDefault(Anime.Status.WATCHED, 0), "completed"),
            new Reading(english ? "Watching" : "In visione", totals.getOrDefault(Anime.Status.WATCHING, 0), "watching"),
            new Reading(english ? "Plan to watch" : "Da vedere", totals.getOrDefault(Anime.Status.TO_WATCH, 0), "planned"),
            new Reading(english ? "Dropped" : "Droppati", totals.getOrDefault(Anime.Status.DROPPED, 0), "dropped"));

        HBox amount = new HBox(6, label(hours, "dashboard-stats-hours"), label("h", "dashboard-stats-hours-unit"));
        amount.setAlignment(Pos.BASELINE_LEFT);
        VBox time = new VBox(1, label(english ? "Watch time" : "Tempo di visione", "dashboard-stats-heading"), amount);

        VBox legend = new VBox(7);
        for (Reading status : statuses) legend.getChildren().add(reading(status));
        HBox.setHgrow(legend, Priority.ALWAYS);
        HBox distribution = new HBox(16, watchRing(statuses, library.size()), legend);
        distribution.setAlignment(Pos.CENTER_LEFT);
        VBox.setVgrow(distribution, Priority.ALWAYS);

        HBox secondary = new HBox(8,
            badge(english ? "Favourites" : "Preferiti", favourites),
            badge(english ? "Rewatched" : "Rewatchati", rewatched));
        getChildren().addAll(time, distribution, secondary);
    }

    private StackPane watchRing(List<Reading> statuses, int total) {
        Pane chart = new Pane();
        chart.setPrefSize(CHART_SIZE, CHART_SIZE);
        chart.getChildren().add(circle("track"));
        double start = 90;
        for (Reading status : statuses) {
            if (status.count() == 0) continue;
            double sweep = 360.0 * status.count() / total;
            if (status.count() == total) {
                chart.getChildren().add(circle(status.color()));
            } else {
                double halfGap = Math.min(2, sweep * .11);
                Arc arc = new Arc(CENTER, CENTER, RADIUS, RADIUS, start - halfGap, -(sweep - 2 * halfGap));
                arc.setType(ArcType.OPEN);
                arc.getStyleClass().addAll("dashboard-stats-ring", status.color());
                chart.getChildren().add(arc);
            }
            start -= sweep;
        }

        VBox title = new VBox(label(Integer.toString(total), "dashboard-stats-total"), label("Anime", "dashboard-stats-total-label"));
        title.setAlignment(Pos.CENTER);
        StackPane ring = new StackPane(chart, title);
        ring.setMinSize(CHART_SIZE, CHART_SIZE);
        ring.setPrefSize(CHART_SIZE, CHART_SIZE);
        ring.setMaxSize(CHART_SIZE, CHART_SIZE);
        return ring;
    }

    private Circle circle(String color) {
        Circle circle = new Circle(CENTER, CENTER, RADIUS);
        circle.getStyleClass().addAll("dashboard-stats-ring", color);
        return circle;
    }

    private HBox reading(Reading status) {
        Circle dot = new Circle(3.5);
        dot.getStyleClass().addAll("dashboard-stats-dot", status.color());
        Region space = new Region();
        HBox.setHgrow(space, Priority.ALWAYS);
        HBox row = new HBox(7, dot, label(status.label(), "dashboard-stats-caption"), space,
            label(Integer.toString(status.count()), "dashboard-stats-count"));
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPrefHeight(26);
        row.getStyleClass().add("dashboard-stats-reading");
        return row;
    }

    private HBox badge(String title, int count) {
        HBox badge = new HBox(6, label(title, "dashboard-stats-caption"), label(Integer.toString(count), "dashboard-stats-badge-count"));
        badge.setAlignment(Pos.CENTER);
        badge.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(badge, Priority.ALWAYS);
        badge.getStyleClass().add("dashboard-stats-badge");
        return badge;
    }

    private Label label(String text, String style) {
        Label label = new Label(text);
        label.getStyleClass().add(style);
        return label;
    }
}
