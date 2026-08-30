package com.myanimedesk;

import javafx.animation.FadeTransition;
import javafx.animation.PauseTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Interpolator;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.image.PixelWriter;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.util.Duration;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.net.URI;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Properties;
import java.util.Map;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class App extends Application {
    private static final String APP_VERSION = "0.4.0";
    private static final String RELEASES_URL = "https://github.com/DasCrishpp/MyAnimeDesk/releases";
    private static final String RELEASES_API = "https://api.github.com/repos/DasCrishpp/MyAnimeDesk/releases";
    private static final double SIDEBAR_WIDTH = 230;
    // All'avvio carico le cover delle prime card VISIBILI di ogni genere.
    // Il resto (Mostra altro e ricerca) resta dinamico / in background.
    private static final int STARTUP_DISCOVER_VISIBLE_COVERS = 8;
    private static final Path APP_DIR = Path.of(System.getProperty("user.home"), ".myanimedesk");
    private static final Path SETTINGS_FILE = APP_DIR.resolve("app.properties");
    private static final Path CUSTOM_BACKGROUND_FILE = APP_DIR.resolve("custom_background.img");
    private static final Path IMAGE_CACHE_DIR = APP_DIR.resolve("image_cache");
    private static final Path DISCOVER_CACHE_FILE = APP_DIR.resolve("discover_cache.json");
    private static final String DEFAULT_BACKGROUND_COLOR = "#160b2e";
    private static final String DEFAULT_ACCENT_COLOR = "#8b5cf6";
    private static final String CURRENT_CHANGELOG = """
-  Migliorata schermata delle impostazioni
-  Migliorata la gestione dei colori personalizzati
-  Sistemati alcuni bug
""";

    private final AniListClient client = new AniListClient();
    private final AnimeListManager manager = new AnimeListManager();
    private final ObjectMapper appMapper = new ObjectMapper();
    private final ExecutorService executor = Executors.newFixedThreadPool(4);
    private final Map<String, Image> imageCache = new ConcurrentHashMap<>();
    private final Map<String, byte[]> imageBytesCache = new ConcurrentHashMap<>();
    private final Map<String, List<ImageView>> pendingImageViews = new ConcurrentHashMap<>();
    private final java.util.Set<String> imageDownloadsInProgress = ConcurrentHashMap.newKeySet();
    private final Map<String, List<Anime>> discoverPreloadCache = new ConcurrentHashMap<>();

    private BorderPane root;
    private StackPane mainContentStack;
    private VBox dashboardPane, libraryPane, searchPane, settingsPane;
    
    private TilePane libraryGrid;
    private VBox discoverContent;
    private ScrollPane discoverScrollPane;
    private StackPane appShell;
    private StackPane startupLoadingOverlay;
    private ProgressBar startupProgressBar;
    private Label startupLoadingLabel;
    private TextField librarySearchField;
    private Popup suggestionPopup;
    private ListView<Anime> suggestionList;
    private Label suggestionEmptyLabel;
    private boolean suppressSuggestionPopupUpdate = false;
    private Popup activeCardPopup;
    private HBox dashboardWatchingRow;
    private Button floatingDiscoverButton;
    private Button floatingTopButton;

    private Label totalHoursLabel, watchingCountLabel, completedCountLabel;
    private TextField searchField;
    private Timer searchDebounceTimer;

    // Filtri Lista con Contatori
    private Button btnFilterAll, btnFilterWatching, btnFilterWatched, btnFilterToWatch, btnFilterDropped;

    // Pop-up Modale dei Dettagli Estesi Estetico
    private StackPane detailOverlay;
    private HBox detailDialogBox;
    private ImageView coverView;
    private Label titleLabel, metaLabel, detailStatusChoiceLabel;
    private VBox extendedInfoBox; // Sostituito TextArea con VBox strutturato
    private Button addButton, removeButton;
    private Button detailPlanButton, detailWatchingButton, detailWatchedButton, detailDroppedButton;
    
    private Label statusBar;
    private Label sidebarLogo;
    private Anime activeAnime;
    private String currentLibraryFilter = "TUTTI";
    private Button activeMenuButton = null;
    private volatile boolean shuttingDown = false;
    private String currentAccentColor = DEFAULT_ACCENT_COLOR;
    private String currentBackgroundColor = DEFAULT_BACKGROUND_COLOR;


    // Helper interno per filtrare i dati senza modificare AnimeListManager
    private List<Anime> getByStatus(Anime.Status status) {
        return manager.all().stream().filter(a -> a.status == status).collect(Collectors.toList());
    }

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("MyAnimeDesk");
        stage.setMinWidth(1150);
        stage.setMinHeight(780);

        root = new BorderPane();
        applySolidBackground(Color.web(DEFAULT_BACKGROUND_COLOR), false);

        root.setLeft(createSidebar());

        mainContentStack = new StackPane();
        mainContentStack.setPadding(new Insets(24));

        initDashboardPane();
        initLibraryPane();
        initSearchPane();
        initSettingsPane();
        initDetailOverlay(); 

        mainContentStack.getChildren().addAll(dashboardPane, libraryPane, searchPane, settingsPane);
        root.setCenter(mainContentStack);
        applySavedSettings();
        root.setBottom(createStatusBar());

        appShell = new StackPane(root);
        configureMainAreaModalOverlay(detailOverlay);
        appShell.getChildren().add(detailOverlay);
        startupLoadingOverlay = createStartupLoadingOverlay();
        appShell.getChildren().add(startupLoadingOverlay);

        Scene scene = new Scene(appShell, 1280, 820);
        installModernScrollBarStyle(scene);
        stage.setScene(scene);
        stage.setOnCloseRequest(e -> shutdownBackgroundWork());
        stage.show();

        loadLibraryDataWithStartupOverlay();
        
        Node firstBtn = ((VBox)root.getLeft()).getChildren().get(1);
        if(firstBtn instanceof Button) {
            ((Button) firstBtn).fire();
        }

        Platform.runLater(() -> {
            showChangelogIfFirstRunOfVersion();
            checkForUpdates(false);
        });
    }

    // --- SUONI UI DISATTIVATI ---
    private void playClickSound() { }

    // --- SIDEBAR ---
    private VBox createSidebar() {
        VBox sidebar = new VBox(12);
        sidebar.setPadding(new Insets(30, 15, 20, 15));
        sidebar.setPrefWidth(SIDEBAR_WIDTH);
        sidebar.setStyle(
            "-fx-background-color: linear-gradient(to bottom, #070d1c, #020617);" +
            "-fx-background-radius: 0 26 26 0;" +
            "-fx-border-radius: 0 26 26 0;" +
            "-fx-border-color: transparent rgba(255,255,255,0.08) transparent transparent;" +
            "-fx-border-width: 0 1 0 0;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 22, 0.18, 5, 0);"
        );

        sidebarLogo = new Label("MyAnimeDesk"); 
        sidebarLogo.setTextFill(Color.web(currentAccentColor == null ? DEFAULT_ACCENT_COLOR : currentAccentColor));
        sidebarLogo.setFont(Font.font("Segoe UI", FontWeight.BOLD, 24));
        sidebarLogo.setPadding(new Insets(0, 0, 20, 10));

        Button btnDash = createSidebarButton("Dashboard");
        Button btnLib = createSidebarButton("La Mia Lista");
        Button btnSearch = createSidebarButton("Scopri / Cerca");
        Button btnSettings = createSidebarButton("Impostazioni");

        btnDash.setOnAction(e -> { playClickSound(); selectMenuButton(btnDash); showView(dashboardPane); updateDashboardStats(); });
        btnLib.setOnAction(e -> { playClickSound(); selectMenuButton(btnLib); showView(libraryPane); refreshLibraryGrid(); });
        btnSearch.setOnAction(e -> { playClickSound(); selectMenuButton(btnSearch); showView(searchPane); });
        btnSettings.setOnAction(e -> { playClickSound(); selectMenuButton(btnSettings); showView(settingsPane); });

        sidebar.getChildren().addAll(sidebarLogo, btnDash, btnLib, btnSearch, btnSettings);
        return sidebar;
    }

    private Button createSidebarButton(String text) {
        Button btn = new Button(text);
        btn.setAlignment(Pos.CENTER_LEFT);
        btn.setPrefWidth(200);
        btn.setMinHeight(46);
        btn.setPadding(new Insets(12, 18, 12, 18));
        btn.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 14));
        btn.setStyle("-fx-background-color: rgba(15, 23, 42, 0.92); -fx-text-fill: #94a3b8; -fx-background-radius: 18; -fx-border-radius: 18; -fx-border-color: rgba(148,163,184,0.16); -fx-border-width: 1; -fx-cursor: hand;");
        btn.setOnMouseEntered(e -> {
            if (btn != activeMenuButton) {
                btn.setStyle("-fx-background-color: rgba(30, 41, 72, 0.98); -fx-text-fill: white; -fx-background-radius: 18; -fx-border-radius: 18; -fx-border-color: rgba(96,165,250,0.75); -fx-border-width: 1; -fx-effect: dropshadow(gaussian, rgba(59,130,246,0.25), 12, 0.25, 0, 3); -fx-cursor: hand;");
            }
        });
        btn.setOnMouseExited(e -> {
            if (btn != activeMenuButton) {
                btn.setStyle("-fx-background-color: rgba(15, 23, 42, 0.92); -fx-text-fill: #94a3b8; -fx-background-radius: 18; -fx-border-radius: 18; -fx-border-color: rgba(148,163,184,0.16); -fx-border-width: 1; -fx-cursor: hand;");
            }
        });
        return btn;
    }

    private void selectMenuButton(Button target) {
        if (activeMenuButton != null) {
            activeMenuButton.setStyle("-fx-background-color: rgba(15, 23, 42, 0.92); -fx-text-fill: #94a3b8; -fx-background-radius: 18; -fx-border-radius: 18; -fx-border-color: rgba(148,163,184,0.16); -fx-border-width: 1; -fx-cursor: hand;");
        }
        activeMenuButton = target;
        activeMenuButton.setStyle("-fx-background-color: linear-gradient(to right, " + currentAccentColor + ", #a855f7); -fx-text-fill: white; -fx-background-radius: 18; -fx-border-radius: 18; -fx-border-color: " + accentRgba(0.85) + "; -fx-border-width: 1; -fx-effect: dropshadow(gaussian, " + accentRgba(0.35) + ", 16, 0.25, 0, 4); -fx-cursor: hand;");
    }

    private void showView(VBox targetPane) {
        hideSuggestionPopupCompletely();
        hideActiveCardPopup();
        VBox[] panes = {dashboardPane, libraryPane, searchPane, settingsPane};
        for (VBox p : panes) {
            if (p == targetPane) {
                p.setVisible(true);
                p.setOpacity(0.0);
                p.setTranslateY(12);

                FadeTransition fade = new FadeTransition(Duration.millis(240), p);
                fade.setFromValue(0.0);
                fade.setToValue(1.0);

                Timeline slide = new Timeline(
                    new KeyFrame(Duration.millis(240),
                        new KeyValue(p.translateYProperty(), 0, Interpolator.EASE_OUT)
                    )
                );
                fade.play();
                slide.play();
            } else {
                p.setVisible(false);
            }
        }
        detailOverlay.setVisible(false);
    }


    private void installModernScrollBarStyle(Scene scene) {
        try {
            String css = """
                .scroll-pane { -fx-background-color: transparent; -fx-background: transparent; }
                .scroll-pane > .viewport { -fx-background-color: transparent; }

                .scroll-bar:vertical {
                    -fx-background-color: transparent;
                    -fx-pref-width: 9;
                    -fx-padding: 2 2 2 2;
                }
                .scroll-bar:horizontal {
                    -fx-background-color: transparent;
                    -fx-pref-height: 9;
                    -fx-padding: 2 2 2 2;
                }
                .scroll-bar .track, .scroll-bar .track-background {
                    -fx-background-color: transparent;
                    -fx-border-color: transparent;
                    -fx-background-insets: 0;
                    -fx-opacity: 0;
                }
                .scroll-bar .thumb {
                    -fx-background-color: rgba(148, 163, 184, 0.42);
                    -fx-background-radius: 999;
                }
                .scroll-bar .thumb:hover {
                    -fx-background-color: rgba(96, 165, 250, 0.78);
                }
                .scroll-bar .increment-button, .scroll-bar .decrement-button {
                    -fx-background-color: transparent;
                    -fx-padding: 0;
                    -fx-opacity: 0;
                }
                .scroll-bar .increment-arrow, .scroll-bar .decrement-arrow {
                    -fx-shape: "";
                    -fx-padding: 0;
                    -fx-opacity: 0;
                }

                .list-view, .list-cell { -fx-background-color: transparent; }
                .list-cell:filled:hover { -fx-background-color: rgba(30, 41, 59, 0.95); -fx-background-radius: 10; }
                .list-cell:filled:selected { -fx-background-color: rgba(37, 99, 235, 0.32); -fx-background-radius: 10; }
            """;
            Path cssFile = Files.createTempFile("myanimedesk-ui-", ".css");
            Files.writeString(cssFile, css);
            scene.getStylesheets().add(cssFile.toUri().toString());
        } catch (Exception ignored) { }
    }



    private void configureMainAreaModalOverlay(StackPane overlay) {
        if (overlay == null || appShell == null) return;

        overlay.prefWidthProperty().unbind();
        overlay.prefHeightProperty().unbind();
        overlay.maxWidthProperty().unbind();
        overlay.maxHeightProperty().unbind();

        // L'overlay ora copre tutta la finestra, inclusa la sidebar.
        // Così non rimangono più angolini/strisce dello sfondo personalizzato visibili.
        overlay.prefWidthProperty().bind(appShell.widthProperty());
        overlay.maxWidthProperty().bind(appShell.widthProperty());
        overlay.prefHeightProperty().bind(appShell.heightProperty());
        overlay.maxHeightProperty().bind(appShell.heightProperty());

        overlay.setMinSize(0, 0);
        StackPane.setAlignment(overlay, Pos.CENTER);
        StackPane.setMargin(overlay, Insets.EMPTY);
    }

    private StackPane createStartupLoadingOverlay() {
        StackPane overlay = new StackPane();
        overlay.setStyle(
            "-fx-background-color: radial-gradient(center 50% 45%, radius 70%, rgba(30,64,175,0.28), rgba(2,6,23,0.96));"
        );
        overlay.setVisible(true);
        overlay.setOpacity(1.0);

        HBox card = new HBox(34);
        card.setAlignment(Pos.CENTER_LEFT);
        card.setPadding(new Insets(34, 42, 34, 42));
        card.setPrefWidth(900);
        card.setMaxWidth(900);
        card.setMinHeight(285);
        card.setMaxHeight(285);
        card.setStyle(
            "-fx-background-color: linear-gradient(to bottom right, rgba(15,23,42,0.98), rgba(3,7,18,0.98));" +
            "-fx-background-radius: 26;" +
            "-fx-border-radius: 26;" +
            "-fx-border-color: rgba(96,165,250,0.55);" +
            "-fx-border-width: 1.2;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.68), 42, 0.24, 0, 18);"
        );

        VBox left = new VBox(12);
        left.setAlignment(Pos.CENTER_LEFT);
        left.setPrefWidth(310);
        left.setMaxWidth(310);

        Label badge = new Label("AVVIO APP");
        badge.setTextFill(Color.web("#bfdbfe"));
        badge.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 12));
        badge.setPadding(new Insets(6, 13, 6, 13));
        badge.setStyle(
            "-fx-background-color: rgba(37,99,235,0.22);" +
            "-fx-background-radius: 999;" +
            "-fx-border-radius: 999;" +
            "-fx-border-color: rgba(147,197,253,0.28);"
        );

        Label logo = new Label("MyAnimeDesk");
        logo.setTextFill(Color.web("#f8fafc"));
        logo.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 34));

        Label version = new Label("Versione " + APP_VERSION);
        version.setTextFill(Color.web("#93c5fd"));
        version.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));

        Label description = new Label("Preparo libreria, dashboard e anteprime principali prima di aprire l'app.");
        description.setWrapText(true);
        description.setTextFill(Color.web("#94a3b8"));
        description.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));
        description.setMaxWidth(285);

        left.getChildren().addAll(badge, logo, version, description);

        Region divider = new Region();
        divider.setPrefWidth(1);
        divider.setMinWidth(1);
        divider.setMaxWidth(1);
        divider.setStyle("-fx-background-color: rgba(148,163,184,0.16);");

        VBox right = new VBox(18);
        right.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(right, Priority.ALWAYS);

        Label title = new Label("Caricamento iniziale");
        title.setTextFill(Color.web("#e5e7eb"));
        title.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 24));

        startupLoadingLabel = new Label("Preparazione dell'app...");
        startupLoadingLabel.setTextFill(Color.web("#dbeafe"));
        startupLoadingLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 15));
        startupLoadingLabel.setWrapText(true);
        startupLoadingLabel.setMaxWidth(460);

        startupProgressBar = new ProgressBar(0);
        startupProgressBar.setMinHeight(16);
        startupProgressBar.setPrefHeight(16);
        startupProgressBar.setMaxHeight(16);
        startupProgressBar.setPrefWidth(470);
        startupProgressBar.setMaxWidth(Double.MAX_VALUE);
        startupProgressBar.setStyle(
            "-fx-accent: #60a5fa;" +
            "-fx-control-inner-background: rgba(15,23,42,0.92);" +
            "-fx-background-radius: 999;" +
            "-fx-background-insets: 0;" +
            "-fx-padding: 0;"
        );

        Label sub = new Label("Le sezioni extra e le immagini non essenziali continueranno a caricarsi in background.");
        sub.setWrapText(true);
        sub.setTextFill(Color.web("#64748b"));
        sub.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12));
        sub.setMaxWidth(470);

        HBox dots = new HBox(7);
        dots.setAlignment(Pos.CENTER_LEFT);
        for (int i = 0; i < 3; i++) {
            Label dot = new Label("●");
            dot.setTextFill(Color.web(i == 0 ? "#60a5fa" : "#334155"));
            dot.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
            dots.getChildren().add(dot);
        }

        right.getChildren().addAll(title, startupLoadingLabel, startupProgressBar, sub, dots);
        card.getChildren().addAll(left, divider, right);

        card.setOpacity(0);
        card.setScaleX(0.96);
        card.setScaleY(0.96);

        overlay.getChildren().add(card);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(360), card);
        fadeIn.setFromValue(0);
        fadeIn.setToValue(1);
        ScaleTransition scaleIn = new ScaleTransition(Duration.millis(360), card);
        scaleIn.setFromX(0.96);
        scaleIn.setFromY(0.96);
        scaleIn.setToX(1);
        scaleIn.setToY(1);
        fadeIn.play();
        scaleIn.play();

        return overlay;
    }

    private void hideStartupLoadingOverlay() {
        if (startupLoadingOverlay == null) return;
        FadeTransition fade = new FadeTransition(Duration.millis(420), startupLoadingOverlay);
        fade.setFromValue(1.0);
        fade.setToValue(0.0);
        fade.setOnFinished(e -> {
            startupLoadingOverlay.setVisible(false);
            startupLoadingOverlay.setManaged(false);
            if (appShell != null) appShell.getChildren().remove(startupLoadingOverlay);
        });
        fade.play();
    }

    private void loadLibraryDataWithStartupOverlay() {
        Task<Void> task = new Task<>() {
            @Override protected Void call() {
                updateProgress(0, 100);
                updateMessage("Caricamento libreria personale...");
                try {
                    manager.loadFromDefault();
                } catch (Exception ignored) { }

                updateProgress(14, 100);
                updateMessage("Controllo dati salvati nella lista...");
                enrichSavedAnimeDataSafely();

                updateProgress(28, 100);
                updateMessage("Preparazione dashboard e copertine salvate...");
                preloadSavedCoversSafely();

                updateProgress(42, 100);
                updateMessage("Caricamento dati delle sezioni Scopri...");
                preloadDiscoverSectionsSafely(
                    msg -> updateMessage(msg),
                    done -> updateProgress(42 + (done * 28.0 / Math.max(1, discoverSectionDefinitions().length)), 100)
                );

                updateProgress(70, 100);
                updateMessage("Caricamento prime copertine di Scopri...");
                preloadDiscoverVisibleCoversAtStartupSafely(
                    msg -> updateMessage(msg),
                    done -> updateProgress(70 + (done * 24.0 / Math.max(1, discoverSectionDefinitions().length)), 100)
                );

                updateProgress(96, 100);
                updateMessage("Apro l'interfaccia...");
                sleepQuietly(160);

                updateProgress(100, 100);
                return null;
            }
        };
        if (startupLoadingLabel != null) startupLoadingLabel.textProperty().bind(task.messageProperty());
        if (startupProgressBar != null) startupProgressBar.progressProperty().bind(task.progressProperty());
        task.setOnSucceeded(e -> finishStartupLoading("Libreria, dashboard e prime card di Scopri caricate."));
        task.setOnFailed(e -> finishStartupLoading("Database pronto."));
        executor.submit(task);
    }

    private void finishStartupLoading(String message) {
        if (startupLoadingLabel != null) startupLoadingLabel.textProperty().unbind();
        if (startupProgressBar != null) startupProgressBar.progressProperty().unbind();
        statusBar.setText(message);
        updateFilterButtonCounts();
        updateDashboardStats();
        if (libraryGrid != null) refreshLibraryGrid();
        if (discoverContent != null && searchField != null && searchField.getText().trim().isEmpty()) {
            loadDiscoverHome();
        }
        hideStartupLoadingOverlay();
        startBackgroundDiscoverCoverPreload();
        startBackgroundDiscoverDataRefresh();
    }


    private void preloadDiscoverSectionsSafely(java.util.function.Consumer<String> messageUpdater, java.util.function.IntConsumer progressUpdater) {
        discoverPreloadCache.clear();
        discoverPreloadCache.putAll(loadDiscoverCacheFromDisk());

        String[][] sections = discoverSectionDefinitions();
        boolean cacheChanged = false;

        for (int i = 0; i < sections.length; i++) {
            String[] section = sections[i];
            String title = section[0];
            String key = discoverCacheKey(section[1], section[2], 1, 30);

            if (messageUpdater != null) messageUpdater.accept("Preparo Scopri: " + title + "...");

            List<Anime> cached = discoverPreloadCache.get(key);
            if (cached != null && !cached.isEmpty()) {
                if (progressUpdater != null) progressUpdater.accept(i + 1);
                continue;
            }

            try {
                List<Anime> list = browseWithRetry(section[1], section[2], 1, 30, 2);
                discoverPreloadCache.put(key, new ArrayList<>(list));
                cacheChanged = true;
            } catch (Exception ex) {
                discoverPreloadCache.putIfAbsent(key, new ArrayList<>());
            }

            if (progressUpdater != null) progressUpdater.accept(i + 1);
        }

        if (cacheChanged) saveDiscoverCacheToDisk();
    }

    private void preloadDiscoverVisibleCoversAtStartupSafely(java.util.function.Consumer<String> messageUpdater, java.util.function.IntConsumer progressUpdater) {
        String[][] sections = discoverSectionDefinitions();
        for (int i = 0; i < sections.length; i++) {
            String[] section = sections[i];
            String title = section[0];
            String key = discoverCacheKey(section[1], section[2], 1, 30);
            if (messageUpdater != null) messageUpdater.accept("Caricamento prime cover: " + title + "...");
            List<Anime> list = discoverPreloadCache.get(key);
            preloadAnimeCoversBlocking(list, STARTUP_DISCOVER_VISIBLE_COVERS);
            if (progressUpdater != null) progressUpdater.accept(i + 1);
        }
    }

    private void preloadAnimeCoversBlocking(List<Anime> animeList, int maxCovers) {
        if (animeList == null || animeList.isEmpty()) return;
        int loaded = 0;
        for (Anime anime : animeList) {
            if (anime == null || isBlank(anime.coverImage)) continue;
            String url = normalizeImageUrl(anime.coverImage);
            if (loadImageBytesFromDiskCache(url) == null && !imageBytesCache.containsKey(url)) {
                try {
                    byte[] bytes = downloadImageBytes(url);
                    if (bytes != null && bytes.length > 0) {
                        saveImageBytesToCaches(url, bytes);
                        Image img = new Image(new ByteArrayInputStream(bytes), 0, 0, true, true);
                        if (!img.isError()) imageCache.put(url, img);
                    }
                } catch (Exception ignored) {
                    // Se una cover fallisce, non blocco l'avvio dell'app.
                }
            }
            loaded++;
            if (loaded >= maxCovers) break;
        }
    }

    private List<Anime> browseWithRetry(String mode, String genre, int page, int perPage, int attempts) throws Exception {
        Exception lastError = null;
        for (int i = 1; i <= attempts; i++) {
            try {
                List<Anime> result = client.browse(mode, genre, page, perPage);
                if (result != null) return result;
            } catch (Exception ex) {
                lastError = ex;
            }
            sleepQuietly(250L * i);
        }
        if (lastError != null) throw lastError;
        return new ArrayList<>();
    }

    private void startBackgroundDiscoverCoverPreload() {
        executor.submit(() -> {
            int sectionsDone = 0;
            int totalSections = Math.max(1, discoverPreloadCache.size());
            for (Map.Entry<String, List<Anime>> entry : discoverPreloadCache.entrySet()) {
                List<Anime> list = entry.getValue();
                if (list == null || list.isEmpty()) continue;
                preloadAnimeCoversSafely(list, 30);
                sectionsDone++;
                final int done = sectionsDone;
                Platform.runLater(() -> statusBar.setText("Caricamento copertine Scopri in background: " + done + "/" + totalSections));
            }
            Platform.runLater(() -> statusBar.setText("Copertine Scopri caricate in background."));
        });
    }

    private String discoverCacheKey(String mode, String genre, int page, int perPage) {
        return (mode == null ? "" : mode) + "|" + (genre == null ? "" : genre) + "|" + page + "|" + perPage;
    }

    private void preloadAnimeCoversSafely(List<Anime> animeList, int maxCovers) {
        if (animeList == null || animeList.isEmpty()) return;
        int loaded = 0;
        for (Anime anime : animeList) {
            if (anime == null || isBlank(anime.coverImage)) continue;
            String url = normalizeImageUrl(anime.coverImage);
            if (loadImageBytesFromDiskCache(url) == null && !imageBytesCache.containsKey(url)) {
                try {
                    byte[] bytes = downloadImageBytes(url);
                    if (bytes != null && bytes.length > 0) saveImageBytesToCaches(url, bytes);
                } catch (Exception ignored) { }
            }
            loaded++;
            if (loaded >= maxCovers) break;
        }
    }

    private Map<String, List<Anime>> loadDiscoverCacheFromDisk() {
        try {
            if (!Files.exists(DISCOVER_CACHE_FILE)) return new ConcurrentHashMap<>();
            Map<String, List<Anime>> loaded = appMapper.readValue(
                    DISCOVER_CACHE_FILE.toFile(),
                    new TypeReference<Map<String, List<Anime>>>() {}
            );
            return loaded == null ? new ConcurrentHashMap<>() : new ConcurrentHashMap<>(loaded);
        } catch (Exception ignored) {
            return new ConcurrentHashMap<>();
        }
    }

    private void saveDiscoverCacheToDisk() {
        try {
            Files.createDirectories(APP_DIR);
            appMapper.writerWithDefaultPrettyPrinter().writeValue(DISCOVER_CACHE_FILE.toFile(), discoverPreloadCache);
        } catch (Exception ignored) { }
    }

    private void startBackgroundDiscoverDataRefresh() {
        executor.submit(() -> {
            boolean changed = false;
            String[][] sections = discoverSectionDefinitions();
            for (String[] section : sections) {
                if (shuttingDown) return;
                String key = discoverCacheKey(section[1], section[2], 1, 30);
                try {
                    List<Anime> fresh = browseWithRetry(section[1], section[2], 1, 30, 2);
                    if (fresh != null && !fresh.isEmpty()) {
                        discoverPreloadCache.put(key, new ArrayList<>(fresh));
                        changed = true;
                    }
                } catch (Exception ignored) { }
            }
            if (changed) {
                saveDiscoverCacheToDisk();
                Platform.runLater(() -> {
                    if (discoverContent != null && searchField != null && searchField.getText().trim().isEmpty()) {
                        loadDiscoverHome();
                    }
                });
            }
        });
    }

    private String[][] discoverSectionDefinitions() {
        return new String[][] {
            {"Popolari", "POPULAR", null},
            {"Nuove uscite", "RECENT", null},
            {"Romance", "GENRE", "Romance"},
            {"Azione", "GENRE", "Action"},
            {"Comedy", "GENRE", "Comedy"},
            {"Mystery", "GENRE", "Mystery"},
            {"Horror", "GENRE", "Horror"},
            {"Isekai", "TAG", "Isekai"},
            {"Ecchi", "TAG", "Ecchi"},
            {"Drama", "GENRE", "Drama"},
            {"Slice of Life", "GENRE", "Slice of Life"},
            {"Shōnen", "TAG", "Shounen"}
        };
    }

    private void sleepQuietly(long millis) {
        try { Thread.sleep(millis); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
    }

    private void enrichSavedAnimeDataSafely() {
        boolean changed = false;
        for (Anime saved : manager.all()) {
            if (saved == null || saved.id <= 0) continue;
            boolean needsInfo = isBlank(saved.coverImage) || isBlank(saved.format) || isBlank(saved.year) || isBlank(saved.studio);
            if (!needsInfo) continue;
            try {
                Anime online = client.getAnimeById(saved.id);
                if (online == null) continue;
                Anime.Status oldStatus = saved.status;
                copyMissingAnimeInfo(saved, online);
                saved.status = oldStatus;
                changed = true;
            } catch (Exception ignored) { }
        }
        if (changed) {
            try { manager.saveToDefault(); } catch (Exception ignored) { }
        }
    }

    private void copyMissingAnimeInfo(Anime target, Anime source) {
        if (target == null || source == null) return;
        if (isBlank(target.title)) target.title = source.title;
        if (isBlank(target.coverImage)) target.coverImage = source.coverImage;
        if (target.episodes <= 0) target.episodes = source.episodes;
        if (target.duration <= 0) target.duration = source.duration;
        if ((target.genres == null || target.genres.isEmpty()) && source.genres != null) target.genres = source.genres;
        if (isBlank(target.format)) target.format = source.format;
        if (isBlank(target.airingStatus)) target.airingStatus = source.airingStatus;
        if (isBlank(target.year)) target.year = source.year;
        if (isBlank(target.season)) target.season = source.season;
        if (isBlank(target.studio)) target.studio = source.studio;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank() || "N/D".equalsIgnoreCase(value.trim());
    }

    private void preloadSavedCoversSafely() {
        for (Anime anime : manager.all()) {
            if (anime == null || isBlank(anime.coverImage)) continue;
            String url = normalizeImageUrl(anime.coverImage);
            if (imageBytesCache.containsKey(url)) continue;
            try {
                byte[] bytes = downloadImageBytes(url);
                if (bytes != null && bytes.length > 0) {
                    saveImageBytesToCaches(url, bytes);
                    Image img = new Image(new ByteArrayInputStream(bytes), 0, 0, true, true);
                    if (!img.isError()) imageCache.put(url, img);
                }
            } catch (Exception ignored) { }
        }
    }

    // --- 1. DASHBOARD ---
    private void initDashboardPane() {
        dashboardPane = new VBox(24);
        dashboardPane.setAlignment(Pos.TOP_LEFT);

        Label title = new Label("Dashboard");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 28));

        HBox statsRow = new HBox(20);
        VBox cardHours = createStatCard("TEMPO TOTALE DI VISIONE", totalHoursLabel = new Label("0.0 ore"), "#4d7cff");
        VBox cardWatching = createStatCard("ANIME IN VISIONE", watchingCountLabel = new Label("0"), "#ff9f43");
        VBox cardCompleted = createStatCard("ANIME VISTI", completedCountLabel = new Label("0"), "#1dd1a1");
        statsRow.getChildren().addAll(cardHours, cardWatching, cardCompleted);

        Label rowTitle = new Label("Continua a Guardare");
        rowTitle.setTextFill(Color.web("#cbd5e1"));
        rowTitle.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 18));

        dashboardWatchingRow = new HBox(10); 
        dashboardWatchingRow.setPadding(new Insets(10, 0, 10, 0));
        
        ScrollPane rowScroll = new ScrollPane(dashboardWatchingRow);
        rowScroll.setFitToHeight(true);
        rowScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rowScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");

        dashboardPane.getChildren().addAll(title, statsRow, rowTitle, rowScroll);
    }

    private VBox createStatCard(String title, Label valueLabel, String accentColor) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(18));
        card.setPrefWidth(260);
        card.setStyle("-fx-background-color: rgba(18, 28, 56, 0.6); -fx-background-radius: 14; -fx-border-color: " + accentColor + "; -fx-border-width: 0 0 4 0;");
        Label lblTitle = new Label(title);
        lblTitle.setTextFill(Color.web("#94a3b8"));
        lblTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        valueLabel.setTextFill(Color.WHITE);
        valueLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 24));
        card.getChildren().addAll(lblTitle, valueLabel);
        return card;
    }

    private void updateDashboardStats() {
        double hours = getByStatus(Anime.Status.WATCHED).stream().mapToDouble(Anime::totalHours).sum();
        totalHoursLabel.setText(String.format("%.1f ore", hours));
        watchingCountLabel.setText(String.valueOf(getByStatus(Anime.Status.WATCHING).size()));
        completedCountLabel.setText(String.valueOf(getByStatus(Anime.Status.WATCHED).size()));

        dashboardWatchingRow.getChildren().clear();
        List<Anime> watchingList = getByStatus(Anime.Status.WATCHING);
        if (watchingList.isEmpty()) {
            Label placeholder = new Label("Nessun anime in visione al momento.");
            placeholder.setTextFill(Color.GRAY);
            dashboardWatchingRow.getChildren().add(placeholder);
        } else {
            for (Anime a : watchingList) dashboardWatchingRow.getChildren().add(createAnimeGridCard(a));
        }
    }

    // --- 2. LA MIA LISTA (FILTRI E CONTATORI ALLINEATI AD ANIME.JAVA) ---
    private void initLibraryPane() {
        libraryPane = new VBox(18);
        
        Label title = new Label("La Mia Lista");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 26));

        librarySearchField = new TextField();
        librarySearchField.setPromptText("Cerca anime nella lista...");
        librarySearchField.setPrefWidth(320);
        librarySearchField.setMaxWidth(320);
        librarySearchField.setStyle("-fx-background-color: rgba(15,23,42,0.92); -fx-text-fill: white; -fx-prompt-text-fill: #64748b; -fx-background-radius: 16; -fx-border-radius: 16; -fx-border-color: rgba(148,163,184,0.22); -fx-padding: 11 15; -fx-font-size: 13px;");
        librarySearchField.textProperty().addListener((obs, oldText, newText) -> refreshLibraryGrid());

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);
        HBox headerRow = new HBox(14, title, headerSpacer, librarySearchField);
        headerRow.setAlignment(Pos.CENTER_LEFT);

        HBox filtersRow = new HBox(10);
        
        btnFilterAll = new Button("TUTTI (0)");
        btnFilterWatching = new Button("IN VISIONE (0)");
        btnFilterWatched = new Button("VISTI (0)"); // Sincronizzato con il tuo Anime.java ("Visti")
        btnFilterToWatch = new Button("DA VEDERE (0)");
        btnFilterDropped = new Button("DROPPATO (0)");

        Button[] filterButtons = {btnFilterAll, btnFilterWatching, btnFilterWatched, btnFilterToWatch, btnFilterDropped};
        String[] filterKeys = {"TUTTI", "IN VISIONE", "VISTI", "DA VEDERE", "DROPPATO"};

        for (int i = 0; i < filterButtons.length; i++) {
            Button btn = filterButtons[i];
            String key = filterKeys[i];
            btn.setStyle("-fx-background-color: rgba(30, 41, 74, 0.8); -fx-text-fill: #cbd5e1; -fx-background-radius: 20; -fx-padding: 8 16; -fx-cursor: hand; -fx-font-weight: bold;");
            btn.setOnAction(e -> {
                playClickSound();
                currentLibraryFilter = key;
                refreshLibraryGrid();
            });
            filtersRow.getChildren().add(btn);
        }

        libraryGrid = new TilePane();
        libraryGrid.setHgap(10); // Compatto e ravvicinato
        libraryGrid.setVgap(10);
        libraryGrid.setPrefColumns(5);

        ScrollPane scrollPane = new ScrollPane(libraryGrid);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");

        libraryPane.getChildren().addAll(headerRow, filtersRow, scrollPane);
    }

    private void updateFilterButtonCounts() {
        if (btnFilterAll == null) return;
        btnFilterAll.setText("TUTTI (" + manager.all().size() + ")");
        btnFilterWatching.setText("IN VISIONE (" + getByStatus(Anime.Status.WATCHING).size() + ")");
        btnFilterWatched.setText("VISTI (" + getByStatus(Anime.Status.WATCHED).size() + ")");
        btnFilterToWatch.setText("DA VEDERE (" + getByStatus(Anime.Status.TO_WATCH).size() + ")");
        btnFilterDropped.setText("DROPPATO (" + getByStatus(Anime.Status.DROPPED).size() + ")");
    }

    private void refreshLibraryGrid() {
        libraryGrid.getChildren().clear();
        updateFilterButtonCounts(); 

        List<Anime> sourceList = switch (currentLibraryFilter) {
            case "IN VISIONE" -> getByStatus(Anime.Status.WATCHING);
            case "VISTI" -> getByStatus(Anime.Status.WATCHED);
            case "DA VEDERE" -> getByStatus(Anime.Status.TO_WATCH);
            case "DROPPATO" -> getByStatus(Anime.Status.DROPPED);
            default -> manager.all();
        };

        String libraryQuery = librarySearchField == null ? "" : librarySearchField.getText().trim().toLowerCase();
        if (!libraryQuery.isBlank()) {
            sourceList = sourceList.stream()
                .filter(a -> a.title != null && a.title.toLowerCase().contains(libraryQuery))
                .collect(Collectors.toList());
        }

        if (sourceList.isEmpty()) {
            Label emptyLbl = new Label("Nessun anime trovato.");
            emptyLbl.setTextFill(Color.GRAY);
            emptyLbl.setFont(Font.font("Segoe UI", 16));
            libraryGrid.getChildren().add(emptyLbl);
        } else {
            for (Anime a : sourceList) libraryGrid.getChildren().add(createAnimeGridCard(a));
        }
    }

    // --- 3. RICERCA / SCOPRI ---
    private void initSearchPane() {
        searchPane = new VBox(18);

        Label title = new Label("Esplora e Scopri");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 26));

        searchField = new TextField();
        searchField.setPromptText("Cerca un anime...");
        searchField.setPrefWidth(620);
        searchField.setMaxWidth(620);
        searchField.setStyle("-fx-background-color: #111b34; -fx-text-fill: white; -fx-prompt-text-fill: #64748b; -fx-background-radius: 18; -fx-border-radius: 18; -fx-border-color: #334155; -fx-padding: 13 16; -fx-font-size: 14px;");
        searchField.textProperty().addListener((observable, oldValue, newValue) -> triggerDebouncedSearch(newValue.trim()));

        setupSuggestionPopup();

        discoverContent = new VBox(24);
        discoverContent.setPadding(new Insets(4, 0, 22, 0));

        discoverScrollPane = new ScrollPane(discoverContent);
        discoverScrollPane.setFitToWidth(true);
        discoverScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        discoverScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        discoverScrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");

        floatingDiscoverButton = createFloatingActionButton("← Scopri");
        floatingDiscoverButton.setOnAction(e -> loadDiscoverHome());
        floatingTopButton = createFloatingActionButton("↑ Su");
        floatingTopButton.setOnAction(e -> scrollDiscoverToTop());
        floatingDiscoverButton.setVisible(false);
        floatingDiscoverButton.setManaged(false);
        floatingTopButton.setVisible(false);
        floatingTopButton.setManaged(false);

        HBox floatingBar = new HBox(8, floatingDiscoverButton, floatingTopButton);
        floatingBar.setAlignment(Pos.TOP_RIGHT);
        floatingBar.setPadding(new Insets(10, 10, 0, 0));
        // IMPORTANTE: in uno StackPane un HBox può allargarsi e coprire tutta la sezione.
        // Se prende gli eventi del mouse, Scopri sembra “freezata”: non scorri e non clicchi le card.
        // Così il contenitore resta grande solo quanto i pulsanti e non blocca la UI sotto.
        floatingBar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        floatingBar.setPickOnBounds(false);
        floatingBar.setMouseTransparent(false);

        StackPane discoverWrapper = new StackPane(discoverScrollPane, floatingBar);
        discoverWrapper.setPickOnBounds(false);
        StackPane.setAlignment(floatingBar, Pos.TOP_RIGHT);
        VBox.setVgrow(discoverWrapper, Priority.ALWAYS);

        searchPane.getChildren().addAll(title, searchField, discoverWrapper);
        loadDiscoverHome();
    }

    private void setupSuggestionPopup() {
        suggestionPopup = new Popup();
        suggestionPopup.setAutoHide(true);

        VBox box = new VBox(6);
        box.setPrefWidth(620);
        box.setStyle(
            "-fx-background-color: #0b1224;" +
            "-fx-background-radius: 16;" +
            "-fx-border-radius: 16;" +
            "-fx-border-color: rgba(96,165,250,0.35);" +
            "-fx-border-width: 1;" +
            "-fx-padding: 8;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.50), 22, 0.25, 0, 10);"
        );

        suggestionList = new ListView<>();
        suggestionList.setPrefHeight(330);
        suggestionList.setStyle("-fx-background-color: transparent; -fx-control-inner-background: transparent; -fx-border-color: transparent;");

        suggestionEmptyLabel = new Label("Nessun risultato trovato.");
        suggestionEmptyLabel.setTextFill(Color.web("#94a3b8"));
        suggestionEmptyLabel.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 14));
        suggestionEmptyLabel.setPadding(new Insets(14, 12, 14, 12));
        suggestionEmptyLabel.setVisible(false);
        suggestionEmptyLabel.setManaged(false);
        suggestionList.setCellFactory(list -> new ListCell<>() {
            @Override
            protected void updateItem(Anime anime, boolean empty) {
                super.updateItem(anime, empty);
                if (empty || anime == null) {
                    setGraphic(null);
                    setText(null);
                    setStyle("-fx-background-color: transparent;");
                    return;
                }

                ImageView miniCover = new ImageView();
                miniCover.setFitWidth(44);
                miniCover.setFitHeight(62);
                miniCover.setPreserveRatio(false);
                setImageWithFallback(miniCover, anime.coverImage, 44, 62);
                miniCover.setStyle("-fx-background-radius: 8;");

                VBox texts = new VBox(4);
                texts.setAlignment(Pos.CENTER_LEFT);
                Label name = new Label(anime.title != null ? anime.title : "Titolo sconosciuto");
                name.setTextFill(Color.web("#f8fafc"));
                name.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
                name.setWrapText(true);

                Label meta = new Label(formatValue(anime.format) + " • " + formatEpisodes(anime.episodes) + " ep • " + formatValue(anime.year));
                meta.setTextFill(Color.web("#94a3b8"));
                meta.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 11));

                Label genreLine = new Label(anime.genres != null && !anime.genres.isEmpty() ? String.join(", ", anime.genres.subList(0, Math.min(3, anime.genres.size()))) : "");
                genreLine.setTextFill(Color.web("#64748b"));
                genreLine.setFont(Font.font("Segoe UI", 11));

                texts.getChildren().addAll(name, meta, genreLine);
                HBox row = new HBox(10, miniCover, texts);
                row.setAlignment(Pos.CENTER_LEFT);
                row.setPadding(new Insets(7, 8, 7, 8));

                setGraphic(row);
                setStyle("-fx-background-color: transparent; -fx-padding: 2; -fx-cursor: hand;");
            }
        });
        suggestionList.setOnMouseClicked(e -> {
            Anime selected = suggestionList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                hideSuggestionPopupCompletely();

                suppressSuggestionPopupUpdate = true;
                searchField.setText(selected.title);
                searchField.getParent().requestFocus();
                suppressSuggestionPopupUpdate = false;

                displaySearchResults(List.of(selected), "Risultato selezionato");
                showAnimeDetails(selected);
                e.consume();
            }
        });

        Button showAll = new Button("Mostra tutti i risultati");
        showAll.setMaxWidth(Double.MAX_VALUE);
        showAll.setStyle("-fx-background-color: linear-gradient(to right, #2563eb, #6366f1); -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 12; -fx-padding: 11 14; -fx-cursor: hand; -fx-font-size: 13px;");
        showAll.setOnAction(e -> {
            hideSuggestionPopupCompletely();
            executeOnlineSearch(searchField.getText().trim());
        });

        box.getChildren().addAll(suggestionList, suggestionEmptyLabel, showAll);
        suggestionPopup.getContent().add(box);
    }

    private void hideSuggestionPopupCompletely() {
        if (searchDebounceTimer != null) {
            searchDebounceTimer.cancel();
            searchDebounceTimer = null;
        }
        if (suggestionPopup != null) suggestionPopup.hide();
        if (suggestionList != null) suggestionList.getItems().clear();
        if (suggestionEmptyLabel != null) {
            suggestionEmptyLabel.setVisible(false);
            suggestionEmptyLabel.setManaged(false);
        }
    }

    private void hideActiveCardPopup() {
        if (activeCardPopup != null) {
            activeCardPopup.hide();
            activeCardPopup = null;
        }
    }


    private Button createFloatingActionButton(String text) {
        Button btn = new Button(text);
        btn.setStyle("-fx-background-color: rgba(15,23,42,0.82); -fx-text-fill: #dbeafe; -fx-font-weight: bold; -fx-background-radius: 999; -fx-border-radius: 999; -fx-border-color: rgba(147,197,253,0.35); -fx-padding: 8 14; -fx-cursor: hand; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 14, 0.18, 0, 4);");
        btn.setOnMouseEntered(e -> {
            btn.setStyle("-fx-background-color: rgba(37,99,235,0.90); -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 999; -fx-border-radius: 999; -fx-border-color: rgba(147,197,253,0.65); -fx-padding: 8 14; -fx-cursor: hand; -fx-effect: dropshadow(gaussian, rgba(59,130,246,0.40), 18, 0.25, 0, 5);");
            ScaleTransition st = new ScaleTransition(Duration.millis(120), btn);
            st.setToX(1.04); st.setToY(1.04); st.play();
        });
        btn.setOnMouseExited(e -> {
            btn.setStyle("-fx-background-color: rgba(15,23,42,0.82); -fx-text-fill: #dbeafe; -fx-font-weight: bold; -fx-background-radius: 999; -fx-border-radius: 999; -fx-border-color: rgba(147,197,253,0.35); -fx-padding: 8 14; -fx-cursor: hand; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 14, 0.18, 0, 4);");
            ScaleTransition st = new ScaleTransition(Duration.millis(120), btn);
            st.setToX(1.0); st.setToY(1.0); st.play();
        });
        return btn;
    }

    private void setFloatingDiscoverControls(boolean showDiscover, boolean showTop) {
        if (floatingDiscoverButton != null) {
            floatingDiscoverButton.setVisible(showDiscover);
            floatingDiscoverButton.setManaged(showDiscover);
        }
        if (floatingTopButton != null) {
            floatingTopButton.setVisible(showTop);
            floatingTopButton.setManaged(showTop);
        }
    }

    private void loadDiscoverHome() {
        hideSuggestionPopupCompletely();
        hideActiveCardPopup();
        setFloatingDiscoverControls(false, false);
        discoverContent.getChildren().clear();
        for (String[] section : discoverSectionDefinitions()) {
            discoverContent.getChildren().add(createAnimeRowSection(section[0], section[1], section[2]));
        }
        scrollDiscoverToTop();
    }

    private VBox createAnimeRowSection(String title, String mode, String genre) {
        VBox section = new VBox(10);

        HBox header = new HBox(10);
        header.setAlignment(Pos.CENTER_LEFT);

        Label lbl = new Label(title);
        lbl.setTextFill(Color.WHITE);
        lbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 18));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        Button left = createRoundNavButton("‹");
        Button right = createRoundNavButton("›");

        Button seeAll = new Button("Vedi tutto");
        seeAll.setStyle("-fx-background-color: rgba(37,99,235,0.18); -fx-text-fill: #93c5fd; -fx-font-weight: bold; -fx-background-radius: 14; -fx-padding: 7 14; -fx-cursor: hand;");
        seeAll.setOnAction(e -> loadFullCategory(title, mode, genre));

        header.getChildren().addAll(lbl, spacer, left, right, seeAll);

        HBox row = new HBox(8);
        row.setPadding(new Insets(4, 0, 10, 0));

        ScrollPane rowScroll = new ScrollPane(row);
        rowScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rowScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        rowScroll.setFitToHeight(true);
        rowScroll.setPannable(false);
        rowScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");

        left.setOnAction(e -> smoothHorizontalScroll(rowScroll, Math.max(0, rowScroll.getHvalue() - 0.34)));
        right.setOnAction(e -> smoothHorizontalScroll(rowScroll, Math.min(1, rowScroll.getHvalue() + 0.34)));

        Label loading = new Label("Caricamento...");
        loading.setTextFill(Color.web("#94a3b8"));
        row.getChildren().add(loading);

        String cacheKey = discoverCacheKey(mode, genre, 1, 30);
        List<Anime> cachedSection = discoverPreloadCache.get(cacheKey);
        if (cachedSection != null && !cachedSection.isEmpty()) {
            row.getChildren().clear();
            for (Anime anime : cachedSection) row.getChildren().add(createAnimeGridCard(anime));
        } else {
            Task<List<Anime>> task = new Task<>() {
                @Override protected List<Anime> call() throws Exception {
                    List<Anime> list = browseWithRetry(mode, genre, 1, 30, 3);
                    discoverPreloadCache.put(cacheKey, new ArrayList<>(list));
                    return list;
                }
            };
            task.setOnSucceeded(evt -> {
                row.getChildren().clear();
                List<Anime> value = task.getValue();
                for (Anime anime : value) row.getChildren().add(createAnimeGridCard(anime));
                if (value.isEmpty()) {
                    Label empty = new Label("Nessun risultato.");
                    empty.setTextFill(Color.web("#94a3b8"));
                    row.getChildren().add(empty);
                }
            });
            task.setOnFailed(evt -> {
                row.getChildren().clear();
                Label err = new Label("Errore caricamento. Premi Aggiorna GUI per riprovare.");
                err.setTextFill(Color.web("#fca5a5"));
                row.getChildren().add(err);
            });
            executor.submit(task);
        }

        section.getChildren().addAll(header, rowScroll);
        return section;
    }

    private Button createRoundNavButton(String text) {
        Button btn = new Button(text);
        btn.setMinSize(34, 30);
        btn.setPrefSize(34, 30);
        btn.setStyle("-fx-background-color: rgba(15,23,42,0.92); -fx-text-fill: #e2e8f0; -fx-font-size: 18px; -fx-font-weight: bold; -fx-background-radius: 999; -fx-border-radius: 999; -fx-border-color: rgba(148,163,184,0.24); -fx-cursor: hand; -fx-padding: 0;");
        btn.setOnMouseEntered(e -> btn.setStyle("-fx-background-color: rgba(37,99,235,0.38); -fx-text-fill: white; -fx-font-size: 18px; -fx-font-weight: bold; -fx-background-radius: 999; -fx-border-radius: 999; -fx-border-color: rgba(147,197,253,0.55); -fx-cursor: hand; -fx-padding: 0;"));
        btn.setOnMouseExited(e -> btn.setStyle("-fx-background-color: rgba(15,23,42,0.92); -fx-text-fill: #e2e8f0; -fx-font-size: 18px; -fx-font-weight: bold; -fx-background-radius: 999; -fx-border-radius: 999; -fx-border-color: rgba(148,163,184,0.24); -fx-cursor: hand; -fx-padding: 0;"));
        return btn;
    }

    private void loadFullCategory(String title, String mode, String genre) {
        if (suggestionPopup != null) suggestionPopup.hide();
        hideActiveCardPopup();
        setFloatingDiscoverControls(true, false);
        discoverContent.getChildren().clear();
        scrollDiscoverToTop();
        HBox top = new HBox(12);
        top.setAlignment(Pos.CENTER_LEFT);
        Label lbl = new Label(title);
        lbl.setTextFill(Color.WHITE);
        lbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 22));
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        top.getChildren().addAll(lbl, topSpacer);

        TilePane grid = new TilePane();
        grid.setHgap(18);
        grid.setVgap(20);
        grid.setPrefColumns(5);

        Button loadMore = new Button("Visualizza altri");
        loadMore.setMaxWidth(Double.MAX_VALUE);
        loadMore.setStyle("-fx-background-color: linear-gradient(to right, #2563eb, #6366f1); -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 14; -fx-padding: 12 20; -fx-cursor: hand; -fx-font-size: 14px;");
        final int[] page = {1};
        final boolean[] endReached = {false};
        loadMore.setOnAction(e -> {
            if (!endReached[0]) {
                loadCategoryPage(grid, loadMore, mode, genre, page[0], 30, endReached);
                page[0]++;
            }
        });

        discoverContent.getChildren().addAll(top, grid, loadMore);
        loadMore.fire();
    }

    private void loadCategoryPage(TilePane grid, Button loadMore, String mode, String genre, int page, int amount, boolean[] endReached) {
        statusBar.setText("Caricamento...");
        loadMore.setDisable(true);
        loadMore.setText("Caricamento...");
        Task<List<Anime>> task = new Task<>() {
            @Override protected List<Anime> call() throws Exception {
                return browseWithRetry(mode, genre, page, amount, 3);
            }
        };
        task.setOnSucceeded(evt -> {
            List<Anime> loaded = task.getValue();
            for (Anime anime : loaded) grid.getChildren().add(createAnimeGridCard(anime));
            boolean showTop = grid.getChildren().size() >= 29;
            setFloatingDiscoverControls(true, showTop);
            statusBar.setText(loaded.isEmpty() ? "Non ci sono altri anime da caricare." : "Caricati altri " + loaded.size() + " anime.");
            if (loaded.size() < amount) {
                endReached[0] = true;
                loadMore.setText("Fine risultati");
                loadMore.setDisable(true);
            } else {
                loadMore.setText("Visualizza altri");
                loadMore.setDisable(false);
            }
        });
        task.setOnFailed(evt -> {
            statusBar.setText("Errore durante il caricamento.");
            loadMore.setText("Riprova");
            loadMore.setDisable(false);
        });
        executor.submit(task);
    }

    private void triggerDebouncedSearch(String query) {
        if (searchDebounceTimer != null) searchDebounceTimer.cancel();

        if (suppressSuggestionPopupUpdate) {
            hideSuggestionPopupCompletely();
            return;
        }

        if (query.length() < 2) {
            hideSuggestionPopupCompletely();
            return;
        }

        searchDebounceTimer = new Timer();
        searchDebounceTimer.schedule(new TimerTask() {
            @Override public void run() { Platform.runLater(() -> loadSuggestions(query)); }
        }, 260);
    }

    private void loadSuggestions(String query) {
        Task<List<Anime>> task = new Task<>() {
            @Override protected List<Anime> call() throws Exception { return client.search(query, 7); }
        };
        task.setOnSucceeded(evt -> {
            if (suppressSuggestionPopupUpdate || searchField == null || !searchField.isFocused()) {
                hideSuggestionPopupCompletely();
                return;
            }

            List<Anime> suggestions = task.getValue() == null ? List.of() : task.getValue();
            suggestionList.getItems().setAll(suggestions);

            boolean hasResults = !suggestions.isEmpty();
            suggestionList.setVisible(hasResults);
            suggestionList.setManaged(hasResults);
            if (suggestionEmptyLabel != null) {
                suggestionEmptyLabel.setVisible(!hasResults);
                suggestionEmptyLabel.setManaged(!hasResults);
            }

            if (searchField.getScene() != null) {
                Point2D p = searchField.localToScreen(0, searchField.getHeight() + 6);
                if (!suggestionPopup.isShowing()) {
                    suggestionPopup.show(searchField, p.getX(), p.getY());
                } else {
                    suggestionPopup.setX(p.getX());
                    suggestionPopup.setY(p.getY());
                }
            } else {
                hideSuggestionPopupCompletely();
            }
        });
        task.setOnFailed(evt -> hideSuggestionPopupCompletely());
        executor.submit(task);
    }

    private void executeOnlineSearch(String query) {
        if (query == null || query.isBlank()) return;
        hideSuggestionPopupCompletely();
        hideActiveCardPopup();
        statusBar.setText("Ricerca in corso...");
        Task<List<Anime>> searchTask = new Task<>() {
            @Override protected List<Anime> call() throws Exception { return client.search(query, 30); }
        };
        searchTask.setOnSucceeded(evt -> {
            List<Anime> results = searchTask.getValue();
            displaySearchResults(results, "Risultati per: " + query);
            statusBar.setText(results.isEmpty() ? "Nessun risultato trovato." : "Trovati " + results.size() + " risultati.");
        });
        searchTask.setOnFailed(evt -> statusBar.setText("Errore ricerca. Controlla la connessione."));
        executor.submit(searchTask);
    }

    private void displaySearchResults(List<Anime> results, String title) {
        hideSuggestionPopupCompletely();
        hideActiveCardPopup();
        setFloatingDiscoverControls(true, results != null && results.size() >= 29);
        discoverContent.getChildren().clear();
        scrollDiscoverToTop();
        HBox top = new HBox(12);
        top.setAlignment(Pos.CENTER_LEFT);
        Label lbl = new Label(title);
        lbl.setTextFill(Color.WHITE);
        lbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 22));
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        top.getChildren().addAll(lbl, topSpacer);

        TilePane grid = new TilePane();
        grid.setHgap(18);
        grid.setVgap(20);
        grid.setPrefColumns(5);
        for (Anime a : results) grid.getChildren().add(createAnimeGridCard(a));
        if (results.isEmpty()) {
            Label empty = new Label("Nessun anime trovato.");
            empty.setTextFill(Color.web("#94a3b8"));
            empty.setFont(Font.font("Segoe UI", 16));
            grid.getChildren().add(empty);
        }
        discoverContent.getChildren().addAll(top, grid);
    }

    private void scrollDiscoverToTop() {
        if (discoverScrollPane != null) Platform.runLater(() -> discoverScrollPane.setVvalue(0));
    }

    // --- 4. IMPOSTAZIONI ---
    private void initSettingsPane() {
        settingsPane = new VBox(22);
        settingsPane.setPadding(new Insets(10));

        HBox header = new HBox(16);
        header.setAlignment(Pos.CENTER_LEFT);

        VBox headerTexts = new VBox(4);
        Label title = new Label("Impostazioni");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 30));

        Label subtitle = new Label("Personalizza MyAnimeDesk e gestisci i dati dell'app.");
        subtitle.setTextFill(Color.web("#94a3b8"));
        subtitle.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));
        headerTexts.getChildren().addAll(title, subtitle);

        Region headerSpacer = new Region();
        HBox.setHgrow(headerSpacer, Priority.ALWAYS);

        Label versionBadge = createSettingsBadge("v" + APP_VERSION);
        header.getChildren().addAll(headerTexts, headerSpacer, versionBadge);

        GridPane settingsGrid = new GridPane();
        settingsGrid.setHgap(18);
        settingsGrid.setVgap(18);
        settingsGrid.setMaxWidth(Double.MAX_VALUE);

        ColumnConstraints col1 = new ColumnConstraints();
        col1.setPercentWidth(50);
        col1.setHgrow(Priority.ALWAYS);
        ColumnConstraints col2 = new ColumnConstraints();
        col2.setPercentWidth(50);
        col2.setHgrow(Priority.ALWAYS);
        settingsGrid.getColumnConstraints().addAll(col1, col2);

        VBox updateCard = createSettingsCard(
            "Aggiornamenti",
            "Controlla se esiste una nuova versione disponibile su GitHub."
        );
        Label updateInfo = createSettingsMutedText("Versione installata: " + APP_VERSION);
        Button btnCheckUpdates = createSettingsButton("Controlla aggiornamenti");
        btnCheckUpdates.setOnAction(e -> checkForUpdates(true));
        updateCard.getChildren().addAll(updateInfo, btnCheckUpdates);

        VBox themeCard = createSettingsCard(
            "Aspetto",
            "Scegli un colore o imposta un'immagine come sfondo dell'app."
        );
        ColorPicker colorPicker = new ColorPicker(Color.web(currentBackgroundColor));
        colorPicker.setStyle(
            "-fx-background-color: rgba(15,23,42,0.88);" +
            "-fx-background-radius: 14;" +
            "-fx-color-label-visible: false;" +
            "-fx-cursor: hand;"
        );
        colorPicker.setOnAction(e -> applySolidBackground(colorPicker.getValue()));

        Button btnChooseBg = createSettingsButton("Scegli immagine");
        btnChooseBg.setOnAction(e -> chooseBackgroundImage());

        Button btnResetBg = createSettingsButton("Sfondo predefinito");
        btnResetBg.setOnAction(e -> applySolidBackground(Color.web(DEFAULT_BACKGROUND_COLOR)));

        HBox bgActions = new HBox(10, colorPicker, btnChooseBg, btnResetBg);
        bgActions.setAlignment(Pos.CENTER_LEFT);
        themeCard.getChildren().add(bgActions);

        VBox dataCard = createSettingsCard(
            "Backup e dati",
            "Esporta, importa o elimina la tua libreria personale."
        );
        Button btnExport = createSettingsButton("Esporta lista");
        btnExport.setOnAction(e -> exportLibraryWithDialog());

        Button btnImport = createSettingsButton("Importa lista");
        btnImport.setOnAction(e -> importLibraryWithDialog());

        Button btnClear = createSettingsButtonDanger("Cancella tutta la lista");
        btnClear.setOnAction(e -> clearLibraryWithConfirm());

        HBox dataActions = new HBox(10, btnExport, btnImport, btnClear);
        dataActions.setAlignment(Pos.CENTER_LEFT);
        dataCard.getChildren().add(dataActions);

        VBox infoCard = createSettingsCard(
            "Informazioni app",
            "Dati locali usati da MyAnimeDesk per cache, impostazioni e libreria."
        );
        Label appDirLabel = createSettingsMutedText("Cartella dati: " + APP_DIR.toString());
        appDirLabel.setWrapText(true);
        Label cacheLabel = createSettingsMutedText("Cache immagini e dati Scopri attiva per avvii più veloci.");
        Button btnOpenReleases = createSettingsButton("Apri GitHub Releases");
        btnOpenReleases.setOnAction(e -> openUrl(RELEASES_URL));
        infoCard.getChildren().addAll(appDirLabel, cacheLabel, btnOpenReleases);

        settingsGrid.add(updateCard, 0, 0);
        settingsGrid.add(themeCard, 1, 0);
        settingsGrid.add(dataCard, 0, 1);
        settingsGrid.add(infoCard, 1, 1);

        settingsPane.getChildren().addAll(header, settingsGrid);
    }

    private VBox createSettingsCard(String titleText, String subtitleText) {
        VBox card = new VBox(13);
        card.setPadding(new Insets(20));
        card.setMinHeight(175);
        card.setMaxWidth(Double.MAX_VALUE);
        card.setStyle(
            "-fx-background-color: rgba(7, 12, 28, 0.74);" +
            "-fx-background-radius: 24;" +
            "-fx-border-radius: 24;" +
            "-fx-border-color: " + accentRgba(0.35) + ";" +
            "-fx-border-width: 1;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.28), 22, 0.18, 0, 8);"
        );

        Label title = new Label(titleText);
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 18));

        Label subtitle = createSettingsMutedText(subtitleText);
        subtitle.setWrapText(true);

        card.getChildren().addAll(title, subtitle);
        return card;
    }

    private Label createSettingsMutedText(String text) {
        Label label = new Label(text);
        label.setTextFill(Color.web("#94a3b8"));
        label.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 12.5));
        return label;
    }

    private Label createSettingsBadge(String text) {
        Label badge = new Label(text);
        badge.setTextFill(Color.WHITE);
        badge.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 13));
        badge.setPadding(new Insets(7, 14, 7, 14));
        badge.setStyle(
            "-fx-background-color: linear-gradient(to right, " + currentAccentColor + ", #a855f7);" +
            "-fx-background-radius: 999;" +
            "-fx-border-radius: 999;" +
            "-fx-border-color: " + accentRgba(0.75) + ";" +
            "-fx-border-width: 1;" +
            "-fx-effect: dropshadow(gaussian, " + accentRgba(0.35) + ", 14, 0.2, 0, 3);"
        );
        return badge;
    }

    private Button createSettingsButton(String text) {
        Button btn = new Button(text);
        btn.setMinHeight(38);
        btn.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 12.5));
        btn.setStyle(
            "-fx-background-color: rgba(15, 23, 42, 0.88);" +
            "-fx-text-fill: white;" +
            "-fx-background-radius: 14;" +
            "-fx-border-radius: 14;" +
            "-fx-border-color: " + accentRgba(0.30) + ";" +
            "-fx-border-width: 1;" +
            "-fx-padding: 9 15;" +
            "-fx-cursor: hand;"
        );
        btn.setOnMouseEntered(e -> btn.setStyle(
            "-fx-background-color: linear-gradient(to right, " + currentAccentColor + ", #7c3aed);" +
            "-fx-text-fill: white;" +
            "-fx-background-radius: 14;" +
            "-fx-border-radius: 14;" +
            "-fx-border-color: " + accentRgba(0.75) + ";" +
            "-fx-border-width: 1;" +
            "-fx-padding: 9 15;" +
            "-fx-cursor: hand;" +
            "-fx-effect: dropshadow(gaussian, " + accentRgba(0.35) + ", 14, 0.2, 0, 3);"
        ));
        btn.setOnMouseExited(e -> btn.setStyle(
            "-fx-background-color: rgba(15, 23, 42, 0.88);" +
            "-fx-text-fill: white;" +
            "-fx-background-radius: 14;" +
            "-fx-border-radius: 14;" +
            "-fx-border-color: " + accentRgba(0.30) + ";" +
            "-fx-border-width: 1;" +
            "-fx-padding: 9 15;" +
            "-fx-cursor: hand;"
        ));
        return btn;
    }

    private Button createSettingsButtonDanger(String text) {
        Button btn = new Button(text);
        btn.setMinHeight(38);
        btn.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 12.5));
        btn.setStyle(
            "-fx-background-color: linear-gradient(to right, #dc2626, #ef4444);" +
            "-fx-text-fill: white;" +
            "-fx-background-radius: 14;" +
            "-fx-border-radius: 14;" +
            "-fx-border-color: rgba(254,202,202,0.78);" +
            "-fx-border-width: 1;" +
            "-fx-padding: 9 15;" +
            "-fx-cursor: hand;" +
            "-fx-effect: dropshadow(gaussian, rgba(239,68,68,0.35), 14, 0.25, 0, 3);"
        );
        btn.setOnMouseEntered(e -> btn.setStyle(
            "-fx-background-color: linear-gradient(to right, #b91c1c, #ef4444);" +
            "-fx-text-fill: white;" +
            "-fx-background-radius: 14;" +
            "-fx-border-radius: 14;" +
            "-fx-border-color: rgba(254,202,202,0.95);" +
            "-fx-border-width: 1;" +
            "-fx-padding: 9 15;" +
            "-fx-cursor: hand;" +
            "-fx-effect: dropshadow(gaussian, rgba(239,68,68,0.55), 18, 0.25, 0, 4);"
        ));
        btn.setOnMouseExited(e -> btn.setStyle(
            "-fx-background-color: linear-gradient(to right, #dc2626, #ef4444);" +
            "-fx-text-fill: white;" +
            "-fx-background-radius: 14;" +
            "-fx-border-radius: 14;" +
            "-fx-border-color: rgba(254,202,202,0.78);" +
            "-fx-border-width: 1;" +
            "-fx-padding: 9 15;" +
            "-fx-cursor: hand;" +
            "-fx-effect: dropshadow(gaussian, rgba(239,68,68,0.35), 14, 0.25, 0, 3);"
        ));
        return btn;
    }

    private void exportLibraryWithDialog() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Esporta la tua Lista");
        fc.setInitialFileName("myanimedesk_backup.json");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("File JSON", "*.json"));
        File dest = fc.showSaveDialog(root.getScene().getWindow());
        if (dest != null) {
            try {
                manager.saveToDefault(); 
                File source = new File(System.getProperty("user.home") + "/.myanimedesk/library.json");
                if (source.exists()) Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                statusBar.setText("Lista esportata in: " + dest.getName());
            } catch (IOException e) { }
        }
    }

    private void importLibraryWithDialog() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Importa Backup");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("File JSON", "*.json"));
        File src = fc.showOpenDialog(root.getScene().getWindow());
        if (src != null) {
            try {
                File localDest = new File(System.getProperty("user.home") + "/.myanimedesk/library.json");
                if (!localDest.getParentFile().exists()) localDest.getParentFile().mkdirs();
                Files.copy(src.toPath(), localDest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                manager.loadFromDefault();
                updateDashboardStats();
                refreshLibraryGrid();
                statusBar.setText("Backup importato con successo!");
            } catch (Exception e) { }
        }
    }

    // --- 5. SMART CARD ANIME (POPUP AFFIANCATO E AGGIORNAMENTO IN-PLACE) ---
    private StackPane createAnimeGridCard(Anime anime) {
        StackPane cardRoot = new StackPane();
        cardRoot.setPadding(new Insets(7));
        cardRoot.setMinWidth(186);
        cardRoot.setPrefWidth(186);
        cardRoot.setMinHeight(342);

        VBox baseLayer = new VBox(8);
        baseLayer.setPadding(new Insets(10));
        baseLayer.setPrefWidth(172);
        baseLayer.setMaxWidth(172);

        Label statusBadge = new Label();
        statusBadge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 9));
        statusBadge.setPadding(new Insets(4, 7, 4, 7));
        statusBadge.setStyle("-fx-background-radius: 999; -fx-text-fill: white;");

        Runnable updateBaseStyle = () -> {
            Anime currentLocal = manager.all().stream().filter(x -> x.id == anime.id).findFirst().orElse(null);
            String borderAccent = "#223254";
            if (currentLocal != null) {
                anime.status = currentLocal.status;
                switch (currentLocal.status) {
                    case WATCHING -> { borderAccent = "#ff9f43"; statusBadge.setText("IN VISIONE"); statusBadge.setStyle("-fx-background-radius: 999; -fx-text-fill: white; -fx-background-color: #ff9f43;"); }
                    case WATCHED -> { borderAccent = "#1dd1a1"; statusBadge.setText("VISTO"); statusBadge.setStyle("-fx-background-radius: 999; -fx-text-fill: white; -fx-background-color: #1dd1a1;"); }
                    case DROPPED -> { borderAccent = "#ff6b6b"; statusBadge.setText("DROPPATO"); statusBadge.setStyle("-fx-background-radius: 999; -fx-text-fill: white; -fx-background-color: #ff6b6b;"); }
                    case TO_WATCH -> { borderAccent = "#3b82f6"; statusBadge.setText("DA VEDERE"); statusBadge.setStyle("-fx-background-radius: 999; -fx-text-fill: white; -fx-background-color: #3b82f6;"); }
                }
            } else {
                anime.status = Anime.Status.TO_WATCH;
                statusBadge.setText("NON IN LISTA");
                statusBadge.setStyle("-fx-background-radius: 999; -fx-text-fill: white; -fx-background-color: #475569;");
            }
            baseLayer.setStyle("-fx-background-color: rgba(15,23,42,0.88); -fx-background-radius: 16; -fx-border-radius: 16; -fx-border-color: " + borderAccent + "; -fx-border-width: 1.6; -fx-cursor: hand; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.24), 12, 0.16, 0, 4);");
        };
        updateBaseStyle.run();
        cardRoot.getProperties().put("refreshCard", updateBaseStyle);
        cardRoot.setUserData(anime);

        ImageView poster = new ImageView();
        poster.setFitWidth(148);
        poster.setFitHeight(210);
        poster.setPreserveRatio(false);
        setImageWithFallback(poster, anime.coverImage, 148, 210);

        Label title = new Label(anime.title != null ? anime.title : "Titolo sconosciuto");
        title.setTextFill(Color.web("#f8fafc"));
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 14));
        title.setWrapText(true);
        title.setPrefHeight(44);
        title.setLineSpacing(1.2);

        Label smallMeta = new Label(formatValue(anime.format) + "  •  " + formatEpisodes(anime.episodes) + " ep");
        smallMeta.setTextFill(Color.web("#93a4bd"));
        smallMeta.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 11));

        baseLayer.getChildren().addAll(statusBadge, poster, title, smallMeta);
        cardRoot.getChildren().add(baseLayer);

        Popup sidePopup = new Popup();
        sidePopup.setAutoHide(false);
        sidePopup.setHideOnEscape(true);

        VBox popupContent = new VBox(9);
        popupContent.setPrefWidth(192);
        popupContent.setStyle("-fx-background-color: #071020; -fx-border-color: rgba(96,165,250,0.70); -fx-border-width: 1.3; -fx-background-radius: 14; -fx-border-radius: 14; -fx-padding: 13; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.62), 22, 0.22, 0, 8);");

        VBox hoverInfo = new VBox(7);
        hoverInfo.getChildren().addAll(
            createHoverInfoLine("Tipo", formatValue(anime.format)),
            createHoverInfoLine("Episodi", formatEpisodes(anime.episodes)),
            createHoverInfoLine("Durata", formatDuration(anime.duration)),
            createHoverInfoLine("Anno", formatValue(anime.year)),
            createHoverInfoLine("Generi", anime.genres != null && !anime.genres.isEmpty() ? String.join(", ", anime.genres) : "N/D")
        );

        VBox quickAddMenu = new VBox(6);
        quickAddMenu.setAlignment(Pos.CENTER);
        Label quickTitle = new Label("Imposta stato");
        quickTitle.setTextFill(Color.web("#cbd5e1"));
        quickTitle.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));

        String btnBaseStyle = "-fx-background-radius: 10; -fx-text-fill: white; -fx-cursor: hand; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 7 0;";
        Button btnWatch = new Button("In Visione"); btnWatch.setMaxWidth(Double.MAX_VALUE); btnWatch.setStyle("-fx-background-color: #ff9f43; " + btnBaseStyle);
        Button btnSeen = new Button("Visto"); btnSeen.setMaxWidth(Double.MAX_VALUE); btnSeen.setStyle("-fx-background-color: #1dd1a1; " + btnBaseStyle);
        Button btnPlan = new Button("Da Vedere"); btnPlan.setMaxWidth(Double.MAX_VALUE); btnPlan.setStyle("-fx-background-color: #3b82f6; " + btnBaseStyle);
        Button btnDrop = new Button("Droppato"); btnDrop.setMaxWidth(Double.MAX_VALUE); btnDrop.setStyle("-fx-background-color: #ff6b6b; " + btnBaseStyle);

        Runnable applyQuickStatus = () -> {
            manager.add(anime);
            saveLibraryData("Stato aggiornato.");
            updateBaseStyle.run();
            refreshAllViews();
            sidePopup.hide();
        };

        btnWatch.setOnAction(e -> { anime.status = Anime.Status.WATCHING; applyQuickStatus.run(); });
        btnSeen.setOnAction(e -> { anime.status = Anime.Status.WATCHED; applyQuickStatus.run(); });
        btnPlan.setOnAction(e -> { anime.status = Anime.Status.TO_WATCH; applyQuickStatus.run(); });
        btnDrop.setOnAction(e -> { anime.status = Anime.Status.DROPPED; applyQuickStatus.run(); });
        quickAddMenu.getChildren().addAll(quickTitle, btnWatch, btnSeen, btnPlan, btnDrop);

        if (manager.all().stream().anyMatch(x -> x.id == anime.id)) popupContent.getChildren().addAll(hoverInfo);
        else popupContent.getChildren().addAll(hoverInfo, new Separator(), quickAddMenu);
        sidePopup.getContent().add(popupContent);

        PauseTransition showDelay = new PauseTransition(Duration.millis(280));
        PauseTransition hideDelay = new PauseTransition(Duration.millis(80));
        hideDelay.setOnFinished(evt -> {
            if (!cardRoot.isHover() && !popupContent.isHover()) {
                sidePopup.hide();
                if (activeCardPopup == sidePopup) activeCardPopup = null;
            }
        });

        showDelay.setOnFinished(evt -> {
            if (!cardRoot.isHover()) return;
            if (activeCardPopup != null && activeCardPopup != sidePopup) {
                activeCardPopup.hide();
            }
            activeCardPopup = sidePopup;
            Point2D rightPos = cardRoot.localToScreen(cardRoot.getWidth() + 14, 6);
            Point2D leftPos = cardRoot.localToScreen(-206, 6);
            double popupX = rightPos.getX();
            if (cardRoot.getScene() != null && cardRoot.getScene().getWindow() != null) {
                double screenRight = cardRoot.getScene().getWindow().getX() + cardRoot.getScene().getWindow().getWidth();
                if (popupX + 210 > screenRight) popupX = leftPos.getX();
            }
            if (!sidePopup.isShowing()) {
                sidePopup.show(cardRoot, popupX, rightPos.getY());
            } else {
                sidePopup.setX(popupX);
                sidePopup.setY(rightPos.getY());
            }
        });

        cardRoot.setOnMouseEntered(e -> {
            hideDelay.stop();
            showDelay.playFromStart();
            baseLayer.toFront();
            ScaleTransition st = new ScaleTransition(Duration.millis(150), baseLayer);
            st.setToX(1.035);
            st.setToY(1.035);
            st.setInterpolator(Interpolator.EASE_OUT);
            st.play();
        });
        cardRoot.setOnMouseExited(e -> {
            showDelay.stop();
            hideDelay.playFromStart();
            ScaleTransition st = new ScaleTransition(Duration.millis(150), baseLayer);
            st.setToX(1.0);
            st.setToY(1.0);
            st.setInterpolator(Interpolator.EASE_OUT);
            st.play();
        });
        popupContent.setOnMouseEntered(e -> { hideDelay.stop(); showDelay.stop(); });
        popupContent.setOnMouseExited(e -> hideDelay.playFromStart());

        cardRoot.setOnMouseClicked(e -> {
            hideSuggestionPopupCompletely();
            sidePopup.hide();
            if (activeCardPopup == sidePopup) activeCardPopup = null;
            showAnimeDetails(anime);
        });

        return cardRoot;
    }




    private void setImageWithFallback(ImageView view, String imageUrl, double width, double height) {
        if (view == null) return;
        view.setFitWidth(width);
        view.setFitHeight(height);
        view.setPreserveRatio(false);
        view.setSmooth(true);
        view.setCache(true);

        Image placeholder = createPlaceholderImage(width, height);
        if (isBlank(imageUrl)) {
            view.setImage(placeholder);
            return;
        }

        String normalizedUrl = normalizeImageUrl(imageUrl);
        if (isBlank(normalizedUrl)) {
            view.setImage(placeholder);
            return;
        }

        Image cached = imageCache.get(normalizedUrl);
        if (cached != null && !cached.isError()) {
            view.setImage(cached);
            return;
        }

        byte[] cachedBytes = imageBytesCache.get(normalizedUrl);
        if (cachedBytes == null || cachedBytes.length == 0) {
            cachedBytes = loadImageBytesFromDiskCache(normalizedUrl);
        }
        if (cachedBytes != null && cachedBytes.length > 0) {
            try {
                Image img = new Image(new ByteArrayInputStream(cachedBytes), 0, 0, true, true);
                if (!img.isError()) {
                    imageBytesCache.put(normalizedUrl, cachedBytes);
                    imageCache.put(normalizedUrl, img);
                    view.setImage(img);
                    return;
                }
            } catch (Exception ignored) { }
        }

        view.setImage(placeholder);
        registerPendingImageView(normalizedUrl, view);
        startImageDownloadIfNeeded(normalizedUrl, width, height);
    }

    private void registerPendingImageView(String url, ImageView view) {
        pendingImageViews.compute(url, (k, oldList) -> {
            List<ImageView> list = oldList == null ? Collections.synchronizedList(new ArrayList<>()) : oldList;
            list.add(view);
            return list;
        });
    }

    private void startImageDownloadIfNeeded(String url, double width, double height) {
        if (!imageDownloadsInProgress.add(url)) return;
        executor.submit(() -> {
            try {
                byte[] bytes = downloadImageBytes(url);
                if (bytes == null || bytes.length == 0) return;
                saveImageBytesToCaches(url, bytes);
                Platform.runLater(() -> {
                    Image img = new Image(new ByteArrayInputStream(bytes), 0, 0, true, true);
                    if (!img.isError()) {
                        imageCache.put(url, img);
                        List<ImageView> waiting = pendingImageViews.remove(url);
                        if (waiting != null) {
                            synchronized (waiting) {
                                for (ImageView imageView : waiting) {
                                    if (imageView != null) imageView.setImage(img);
                                }
                            }
                        }
                    }
                });
            } catch (Exception ignored) {
            } finally {
                imageDownloadsInProgress.remove(url);
            }
        });
    }

    private byte[] loadImageBytesFromDiskCache(String url) {
        try {
            if (isBlank(url)) return null;
            byte[] memory = imageBytesCache.get(url);
            if (memory != null && memory.length > 0) return memory;

            Path file = imageCacheFile(url);
            if (!Files.exists(file)) return null;

            byte[] bytes = Files.readAllBytes(file);
            if (bytes.length > 0) {
                imageBytesCache.put(url, bytes);
                return bytes;
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void saveImageBytesToCaches(String url, byte[] bytes) {
        if (isBlank(url) || bytes == null || bytes.length == 0) return;
        imageBytesCache.put(url, bytes);
        try {
            Files.createDirectories(IMAGE_CACHE_DIR);
            Files.write(imageCacheFile(url), bytes);
        } catch (Exception ignored) { }
    }

    private Path imageCacheFile(String url) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return IMAGE_CACHE_DIR.resolve(HexFormat.of().formatHex(hash) + ".img");
    }

    private String normalizeImageUrl(String imageUrl) {
        if (imageUrl == null) return "";
        String url = imageUrl.trim();
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("http://")) return "https://" + url.substring("http://".length());
        return url;
    }

    private byte[] downloadImageBytes(String imageUrl) throws IOException, InterruptedException {
        String normalizedUrl = normalizeImageUrl(imageUrl);
        byte[] cached = loadImageBytesFromDiskCache(normalizedUrl);
        if (cached != null && cached.length > 0) return cached;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(normalizedUrl))
                .timeout(java.time.Duration.ofSeconds(14))
                .header("User-Agent", "MyAnimeDesk/0.4.0")
                .GET()
                .build();
        HttpResponse<byte[]> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
        if (response.statusCode() >= 200 && response.statusCode() < 300) return response.body();
        throw new IOException("Errore download cover: HTTP " + response.statusCode());
    }

    private Image createPlaceholderImage(double width, double height) {
        int w = Math.max(1, (int) width);
        int h = Math.max(1, (int) height);
        WritableImage placeholder = new WritableImage(w, h);
        PixelWriter writer = placeholder.getPixelWriter();
        Color base = Color.web("#111827");
        Color stripe = Color.web("#1e293b");
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                writer.setColor(x, y, ((x + y) % 18 < 9) ? base : stripe);
            }
        }
        return placeholder;
    }

    private HBox createHoverInfoLine(String key, String value) {
        HBox row = new HBox(6);
        row.setAlignment(Pos.TOP_LEFT);
        Label k = new Label(key + ":");
        k.setTextFill(Color.web("#93c5fd"));
        k.setFont(Font.font("Segoe UI", FontWeight.BOLD, 11));
        k.setMinWidth(52);

        Label v = new Label(value);
        v.setTextFill(Color.web("#e2e8f0"));
        v.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 11));
        v.setWrapText(true);
        v.setMaxWidth(100);
        row.getChildren().addAll(k, v);
        return row;
    }

    // --- 6. DETTAGLI MODALI PREMIUM (VOCI PULITE AGGANCIATE SOLO AD ANIME.JAVA) ---
    private void initDetailOverlay() {
        detailOverlay = new StackPane();
        detailOverlay.setStyle("-fx-background-color: rgba(3, 7, 18, 0.84);");
        detailOverlay.setVisible(false);
        detailOverlay.setPickOnBounds(true);
        detailOverlay.setOnMouseClicked(e -> {
            // Se clicchi fuori dal riquadro dei dettagli, il popup si chiude.
            if (e.getTarget() == detailOverlay) {
                closeAnimeDetailsOverlay();
            }
        });

        detailDialogBox = new HBox(28);
        detailDialogBox.setPadding(new Insets(24));
        detailDialogBox.setMaxSize(860, 530);
        detailDialogBox.setOnMouseClicked(e -> e.consume());

        coverView = new ImageView();
        coverView.setFitWidth(240); coverView.setFitHeight(360); coverView.setPreserveRatio(true);

        VBox contentSide = new VBox(14);
        contentSide.setAlignment(Pos.TOP_LEFT);
        contentSide.setPrefWidth(520);

        titleLabel = new Label();
        titleLabel.setTextFill(Color.WHITE);
        titleLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 24));
        titleLabel.setWrapText(true);

        metaLabel = new Label();
        metaLabel.setTextFill(Color.web("#38bdf8"));
        metaLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));

        extendedInfoBox = new VBox(6);
        extendedInfoBox.setPrefHeight(280);

        ScrollPane infoScroll = new ScrollPane(extendedInfoBox);
        infoScroll.setFitToWidth(true);
        infoScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");

        addButton = new Button("Aggiungi alla lista");
        addButton.setMaxWidth(Double.MAX_VALUE);
        addButton.setMinHeight(46);
        addButton.setStyle("-fx-background-color: linear-gradient(to right, #10b981, #22c55e); -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 14; -fx-cursor: hand; -fx-padding: 12 18; -fx-font-size: 15px;");
        addButton.setOnAction(e -> addAnimeToLibrary());

        detailStatusChoiceLabel = new Label("Imposta stato di visione");
        detailStatusChoiceLabel.setTextFill(Color.web("#dbeafe"));
        detailStatusChoiceLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));

        detailPlanButton = createStatusChip("Da vedere", Anime.Status.TO_WATCH);
        detailWatchingButton = createStatusChip("In visione", Anime.Status.WATCHING);
        detailWatchedButton = createStatusChip("Visto", Anime.Status.WATCHED);
        detailDroppedButton = createStatusChip("Droppato", Anime.Status.DROPPED);
        FlowPane statusButtonsBox = new FlowPane(8, 8, detailPlanButton, detailWatchingButton, detailWatchedButton, detailDroppedButton);
        statusButtonsBox.setAlignment(Pos.CENTER_LEFT);

        removeButton = new Button("Rimuovi dalla lista");
        removeButton.setStyle("-fx-background-color: rgba(239,68,68,0.16); -fx-text-fill: #fca5a5; -fx-font-weight: bold; -fx-background-radius: 12; -fx-border-radius: 12; -fx-border-color: rgba(248,113,113,0.45); -fx-cursor: hand; -fx-padding: 9 14;");
        removeButton.setOnAction(e -> removeAnimeFromLibrary());

        Button closeBtn = new Button("Chiudi");
        closeBtn.setStyle("-fx-background-color: #334155; -fx-text-fill: white; -fx-background-radius: 12; -fx-cursor: hand; -fx-padding: 9 14; -fx-font-weight: bold;");
        closeBtn.setOnAction(e -> closeAnimeDetailsOverlay());

        HBox bottomActions = new HBox(10, removeButton, closeBtn);
        bottomActions.setAlignment(Pos.CENTER_LEFT);
        VBox actionsBox = new VBox(10, addButton, detailStatusChoiceLabel, statusButtonsBox, bottomActions);
        actionsBox.setPadding(new Insets(8, 0, 0, 0));

        contentSide.getChildren().addAll(titleLabel, metaLabel, infoScroll, actionsBox);
        detailDialogBox.getChildren().addAll(coverView, contentSide);
        applyDetailPopupTheme();
        detailOverlay.getChildren().add(detailDialogBox);
    }

    private void refreshThemeDecorations() {
        if (sidebarLogo != null) {
            sidebarLogo.setTextFill(Color.web(currentAccentColor == null ? DEFAULT_ACCENT_COLOR : currentAccentColor));
        }
        applyDetailPopupTheme();
    }

    private void applyDetailPopupTheme() {
        if (detailOverlay != null) {
            detailOverlay.setStyle("-fx-background-color: rgba(3,7,18,0.84);");
        }
        if (detailDialogBox != null) {
            detailDialogBox.setStyle(
                "-fx-background-color: linear-gradient(to bottom right, rgba(15,23,42,0.98), rgba(6,10,26,0.98));" +
                "-fx-background-radius: 18;" +
                "-fx-border-radius: 18;" +
                "-fx-border-color: " + accentRgba(0.95) + ";" +
                "-fx-border-width: 1.8;" +
                "-fx-effect: dropshadow(gaussian, " + accentRgba(0.32) + ", 30, 0.24, 0, 0), dropshadow(gaussian, rgba(0,0,0,0.70), 34, 0.20, 0, 12);"
            );
        }
        if (metaLabel != null) metaLabel.setTextFill(Color.web(currentAccentColor));
        if (detailStatusChoiceLabel != null) detailStatusChoiceLabel.setTextFill(Color.web(currentAccentColor));
        if (addButton != null) {
            addButton.setStyle(
                "-fx-background-color: linear-gradient(to right, " + currentAccentColor + ", #22c55e);" +
                "-fx-text-fill: white;" +
                "-fx-font-weight: bold;" +
                "-fx-background-radius: 14;" +
                "-fx-border-radius: 14;" +
                "-fx-border-color: " + accentRgba(0.55) + ";" +
                "-fx-cursor: hand;" +
                "-fx-padding: 12 18;" +
                "-fx-font-size: 15px;" +
                "-fx-effect: dropshadow(gaussian, " + accentRgba(0.26) + ", 14, 0.18, 0, 4);"
            );
        }
        if (removeButton != null) {
            removeButton.setStyle("-fx-background-color: rgba(239,68,68,0.16); -fx-text-fill: #fca5a5; -fx-font-weight: bold; -fx-background-radius: 12; -fx-border-radius: 12; -fx-border-color: rgba(248,113,113,0.45); -fx-cursor: hand; -fx-padding: 9 14;");
        }
        updateDetailStatusButtons();
    }

    private void closeAnimeDetailsOverlay() {
        if (detailOverlay != null) {
            FadeTransition fade = new FadeTransition(Duration.millis(150), detailOverlay);
            fade.setFromValue(detailOverlay.getOpacity());
            fade.setToValue(0.0);
            fade.setOnFinished(e -> {
                detailOverlay.setVisible(false);
                detailOverlay.setOpacity(1.0);
                refreshLibraryGrid();
                updateDashboardStats();
            });
            fade.play();
        } else {
            refreshLibraryGrid();
            updateDashboardStats();
        }
    }

    private void showAnimeDetails(Anime anime) {
        if (anime == null) return;
        hideSuggestionPopupCompletely();
        hideActiveCardPopup();
        Anime local = manager.all().stream().filter(x -> x.id == anime.id).findFirst().orElse(null);
        boolean exists = local != null;

        if (exists) {
            Anime.Status savedStatus = local.status;
            copyOnlineInfo(anime, local);
            local.status = savedStatus;
            activeAnime = local;
        } else {
            anime.status = Anime.Status.TO_WATCH;
            activeAnime = anime;
        }

        if (hasMissingImportantInfo(activeAnime)) {
            enrichAnimeDetailsAsync(activeAnime.id);
        }

        applyDetailPopupTheme();
        titleLabel.setText(activeAnime.title != null ? activeAnime.title : "Sconosciuto");
        metaLabel.setText(exists ? "STATO NELLA TUA LISTA: " + activeAnime.statusToString().toUpperCase() : "NON ANCORA SALVATO NELLA TUA LISTA");
        renderActiveAnimeInfo();
        updateDetailStatusButtons();

        addButton.setVisible(!exists);
        addButton.setManaged(!exists);
        addButton.setDisable(false);
        removeButton.setVisible(exists);
        removeButton.setManaged(exists);
        removeButton.setDisable(!exists);

        setImageWithFallback(coverView, activeAnime.coverImage, 240, 360);

        detailOverlay.setVisible(true);
        FadeTransition ft = new FadeTransition(Duration.millis(200), detailOverlay);
        ft.setFromValue(0.0);
        ft.setToValue(1.0);
        ft.play();
    }


    private boolean hasMissingImportantInfo(Anime anime) {
        if (anime == null) return false;
        return anime.episodes <= 0 || anime.duration <= 0 || isEmptyInfo(anime.format) || isEmptyInfo(anime.airingStatus)
                || isEmptyInfo(anime.year) || isEmptyInfo(anime.season) || isEmptyInfo(anime.studio);
    }

    private boolean isEmptyInfo(String value) {
        return value == null || value.isBlank() || value.equalsIgnoreCase("N/D") || value.equalsIgnoreCase("UNKNOWN");
    }

    private void enrichAnimeDetailsAsync(int animeId) {
        Task<Anime> task = new Task<>() {
            @Override protected Anime call() throws Exception {
                return client.getAnimeById(animeId);
            }
        };
        task.setOnSucceeded(evt -> {
            Anime fresh = task.getValue();
            if (fresh == null || activeAnime == null || activeAnime.id != fresh.id) return;
            Anime.Status oldStatus = activeAnime.status;
            copyOnlineInfo(fresh, activeAnime);
            activeAnime.status = oldStatus;
            renderActiveAnimeInfo();
        });
        executor.submit(task);
    }

    private void copyOnlineInfo(Anime from, Anime to) {
        if (from == null || to == null) return;
        if (from.title != null && !from.title.isBlank()) to.title = from.title;
        if (from.coverImage != null && !from.coverImage.isBlank()) to.coverImage = from.coverImage;
        if (from.episodes > 0) to.episodes = from.episodes;
        if (from.duration > 0) to.duration = from.duration;
        if (from.genres != null && !from.genres.isEmpty()) to.genres = from.genres;
        if (!isEmptyInfo(from.format)) to.format = from.format;
        if (!isEmptyInfo(from.airingStatus)) to.airingStatus = from.airingStatus;
        if (!isEmptyInfo(from.year)) to.year = from.year;
        if (!isEmptyInfo(from.season)) to.season = from.season;
        if (!isEmptyInfo(from.studio)) to.studio = from.studio;
    }

    private void renderActiveAnimeInfo() {
        if (activeAnime == null) return;
        titleLabel.setText(activeAnime.title != null ? activeAnime.title : "Sconosciuto");
        extendedInfoBox.getChildren().clear();
        extendedInfoBox.getChildren().addAll(
            createInfoDetailRow("Tipo", formatValue(activeAnime.format)),
            createInfoDetailRow("Episodi", formatEpisodes(activeAnime.episodes)),
            createInfoDetailRow("Durata episodio", formatDuration(activeAnime.duration)),
            createInfoDetailRow("Stato", formatValue(activeAnime.airingStatus)),
            createInfoDetailRow("Anno", formatValue(activeAnime.year)),
            createInfoDetailRow("Stagione", formatValue(activeAnime.season)),
            createInfoDetailRow("Studio", formatValue(activeAnime.studio)),
            createInfoDetailRow("Tempo totale", String.format("%.1f ore", activeAnime.totalHours())),
            createGenresTagRow(activeAnime.genres)
        );
        setImageWithFallback(coverView, activeAnime.coverImage, 240, 360);
    }

    private HBox createInfoDetailRow(String label, String value) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(7, 14, 7, 14));
        row.setStyle("-fx-background-color: rgba(30,41,59,0.86); -fx-background-radius: 9; -fx-border-radius: 9; -fx-border-color: " + accentRgba(0.16) + "; -fx-border-width: 0 0 0 3;");
        
        Label lblKey = new Label(label);
        lblKey.setTextFill(Color.web("#94a3b8"));
        lblKey.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
        lblKey.setPrefWidth(190);
        
        Label lblVal = new Label(value);
        lblVal.setTextFill(Color.WHITE);
        lblVal.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));
        
        row.getChildren().addAll(lblKey, lblVal);
        return row;
    }

    private VBox createGenresTagRow(List<String> genres) {
        VBox box = new VBox(6);
        box.setPadding(new Insets(8, 14, 8, 14));
        box.setStyle("-fx-background-color: rgba(30,41,59,0.86); -fx-background-radius: 9; -fx-border-radius: 9; -fx-border-color: " + accentRgba(0.16) + "; -fx-border-width: 0 0 0 3;");
        
        Label lblKey = new Label("Generi");
        lblKey.setTextFill(Color.web("#94a3b8"));
        lblKey.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
        
        FlowPane flow = new FlowPane();
        flow.setHgap(6); flow.setVgap(6);
        if (genres != null && !genres.isEmpty()) {
            for (String g : genres) {
                Label tag = new Label(g);
                tag.setStyle("-fx-background-color: " + currentAccentColor + "; -fx-text-fill: white; -fx-background-radius: 7; -fx-padding: 4 10; -fx-font-size: 11px; -fx-font-weight: bold; -fx-effect: dropshadow(gaussian, " + accentRgba(0.25) + ", 8, 0.18, 0, 2);");
                flow.getChildren().add(tag);
            }
        } else {
            Label tag = new Label("Nessun genere");
            tag.setTextFill(Color.GRAY);
            flow.getChildren().add(tag);
        }
        box.getChildren().addAll(lblKey, flow);
        return box;
    }

    private void addAnimeToLibrary() {
        if (activeAnime == null) return;
        manager.add(activeAnime);
        saveLibraryData("Salvato in lista.");
        refreshAllViews();
        showAnimeDetails(activeAnime);
    }

    private void removeAnimeFromLibrary() {
        if (activeAnime == null) return;
        int removedId = activeAnime.id;
        manager.remove(removedId);
        activeAnime.status = Anime.Status.TO_WATCH;
        saveLibraryData("Rimosso dalla lista.");
        refreshAllViews();
        showAnimeDetails(activeAnime);
    }

    private Button createStatusChip(String text, Anime.Status status) {
        Button btn = new Button(text);
        btn.setMinWidth(88);
        btn.setMinHeight(28);
        btn.setStyle("-fx-background-color: rgba(30,41,59,0.82); -fx-text-fill: #dbeafe; -fx-background-radius: 11; -fx-border-radius: 11; -fx-border-color: " + accentRgba(0.22) + "; -fx-border-width: 1; -fx-padding: 5 9; -fx-font-weight: bold; -fx-font-size: 11px; -fx-cursor: hand;");
        btn.setOnAction(e -> setActiveAnimeStatus(status));
        return btn;
    }

    private void setActiveAnimeStatus(Anime.Status status) {
        if (activeAnime == null) return;
        activeAnime.status = status;
        if (manager.all().stream().anyMatch(x -> x.id == activeAnime.id)) {
            manager.updateStatus(activeAnime.id, status);
            saveLibraryData("Stato modificato.");
        } else {
            manager.add(activeAnime);
            saveLibraryData("Aggiunto alla lista.");
            addButton.setVisible(false);
            addButton.setManaged(false);
            removeButton.setVisible(true);
            removeButton.setManaged(true);
        }
        metaLabel.setText("STATO NELLA TUA LISTA: " + activeAnime.statusToString().toUpperCase());
        updateDetailStatusButtons();
        refreshAllViews();
    }

    private void updateDetailStatusButtons() {
        if (activeAnime == null) return;
        boolean exists = manager.all().stream().anyMatch(x -> x.id == activeAnime.id);
        if (!exists) {
            styleStatusChip(detailPlanButton, false, "#3b82f6");
            styleStatusChip(detailWatchingButton, false, "#ff9f43");
            styleStatusChip(detailWatchedButton, false, "#1dd1a1");
            styleStatusChip(detailDroppedButton, false, "#ff6b6b");
            return;
        }
        styleStatusChip(detailPlanButton, activeAnime.status == Anime.Status.TO_WATCH, "#3b82f6");
        styleStatusChip(detailWatchingButton, activeAnime.status == Anime.Status.WATCHING, "#ff9f43");
        styleStatusChip(detailWatchedButton, activeAnime.status == Anime.Status.WATCHED, "#1dd1a1");
        styleStatusChip(detailDroppedButton, activeAnime.status == Anime.Status.DROPPED, "#ff6b6b");
    }

    private void styleStatusChip(Button btn, boolean selected, String color) {
        if (btn == null) return;
        if (selected) {
            btn.setStyle("-fx-background-color: " + color + "; -fx-text-fill: white; -fx-background-radius: 11; -fx-border-radius: 11; -fx-border-color: rgba(255,255,255,0.35); -fx-border-width: 1; -fx-padding: 5 9; -fx-font-weight: bold; -fx-font-size: 11px; -fx-cursor: hand;");
        } else {
            btn.setStyle("-fx-background-color: rgba(30,41,59,0.82); -fx-text-fill: #dbeafe; -fx-background-radius: 11; -fx-border-radius: 11; -fx-border-color: " + accentRgba(0.22) + "; -fx-border-width: 1; -fx-padding: 5 9; -fx-font-weight: bold; -fx-font-size: 11px; -fx-cursor: hand;");
        }
    }

    private String formatValue(String value) {
        return value == null || value.isBlank() || value.equalsIgnoreCase("UNKNOWN") ? "N/D" : value;
    }

    private String formatEpisodes(int episodes) {
        return episodes <= 0 ? "??" : String.valueOf(episodes);
    }

    private String formatDuration(int duration) {
        return duration <= 0 ? "N/D" : duration + " min";
    }

    // --- CARICAMENTO DATI ---
    private void saveLibraryData(String msg) {
        try { manager.saveToDefault(); statusBar.setText(msg); }
        catch (Exception e) { statusBar.setText("Errore salvataggio."); }
    }


    private void refreshAllViews() {
        updateFilterButtonCounts();
        updateDashboardStats();
        if (libraryGrid != null) refreshLibraryGrid();
        refreshVisibleAnimeCards(mainContentStack);
        if (activeAnime != null && detailOverlay != null && detailOverlay.isVisible()) {
            boolean exists = manager.all().stream().anyMatch(x -> x.id == activeAnime.id);
            metaLabel.setText(exists ? "STATO NELLA TUA LISTA: " + activeAnime.statusToString().toUpperCase() : "NON ANCORA SALVATO NELLA TUA LISTA");
            addButton.setVisible(!exists);
            addButton.setManaged(!exists);
            addButton.setDisable(false);
            removeButton.setVisible(exists);
            removeButton.setManaged(exists);
            removeButton.setDisable(!exists);
            updateDetailStatusButtons();
        }
    }


    private void refreshVisibleAnimeCards(Node node) {
        if (node == null) return;
        Object updater = node.getProperties().get("refreshCard");
        if (updater instanceof Runnable r) r.run();
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) refreshVisibleAnimeCards(child);
        }
    }

    private void smoothHorizontalScroll(ScrollPane pane, double targetValue) {
        Timeline timeline = new Timeline(
            new KeyFrame(Duration.millis(320),
                new KeyValue(pane.hvalueProperty(), targetValue, Interpolator.EASE_BOTH)
            )
        );
        timeline.play();
    }


    private void applySavedSettings() {
        try {
            Properties props = loadAppProperties();
            currentAccentColor = props.getProperty("accent.color", DEFAULT_ACCENT_COLOR);
            currentBackgroundColor = props.getProperty("background.color", DEFAULT_BACKGROUND_COLOR);
            String mode = props.getProperty("background.mode", "color");
            if ("image".equalsIgnoreCase(mode)) {
                String path = props.getProperty("background.image", CUSTOM_BACKGROUND_FILE.toString());
                if (path != null && !path.isBlank() && Files.exists(Path.of(path))) {
                    applyImageBackground(Path.of(path), false);
                    return;
                }
            }
            applySolidBackground(Color.web(currentBackgroundColor), false);
        } catch (Exception ex) {
            applySolidBackground(Color.web(DEFAULT_BACKGROUND_COLOR), false);
        }
    }

    private void applySolidBackground(Color color) {
        applySolidBackground(color, true);
    }

    private void applySolidBackground(Color color, boolean save) {
        if (root == null) return;
        currentBackgroundColor = toHex(color);
        currentAccentColor = deriveAccentColor(color);
        root.setBackground(new Background(new BackgroundFill(color, CornerRadii.EMPTY, Insets.EMPTY)));
        if (mainContentStack != null) {
            mainContentStack.setStyle("-fx-background-color: transparent;");
        }
        refreshThemeDecorations();
        if (save) {
            saveBackgroundSettings("color", currentBackgroundColor, null);
            if (statusBar != null) statusBar.setText("Sfondo colore salvato.");
        }
    }

    private void chooseBackgroundImage() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Scegli immagine di sfondo");
        fc.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("Immagini", "*.png", "*.jpg", "*.jpeg", "*.webp"),
            new FileChooser.ExtensionFilter("Tutti i file", "*.*")
        );
        File file = fc.showOpenDialog(root.getScene().getWindow());
        if (file == null) return;
        try {
            Files.createDirectories(APP_DIR);
            Files.copy(file.toPath(), CUSTOM_BACKGROUND_FILE, StandardCopyOption.REPLACE_EXISTING);
            applyImageBackground(CUSTOM_BACKGROUND_FILE, true);
            statusBar.setText("Sfondo personalizzato salvato.");
        } catch (Exception ex) {
            statusBar.setText("Impossibile salvare l'immagine scelta.");
        }
    }

    private void applyImageBackground(Path imagePath, boolean save) {
        try {
            Image image = new Image(imagePath.toUri().toString(), 0, 0, false, true, true);
            BackgroundSize size = new BackgroundSize(100, 100, true, true, false, true);
            BackgroundImage bg = new BackgroundImage(image, BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT, BackgroundPosition.CENTER, size);
            root.setBackground(new Background(bg));
            if (mainContentStack != null) {
                mainContentStack.setStyle(
                    "-fx-background-color: rgba(2, 6, 23, 0.58);" +
                    "-fx-background-radius: 22;" +
                    "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.25), 20, 0.18, 0, 4);"
                );
            }
            refreshThemeDecorations();
            if (save) {
                saveBackgroundSettings("image", currentBackgroundColor, imagePath.toString());
            }
        } catch (Exception ex) {
            applySolidBackground(Color.web(currentBackgroundColor), false);
        }
    }

    private void saveBackgroundSettings(String mode, String colorHex, String imagePath) {
        try {
            Files.createDirectories(APP_DIR);
            Properties props = loadAppProperties();
            props.setProperty("background.mode", mode);
            props.setProperty("background.color", colorHex == null ? DEFAULT_BACKGROUND_COLOR : colorHex);
            props.setProperty("accent.color", currentAccentColor == null ? DEFAULT_ACCENT_COLOR : currentAccentColor);
            if (imagePath != null) props.setProperty("background.image", imagePath);
            try (OutputStream out = Files.newOutputStream(SETTINGS_FILE)) { props.store(out, "MyAnimeDesk settings"); }
        } catch (Exception ignored) { }
    }

    private String toHex(Color color) {
        int r = (int)Math.round(color.getRed() * 255);
        int g = (int)Math.round(color.getGreen() * 255);
        int b = (int)Math.round(color.getBlue() * 255);
        return String.format("#%02x%02x%02x", r, g, b);
    }

    private String deriveAccentColor(Color base) {
        double brightness = (base.getRed() * 0.299) + (base.getGreen() * 0.587) + (base.getBlue() * 0.114);
        Color accent = brightness < 0.35 ? base.brighter().brighter() : base;
        return toHex(accent);
    }

    private String accentRgba(double opacity) {
        try {
            Color c = Color.web(currentAccentColor == null ? DEFAULT_ACCENT_COLOR : currentAccentColor);
            int r = (int)Math.round(c.getRed() * 255);
            int g = (int)Math.round(c.getGreen() * 255);
            int b = (int)Math.round(c.getBlue() * 255);
            return "rgba(" + r + "," + g + "," + b + "," + opacity + ")";
        } catch (Exception ex) {
            return "rgba(59,130,246," + opacity + ")";
        }
    }

    private void clearLibraryWithConfirm() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle("Cancella lista");
        alert.setHeaderText("Vuoi davvero cancellare tutta la tua lista?");
        alert.setContentText("Questa azione rimuove tutti gli anime salvati localmente.");
        ButtonType cancel = new ButtonType("Annulla", ButtonBar.ButtonData.CANCEL_CLOSE);
        ButtonType confirm = new ButtonType("Cancella", ButtonBar.ButtonData.OK_DONE);
        alert.getButtonTypes().setAll(cancel, confirm);
        alert.showAndWait().ifPresent(type -> {
            if (type == confirm) {
                for (Anime anime : manager.all()) manager.remove(anime.id);
                saveLibraryData("Lista cancellata.");
                refreshAllViews();
            }
        });
    }

    private void showChangelogIfFirstRunOfVersion() {
        try {
            Properties props = loadAppProperties();
            String lastSeen = props.getProperty("lastSeenVersion", "");
            if (!APP_VERSION.equals(lastSeen)) {
                showStyledMessageDialog(
                    "Novità MyAnimeDesk",
                    "MyAnimeDesk " + APP_VERSION,
                    CURRENT_CHANGELOG,
                    "Ok, continua",
                    () -> {
                        try {
                            Properties updatedProps = loadAppProperties();
                            updatedProps.setProperty("lastSeenVersion", APP_VERSION);
                            saveAppProperties(updatedProps);
                        } catch (Exception ignored) { }
                    }
                );
            }
        } catch (Exception ignored) { }
    }

    private void checkForUpdates(boolean manual) {
        Task<UpdateInfo> task = new Task<>() {
            @Override protected UpdateInfo call() throws Exception {
                return fetchLatestReleaseInfo();
            }
        };
        task.setOnSucceeded(e -> {
            UpdateInfo info = task.getValue();
            if (info != null && info.version != null && isNewerVersion(info.version, APP_VERSION)) {
                String message = "Versione installata: " + APP_VERSION + "\n" +
                                 "Nuova versione: " + cleanVersion(info.version) + "\n\n" +
                                 "Vuoi aprire GitHub Releases per scaricare l'aggiornamento?";
                showStyledConfirmDialog(
                    "Aggiornamenti",
                    "È disponibile MyAnimeDesk " + cleanVersion(info.version),
                    message,
                    "Apri GitHub",
                    () -> openUrl(info.url != null ? info.url : RELEASES_URL),
                    "Più tardi",
                    null
                );
            } else if (manual) {
                showStyledMessageDialog(
                    "Aggiornamenti",
                    "MyAnimeDesk è aggiornato",
                    "Stai usando la versione più recente disponibile.\n\nVersione attuale: " + APP_VERSION,
                    "Ok",
                    null
                );
            }
        });
        task.setOnFailed(e -> {
            if (manual) {
                showStyledConfirmDialog(
                    "Aggiornamenti",
                    "Controllo automatico non riuscito",
                    "Non sono riuscito a leggere automaticamente l'ultima release.\nPuò succedere se il repository è privato, GitHub non risponde o la connessione è instabile.\n\nPuoi comunque aprire la pagina Releases e controllare manualmente.",
                    "Apri GitHub",
                    () -> openUrl(RELEASES_URL),
                    "Chiudi",
                    null
                );
            }
        });
        executor.submit(task);
    }

    private UpdateInfo fetchLatestReleaseInfo() throws Exception {
        HttpClient http = HttpClient.newHttpClient();

        // IMPORTANTE:
        // /releases/latest di GitHub ignora le pre-release.
        // Siccome le release di MyAnimeDesk possono essere segnate come pre-release,
        // usiamo /releases e prendiamo la prima release pubblicata disponibile.
        try {
            HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(RELEASES_API))
                .timeout(java.time.Duration.ofSeconds(12))
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "MyAnimeDesk/" + APP_VERSION)
                .GET()
                .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                String body = response.body();
                String tag = extractJsonString(body, "tag_name");
                String url = extractJsonString(body, "html_url");

                if (tag != null && !tag.isBlank()) {
                    if (url == null || url.isBlank()) {
                        url = RELEASES_URL + "/tag/" + tag;
                    }
                    return new UpdateInfo(tag, url);
                }
            }
        } catch (Exception ignored) { }

        // Fallback: se l'API non risponde, provo a leggere direttamente la pagina Releases pubblica.
        HttpRequest pageRequest = HttpRequest.newBuilder()
            .uri(URI.create(RELEASES_URL))
            .timeout(java.time.Duration.ofSeconds(12))
            .header("User-Agent", "MyAnimeDesk/" + APP_VERSION)
            .GET()
            .build();

        HttpResponse<String> pageResponse = http.send(pageRequest, HttpResponse.BodyHandlers.ofString());
        if (pageResponse.statusCode() != 200) {
            throw new IOException("GitHub releases page " + pageResponse.statusCode());
        }

        String page = pageResponse.body();
        String marker = "/DasCrishpp/MyAnimeDesk/releases/tag/";
        int idx = page.indexOf(marker);
        if (idx < 0) {
            throw new IOException("Nessun tag release trovato nella pagina Releases");
        }

        int startTag = idx + marker.length();
        int endTag = startTag;
        while (endTag < page.length()) {
            char ch = page.charAt(endTag);
            if (ch == '"' || ch == '\'' || ch == '<' || ch == '?' || Character.isWhitespace(ch)) {
                break;
            }
            endTag++;
        }

        String tag = page.substring(startTag, endTag)
            .replace("/", "")
            .replace("\\", "")
            .trim();

        if (tag.isBlank()) {
            throw new IOException("Tag release vuoto");
        }

        return new UpdateInfo(tag, RELEASES_URL + "/tag/" + tag);
    }

    private void showStyledMessageDialog(String title, String subtitle, String message, String buttonText, Runnable onClose) {
        showStyledDialog(title, subtitle, message, buttonText, onClose, null, null);
    }

    private void showStyledConfirmDialog(String title, String subtitle, String message,
                                         String primaryText, Runnable primaryAction,
                                         String secondaryText, Runnable secondaryAction) {
        showStyledDialog(title, subtitle, message, primaryText, primaryAction, secondaryText, secondaryAction);
    }

    private void showStyledDialog(String title, String subtitle, String message,
                                  String primaryText, Runnable primaryAction,
                                  String secondaryText, Runnable secondaryAction) {
        // Il popup oscura tutta l'area principale fino ai bordi, lasciando fuori solo la sidebar.
        StackPane overlayHost = appShell;
        if (overlayHost == null) {
            Alert fallback = new Alert(secondaryText == null ? Alert.AlertType.INFORMATION : Alert.AlertType.CONFIRMATION);
            fallback.setTitle(title);
            fallback.setHeaderText(subtitle);
            fallback.setContentText(message);
            fallback.showAndWait();
            if (primaryAction != null) primaryAction.run();
            return;
        }

        StackPane overlay = new StackPane();
        overlay.setPickOnBounds(true);
        overlay.setAlignment(Pos.CENTER);
        overlay.setPadding(new Insets(30));
        overlay.setMaxSize(Double.MAX_VALUE, Double.MAX_VALUE);
        overlay.setMinSize(0, 0);
        configureMainAreaModalOverlay(overlay);
        overlay.setStyle("-fx-background-color: rgba(1, 3, 12, 0.68);");

        VBox card = new VBox(18);
        card.setPadding(new Insets(28, 34, 26, 34));
        card.setMaxWidth(820);
        card.setPrefWidth(820);
        card.setMinWidth(620);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setStyle(
            "-fx-background-color: linear-gradient(to bottom right, rgba(15,23,42,0.96), rgba(6,10,26,0.97));" +
            "-fx-background-radius: 28;" +
            "-fx-border-radius: 28;" +
            "-fx-border-width: 2.2;" +
            "-fx-border-color: " + accentRgba(0.92) + ";" +
            "-fx-effect: dropshadow(gaussian, " + accentRgba(0.45) + ", 34, 0.28, 0, 10), dropshadow(gaussian, rgba(0,0,0,0.65), 28, 0.18, 0, 12);"
        );

        Label badge = new Label(title);
        badge.setTextFill(Color.WHITE);
        badge.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 12));
        badge.setPadding(new Insets(8, 16, 8, 16));
        badge.setStyle(
            "-fx-background-color: " + accentRgba(0.34) + ";" +
            "-fx-background-radius: 999;" +
            "-fx-border-color: " + accentRgba(0.95) + ";" +
            "-fx-border-radius: 999;" +
            "-fx-border-width: 1.4;" +
            "-fx-effect: dropshadow(gaussian, " + accentRgba(0.35) + ", 10, 0.20, 0, 2);"
        );

        Label header = new Label(subtitle);
        header.setTextFill(Color.WHITE);
        header.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 28));
        header.setWrapText(true);

        TextArea body = new TextArea(message == null ? "" : message);
        body.setEditable(false);
        body.setWrapText(true);
        body.setFocusTraversable(false);
        body.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 15));
        body.setStyle(
            "-fx-control-inner-background: transparent;" +
            "-fx-background-color: transparent;" +
            "-fx-text-fill: #eef5ff;" +
            "-fx-highlight-fill: rgba(59,130,246,0.35);" +
            "-fx-highlight-text-fill: white;" +
            "-fx-border-color: transparent;" +
            "-fx-padding: 0;"
        );

        ScrollPane bodyScroll = new ScrollPane(body);
        bodyScroll.setFitToWidth(true);
        bodyScroll.setFitToHeight(true);
        bodyScroll.setPrefHeight(430);
        bodyScroll.setMinHeight(260);
        bodyScroll.setMaxHeight(520);
        bodyScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        bodyScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        bodyScroll.setStyle(
            "-fx-background: transparent;" +
            "-fx-background-color: transparent;" +
            "-fx-control-inner-background: transparent;" +
            "-fx-padding: 0;"
        );
        bodyScroll.getStyleClass().add("modern-scroll");
        VBox.setVgrow(bodyScroll, Priority.ALWAYS);

        HBox actions = new HBox(12);
        actions.setAlignment(Pos.CENTER_RIGHT);
        actions.setPadding(new Insets(8, 0, 0, 0));

        Button primary = createDialogButton(primaryText == null ? "Ok" : primaryText, true);
        Button secondary = null;

        Runnable closeDialog = () -> {
            FadeTransition fadeOut = new FadeTransition(Duration.millis(120), overlay);
            fadeOut.setToValue(0);
            fadeOut.setOnFinished(ev -> overlayHost.getChildren().remove(overlay));
            fadeOut.play();
        };

        primary.setOnAction(e -> {
            closeDialog.run();
            if (primaryAction != null) primaryAction.run();
        });

        if (secondaryText != null) {
            secondary = createDialogButton(secondaryText, false);
            secondary.setOnAction(e -> {
                closeDialog.run();
                if (secondaryAction != null) secondaryAction.run();
            });
            actions.getChildren().addAll(secondary, primary);
        } else {
            actions.getChildren().add(primary);
        }

        card.getChildren().addAll(badge, header, bodyScroll, actions);
        overlay.getChildren().add(card);
        overlayHost.getChildren().add(overlay);

        Platform.runLater(() -> {
            double availableHeight = overlayHost.getHeight() > 0 ? overlayHost.getHeight() : 760;
            double dialogHeight = Math.max(520, availableHeight - 110);
            card.setPrefHeight(Math.min(720, dialogHeight));
        });

        overlay.setOpacity(0);
        card.setScaleX(0.96);
        card.setScaleY(0.96);

        FadeTransition fadeIn = new FadeTransition(Duration.millis(150), overlay);
        fadeIn.setToValue(1);

        ScaleTransition scaleIn = new ScaleTransition(Duration.millis(180), card);
        scaleIn.setToX(1);
        scaleIn.setToY(1);
        scaleIn.setInterpolator(Interpolator.EASE_OUT);

        fadeIn.play();
        scaleIn.play();
    }

    private Button createDialogButton(String text, boolean primary) {
        Button btn = new Button(text);
        btn.setMinHeight(40);
        btn.setPadding(new Insets(10, 18, 10, 18));
        btn.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
        btn.setCursor(javafx.scene.Cursor.HAND);
        if (primary) {
            btn.setTextFill(Color.WHITE);
            btn.setStyle(
                "-fx-background-color: linear-gradient(to right, " + currentAccentColor + ", #6366f1);" +
                "-fx-background-radius: 16;" +
                "-fx-border-radius: 16;" +
                "-fx-border-color: " + accentRgba(0.62) + ";" +
                "-fx-border-width: 1;" +
                "-fx-effect: dropshadow(gaussian, " + accentRgba(0.34) + ", 14, 0.20, 0, 4);"
            );
        } else {
            btn.setTextFill(Color.web("#cbd5e1"));
            btn.setStyle(
                "-fx-background-color: rgba(15,23,42,0.92);" +
                "-fx-background-radius: 16;" +
                "-fx-border-radius: 16;" +
                "-fx-border-color: rgba(148,163,184,0.22);" +
                "-fx-border-width: 1;"
            );
        }
        return btn;
    }

    private Properties loadAppProperties() throws IOException {
        Properties props = new Properties();
        if (Files.exists(SETTINGS_FILE)) {
            try (InputStream in = Files.newInputStream(SETTINGS_FILE)) { props.load(in); }
        }
        return props;
    }

    private void saveAppProperties(Properties props) throws IOException {
        if (!Files.exists(APP_DIR)) Files.createDirectories(APP_DIR);
        try (OutputStream out = Files.newOutputStream(SETTINGS_FILE)) { props.store(out, "MyAnimeDesk settings"); }
    }

    private void openUrl(String url) {
        try {
            getHostServices().showDocument(url);
        } catch (Exception ex) {
            try {
                if (Desktop.isDesktopSupported()) Desktop.getDesktop().browse(URI.create(url));
            } catch (Exception ignored) { }
        }
    }

    private String extractJsonString(String json, String key) {
        if (json == null || key == null) return null;
        String pattern = "\"" + key + "\"";
        int keyIndex = json.indexOf(pattern);
        if (keyIndex < 0) return null;
        int colon = json.indexOf(':', keyIndex + pattern.length());
        if (colon < 0) return null;
        int start = json.indexOf('"', colon + 1);
        if (start < 0) return null;
        StringBuilder sb = new StringBuilder();
        boolean escaped = false;
        for (int i = start + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                if (c == 'n') sb.append('\n');
                else if (c == 't') sb.append('\t');
                else sb.append(c);
                escaped = false;
            } else if (c == '\\') escaped = true;
            else if (c == '"') break;
            else sb.append(c);
        }
        return sb.toString();
    }

    private boolean isNewerVersion(String remote, String local) {
        int[] r = parseVersion(remote);
        int[] l = parseVersion(local);
        for (int i = 0; i < Math.max(r.length, l.length); i++) {
            int rv = i < r.length ? r[i] : 0;
            int lv = i < l.length ? l[i] : 0;
            if (rv > lv) return true;
            if (rv < lv) return false;
        }
        return false;
    }

    private int[] parseVersion(String version) {
        String clean = cleanVersion(version);
        String[] parts = clean.split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { out[i] = Integer.parseInt(parts[i].replaceAll("[^0-9]", "")); }
            catch (Exception e) { out[i] = 0; }
        }
        return out;
    }

    private String cleanVersion(String version) {
        if (version == null || version.isBlank()) return "0.0.0";
        return version.trim().replaceFirst("^[vV]", "");
    }

    private static class UpdateInfo {
        final String version;
        final String url;
        UpdateInfo(String version, String url) {
            this.version = version;
            this.url = url;
        }
    }

    private HBox createStatusBar() {
        Button refreshButton = new Button("Aggiorna GUI");
        refreshButton.setStyle("-fx-background-color: rgba(37,99,235,0.20); -fx-text-fill: #bfdbfe; -fx-font-weight: bold; -fx-background-radius: 12; -fx-border-radius: 12; -fx-border-color: rgba(147,197,253,0.26); -fx-padding: 7 13; -fx-cursor: hand;");
        refreshButton.setOnAction(e -> {
            refreshAllViews();
            statusBar.setText("GUI aggiornata.");
        });

        statusBar = new Label("Pronto.");
        statusBar.setTextFill(Color.web("#94a3b8"));

        Label copyrightLabel = new Label("© 2026 MyAnimeDesk");
        copyrightLabel.setTextFill(Color.web("#64748b"));
        copyrightLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(12, refreshButton, statusBar, spacer, copyrightLabel);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(10, 20, 10, 20));
        footer.setStyle("-fx-background-color: #040814; -fx-border-color: rgba(255,255,255,0.04); -fx-border-width: 1 0 0 0;");
        return footer;
    }


    @Override
    public void stop() {
        shutdownBackgroundWork();
    }

    private void shutdownBackgroundWork() {
        shuttingDown = true;
        if (searchDebounceTimer != null) {
            searchDebounceTimer.cancel();
            searchDebounceTimer = null;
        }
        if (suggestionPopup != null) suggestionPopup.hide();
        if (activeCardPopup != null) activeCardPopup.hide();

        executor.shutdownNow();
        try {
            if (!executor.awaitTermination(1500, TimeUnit.MILLISECONDS)) {
                System.err.println("MyAnimeDesk: alcuni task in background sono stati interrotti alla chiusura.");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
 //cristian
}
