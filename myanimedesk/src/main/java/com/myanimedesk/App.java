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
import javafx.collections.FXCollections;
import javafx.concurrent.Task; // <-- IMPORT RISOLTO
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.awt.Desktop;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class App extends Application {
    private static final String APP_VERSION = "0.3.8";
    private static final String RELEASES_URL = "https://github.com/DasCrishpp/MyAnimeDesk/releases";
    private static final String LATEST_RELEASE_API = "https://api.github.com/repos/DasCrishpp/MyAnimeDesk/releases/latest";
    private static final Path APP_DIR = Path.of(System.getProperty("user.home"), ".myanimedesk");
    private static final Path SETTINGS_FILE = APP_DIR.resolve("app.properties");
    private static final String CURRENT_CHANGELOG = """
- Aggiunto il controllo aggiornamenti all'avvio e nelle impostazioni
- Aggiunta la versione attuale dell'app nelle impostazioni
- Aggiunto il popup del changelog al primo avvio dopo l'aggiornamento
- Aggiunto il supporto per uno sfondo personalizzato
- Aggiunta l'opzione per ripristinare lo sfondo predefinito
- Migliorato il design della sidebar con pulsanti arrotondati
- Migliorata la disposizione e la leggibilità delle card degli anime
- Aggiunte animazioni più fluide nell'interfaccia
- Migliorata la navigazione orizzontale nelle sezioni Scopri
- Aggiunte categorie in Scopri: Popolari, Romance, Azione, Isekai, Drama, Slice of Life, Shonen, Mystery, Horror e Comedy
- Aggiunto il supporto a Vedi tutto e Visualizza altri nelle categorie Scopri
- Migliorati i suggerimenti di ricerca con copertina e informazioni dell'anime
- Risolto il problema della tendina di ricerca che rimaneva aperta dopo la selezione di un anime
- Aggiunte più informazioni sugli anime: tipo, episodi, durata, stato, anno, stagione e studio
- Migliorata la schermata dei dettagli anime
- Migliorati i pulsanti dello stato di visione nei dettagli anime
- Migliorata la visibilità del pulsante Aggiungi alla lista
- Corretto il calcolo del tempo totale di visione: ora conta solo gli anime completati
- Risolto il problema della rimozione degli anime dalla lista
- Risolto il problema dello stato degli anime che non si aggiornava correttamente dopo le modifiche
- Aggiunto un pulsante per aggiornare manualmente la GUI
- Aggiunta l'opzione per cancellare tutta la lista con conferma
- Migliorato lo stile della barra di scorrimento
- Risolti problemi di sovrapposizione dei popup delle card
- Migliorata la stabilità della sezione Scopri/Ricerca
- Rimosso il filtro Hentai/18+ a causa di vari problemi
""";

    private final AniListClient client = new AniListClient();
    private final AnimeListManager manager = new AnimeListManager();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private BorderPane root;
    private StackPane mainContentStack;
    private VBox dashboardPane, libraryPane, searchPane, settingsPane;
    
    private TilePane libraryGrid, searchGrid;
    private VBox discoverContent;
    private ScrollPane discoverScrollPane;
    private Popup suggestionPopup;
    private ListView<Anime> suggestionList;
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
    private ImageView coverView;
    private Label titleLabel, metaLabel;
    private VBox extendedInfoBox; // Sostituito TextArea con VBox strutturato
    private Button addButton, removeButton;
    private Button detailPlanButton, detailWatchingButton, detailWatchedButton, detailDroppedButton;
    
    private Label statusBar;
    private Anime activeAnime;
    private String currentLibraryFilter = "TUTTI";
    private Button activeMenuButton = null;


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
        applySolidBackground(Color.web("#0a1128"));
        applySavedSettings();

        root.setLeft(createSidebar());

        mainContentStack = new StackPane();
        mainContentStack.setPadding(new Insets(24));

        initDashboardPane();
        initLibraryPane();
        initSearchPane();
        initSettingsPane();
        initDetailOverlay(); 

        mainContentStack.getChildren().addAll(dashboardPane, libraryPane, searchPane, settingsPane, detailOverlay);
        root.setCenter(mainContentStack);
        root.setBottom(createStatusBar());

        Scene scene = new Scene(root, 1280, 820);
        installModernScrollBarStyle(scene);
        stage.setScene(scene);
        stage.show();

        loadLibraryData();
        
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
    private void playHoverSound() { }

    private void playClickSound() { }

    // --- SIDEBAR ---
    private VBox createSidebar() {
        VBox sidebar = new VBox(12);
        sidebar.setPadding(new Insets(30, 15, 20, 15));
        sidebar.setPrefWidth(230);
        sidebar.setStyle(
            "-fx-background-color: linear-gradient(to bottom, #070d1c, #020617);" +
            "-fx-background-radius: 0 26 26 0;" +
            "-fx-border-radius: 0 26 26 0;" +
            "-fx-border-color: transparent rgba(255,255,255,0.08) transparent transparent;" +
            "-fx-border-width: 0 1 0 0;" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 22, 0.18, 5, 0);"
        );

        Label logo = new Label("MyAnimeDesk"); 
        logo.setTextFill(Color.web("#4d7cff"));
        logo.setFont(Font.font("Segoe UI", FontWeight.BOLD, 24));
        logo.setPadding(new Insets(0, 0, 20, 10));

        Button btnDash = createSidebarButton("Dashboard");
        Button btnLib = createSidebarButton("La Mia Lista");
        Button btnSearch = createSidebarButton("Scopri / Cerca");
        Button btnSettings = createSidebarButton("Impostazioni");

        btnDash.setOnAction(e -> { playClickSound(); selectMenuButton(btnDash); showView(dashboardPane); updateDashboardStats(); });
        btnLib.setOnAction(e -> { playClickSound(); selectMenuButton(btnLib); showView(libraryPane); refreshLibraryGrid(); });
        btnSearch.setOnAction(e -> { playClickSound(); selectMenuButton(btnSearch); showView(searchPane); });
        btnSettings.setOnAction(e -> { playClickSound(); selectMenuButton(btnSettings); showView(settingsPane); });

        sidebar.getChildren().addAll(logo, btnDash, btnLib, btnSearch, btnSettings);
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
        activeMenuButton.setStyle("-fx-background-color: linear-gradient(to right, #3b82f6, #6366f1); -fx-text-fill: white; -fx-background-radius: 18; -fx-border-radius: 18; -fx-border-color: rgba(147,197,253,0.85); -fx-border-width: 1; -fx-effect: dropshadow(gaussian, rgba(59,130,246,0.35), 16, 0.25, 0, 4); -fx-cursor: hand;");
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

        libraryPane.getChildren().addAll(title, filtersRow, scrollPane);
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

        if (sourceList.isEmpty()) {
            Label emptyLbl = new Label("Nessun anime trovato in questa categoria.");
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
        floatingBar.setAlignment(Pos.TOP_LEFT);
        floatingBar.setPadding(new Insets(10, 0, 0, 10));
        // IMPORTANTE: in uno StackPane un HBox può allargarsi e coprire tutta la sezione.
        // Se prende gli eventi del mouse, Scopri sembra “freezata”: non scorri e non clicchi le card.
        // Così il contenitore resta grande solo quanto i pulsanti e non blocca la UI sotto.
        floatingBar.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        floatingBar.setPickOnBounds(false);
        floatingBar.setMouseTransparent(false);

        StackPane discoverWrapper = new StackPane(discoverScrollPane, floatingBar);
        discoverWrapper.setPickOnBounds(false);
        StackPane.setAlignment(floatingBar, Pos.TOP_LEFT);
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
                if (anime.coverImage != null && !anime.coverImage.isBlank()) {
                    miniCover.setImage(new Image(anime.coverImage, 44, 62, false, true, true));
                }
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

        box.getChildren().addAll(suggestionList, showAll);
        suggestionPopup.getContent().add(box);
    }

    private void hideSuggestionPopupCompletely() {
        if (searchDebounceTimer != null) {
            searchDebounceTimer.cancel();
            searchDebounceTimer = null;
        }
        if (suggestionPopup != null) suggestionPopup.hide();
        if (suggestionList != null) suggestionList.getItems().clear();
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
        discoverContent.getChildren().addAll(
            createAnimeRowSection("Popolari", "POPULAR", null),
            createAnimeRowSection("Nuove uscite", "RECENT", null),
            createAnimeRowSection("Romance", "GENRE", "Romance"),
            createAnimeRowSection("Azione", "GENRE", "Action"),
            createAnimeRowSection("Comedy", "GENRE", "Comedy"),
            createAnimeRowSection("Mystery", "GENRE", "Mystery"),
            createAnimeRowSection("Horror", "GENRE", "Horror"),
            createAnimeRowSection("Isekai", "TAG", "Isekai"),
            createAnimeRowSection("Ecchi", "TAG", "Ecchi"),
            createAnimeRowSection("Drama", "GENRE", "Drama"),
            createAnimeRowSection("Slice of Life", "GENRE", "Slice of Life"),
            createAnimeRowSection("Shōnen", "TAG", "Shounen")
        );
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

        Task<List<Anime>> task = new Task<>() {
            @Override protected List<Anime> call() throws Exception {
                return client.browse(mode, genre, 1, 30);
            }
        };
        task.setOnSucceeded(evt -> {
            row.getChildren().clear();
            for (Anime anime : task.getValue()) row.getChildren().add(createAnimeGridCard(anime));
            if (task.getValue().isEmpty()) {
                Label empty = new Label("Nessun risultato.");
                empty.setTextFill(Color.web("#94a3b8"));
                row.getChildren().add(empty);
            }
        });
        task.setOnFailed(evt -> {
            row.getChildren().clear();
            Label err = new Label("Errore caricamento.");
            err.setTextFill(Color.web("#fca5a5"));
            row.getChildren().add(err);
        });
        executor.submit(task);

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
        Button back = new Button("← Indietro");
        back.setStyle("-fx-background-color: #1e293b; -fx-text-fill: white; -fx-background-radius: 12; -fx-padding: 8 14; -fx-cursor: hand;");
        back.setOnAction(e -> loadDiscoverHome());
        Label lbl = new Label(title);
        lbl.setTextFill(Color.WHITE);
        lbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 22));
        Button topButton = createBackToTopButton();
        topButton.setVisible(false);
        topButton.setManaged(false);
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        top.getChildren().addAll(back, lbl, topSpacer, topButton);

        TilePane grid = new TilePane();
        grid.setHgap(18);
        grid.setVgap(20);
        grid.setPrefColumns(5);

        Button loadMore = new Button("Visualizza altri");
        loadMore.setMaxWidth(Double.MAX_VALUE);
        loadMore.setStyle("-fx-background-color: linear-gradient(to right, #2563eb, #6366f1); -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 14; -fx-padding: 12 20; -fx-cursor: hand; -fx-font-size: 14px;");
        loadMore.setUserData(topButton);

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
                return client.browse(mode, genre, page, amount);
            }
        };
        task.setOnSucceeded(evt -> {
            List<Anime> loaded = task.getValue();
            for (Anime anime : loaded) grid.getChildren().add(createAnimeGridCard(anime));
            if (loadMore.getUserData() instanceof Button topBtn) {
                boolean showTop = grid.getChildren().size() >= 29;
                topBtn.setVisible(showTop);
                topBtn.setManaged(showTop);
                setFloatingDiscoverControls(true, showTop);
            }
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

            suggestionList.getItems().setAll(task.getValue());
            if (!task.getValue().isEmpty() && searchField.getScene() != null) {
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
        Button back = new Button("← Scopri");
        back.setStyle("-fx-background-color: #1e293b; -fx-text-fill: white; -fx-background-radius: 12; -fx-padding: 8 14; -fx-cursor: hand;");
        back.setOnAction(e -> loadDiscoverHome());
        Label lbl = new Label(title);
        lbl.setTextFill(Color.WHITE);
        lbl.setFont(Font.font("Segoe UI", FontWeight.BOLD, 22));
        Button topButton = createBackToTopButton();
        boolean showTopButton = results != null && results.size() >= 29;
        topButton.setVisible(showTopButton);
        topButton.setManaged(showTopButton);
        Region topSpacer = new Region();
        HBox.setHgrow(topSpacer, Priority.ALWAYS);
        top.getChildren().addAll(back, lbl, topSpacer, topButton);

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

    private Button createBackToTopButton() {
        Button btn = new Button("↑ Torna su");
        btn.setStyle("-fx-background-color: rgba(15,23,42,0.72); -fx-text-fill: #dbeafe; -fx-font-weight: bold; -fx-background-radius: 999; -fx-border-radius: 999; -fx-border-color: rgba(147,197,253,0.28); -fx-padding: 7 13; -fx-cursor: hand;");
        btn.setOnAction(e -> scrollDiscoverToTop());
        return btn;
    }

    private void scrollDiscoverToTop() {
        if (discoverScrollPane != null) Platform.runLater(() -> discoverScrollPane.setVvalue(0));
    }

    // --- 4. IMPOSTAZIONI ---
    private void initSettingsPane() {
        settingsPane = new VBox(24);
        settingsPane.setPadding(new Insets(10));

        Label title = new Label("Impostazioni Generali");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 26));

        VBox sectionUpdate = new VBox(10);
        Label lblUpdate = new Label("Aggiornamenti");
        lblUpdate.setTextFill(Color.web("#cbd5e1"));
        lblUpdate.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));

        Label versionLabel = new Label("Versione attuale: " + APP_VERSION);
        versionLabel.setTextFill(Color.web("#94a3b8"));
        versionLabel.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));

        Button btnCheckUpdates = createSettingsButton("Controlla aggiornamenti");
        btnCheckUpdates.setOnAction(e -> checkForUpdates(true));
        sectionUpdate.getChildren().addAll(lblUpdate, versionLabel, btnCheckUpdates);

        VBox sectionTheme = new VBox(10);
        Label lblTheme = new Label("Aspetto e sfondo");
        lblTheme.setTextFill(Color.web("#cbd5e1"));
        lblTheme.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));

        ColorPicker colorPicker = new ColorPicker(Color.web("#0a1128"));
        colorPicker.setStyle("-fx-background-color: #1e293b;");
        colorPicker.setOnAction(e -> applySolidBackground(colorPicker.getValue()));

        Button btnChooseBg = createSettingsButton("Scegli immagine come sfondo");
        btnChooseBg.setOnAction(e -> chooseBackgroundImage());

        Button btnResetBg = createSettingsButton("Ripristina sfondo predefinito");
        btnResetBg.setOnAction(e -> applySolidBackground(Color.web("#0a1128")));

        HBox bgActions = new HBox(12, colorPicker, btnChooseBg, btnResetBg);
        bgActions.setAlignment(Pos.CENTER_LEFT);

        sectionTheme.getChildren().addAll(lblTheme, bgActions);

        VBox sectionBackup = new VBox(12);
        Label lblBackup = new Label("Backup e dati");
        lblBackup.setTextFill(Color.web("#cbd5e1"));
        lblBackup.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));

        Button btnExport = createSettingsButton("Esporta Lista");
        btnExport.setOnAction(e -> exportLibraryWithDialog());

        Button btnImport = createSettingsButton("Importa Lista");
        btnImport.setOnAction(e -> importLibraryWithDialog());

        Button btnClear = createSettingsButtonDanger("Cancella tutta la lista");
        btnClear.setOnAction(e -> clearLibraryWithConfirm());

        sectionBackup.getChildren().addAll(lblBackup, new HBox(14, btnExport, btnImport, btnClear));
        settingsPane.getChildren().addAll(title, new Separator(), sectionUpdate, new Separator(), sectionTheme, new Separator(), sectionBackup);
    }

    private Button createSettingsButton(String text) {
        Button btn = new Button(text);
        btn.setStyle("-fx-background-color: #1e293b; -fx-text-fill: white; -fx-background-radius: 12; -fx-padding: 9 16; -fx-cursor: hand; -fx-font-weight: bold;");
        return btn;
    }

    private Button createSettingsButtonDanger(String text) {
        Button btn = new Button(text);
        btn.setStyle("-fx-background-color: rgba(239,68,68,0.15); -fx-text-fill: #fecaca; -fx-background-radius: 12; -fx-border-radius: 12; -fx-border-color: rgba(248,113,113,0.35); -fx-padding: 9 16; -fx-cursor: hand; -fx-font-weight: bold;");
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
        if (anime.coverImage != null && !anime.coverImage.isBlank()) poster.setImage(new Image(anime.coverImage, 148, 210, false, true, true));

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
        detailOverlay.setStyle("-fx-background-color: rgba(3, 7, 18, 0.88);");
        detailOverlay.setVisible(false);

        HBox dialogBox = new HBox(28);
        dialogBox.setPadding(new Insets(24));
        dialogBox.setMaxSize(840, 520);
        dialogBox.setStyle("-fx-background-color: #0f172a; -fx-background-radius: 16; -fx-border-radius: 16; -fx-border-color: #3b82f6; -fx-border-width: 1.5;");

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

        Label statusChoiceLabel = new Label("Imposta stato di visione");
        statusChoiceLabel.setTextFill(Color.web("#cbd5e1"));
        statusChoiceLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));

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
        closeBtn.setOnAction(e -> {
            detailOverlay.setVisible(false);
            refreshLibraryGrid();
            updateDashboardStats();
        });

        HBox bottomActions = new HBox(10, removeButton, closeBtn);
        bottomActions.setAlignment(Pos.CENTER_LEFT);
        VBox actionsBox = new VBox(10, addButton, statusChoiceLabel, statusButtonsBox, bottomActions);
        actionsBox.setPadding(new Insets(8, 0, 0, 0));

        contentSide.getChildren().addAll(titleLabel, metaLabel, infoScroll, actionsBox);
        dialogBox.getChildren().addAll(coverView, contentSide);
        detailOverlay.getChildren().add(dialogBox);
    }

    private void showAnimeDetails(Anime anime) {
        if (anime == null) return;
        hideSuggestionPopupCompletely();
        hideActiveCardPopup();
        Anime local = manager.all().stream().filter(x -> x.id == anime.id).findFirst().orElse(null);
        boolean exists = (local != null);
        activeAnime = exists ? local : anime;
        if (hasMissingImportantInfo(activeAnime)) {
            enrichAnimeDetailsAsync(activeAnime.id);
        }

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

        if (activeAnime.coverImage != null && !activeAnime.coverImage.isBlank()) {
            coverView.setImage(new Image(activeAnime.coverImage, 240, 360, true, true, true));
        } else {
            coverView.setImage(null);
        }

        detailOverlay.setVisible(true);
        FadeTransition ft = new FadeTransition(Duration.millis(200), detailOverlay);
        ft.setFromValue(0.0); ft.setToValue(1.0); ft.play();
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
        to.title = from.title;
        to.coverImage = from.coverImage;
        to.episodes = from.episodes;
        to.duration = from.duration;
        to.genres = from.genres;
        to.format = from.format;
        to.airingStatus = from.airingStatus;
        to.year = from.year;
        to.season = from.season;
        to.studio = from.studio;
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
        if (activeAnime.coverImage != null && !activeAnime.coverImage.isBlank()) {
            coverView.setImage(new Image(activeAnime.coverImage, 240, 360, true, true, true));
        }
    }

    private HBox createInfoDetailRow(String label, String value) {
        HBox row = new HBox();
        row.setAlignment(Pos.CENTER_LEFT);
        row.setPadding(new Insets(7, 14, 7, 14));
        row.setStyle("-fx-background-color: #1e293b; -fx-background-radius: 8;");
        
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
        box.setStyle("-fx-background-color: #1e293b; -fx-background-radius: 8;");
        
        Label lblKey = new Label("Generi");
        lblKey.setTextFill(Color.web("#94a3b8"));
        lblKey.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));
        
        FlowPane flow = new FlowPane();
        flow.setHgap(6); flow.setVgap(6);
        if (genres != null && !genres.isEmpty()) {
            for (String g : genres) {
                Label tag = new Label(g);
                tag.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: white; -fx-background-radius: 5; -fx-padding: 3 9; -fx-font-size: 11px; -fx-font-weight: bold;");
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
        btn.setStyle("-fx-background-color: #1e293b; -fx-text-fill: #cbd5e1; -fx-background-radius: 11; -fx-border-radius: 11; -fx-border-color: #334155; -fx-border-width: 1; -fx-padding: 5 9; -fx-font-weight: bold; -fx-font-size: 11px; -fx-cursor: hand;");
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
            btn.setStyle("-fx-background-color: #1e293b; -fx-text-fill: #cbd5e1; -fx-background-radius: 11; -fx-border-radius: 11; -fx-border-color: #334155; -fx-border-width: 1; -fx-padding: 5 9; -fx-font-weight: bold; -fx-font-size: 11px; -fx-cursor: hand;");
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
    private void loadLibraryData() {
        try { manager.loadFromDefault(); statusBar.setText("Libreria caricata."); }
        catch (Exception e) { statusBar.setText("Database pronto."); }
        updateFilterButtonCounts();
        updateDashboardStats();
        if (libraryGrid != null) refreshLibraryGrid();
    }

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
        // Per ora non applico filtri sui contenuti: lascio AniList libero di restituire i risultati.
    }

    private void applySolidBackground(Color color) {
        if (root == null) return;
        root.setBackground(new Background(new BackgroundFill(color, CornerRadii.EMPTY, Insets.EMPTY)));
        if (mainContentStack != null) {
            mainContentStack.setStyle("-fx-background-color: transparent;");
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
            Image image = new Image(file.toURI().toString());
            BackgroundSize size = new BackgroundSize(100, 100, true, true, false, true);
            BackgroundImage bg = new BackgroundImage(image, BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT, BackgroundPosition.CENTER, size);
            root.setBackground(new Background(bg));
            mainContentStack.setStyle("-fx-background-color: rgba(2, 6, 23, 0.52); -fx-background-radius: 22;");
            statusBar.setText("Sfondo personalizzato applicato.");
        } catch (Exception ex) {
            statusBar.setText("Impossibile caricare l'immagine scelta.");
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
                HttpClient http = HttpClient.newHttpClient();
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(LATEST_RELEASE_API))
                    .timeout(java.time.Duration.ofSeconds(8))
                    .header("Accept", "application/vnd.github+json")
                    .header("User-Agent", "MyAnimeDesk/" + APP_VERSION)
                    .GET()
                    .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new IOException("GitHub API " + response.statusCode());
                String body = response.body();
                String tag = extractJsonString(body, "tag_name");
                String url = extractJsonString(body, "html_url");
                String notes = extractJsonString(body, "body");
                if (url == null || url.isBlank()) url = RELEASES_URL;
                return new UpdateInfo(tag, url, notes);
            }
        };
        task.setOnSucceeded(e -> {
            UpdateInfo info = task.getValue();
            if (info != null && isNewerVersion(info.version, APP_VERSION)) {
                String message = "Versione installata: " + APP_VERSION + "\n" +
                                 "Nuova versione: " + cleanVersion(info.version) + "\n\n" +
                                 "Vuoi aprire GitHub Releases per scaricare l'aggiornamento?";
                showStyledConfirmDialog(
                    "Aggiornamento disponibile",
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
                showStyledMessageDialog(
                    "Aggiornamenti",
                    "Controllo aggiornamenti non riuscito",
                    "Controlla la connessione a Internet oppure riprova più tardi.",
                    "Ok",
                    null
                );
            }
        });
        executor.submit(task);
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
        if (mainContentStack == null) {
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
        overlay.setStyle("-fx-background-color: rgba(2, 6, 23, 0.74);");

        VBox card = new VBox(18);
        card.setPadding(new Insets(28, 34, 26, 34));
        card.setMaxWidth(820);
        card.setPrefWidth(820);
        card.setMinWidth(620);
        card.setMaxHeight(Region.USE_PREF_SIZE);
        card.setStyle(
            "-fx-background-color: linear-gradient(to bottom right, rgba(15,23,42,0.98), rgba(2,6,23,0.98));" +
            "-fx-background-radius: 28;" +
            "-fx-border-radius: 28;" +
            "-fx-border-width: 1;" +
            "-fx-border-color: rgba(96,165,250,0.42);" +
            "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.58), 34, 0.25, 0, 14);"
        );

        Label badge = new Label(title);
        badge.setTextFill(Color.web("#93c5fd"));
        badge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        badge.setPadding(new Insets(7, 14, 7, 14));
        badge.setStyle(
            "-fx-background-color: rgba(59,130,246,0.16);" +
            "-fx-background-radius: 999;" +
            "-fx-border-color: rgba(96,165,250,0.36);" +
            "-fx-border-radius: 999;" +
            "-fx-border-width: 1;"
        );

        Label header = new Label(subtitle);
        header.setTextFill(Color.WHITE);
        header.setFont(Font.font("Segoe UI", FontWeight.EXTRA_BOLD, 26));
        header.setWrapText(true);

        TextArea body = new TextArea(message == null ? "" : message);
        body.setEditable(false);
        body.setWrapText(true);
        body.setFocusTraversable(false);
        body.setFont(Font.font("Segoe UI", FontWeight.NORMAL, 15));
        body.setStyle(
            "-fx-control-inner-background: transparent;" +
            "-fx-background-color: transparent;" +
            "-fx-text-fill: #dbeafe;" +
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
            fadeOut.setOnFinished(ev -> mainContentStack.getChildren().remove(overlay));
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
        mainContentStack.getChildren().add(overlay);

        Platform.runLater(() -> {
            double availableHeight = mainContentStack.getHeight() > 0 ? mainContentStack.getHeight() : 760;
            double dialogHeight = Math.max(520, availableHeight - 100);
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
                "-fx-background-color: linear-gradient(to right, #3b82f6, #6366f1);" +
                "-fx-background-radius: 16;" +
                "-fx-border-radius: 16;" +
                "-fx-border-color: rgba(147,197,253,0.55);" +
                "-fx-border-width: 1;" +
                "-fx-effect: dropshadow(gaussian, rgba(59,130,246,0.30), 14, 0.20, 0, 4);"
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
        final String notes;
        UpdateInfo(String version, String url, String notes) {
            this.version = version;
            this.url = url;
            this.notes = notes;
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

}