package com.myanimedesk;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.css.PseudoClass;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContentDisplay;
import javafx.scene.control.Label;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/** Compact editorial shelf that loads independently of the Dashboard's library data. */
final class DashboardNews extends VBox {
    private static final int VISIBLE_ARTICLES = 3;
    private static final PseudoClass RAIL = PseudoClass.getPseudoClass("rail");

    private final AnimeNews news;
    private final boolean english;
    private final Consumer<String> openUrl;
    private final DateTimeFormatter dateFormat;
    private final GridPane articles = new GridPane();
    private final Label status = Ui.label("", "dashboard-news-status");
    private final Button refresh;
    private boolean rail;

    DashboardNews(AnimeNews news, boolean english, Consumer<String> openUrl) {
        super(13);
        this.news = news;
        this.english = english;
        this.openUrl = openUrl;
        dateFormat = DateTimeFormatter.ofPattern("d MMM", english ? Locale.ENGLISH : Locale.ITALIAN)
            .withZone(ZoneId.systemDefault());
        setId("dashboard-news");
        getStyleClass().add("dashboard-news");
        setMaxWidth(2080);
        getStylesheets().add(Objects.requireNonNull(
            DashboardNews.class.getResource("dashboard-news.css")).toExternalForm());

        Label title = Ui.label(local("Notizie anime e manga", "Anime and manga news"), "dashboard-news-title");
        refresh = Ui.button(local("Aggiorna", "Refresh"), "dashboard-news-refresh", () -> load(true));
        refresh.setAccessibleText(local("Aggiorna le notizie", "Refresh news"));
        HBox header = new HBox(14, title, Ui.spacer(), refresh);
        header.setAlignment(Pos.CENTER_LEFT);
        articles.setHgap(12);
        articles.setVgap(8);
        getChildren().addAll(header, articles, status);

        setRail(false);
        show(news.cached(english));
        setStatus("");
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) load(false);
        });
    }

    void setRail(boolean rail) {
        this.rail = rail;
        pseudoClassStateChanged(RAIL, rail);
        articles.getColumnConstraints().clear();
        int columns = rail ? 1 : VISIBLE_ARTICLES;
        for (int i = 0; i < columns; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / columns);
            articles.getColumnConstraints().add(column);
        }
        for (int i = 0; i < articles.getChildren().size(); i++) {
            GridPane.setConstraints(articles.getChildren().get(i), rail ? 0 : i, rail ? i : 0);
        }
    }

    private void load(boolean force) {
        refresh.setDisable(true);
        refresh.setText(local("Aggiorna", "Refresh"));
        setStatus(articles.getChildren().isEmpty() ? local("Carico le notizie…", "Loading news…") : "");
        news.load(english, force).whenComplete((items, error) -> Platform.runLater(() -> {
            if (getScene() == null) return;
            refresh.setDisable(false);
            if (error == null) {
                show(items);
                setStatus("");
            } else {
                refresh.setText(local("Riprova", "Retry"));
                setStatus(articles.getChildren().isEmpty()
                    ? local("Notizie non disponibili.", "News unavailable.")
                    : local("Aggiornamento non riuscito.", "Could not refresh news."));
            }
        }));
    }

    private void show(List<AnimeNews.Article> items) {
        List<AnimeNews.Article> headlines = new ArrayList<>(items.stream().limit(VISIBLE_ARTICLES).toList());
        if (items.size() > VISIBLE_ARTICLES) {
            boolean anime = headlines.stream().anyMatch(article -> article.topic() != AnimeNews.Topic.MANGA);
            boolean manga = headlines.stream().anyMatch(article -> article.topic() != AnimeNews.Topic.ANIME);
            if (!anime || !manga) {
                items.stream().skip(VISIBLE_ARTICLES)
                    .filter(article -> anime ? article.topic() != AnimeNews.Topic.ANIME : article.topic() != AnimeNews.Topic.MANGA)
                    .findFirst().ifPresent(article -> headlines.set(VISIBLE_ARTICLES - 1, article));
            }
        }
        articles.getChildren().clear();
        for (int i = 0; i < headlines.size(); i++) {
            Button card = articleCard(headlines.get(i));
            if (i == 0) card.getStyleClass().add("dashboard-news-lead");
            articles.add(card, rail ? 0 : i, rail ? i : 0);
        }
        articles.setVisible(!headlines.isEmpty());
        articles.setManaged(!headlines.isEmpty());
    }

    private Button articleCard(AnimeNews.Article article) {
        String topic = switch (article.topic()) {
            case ANIME -> "Anime";
            case MANGA -> "Manga";
            case BOTH -> local("Anime e manga", "Anime & manga");
        };
        Label badge = Ui.label(topic, "dashboard-news-topic");
        badge.getStyleClass().add(article.topic() == AnimeNews.Topic.MANGA ? "dashboard-news-manga" : "dashboard-news-anime");
        Label metadata = Ui.label(article.source() + " · " + dateFormat.format(article.published()), "dashboard-news-meta");
        metadata.setWrapText(false);
        HBox byline = new HBox(8, badge, metadata);
        byline.setAlignment(Pos.CENTER_LEFT);
        Label headline = Ui.label(article.title(), "dashboard-news-headline");
        headline.setTextOverrun(OverrunStyle.ELLIPSIS);
        headline.setMaxHeight(64);
        VBox content = new VBox(9, byline, headline);

        Button card = Ui.button(article.title(), "dashboard-news-article", () -> openUrl.accept(article.url().toString()));
        content.prefWidthProperty().bind(Bindings.max(0, card.widthProperty().subtract(34)));
        card.setGraphic(content);
        card.setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
        card.setTooltip(new Tooltip(article.title()));
        card.setAccessibleText(article.title() + " · " + metadata.getText());
        card.setMinWidth(0);
        card.setPrefWidth(300);
        card.setMaxWidth(Double.MAX_VALUE);
        GridPane.setHgrow(card, Priority.ALWAYS);
        return card;
    }

    private void setStatus(String text) {
        status.setText(text);
        status.setVisible(!text.isEmpty());
        status.setManaged(!text.isEmpty());
    }

    private String local(String italian, String translated) {
        return english ? translated : italian;
    }
}
