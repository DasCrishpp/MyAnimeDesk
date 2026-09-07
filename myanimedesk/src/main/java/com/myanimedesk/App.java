package com.myanimedesk;

import com.fasterxml.jackson.databind.*;
import javafx.animation.*;
import javafx.application.*;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.concurrent.Worker;
import javafx.geometry.*;
import javafx.scene.*;
import javafx.scene.control.*;
import javafx.scene.image.*;
import javafx.scene.input.*;
import javafx.scene.layout.*;
import javafx.scene.paint.Color;
import javafx.scene.web.WebView;
import javafx.stage.*;
import javafx.util.Duration;
import javafx.util.StringConverter;

import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

import static com.myanimedesk.Ui.*;

/** JavaFX presentation. Library rules and persistence live in AnimeListManager. */
public class App extends Application {
    private static final String VERSION = "0.4.0";
    private static final String RELEASES = "https://github.com/DasCrishpp/MyAnimeDesk/releases";
    private final Path profile = Path.of(System.getProperty("user.home"), ".myanimedesk");
    private final Preferences prefs = new Preferences(profile.resolve("app.properties"));
    private final AnimeListManager library = new AnimeListManager(profile.resolve("library.json"));
    private final ExecutorService workers = Executors.newFixedThreadPool(3, task -> {
        Thread thread = new Thread(task, "myanimedesk-data"); thread.setDaemon(true); return thread;
    });
    private final Set<Future<?>> pending = ConcurrentHashMap.newKeySet();
    private final Map<Integer, Anime> detailCache = new LinkedHashMap<>(32, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<Integer, Anime> e) { return size() > 32; }
    };
    private AniListClient api;
    private CoverCache covers;
    private Stage window;
    private StackPane root;
    private BorderPane shell;
    private FileChannel lockChannel;
    private FileLock instanceLock;
    private volatile boolean closed;
    private boolean ready;
    private Route route = Route.HOME;
    private Timeline carousel;
    private PauseTransition searchDelay;
    private int pageGeneration, searchGeneration;
    private final Deque<StackPane> modals = new ArrayDeque<>();
    private final Deque<Anime> detailHistory = new ArrayDeque<>();
    private StackPane detailModal;
    private VBox detailBody;
    private Anime currentDetail;
    private int detailGeneration;
    private LibraryQuery.Filter libraryFilter = LibraryQuery.Filter.ALL;
    private LibraryQuery.Order libraryOrder = LibraryQuery.Order.RECENT;
    private String libraryGenre, libraryFolder, librarySearch = "";
    private boolean advanced;
    private Runnable updateLibraryGrid;

    private enum Route { HOME, LIBRARY, RANKING, DISCOVER, SETTINGS }
    public static void main(String[] args) { launch(args); }
    private boolean en() { return prefs.language().equals("en"); }
    private String t(String it, String en) { return en() ? en : it; }
    private String number(double value) { return String.format(en() ? Locale.UK : Locale.ITALY, "%.1f", value); }
    private String genre(String value) { return Texts.genre(value, en()); }
    private String meta(String value) { return Texts.metadata(value, en()); }
    private String status(Anime.Status value) {
        return switch (value) {
            case TO_WATCH -> t("Da vedere", "Plan to watch"); case WATCHING -> t("In visione", "Watching");
            case WATCHED -> t("Visto", "Completed"); case DROPPED -> t("Droppato", "Dropped");
        };
    }
    private String routeName(Route value) {
        return switch (value) {
            case HOME -> "Dashboard"; case LIBRARY -> t("La Mia Lista", "My Library");
            case RANKING -> t("Classifica", "Ranking"); case DISCOVER -> t("Esplora e Scopri", "Explore & Discover");
            case SETTINGS -> t("Impostazioni", "Settings");
        };
    }

    @Override public void start(Stage stage) {
        window = stage;
        try {
            Files.createDirectories(profile);
            lockChannel = FileChannel.open(profile.resolve("app.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            instanceLock = lockChannel.tryLock();
            if (instanceLock == null) throw new IOException("ALREADY_RUNNING");
            prefs.load();
        } catch (Exception e) {
            Alert error = new Alert(Alert.AlertType.ERROR,
                "ALREADY_RUNNING".equals(e.getMessage())
                    ? "MyAnimeDesk è già aperto / MyAnimeDesk is already running."
                    : "Impossibile aprire il profilo / Cannot open the profile:\n" + e.getMessage());
            error.showAndWait(); shutdown(); Platform.exit(); return;
        }
        api = createApi(profile);
        covers = new CoverCache(profile.resolve("image_cache"));
        root = new StackPane(); root.getStyleClass().add("app-root");
        Scene scene = new Scene(root, 1280, 820);
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/com/myanimedesk/desk.css")).toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ESCAPE && !modals.isEmpty()) { closeTopModal(); event.consume(); }
        });
        stage.setScene(scene);
        stage.setTitle("MyAnimeDesk" + System.getProperty("myanimedesk.windowSuffix", ""));
        stage.setMinWidth(1150); stage.setMinHeight(780);
        stage.setOnHidden(e -> { shutdown(); Platform.exit(); });
        stage.iconifiedProperty().addListener((obs, old, value) -> syncCarousel());
        stage.focusedProperty().addListener((obs, old, value) -> syncCarousel());
        Platform.setImplicitExit(true);
        loadingScreen();
        stage.show();
        async(() -> { library.loadFromDefault(); return true; }, root, ignored -> {
            ready = true; navigate(Route.HOME);
            if (!prefs.onboarded()) onboarding(false);
        }, failure -> {
            root.getChildren().setAll(panel(label(t("Il salvataggio ha bisogno di attenzione", "Your save needs attention"), "title"),
                label(t("Non ho sovrascritto la tua lista. Controlla library.json nella cartella del profilo o ripristina una copia valida.",
                    "Your library has not been overwritten. Check library.json in your profile folder or restore a valid backup."), "body"),
                label(failure.getMessage(), "muted"), button(t("Chiudi", "Close"), "primary", stage::close)));
        });
    }

    private void loadingScreen() {
        VBox loading = new VBox(18);
        loading.setAlignment(Pos.CENTER); loading.setMaxWidth(560);
        Label emblem = label("✦", "loading-emblem");
        ProgressIndicator activity = new ProgressIndicator(); activity.setPrefSize(30, 30);
        loading.getChildren().addAll(emblem, label("MyAnimeDesk", "hero-title"),
            label(t("La tua prossima storia comincia qui.", "Your next story starts here."), "subtitle"),
            activity, label(t("Apro la tua collezione…", "Opening your collection…"), "muted"));
        root.getChildren().setAll(loading);
        entrance(loading, 0);
    }
    AniListClient createApi(Path directory) { return new AniListClient(directory); }

    private void navigate(Route next) {
        if (!ready) return;
        closeAllModals();
        route = next; pageGeneration++; searchGeneration++;
        if (carousel != null) { carousel.stop(); carousel = null; }
        if (searchDelay != null) searchDelay.stop();
        covers.forgetPendingViews(); updateLibraryGrid = null;
        shell = new BorderPane(); shell.getStyleClass().add("shell");
        shell.setLeft(sidebar()); shell.setBottom(statusBar()); root.getChildren().setAll(shell);
        applyTheme();
        renderPage();
    }
    private VBox sidebar() {
        VBox sidebar = new VBox(12);
        sidebar.getStyleClass().add("sidebar"); sidebar.setPrefWidth(230);
        Label logo = label("MyAnimeDesk", "brand");
        logo.setPadding(new Insets(0, 0, 20, 10));
        sidebar.getChildren().add(logo);
        for (Route item : Route.values()) {
            String caption = item == Route.DISCOVER ? t("Scopri / Cerca", "Discover / Search") : routeName(item);
            Button nav = button(caption, route == item ? "nav-active" : "nav", () -> navigate(item));
            nav.setId("nav-" + item.name().toLowerCase(Locale.ROOT)); nav.setPrefWidth(200); nav.setMinHeight(46);
            sidebar.getChildren().add(nav);
        }
        return sidebar;
    }
    private HBox statusBar() {
        Label status = label(t("Pronto.", "Ready."), "muted");
        HBox footer = row(button(t("Aggiorna GUI", "Refresh UI"), "refresh-button", () -> {
            navigate(route);
        }), status, spacer(), label("© 2026 MyAnimeDesk", "copyright"));
        footer.getStyleClass().add("status-bar");
        return footer;
    }
    private VBox page(String title, String subtitle) {
        VBox box = new VBox(24); box.getStyleClass().add("page");
        if (title != null) box.getChildren().add(subtitle == null ? label(title, "title") : new VBox(6, label(title, "title"), label(subtitle, "subtitle")));
        return box;
    }
    private void renderPage() {
        switch (route) {
            case HOME -> home();
            case LIBRARY -> libraryPage();
            case RANKING -> rankingPage();
            case DISCOVER -> discoverPage();
            case SETTINGS -> settingsPage();
        }
    }
    private void afterLibraryChange() {
        if (route == Route.LIBRARY && updateLibraryGrid != null) updateLibraryGrid.run();
        else if (route == Route.HOME || route == Route.RANKING) renderPage();
        refreshCards(shell);
    }
    private void home() {
        VBox page = page("Dashboard", null);
        HBox stats = new HBox(20,
            dashboardStat(t("TEMPO TOTALE DI VISIONE", "TOTAL WATCH TIME"), number(library.watchedHours()) + t(" ore", " hours"), "#4d7cff"),
            dashboardStat(t("ANIME IN VISIONE", "ANIME WATCHING"), Integer.toString(library.byStatus(Anime.Status.WATCHING).size()), "#ff9f43"),
            dashboardStat(t("ANIME VISTI", "ANIME COMPLETED"), Integer.toString(library.byStatus(Anime.Status.WATCHED).size()), "#1dd1a1"));
        page.getChildren().addAll(stats, label(t("Continua a Guardare", "Continue Watching"), "section-title"));
        List<Anime> watching = library.byStatus(Anime.Status.WATCHING);
        HBox cards = new HBox(10); cards.setPadding(new Insets(10, 0, 10, 0));
        if (watching.isEmpty()) cards.getChildren().add(label(t("Nessun anime in visione al momento.", "No anime currently being watched."), "muted"));
        else for (Anime anime : watching) cards.getChildren().add(animeCard(anime));
        ScrollPane rowScroll = new ScrollPane(cards); rowScroll.setFitToHeight(true);
        rowScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        page.getChildren().add(rowScroll);
        shell.setCenter(scroll(page));
    }
    private VBox dashboardStat(String caption, String value, String accent) {
        VBox box = new VBox(8, label(caption, "dashboard-stat-caption"), label(value, "dashboard-stat-value"));
        box.setPrefWidth(260); box.getStyleClass().add("dashboard-stat");
        box.setStyle("-fx-border-color: " + accent + ";");
        return box;
    }
    private VBox stat(String value, String caption) {
        VBox stat = panel(label(value, "stat-value"), label(caption, "muted"));
        HBox.setHgrow(stat, Priority.ALWAYS); stat.setMaxWidth(Double.MAX_VALUE); return stat;
    }
    private VBox empty(String title, String body, String action, Runnable run) {
        VBox empty = panel(label("✦", "empty-icon"), label(title, "section-title"), label(body, "muted"), button(action, "primary", run));
        empty.setAlignment(Pos.CENTER); empty.getStyleClass().add("empty"); return empty;
    }

    private String filterName(LibraryQuery.Filter value) {
        return switch (value) {
            case ALL -> t("Tutti", "All"); case FAVORITES -> t("♥ Preferiti", "♥ Favourites");
            case REWATCHED -> t("↻ Rewatchati", "↻ Rewatched"); case WATCHED -> t("Visti", "Completed");
            case WATCHING -> status(Anime.Status.WATCHING); case TO_WATCH -> status(Anime.Status.TO_WATCH);
            case DROPPED -> status(Anime.Status.DROPPED);
        };
    }
    private String orderName(LibraryQuery.Order value) {
        return switch (value) {
            case RECENT -> t("Anime più recenti", "Newest anime"); case OLDEST -> t("Anime meno recenti", "Oldest anime");
            case EPISODES_DESC -> t("Più episodi", "Most episodes"); case EPISODES_ASC -> t("Meno episodi", "Fewest episodes");
            case AZ -> "A → Z"; case ZA -> "Z → A";
            case VIEWS_DESC -> t("Più volte guardati", "Most viewings"); case VIEWS_ASC -> t("Meno volte guardati", "Fewest viewings");
        };
    }
    private <T> ComboBox<T> combo(List<T> choices, T selected, Function<T, String> display) {
        ComboBox<T> box = new ComboBox<>(FXCollections.observableArrayList(choices));
        box.setEditable(false); box.setMaxWidth(Double.MAX_VALUE); box.setPrefWidth(250);
        box.setConverter(new StringConverter<>() {
            @Override public String toString(T value) { return value == null ? "" : display.apply(value); }
            @Override public T fromString(String text) { throw new UnsupportedOperationException(); }
        });
        box.setValue(selected); box.setVisibleRowCount(9); return box;
    }
    private void libraryPage() {
        VBox page = page(null, null); page.setSpacing(18);
        TextField search = new TextField(librarySearch);
        search.setPromptText(t("Cerca anime nella lista...", "Search anime in your library...")); search.setId("library-search");
        search.setPrefWidth(320); search.setMaxWidth(320);
        Button filtersButton = button(advanced ? t("CHIUDI FILTRI", "CLOSE FILTERS") : t("FILTRI", "FILTERS"), "filter-toggle", () -> {
            advanced = !advanced; libraryPage();
        });
        page.getChildren().add(row(label(routeName(Route.LIBRARY), "title"), spacer(), search));
        FlowPane chips = new FlowPane(10, 10);
        Map<LibraryQuery.Filter, Button> filterButtons = new EnumMap<>(LibraryQuery.Filter.class);
        for (var filter : LibraryQuery.Filter.values()) {
            Button chip = button("", "chip", () -> {
                libraryFilter = filter;
                if (filter == LibraryQuery.Filter.REWATCHED) libraryOrder = LibraryQuery.Order.VIEWS_DESC;
                libraryPage();
            });
            chip.setId("filter-" + filter.name().toLowerCase(Locale.ROOT)); filterButtons.put(filter, chip); chips.getChildren().add(chip);
        }
        chips.getChildren().add(filtersButton);
        page.getChildren().add(chips);
        if (advanced) {
            ComboBox<LibraryQuery.Order> order = combo(List.of(LibraryQuery.Order.values()), libraryOrder, this::orderName);
            order.setId("library-order");
            List<String> genres = new ArrayList<>(); genres.add("*");
            library.all().stream().filter(a -> a.genres != null).flatMap(a -> a.genres.stream()).distinct().sorted().forEach(genres::add);
            if (libraryGenre != null && !genres.contains(libraryGenre)) libraryGenre = null;
            ComboBox<String> genreBox = combo(genres, libraryGenre == null ? "*" : libraryGenre,
                item -> item.equals("*") ? t("Tutti i generi", "All genres") : genre(item));
            genreBox.setId("library-genre");
            order.setOnAction(e -> { libraryOrder = order.getValue(); updateLibraryGrid.run(); });
            genreBox.setOnAction(e -> { libraryGenre = "*".equals(genreBox.getValue()) ? null : genreBox.getValue(); updateLibraryGrid.run(); });
            page.getChildren().add(panel(row(new VBox(7, label(t("Ordina per", "Sort by"), "tiny"), order),
                new VBox(7, label(t("Genere", "Genre"), "tiny"), genreBox), spacer(),
                button(t("Resetta filtri avanzati", "Reset advanced filters"), "quiet", () -> {
                    libraryGenre = null; libraryOrder = LibraryQuery.Order.RECENT; libraryPage();
                }))));
        }
        FlowPane folders = new FlowPane(8, 8);
        folders.getChildren().add(button(t("Tutte le cartelle", "All folders"), libraryFolder == null ? "chip-active" : "chip", () -> {
            libraryFolder = null; libraryPage();
        }));
        for (var folder : library.folders().entrySet()) {
            Button item = button("▤ " + folder.getValue(), folder.getKey().equals(libraryFolder) ? "chip-active" : "chip", () -> {
                libraryFolder = folder.getKey(); libraryPage();
            });
            ContextMenu menu = new ContextMenu();
            MenuItem rename = new MenuItem(t("Rinomina", "Rename")); rename.setOnAction(e -> folderNameDialog(folder.getKey(), null));
            MenuItem remove = new MenuItem(t("Elimina cartella", "Delete folder")); remove.setOnAction(e -> deleteFolder(folder.getKey()));
            menu.getItems().addAll(rename, remove); item.setContextMenu(menu);
            folders.getChildren().add(item);
        }
        folders.getChildren().add(button("+ " + t("Nuova cartella", "New folder"), "quiet", () -> folderNameDialog(null, null)));
        if (libraryFolder != null) {
            folders.getChildren().add(button(t("Rinomina", "Rename"), "quiet", () -> folderNameDialog(libraryFolder, null)));
            folders.getChildren().add(button(t("Elimina cartella", "Delete folder"), "danger", () -> deleteFolder(libraryFolder)));
        }
        page.getChildren().add(folders);
        Label results = label("", "muted");
        VBox collection = new VBox(10);
        ScrollPane scrolling = scroll(collection);
        page.getChildren().addAll(results, scrolling);
        updateLibraryGrid = () -> {
            for (var entry : filterButtons.entrySet()) {
                long count = library.all().stream().filter(a -> LibraryQuery.matches(a, entry.getKey())).count();
                Button chip = entry.getValue();
                chip.setText(filterName(entry.getKey()).toUpperCase(en() ? Locale.ENGLISH : Locale.ITALIAN) + " (" + count + ")");
                chip.getStyleClass().removeAll("chip", "chip-active");
                chip.getStyleClass().add(entry.getKey() == libraryFilter ? "chip-active" : "chip");
            }
            List<Anime> selected = LibraryQuery.select(library.all(), libraryFilter, libraryOrder, libraryGenre, libraryFolder, librarySearch);
            results.setText(selected.size() + " anime  ·  " + orderName(libraryOrder));
            collection.getChildren().clear();
            if (selected.isEmpty()) collection.getChildren().add(empty(t("Qui non ci sono ancora anime", "No anime here yet"),
                t("Prova altri filtri o aggiungi anime da Esplora.", "Try other filters or add anime from Explore."),
                t("Esplora", "Explore"), () -> navigate(Route.DISCOVER)));
            else pagedLocalCards(collection, selected, 0);
        };
        search.textProperty().addListener((obs, old, value) -> { librarySearch = value; updateLibraryGrid.run(); });
        updateLibraryGrid.run();
        shell.setCenter(page);
    }
    private void pagedLocalCards(VBox target, List<Anime> anime, int offset) {
        int end = Math.min(offset + 48, anime.size());
        target.getChildren().add(cardGrid(anime.subList(offset, end), true));
        if (end < anime.size()) {
            Button more = button(t("Mostra altri", "Show more"), "quiet", () -> {});
            more.setOnAction(e -> { target.getChildren().remove(more); pagedLocalCards(target, anime, end); });
            target.getChildren().add(more);
        }
    }
    private TilePane cardGrid(List<Anime> anime, boolean animate) {
        TilePane grid = new TilePane(10, 10); grid.setPrefColumns(5);
        int index = 0;
        for (Anime item : anime) {
            Node card = animeCard(item); grid.getChildren().add(card);
            if (animate) entrance(card, index++);
        }
        return grid;
    }
    private Node animeCard(Anime anime) {
        StackPane cover = image(covers, anime.coverImage, 200, 280, 20);
        Label state = label("", "status");
        StackPane.setAlignment(state, Pos.TOP_LEFT); StackPane.setMargin(state, new Insets(12));
        Button heart = button("", "heart", () -> {});
        heart.setId("favourite-" + anime.id);
        StackPane.setAlignment(heart, Pos.TOP_RIGHT); StackPane.setMargin(heart, new Insets(10));
        cover.getChildren().addAll(state, heart);
        Label name = label(anime.title, "card-title"); name.setMinHeight(42); name.setMaxHeight(42);
        Label info = label(meta(anime.format) + "  ·  " + (anime.episodes > 0 ? anime.episodes + " ep" : t("Episodi N/D", "Episodes N/A")), "tiny");
        VBox footer = new VBox(6, name, info); footer.getStyleClass().add("card-footer");
        VBox card = new VBox(cover, footer); card.setPrefWidth(200); card.setMaxWidth(200);
        card.getStyleClass().add("anime-card"); card.setId("anime-" + anime.id);
        card.setFocusTraversable(true); card.setAccessibleRole(AccessibleRole.BUTTON); card.setAccessibleText(anime.title);
        Runnable update = () -> {
            Anime saved = library.findById(anime.id);
            boolean favourite = saved != null && saved.favorite;
            heart.setText(favourite ? "♥" : "♡"); heart.setAccessibleText(t("Preferito: ", "Favourite: ") + anime.title);
            heart.getStyleClass().remove("heart-selected"); if (favourite) heart.getStyleClass().add("heart-selected");
            state.setVisible(saved != null);
            state.setText(saved == null ? "" : (saved.viewCount() > 1 ? "↻ " + saved.viewCount() + " · " : "") + status(saved.status));
            state.getStyleClass().removeIf(s -> s.startsWith("status-"));
            if (saved != null) state.getStyleClass().add("status-" + saved.status.name().toLowerCase(Locale.ROOT));
        };
        update.run(); card.getProperties().put("refreshCard", update);
        heart.setOnAction(e -> {
            if (mutate(() -> { ensureSaved(anime); library.toggleFavorite(library.findById(anime.id)); })) {
                update.run();
                ScaleTransition pulse = new ScaleTransition(Duration.millis(120), heart);
                pulse.setToX(1.25); pulse.setToY(1.25); pulse.setAutoReverse(true); pulse.setCycleCount(2); pulse.play();
                afterLibraryChange();
            }
            e.consume();
        });
        heart.addEventHandler(MouseEvent.MOUSE_CLICKED, MouseEvent::consume);
        card.setOnMouseClicked(e -> { if (e.getButton() == MouseButton.PRIMARY) showDetails(anime, false); });
        card.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) { showDetails(anime, false); e.consume(); } });
        ContextMenu menu = new ContextMenu();
        for (Anime.Status value : Anime.Status.values()) {
            MenuItem item = new MenuItem(status(value)); item.setOnAction(e -> changeStatus(anime, value)); menu.getItems().add(item);
        }
        MenuItem folder = new MenuItem(t("Organizza in cartelle", "Organise in folders")); folder.setOnAction(e -> folderMembership(anime));
        menu.getItems().add(folder);
        card.setOnContextMenuRequested(e -> { menu.show(card, e.getScreenX(), e.getScreenY()); e.consume(); });
        // No detached hover windows: wheel scrolling always closes the quick menu.
        card.addEventFilter(ScrollEvent.SCROLL, e -> menu.hide());
        card.sceneProperty().addListener((obs, old, scene) -> { if (scene == null) menu.hide(); });
        return card;
    }

    private void refreshCards(Node node) {
        Object action = node.getProperties().get("refreshCard");
        if (action instanceof Runnable update) update.run();
        if (node instanceof Parent parent) for (Node child : parent.getChildrenUnmodifiable()) refreshCards(child);
    }
    private boolean mutate(Runnable action) {
        try { library.transaction(action); return true; }
        catch (Exception e) {
            message(t("Modifica non salvata", "Change not saved"),
                t("I dati precedenti sono stati mantenuti. Controlla i valori e che la cartella del profilo sia scrivibile.",
                    "Your previous data has been preserved. Check the values and that your profile folder is writable."));
            return false;
        }
    }
    private void ensureSaved(Anime anime) {
        if (library.findById(anime.id) == null) {
            Anime fresh = new Anime(); fresh.id = anime.id; copyMetadata(anime, fresh); library.add(fresh);
        }
    }
    private void changeStatus(Anime anime, Anime.Status value) {
        Anime saved = library.findById(anime.id);
        boolean ask = value == Anime.Status.WATCHED && (saved == null || saved.status != value) && (saved == null || !saved.rated());
        if (mutate(() -> { ensureSaved(anime); library.updateStatus(anime.id, value); })) {
            afterLibraryChange(); refreshDetail();
            if (ask) ratingDialog(library.findById(anime.id), true);
        }
    }
    private void folderNameDialog(String id, Runnable after) {
        TextField name = new TextField(id == null ? "" : library.folders().get(id));
        name.setPromptText(t("Es. Da riguardare insieme", "e.g. Watch again together"));
        name.setTextFormatter(new TextFormatter<String>(change -> change.getControlNewText().length() <= 60 ? change : null));
        Label error = label("", "error");
        VBox body = new VBox(15, label(t("Da 1 a 60 caratteri. Ogni cartella deve avere un nome diverso.", "1–60 characters. Each folder needs a unique name."), "muted"), name, error);
        StackPane dialog = modal(id == null ? t("Nuova cartella", "New folder") : t("Rinomina cartella", "Rename folder"), body, 540, true);
        Button save = button(t("Salva", "Save"), "primary", () -> {
            String trimmed = name.getText().strip();
            if (trimmed.isEmpty() || trimmed.chars().anyMatch(Character::isISOControl) ||
                library.folders().entrySet().stream().anyMatch(e -> !e.getKey().equals(id) && e.getValue().equalsIgnoreCase(trimmed))) {
                error.setText(t("Scegli un nome valido e non già usato.", "Choose a valid, unused name.")); return;
            }
            if (mutate(() -> { if (id == null) library.createFolder(trimmed); else library.renameFolder(id, trimmed); })) {
                closeModal(dialog); if (route == Route.LIBRARY) libraryPage(); if (after != null) after.run();
            }
        });
        body.getChildren().add(save); name.setOnAction(e -> save.fire()); Platform.runLater(name::requestFocus);
    }
    private void deleteFolder(String id) {
        confirm(t("Eliminare la cartella?", "Delete this folder?"),
            t("Gli anime resteranno nella tua lista e nelle altre cartelle.", "Your anime will remain in your library and other folders."), () -> {
                if (mutate(() -> library.removeFolder(id))) { if (Objects.equals(libraryFolder, id)) libraryFolder = null; libraryPage(); refreshDetail(); }
            });
    }
    private void folderMembership(Anime anime) {
        VBox choices = new VBox(12);
        Anime saved = library.findById(anime.id);
        Set<String> selected = new LinkedHashSet<>(saved == null ? Set.of() : saved.folderIds);
        Map<String, CheckBox> checks = new LinkedHashMap<>();
        library.folders().forEach((id, name) -> {
            CheckBox choice = new CheckBox(name); choice.setSelected(selected.contains(id)); checks.put(id, choice); choices.getChildren().add(choice);
        });
        if (checks.isEmpty()) choices.getChildren().add(label(t("Crea una cartella per organizzare questo anime.", "Create a folder to organise this anime."), "muted"));
        String currentFolders = selected.stream().map(library.folders()::get).filter(Objects::nonNull)
            .reduce((a, b) -> a + "  ·  " + b).orElse(t("Nessuna cartella", "No folders"));
        VBox current = new VBox(5, label(t("ATTUALMENTE IN", "CURRENTLY IN"), "eyebrow"), label(currentFolders, "folder-names"));
        current.getStyleClass().add("folder-membership");
        VBox body = new VBox(18, current, label(t("Puoi scegliere più cartelle: l'anime e le ore vengono contati una sola volta.",
            "Choose multiple folders: the anime and its hours are still counted only once."), "muted"), scroll(choices));
        StackPane dialog = modal(t("Organizza in cartelle", "Organise in folders"), body, 580, true);
        body.getChildren().add(row(button("+ " + t("Nuova cartella", "New folder"), "quiet", () ->
            folderNameDialog(null, () -> { closeModal(dialog); folderMembership(anime); })),
            spacer(), button(t("Salva", "Save"), "primary", () -> {
                Set<String> ids = new LinkedHashSet<>(); checks.forEach((id, check) -> { if (check.isSelected()) ids.add(id); });
                if (mutate(() -> { ensureSaved(anime); library.setFolders(anime.id, ids); })) {
                    closeModal(dialog); afterLibraryChange(); refreshDetail();
                }
            })));
    }


    private void showDetails(Anime anime, boolean related) {
        if (detailModal == null) {
            detailBody = new VBox(16);
            detailModal = modal(t("Scheda anime", "Anime details"), detailBody, 1100, true);
        } else if (related && currentDetail != null) detailHistory.push(currentDetail);
        else if (!related) detailHistory.clear();
        currentDetail = library.findById(anime.id);
        if (currentDetail == null) currentDetail = detailCache.getOrDefault(anime.id, anime);
        int generation = ++detailGeneration;
        renderDetail(false);
        if (!currentDetail.detailsLoaded) {
            Label loading = label(t("Carico trama, cast e collegamenti…", "Loading synopsis, cast and related titles…"), "muted");
            detailBody.getChildren().add(0, loading);
            Anime requested = currentDetail;
            async(() -> api.getAnimeDetails(requested), detailBody, full -> {
                if (generation != detailGeneration) return;
                if (full == null) { detailFailure(); return; }
                detailCache.put(full.id, full);
                Anime saved = library.findById(full.id);
                if (saved != null) { copyMetadata(full, saved); currentDetail = saved; }
                else currentDetail = full;
                renderDetail(false);
            }, error -> { if (generation == detailGeneration) detailFailure(); });
        }
    }
    private void detailFailure() {
        renderDetail(false);
        detailBody.getChildren().add(0, row(label(t("Informazioni online non disponibili. La tua lista funziona anche offline.",
            "Online details are unavailable. Your library still works offline."), "muted"),
            button(t("Riprova", "Retry"), "quiet", () -> showDetails(currentDetail, false))));
    }
    private void copyMetadata(Anime from, Anime to) {
        to.malId = from.malId; to.kitsuId = from.kitsuId; to.provider = from.provider;
        to.title = from.title; to.coverImage = from.coverImage; to.episodes = from.episodes; to.duration = from.duration;
        to.genres = from.genres; to.format = from.format; to.year = from.year; to.season = from.season;
        to.studio = from.studio; to.airingStatus = from.airingStatus; to.bannerImage = from.bannerImage;
        to.description = from.description; to.trailerId = from.trailerId; to.trailerSite = from.trailerSite;
        to.siteUrl = from.siteUrl; to.averageScore = from.averageScore; to.cast = from.cast; to.castPartial = from.castPartial;
        to.relations = from.relations; to.detailsLoaded = from.detailsLoaded; to.hasMoreCast = from.hasMoreCast; to.castPage = from.castPage;
    }
    private void refreshDetail() {
        if (detailModal == null || currentDetail == null) return;
        Anime saved = library.findById(currentDetail.id);
        if (saved != null) currentDetail = saved;
        renderDetail(false);
    }
    private void renderDetail(boolean ignored) {
        Anime anime = currentDetail;
        detailBody.getChildren().clear();
        if (!detailHistory.isEmpty()) {
            detailBody.getChildren().add(button("← " + t("Torna a ", "Back to ") + detailHistory.peek().title, "quiet", () -> {
                Anime back = detailHistory.pop();
                Deque<Anime> keep = new ArrayDeque<>(detailHistory);
                showDetails(back, false); detailHistory.addAll(keep); renderDetail(false);
            }));
        }
        Anime saved = library.findById(anime.id);
        VBox left = new VBox(14, image(covers, anime.coverImage, 220, 310, 22));
        left.setPrefWidth(220); left.setMaxWidth(220); left.setMinWidth(220);
        Button favourite = button(saved != null && saved.favorite ? t("♥ Nei preferiti", "♥ Favourited") : t("♡ Preferiti", "♡ Favourite"), "quiet", () -> {
            if (mutate(() -> { ensureSaved(anime); library.toggleFavorite(library.findById(anime.id)); })) { afterLibraryChange(); refreshDetail(); }
        });
        favourite.setMaxWidth(Double.MAX_VALUE);
        left.getChildren().add(favourite);
        ComboBox<Anime.Status> state = combo(List.of(Anime.Status.values()), saved == null ? null : saved.status, this::status);
        state.setPromptText(t("+ Aggiungi alla lista", "+ Add to library")); state.setId("detail-status");
        state.setOnAction(e -> { if (state.getValue() != null) changeStatus(anime, state.getValue()); });
        left.getChildren().addAll(label(t("Stato nella tua lista", "Your library status"), "tiny"), state);
        if (saved != null) {
            Spinner<Integer> views = new Spinner<>(0, AnimeListManager.MAX_VIEWS, saved.viewCount());
            views.setEditable(false); views.setId("view-count"); views.setMaxWidth(Double.MAX_VALUE);
            Button apply = button(t("Salva conteggio", "Save count"), "quiet", () -> {
                int count = views.getValue();
                if (count == 0 && library.findById(anime.id).rated()) {
                    message(t("Questo anime ha un voto", "This anime has a rating"),
                        t("Rimuovi prima il voto se vuoi azzerare tutte le visioni.", "Remove its rating before clearing all viewings.")); return;
                }
                boolean ask = saved.viewCount() == 0 && count > 0 && !saved.rated();
                if (mutate(() -> library.setViewCount(anime.id, count))) {
                    afterLibraryChange(); refreshDetail(); if (ask) ratingDialog(library.findById(anime.id), true);
                }
            });
            Button rewatch = button("+1 " + t("visione completa", "completed viewing"), "primary", () -> {
                Anime latest = library.findById(anime.id);
                boolean ask = latest.viewCount() == 0 && !latest.rated();
                if (mutate(() -> library.setViewCount(anime.id, latest.viewCount() + 1))) {
                    afterLibraryChange(); refreshDetail(); if (ask) ratingDialog(library.findById(anime.id), true);
                }
            });
            rewatch.setDisable(saved.viewCount() >= AnimeListManager.MAX_VIEWS);
            rewatch.setMaxWidth(Double.MAX_VALUE);
            left.getChildren().addAll(new Separator(), label(t("Visioni complete", "Completed viewings"), "tiny"),
                views, apply, rewatch,
                label(number(saved.watchedHours()) + t(" ore guardate", " hours watched"), "accent-text"));
            Button organise = button(t("▤ Organizza in cartelle", "▤ Organise in folders"), "quiet", () -> folderMembership(anime));
            organise.setMaxWidth(Double.MAX_VALUE);
            String folders = saved.folderIds.stream().map(library.folders()::get).filter(Objects::nonNull)
                .reduce((a,b) -> a + "\n" + b).orElse(t("Nessuna cartella", "No folders"));
            VBox folderStatus = new VBox(4, label(t("NELLE CARTELLE", "IN FOLDERS"), "eyebrow"), label(folders, "folder-names"));
            folderStatus.getStyleClass().add("folder-membership");
            left.getChildren().addAll(organise, folderStatus);
            left.getChildren().add(button(t("Rimuovi dalla lista", "Remove from library"), "danger", () ->
                confirm(t("Rimuovere questo anime?", "Remove this anime?"),
                    t("Verranno rimossi anche le sue visioni, le cartelle associate e il voto.", "Its viewings, folder memberships and rating will also be removed."), () -> {
                        if (mutate(() -> library.remove(anime.id))) { closeModal(detailModal); afterLibraryChange(); }
                    })));
        }
        VBox right = new VBox(18, label(anime.title, "detail-title"),
            label(meta(anime.format) + "  ·  " + meta(anime.year) + "  ·  " + meta(anime.season) + "  ·  " + meta(anime.airingStatus), "subtitle"));
        right.setMinWidth(0); HBox.setHgrow(right, Priority.ALWAYS);
        FlowPane tags = new FlowPane(7, 7);
        if (anime.genres != null) for (String value : anime.genres) tags.getChildren().add(label(genre(value), "tag"));
        right.getChildren().add(tags);
        String scoreSource = "JIKAN".equalsIgnoreCase(anime.provider) ? t("media MyAnimeList", "MyAnimeList average")
            : "KITSU".equalsIgnoreCase(anime.provider) ? t("media Kitsu", "Kitsu average") : t("media AniList", "AniList average");
        right.getChildren().add(row(stat(anime.episodes > 0 ? String.valueOf(anime.episodes) : "—", t("episodi", "episodes")),
            stat(anime.duration > 0 ? anime.duration + " min" : "—", t("per episodio", "per episode")),
            stat(anime.averageScore > 0 ? anime.averageScore + "%" : "—", scoreSource)));
        right.getChildren().add(label("Studio  ·  " + meta(anime.studio), "muted"));
        HBox external = row();
        String trailer = trailerUrl(anime);
        Button trailerButton = button("▶ " + t("Guarda il trailer", "Watch trailer"), "primary", () -> showTrailer(anime));
        trailerButton.setDisable(trailer == null);
        trailerButton.setTooltip(new Tooltip(trailer == null ? t("Trailer non disponibile", "No trailer available")
            : t("Riproduci direttamente nell'app", "Play directly in the app")));
        boolean fromMal = "JIKAN".equalsIgnoreCase(anime.provider), fromKitsu = "KITSU".equalsIgnoreCase(anime.provider);
        String sourceButton = fromMal ? t("Apri su MyAnimeList", "View on MyAnimeList")
            : fromKitsu ? t("Apri su Kitsu", "View on Kitsu") : t("Apri su AniList", "View on AniList");
        external.getChildren().addAll(trailerButton, button(sourceButton,
            "quiet", () -> openUrl(animePageUrl(anime))));
        right.getChildren().add(external);
        right.getChildren().add(label(t("Trama", "Synopsis"), "section-title"));
        String synopsis = Texts.synopsis(anime.description);
        right.getChildren().add(label(synopsis.isEmpty() ? t("Trama non disponibile.", "No synopsis available.") : synopsis, "synopsis"));
        right.getChildren().add(label(t("Trama e nomi provengono da AniList, Kitsu o MyAnimeList. Il trailer viene riprodotto nell'app.",
            "Synopsis and names come from AniList, Kitsu or MyAnimeList. The trailer plays inside the app."), "tiny"));
        if (saved != null && saved.viewCount() > 0) {
            right.getChildren().add(row(label(t("Il tuo voto", "Your rating"), "section-title"), spacer(),
                button(saved.rated() ? t("Modifica voti", "Edit ratings") : t("Vota questo anime", "Rate this anime"), "primary", () -> ratingDialog(library.findById(anime.id), false))));
            if (saved.rated()) right.getChildren().add(ratingBreakdown(saved));
            else right.getChildren().add(label(t("Senza voto: questo anime non compare in classifica.", "Not rated: this anime is not in your ranking."), "muted"));
        }
        VBox cast = new VBox(14);
        TitledPane castSection = new TitledPane(t("Personaggi e doppiatori giapponesi", "Characters & Japanese voice cast"), cast);
        castSection.setExpanded(false);
        boolean[] castBuilt = {false};
        castSection.expandedProperty().addListener((obs, old, expanded) -> {
            if (expanded && !castBuilt[0]) { castBuilt[0] = true; renderCast(anime, cast); }
        });
        right.getChildren().add(castSection);
        VBox related = new VBox(14);
        TitledPane relatedSection = new TitledPane(t("Sequel, prequel e anime collegati", "Sequels, prequels & related anime"), related);
        relatedSection.setExpanded(false);
        boolean[] relatedBuilt = {false};
        relatedSection.expandedProperty().addListener((obs, old, expanded) -> {
            if (expanded && !relatedBuilt[0]) {
                relatedBuilt[0] = true;
                if (anime.relations.isEmpty()) related.getChildren().add(label(t("Nessun anime collegato disponibile.", "No related anime available."), "muted"));
                for (Anime.Relation relation : anime.relations) {
                    if (relation.anime == null || relation.anime.id <= 0) continue;
                    HBox entry = row(image(covers, relation.anime.coverImage, 60, 84, 10),
                        new VBox(5, label(Texts.relation(relation.type, en()) + " · " + meta(relation.anime.format), "accent-text"),
                            label(relation.anime.title, "body")), spacer(),
                        button("→", "quiet", () -> showDetails(relation.anime, true)));
                    entry.getStyleClass().add("list-row"); entry.setOnMouseClicked(e -> showDetails(relation.anime, true));
                    related.getChildren().add(entry);
                }
            }
        });
        right.getChildren().add(relatedSection);
        HBox columns = new HBox(28, left, right); columns.setAlignment(Pos.TOP_LEFT);
        detailBody.getChildren().add(scroll(columns));
    }
    static String trailerUrl(Anime anime) {
        String id = anime.trailerId;
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) return null;
        if ("youtube".equalsIgnoreCase(anime.trailerSite)) return "https://www.youtube.com/watch?v=" + id;
        if ("dailymotion".equalsIgnoreCase(anime.trailerSite)) return "https://www.dailymotion.com/video/" + id;
        return null;
    }
    static String trailerEmbedUrl(Anime anime) {
        String id = anime.trailerId;
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,100}")) return null;
        if ("youtube".equalsIgnoreCase(anime.trailerSite)) return "https://www.youtube-nocookie.com/embed/" + id + "?rel=0";
        if ("dailymotion".equalsIgnoreCase(anime.trailerSite)) return "https://www.dailymotion.com/embed/video/" + id;
        return null;
    }
    private String animePageUrl(Anime anime) {
        if (("JIKAN".equalsIgnoreCase(anime.provider) || "KITSU".equalsIgnoreCase(anime.provider))
                && anime.siteUrl != null && anime.siteUrl.startsWith("https://")) return anime.siteUrl;
        return "https://anilist.co/anime/" + anime.id;
    }
    private void showTrailer(Anime anime) {
        String embed = trailerEmbedUrl(anime), browser = trailerUrl(anime);
        if (embed == null || browser == null) return;
        WebView player = new WebView();
        player.setContextMenuEnabled(false); player.setPrefSize(860, 484); player.setMinHeight(360);
        ProgressIndicator loading = new ProgressIndicator(); loading.setMaxSize(42, 42);
        Label error = label(t("Il player non è riuscito a caricare il trailer.", "The player could not load the trailer."), "muted");
        Button browserButton = button(t("Apri nel browser", "Open in browser"), "quiet", () -> openUrl(browser));
        VBox failure = new VBox(14, error, browserButton); failure.setAlignment(Pos.CENTER);
        failure.setVisible(false); failure.setManaged(false);
        StackPane frame = new StackPane(player, loading, failure); frame.getStyleClass().add("trailer-frame");
        VBox body = new VBox(14, frame, label(t("Se il servizio video impedisce la riproduzione incorporata, puoi aprirlo nel browser.",
            "If the video service blocks embedded playback, you can open it in your browser."), "tiny"));
        VBox.setVgrow(frame, Priority.ALWAYS);
        StackPane dialog = modal(t("Trailer · ", "Trailer · ") + anime.title, body, 940, true);
        player.getEngine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            loading.setVisible(state == Worker.State.RUNNING || state == Worker.State.SCHEDULED);
            if (state == Worker.State.FAILED || state == Worker.State.CANCELLED) {
                failure.setManaged(true); failure.setVisible(true);
            }
        });
        dialog.sceneProperty().addListener((obs, old, scene) -> { if (scene == null) player.getEngine().load("about:blank"); });
        player.getEngine().load(embed);
    }
    private void renderCast(Anime anime, VBox box) {
        box.getChildren().clear();
        if (anime.cast.isEmpty()) box.getChildren().add(label(t("Cast non disponibile.", "No cast available."), "muted"));
        for (Anime.CastMember member : anime.cast) {
            HBox row = row(image(covers, member.image, 52, 72, 8),
                new VBox(4, label(member.name, "body"), label("MAIN".equals(member.role) ? t("Protagonista", "Main character") : t("Personaggio secondario", "Supporting character"), "tiny")),
                spacer(), new VBox(4, label(member.voiceActor == null || member.voiceActor.isBlank() ? "—" : member.voiceActor, "body"),
                    label(t("Voce giapponese", "Japanese voice"), "tiny")), image(covers, member.voiceImage, 44, 60, 8));
            row.getStyleClass().add("list-row"); box.getChildren().add(row);
        }
        if (anime.castPartial && anime.malId > 0) {
            Button retryCast = button(t("Riprova doppiatori", "Retry voice actors"), "quiet", () -> {});
            retryCast.setOnAction(e -> {
                retryCast.setDisable(true); retryCast.setText(t("Caricamento doppiatori…", "Loading voice actors…"));
                async(() -> api.retryAlternativeCast(anime), box, loaded -> {
                    anime.cast = loaded.cast; anime.castPartial = false; renderCast(anime, box);
                }, error -> {
                    retryCast.setDisable(false);
                    retryCast.setText(t("Servizio occupato · Riprova", "Service busy · Retry"));
                });
            });
            box.getChildren().add(new VBox(7, label(t("I personaggi sono disponibili; i nomi dei doppiatori sono momentaneamente incompleti.",
                "Characters are available; voice actor names are temporarily incomplete."), "tiny"), retryCast));
            Platform.runLater(retryCast::fire);
        }
        if (anime.hasMoreCast) {
            Button more = button(t("Carica altri personaggi", "Load more characters"), "quiet", () -> {});
            more.setOnAction(e -> {
                more.setDisable(true);
                int nextPage = anime.castPage + 1;
                async(() -> api.loadCastPage(anime.id, nextPage), box, next -> {
                    Set<Integer> known = new HashSet<>(); anime.cast.forEach(member -> known.add(member.id));
                    for (Anime.CastMember member : next.cast) if (known.add(member.id)) anime.cast.add(member);
                    anime.hasMoreCast = next.hasMoreCast; anime.castPage = nextPage; renderCast(anime, box);
                }, error -> { more.setDisable(false); more.setText(t("Connessione non disponibile · Riprova", "Connection unavailable · Retry")); });
            });
            box.getChildren().add(more);
        }
    }
    private VBox ratingBreakdown(Anime anime) {
        VBox box = panel(row(label(number(anime.overall()), "stat-value"), label("OVERALL", "tiny")));
        for (RatingCategory category : RatingCategory.values()) {
            Label help = label("?", "help"); help.setTooltip(new Tooltip(en() ? category.helpEn : category.helpIt));
            ProgressBar progress = new ProgressBar(anime.ratings.get(category.name()) / 12.0);
            progress.setPrefWidth(120);
            box.getChildren().add(row(label(en() ? category.en : category.it, "body"), help, spacer(), progress,
                label(Integer.toString(anime.ratings.get(category.name())), "score")));
        }
        return box;
    }
    private void ratingDialog(Anime anime, boolean optional) {
        VBox body = new VBox(16, label(t("Da 0 a 10 per categoria. Puoi usare un solo 12 come voto speciale. L'overall è la media dei dieci voti.",
            "Score each category from 0 to 10. You may use one special 12. Overall is the average of all ten scores."), "muted"));
        VBox fields = new VBox(10); Map<RatingCategory, ComboBox<Integer>> controls = new EnumMap<>(RatingCategory.class);
        for (RatingCategory category : RatingCategory.values()) {
            List<Integer> scores = new ArrayList<>(); for (int n = 0; n <= 10; n++) scores.add(n); scores.add(12);
            ComboBox<Integer> score = combo(scores, anime.ratings.get(category.name()), Object::toString);
            score.setPromptText("—"); score.setPrefWidth(84); score.setMinWidth(84); score.setMaxWidth(84); score.setId("rating-" + category.name());
            Label help = label("?", "help"); help.setTooltip(new Tooltip(en() ? category.helpEn : category.helpIt));
            Label caption = label(en() ? category.en : category.it, "body");
            fields.getChildren().add(row(caption, help, spacer(), score)); controls.put(category, score);
        }
        Label overall = label("", "accent-text"); Label validation = label("", "error");
        body.getChildren().addAll(scroll(fields), overall, validation);
        StackPane dialog = modal(t("Il tuo voto · ", "Your rating · ") + anime.title, body, 660, true);
        Button save = button(t("Salva in classifica", "Save to ranking"), "primary", () -> {
            Map<String,Integer> scores = new LinkedHashMap<>(); controls.forEach((key, box) -> scores.put(key.name(), box.getValue()));
            try { RatingCategory.validate(scores); }
            catch (IllegalArgumentException e) { validation.setText(t("Completa tutti i voti: 0–10 e al massimo un 12.", "Complete all scores: 0–10 and at most one 12.")); return; }
            if (mutate(() -> library.setRating(anime.id, scores))) { closeModal(dialog); afterLibraryChange(); refreshDetail(); }
        });
        save.setId("save-rating");
        HBox actions = row();
        if (anime.rated()) actions.getChildren().add(button(t("Rimuovi voto", "Remove rating"), "danger", () ->
            confirm(t("Rimuovere il voto?", "Remove this rating?"), t("L'anime uscirà dalla classifica. Le visioni non cambieranno.",
                "This anime will leave your ranking. Its viewings will not change."), () -> {
                    if (mutate(() -> library.setRating(anime.id, Map.of()))) { closeModal(dialog); afterLibraryChange(); refreshDetail(); }
                })));
        actions.getChildren().addAll(spacer(), button(optional ? t("Non ora", "Not now") : t("Annulla", "Cancel"), "quiet", () -> closeModal(dialog)), save);
        body.getChildren().add(actions);
        boolean[] updating = {false};
        Runnable update = () -> {
            if (updating[0]) return; updating[0] = true;
            boolean bonusUsed = controls.values().stream().anyMatch(box -> Integer.valueOf(12).equals(box.getValue()));
            int completed = 0, sum = 0;
            for (ComboBox<Integer> box : controls.values()) {
                Integer selected = box.getValue();
                if (selected != null) { completed++; sum += selected; }
                boolean allow = !bonusUsed || Integer.valueOf(12).equals(selected);
                if (allow && !box.getItems().contains(12)) box.getItems().add(12);
                if (!allow) box.getItems().remove(Integer.valueOf(12));
            }
            save.setDisable(completed != RatingCategory.values().length);
            overall.setText(completed == 10 ? "OVERALL  " + number(sum / 10.0)
                : completed + "/10 " + t("categorie compilate", "categories completed"));
            updating[0] = false;
        };
        controls.values().forEach(box -> box.valueProperty().addListener((obs, old, value) -> update.run()));
        update.run();
    }

    private void rankingPage() {
        List<Anime> ranking = library.ranking();
        VBox page = page(routeName(Route.RANKING), t("Non il voto di tutti. Il tuo.", "Not everyone's score. Yours."));
        page.getChildren().add(label(t("Solo gli anime con una visione completa e tutti i dieci voti entrano in classifica. A parità di media, ordine alfabetico.",
            "Only anime with a completed viewing and all ten scores are ranked. Ties are sorted alphabetically."), "muted"));
        if (ranking.isEmpty()) page.getChildren().add(empty(t("Il podio aspetta le tue storie", "Your podium is waiting"),
            t("Segna un anime come visto e scegli se votarlo. Puoi farlo anche dalla sua scheda.", "Mark an anime completed and choose whether to rate it. You can also rate it from its details."),
            t("Apri la mia lista", "Open my library"), () -> navigate(Route.LIBRARY)));
        else {
            HBox podium = new HBox(18); podium.setAlignment(Pos.BOTTOM_CENTER);
            for (int position : new int[]{2, 1, 3}) {
                if (position > ranking.size()) continue;
                Anime anime = ranking.get(position - 1);
                VBox place = new VBox(12, label(position == 1 ? "★  1" : Integer.toString(position), "podium-position"),
                    image(covers, anime.coverImage, position == 1 ? 156 : 132, position == 1 ? 218 : 184, 16),
                    label(anime.title, "card-title"), label(number(anime.overall()), "stat-value"), label("OVERALL", "tiny"));
                place.getStyleClass().addAll("podium", "podium-" + position);
                place.setAlignment(Pos.CENTER); place.setPrefWidth(230); place.setMaxWidth(260);
                place.setOnMouseClicked(e -> showDetails(anime, false)); HBox.setHgrow(place, Priority.ALWAYS);
                podium.getChildren().add(place); entrance(place, position - 1);
            }
            page.getChildren().add(podium);
            for (int index = 3; index < ranking.size(); index++) {
                Anime anime = ranking.get(index);
                HBox entry = row(label(String.format("%02d", index + 1), "rank-number"), image(covers, anime.coverImage, 48, 67, 8),
                    new VBox(4, label(anime.title, "body"), label(meta(anime.format) + "  ·  " + anime.viewCount() + t(" visioni", " viewings"), "tiny")),
                    spacer(), label(number(anime.overall()), "rank-number"), button(t("Dettagli", "Details") + " →", "quiet", () -> showDetails(anime, false)));
                entry.getStyleClass().add("list-row"); page.getChildren().add(entry);
            }
            page.getChildren().add(label(t("Apri un anime per vedere o modificare il voto di ogni categoria.", "Open an anime to view or edit each category score."), "muted"));
        }
        shell.setCenter(scroll(page));
    }

    private void discoverPage() {
        VBox page = page(null, null);
        TextField search = new TextField(); search.setId("online-search");
        search.setPromptText(t("Cerca un anime, una storia, un mondo…", "Search for an anime, a story, a world…"));
        HBox.setHgrow(search, Priority.ALWAYS);
        VBox suggestions = new VBox(3); suggestions.setVisible(false); suggestions.setManaged(false); suggestions.getStyleClass().add("suggestions");
        VBox content = new VBox(26);
        page.getChildren().addAll(row(search, button(t("Cerca", "Search"), "primary", () -> onlineSearch(search.getText(), content, suggestions))), suggestions, content);
        ScrollPane scrolling = scroll(page); shell.setCenter(scrolling);
        searchDelay = new PauseTransition(Duration.millis(320));
        search.textProperty().addListener((obs, old, query) -> {
            int generation = ++searchGeneration; searchDelay.stop();
            suggestions.getChildren().clear(); suggestions.setVisible(false); suggestions.setManaged(false);
            if (query.strip().length() < 2) return;
            searchDelay.setOnFinished(e -> async(() -> api.search(query.strip(), 5), suggestions, results -> {
                if (generation != searchGeneration || !search.isFocused()) return;
                suggestions.getChildren().clear();
                for (Anime anime : results) {
                    Button choice = button(anime.title, "suggestion", () -> {
                        suggestions.setVisible(false); suggestions.setManaged(false); showDetails(anime, false);
                    });
                    choice.setMaxWidth(Double.MAX_VALUE); suggestions.getChildren().add(choice);
                }
                suggestions.setVisible(!results.isEmpty()); suggestions.setManaged(!results.isEmpty());
            }, error -> {}));
            searchDelay.playFromStart();
        });
        search.setOnAction(e -> onlineSearch(search.getText(), content, suggestions));
        scrolling.addEventFilter(ScrollEvent.SCROLL, e -> { suggestions.setVisible(false); suggestions.setManaged(false); });
        discoverHome(content, scrolling);
    }
    private void onlineSearch(String text, VBox content, VBox suggestions) {
        String query = text.strip(); if (query.isEmpty()) return;
        searchGeneration++; searchDelay.stop();
        suggestions.setVisible(false); suggestions.setManaged(false);
        if (carousel != null) { carousel.stop(); carousel = null; }
        content.getChildren().setAll(row(label(t("Risultati per ", "Results for ") + "“" + query + "”", "section-title"), spacer(),
            button(t("Torna a Esplora", "Back to Explore"), "quiet", () -> navigate(Route.DISCOVER))));
        VBox results = new VBox(20, label(t("Ricerca in corso…", "Searching…"), "muted")); content.getChildren().add(results);
        async(() -> api.search(query, 50), results, found -> {
            results.getChildren().clear();
            if (found.isEmpty()) results.getChildren().add(label(t("Nessun anime trovato. Prova un altro titolo.", "No anime found. Try another title."), "muted"));
            else {
                results.getChildren().add(label(t("Fino a 50 risultati. Per una ricerca più precisa, aggiungi parole al titolo.",
                    "Up to 50 results. Add more words to narrow your search."), "tiny"));
                results.getChildren().add(cardGrid(found, true));
            }
        }, error -> networkRetry(results, () -> onlineSearch(query, content, suggestions)));
    }
    private void discoverHome(VBox content, ScrollPane scrolling) {
        content.getChildren().clear();
        StackPane banner = new StackPane(label(t("Cerco le storie del momento…", "Finding today's trending stories…"), "subtitle"));
        banner.setPrefHeight(310); banner.setMinHeight(310); banner.getStyleClass().add("banner");
        content.getChildren().add(banner);
        async(() -> api.browse("TRENDING", null, 1, 8), banner, anime -> buildBanner(banner, anime),
            error -> {
                VBox fallback = new VBox(12, label(t("Una nuova storia ti aspetta", "A new story awaits"), "hero-title"),
                    label(t("Banner momentaneamente non disponibile. Puoi continuare a esplorare.", "The banner is temporarily unavailable. You can still explore."), "muted"),
                    button(t("Riprova", "Retry"), "quiet", () -> discoverHome(content, scrolling)));
                fallback.setPadding(new Insets(30)); banner.getChildren().setAll(fallback);
            });
        content.getChildren().add(new VBox(6, label(routeName(Route.DISCOVER), "title"),
            label(prefs.genres().isEmpty() ? t("Trova il tuo prossimo anime.", "Find your next anime.")
                : t("Prima i tuoi generi preferiti, poi nuovi mondi da scoprire.", "Your favourite genres first, then new worlds to discover."), "subtitle")));
        List<DiscoverSection> sections = new ArrayList<>();
        for (String favourite : prefs.genres())
            sections.add(new DiscoverSection("♥ " + genre(favourite), "GENRE", favourite, content, scrolling));
        sections.add(new DiscoverSection(t("Popolari", "Popular"), "POPULAR", null, content, scrolling));
        sections.add(new DiscoverSection(t("Nuove uscite", "New releases"), "RECENT", null, content, scrolling));
        for (String other : Preferences.GENRES)
            if (!prefs.genres().contains(other)) sections.add(new DiscoverSection(genre(other), "GENRE", other, content, scrolling));
        sections.add(new DiscoverSection("Isekai", "TAG", "Isekai", content, scrolling));
        sections.add(new DiscoverSection("Shōnen", "TAG", "Shounen", content, scrolling));
        for (DiscoverSection section : sections) content.getChildren().add(section.box);
        Runnable visibility = () -> sections.forEach(DiscoverSection::checkVisibility);
        ChangeListener<Number> scrollListener = (obs, old, value) -> visibility.run();
        scrolling.vvalueProperty().addListener(scrollListener);
        ChangeListener<Bounds> viewportListener = (obs, old, value) -> visibility.run();
        scrolling.viewportBoundsProperty().addListener(viewportListener);
        // Remove listeners when this home is replaced by a category/search.
        banner.sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) {
                scrolling.vvalueProperty().removeListener(scrollListener);
                scrolling.viewportBoundsProperty().removeListener(viewportListener);
            }
        });
        Platform.runLater(visibility);
    }
    private final class DiscoverSection {
        final VBox box = new VBox(14);
        final VBox items = new VBox();
        final String mode, filter;
        final ScrollPane scrolling;
        List<Anime> data;
        boolean loading, visible;
        DiscoverSection(String title, String mode, String filter, VBox content, ScrollPane scrolling) {
            this.mode = mode; this.filter = filter; this.scrolling = scrolling;
            box.setMinHeight(410); items.setMinHeight(355);
            box.getChildren().addAll(row(label(title, "section-title"), spacer(),
                button(t("Vedi tutti", "See all") + " →", "quiet", () -> categoryPage(content, title, mode, filter, scrolling))), items);
        }
        void checkVisibility() {
            if (box.getScene() == null) return;
            Bounds bounds = box.localToScene(box.getBoundsInLocal());
            Bounds viewport = scrolling.localToScene(scrolling.getBoundsInLocal());
            boolean near = bounds.getMaxY() >= viewport.getMinY() - 350 && bounds.getMinY() <= viewport.getMaxY() + 350;
            if (!near) {
                if (visible) { visible = false; items.getChildren().clear(); }
                return;
            }
            if (visible) return;
            visible = true;
            if (data != null) show(); else if (!loading) load();
        }
        void load() {
            loading = true;
            items.getChildren().setAll(label(t("Caricamento…", "Loading…"), "muted"));
            async(() -> api.browse(mode, filter, 1, 8), box, found -> {
                loading = false; data = found; if (visible) show();
            }, error -> { loading = false; if (visible) networkRetry(items, this::load); });
        }
        void show() {
            if (data.isEmpty()) { items.getChildren().setAll(label(t("Nessun titolo disponibile.", "No titles available."), "muted")); return; }
            HBox cards = new HBox(18); for (Anime anime : data) cards.getChildren().add(animeCard(anime));
            ScrollPane strip = new ScrollPane(cards);
            strip.setFitToHeight(true); strip.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER); strip.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            HBox controls = row(spacer(), button("←", "quiet", () -> horizontal(strip, -0.6)), button("→", "quiet", () -> horizontal(strip, 0.6)));
            strip.addEventFilter(ScrollEvent.SCROLL, e -> {
                if (e.isShiftDown() || Math.abs(e.getDeltaX()) > Math.abs(e.getDeltaY())) {
                    horizontal(strip, (e.getDeltaX() != 0 ? -e.getDeltaX() : -e.getDeltaY()) / 600); e.consume();
                }
            });
            items.getChildren().setAll(strip, controls);
        }
    }
    private void horizontal(ScrollPane pane, double increment) {
        Timeline move = new Timeline(new KeyFrame(Duration.millis(200),
            new KeyValue(pane.hvalueProperty(), Math.max(0, Math.min(1, pane.getHvalue() + increment)), Interpolator.EASE_BOTH)));
        move.play();
    }
    private void buildBanner(StackPane banner, List<Anime> anime) { buildBanner(banner, anime, 0); }
    private void buildBanner(StackPane banner, List<Anime> anime, int initial) {
        if (anime.isEmpty()) { banner.getChildren().setAll(label(t("Nessuna tendenza disponibile", "No trending titles available"), "subtitle")); return; }
        List<Anime> picks = anime.stream().filter(a -> a.bannerImage != null && !a.bannerImage.isBlank()).limit(6).toList();
        if (picks.isEmpty()) picks = anime.stream().limit(6).toList();
        final List<Anime> slides = picks; int[] position = {Math.floorMod(initial, picks.size())};
        Runnable[] display = new Runnable[1];
        display[0] = () -> {
            Anime item = slides.get(position[0]);
            ImageView background = new ImageView(); background.setPreserveRatio(false);
            background.fitWidthProperty().bind(banner.widthProperty()); background.fitHeightProperty().bind(banner.heightProperty());
            String url = item.bannerImage == null || item.bannerImage.isBlank() ? item.coverImage : item.bannerImage;
            covers.load(url, 1400, 550, background::setImage);
            Region shade = new Region(); shade.getStyleClass().add("banner-gradient");
            VBox caption = new VBox(10, label(t("ORA DI TENDENZA", "TRENDING NOW"), "eyebrow"), label(item.title, "hero-title"),
                label(meta(item.format) + "  ·  " + meta(item.year), "subtitle"),
                button(t("Scopri l'anime", "Explore this anime") + " →", "primary", () -> showDetails(item, false)));
            caption.setAlignment(Pos.CENTER_LEFT); caption.setMaxWidth(690); caption.setPadding(new Insets(32));
            StackPane.setAlignment(caption, Pos.CENTER_LEFT);
            HBox controls = row(button("←", "banner-button", () -> { position[0] = Math.floorMod(position[0] - 1, slides.size()); display[0].run(); restartCarousel(); }),
                label((position[0] + 1) + " / " + slides.size(), "tiny"),
                button("→", "banner-button", () -> { position[0] = (position[0] + 1) % slides.size(); display[0].run(); restartCarousel(); }));
            controls.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            StackPane.setAlignment(controls, Pos.BOTTOM_RIGHT); StackPane.setMargin(controls, new Insets(24));
            banner.getChildren().setAll(background, shade, caption, controls);
            banner.setOnMouseClicked(e -> { if (e.getTarget() == banner || e.getTarget() == background || e.getTarget() == shade) showDetails(item, false); });
            entrance(caption, 0);
        };
        display[0].run();
        if (carousel != null) carousel.stop();
        carousel = new Timeline(new KeyFrame(Duration.seconds(7), e -> { position[0] = (position[0] + 1) % slides.size(); display[0].run(); }));
        carousel.setCycleCount(Animation.INDEFINITE);
        banner.setOnMouseEntered(e -> carousel.pause()); banner.setOnMouseExited(e -> syncCarousel());
        syncCarousel();
    }
    private void restartCarousel() {
        if (carousel != null) { carousel.playFromStart(); syncCarousel(); }
    }
    private void categoryPage(VBox content, String title, String mode, String filter, ScrollPane scrolling) {
        if (carousel != null) { carousel.stop(); carousel = null; }
        VBox results = new VBox(20); TilePane grid = new TilePane(20, 24);
        Button more = button(t("Carica altri", "Load more"), "quiet", () -> {});
        int[] page = {1}; Set<Integer> shown = new HashSet<>();
        Runnable load = () -> {
            more.setDisable(true); more.setText(t("Caricamento…", "Loading…"));
            async(() -> api.browse(mode, filter, page[0], 30), results, found -> {
                int added = 0;
                for (Anime anime : found) if (shown.add(anime.id)) { Node card = animeCard(anime); grid.getChildren().add(card); entrance(card, added++); }
                page[0]++; more.setDisable(false); more.setText(t("Carica altri", "Load more"));
                if (added == 0) { more.setVisible(false); more.setManaged(false); }
                if (shown.isEmpty()) results.getChildren().add(label(t("Nessun anime disponibile.", "No anime available."), "muted"));
            }, error -> { more.setDisable(false); more.setText(t("Connessione non disponibile · Riprova", "Connection unavailable · Retry")); });
        };
        more.setOnAction(e -> load.run());
        results.getChildren().addAll(grid, more);
        content.getChildren().setAll(row(button("← " + t("Esplora", "Explore"), "quiet", () -> discoverHome(content, scrolling)),
            label(title, "title"), spacer(), button("↑", "quiet", () -> scrolling.setVvalue(0))), results);
        scrolling.setVvalue(0); load.run();
    }
    private void networkRetry(VBox target, Runnable retry) {
        target.getChildren().setAll(label(t("Connessione non disponibile o limite temporaneo di AniList.", "Connection unavailable or AniList's temporary limit reached."), "muted"),
            button(t("Riprova", "Retry"), "quiet", retry));
    }

    private void settingsPage() {
        VBox page = page(routeName(Route.SETTINGS), t("Personalizza MyAnimeDesk e gestisci i dati dell'app.", "Personalise MyAnimeDesk and manage your app data."));
        page.getStyleClass().add("settings-page");
        ComboBox<String> language = combo(List.of("it", "en"), prefs.language(), code -> code.equals("it") ? "Italiano" : "English");
        language.setId("settings-language");
        language.setOnAction(e -> {
            if (savePreferences(() -> prefs.values.setProperty("language", language.getValue()))) navigate(Route.SETTINGS);
        });
        page.getChildren().add(panel(label(t("Lingua e scoperte", "Language & discovery"), "section-title"),
            label(t("Lingua dell'interfaccia", "Interface language"), "tiny"), language,
            label(t("Generi preferiti: ", "Favourite genres: ") + (prefs.genres().isEmpty() ? t("nessuno selezionato", "none selected")
                : prefs.genres().stream().map(this::genre).reduce((a,b) -> a + ", " + b).orElse("")), "muted"),
            button(t("Scegli di nuovo i generi", "Choose your genres again"), "quiet", () -> onboarding(true))));
        ColorPicker background = new ColorPicker(readColor("background.color", "#160b2e"));
        ColorPicker accent = new ColorPicker(readColor("accent.color", "#8b5cf6"));
        background.setOnAction(e -> {
            if (savePreferences(() -> { prefs.values.setProperty("background.color", hex(background.getValue())); prefs.values.setProperty("background.mode", "color"); })) applyTheme();
        });
        accent.setOnAction(e -> { if (savePreferences(() -> prefs.values.setProperty("accent.color", hex(accent.getValue())))) applyTheme(); });
        page.getChildren().add(panel(label(t("Aspetto", "Appearance"), "section-title"),
            row(new VBox(7, label(t("Sfondo", "Background"), "tiny"), background),
                new VBox(7, label(t("Colore principale", "Accent colour"), "tiny"), accent)),
            row(button(t("Scegli immagine di sfondo", "Choose background image"), "quiet", this::chooseBackground),
                button(t("Ripristina aspetto", "Reset appearance"), "quiet", () -> {
                    if (savePreferences(() -> {
                        prefs.values.setProperty("background.mode", "color");
                        prefs.values.setProperty("background.color", "#160b2e"); prefs.values.setProperty("accent.color", "#8b5cf6");
                    })) { applyTheme(); settingsPage(); }
                }))));
        page.getChildren().add(panel(label(t("Classifica", "Ranking"), "section-title"),
            label(t("Esporta i tuoi voti o importa una classifica. L'importazione unisce i voti per ID anime: quelli presenti nel file sostituiscono i voti precedenti. Gli anime mancanti saranno aggiunti come visti.",
                "Export your scores or import a ranking. Import merges scores by anime ID: scores in the file replace previous scores. Missing anime will be added as completed."), "muted"),
            row(button(t("Esporta classifica", "Export ranking"), "quiet", () -> exportData(true)),
                button(t("Importa classifica", "Import ranking"), "quiet", () -> importData(true)),
                button(t("Resetta classifica", "Reset ranking"), "danger", () ->
                    confirm(t("Azzerare tutti i voti?", "Reset all ratings?"),
                        t("La classifica verrà svuotata. Anime, cartelle e ore guardate resteranno invariati. Puoi prima esportare una copia.",
                            "Your ranking will be cleared. Anime, folders and watched hours will remain unchanged. You can export a backup first."), () -> {
                            if (mutate(library::resetRanking)) { settingsPage(); message(t("Classifica resettata", "Ranking reset"), t("Tutti i voti sono stati rimossi.", "All ratings have been removed.")); }
                        })))));
        page.getChildren().add(panel(label(t("Backup della tua lista", "Library backup"), "section-title"),
            label(t("Un unico file con anime, preferiti, cartelle, visioni e voti. Conserva una copia prima di importare.",
                "One file with your anime, favourites, folders, viewings and ratings. Keep a copy before importing."), "muted"),
            row(button(t("Esporta lista", "Export library"), "quiet", () -> exportData(false)),
                button(t("Importa lista", "Import library"), "quiet", () -> importData(false)),
                button(t("Svuota lista", "Clear library"), "danger", () ->
                    confirm(t("Svuotare la tua lista?", "Clear your library?"),
                        t("Tutti gli anime, le visioni, i preferiti e i voti verranno rimossi. Le cartelle vuote saranno conservate.",
                            "All anime, viewings, favourites and ratings will be removed. Empty folders will be kept."), () -> {
                            if (mutate(() -> library.all().forEach(a -> library.remove(a.id)))) settingsPage();
                        })))));
        Label update = label(t("Versione installata: ", "Installed version: ") + VERSION, "muted");
        Button check = button(t("Controlla aggiornamenti", "Check for updates"), "quiet", () -> {});
        check.setOnAction(e -> checkUpdates(update, check));
        page.getChildren().add(panel(label(t("Aggiornamenti", "Updates"), "section-title"), update,
            row(check, button(t("Apri le release", "Open releases"), "quiet", () -> openUrl(RELEASES))),
            label(t("Dati anime: AniList. La libreria e le preferenze sono salvate sul tuo computer.",
                "Anime data: AniList. Your library and preferences are stored on your computer."), "tiny")));
        // Preserve the original two-column Settings layout; new options extend it.
        List<Node> sections = new ArrayList<>(page.getChildren());
        GridPane grid = new GridPane(); grid.setHgap(18); grid.setVgap(18);
        for (int i = 0; i < 2; i++) {
            ColumnConstraints column = new ColumnConstraints(); column.setPercentWidth(50); column.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(column);
        }
        VBox info = panel(label(t("Informazioni app", "App information"), "section-title"),
            label(t("Cartella dati: ", "Data folder: ") + profile, "muted"),
            label(t("Cache immagini e dati Scopri attiva per avvii più veloci.", "Image and Discover caching is enabled for faster startup."), "muted"));
        Node[] ordered = {sections.get(5), sections.get(2), sections.get(4), info, sections.get(1), sections.get(3)};
        page.getChildren().setAll(sections.get(0), grid);
        for (int i = 0; i < ordered.length; i++) {
            VBox card = (VBox) ordered[i]; card.setMinWidth(0); card.setMaxWidth(Double.MAX_VALUE);
            card.getStyleClass().add("settings-card");
            for (Node child : List.copyOf(card.getChildren())) {
                if (child instanceof HBox row && row.getChildren().stream().allMatch(n -> n instanceof Button)) {
                    int index = card.getChildren().indexOf(row);
                    FlowPane actions = new FlowPane(10, 10);
                    List<Node> buttons = List.copyOf(row.getChildren()); row.getChildren().clear();
                    actions.getChildren().setAll(buttons); card.getChildren().set(index, actions);
                }
            }
            grid.add(card, i % 2, i / 2);
        }
        shell.setCenter(scroll(page));
    }
    private void onboarding(boolean genresOnly) {
        Set<String> selected = new LinkedHashSet<>(prefs.genres());
        VBox body = new VBox(20);
        StackPane dialog = modal(genresOnly ? t("Le storie che ti piacciono", "The stories you love") : "Benvenuto · Welcome", body, 820, genresOnly);
        Runnable genresStep = () -> {
            body.getChildren().clear();
            body.getChildren().addAll(label(t("Che mondi ti va di esplorare?", "Which worlds would you like to explore?"), "title"),
                label(t("Scegli tutti i generi che vuoi. Li troverai per primi in Esplora e potrai cambiarli nelle impostazioni.",
                    "Choose as many genres as you like. They will appear first in Explore, and you can change them in Settings."), "muted"));
            FlowPane genres = new FlowPane(10, 10);
            for (String value : Preferences.GENRES) {
                ToggleButton chip = new ToggleButton(genre(value)); chip.getStyleClass().add("genre-choice"); chip.setSelected(selected.contains(value));
                chip.setOnAction(e -> { if (chip.isSelected()) selected.add(value); else selected.remove(value); }); genres.getChildren().add(chip);
            }
            body.getChildren().add(scroll(genres));
            Runnable complete = () -> {
                if (savePreferences(() -> {
                    prefs.values.setProperty("preferred.genres", String.join(",", selected));
                    prefs.values.setProperty("onboarding.complete", "true");
                })) { closeModal(dialog); navigate(genresOnly ? Route.SETTINGS : Route.DISCOVER); }
            };
            HBox actions = row(label(t("Nessun obbligo: puoi anche non selezionare nulla.", "No pressure: you can also leave this empty."), "tiny"), spacer(),
                button(genresOnly ? t("Salva preferenze", "Save preferences") : t("Iniziamo", "Let's begin") + " →", "primary", complete));
            body.getChildren().add(actions);
        };
        if (genresOnly) genresStep.run();
        else {
            Label welcome = label(t("La tua collezione, il tuo universo.", "Your collection, your universe."), "hero-title");
            Label prompt = label("Scegli la tua lingua / Choose your language", "subtitle");
            ComboBox<String> language = combo(List.of("it", "en"), prefs.language(), code -> code.equals("it") ? "Italiano" : "English");
            language.setId("welcome-language");
            Button next = button(t("Continua", "Continue") + " →", "primary", genresStep);
            language.setOnAction(e -> {
                prefs.values.setProperty("language", language.getValue());
                welcome.setText(t("La tua collezione, il tuo universo.", "Your collection, your universe."));
                next.setText(t("Continua", "Continue") + " →");
            });
            body.getChildren().addAll(label("✦", "loading-emblem"), welcome, prompt, language,
                label("Italiano · English", "tiny"), next);
        }
    }
    private boolean savePreferences(Runnable change) {
        Properties previous = new Properties(); previous.putAll(prefs.values);
        try { change.run(); prefs.save(); return true; }
        catch (Exception e) {
            prefs.values.clear(); prefs.values.putAll(previous);
            message(t("Preferenze non salvate", "Preferences not saved"), t("Controlla che la cartella del profilo sia scrivibile.", "Check that the profile folder is writable."));
            return false;
        }
    }
    private Color readColor(String key, String fallback) {
        try { return Color.web(prefs.values.getProperty(key, fallback)); } catch (Exception e) { return Color.web(fallback); }
    }
    private String hex(Color color) { return String.format("#%02x%02x%02x", Math.round(color.getRed()*255), Math.round(color.getGreen()*255), Math.round(color.getBlue()*255)); }
    private void applyTheme() {
        Color background = readColor("background.color", "#160b2e");
        Color accent = readColor("accent.color", "#8b5cf6");
        root.setStyle("-desk-accent: " + hex(accent) + "; -fx-background-color: " + hex(background) + ";");
        BackgroundImage image = null;
        if ("image".equals(prefs.values.getProperty("background.mode"))) {
            try (InputStream input = Files.newInputStream(Path.of(prefs.values.getProperty("background.image", profile.resolve("custom_background.img").toString())))) {
                Image decoded = new Image(input, 1600, 1000, true, true);
                if (!decoded.isError()) image = new BackgroundImage(decoded, BackgroundRepeat.NO_REPEAT, BackgroundRepeat.NO_REPEAT,
                    BackgroundPosition.CENTER, new BackgroundSize(100, 100, true, true, false, true));
            } catch (Exception ignored) { }
        }
        BackgroundFill fill = new BackgroundFill(background, CornerRadii.EMPTY, Insets.EMPTY);
        root.setBackground(image == null ? new Background(fill) : new Background(new BackgroundFill[]{fill}, new BackgroundImage[]{image}));
    }
    private void chooseBackground() {
        FileChooser chooser = new FileChooser(); chooser.setTitle(t("Scegli lo sfondo", "Choose background"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(t("Immagini", "Images"), "*.png", "*.jpg", "*.jpeg", "*.webp", "*.gif"));
        File file = chooser.showOpenDialog(window); if (file == null) return;
        try {
            if (Files.size(file.toPath()) > 20L * 1024 * 1024) throw new IOException("Too large");
            byte[] bytes = Files.readAllBytes(file.toPath());
            Image test = new Image(new ByteArrayInputStream(bytes), 1600, 1000, true, true);
            if (test.isError()) throw new IOException("Invalid image");
            Path destination = profile.resolve("custom_background.img"); AtomicFiles.write(destination, bytes);
            if (savePreferences(() -> { prefs.values.setProperty("background.mode", "image"); prefs.values.setProperty("background.image", destination.toString()); })) applyTheme();
        } catch (Exception e) { message(t("Immagine non utilizzabile", "Cannot use this image"),
            t("Scegli un'immagine PNG o JPG valida e più piccola di 20 MB.", "Choose a valid PNG or JPG image smaller than 20 MB.")); }
    }
    private FileChooser jsonChooser(String name) {
        FileChooser chooser = new FileChooser(); chooser.setInitialFileName(name);
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("MyAnimeDesk JSON", "*.json")); return chooser;
    }
    private void exportData(boolean ranking) {
        File file = jsonChooser(ranking ? "myanimedesk-ranking.json" : "myanimedesk-library.json").showSaveDialog(window);
        if (file == null) return;
        try {
            if (ranking) library.exportRanking(file.toPath()); else library.exportLibrary(file.toPath());
            message(t("Esportazione completata", "Export complete"), t("La copia è stata salvata in:\n", "Your backup was saved to:\n") + file.getAbsolutePath());
        } catch (Exception e) { message(t("Esportazione non riuscita", "Export failed"), t("Controlla i permessi della cartella scelta.", "Check the selected folder's permissions.")); }
    }
    private void importData(boolean ranking) {
        File file = jsonChooser("").showOpenDialog(window); if (file == null) return;
        try { library.read(file.toPath()); }
        catch (Exception e) { invalidImport(); return; }
        confirm(ranking ? t("Importare la classifica?", "Import this ranking?") : t("Sostituire la lista?", "Replace your library?"),
            ranking ? t("I voti nel file sostituiranno quelli degli stessi anime. Gli anime mancanti saranno aggiunti come visti. Nessuna visione esistente sarà duplicata.",
                "Scores in this file will replace scores for the same anime. Missing anime will be added as completed. Existing viewings will not be duplicated.")
                : t("La lista, le cartelle, i preferiti, le visioni e i voti attuali verranno sostituiti dai dati del file. Esporta prima una copia se vuoi conservarli.",
                    "Your current library, folders, favourites, viewings and ratings will be replaced with the file's data. Export a backup first if you want to keep them."), () -> {
                try {
                    if (ranking) library.importRanking(file.toPath()); else library.importLibrary(file.toPath());
                    libraryFolder = null; libraryGenre = null; librarySearch = "";
                    settingsPage(); message(t("Importazione completata", "Import complete"), t("I dati sono stati verificati e salvati.", "Your data has been validated and saved."));
                } catch (Exception e) { invalidImport(); }
            });
    }
    private void invalidImport() {
        message(t("File non importato", "File not imported"),
            t("Il file non è un backup valido o non può essere salvato. Sono ammessi dieci voti interi da 0 a 10, con al massimo un 12, ID univoci e conteggi validi. La lista precedente non è stata sostituita.",
                "The file is not a valid backup or could not be saved. Ratings require ten whole-number scores from 0 to 10, with at most one 12, unique IDs and valid counts. Your previous library has not been replaced."));
    }
    private void checkUpdates(Label output, Button check) {
        check.setDisable(true); output.setText(t("Controllo in corso…", "Checking…"));
        async(() -> {
            try (HttpClient http = HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(8)).build()) {
                HttpRequest request = HttpRequest.newBuilder(URI.create("https://api.github.com/repos/DasCrishpp/MyAnimeDesk/releases"))
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "MyAnimeDesk/" + VERSION)
                    .timeout(java.time.Duration.ofSeconds(15)).build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() != 200) throw new IOException("GitHub unavailable");
                JsonNode releases = new ObjectMapper().readTree(response.body());
                for (JsonNode release : releases) if (!release.path("draft").asBoolean() && !release.path("tag_name").asText().isBlank())
                    return release.path("tag_name").asText();
                return VERSION;
            }
        }, output, remote -> {
            check.setDisable(false);
            output.setText(isNewerVersion(remote, VERSION) ? t("È disponibile la versione ", "Version available: ") + remote
                : t("Stai usando la versione più recente.", "You're using the latest version."));
        }, error -> { check.setDisable(false); output.setText(t("Controllo non disponibile. Puoi aprire le release nel browser.", "Could not check. You can open releases in your browser.")); });
    }
    static boolean isNewerVersion(String remote, String local) {
        try {
            String[] a = remote.replaceFirst("^[vV]", "").split("[.\\-+]"), b = local.replaceFirst("^[vV]", "").split("[.\\-+]");
            for (int i = 0; i < 3; i++) {
                int first = i < a.length ? Integer.parseInt(a[i]) : 0, second = i < b.length ? Integer.parseInt(b[i]) : 0;
                if (first != second) return first > second;
            }
        } catch (NumberFormatException ignored) { }
        return false;
    }

    private StackPane modal(String title, VBox body, double width, boolean dismissible) {
        VBox surface = new VBox(20); surface.getStyleClass().add("modal-surface");
        surface.setMaxWidth(width); surface.setMaxHeight(Region.USE_PREF_SIZE);
        surface.prefWidthProperty().bind(root.widthProperty().subtract(64).map(w -> Math.min(width, w.doubleValue())));
        double preferredHeight = width >= 1000 ? 10000 : width >= 800 ? 550 : width >= 650 ? 750 : 440;
        surface.maxHeightProperty().bind(root.heightProperty().subtract(60).map(h -> Math.min(preferredHeight, h.doubleValue())));
        HBox heading = row(label(title, "section-title"), spacer());
        StackPane overlay = new StackPane(surface); overlay.getStyleClass().add("modal-shade"); overlay.setPadding(new Insets(30));
        overlay.getProperties().put("dismissible", dismissible);
        if (dismissible) heading.getChildren().add(button("✕", "quiet", () -> closeModal(overlay)));
        surface.getChildren().addAll(heading, body);
        VBox.setVgrow(body, Priority.ALWAYS); body.setMinHeight(0);
        if (!modals.isEmpty()) modals.peek().setDisable(true);
        if (shell != null) shell.setDisable(true);
        surface.setFocusTraversable(true);
        modals.push(overlay); root.getChildren().add(overlay); syncCarousel();
        entrance(surface, 0); Platform.runLater(surface::requestFocus); return overlay;
    }
    private void closeModal(StackPane overlay) {
        root.getChildren().remove(overlay); modals.remove(overlay);
        if (!modals.isEmpty()) modals.peek().setDisable(false);
        if (shell != null) shell.setDisable(!modals.isEmpty());
        if (overlay == detailModal) { detailGeneration++; detailModal = null; detailBody = null; currentDetail = null; detailHistory.clear(); }
        syncCarousel();
    }
    private void closeTopModal() {
        if (!modals.isEmpty() && Boolean.TRUE.equals(modals.peek().getProperties().get("dismissible"))) closeModal(modals.peek());
    }
    private void closeAllModals() { while (!modals.isEmpty()) closeModal(modals.peek()); }
    private void message(String title, String text) {
        VBox body = new VBox(20, label(text, "body")); StackPane dialog = modal(title, body, 600, true);
        body.getChildren().add(button("OK", "primary", () -> closeModal(dialog)));
    }
    private void confirm(String title, String text, Runnable action) {
        VBox body = new VBox(20, label(text, "body")); StackPane dialog = modal(title, body, 600, true);
        body.getChildren().add(row(spacer(), button(t("Annulla", "Cancel"), "quiet", () -> closeModal(dialog)),
            button(t("Conferma", "Confirm"), "danger", () -> { closeModal(dialog); action.run(); })));
    }
    @FunctionalInterface private interface Work<T> { T run() throws Exception; }
    private <T> void async(Work<T> work, Node owner, Consumer<T> success, Consumer<Throwable> failure) {
        if (closed) return;
        CompletableFuture<T> future = CompletableFuture.supplyAsync(() -> {
            try { return work.run(); } catch (Exception e) { throw new CompletionException(e); }
        }, workers);
        pending.add(future);
        future.whenComplete((result, error) -> {
            pending.remove(future);
            if (closed) return;
            Platform.runLater(() -> {
                if (closed || (owner != null && owner.getScene() == null)) return;
                if (error == null) success.accept(result);
                else failure.accept(error.getCause() == null ? error : error.getCause());
            });
        });
    }
    private void syncCarousel() {
        if (carousel == null) return;
        if (!closed && route == Route.DISCOVER && modals.isEmpty() && window.isShowing() && !window.isIconified() && window.isFocused())
            carousel.play();
        else carousel.pause();
    }
    private void openUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (!"https".equalsIgnoreCase(uri.getScheme())) throw new IllegalArgumentException();
            getHostServices().showDocument(uri.toASCIIString());
        } catch (Exception e) { message(t("Link non disponibile", "Link unavailable"), t("Non è stato possibile aprire il browser.", "Could not open the browser.")); }
    }
    private void shutdown() {
        if (closed) return; closed = true;
        if (carousel != null) carousel.stop();
        if (searchDelay != null) searchDelay.stop();
        for (Future<?> job : pending) job.cancel(true); pending.clear();
        workers.shutdownNow();
        if (api != null) api.close();
        if (covers != null) covers.close();
        detailCache.clear();
        try { if (instanceLock != null && instanceLock.isValid()) instanceLock.release(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
    }
    @Override public void stop() { shutdown(); }
}
