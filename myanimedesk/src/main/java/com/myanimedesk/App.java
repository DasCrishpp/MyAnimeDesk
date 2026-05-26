package com.myanimedesk;

import javafx.animation.FadeTransition;
import javafx.animation.ScaleTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.concurrent.Task; // <-- IMPORT RISOLTO
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
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
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.util.Duration;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class App extends Application {
    private final AniListClient client = new AniListClient();
    private final AnimeListManager manager = new AnimeListManager();
    private final ExecutorService executor = Executors.newCachedThreadPool();

    private BorderPane root;
    private StackPane mainContentStack;
    private VBox dashboardPane, libraryPane, searchPane, settingsPane;
    
    private TilePane libraryGrid, searchGrid;
    private HBox dashboardWatchingRow;

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
    private ComboBox<String> statusCombo;
    private Button addButton, removeButton;
    
    private Label statusBar;
    private Anime activeAnime;
    private String currentLibraryFilter = "TUTTI";
    private Button activeMenuButton = null;

    // Impostazioni Suono
    private boolean soundEnabled = true;

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
        
        Node firstBtn = ((VBox)root.getLeft()).getChildren().get(1);
        if(firstBtn instanceof Button) {
            ((Button) firstBtn).fire();
        }
    }

    // --- GENERATORE DI SUONI PROCEDURALI DI SISTEMA ---
    private void playSynthesizedSound(int frequency, int durationMs, double volumeMultiplier) {
        if (!soundEnabled) return;
        executor.submit(() -> {
            try {
                byte[] buf = new byte[durationMs * 8];
                for (int i = 0; i < buf.length; i++) {
                    double angle = i / (8000.0 / frequency) * 2.0 * Math.PI;
                    buf[i] = (byte) (Math.sin(angle) * 25.0 * volumeMultiplier * (1.0 - (double) i / buf.length));
                }
                javax.sound.sampled.AudioFormat af = new javax.sound.sampled.AudioFormat(8000f, 8, 1, true, false);
                javax.sound.sampled.SourceDataLine sdl = javax.sound.sampled.AudioSystem.getSourceDataLine(af);
                sdl.open(af);
                sdl.start();
                sdl.write(buf, 0, buf.length);
                sdl.drain();
                sdl.close();
            } catch (Exception e) {
                java.awt.Toolkit.getDefaultToolkit().beep(); 
            }
        });
    }

    private void playHoverSound() {
        playSynthesizedSound(580, 35, 0.6); 
    }

    private void playClickSound() {
        playSynthesizedSound(420, 70, 0.9); 
    }

    // --- SIDEBAR ---
    private VBox createSidebar() {
        VBox sidebar = new VBox(12);
        sidebar.setPadding(new Insets(30, 15, 20, 15));
        sidebar.setPrefWidth(230);
        sidebar.setStyle("-fx-background-color: #040814; -fx-border-color: rgba(255,255,255,0.05); -fx-border-width: 0 1 0 0;");

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
        btn.setPadding(new Insets(12, 16, 12, 16));
        btn.setFont(Font.font("Segoe UI", FontWeight.SEMI_BOLD, 14));
        btn.setStyle("-fx-background-color: #0d1527; -fx-text-fill: #94a3b8; -fx-background-radius: 8; -fx-border-color: #1e293b; -fx-border-width: 1; -fx-cursor: hand;");

        btn.setOnMouseEntered(e -> {
            if (btn != activeMenuButton) {
                playHoverSound();
                btn.setStyle("-fx-background-color: #16223f; -fx-text-fill: #ffffff; -fx-background-radius: 8; -fx-border-color: #3b82f6; -fx-border-width: 1; -fx-cursor: hand;");
            }
        });
        btn.setOnMouseExited(e -> {
            if (btn != activeMenuButton) {
                btn.setStyle("-fx-background-color: #0d1527; -fx-text-fill: #94a3b8; -fx-background-radius: 8; -fx-border-color: #1e293b; -fx-border-width: 1; -fx-cursor: hand;");
            }
        });
        return btn;
    }

    private void selectMenuButton(Button target) {
        if (activeMenuButton != null) {
            activeMenuButton.setStyle("-fx-background-color: #0d1527; -fx-text-fill: #94a3b8; -fx-background-radius: 8; -fx-border-color: #1e293b; -fx-border-width: 1; -fx-cursor: hand;");
        }
        activeMenuButton = target;
        activeMenuButton.setStyle("-fx-background-color: #3b82f6; -fx-text-fill: #ffffff; -fx-background-radius: 8; -fx-border-color: #60a5fa; -fx-border-width: 1; -fx-cursor: hand;");
    }

    private void showView(VBox targetPane) {
        VBox[] panes = {dashboardPane, libraryPane, searchPane, settingsPane};
        for (VBox p : panes) {
            if (p == targetPane) {
                p.setVisible(true);
                FadeTransition ft = new FadeTransition(Duration.millis(300), p);
                ft.setFromValue(0.2); ft.setToValue(1.0); ft.play();
            } else { p.setVisible(false); }
        }
        detailOverlay.setVisible(false);
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
        double hours = manager.all().stream().mapToDouble(Anime::totalHours).sum();
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

    // --- 3. RICERCA RAPIDA DEBOUNCED ---
    private void initSearchPane() {
        searchPane = new VBox(18);

        Label title = new Label("Esplora e Scopri");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 26));

        searchField = new TextField();
        searchField.setPromptText("Digita il titolo di un anime...");
        searchField.setPrefWidth(550);
        searchField.setStyle("-fx-background-color: #16223f; -fx-text-fill: white; -fx-prompt-text-fill: #64748b; -fx-background-radius: 12; -fx-border-radius: 12; -fx-border-color: #3b82f6; -fx-padding: 12; -fx-font-size: 14px;");
        
        searchField.textProperty().addListener((observable, oldValue, newValue) -> triggerDebouncedSearch(newValue.trim()));

        searchGrid = new TilePane();
        searchGrid.setHgap(10); 
        searchGrid.setVgap(10);
        searchGrid.setPrefColumns(5);

        ScrollPane scrollPane = new ScrollPane(searchGrid);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-border-color: transparent;");

        searchPane.getChildren().addAll(title, searchField, scrollPane);
    }

    private void triggerDebouncedSearch(String query) {
        if (searchDebounceTimer != null) searchDebounceTimer.cancel();
        if (query.length() < 2) {
            Platform.runLater(() -> searchGrid.getChildren().clear());
            return;
        }
        searchDebounceTimer = new Timer();
        searchDebounceTimer.schedule(new TimerTask() {
            @Override public void run() { Platform.runLater(() -> executeOnlineSearch(query)); }
        }, 200); 
    }

    private void executeOnlineSearch(String query) {
        statusBar.setText("Ricerca in corso...");
        Task<List<Anime>> searchTask = new Task<>() {
            @Override protected List<Anime> call() throws Exception { return client.search(query); }
        };
        searchTask.setOnSucceeded(evt -> {
            searchGrid.getChildren().clear();
            List<Anime> results = searchTask.getValue();
            if (results.isEmpty()) {
                statusBar.setText("Nessun risultato trovato.");
            } else {
                statusBar.setText("Trovati " + results.size() + " risultati.");
                for (Anime a : results) searchGrid.getChildren().add(createAnimeGridCard(a));
            }
        });
        executor.submit(searchTask);
    }

    // --- 4. IMPOSTAZIONI ---
    private void initSettingsPane() {
        settingsPane = new VBox(24);
        settingsPane.setPadding(new Insets(10));

        Label title = new Label("Impostazioni Generali");
        title.setTextFill(Color.WHITE);
        title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 26));

        VBox sectionTheme = new VBox(10);
        Label lblTheme = new Label("Colore dello Sfondo");
        lblTheme.setTextFill(Color.web("#cbd5e1"));
        lblTheme.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));
        
        ColorPicker colorPicker = new ColorPicker(Color.web("#0a1128"));
        colorPicker.setStyle("-fx-background-color: #1e293b;");
        colorPicker.setOnAction(e -> root.setBackground(new Background(new BackgroundFill(colorPicker.getValue(), CornerRadii.EMPTY, Insets.EMPTY))));
        sectionTheme.getChildren().addAll(lblTheme, colorPicker);

        VBox sectionSound = new VBox(10);
        Label lblSound = new Label("Effetti Sonori (UI)");
        lblSound.setTextFill(Color.web("#cbd5e1"));
        lblSound.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));
        CheckBox soundCheck = new CheckBox("Abilita suoni di interfaccia dinamici");
        soundCheck.setSelected(true);
        soundCheck.setTextFill(Color.WHITE);
        soundCheck.setOnAction(e -> soundEnabled = soundCheck.isSelected());
        sectionSound.getChildren().addAll(lblSound, soundCheck);

        VBox sectionBackup = new VBox(12);
        Label lblBackup = new Label("Backup dei Dati");
        lblBackup.setTextFill(Color.web("#cbd5e1"));
        lblBackup.setFont(Font.font("Segoe UI", FontWeight.BOLD, 16));

        Button btnExport = new Button("Esporta Lista");
        btnExport.setStyle("-fx-background-color: #1e293b; -fx-text-fill: white; -fx-padding: 8 16; -fx-cursor: hand;");
        btnExport.setOnAction(e -> exportLibraryWithDialog());

        Button btnImport = new Button("Importa Lista");
        btnImport.setStyle("-fx-background-color: #1e293b; -fx-text-fill: white; -fx-padding: 8 16; -fx-cursor: hand;");
        btnImport.setOnAction(e -> importLibraryWithDialog());

        sectionBackup.getChildren().addAll(lblBackup, new HBox(14, btnExport, btnImport));

        settingsPane.getChildren().addAll(title, new Separator(), sectionTheme, new Separator(), sectionSound, new Separator(), sectionBackup);
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
        
        Anime localInstance = manager.all().stream().filter(x -> x.id == anime.id).findFirst().orElse(null);
        boolean isSaved = (localInstance != null);

        // BASE LAYER (Stabile)
        VBox baseLayer = new VBox(8);
        baseLayer.setPadding(new Insets(10));
        baseLayer.setPrefWidth(165);
        baseLayer.setMaxWidth(165);

        Label statusBadge = new Label();
        statusBadge.setFont(Font.font("Segoe UI", FontWeight.BOLD, 10));
        statusBadge.setPadding(new Insets(3, 6, 3, 6));
        statusBadge.setStyle("-fx-background-radius: 4; -fx-text-fill: white;");

        // Aggiorna lo stile in-place senza far sparire e riapparire il menu!
        Runnable updateBaseStyle = () -> {
            Anime currentLocal = manager.all().stream().filter(x -> x.id == anime.id).findFirst().orElse(null);
            String borderAccent = "#223254"; 
            if (currentLocal != null) {
                switch (currentLocal.status) {
                    case WATCHING -> { borderAccent = "#ff9f43"; statusBadge.setText("IN VISIONE"); statusBadge.setStyle("-fx-background-radius: 4; -fx-text-fill: white; -fx-background-color: #ff9f43;"); }
                    case WATCHED -> { borderAccent = "#1dd1a1"; statusBadge.setText("VISTI"); statusBadge.setStyle("-fx-background-radius: 4; -fx-text-fill: white; -fx-background-color: #1dd1a1;"); }
                    case DROPPED -> { borderAccent = "#ff6b6b"; statusBadge.setText("DROPPATO"); statusBadge.setStyle("-fx-background-radius: 4; -fx-text-fill: white; -fx-background-color: #ff6b6b;"); }
                    case TO_WATCH -> { borderAccent = "#3b82f6"; statusBadge.setText("DA VEDERE"); statusBadge.setStyle("-fx-background-radius: 4; -fx-text-fill: white; -fx-background-color: #3b82f6;"); }
                }
            } else {
                statusBadge.setText("NON IN LISTA");
                statusBadge.setStyle("-fx-background-radius: 4; -fx-text-fill: white; -fx-background-color: #475569;");
            }
            baseLayer.setStyle("-fx-background-color: rgba(22, 34, 61, 0.6); -fx-background-radius: 12; -fx-border-radius: 12; -fx-border-color: " + borderAccent + "; -fx-border-width: 2; -fx-cursor: hand;");
        };
        updateBaseStyle.run();

        ImageView poster = new ImageView();
        poster.setFitWidth(141); poster.setFitHeight(200);
        if (anime.coverImage != null && !anime.coverImage.isBlank()) poster.setImage(new Image(anime.coverImage, 141, 200, false, true, true));

        Label title = new Label(anime.title != null ? anime.title : "Titolo sconosciuto");
        title.setTextFill(Color.web("#f1f5f9")); title.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));
        title.setWrapText(true); title.setPrefHeight(36);

        baseLayer.getChildren().addAll(statusBadge, poster, title);
        cardRoot.getChildren().add(baseLayer);

        // POPUP LATERALE (Accanto alla card)
        Popup sidePopup = new Popup();
        VBox popupContent = new VBox(8);
        popupContent.setStyle("-fx-background-color: #060b1a; -fx-border-color: #3b82f6; -fx-border-width: 1.5; -fx-background-radius: 10; -fx-border-radius: 10; -fx-padding: 12; -fx-effect: dropshadow(three-pass-box, rgba(0,0,0,0.6), 10, 0, 0, 0);");
        popupContent.setPrefWidth(170);

        Label hoverInfo = new Label(
            "Episodi: " + anime.episodes + "\n" +
            "Durata: " + anime.duration + " min\n\n" +
            "Generi:\n" + (anime.genres != null ? String.join(", ", anime.genres) : "-")
        );
        hoverInfo.setTextFill(Color.WHITE); hoverInfo.setFont(Font.font("Segoe UI", 12)); hoverInfo.setWrapText(true);
        
        VBox quickAddMenu = new VBox(5);
        quickAddMenu.setAlignment(Pos.CENTER);
        
        String btnBaseStyle = "-fx-background-radius: 6; -fx-text-fill: white; -fx-cursor: hand; -fx-font-weight: bold; -fx-font-size: 11px; -fx-padding: 5 0;";
        Button btnWatch = new Button("In Visione"); btnWatch.setMaxWidth(Double.MAX_VALUE); btnWatch.setStyle("-fx-background-color: #ff9f43; " + btnBaseStyle);
        Button btnSeen = new Button("Visti"); btnSeen.setMaxWidth(Double.MAX_VALUE); btnSeen.setStyle("-fx-background-color: #1dd1a1; " + btnBaseStyle);
        Button btnPlan = new Button("Da Vedere"); btnPlan.setMaxWidth(Double.MAX_VALUE); btnPlan.setStyle("-fx-background-color: #3b82f6; " + btnBaseStyle);
        Button btnDrop = new Button("Droppato"); btnDrop.setMaxWidth(Double.MAX_VALUE); btnDrop.setStyle("-fx-background-color: #ff6b6b; " + btnBaseStyle);

        Runnable applyQuickStatus = () -> {
            manager.add(anime);
            saveLibraryData("Stato aggiornato.");
            
            updateBaseStyle.run();
            updateFilterButtonCounts();
            sidePopup.hide(); 
            
            if (libraryPane.isVisible() && !currentLibraryFilter.equals("TUTTI") && !currentLibraryFilter.equals(anime.statusToString().toUpperCase())) {
                FadeTransition out = new FadeTransition(Duration.millis(200), cardRoot);
                out.setToValue(0);
                out.setOnFinished(evt -> libraryGrid.getChildren().remove(cardRoot));
                out.play();
            }
        };

        btnWatch.setOnAction(e -> { anime.status = Anime.Status.WATCHING; applyQuickStatus.run(); });
        btnSeen.setOnAction(e -> { anime.status = Anime.Status.WATCHED; applyQuickStatus.run(); });
        btnPlan.setOnAction(e -> { anime.status = Anime.Status.TO_WATCH; applyQuickStatus.run(); });
        btnDrop.setOnAction(e -> { anime.status = Anime.Status.DROPPED; applyQuickStatus.run(); });

        quickAddMenu.getChildren().addAll(new Label("Imposta Stato:"), btnWatch, btnSeen, btnPlan, btnDrop);
        ((Label)quickAddMenu.getChildren().get(0)).setTextFill(Color.web("#94a3b8"));
        ((Label)quickAddMenu.getChildren().get(0)).setFont(Font.font("Segoe UI", 10));

        if (isSaved) {
            popupContent.getChildren().addAll(hoverInfo);
        } else {
            popupContent.getChildren().addAll(hoverInfo, new Separator(), quickAddMenu);
        }
        sidePopup.getContent().add(popupContent);

        // ANIMAZIONI HOVER E ORIENTAMENTO POSIZIONE
        ScaleTransition scaleIn = new ScaleTransition(Duration.millis(120), cardRoot); scaleIn.setToX(1.04); scaleIn.setToY(1.04);
        ScaleTransition scaleOut = new ScaleTransition(Duration.millis(120), cardRoot); scaleOut.setToX(1.0); scaleOut.setToY(1.0);

        cardRoot.setOnMouseEntered(e -> {
            playHoverSound();
            scaleIn.playFromStart();
            
            if (!sidePopup.isShowing()) {
                Point2D screenPos = cardRoot.localToScreen(cardRoot.getWidth(), 0);
                sidePopup.show(cardRoot, screenPos.getX() + 6, screenPos.getY());
            }
        });

        cardRoot.setOnMouseExited(e -> {
            scaleOut.playFromStart();
            Platform.runLater(() -> {
                if (!popupContent.isHover() && !cardRoot.isHover()) sidePopup.hide();
            });
        });

        popupContent.setOnMouseExited(e -> {
            Platform.runLater(() -> {
                if (!popupContent.isHover() && !cardRoot.isHover()) sidePopup.hide();
            });
        });

        cardRoot.setOnMouseClicked(e -> {
            sidePopup.hide();
            playClickSound();
            showAnimeDetails(anime);
        });

        return cardRoot;
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

        statusCombo = new ComboBox<>(FXCollections.observableArrayList("Da vedere", "In visione", "Visti", "Droppato"));
        statusCombo.setStyle("-fx-background-color: #1e293b; -fx-text-fill: white; -fx-background-radius: 8; -fx-border-color: #475569;");
        statusCombo.setOnAction(e -> updateAnimeStatus());

        addButton = new Button("Salva in Lista");
        addButton.setStyle("-fx-background-color: #10b981; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-cursor: hand; -fx-padding: 8 16;");
        addButton.setOnAction(e -> addAnimeToLibrary());

        removeButton = new Button("Rimuovi");
        removeButton.setStyle("-fx-background-color: #ef4444; -fx-text-fill: white; -fx-font-weight: bold; -fx-background-radius: 8; -fx-cursor: hand; -fx-padding: 8 16;");
        removeButton.setOnAction(e -> removeAnimeFromLibrary());

        Button closeBtn = new Button("Chiudi");
        closeBtn.setStyle("-fx-background-color: #475569; -fx-text-fill: white; -fx-background-radius: 8; -fx-cursor: hand; -fx-padding: 8 16;");
        closeBtn.setOnAction(e -> {
            detailOverlay.setVisible(false);
            refreshLibraryGrid();
            updateDashboardStats();
        });

        HBox actionsRow = new HBox(12, statusCombo, addButton, removeButton, closeBtn);
        actionsRow.setAlignment(Pos.CENTER_LEFT);
        actionsRow.setPadding(new Insets(10, 0, 0, 0));

        contentSide.getChildren().addAll(titleLabel, metaLabel, infoScroll, actionsRow);
        dialogBox.getChildren().addAll(coverView, contentSide);
        detailOverlay.getChildren().add(dialogBox);
    }

    private void showAnimeDetails(Anime anime) {
        if (anime == null) return;
        Anime local = manager.all().stream().filter(x -> x.id == anime.id).findFirst().orElse(null);
        boolean exists = (local != null);
        activeAnime = exists ? local : anime;

        titleLabel.setText(activeAnime.title != null ? activeAnime.title : "Sconosciuto");
        metaLabel.setText("STATO NELLA TUA LISTA: " + activeAnime.statusToString().toUpperCase());
        
        // Struttura Premium pulita: mostra ESATTAMENTE i dati reali del tuo file Anime.java
        extendedInfoBox.getChildren().clear();
        extendedInfoBox.getChildren().addAll(
            createInfoDetailRow("Numero Episodi", String.valueOf(activeAnime.episodes)),
            createInfoDetailRow("Durata Singolo Episodio", activeAnime.duration + " min"),
            createInfoDetailRow("Tempo di Visione Totale", String.format("%.1f ore", activeAnime.totalHours())),
            createGenresTagRow(activeAnime.genres)
        );

        statusCombo.setValue(activeAnime.statusToString());
        addButton.setDisable(exists);
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
        activeAnime.status = Anime.fromStringLocalized(statusCombo.getValue());
        manager.add(activeAnime);
        saveLibraryData("Salvato in lista.");
        addButton.setDisable(true); removeButton.setDisable(false);
        showAnimeDetails(activeAnime); 
    }

    private void removeAnimeFromLibrary() {
        if (activeAnime == null) return;
        manager.remove(activeAnime.id);
        saveLibraryData("Rimosso dalla lista.");
        addButton.setDisable(false); removeButton.setDisable(true);
        showAnimeDetails(activeAnime);
    }

    private void updateAnimeStatus() {
        if (activeAnime == null || statusCombo.getValue() == null) return;
        Anime.Status newStatus = Anime.fromStringLocalized(statusCombo.getValue());
        if (manager.all().stream().anyMatch(x -> x.id == activeAnime.id)) {
            activeAnime.status = newStatus;
            manager.updateStatus(activeAnime.id, newStatus);
            saveLibraryData("Stato modificato.");
            showAnimeDetails(activeAnime);
        }
    }

    // --- CARICAMENTO DATI ---
    private void loadLibraryData() {
        try { manager.loadFromDefault(); statusBar.setText("Libreria caricata."); }
        catch (Exception e) { statusBar.setText("Database pronto."); }
        updateFilterButtonCounts();
        updateDashboardStats();
    }

    private void saveLibraryData(String msg) {
        try { manager.saveToDefault(); statusBar.setText(msg); }
        catch (Exception e) { statusBar.setText("Errore salvataggio."); }
    }

    private HBox createStatusBar() {
        statusBar = new Label("Pronto.");
        statusBar.setTextFill(Color.web("#94a3b8"));
        
        Label copyrightLabel = new Label("© 2026 MyAnimeDesk");
        copyrightLabel.setTextFill(Color.web("#64748b"));
        copyrightLabel.setFont(Font.font("Segoe UI", FontWeight.BOLD, 12));

        Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox footer = new HBox(statusBar, spacer, copyrightLabel);
        footer.setAlignment(Pos.CENTER_LEFT);
        footer.setPadding(new Insets(10, 20, 10, 20));
        footer.setStyle("-fx-background-color: #040814; -fx-border-color: rgba(255,255,255,0.04); -fx-border-width: 1 0 0 0;");
        return footer;
    }
}