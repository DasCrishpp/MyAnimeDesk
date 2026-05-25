package com.myanimedesk;

import javafx.animation.FadeTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class App extends Application {
    private final AniListClient client = new AniListClient();
    private final AnimeListManager manager = new AnimeListManager();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private BorderPane root;
    private StackPane mainContentStack;
    private VBox dashboardPane;
    private VBox libraryPane;
    private VBox searchPane;
    private VBox settingsPane;
    
    private TilePane libraryGrid;
    private TilePane searchGrid;
    private HBox dashboardWatchingRow;

    private Label totalHoursLabel;
    private Label watchingCountLabel;
    private Label completedCountLabel;

    private TextField searchField;
    private Timer searchDebounceTimer;

    private StackPane detailOverlay;
    private ImageView coverView;
    private Label titleLabel;
    private Label metaLabel;
    private Label scoreLabel;
    private TextArea descriptionArea;
    private ComboBox<String> statusCombo;
    private Button addButton;
    private Button removeButton;
    
    private Label statusBar;
    private Anime activeAnime;
    private String currentLibraryFilter = "TUTTI";
    
    private Button activeMenuButton = null;

    public static void main(String[] args) {
        launch(args);
    }

    @Override
    public void start(Stage stage) {
        stage.setTitle("MyAnimeDesk");
        stage.setMinWidth(1150);
        stage.setMinHeight(780);

        root = new BorderPane();
        // Colore di sfondo scuro predefinito
        root.setBackground(new Background(new BackgroundFill(Color.web("#0a1128"), CornerRadii.EMPTY, Insets.EMPTY)));

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
        stage.setScene(scene);
        stage.show();

        loadLibraryData();
        // Schermata iniziale predefinita sulla Dashboard
        Node firstBtn = ((VBox)root.getLeft()).getChildren().get(1);
        if(firstBtn instanceof Button) {
            ((Button) firstBtn).fire();
        }
    }

    private VBox createSidebar() {
        VBox sidebar = new VBox(10);
        sidebar.setPadding(new Insets(30, 15, 20, 15));
        sidebar.setPrefWidth(230);
        sidebar.setStyle("-fx-background-color: #040814; -fx-border-color: rgba(255,255,255,0.05); -fx-border-width: 0 1 0 0;");

        Label logo = new Label("✧ MyAnimeDesk");
        logo.setTextFill(Color.web("#4d7cff"));
        logo.setFont(Font.font("Segoe UI", FontWeight.BOLD, 24));
        logo.setPadding(new Insets(0, 0, 20, 10));

        Button btnDash = createSidebarButton("🏠  Dashboard");
        Button btnLib = createSidebarButton("📅  La Mia Lista");
        Button btnSearch = createSidebarButton("🔍  Scopri / Cerca");
        Button btnSettings = createSidebarButton("⚙️  Impostazioni");

        btnDash.setOnAction(e -> { selectMenuButton(btnDash); showView(dashboardPane); updateDashboardStats(); });
        btnLib.setOnAction(e -> { selectMenuButton(btnLib); showView(libraryPane); refreshLibraryGrid(); });
        btnSearch.setOnAction(e -> { selectMenuButton(btnSearch); showView(searchPane); });
        btnSettings.setOnAction(e -> { selectMenuButton(btnSettings); showView(settingsPane); });

        sidebar.getChildren().addAll(logo, btnDash, btnLib, btnSearch, btnSettings);
        return sidebar;
    }

    private Button createSidebarButton(String text) {
        Button btn = new Button(text);
        btn.setAlignment(Pos.CENTER_LEFT);
        btn.setPrefWidth(200);
        btn.setPadding(new Insets(12, 16, 12, 16));
        btn.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 14));
        updateButtonState(btn, false);

        btn.setOnMouseEntered(e -> {
            if (btn != activeMenuButton) {
                btn.setStyle("-fx-background-color: rgba(255, 255, 255, 0.07); -fx-text-fill: #ffffff; -fx-background-radius: 8; -fx-cursor: hand;");
            }
        });
        btn.setOnMouseExited(e -> {
            if (btn != activeMenuButton) {
                updateButtonState(btn, false);
            }
        });
        return btn;
    }

    private void updateButtonState(Button btn, boolean selected) {
        if (selected) {
            btn.setStyle("-fx-background-color: #4d7cff; -fx-text-fill: #ffffff; -fx-background-radius: 8; -fx-cursor: hand;");
        } else {
            btn.setStyle("-fx-background-color: transparent; -fx-text-fill: #94a3b8; -fx-background-radius: 8; -fx-cursor: hand;");
        }
    }

    private void selectMenuButton(Button target) {
        if (activeMenuButton != null) {
            updateButtonState(activeMenuButton, false);
        }
        activeMenuButton = target;
        updateButtonState(activeMenuButton, true);
    }

    private void showView(VBox targetPane) {
        VBox[] panes = {dashboardPane, libraryPane, searchPane, settingsPane};
        for (VBox p : panes) {
            if (p == targetPane) {
                p.setVisible(true);
                FadeTransition ft = new FadeTransition(Duration.millis(350), p);
                ft.setFromValue(0.4);
                ft.setToValue(1.0);
                ft.play();
            } else {
                p.setVisible(false);
            }
        }
        detailOverlay.setVisible(false);
    }

    // --- 1. SCHERMATA DASHBOARD ---
    private void initDashboardPane() {
        dashboardPane = new VBox(24);
        dashboardPane.setAlignment(Pos.TOP_LEFT);

        Label title = new Label("Dashboard");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 28));

        HBox statsRow = new HBox(20);
        VBox cardHours = createStatCard("TEMPO TOTALE DI VISIONE", totalHoursLabel = new Label("0.0 ore"), "#4d7cff");
        VBox cardWatching = createStatCard("ANIME IN VISIONE", watchingCountLabel = new Label("0"), "#ff9f43");
        VBox cardCompleted = createStatCard("ANIME COMPLETATI", completedCountLabel = new Label("0"), "#1dd1a1");
        statsRow.getChildren().addAll(cardHours, cardWatching, cardCompleted);

        Label rowTitle = new Label("⚡ Continua a Guardare");
        rowTitle.setTextFill(Color.web("#cbd5e1"));
        rowTitle.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 18));

        dashboardWatchingRow = new HBox(16);
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
        double hours = manager.all().stream().mapToDouble(Anime::totalHours).sum();
        totalHoursLabel.setText(String.format("%.1f ore", hours));
        watchingCountLabel.setText(String.valueOf(manager.byStatus(Anime.Status.WATCHING).size()));
        completedCountLabel.setText(String.valueOf(manager.byStatus(Anime.Status.WATCHED).size()));

        dashboardWatchingRow.getChildren().clear();
        List<Anime> watchingList = manager.byStatus(Anime.Status.WATCHING);
        if (watchingList.isEmpty()) {
            Label placeholder = new Label("Nessun anime in visione al momento. Cercane uno!");
            placeholder.setTextFill(Color.GRAY);
            dashboardWatchingRow.getChildren().add(placeholder);
        } else {
            for (Anime a : watchingList) {
                dashboardWatchingRow.getChildren().add(createAnimeGridCard(a));
            }
        }
    }

    // --- 2. SCHERMATA LA MIA LISTA ---
    private void initLibraryPane() {
        libraryPane = new VBox(18);
        
        Label title = new Label("La Mia Lista");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 26));

        HBox filtersRow = new HBox(10);
        String[] filters = {"TUTTI", "IN VISIONE", "VISTI", "DA VEDERE", "DROPPATO"};
        for (String f : filters) {
            Button filterBtn = new Button(f);
            filterBtn.setStyle("-fx-background-color: rgba(30, 41, 74, 0.8); -fx-text-fill: #cbd5e1; -fx-background-radius: 20; -fx-padding: 6 16 6 16; -fx-cursor: hand; -fx-font-weight: bold;");
            filterBtn.setOnAction(e -> {
                currentLibraryFilter = f;
                refreshLibraryGrid();
            });
            filtersRow.getChildren().add(filterBtn);
        }

        libraryGrid = new TilePane();
        libraryGrid.setHgap(18);
        libraryGrid.setVgap(18);
        libraryGrid.setPrefColumns(5);

        ScrollPane scrollPane = new ScrollPane(libraryGrid);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");

        libraryPane.getChildren().addAll(title, filtersRow, scrollPane);
    }

    private void refreshLibraryGrid() {
        libraryGrid.getChildren().clear();
        List<Anime> sourceList = switch (currentLibraryFilter) {
            case "IN VISIONE" -> manager.byStatus(Anime.Status.WATCHING);
            case "VISTI" -> manager.byStatus(Anime.Status.WATCHED);
            case "DA VEDERE" -> manager.byStatus(Anime.Status.TO_WATCH);
            case "DROPPATO" -> manager.byStatus(Anime.Status.DROPPED);
            default -> manager.all();
        };

        if (sourceList.isEmpty()) {
            Label emptyLbl = new Label("Nessun anime trovato in questa categoria.");
            emptyLbl.setTextFill(Color.GRAY);
            emptyLbl.setFont(Font.font("Segoe UI", 16));
            libraryGrid.getChildren().add(emptyLbl);
        } else {
            for (Anime a : sourceList) {
                libraryGrid.getChildren().add(createAnimeGridCard(a));
            }
        }
    }

    // --- 3. SCHERMATA CERCA ANIME (INSTANT SEARCH CON DEBOUNCE) ---
    private void initSearchPane() {
        searchPane = new VBox(18);

        Label title = new Label("Esplora e Scopri Nuovi Anime");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 26));

        searchField = new TextField();
        searchField.setPromptText("Inizia a digitare il titolo di un anime...");
        searchField.setPrefWidth(550);
        searchField.setStyle("-fx-background-color: #16223f; -fx-text-fill: white; -fx-prompt-text-fill: #64748b; -fx-background-radius: 12; -fx-border-radius: 12; -fx-border-color: #3b82f6; -fx-padding: 12; -fx-font-size: 14px;");
        
        // Listener per la ricerca istantanea mentre l'utente digita
        searchField.textProperty().addListener((observable, oldValue, newValue) -> {
            triggerDebouncedSearch(newValue.trim());
        });

        searchGrid = new TilePane();
        searchGrid.setHgap(18);
        searchGrid.setVgap(18);
        searchGrid.setPrefColumns(5);

        ScrollPane scrollPane = new ScrollPane(searchGrid);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");

        searchPane.getChildren().addAll(title, searchField, scrollPane);
    }

    private void triggerDebouncedSearch(String query) {
        if (searchDebounceTimer != null) {
            searchDebounceTimer.cancel();
        }
        if (query.length() < 2) {
            Platform.runLater(() -> searchGrid.getChildren().clear());
            return;
        }

        searchDebounceTimer = new Timer();
        searchDebounceTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                Platform.runLater(() -> executeOnlineSearch(query));
            }
        }, 400); // Avvia la ricerca dopo 400ms di inattività sulla tastiera
    }

    private void executeOnlineSearch(String query) {
        statusBar.setText("Ricerca di '" + query + "' su AniList...");
        Task<List<Anime>> searchTask = new Task<>() {
            @Override
            protected List<Anime> call() throws Exception {
                return client.search(query);
            }
        };

        searchTask.setOnSucceeded(evt -> {
            searchGrid.getChildren().clear();
            List<Anime> results = searchTask.getValue();
            if (results.isEmpty()) {
                statusBar.setText("Nessun risultato trovato online.");
                searchGrid.getChildren().add(new Label("Nessuna corrispondenza trovata."));
            } else {
                statusBar.setText("Trovati " + results.size() + " risultati.");
                for (Anime a : results) {
                    searchGrid.getChildren().add(createAnimeGridCard(a));
                }
            }
        });

        searchTask.setOnFailed(evt -> statusBar.setText("Errore di connessione API."));
        executor.submit(searchTask);
    }

    // --- 4. PANNELLO IMPOSTAZIONI ---
    private void initSettingsPane() {
        settingsPane = new VBox(24);
        settingsPane.setPadding(new Insets(10));

        Label title = new Label("Impostazioni Generali");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 26));

        // Sezione Personalizzazione Sfondo
        VBox sectionTheme = new VBox(10);
        Label lblTheme = new Label("Personalizza Colore dello Sfondo");
        lblTheme.setTextFill(Color.web("#cbd5e1"));
        lblTheme.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));
        
        ColorPicker colorPicker = new ColorPicker(Color.web("#0a1128"));
        colorPicker.setStyle("-fx-background-color: #1e293b; -fx-color-label-visible: false;");
        colorPicker.setOnAction(e -> {
            Color chosenColor = colorPicker.getValue();
            root.setBackground(new Background(new BackgroundFill(chosenColor, CornerRadii.EMPTY, Insets.EMPTY)));
        });
        sectionTheme.getChildren().addAll(lblTheme, colorPicker);

        // Sezione Backup Dati
        VBox sectionBackup = new VBox(12);
        Label lblBackup = new Label("Gestione e Backup dei Dati");
        lblBackup.setTextFill(Color.web("#cbd5e1"));
        lblBackup.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));

        Button btnExport = new Button("📤 Esporta Lista (JSON)");
        btnExport.setStyle("-fx-background-color: #1e293b; -fx-text-fill: white; -fx-padding: 10 18; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 8;");
        btnExport.setOnAction(e -> exportLibraryWithDialog());

        Button btnImport = new Button("📥 Importa Lista (JSON)");
        btnImport.setStyle("-fx-background-color: #1e293b; -fx-text-fill: white; -fx-padding: 10 18; -fx-font-weight: bold; -fx-cursor: hand; -fx-background-radius: 8;");
        btnImport.setOnAction(e -> importLibraryWithDialog());

        HBox backupButtons = new HBox(14, btnExport, btnImport);
        sectionBackup.getChildren().addAll(lblBackup, backupButtons);

        settingsPane.getChildren().addAll(title, new Separator(), sectionTheme, new Separator(), sectionBackup);
    }

    private void exportLibraryWithDialog() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Esporta la tua Lista");
        fc.setInitialFileName("myanimedesk_backup.json");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("File JSON", "*.json"));
        File dest = fc.showSaveDialog(root.getScene().getWindow());
        if (dest != null) {
            try {
                manager.saveToDefault(); // Assicura che il locale sia aggiornato
                File source = new File(System.getProperty("user.home") + "/.myanimedesk/library.json");
                if (source.exists()) {
                    Files.copy(source.toPath(), dest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    statusBar.setText("Lista esportata correttamente in: " + dest.getName());
                }
            } catch (IOException e) {
                statusBar.setText("Errore durante l'esportazione: " + e.getMessage());
            }
        }
    }

    private void importLibraryWithDialog() {
        FileChooser fc = new FileChooser();
        fc.setTitle("Seleziona il Backup da Importare");
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("File JSON", "*.json"));
        File src = fc.showOpenDialog(root.getScene().getWindow());
        if (src != null) {
            try {
                File localDest = new File(System.getProperty("user.home") + "/.myanimedesk/library.json");
                if (!localDest.getParentFile().exists()) localDest.getParentFile().mkdirs();
                Files.copy(src.toPath(), localDest.toPath(), StandardCopyOption.REPLACE_EXISTING);
                manager.loadFromDefault();
                statusBar.setText("Backup importato con successo! Dati ricaricati.");
                updateDashboardStats();
                refreshLibraryGrid();
            } catch (Exception e) {
                statusBar.setText("Errore di importazione: " + e.getMessage());
            }
        }
    }

    // --- 5. FABBRICA RIGUADRI ANIME (CARDS AGGIORNATE) ---
    private VBox createAnimeGridCard(Anime anime) {
        VBox card = new VBox(8);
        card.setPadding(new Insets(10));
        card.setPrefWidth(165);
        card.setMaxWidth(165);
        
        // Cerca se esiste già in memoria locale
        Anime localInstance = manager.all().stream().filter(x -> x.id == anime.id).findFirst().orElse(null);
        boolean isSaved = (localInstance != null);
        Anime.Status activeStatus = isSaved ? localInstance.status : null;

        // Configurazione Badge Stato Esplicito e Colori netti
        Label statusBadge = new Label();
        statusBadge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
        statusBadge.setPadding(new Insets(3, 6, 3, 6));
        statusBadge.setStyle("-fx-background-radius: 4; -fx-text-fill: white;");

        String borderAccent = "#223254"; // Di base grigio scuro neutro se non è salvato
        if (isSaved) {
            switch (activeStatus) {
                case WATCHING -> { borderAccent = "#ff9f43"; statusBadge.setText("IN VISIONE"); statusBadge.setStyle(statusBadge.getStyle() + "-fx-background-color: #ff9f43;"); }
                case WATCHED -> { borderAccent = "#1dd1a1"; statusBadge.setText("COMPLETATO"); statusBadge.setStyle(statusBadge.getStyle() + "-fx-background-color: #1dd1a1;"); }
                case DROPPED -> { borderAccent = "#ff6b6b"; statusBadge.setText("DROPPATO"); statusBadge.setStyle(statusBadge.getStyle() + "-fx-background-color: #ff6b6b;"); }
                case TO_WATCH -> { borderAccent = "#3b82f6"; statusBadge.setText("DA VEDERE"); statusBadge.setStyle(statusBadge.getStyle() + "-fx-background-color: #3b82f6;"); }
            }
        } else {
            statusBadge.setText("NON IN LISTA");
            statusBadge.setStyle(statusBadge.getStyle() + "-fx-background-color: #475569;");
        }

        card.setStyle("-fx-background-color: rgba(22, 34, 61, 0.6); -fx-background-radius: 12; -fx-border-radius: 12; -fx-border-color: " + borderAccent + "; -fx-border-width: 2; -fx-cursor: hand;");

        // Copertina dell'Anime
        ImageView poster = new ImageView();
        poster.setFitWidth(141);
        poster.setFitHeight(200);
        if (anime.coverImage != null && !anime.coverImage.isBlank()) {
            poster.setImage(new Image(anime.coverImage, 141, 200, false, true, true));
        }

        Label title = new Label(anime.title != null ? anime.title : "Titolo sconosciuto");
        title.setTextFill(Color.web("#f1f5f9"));
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        title.setWrapText(true);
        title.setPrefHeight(36);

        // Riga Inferiore: Informazioni sugli episodi + Bottone Rapido Aggiungi se assente
        HBox bottomRow = new HBox(4);
        bottomRow.setAlignment(Pos.CENTER_LEFT);
        
        Label epInfo = new Label("Episodi: " + anime.episodes);
        epInfo.setTextFill(Color.web("#94a3b8"));
        epInfo.setFont(Font.font("Segoe UI", 11));
        epInfo.setPrefWidth(95);

        bottomRow.getChildren().add(epInfo);

        if (!isSaved) {
            Button quickAddBtn = new Button("➕");
            quickAddBtn.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-size: 10px; -fx-background-radius: 6; -fx-padding: 3 6; -fx-cursor: hand;");
            quickAddBtn.setOnAction(e -> {
                e.consume(); // Evita di far scattare il click del pannello intero
                anime.status = Anime.Status.TO_WATCH; 
                manager.add(anime);
                saveLibraryData("Aggiunto rapidamente alla lista 'Da vedere'.");
                refreshLibraryGrid();
                updateDashboardStats();
            });
            bottomRow.getChildren().add(quickAddBtn);
        }

        card.getChildren().addAll(statusBadge, poster, title, bottomRow);
        card.setOnMouseClicked(e -> showAnimeDetails(anime));
        return card;
    }

    // --- 6. POP-UP MODALE INTERFACCIA DETTAGLI ---
    private void initDetailOverlay() {
        detailOverlay = new StackPane();
        detailOverlay.setStyle("-fx-background-color: rgba(3, 7, 18, 0.85);");
        detailOverlay.setVisible(false);

        HBox dialogBox = new HBox(24);
        dialogBox.setPadding(new Insets(24));
        dialogBox.setMaxSize(820, 500);
        dialogBox.setStyle("-fx-background-color: #0f172a; -fx-background-radius: 16; -fx-border-radius: 16; -fx-border-color: #3b82f6; -fx-border-width: 1.5;");

        coverView = new ImageView();
        coverView.setFitWidth(240);
        coverView.setFitHeight(360);
        coverView.setPreserveRatio(true);

        VBox contentSide = new VBox(12);
        contentSide.setAlignment(Pos.TOP_LEFT);
        contentSide.setPrefWidth(500);

        titleLabel = new Label();
        titleLabel.setTextFill(Color.WHITE);
        titleLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 24));
        titleLabel.setWrapText(true);

        metaLabel = new Label();
        metaLabel.setTextFill(Color.web("#38bdf8"));
        metaLabel.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 13));

        scoreLabel = new Label();
        scoreLabel.setTextFill(Color.web("#eab308"));
        scoreLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 13));

        descriptionArea = new TextArea();
        descriptionArea.setEditable(false);
        descriptionArea.setWrapText(true);
        descriptionArea.setPrefRowCount(8);
        descriptionArea.setStyle("-fx-control-inner-background: #1e293b; -fx-text-fill: #f8fafc; -fx-background-radius: 8; -fx-border-radius: 8; -fx-border-color: #334155; -fx-font-size: 13px;");

        statusCombo = new ComboBox<>(FXCollections.observableArrayList("Da vedere", "In visione", "Visti", "Droppato"));
        statusCombo.setStyle("-fx-background-color: #1e293b; -fx-text-fill: white; -fx-background-radius: 8; -fx-border-color: #475569;");
        statusCombo.setOnAction(e -> updateAnimeStatus());

        addButton = new Button("➕ Salva in Lista");
        addButton.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 8 16; -fx-cursor: hand;");
        addButton.setOnAction(e -> addAnimeToLibrary());

        removeButton = new Button("🗑️ Rimuovi");
        removeButton.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-padding: 8 16; -fx-cursor: hand;");
        removeButton.setOnAction(e -> removeAnimeFromLibrary());

        Button closeBtn = new Button("Chiudi");
        closeBtn.setStyle("-fx-background-color: #475569; -fx-text-fill: white; -fx-background-radius: 8; -fx-padding: 8 16; -fx-cursor: hand;");
        closeBtn.setOnAction(e -> {
            detailOverlay.setVisible(false);
            refreshLibraryGrid();
            updateDashboardStats();
        });

        HBox actionsRow = new HBox(10, addButton, statusCombo, removeButton, closeBtn);
        actionsRow.setAlignment(Pos.CENTER_LEFT);
        actionsRow.setPadding(new Insets(10, 0, 0, 0));

        contentSide.getChildren().addAll(titleLabel, metaLabel, scoreLabel, descriptionArea, actionsRow);
        dialogBox.getChildren().addAll(coverView, contentSide);
        detailOverlay.getChildren().add(dialogBox);
    }

    private void showAnimeDetails(Anime anime) {
        if (anime == null) return;
        
        Anime local = manager.all().stream().filter(x -> x.id == anime.id).findFirst().orElse(null);
        boolean exists = (local != null);
        activeAnime = exists ? local : anime;

        titleLabel.setText(activeAnime.title != null ? activeAnime.title : "Titolo Sconosciuto");
        metaLabel.setText("⏱️ " + activeAnime.episodes + " ep. x " + activeAnime.duration + " min  •  Stato attuale: " 
                + (exists ? activeAnime.statusToString() : "Non in lista"));
        
        scoreLabel.setText("🏷️ Generi: " + (activeAnime.genres != null ? String.join(", ", activeAnime.genres) : "Nessuno"));
        
        descriptionArea.setText("Dettagli Estesi dell'Anime:\n"
                + "Questo anime è indicizzato globalmente con ID #" + activeAnime.id + ".\n"
                + "Puoi salvarlo o aggiornarlo direttamente utilizzando il menu a tendina in basso per tracciare con esattezza il conteggio totale delle tue ore complessive espresse in Dashboard.");

        statusCombo.setValue(exists ? activeAnime.statusToString() : "Da vedere");
        addButton.setDisable(exists);
        removeButton.setDisable(!exists);

        if (activeAnime.coverImage != null && !activeAnime.coverImage.isBlank()) {
            coverView.setImage(new Image(activeAnime.coverImage, 240, 360, true, true, true));
        } else {
            coverView.setImage(null);
        }

        detailOverlay.setVisible(true);
        FadeTransition ft = new FadeTransition(Duration.millis(250), detailOverlay);
        ft.setFromValue(0.0);
        ft.setToValue(1.0);
        ft.play();
    }

    private void addAnimeToLibrary() {
        if (activeAnime == null) return;
        activeAnime.status = Anime.fromStringLocalized(statusCombo.getValue());
        manager.add(activeAnime);
        saveLibraryData("Anime memorizzato con successo nella tua collezione.");
        addButton.setDisable(true);
        removeButton.setDisable(false);
        showAnimeDetails(activeAnime); 
    }

    private void removeAnimeFromLibrary() {
        if (activeAnime == null) return;
        manager.remove(activeAnime.id);
        saveLibraryData("Anime eliminato definitivamente dalla lista locale.");
        addButton.setDisable(false);
        removeButton.setDisable(true);
        showAnimeDetails(activeAnime);
    }

    private void updateAnimeStatus() {
        if (activeAnime == null) return;
        String selected = statusCombo.getValue();
        if (selected == null || selected.isBlank()) return;

        Anime.Status newStatus = Anime.fromStringLocalized(selected);
        
        if (manager.all().stream().anyMatch(x -> x.id == activeAnime.id)) {
            activeAnime.status = newStatus;
            manager.updateStatus(activeAnime.id, newStatus);
            saveLibraryData("Stato modificato correttamente in '" + selected + "'.");
            metaLabel.setText("⏱️ " + activeAnime.episodes + " ep. x " + activeAnime.duration + " min  •  Stato attuale: " + activeAnime.statusToString());
        }
    }

    // --- 7. PERSISTENZA E BARRA DI STATO REGISTRATA ---
    private void loadLibraryData() {
        try {
            manager.loadFromDefault();
            statusBar.setText("Libreria locale caricata con successo.");
        } catch (Exception e) {
            statusBar.setText("Nessun salvataggio trovato. Database pronto.");
        }
        updateDashboardStats();
    }

    private void saveLibraryData(String message) {
        try {
            manager.saveToDefault();
            statusBar.setText(message);
        } catch (Exception e) {
            statusBar.setText("Errore di salvataggio su disco: " + e.getMessage());
        }
    }

    private HBox createStatusBar() {
        statusBar = new Label("Pronto.");
        statusBar.setTextFill(Color.web("#94a3b8"));
        statusBar.setFont(Font.font("Segoe UI", 12));

        Label copyrightLabel = new Label("© 2026 MyAnimeDesk - Creato da Cristian Biondi");
        copyrightLabel.setTextFill(Color.web("#64748b"));
        copyrightLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox footer = new HBox(statusBar, spacer, copyrightLabel);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(12, 20, 12, 20));
        footer.setStyle("-fx-background-color: #040814; -fx-border-color: rgba(255,255,255,0.04); -fx-border-width: 1 0 0 0;");
        return footer;
    }
}