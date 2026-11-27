package com.myanimedesk;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.time.Year;
import java.util.function.Consumer;
import java.util.function.Function;

import static com.myanimedesk.Ui.button;
import static com.myanimedesk.Ui.entrance;
import static com.myanimedesk.Ui.image;
import static com.myanimedesk.Ui.label;
import static com.myanimedesk.Ui.panel;
import static com.myanimedesk.Ui.row;
import static com.myanimedesk.Ui.scroll;
import static com.myanimedesk.Ui.spacer;

import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.PauseTransition;
import javafx.animation.ParallelTransition;
import javafx.animation.ScaleTransition;
import javafx.animation.Timeline;
import javafx.animation.TranslateTransition;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.SimpleDoubleProperty;
import javafx.beans.value.ChangeListener;
import javafx.collections.FXCollections;
import javafx.concurrent.Worker;
import javafx.css.PseudoClass;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.geometry.Rectangle2D;
import javafx.geometry.VPos;
import javafx.scene.AccessibleRole;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.ScrollEvent;
import javafx.scene.layout.Background;
import javafx.scene.layout.BackgroundFill;
import javafx.scene.layout.BackgroundImage;
import javafx.scene.layout.BackgroundPosition;
import javafx.scene.layout.BackgroundRepeat;
import javafx.scene.layout.BackgroundSize;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.CornerRadii;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.TilePane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.web.WebView;
import javafx.stage.FileChooser;
import javafx.stage.Popup;
import javafx.stage.Stage;
import javafx.util.Duration;
import javafx.util.StringConverter;

/** JavaFX presentation. Library rules and persistence live in AnimeListManager. */
public class App extends Application {
    private static final String VERSION = "0.4.2";
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
    private AnimeNews animeNews;
    private Stage window;
    private StackPane root;
    private BorderPane shell;
    private NavigationBar navigationBar;
    private FileChannel lockChannel;
    private FileLock instanceLock;
    private volatile boolean closed;
    private boolean ready;
    private Route route = Route.HOME;
    private int dashboardSpotlightId;
    private Timeline carousel;
    private BannerDeck carouselHoverTarget;
    private boolean carouselInView;
    private PauseTransition searchDelay;
    private CardHoverMenu cardHover;
    private ContextMenu cardContextMenu;
    private long lastCardScroll;
    private int pageGeneration, searchGeneration;
    private DiscoverQuery discoverFilters = DiscoverQuery.empty();
    private final Deque<StackPane> modals = new ArrayDeque<>();
    private final Deque<Anime> detailHistory = new ArrayDeque<>();
    private StackPane detailModal;
    private AnimeDetailMotion detailMotion;
    private Node detailReturnFocus;
    private VBox detailBody;
    private Anime currentDetail;
    private int detailGeneration;
    private LibraryQuery.Filter libraryFilter = LibraryQuery.Filter.ALL;
    private LibraryQuery.Order libraryOrder = LibraryQuery.Order.RECENT;
    private String libraryGenre, libraryFolder, librarySearch = "";
    private boolean advanced;
    private boolean updateInProgress;
    private Runnable updateLibraryGrid;

    private enum Route { HOME, LIBRARY, RANKING, DISCOVER, SETTINGS }
    private static final class RatioPane extends StackPane {
        @Override public Orientation getContentBias() { return Orientation.HORIZONTAL; }
        @Override protected double computeMinHeight(double width) { return ratioHeight(width); }
        @Override protected double computePrefHeight(double width) { return ratioHeight(width); }
        @Override protected double computeMaxHeight(double width) { return Double.MAX_VALUE; }
        private double ratioHeight(double width) { return Math.min(480, (width > 0 ? width : 780) * .54); }
    }
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
        animeNews = createAnimeNews();
        root = new StackPane(); root.getStyleClass().add("app-root");
        Scene scene = new Scene(root, 1280, 820);
        scene.addEventFilter(ScrollEvent.SCROLL, event -> {
            lastCardScroll = System.nanoTime();
            hideCardPopup();
        });
        scene.addEventFilter(MouseEvent.MOUSE_PRESSED, e -> root.getStyleClass().remove("keyboard-navigation"));
        scene.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if (e.getCode() == KeyCode.TAB && !root.getStyleClass().contains("keyboard-navigation")) root.getStyleClass().add("keyboard-navigation");
        });
        scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/com/myanimedesk/desk.css")).toExternalForm());
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() != KeyCode.ESCAPE) return;
            if (!modals.isEmpty()) { closeTopModal(); event.consume(); }
            else if (cardHover != null && cardHover.content.isVisible()) { cardHover.hide(); event.consume(); }
        });
        stage.setScene(scene);
        stage.setTitle("MyAnimeDesk" + System.getProperty("myanimedesk.windowSuffix", ""));
        stage.setMinWidth(1150); stage.setMinHeight(780);
        stage.setOnHidden(e -> { shutdown(); Platform.exit(); });
        stage.iconifiedProperty().addListener((obs, old, value) -> syncCarousel());
        stage.focusedProperty().addListener((obs, old, value) -> {
            if (!value && cardHover != null) cardHover.hide();
            syncCarousel();
        });
        Platform.setImplicitExit(true);
        loadingScreen();
        stage.show();
        async(() -> { library.loadFromDefault(); return true; }, root, ignored -> {
            ready = true; navigate(Route.HOME);
            if (!prefs.onboarded()) onboarding(false);
            if (!System.getProperty("jpackage.app-path", "").isBlank())
                async(() -> AppUpdater.latest(VERSION), root, release -> { if (release != null) installUpdate(release); }, error -> {});
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
    AnimeNews createAnimeNews() { return new AnimeNews(); }

    private void navigate(Route next) {
        if (!ready) return;
        hideCardPopup();
        closeAllModals();
        route = next; pageGeneration++; searchGeneration++;
        if (carousel != null) { carousel.stop(); carousel = null; }
        carouselHoverTarget = null;
        if (searchDelay != null) searchDelay.stop();
        covers.forgetPendingViews(); updateLibraryGrid = null;
        if (shell == null) {
            shell = new BorderPane();
            shell.getStyleClass().add("shell");
            navigationBar = new NavigationBar();
            shell.setTop(navigationBar);
            root.getChildren().setAll(shell);
            applyTheme();
        }
        navigationBar.select(next);
        renderPage();
        // The library already staggers its covers. Other routes share one short entrance.
        if (next != Route.LIBRARY) entrance(shell.getCenter(), 0);
    }

    private final class NavigationBar extends StackPane {
        private static final PseudoClass CURRENT = PseudoClass.getPseudoClass("current");
        private static final double TAB_HEIGHT = 44;
        private final Map<Route, Button> buttons = new EnumMap<>(Route.class);
        private final HBox tabs = new HBox(3);
        private final Region selection = new Region();
        private final DoubleProperty selectionWidth = new SimpleDoubleProperty();
        private final Timeline selectionMotion = new Timeline();
        private Route selected;

        NavigationBar() {
            getStyleClass().add("navigation-bar");
            setMinHeight(78);
            setPrefHeight(78);

            Label brandName = new Label("MyAnime");
            Label brandAccent = new Label("Desk");
            brandAccent.getStyleClass().add("navigation-brand-accent");
            HBox brand = new HBox(brandName, brandAccent);
            brand.setAlignment(Pos.CENTER_LEFT);
            brand.getStyleClass().add("navigation-brand");
            brand.setAccessibleText("MyAnimeDesk");

            tabs.getChildren().addAll(
                createButton(Route.HOME, "M 2,2 H 8 V 8 H 2 Z M 11,2 H 17 V 8 H 11 Z M 2,11 H 8 V 17 H 2 Z M 11,11 H 17 V 17 H 11 Z"),
                createButton(Route.LIBRARY, "M 3,2 H 13 Q 15,2 15,4 V 16 L 9,12.5 L 3,16 Z M 6,6 H 12"),
                createButton(Route.RANKING, "M 2,16 H 18 M 3.5,16 V 9 H 7.5 V 16 M 7.5,16 V 3 H 12 V 16 M 12,16 V 7 H 16.5 V 16"),
                createButton(Route.DISCOVER, "M 13.5,8 A 5.5,5.5 0 1,1 2.5,8 A 5.5,5.5 0 1,1 13.5,8 M 12,12 L 17,17"));
            tabs.getStyleClass().add("navigation-tabs");
            tabs.setAlignment(Pos.CENTER);
            Region dockSurface = new Region();
            dockSurface.getStyleClass().add("navigation-dock-surface");
            dockSurface.setMouseTransparent(true);
            selection.getStyleClass().add("navigation-selection");
            selection.setManaged(false);
            selection.setMouseTransparent(true);
            selectionWidth.addListener((obs, before, width) -> selection.resize(width.doubleValue(), TAB_HEIGHT));
            selectionMotion.setOnFinished(event -> requestLayout());
            StackPane dock = new StackPane(dockSurface, selection, tabs);
            dock.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);

            Button settings = createButton(Route.SETTINGS,
                "M 8,1.8 H 11 L 11.5,4 L 13.2,4.9 L 15.3,4.2 L 16.9,6.8 L 15.4,8.4 V 10.3 L 17,11.9 L 15.4,14.5 L 13.2,13.9 L 11.5,14.8 L 11,17 H 8 L 7.4,14.8 L 5.7,13.9 L 3.6,14.5 L 2,11.9 L 3.6,10.3 V 8.4 L 2,6.8 L 3.6,4.2 L 5.7,4.9 L 7.4,4 Z M 12,9.4 A 2.5,2.5 0 1,1 7,9.4 A 2.5,2.5 0 1,1 12,9.4");
            settings.getStyleClass().add("navigation-settings");
            settings.setMinSize(48, 48);
            settings.setPrefSize(48, 48);
            settings.setMaxSize(48, 48);
            settings.setTooltip(new Tooltip());

            // Matching end widths keep the dock centered over the content.
            StackPane settingsArea = new StackPane(settings);
            settingsArea.setAlignment(Pos.CENTER_RIGHT);
            settingsArea.prefWidthProperty().bind(brand.widthProperty());
            BorderPane body = new BorderPane();
            body.setLeft(brand);
            body.setCenter(new StackPane(dock));
            body.setRight(settingsArea);
            getChildren().add(body);
        }

        private Button createButton(Route item, String path) {
            Button nav = button("", "navigation-tab", () -> navigate(item));
            nav.setId("nav-" + item.name().toLowerCase(Locale.ROOT));
            nav.setMinSize(Region.USE_PREF_SIZE, TAB_HEIGHT);
            nav.setPrefHeight(TAB_HEIGHT);
            nav.setMaxHeight(TAB_HEIGHT);
            StackPane graphic = icon(path);
            nav.setGraphic(graphic);
            TranslateTransition lift = new TranslateTransition(Duration.millis(145), graphic);
            lift.setInterpolator(Interpolator.EASE_OUT);
            nav.hoverProperty().addListener((obs, before, hovering) -> {
                lift.stop();
                lift.setToY(hovering ? -2 : 0);
                lift.playFromStart();
            });
            buttons.put(item, nav);
            return nav;
        }

        private StackPane icon(String path) {
            SVGPath line = new SVGPath();
            line.setContent(path);
            line.getStyleClass().add("navigation-icon");
            StackPane frame = new StackPane(line);
            frame.setMinSize(19, 19);
            frame.setPrefSize(19, 19);
            frame.setMaxSize(19, 19);
            frame.setMouseTransparent(true);
            return frame;
        }

        void select(Route next) {
            Route previous = selected;
            selected = next;
            buttons.forEach((item, nav) -> {
                String caption = item == Route.DISCOVER ? t("Scopri / Cerca", "Discover / Search") : routeName(item);
                if (item == Route.SETTINGS) nav.getTooltip().setText(caption);
                else nav.setText(caption);
                nav.setAccessibleText(caption);
                nav.pseudoClassStateChanged(CURRENT, item == next);
                nav.setAccessibleHelp(item == next ? t("Sezione attiva", "Current section") : null);
            });
            if (previous != null && previous != next) animateSelection();
            requestLayout();
        }

        @Override protected void layoutChildren() {
            super.layoutChildren();
            if (selected == null || selectionMotion.getStatus() == Animation.Status.RUNNING) return;
            if (selected == Route.SETTINGS) {
                selection.setOpacity(0);
                return;
            }
            Button target = buttons.get(selected);
            selection.setLayoutY(tabs.getLayoutY() + target.getLayoutY());
            selection.setLayoutX(tabs.getLayoutX() + target.getLayoutX());
            selectionWidth.set(target.getWidth());
            selection.setScaleY(1);
            selection.setOpacity(1);
        }

        private void animateSelection() {
            selectionMotion.stop();
            if (selected == Route.SETTINGS) {
                selectionMotion.getKeyFrames().setAll(new KeyFrame(Duration.millis(130),
                    new KeyValue(selection.opacityProperty(), 0, Interpolator.EASE_OUT)));
                selectionMotion.playFromStart();
                return;
            }
            Button target = buttons.get(selected);
            if (target.getWidth() == 0) return;
            double x = tabs.getLayoutX() + target.getLayoutX();
            if (selection.getOpacity() < .05) {
                selection.setLayoutX(x);
                selectionWidth.set(target.getWidth());
                selection.setScaleY(.87);
            }
            selectionMotion.getKeyFrames().setAll(
                new KeyFrame(Duration.millis(70),
                    new KeyValue(selection.scaleYProperty(), .92, Interpolator.EASE_OUT)),
                new KeyFrame(Duration.millis(195),
                    new KeyValue(selection.layoutXProperty(), x, Interpolator.EASE_BOTH),
                    new KeyValue(selectionWidth, target.getWidth(), Interpolator.EASE_BOTH),
                    new KeyValue(selection.scaleYProperty(), 1, Interpolator.EASE_OUT),
                    new KeyValue(selection.opacityProperty(), 1, Interpolator.EASE_OUT)));
            selectionMotion.playFromStart();
        }
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
        List<Anime> collection = library.all();
        List<Anime> watching = collection.stream().filter(anime -> anime.status == Anime.Status.WATCHING).toList();
        VBox page = page("Dashboard", null);
        page.setSpacing(24);
        ScrollPane scrolling = scroll(page);

        DashboardStats stats = new DashboardStats(number(library.watchedHours()), collection, en());

        DashboardSpotlight spotlight = new DashboardSpotlight(watching);
        DashboardNews news = new DashboardNews(animeNews, en(), this::openUrl);
        GridPane dashboard = new GridPane();
        dashboard.setHgap(20);
        dashboard.setVgap(24);
        ColumnConstraints spotlightColumn = new ColumnConstraints();
        spotlightColumn.setHgrow(Priority.ALWAYS);
        ColumnConstraints newsColumn = new ColumnConstraints(360, 400, 440);
        dashboard.getColumnConstraints().addAll(spotlightColumn, new ColumnConstraints(300, 350, 385));
        dashboard.add(spotlight, 0, 0);
        dashboard.add(stats, 1, 0);

        int selected = 0;
        if (!watching.isEmpty()) {
            TilePane covers = new TilePane(18, 22);
            covers.setAlignment(Pos.TOP_LEFT);
            for (int i = 0; i < watching.size(); i++) {
                Anime anime = watching.get(i);
                if (anime.id == dashboardSpotlightId) selected = i;
                Node card = animeCard(anime, true);
                spotlight.register(card, i, scrolling);
                covers.getChildren().add(card);
            }
            dashboard.add(new VBox(16, label(t("In visione", "Watching"), "dashboard-queue-title"), covers), 0, 1, 2, 1);
        }
        int newsRow = watching.isEmpty() ? 1 : 2;
        dashboard.add(news, 0, newsRow, 2, 1);
        GridPane.setValignment(news, VPos.TOP);
        GridPane.setFillHeight(news, false);
        scrolling.viewportBoundsProperty().addListener((obs, before, after) -> {
            boolean wide = after.getWidth() >= 1600;
            if (wide == (before.getWidth() >= 1600)) return;
            if (wide) dashboard.getColumnConstraints().add(newsColumn);
            else dashboard.getColumnConstraints().remove(newsColumn);
            GridPane.setConstraints(news, wide ? 2 : 0, wide ? 0 : newsRow,
                wide ? 1 : 2, wide ? newsRow : 1);
            news.setRail(wide);
        });
        page.getChildren().add(dashboard);

        shell.setCenter(scrolling);
        if (!watching.isEmpty()) spotlight.select(selected);
    }

    private final class DashboardSpotlight extends StackPane {
        private static final PseudoClass SPOTLIGHT = PseudoClass.getPseudoClass("spotlight");
        private static final double POSTER_WIDTH = 178;
        private static final double POSTER_HEIGHT = 250;
        private final List<Anime> watching;
        private final List<Node> choices = new ArrayList<>();
        private final PauseTransition hoverDelay = new PauseTransition(Duration.millis(190));
        private final Label title = label("", "dashboard-spotlight-title");
        private final Label metadata = label("", "dashboard-spotlight-meta");
        private final ImageView backdrop = new ImageView();
        private final ImageView portrait = new ImageView();
        private final StackPane poster;
        private final Region posterOutline = new Region();
        private final Region shade = new Region();
        private final VBox caption;
        private FadeTransition backdropFade;
        private FadeTransition portraitFade;
        private ParallelTransition captionMotion;
        private int index = -1;
        private long artworkVersion;

        DashboardSpotlight(List<Anime> watching) {
            this.watching = watching;
            getStyleClass().add("dashboard-spotlight");
            setMinSize(0, 340);
            setPrefHeight(354);
            setMaxWidth(Double.MAX_VALUE);
            Rectangle clip = new Rectangle();
            clip.widthProperty().bind(widthProperty());
            clip.heightProperty().bind(heightProperty());
            clip.setArcWidth(42);
            clip.setArcHeight(42);
            setClip(clip);

            backdrop.setManaged(false);
            backdrop.setMouseTransparent(true);
            backdrop.setId("dashboard-spotlight-backdrop");
            shade.getStyleClass().add("dashboard-spotlight-shade");
            shade.setManaged(false);
            shade.setMouseTransparent(true);

            posterOutline.getStyleClass().add("dashboard-spotlight-outline");
            posterOutline.setManaged(false);
            posterOutline.setMouseTransparent(true);
            posterOutline.setRotate(12);
            Region posterSurface = new Region();
            posterSurface.getStyleClass().add("dashboard-spotlight-poster-surface");
            poster = new StackPane(posterSurface, portrait);
            poster.getStyleClass().add("dashboard-spotlight-poster");
            poster.setManaged(false);
            poster.setMouseTransparent(true);
            poster.setRotate(5);
            portrait.setFitWidth(POSTER_WIDTH);
            portrait.setFitHeight(POSTER_HEIGHT);
            portrait.setId("dashboard-spotlight-cover");
            Rectangle posterClip = new Rectangle(POSTER_WIDTH, POSTER_HEIGHT);
            posterClip.setArcWidth(21);
            posterClip.setArcHeight(21);
            portrait.setClip(posterClip);

            Button open = button(watching.isEmpty() ? t("Scopri anime", "Explore anime") : t("Apri scheda", "Open details"),
                "dashboard-spotlight-action", () -> {
                    if (index < 0) navigate(Route.DISCOVER);
                    else showDetails(watching.get(index), false, poster);
                });
            caption = new VBox(12);
            caption.getStyleClass().add("dashboard-spotlight-caption");
            caption.setAlignment(Pos.CENTER_LEFT);
            caption.setMaxHeight(Region.USE_PREF_SIZE);
            title.setMaxHeight(158);
            StackPane.setAlignment(caption, Pos.CENTER_LEFT);
            if (watching.isEmpty()) {
                title.setText(t("Nessun anime in visione", "No anime in progress"));
                caption.getChildren().add(title);
            } else {
                Label heading = label(t("Continua a guardare", "Continue watching"), "dashboard-spotlight-kicker");
                caption.getChildren().addAll(heading, title, metadata);
            }
            HBox actions = new HBox(9, open);
            actions.setAlignment(Pos.CENTER_LEFT);
            if (watching.size() > 1) {
                actions.getChildren().addAll(stepButton(-1), stepButton(1));
            }
            caption.getChildren().add(actions);
            getChildren().addAll(backdrop, shade, posterOutline, poster, caption);
            poster.setVisible(!watching.isEmpty());
            posterOutline.setVisible(!watching.isEmpty());
            sceneProperty().addListener((obs, old, scene) -> {
                if (scene == null) {
                    hoverDelay.stop();
                    artworkVersion++;
                    if (backdropFade != null) backdropFade.stop();
                    if (portraitFade != null) portraitFade.stop();
                    if (captionMotion != null) captionMotion.stop();
                }
            });
        }

        private Button stepButton(int direction) {
            Button step = button(direction < 0 ? "‹" : "›", "dashboard-spotlight-step",
                () -> select(Math.floorMod(index + direction, watching.size())));
            step.setId(direction < 0 ? "dashboard-spotlight-previous" : "dashboard-spotlight-next");
            step.setAccessibleText(direction < 0 ? t("Anime precedente", "Previous anime") : t("Anime successivo", "Next anime"));
            return step;
        }

        void register(Node card, int position, ScrollPane scrolling) {
            choices.add(card);
            card.hoverProperty().addListener((obs, before, hovered) -> {
                if (hovered && inViewport(scrolling)) {
                    hoverDelay.setOnFinished(event -> {
                        if (card.isHover() && inViewport(scrolling)) select(position);
                    });
                    hoverDelay.playFromStart();
                } else if (!hovered) hoverDelay.stop();
            });
            card.focusedProperty().addListener((obs, before, focused) -> {
                if (focused && inViewport(scrolling)) select(position);
            });
        }

        private boolean inViewport(ScrollPane scrolling) {
            if (getScene() == null || scrolling.getScene() == null) return false;
            Bounds area = scrolling.localToScene(scrolling.getLayoutBounds());
            Bounds card = localToScene(getLayoutBounds());
            return card.getMaxY() > area.getMinY() + 80 && card.getMinY() < area.getMaxY();
        }

        void select(int position) {
            hoverDelay.stop();
            if (index == position) return;
            if (index >= 0) choices.get(index).pseudoClassStateChanged(SPOTLIGHT, false);
            index = position;
            choices.get(index).pseudoClassStateChanged(SPOTLIGHT, true);
            Anime item = watching.get(index);
            dashboardSpotlightId = item.id;
            title.setText(item.title);
            metadata.setText(meta(item.format) + (item.episodes > 0 ? "  ·  " + item.episodes + " ep" : ""));
            if (captionMotion != null) captionMotion.stop();
            caption.setTranslateY(8);
            caption.setOpacity(.65);
            TranslateTransition slide = new TranslateTransition(Duration.millis(200), caption);
            slide.setToY(0);
            slide.setInterpolator(Interpolator.EASE_OUT);
            FadeTransition fade = new FadeTransition(Duration.millis(170), caption);
            fade.setToValue(1);
            captionMotion = new ParallelTransition(slide, fade);
            captionMotion.play();

            long version = ++artworkVersion;
            if (backdropFade != null) backdropFade.stop();
            if (portraitFade != null) portraitFade.stop();
            backdrop.setImage(null);
            portrait.setImage(null);
            covers.load(item.coverImage, 464, 650, image -> {
                if (artworkVersion != version) return;
                portrait.setImage(image);
                cropToFit(portrait);
                portraitFade = fadeArtwork(portrait, .45, 1);
                if (backdrop.getImage() == null) showBackdrop(image, .51);
            });
            if (item.bannerImage != null && !item.bannerImage.equals(item.coverImage)) {
                covers.load(item.bannerImage, 1280, 550, image -> {
                    if (artworkVersion != version || image.getWidth() < image.getHeight() * 1.4) return;
                    showBackdrop(image, .94);
                });
            }
        }

        private void showBackdrop(Image image, double opacity) {
            double from = backdrop.getImage() == null ? 0 : backdrop.getOpacity();
            if (backdropFade != null) backdropFade.stop();
            backdrop.setImage(image);
            cropToFit(backdrop);
            backdropFade = fadeArtwork(backdrop, from, opacity);
        }

        private FadeTransition fadeArtwork(Node node, double from, double to) {
            FadeTransition fade = new FadeTransition(Duration.millis(220), node);
            fade.setFromValue(from);
            fade.setToValue(to);
            fade.play();
            return fade;
        }

        @Override protected void layoutChildren() {
            double w = getWidth();
            double h = getHeight();
            caption.setMaxWidth(w * (watching.isEmpty() ? .9 : .63));
            super.layoutChildren();
            backdrop.setFitWidth(w);
            backdrop.setFitHeight(h);
            backdrop.relocate(0, 0);
            shade.resizeRelocate(0, 0, w, h);
            cropToFit(backdrop);
            double posterX = w - Math.min(126, w * .1) - POSTER_WIDTH;
            double posterY = (h - POSTER_HEIGHT) / 2;
            poster.resizeRelocate(posterX, posterY, POSTER_WIDTH, POSTER_HEIGHT);
            posterOutline.resizeRelocate(posterX + 16, posterY - 4, POSTER_WIDTH, POSTER_HEIGHT);
        }

        private void cropToFit(ImageView view) {
            Image image = view.getImage();
            if (image == null || view.getFitWidth() <= 0 || view.getFitHeight() <= 0) return;
            double ratio = view.getFitWidth() / view.getFitHeight();
            double cropWidth = Math.min(image.getWidth(), image.getHeight() * ratio);
            double cropHeight = Math.min(image.getHeight(), image.getWidth() / ratio);
            double verticalPosition = view == backdrop && image == portrait.getImage() ? .29 : .5;
            view.setViewport(new Rectangle2D((image.getWidth() - cropWidth) / 2,
                (image.getHeight() - cropHeight) * verticalPosition, cropWidth, cropHeight));
        }
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
        TilePane grid = posterGrid(10, 10);
        grid.setPrefColumns(5);
        int index = 0;
        for (Anime item : anime) {
            Node card = animeCard(item); grid.getChildren().add(card);
            if (animate) entrance(card, index++);
        }
        return grid;
    }
    private TilePane posterGrid(double horizontalGap, double verticalGap) {
        TilePane grid = new TilePane(horizontalGap, verticalGap);
        grid.getStyleClass().add("poster-grid");
        grid.setAlignment(Pos.TOP_CENTER);
        return grid;
    }
    private Node animeCard(Anime anime) {
        return animeCard(anime, false);
    }

    private Node animeCard(Anime anime, boolean dashboardPoster) {
        int width = dashboardPoster ? 232 : 200;
        StackPane cover = image(covers, anime.coverImage, width, dashboardPoster ? 325 : 280, dashboardPoster ? 26 : 20);
        Label state = label("", "status");
        StackPane.setAlignment(state, Pos.TOP_LEFT); StackPane.setMargin(state, new Insets(12));
        Button heart = button("", "heart", () -> {});
        heart.setId("favourite-" + anime.id);
        StackPane.setAlignment(heart, Pos.TOP_RIGHT); StackPane.setMargin(heart, new Insets(10));
        if (!dashboardPoster) cover.getChildren().add(state);
        cover.getChildren().add(heart);
        Label name = label(anime.title, "card-title");
        if (dashboardPoster) {
            name.setMinHeight(0);
            name.setMaxHeight(46);
        } else {
            name.setMinHeight(42);
            name.setMaxHeight(42);
        }
        Label info = label(meta(anime.format) + "  ·  " + (anime.episodes > 0 ? anime.episodes + " ep" : t("Episodi N/D", "Episodes N/A")), "tiny");
        VBox footer = new VBox(dashboardPoster ? 4 : 6, name, info); footer.getStyleClass().add("card-footer");
        VBox card = new VBox(cover, footer); card.setPrefWidth(width); card.setMaxWidth(width);
        card.getStyleClass().add("anime-card"); card.setId("anime-" + anime.id);
        if (dashboardPoster) {
            card.getStyleClass().add("dashboard-poster");
            TranslateTransition lift = new TranslateTransition(Duration.millis(155), cover);
            lift.setInterpolator(Interpolator.EASE_OUT);
            card.hoverProperty().addListener((obs, before, hovering) -> {
                lift.stop();
                lift.setToY(hovering ? -3 : 0);
                lift.playFromStart();
            });
        }
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
        card.setOnMouseClicked(e -> { if (e.getButton() == MouseButton.PRIMARY) { hideCardPopup(); showDetails(anime, false, cover); } });
        card.setOnKeyPressed(e -> { if (e.getCode() == KeyCode.ENTER) { showDetails(anime, false, cover); e.consume(); } });
        card.setOnContextMenuRequested(event -> {
            showCardContext(card, anime, event.getScreenX(), event.getScreenY());
            event.consume();
        });
        card.hoverProperty().addListener((obs, before, hovering) -> {
            if (hovering && modals.isEmpty()) {
                if (cardHover == null) cardHover = new CardHoverMenu();
                cardHover.enter(card, anime);
            } else if (cardHover != null) cardHover.leave(card);
        });
        card.setOnMouseMoved(event -> {
            if (cardHover != null && cardHover.anchor == null && modals.isEmpty()) cardHover.enter(card, anime);
        });
        card.sceneProperty().addListener((obs, before, scene) -> {
            if (scene == null && cardHover != null && cardHover.anchor == card) cardHover.hide();
        });
        return card;
    }

    private void showCardContext(Node card, Anime anime, double x, double y) {
        hideCardPopup();
        ContextMenu menu = new ContextMenu();
        ToggleGroup statuses = new ToggleGroup();
        Anime saved = library.findById(anime.id);
        for (Anime.Status value : Anime.Status.values()) {
            RadioMenuItem item = new RadioMenuItem(status(value));
            item.getStyleClass().add("status-item-" + value.name().toLowerCase(Locale.ROOT));
            item.setToggleGroup(statuses);
            item.setSelected(saved != null && saved.status == value);
            item.setOnAction(event -> changeStatus(anime, value));
            menu.getItems().add(item);
        }
        MenuItem folder = new MenuItem(t("Organizza in cartelle", "Organise in folders"));
        folder.setOnAction(event -> folderMembership(anime));
        menu.getItems().addAll(new SeparatorMenuItem(), folder);
        menu.setOnHidden(event -> { if (cardContextMenu == menu) cardContextMenu = null; });
        cardContextMenu = menu;
        menu.show(card, x, y);
    }

    private void hideCardPopup() {
        if (cardHover != null) cardHover.hide();
        if (cardContextMenu != null) cardContextMenu.hide();
    }

    /** Reuses one overlay in the app scene instead of opening a native window for each cover. */
    private final class CardHoverMenu {
        private static final double WIDTH = 204;
        private final VBox content = new VBox(7);
        private final Label title = label("", "card-hover-title");
        private final Label description = label("", "tiny");
        private final Label statusHeading = label("", "eyebrow");
        private final Map<Anime.Status, Button> statusButtons = new EnumMap<>(Anime.Status.class);
        private final PauseTransition openDelay = new PauseTransition(Duration.millis(95));
        private final PauseTransition closeDelay = new PauseTransition(Duration.millis(165));
        private final FadeTransition reveal = new FadeTransition(Duration.millis(110), content);
        private VBox anchor;
        private Anime anime;

        CardHoverMenu() {
            content.getStyleClass().add("card-hover-menu");
            content.setManaged(false);
            content.setVisible(false);
            VBox actions = new VBox(4, statusHeading);
            for (Anime.Status value : Anime.Status.values()) {
                Button quick = button("", "quick-status", () -> {
                    Anime selected = anime;
                    hide();
                    if (selected != null) changeStatus(selected, value);
                });
                quick.getStyleClass().add("quick-status-" + value.name().toLowerCase(Locale.ROOT));
                quick.setMaxWidth(Double.MAX_VALUE);
                statusButtons.put(value, quick);
                actions.getChildren().add(quick);
            }
            content.getChildren().addAll(title, description, new Separator(), actions);
            openDelay.setOnFinished(event -> show());
            closeDelay.setOnFinished(event -> {
                if (anchor != null && !anchor.isHover() && !content.isHover()) hide();
            });
            reveal.setFromValue(.55);
            reveal.setToValue(1);
            content.hoverProperty().addListener((obs, before, hovering) -> {
                if (hovering) closeDelay.stop();
                else if (anchor != null && !anchor.isHover()) closeDelay.playFromStart();
            });
        }

        void enter(VBox card, Anime item) {
            if (cardContextMenu != null || card.getScene() == null) return;
            closeDelay.stop();
            if (anchor == card && content.isVisible()) return;
            hide();
            anchor = card;
            anime = item;
            openDelay.playFromStart();
        }

        void leave(VBox card) {
            if (anchor != card) return;
            if (!content.isVisible()) hide();
            else closeDelay.playFromStart();
        }

        private void show() {
            if (anchor == null || !anchor.isHover() || anchor.getScene() == null || !modals.isEmpty()) {
                hide();
                return;
            }
            if (lastCardScroll != 0 && System.nanoTime() - lastCardScroll < 150_000_000L) {
                openDelay.playFromStart();
                return;
            }
            title.setText(anime.title);
            description.setText(meta(anime.format) + "  ·  " + (anime.episodes > 0 ? anime.episodes + " ep" : "—")
                + "\n" + meta(anime.year) + (anime.genres == null || anime.genres.isEmpty() ? "" : "  ·  " + genre(anime.genres.get(0))));
            statusHeading.setText(t("STATO DI VISIONE", "WATCH STATUS"));
            Anime saved = library.findById(anime.id);
            statusButtons.forEach((value, quick) -> {
                quick.setText(status(value));
                quick.getStyleClass().remove("quick-status-current");
                if (saved != null && saved.status == value) quick.getStyleClass().add("quick-status-current");
            });
            if (content.getParent() == null) root.getChildren().add(content);
            content.setOpacity(.55);
            content.setVisible(true);
            content.applyCss();
            double height = content.prefHeight(WIDTH);
            Bounds card = root.sceneToLocal(anchor.localToScene(anchor.getLayoutBounds()));
            double x = card.getMaxX() + 10;
            if (x + WIDTH > root.getWidth() - 8) x = card.getMinX() - WIDTH - 10;
            x = Math.max(8, Math.min(x, root.getWidth() - WIDTH - 8));
            double y = Math.max(8, Math.min(card.getMinY() + 4, root.getHeight() - height - 8));
            content.resizeRelocate(x, y, WIDTH, height);
            reveal.playFromStart();
        }

        void hide() {
            openDelay.stop();
            closeDelay.stop();
            reveal.stop();
            anchor = null;
            anime = null;
            content.setVisible(false);
        }
    }
    private ListCell<Anime.Status> statusCell() {
        return new ListCell<>() {
            @Override protected void updateItem(Anime.Status value, boolean empty) {
                super.updateItem(value, empty);
                getStyleClass().removeIf(name -> name.startsWith("status-choice-"));
                setText(empty || value == null ? null : status(value));
                if (!empty && value != null) getStyleClass().add("status-choice-" + value.name().toLowerCase(Locale.ROOT));
            }
        };
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
        showDetails(anime, related, null);
    }

    private void showDetails(Anime anime, boolean related, StackPane cover) {
        boolean opening = detailModal == null;
        if (opening) {
            detailReturnFocus = cover != null && cover.getParent() != null && cover.getParent().isFocusTraversable()
                ? cover.getParent() : root.getScene().getFocusOwner();
            detailBody = new VBox();
            detailModal = modal(t("Scheda anime", "Anime details"), detailBody, 1120, true, false);
            detailModal.getStyleClass().add("anime-detail-modal");
            detailModal.getStylesheets().add(Objects.requireNonNull(App.class.getResource("anime-detail.css")).toExternalForm());
            StackPane overlay = detailModal;
            overlay.setPickOnBounds(true);
            overlay.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getTarget() == overlay && modals.peek() == overlay) {
                    event.consume();
                    closeModal(overlay);
                }
            });
            detailMotion = new AnimeDetailMotion(detailModal, cover);
        } else if (related && currentDetail != null) detailHistory.push(currentDetail);
        else if (!related) detailHistory.clear();
        currentDetail = library.findById(anime.id);
        if (currentDetail == null) currentDetail = detailCache.getOrDefault(anime.id, anime);
        int generation = ++detailGeneration;
        renderDetail(false);
        if (!detailCache.containsKey(anime.id)) {
            detailNotice(label(t("Carico i dettagli…", "Loading anime details…"), "muted"));
            Anime requested = currentDetail;
            async(() -> api.getAnimeDetails(requested), detailBody, full -> {
                if (generation != detailGeneration) return;
                if (full == null) { detailFailure(); return; }
                detailCache.put(full.id, full);
                Anime saved = library.findById(full.id);
                if (saved != null) { copyMetadata(full, saved); currentDetail = saved; }
                else currentDetail = full;
                renderDetail(true);
            }, error -> { if (generation == detailGeneration) detailFailure(); });
        }
        if (opening) {
            root.applyCss();
            root.layout();
            detailModal.lookup(".modal-close").setId("detail-close");
            detailMotion.open();
        }
    }
    private void detailFailure() {
        renderDetail(true);
        detailNotice(label(t("Informazioni online non disponibili.", "Online details are unavailable."), "muted"),
            button(t("Riprova", "Retry"), "quiet", () -> showDetails(currentDetail, false)));
    }
    private void detailNotice(Node... content) {
        HBox notice = row(content);
        notice.getStyleClass().add("anime-detail-notice");
        detailBody.getChildren().add(0, notice);
    }
    private void copyMetadata(Anime from, Anime to) {
        to.malId = from.malId; to.kitsuId = from.kitsuId; to.provider = from.provider;
        to.title = from.title; to.coverImage = from.coverImage; to.episodes = from.episodes; to.duration = from.duration;
        to.genres = from.genres; to.format = from.format; to.year = from.year; to.season = from.season;
        to.startDate = from.startDate;
        to.studio = from.studio; to.airingStatus = from.airingStatus; to.bannerImage = from.bannerImage;
        to.description = from.description; to.trailerId = from.trailerId; to.trailerSite = from.trailerSite;
        to.siteUrl = from.siteUrl; to.averageScore = from.averageScore; to.cast = from.cast; to.castPartial = from.castPartial;
        to.relations = from.relations; to.detailsLoaded = from.detailsLoaded; to.hasMoreCast = from.hasMoreCast; to.castPage = from.castPage;
    }
    private void refreshDetail() {
        if (detailModal == null || currentDetail == null) return;
        Anime saved = library.findById(currentDetail.id);
        if (saved != null) currentDetail = saved;
        renderDetail(true);
    }
    private void renderDetail(boolean preserveScroll) {
        Anime anime = currentDetail;
        ScrollPane previous = (ScrollPane) detailBody.lookup("#detail-scroll");
        double position = preserveScroll && previous != null ? previous.getVvalue() : 0;
        VBox content = new VBox(18);
        content.getStyleClass().add("anime-detail-content");
        if (!detailHistory.isEmpty()) {
            Button backButton = button("← " + t("Torna a ", "Back to ") + detailHistory.peek().title, "anime-detail-source", () -> {
                Anime back = detailHistory.pop();
                Deque<Anime> keep = new ArrayDeque<>(detailHistory);
                showDetails(back, false); detailHistory.addAll(keep); renderDetail(false);
            });
            backButton.setId("detail-back");
            backButton.setMaxWidth(480);
            content.getChildren().add(backButton);
        }
        Anime saved = library.findById(anime.id);
        HBox columns = new HBox(18, detailOverview(anime, saved), detailLibrary(anime, saved));
        columns.getStyleClass().add("anime-detail-bottom");
        columns.setAlignment(Pos.TOP_LEFT);
        content.getChildren().addAll(detailHero(anime), columns);
        ScrollPane scrolling = scroll(content);
        scrolling.getStyleClass().add("anime-detail-scroll");
        scrolling.setId("detail-scroll");
        detailBody.getChildren().setAll(scrolling);
        scrolling.setVvalue(position);
    }

    private VBox detailLibrary(Anime anime, Anime saved) {
        VBox libraryPanel = new VBox(11, label(t("La mia lista", "My library"), "anime-detail-heading"));
        libraryPanel.getStyleClass().add("anime-detail-library");
        libraryPanel.setPrefWidth(270);
        libraryPanel.setMinWidth(250);
        libraryPanel.setMaxWidth(270);
        libraryPanel.setMaxHeight(Region.USE_PREF_SIZE);
        Button favourite = button(saved != null && saved.favorite ? t("♥  Nei preferiti", "♥  Favourited")
            : t("♡  Aggiungi ai preferiti", "♡  Add to favourites"), "anime-detail-favorite", () -> {
            if (mutate(() -> { ensureSaved(anime); library.toggleFavorite(library.findById(anime.id)); })) { afterLibraryChange(); refreshDetail(); }
        });
        favourite.setId("detail-favourite");
        favourite.pseudoClassStateChanged(PseudoClass.getPseudoClass("selected"), saved != null && saved.favorite);
        favourite.setMaxWidth(Double.MAX_VALUE);
        ComboBox<Anime.Status> state = combo(List.of(Anime.Status.values()), saved == null ? null : saved.status, this::status);
        state.setCellFactory(list -> statusCell()); state.setButtonCell(statusCell());
        state.getStyleClass().add("status-selector");
        state.setPromptText(t("+ Aggiungi alla lista", "+ Add to library")); state.setId("detail-status");
        state.setOnAction(e -> { if (state.getValue() != null) changeStatus(anime, state.getValue()); });
        Label stateLabel = label(t("Stato di visione", "Watch status"), "anime-detail-control-label");
        stateLabel.setLabelFor(state);
        libraryPanel.getChildren().addAll(new VBox(5, stateLabel, state), favourite);
        if (saved != null) {
            Spinner<Integer> views = new Spinner<>(0, AnimeListManager.MAX_VIEWS, saved.viewCount());
            views.setEditable(false); views.setId("view-count"); views.setMaxWidth(Double.MAX_VALUE);
            Label hours = label(number(saved.watchedHours()) + t(" ore guardate", " hours watched"), "accent-text");
            boolean[] restoringCount = { false };
            java.util.function.Consumer<Integer> restoreCount = value -> Platform.runLater(() -> {
                restoringCount[0] = true;
                try { views.getValueFactory().setValue(value); } finally { restoringCount[0] = false; }
            });
            views.valueProperty().addListener((obs, oldCount, count) -> {
                if (restoringCount[0]) return;
                if (count == 0 && library.findById(anime.id).rated()) {
                    message(t("Questo anime ha un voto", "This anime has a rating"),
                        t("Rimuovi prima il voto se vuoi azzerare tutte le visioni.", "Remove its rating before clearing all viewings."));
                    restoreCount.accept(oldCount); return;
                }
                boolean ask = saved.viewCount() == 0 && count > 0 && !saved.rated();
                if (mutate(() -> library.setViewCount(anime.id, count))) {
                    hours.setText(number(saved.watchedHours()) + t(" ore guardate", " hours watched"));
                    afterLibraryChange();
                    if ((oldCount == 0) != (count == 0)) refreshDetail();
                    if (ask) ratingDialog(library.findById(anime.id), true);
                } else {
                    restoreCount.accept(oldCount);
                }
            });
            Label viewsLabel = label(t("Visioni complete", "Completed viewings"), "anime-detail-control-label");
            viewsLabel.setLabelFor(views);
            libraryPanel.getChildren().add(new VBox(5, viewsLabel, views, hours));
            if (saved.viewCount() > 0) {
                Button rating = button(saved.rated() ? t("Modifica voti", "Edit ratings") : t("Vota questo anime", "Rate this anime"),
                    "anime-detail-rating-action", () -> ratingDialog(library.findById(anime.id), false));
                rating.setId("detail-rating");
                rating.setMaxWidth(Double.MAX_VALUE);
                HBox summary = row(label(t("Il tuo voto", "Your rating"), "anime-detail-control-label"), spacer(),
                    label(saved.rated() ? number(saved.overall()) : "—", "anime-detail-rating-value"));
                libraryPanel.getChildren().add(new VBox(5, summary, rating));
            }
            Button organise = button(t("Organizza cartelle", "Organise folders"), "anime-detail-source", () -> folderMembership(anime));
            organise.setId("detail-folders");
            organise.setMaxWidth(Double.MAX_VALUE);
            String folders = saved.folderIds.stream().map(library.folders()::get).filter(Objects::nonNull)
                .reduce((a,b) -> a + "\n" + b).orElse(t("Nessuna cartella", "No folders"));
            VBox folderStatus = new VBox(7, label(t("Cartelle", "Folders"), "anime-detail-control-label"), label(folders, "folder-names"), organise);
            folderStatus.getStyleClass().add("anime-detail-folders");
            libraryPanel.getChildren().add(folderStatus);
            Button remove = button(t("Rimuovi dalla lista", "Remove from library"), "anime-detail-remove", () ->
                confirm(t("Rimuovere questo anime?", "Remove this anime?"),
                    t("Verranno rimossi anche le sue visioni, le cartelle associate e il voto.", "Its viewings, folder memberships and rating will also be removed."), () -> {
                        if (mutate(() -> library.remove(anime.id))) { closeModal(detailModal); afterLibraryChange(); }
                    }));
            remove.setId("detail-remove");
            remove.setMaxWidth(Double.MAX_VALUE);
            libraryPanel.getChildren().add(remove);
        }
        return libraryPanel;
    }
    private StackPane detailHero(Anime anime) {
        Label title = label(meta(anime.title), "anime-detail-title");
        title.setId("anime-detail-title");
        if (title.getText().length() >= 95) title.getStyleClass().add("long-title");
        Label metadata = label(meta(anime.format) + "  ·  " + meta(anime.year) + "  ·  " + meta(anime.season)
            + "  ·  " + meta(anime.airingStatus), "anime-detail-meta");
        FlowPane tags = new FlowPane(7, 7);
        tags.getStyleClass().add("anime-detail-genres");
        if (anime.genres != null) for (String value : anime.genres) tags.getChildren().add(label(genre(value), "tag"));
        String trailer = trailerUrl(anime);
        Button trailerButton = button(trailer == null ? t("Trailer non disponibile", "No trailer available")
            : "▶  " + t("Guarda il trailer", "Watch trailer"), "anime-detail-trailer", () -> showTrailer(anime));
        trailerButton.setId("detail-trailer");
        trailerButton.setDisable(trailer == null);
        trailerButton.setTooltip(new Tooltip(trailer == null ? t("Trailer non disponibile", "No trailer available")
            : t("Riproduci direttamente nell'app", "Play directly in the app")));
        boolean fromMal = "JIKAN".equalsIgnoreCase(anime.provider), fromKitsu = "KITSU".equalsIgnoreCase(anime.provider);
        String sourceButton = fromMal ? t("Apri su MyAnimeList", "View on MyAnimeList")
            : fromKitsu ? t("Apri su Kitsu", "View on Kitsu") : t("Apri su AniList", "View on AniList");
        Button source = button(sourceButton + "  ↗", "anime-detail-source", () -> openUrl(animePageUrl(anime)));
        source.setId("detail-source");
        HBox external = row(trailerButton, source);
        external.getStyleClass().add("anime-detail-actions");

        VBox summary = new VBox(11, title, metadata);
        if (!tags.getChildren().isEmpty()) summary.getChildren().add(tags);
        summary.getChildren().add(external);
        summary.setMinWidth(0);
        summary.getStyleClass().add("anime-detail-summary");
        HBox.setHgrow(summary, Priority.ALWAYS);
        StackPane cover = image(covers, anime.coverImage, 160, 224, 21);
        cover.getStyleClass().add("anime-detail-cover");
        HBox intro = new HBox(25, cover, summary);
        intro.getStyleClass().add("anime-detail-intro");
        intro.setAlignment(Pos.CENTER_LEFT);

        ImageView artwork = new ImageView();
        artwork.setManaged(false);
        artwork.setMouseTransparent(true);
        artwork.setSmooth(true);
        Region shade = new Region();
        shade.getStyleClass().add("anime-detail-hero-shade");
        shade.setMouseTransparent(true);
        StackPane hero = new StackPane(artwork, shade, intro);
        hero.getStyleClass().add("anime-detail-hero");
        Rectangle clip = new Rectangle();
        clip.setArcWidth(38); clip.setArcHeight(38);
        clip.widthProperty().bind(hero.widthProperty()); clip.heightProperty().bind(hero.heightProperty());
        hero.setClip(clip);
        artwork.fitWidthProperty().bind(hero.widthProperty());
        artwork.fitHeightProperty().bind(hero.heightProperty());
        artwork.fitWidthProperty().addListener((obs, before, after) -> cropDetailArtwork(artwork));
        artwork.fitHeightProperty().addListener((obs, before, after) -> cropDetailArtwork(artwork));
        covers.load(anime.coverImage, 320, 448, fallback -> {
            if (artwork.getImage() == null) {
                artwork.setImage(fallback);
                artwork.setOpacity(.37);
                cropDetailArtwork(artwork);
            }
        });
        if (hasArtwork(anime.bannerImage) && !Objects.equals(anime.coverImage, anime.bannerImage)) {
            covers.load(anime.bannerImage, 1280, 720, banner -> {
                artwork.setImage(banner);
                artwork.setOpacity(banner.getWidth() >= banner.getHeight() * 1.3 ? .82 : .37);
                cropDetailArtwork(artwork);
            });
        }
        return hero;
    }

    private void cropDetailArtwork(ImageView view) {
        Image image = view.getImage();
        if (image == null || view.getFitWidth() <= 0 || view.getFitHeight() <= 0) return;
        double ratio = view.getFitWidth() / view.getFitHeight();
        double width = Math.min(image.getWidth(), image.getHeight() * ratio);
        double height = Math.min(image.getHeight(), image.getWidth() / ratio);
        double focus = image.getHeight() > image.getWidth() ? .27 : .5;
        view.setViewport(new Rectangle2D((image.getWidth() - width) / 2, (image.getHeight() - height) * focus, width, height));
    }

    private VBox detailOverview(Anime anime, Anime saved) {
        VBox overview = new VBox(13);
        overview.setMinWidth(0);
        HBox.setHgrow(overview, Priority.ALWAYS);
        String scoreSource = "JIKAN".equalsIgnoreCase(anime.provider) ? "MyAnimeList"
            : "KITSU".equalsIgnoreCase(anime.provider) ? "Kitsu" : "AniList";
        HBox facts = row(detailFact(anime.episodes > 0 ? String.valueOf(anime.episodes) : "—", t("Episodi", "Episodes")),
            detailFact(anime.duration > 0 ? anime.duration + " min" : "—", t("Per episodio", "Per episode")),
            detailFact(anime.averageScore > 0 ? anime.averageScore + "%" : "—", t("Media ", "Average on ") + scoreSource),
            detailFact(meta(anime.studio), "Studio"));
        facts.getStyleClass().add("anime-detail-facts");
        overview.getChildren().add(facts);
        String synopsis = Texts.synopsis(anime.description);
        Label plot = label(synopsis.isEmpty() ? t("Trama non disponibile.", "No synopsis available.") : en() ? synopsis : "Traduco la trama…", "synopsis");
        VBox plotText = new VBox(8, plot);
        VBox plotBox = new VBox(10, label(t("Trama", "Synopsis"), "anime-detail-heading"), plotText);
        plotBox.getStyleClass().add("anime-detail-plot");
        plotBox.setId("detail-plot");
        overview.getChildren().add(plotBox);
        if (!en() && !synopsis.isEmpty()) {
            Runnable translate = () -> async(() -> SynopsisTranslator.italian(synopsis, profile), plot, plot::setText, failure -> {
                plot.setText("Traduzione momentaneamente non disponibile.");
                Button original = button("Mostra originale inglese", "quiet", () -> plot.setText(synopsis));
                plotText.getChildren().setAll(plot, original);
            });
            Platform.runLater(translate);
        }
        VBox cast = new VBox(14);
        TitledPane castSection = detailDisclosure(t("Personaggi e doppiatori", "Characters & voice cast"), "detail-cast", cast);
        boolean[] castBuilt = {false};
        castSection.expandedProperty().addListener((obs, old, expanded) -> {
            if (expanded && !castBuilt[0]) { castBuilt[0] = true; renderCast(anime, cast); }
        });
        overview.getChildren().add(castSection);
        VBox related = new VBox(10);
        TitledPane relatedSection = detailDisclosure(t("Stagioni", "Seasons"), "detail-seasons", related);
        boolean[] relatedBuilt = {false};
        relatedSection.expandedProperty().addListener((obs, old, expanded) -> {
            if (expanded && !relatedBuilt[0]) {
                relatedBuilt[0] = true;
                related.getChildren().setAll(label(t("Caricamento stagioni…", "Loading seasons…"), "muted"));
                async(() -> api.seasons(anime), related, seasons -> {
                    related.getChildren().clear();
                    if (seasons.size() <= 1) { related.getChildren().add(label(t("Nessun'altra stagione disponibile.", "No other seasons available."), "muted")); return; }
                    for (int index = 0; index < seasons.size(); index++) {
                        Anime season = seasons.get(index);
                        HBox entry = row(label(String.format("%02d", index + 1), "ranking-position"),
                            image(covers, season.coverImage, 52, 72, 8),
                            new VBox(5, label(season.title, "body"), label(meta(season.year) + "  ·  " + meta(season.season), "tiny")),
                            spacer(), label(season.id == anime.id ? t("Attuale", "Current") : "→", "accent-text"));
                        entry.getStyleClass().addAll("list-row", "season-entry");
                        if (season.id != anime.id) {
                            entry.setFocusTraversable(true);
                            entry.setAccessibleRole(AccessibleRole.BUTTON);
                            entry.setAccessibleText(season.title);
                            entry.setOnMouseClicked(e -> { if (e.getButton() == MouseButton.PRIMARY) showDetails(season, true); });
                            entry.setOnKeyPressed(e -> {
                                if (e.getCode() == KeyCode.ENTER || e.getCode() == KeyCode.SPACE) { showDetails(season, true); e.consume(); }
                            });
                        }
                        related.getChildren().add(entry);
                    }
                }, error -> related.getChildren().setAll(label(t("Stagioni non disponibili.", "Seasons unavailable."), "muted")));
            }
        });
        overview.getChildren().add(relatedSection);
        if (saved != null && saved.viewCount() > 0 && saved.rated()) overview.getChildren().add(detailDisclosure(
            t("Dettaglio dei tuoi voti", "Your rating breakdown"), "detail-rating-breakdown", ratingBreakdown(saved)));
        return overview;
    }

    private VBox detailFact(String value, String caption) {
        VBox fact = new VBox(3, label(caption, "anime-detail-fact-label"), label(value, "anime-detail-fact-value"));
        fact.getStyleClass().add("anime-detail-fact");
        fact.setMinWidth(0);
        fact.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(fact, Priority.ALWAYS);
        return fact;
    }

    private TitledPane detailDisclosure(String title, String id, Node content) {
        TitledPane pane = new TitledPane(title, content);
        pane.setId(id);
        pane.getStyleClass().add("anime-detail-disclosure");
        pane.setExpanded(false);
        return pane;
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
        TrailerPage trailerPage;
        try { trailerPage = new TrailerPage(embed); }
        catch (IOException error) { message(t("Trailer", "Trailer"), t("Impossibile avviare il player.", "Could not start the player.")); return; }
        WebView player = new WebView();
        player.setContextMenuEnabled(false); player.setPrefSize(1120, 630); player.setMinHeight(0);
        ProgressIndicator loading = new ProgressIndicator(); loading.setMaxSize(42, 42);
        Label error = label(t("Il player non è riuscito a caricare il trailer.", "The player could not load the trailer."), "muted");
        Button browserButton = button(t("Apri nel browser", "Open in browser"), "quiet", () -> openUrl(browser));
        VBox failure = new VBox(14, error); failure.setAlignment(Pos.CENTER);
        failure.setVisible(false); failure.setManaged(false);
        StackPane frame = new RatioPane(); frame.getChildren().addAll(player, loading, failure);
        frame.setMaxWidth(Double.MAX_VALUE); frame.setPrefWidth(1120); frame.getStyleClass().add("trailer-frame");
        Rectangle playerClip = new Rectangle(); playerClip.widthProperty().bind(frame.widthProperty()); playerClip.heightProperty().bind(frame.heightProperty());
        playerClip.setArcWidth(24); playerClip.setArcHeight(24); frame.setClip(playerClip);
        VBox body = new VBox(14, frame, label(t("Se il servizio video impedisce la riproduzione incorporata, puoi aprirlo nel browser.",
            "If the video service blocks embedded playback, you can open it in your browser."), "tiny"), browserButton);
        StackPane dialog = modal(t("Trailer · ", "Trailer · ") + anime.title, body, 1280, true);
        player.getEngine().getLoadWorker().stateProperty().addListener((obs, old, state) -> {
            loading.setVisible(state == Worker.State.RUNNING || state == Worker.State.SCHEDULED);
            if (state == Worker.State.FAILED || state == Worker.State.CANCELLED) {
                failure.setManaged(true); failure.setVisible(true);
            }
        });
        dialog.getProperties().put("dispose", (Runnable) () -> { player.getEngine().load("about:blank"); trailerPage.close(); });
        player.getEngine().load(trailerPage.url());
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
        Label overall = label("", "rating-editor-average");
        overall.setId("rating-overall");
        Label specialStatus = label("", "rating-editor-special-state");
        Label animeTitle = label(meta(anime.title), "rating-editor-title");
        animeTitle.setWrapText(false);
        animeTitle.setMinWidth(0);
        animeTitle.setTooltip(new Tooltip(animeTitle.getText()));
        VBox heading = new VBox(8, animeTitle, label(t("Voti da 0 a 10. Puoi assegnare un solo 12.",
            "Score each category from 0 to 10. You can give one score of 12."), "rating-editor-hint"));
        heading.setMinWidth(0);
        VBox average = new VBox(0, label(t("Media", "Average"), "rating-editor-average-label"), overall);
        average.getStyleClass().add("rating-editor-readout");
        average.setAlignment(Pos.CENTER);
        HBox summary = row(heading, spacer(), specialStatus, average);
        summary.getStyleClass().add("rating-editor-summary");

        GridPane fields = new GridPane();
        fields.getStyleClass().add("rating-editor-grid");
        fields.setHgap(11); fields.setVgap(9);
        for (int i = 0; i < 2; i++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(50);
            fields.getColumnConstraints().add(column);
        }
        Map<RatingCategory, RatingInput> inputs = new EnumMap<>(RatingCategory.class);
        ToggleGroup specials = new ToggleGroup();
        PseudoClass bonusActive = PseudoClass.getPseudoClass("bonus-active");
        for (RatingCategory category : RatingCategory.values()) {
            String caption = en() ? category.en : category.it;
            Integer savedScore = anime.ratings.get(category.name());
            Slider score = new Slider(0, 10, savedScore == null ? 5 : Math.min(10, savedScore));
            score.setBlockIncrement(1); score.setMajorTickUnit(1); score.setMinorTickCount(0); score.setSnapToTicks(true);
            score.setShowTickMarks(true);
            score.setId("rating-" + category.name());
            score.getStyleClass().add("rating-editor-slider");
            score.setAccessibleText(caption);
            score.setMinWidth(0);
            HBox.setHgrow(score, Priority.ALWAYS);
            score.styleProperty().bind(Bindings.createStringBinding(() -> {
                int percent = (int) Math.round(score.getValue() * 10);
                return "-score-track: linear-gradient(to right, #bb92ff " + percent + "%, #3b2a53 " + percent + "%);";
            }, score.valueProperty()));

            ToggleButton bonus = new ToggleButton("12");
            bonus.getStyleClass().add("rating-editor-bonus");
            bonus.setId("rating-bonus-" + category.name());
            bonus.setAccessibleText(t("Voto speciale 12 per ", "Special score 12 for ") + caption);
            bonus.setTooltip(new Tooltip(t("Voto speciale 12", "Special score 12")));
            bonus.setUserData(category);
            bonus.setToggleGroup(specials);
            bonus.setSelected(savedScore != null && savedScore == 12);
            score.disableProperty().bind(bonus.selectedProperty());

            RatingInput input = new RatingInput(score, bonus);
            inputs.put(category, input);
            Label value = label("", "rating-editor-value");
            value.textProperty().bind(Bindings.createStringBinding(() -> Integer.toString(input.value()),
                score.valueProperty(), bonus.selectedProperty()));
            HBox range = row(label("0", "rating-editor-endpoint"), score, label("10", "rating-editor-endpoint"), bonus);
            range.getStyleClass().add("rating-editor-range");
            VBox card = new VBox(4, row(label(caption, "rating-editor-category"), ratingHelp(category, caption), spacer(), value), range);
            card.getStyleClass().add("rating-editor-criterion");
            card.pseudoClassStateChanged(bonusActive, bonus.isSelected());
            bonus.selectedProperty().addListener((obs, before, active) -> card.pseudoClassStateChanged(bonusActive, active));
            fields.add(card, category.ordinal() % 2, category.ordinal() / 2);
        }
        Label validation = label("", "error");
        validation.visibleProperty().bind(validation.textProperty().isNotEmpty());
        validation.managedProperty().bind(validation.visibleProperty());
        ScrollPane scoreScroll = scroll(fields);
        scoreScroll.setId("rating-scroll");
        scoreScroll.getStyleClass().add("rating-editor-scroll");
        scoreScroll.setPrefViewportHeight(438);
        VBox body = new VBox(12, summary, scoreScroll, validation);
        StackPane dialog = modal(t("La tua valutazione", "Your rating"), body, 1120, true);
        dialog.setId("rating-dialog");
        dialog.getStyleClass().add("rating-editor-modal");
        dialog.getStylesheets().add(Objects.requireNonNull(App.class.getResource("rating-dialog.css")).toExternalForm());
        Region surface = (Region) dialog.getChildren().getFirst();
        surface.maxHeightProperty().bind(root.heightProperty().subtract(60).map(height -> Math.min(714, height.doubleValue())));
        Button save = button(t("Salva in classifica", "Save to ranking"), "rating-editor-save", () -> {
            Map<String, Integer> scores = new LinkedHashMap<>();
            inputs.forEach((category, input) -> scores.put(category.name(), input.value()));
            try { RatingCategory.validate(scores); }
            catch (IllegalArgumentException e) { validation.setText(t("Completa tutti i voti: 0–10 e al massimo un 12.", "Complete all scores: 0–10 and at most one 12.")); return; }
            if (mutate(() -> library.setRating(anime.id, scores))) { closeModal(dialog); afterLibraryChange(); refreshDetail(); }
        });
        save.setId("save-rating");
        HBox actions = row();
        actions.getStyleClass().add("rating-editor-actions");
        if (anime.rated()) {
            Button remove = button(t("Rimuovi voto", "Remove rating"), "rating-editor-remove", () ->
                confirm(t("Rimuovere il voto?", "Remove this rating?"), t("L'anime uscirà dalla classifica. Le visioni non cambieranno.",
                "This anime will leave your ranking. Its viewings will not change."), () -> {
                    if (mutate(() -> library.setRating(anime.id, Map.of()))) { closeModal(dialog); afterLibraryChange(); refreshDetail(); }
                }));
            remove.setId("rating-remove");
            actions.getChildren().add(remove);
        }
        Button cancel = button(optional ? t("Non ora", "Not now") : t("Annulla", "Cancel"), "rating-editor-cancel", () -> closeModal(dialog));
        cancel.setId("rating-cancel");
        actions.getChildren().addAll(spacer(), cancel, save);
        body.getChildren().add(actions);

        Runnable update = () -> {
            int sum = inputs.values().stream().mapToInt(RatingInput::value).sum();
            overall.setText(number(sum / (double) inputs.size()));
            RatingCategory bonus = specials.getSelectedToggle() == null ? null : (RatingCategory) specials.getSelectedToggle().getUserData();
            specialStatus.setText(bonus == null ? t("12 speciale disponibile", "Special score 12 available")
                : "12 · " + (en() ? bonus.en : bonus.it));
            specialStatus.pseudoClassStateChanged(bonusActive, bonus != null);
            validation.setText("");
        };
        inputs.values().forEach(input -> input.slider().valueProperty().addListener((obs, before, after) -> update.run()));
        specials.selectedToggleProperty().addListener((obs, before, after) -> update.run());
        update.run();
    }

    private record RatingInput(Slider slider, ToggleButton bonus) {
        int value() { return bonus.isSelected() ? 12 : (int) Math.round(slider.getValue()); }
    }

    private Button ratingHelp(RatingCategory category, String caption) {
        Tooltip tooltip = new Tooltip(en() ? category.helpEn : category.helpIt);
        tooltip.setWrapText(true);
        tooltip.setMaxWidth(290);
        tooltip.setAutoHide(true);
        Button help = new Button("?");
        help.getStyleClass().add("rating-editor-help");
        help.setTooltip(tooltip);
        help.setAccessibleText(t("Cosa significa ", "What does this mean: ") + caption);
        help.setAccessibleHelp(tooltip.getText());
        help.setOnAction(event -> {
            if (tooltip.isShowing()) { tooltip.hide(); return; }
            Bounds target = help.localToScreen(help.getBoundsInLocal());
            if (target != null) tooltip.show(help, target.getMinX(), target.getMaxY() + 5);
        });
        return help;
    }

    private void rankingPage() {
        List<Anime> ranking = library.ranking();
        VBox page = page(null, null);
        page.setAlignment(Pos.TOP_CENTER);
        VBox content = new VBox(18, label(routeName(Route.RANKING), "title"));
        content.setMaxWidth(1080);
        if (ranking.isEmpty()) {
            content.getChildren().add(empty(t("Nessun voto", "No ratings yet"),
                t("Puoi votare dalla scheda di un anime visto.", "You can rate a completed anime from its details."),
                t("Apri la mia lista", "Open my library"), () -> navigate(Route.LIBRARY)));
        } else {
            HBox podium = new HBox(18);
            podium.setAlignment(Pos.BOTTOM_CENTER);
            podium.getStyleClass().add("ranking-podium");
            List<Integer> positions = switch (ranking.size()) {
                case 1 -> List.of(1);
                case 2 -> List.of(1, 2);
                default -> List.of(2, 1, 3);
            };
            for (int position : positions)
                podium.getChildren().add(rankingPodiumCard(ranking.get(position - 1), position));
            content.getChildren().add(podium);
            VBox entries = new VBox(8);
            for (int index = 3; index < ranking.size(); index++) {
                entries.getChildren().add(rankingListEntry(ranking.get(index), index + 1));
            }
            if (!entries.getChildren().isEmpty()) content.getChildren().add(entries);
        }
        page.getChildren().add(content);
        shell.setCenter(scroll(page));
    }

    private Button rankingPodiumCard(Anime anime, int position) {
        boolean winner = position == 1;
        int width = winner ? 252 : 207;
        int height = winner ? 354 : 290;
        StackPane cover = image(covers, anime.coverImage, width, height, 34);
        Region shade = new Region();
        shade.getStyleClass().add("ranking-podium-shade");
        Label rank = label("#" + position, "ranking-position");
        rank.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        HBox score = rankingScore(anime);
        StackPane.setAlignment(rank, Pos.TOP_LEFT);
        StackPane.setAlignment(score, Pos.TOP_RIGHT);
        StackPane.setMargin(rank, new Insets(12));
        StackPane.setMargin(score, new Insets(12));

        Label title = label(anime.title, "ranking-podium-title");
        title.setPrefWidth(width - 30);
        title.setMaxWidth(width - 30);
        title.setMaxHeight(winner ? 62 : 50);
        VBox caption = new VBox(5, title, label(meta(anime.format) + "  ·  " + meta(anime.year), "ranking-podium-meta"));
        caption.getStyleClass().add("ranking-podium-caption");
        caption.setMaxHeight(Region.USE_PREF_SIZE);
        StackPane.setAlignment(caption, Pos.BOTTOM_CENTER);
        StackPane poster = new StackPane(cover, shade, rank, score, caption);
        poster.setMinSize(width, height);
        poster.setPrefSize(width, height);
        poster.setMaxSize(width, height);
        Rectangle clip = new Rectangle(width, height);
        clip.setArcWidth(34);
        clip.setArcHeight(34);
        poster.setClip(clip);

        Button card = rankingButton(anime, position, "ranking-podium-card", poster, cover);
        card.getStyleClass().add(switch (position) {
            case 1 -> "ranking-gold";
            case 2 -> "ranking-silver";
            default -> "ranking-bronze";
        });
        return card;
    }

    private Button rankingListEntry(Anime anime, int position) {
        Label rank = label("#" + position, "ranking-list-position");
        Label name = label(anime.title, "ranking-list-title");
        name.setWrapText(false);
        name.setMinWidth(0);
        Label details = label(meta(anime.format) + "  ·  " + anime.viewCount() + t(" visioni", " viewings"), "tiny");
        details.setWrapText(false);
        VBox title = new VBox(3, name, details);
        title.setMinWidth(0);
        title.setMaxWidth(Double.MAX_VALUE);
        title.setMaxHeight(Region.USE_PREF_SIZE);
        HBox.setHgrow(title, Priority.ALWAYS);
        SVGPath chevron = new SVGPath();
        chevron.setContent("M 1,1 L 5,5 L 1,9");
        chevron.getStyleClass().add("ranking-chevron");
        StackPane cover = image(covers, anime.coverImage, 42, 59, 12);
        HBox entry = new HBox(14, rank, cover,
            title, rankingScore(anime), chevron);
        entry.setAlignment(Pos.CENTER_LEFT);
        Button button = rankingButton(anime, position, "ranking-entry", entry, cover);
        entry.prefWidthProperty().bind(button.widthProperty().subtract(32));
        entry.setMinWidth(0);
        button.setMinHeight(82);
        button.setPrefHeight(82);
        button.setMaxHeight(82);
        button.setMaxWidth(Double.MAX_VALUE);
        return button;
    }

    private Button rankingButton(Anime anime, int position, String style, Node content, StackPane cover) {
        Button button = button("", style, () -> showDetails(anime, false, cover));
        button.setGraphic(content);
        button.setAccessibleText("#" + position + " · " + anime.title + " · "
            + t("Voto ", "Rating ") + number(anime.overall()));
        return button;
    }

    private HBox rankingScore(Anime anime) {
        SVGPath star = new SVGPath();
        star.setContent("M 6,0 L 7.8,3.8 L 12,4.4 L 9,7.5 L 9.7,11.8 L 6,9.8 L 2.3,11.8 L 3,7.5 L 0,4.4 L 4.2,3.8 Z");
        star.getStyleClass().add("ranking-score-star");
        HBox score = new HBox(6, star, label(number(anime.overall()), "ranking-score-value"));
        score.getStyleClass().add("ranking-score");
        score.setAlignment(Pos.CENTER);
        score.setMinWidth(Region.USE_PREF_SIZE);
        score.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
        return score;
    }

    private void discoverPage() {
        VBox page = page(null, null);
        page.setAlignment(Pos.TOP_CENTER);
        TextField search = new TextField();
        search.setId("online-search");
        search.setPromptText(t("Cerca un anime…", "Search anime…"));
        search.setAccessibleText(t("Cerca anime", "Search anime"));
        SVGPath searchIcon = discoverIcon("M 12.5,7.5 A 5,5 0 1,1 2.5,7.5 A 5,5 0 0,1 12.5,7.5 Z M 11.1,11.1 L 15.5,15.5");
        StackPane input = new StackPane(search, searchIcon);
        input.getStyleClass().add("discover-search-input");
        StackPane.setAlignment(searchIcon, Pos.CENTER_LEFT);
        StackPane.setMargin(searchIcon, new Insets(0, 0, 0, 16));
        HBox.setHgrow(input, Priority.ALWAYS);
        VBox content = new VBox(26);
        DiscoverSuggestions suggestions = new DiscoverSuggestions(search, content);
        Button submit = button(t("Cerca", "Search"), "discover-search-submit", () -> onlineSearch(search.getText(), content, suggestions));
        Region searchDivider = new Region();
        searchDivider.getStyleClass().add("discover-search-divider");
        HBox searchCapsule = row(input, searchDivider, submit);
        searchCapsule.getStyleClass().add("discover-search-capsule");
        HBox.setHgrow(searchCapsule, Priority.ALWAYS);
        PseudoClass typing = PseudoClass.getPseudoClass("typing");
        search.focusedProperty().addListener((obs, before, focused) -> searchCapsule.pseudoClassStateChanged(typing, focused));
        ToggleButton filterToggle = new ToggleButton();
        filterToggle.setId("discover-filters-toggle");
        filterToggle.getStyleClass().add("discover-filters-toggle");
        filterToggle.setGraphic(discoverIcon("M 1,4 H 7 M 10,4 H 16 M 7,1.5 V 6.5 M 1,12 H 11 M 14,12 H 16 M 11,9.5 V 14.5"));
        HBox command = row(searchCapsule, filterToggle);
        command.getStyleClass().add("discover-command");
        GridPane fields = new GridPane();
        fields.setHgap(14);
        fields.setVgap(13);
        for (int index = 0; index < 3; index++) {
            ColumnConstraints column = new ColumnConstraints();
            column.setPercentWidth(100.0 / 3);
            fields.getColumnConstraints().add(column);
        }
        List<String> genreOptions = new ArrayList<>(); genreOptions.add(""); genreOptions.addAll(Preferences.GENRES);
        ComboBox<String> genreBox = combo(genreOptions, discoverFilters.genre(), value -> value.isBlank() ? t("Tutti", "Any") : genre(value));
        genreBox.setId("discover-genre");
        List<Integer> years = new ArrayList<>(); years.add(0);
        for (int year = Year.now().getValue() + 1; year >= 1960; year--) years.add(year);
        ComboBox<Integer> yearBox = combo(years, discoverFilters.year(), value -> value == 0 ? t("Tutti", "Any") : Integer.toString(value));
        ComboBox<DiscoverQuery.Sort> sortBox = combo(List.of(DiscoverQuery.Sort.values()), discoverFilters.sort(), value -> switch (value) {
            case POPULARITY -> t("Popolarità", "Popularity"); case TITLE_ASC -> "A–Z";
            case TITLE_DESC -> "Z–A"; case RATING -> t("Valutazione", "Rating");
        });
        ComboBox<DiscoverQuery.Status> statusBox = combo(List.of(DiscoverQuery.Status.values()), discoverFilters.status(), value -> switch (value) {
            case ANY -> t("Tutti", "Any"); case RELEASING -> t("In corso", "Releasing");
            case FINISHED -> t("Concluso", "Finished"); case NOT_YET_RELEASED -> t("In arrivo", "Upcoming");
            case CANCELLED -> t("Cancellato", "Cancelled"); case HIATUS -> "Hiatus";
        });
        ComboBox<DiscoverQuery.Format> formatBox = combo(List.of(DiscoverQuery.Format.values()), discoverFilters.format(), value -> value == DiscoverQuery.Format.ANY ? t("Tutti", "Any") : value == DiscoverQuery.Format.TV_SHORT ? "TV Short" : value.toString());
        ComboBox<DiscoverQuery.Season> seasonBox = combo(List.of(DiscoverQuery.Season.values()), discoverFilters.season(), value -> switch (value) {
            case ANY -> t("Tutte", "Any"); case WINTER -> t("Inverno", "Winter");
            case SPRING -> t("Primavera", "Spring"); case SUMMER -> t("Estate", "Summer");
            case FALL -> t("Autunno", "Fall");
        });
        fields.addRow(0, discoverFilter(t("Genere", "Genre"), genreBox), discoverFilter(t("Tipo", "Format"), formatBox),
            discoverFilter(t("Stato", "Status"), statusBox));
        fields.addRow(1, discoverFilter(t("Anno", "Year"), yearBox), discoverFilter(t("Stagione", "Season"), seasonBox),
            discoverFilter(t("Ordina", "Sort"), sortBox));
        for (ComboBox<?> box : List.of(genreBox, yearBox, sortBox, statusBox, formatBox, seasonBox)) box.setVisibleRowCount(10);
        Button reset = button(t("Azzera filtri", "Reset filters"), "discover-filters-reset", () -> {});
        reset.setId("discover-filters-reset");
        HBox filterActions = new HBox(reset);
        filterActions.setAlignment(Pos.CENTER_RIGHT);
        VBox filters = new VBox(12, fields, filterActions);
        filters.setId("discover-filter-panel");
        filters.getStyleClass().add("discover-filters");
        filters.visibleProperty().bind(filterToggle.selectedProperty());
        filters.managedProperty().bind(filters.visibleProperty());
        filterToggle.setOnAction(e -> {
            suggestions.hide();
            if (filterToggle.isSelected()) entrance(filters, 0);
        });
        VBox toolbar = new VBox(10, command, filters);
        toolbar.setMaxWidth(1080);
        page.getChildren().addAll(toolbar, content);
        ScrollPane scrolling = scroll(page);
        shell.setCenter(scrolling);

        PauseTransition delay = new PauseTransition(Duration.millis(110));
        searchDelay = delay;
        List<Anime> previous = new ArrayList<>();
        Runnable updateFilterCount = () -> {
            int count = 0;
            if (!discoverFilters.genre().isBlank()) count++;
            if (discoverFilters.year() != 0) count++;
            if (discoverFilters.sort() != DiscoverQuery.Sort.POPULARITY) count++;
            if (discoverFilters.status() != DiscoverQuery.Status.ANY) count++;
            if (discoverFilters.format() != DiscoverQuery.Format.ANY) count++;
            if (discoverFilters.season() != DiscoverQuery.Season.ANY) count++;
            String caption = t("Filtri", "Filters");
            filterToggle.setText(count > 0 ? caption + " · " + count : caption);
            reset.setDisable(count == 0);
        };
        boolean[] resetting = {false};
        Runnable applyFilters = () -> {
            if (resetting[0]) return;
            discoverFilters = new DiscoverQuery("", genreBox.getValue(), yearBox.getValue(), sortBox.getValue(),
                statusBox.getValue(), formatBox.getValue(), seasonBox.getValue());
            previous.clear();
            searchGeneration++;
            delay.stop();
            suggestions.hide();
            updateFilterCount.run();
            if (!search.getText().isBlank() || discoverFilters.hasFilters()) onlineSearch(search.getText(), content, suggestions);
            else discoverHome(content, scrolling);
        };
        for (ComboBox<?> box : List.of(genreBox, yearBox, sortBox, statusBox, formatBox, seasonBox))
            box.valueProperty().addListener((obs, old, value) -> applyFilters.run());
        reset.setOnAction(e -> {
            resetting[0] = true;
            genreBox.setValue("");
            yearBox.setValue(0);
            sortBox.setValue(DiscoverQuery.Sort.POPULARITY);
            statusBox.setValue(DiscoverQuery.Status.ANY);
            formatBox.setValue(DiscoverQuery.Format.ANY);
            seasonBox.setValue(DiscoverQuery.Season.ANY);
            resetting[0] = false;
            applyFilters.run();
        });
        search.textProperty().addListener((obs, old, query) -> {
            int generation = ++searchGeneration;
            delay.stop();
            if (query.isBlank()) {
                suggestions.hide();
                return;
            }
            DiscoverQuery filter = discoverFilters.withText(query);
            List<Anime> available = new ArrayList<>(previous);
            if (!discoverFilters.hasFilters()) available.addAll(library.all());
            String term = query.strip().toLowerCase(Locale.ROOT);
            Set<Integer> shown = new HashSet<>();
            List<Anime> instant = available.stream()
                .filter(anime -> anime.title != null && anime.title.toLowerCase(Locale.ROOT).contains(term) && shown.add(anime.id))
                .limit(5).toList();
            if (!instant.isEmpty()) suggestions.show(instant);
            else if (suggestions.isShowing()) suggestions.showMessage(t("Ricerca in corso…", "Searching…"));
            delay.setOnFinished(e -> async(() -> api.search(filter, 1, 5), search, results -> {
                if (generation != searchGeneration || !search.isFocused()) return;
                if (!results.isEmpty()) {
                    previous.clear(); previous.addAll(results);
                    suggestions.show(results);
                } else if (instant.isEmpty()) {
                    suggestions.showMessage(t("Nessun suggerimento", "No suggestions"));
                }
            }, error -> {
                if (generation == searchGeneration && search.isFocused() && instant.isEmpty()) {
                    suggestions.showMessage(t("Suggerimenti online non disponibili", "Online suggestions unavailable"));
                }
            }));
            delay.playFromStart();
        });
        search.addEventFilter(KeyEvent.KEY_PRESSED, e -> {
            if ((e.getCode() == KeyCode.DOWN && suggestions.move(1)) || (e.getCode() == KeyCode.UP && suggestions.move(-1))) {
                e.consume();
            } else if (e.getCode() == KeyCode.ESCAPE && suggestions.isShowing()) {
                searchGeneration++;
                delay.stop();
                suggestions.hide();
                e.consume();
            } else if (e.getCode() == KeyCode.TAB) {
                searchGeneration++;
                delay.stop();
                suggestions.hide();
            }
        });
        search.setOnAction(e -> {
            if (!suggestions.openSelected()) onlineSearch(search.getText(), content, suggestions);
        });
        scrolling.vvalueProperty().addListener((obs, old, value) -> {
            searchGeneration++;
            delay.stop();
            suggestions.hide();
        });
        updateFilterCount.run();
        if (discoverFilters.hasFilters()) onlineSearch("", content, suggestions);
        else discoverHome(content, scrolling);
    }

    private SVGPath discoverIcon(String path) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.getStyleClass().add("discover-command-icon");
        icon.setMouseTransparent(true);
        return icon;
    }

    private VBox discoverFilter(String title, ComboBox<?> box) {
        box.setMinWidth(0);
        box.setMaxWidth(Double.MAX_VALUE);
        box.getStyleClass().add("discover-filter-box");
        Label heading = label(title, "discover-filter-label");
        heading.setLabelFor(box);
        return new VBox(6, heading, box);
    }

    private final class DiscoverSuggestions {
        private final PseudoClass highlighted = PseudoClass.getPseudoClass("highlighted");
        private final Popup popup = new Popup();
        private final Label message = label("", "suggestion-message");
        private final VBox choices = new VBox(3);
        private final VBox panel = new VBox(3, message, choices);
        private final TextField search;
        private final VBox content;
        private final Button all;
        private List<Anime> displayed = List.of();
        private int selected = -1;

        DiscoverSuggestions(TextField search, VBox content) {
            this.search = search;
            this.content = content;
            panel.getStyleClass().add("suggestions");
            message.managedProperty().bind(message.visibleProperty());
            all = button(t("Vedi tutti i risultati", "See all results"), "suggestion-all", () -> onlineSearch(search.getText(), content, this));
            all.setId("suggestion-all");
            all.setMaxWidth(Double.MAX_VALUE);
            popup.setAutoHide(true);
            popup.setHideOnEscape(false);
            popup.setConsumeAutoHidingEvents(false);
            popup.getContent().add(panel);
            popup.setOnHidden(e -> select(-1));
            popup.setOnAutoHide(e -> {
                searchGeneration++;
                searchDelay.stop();
            });
            search.sceneProperty().addListener((obs, old, scene) -> { if (scene == null) hide(); });
        }

        void show(List<Anime> results) {
            update(results, "");
        }

        void showMessage(String text) {
            update(List.of(), text);
        }

        private void update(List<Anime> results, String text) {
            if (!search.isFocused()) return;
            Point2D anchor = search.localToScreen(0, search.getHeight() + 7);
            if (anchor == null) return;
            double width = Math.min(490, search.getWidth());
            List<Anime> visible = List.copyOf(results.subList(0, Math.min(5, results.size())));
            boolean unchanged = panel.getPrefWidth() == width && message.getText().equals(text) && sameRows(visible);
            displayed = visible;
            if (!unchanged) renderRows(text, width);
            if (popup.isShowing()) {
                popup.setAnchorX(anchor.getX());
                popup.setAnchorY(anchor.getY());
            } else popup.show(search, anchor.getX(), anchor.getY());
        }

        private boolean sameRows(List<Anime> results) {
            if (displayed.size() != results.size()) return false;
            for (int i = 0; i < displayed.size(); i++) {
                Anime old = displayed.get(i);
                Anime current = results.get(i);
                if (old.id != current.id || !Objects.equals(old.title, current.title) || !Objects.equals(old.coverImage, current.coverImage)
                    || !Objects.equals(old.format, current.format) || !Objects.equals(old.year, current.year)) return false;
            }
            return true;
        }

        private void renderRows(String text, double width) {
            panel.setMinWidth(width);
            panel.setPrefWidth(width);
            panel.setMaxWidth(width);
            Node highlightedChoice = selected < 0 ? null : choices.getChildren().get(selected);
            String remembered = highlightedChoice == null ? null : highlightedChoice.getId();
            select(-1);
            List<Node> rows = new ArrayList<>();
            for (Anime anime : displayed) {
                int rowIndex = rows.size();
                Label name = label(anime.title, "suggestion-title");
                name.setWrapText(false);
                name.setMinWidth(0);
                VBox description = new VBox(2, name,
                    label(meta(anime.format) + "  ·  " + (anime.year == null ? "—" : meta(anime.year)), "tiny"));
                description.setMinWidth(0);
                HBox.setHgrow(description, Priority.ALWAYS);
                Button choice = button("", "suggestion", () -> {
                    searchGeneration++;
                    searchDelay.stop();
                    hide();
                    showDetails(displayed.get(rowIndex), false);
                });
                choice.setId("suggestion-" + anime.id);
                choice.setAccessibleText(anime.title);
                choice.setMaxWidth(Double.MAX_VALUE);
                HBox graphic = row(image(covers, anime.coverImage, 32, 44, 8), description);
                graphic.setMinWidth(0);
                graphic.setPrefWidth(width - 36);
                graphic.setMaxWidth(width - 36);
                choice.setGraphic(graphic);
                rows.add(choice);
            }
            rows.add(all);
            message.setText(text);
            message.setVisible(!text.isEmpty());
            choices.getChildren().setAll(rows);
            for (int index = 0; index < choices.getChildren().size(); index++)
                if (Objects.equals(remembered, choices.getChildren().get(index).getId())) select(index);
        }

        boolean isShowing() { return popup.isShowing(); }
        void hide() { popup.hide(); }

        boolean move(int direction) {
            int count = choices.getChildren().size();
            if (!isShowing() || count == 0) return false;
            select(selected < 0 ? (direction > 0 ? 0 : count - 1) : Math.floorMod(selected + direction, count));
            return true;
        }

        boolean openSelected() {
            if (!isShowing() || selected < 0) return false;
            ((Button) choices.getChildren().get(selected)).fire();
            return true;
        }

        private void select(int index) {
            if (selected >= 0) choices.getChildren().get(selected).pseudoClassStateChanged(highlighted, false);
            selected = index;
            if (selected >= 0) choices.getChildren().get(selected).pseudoClassStateChanged(highlighted, true);
        }
    }

    private void onlineSearch(String text, VBox content, DiscoverSuggestions suggestions) {
        String query = text.strip(); if (query.isEmpty() && !discoverFilters.hasFilters()) return;
        searchGeneration++; searchDelay.stop();
        suggestions.hide();
        if (carousel != null) { carousel.stop(); carousel = null; }
        DiscoverQuery filter = discoverFilters.withText(query);
        String heading = query.isEmpty() ? t("Risultati", "Results") : t("Risultati per ", "Results for ") + "“" + query + "”";
        content.getChildren().setAll(row(label(heading, "section-title"), spacer(),
            button(t("Torna a Esplora", "Back to Explore"), "quiet", () -> {
                discoverFilters = DiscoverQuery.empty();
                navigate(Route.DISCOVER);
            })));
        VBox results = new VBox(20, label(t("Ricerca in corso…", "Searching…"), "muted")); content.getChildren().add(results);
        TilePane grid = posterGrid(20, 24);
        Button more = button(t("Carica altri", "Load more"), "load-more", () -> {});
        more.setMaxWidth(Double.MAX_VALUE); more.setMinHeight(54);
        int[] page = {1}; Set<Integer> shown = new HashSet<>();
        Runnable load = () -> {
            more.setDisable(true);
            async(() -> api.search(filter, page[0], 30), results, found -> {
                if (page[0] == 1) results.getChildren().setAll(grid, more);
                int added = 0;
                for (Anime anime : found) if (shown.add(anime.id)) {
                    Node card = animeCard(anime);
                    grid.getChildren().add(card);
                    if (page[0] == 1 && added < 8) entrance(card, added);
                    added++;
                }
                page[0]++; more.setDisable(false);
                if (found.size() < 30 || added == 0) { more.setVisible(false); more.setManaged(false); }
                if (shown.isEmpty()) results.getChildren().setAll(label(t("Nessun anime trovato.", "No anime found."), "muted"));
            }, error -> networkRetry(results, () -> onlineSearch(query, content, suggestions)));
        };
        more.setOnAction(e -> load.run()); load.run();
    }
    private void discoverHome(VBox content, ScrollPane scrolling) {
        content.getChildren().clear();
        carouselInView = true;
        BannerDeck deck = new BannerDeck();
        HBox bannerArea = new HBox(deck); bannerArea.setAlignment(Pos.TOP_CENTER);
        HBox.setHgrow(deck, Priority.ALWAYS); content.getChildren().add(bannerArea);
        content.getChildren().add(label(t("Esplora il catalogo", "Explore the catalogue"), "title"));
        List<DiscoverSection> sections = new ArrayList<>();
        for (String favourite : prefs.genres())
            sections.add(new DiscoverSection("♥ " + genre(favourite), "GENRE", favourite, content, scrolling));
        sections.add(new DiscoverSection(t("Popolari", "Popular"), "POPULAR", null, content, scrolling));
        sections.add(new DiscoverSection(t("Nuove uscite", "New releases"), "RECENT", null, content, scrolling));
        for (String other : Preferences.GENRES)
            if (!prefs.genres().contains(other)) sections.add(new DiscoverSection(genre(other), "GENRE", other, content, scrolling));
        for (DiscoverSection section : sections) content.getChildren().add(section.box);
        boolean[] checkQueued = {false};
        Runnable scheduleVisibility = () -> {
            if (checkQueued[0]) return;
            checkQueued[0] = true;
            Platform.runLater(() -> {
                checkQueued[0] = false;
                if (deck.getScene() == null) return;
                Bounds viewport = scrolling.localToScene(scrolling.getLayoutBounds());
                Bounds bannerBounds = deck.localToScene(deck.getLayoutBounds());
                boolean bannerVisible = bannerBounds.getMaxY() > viewport.getMinY()
                    && bannerBounds.getMinY() < viewport.getMaxY();
                if (carouselInView != bannerVisible) {
                    carouselInView = bannerVisible;
                    syncCarousel();
                }
                for (DiscoverSection section : sections) section.checkVisibility(viewport);
            });
        };
        ChangeListener<Number> scrollListener = (obs, old, value) -> scheduleVisibility.run();
        scrolling.vvalueProperty().addListener(scrollListener);
        ChangeListener<Bounds> viewportListener = (obs, old, value) -> scheduleVisibility.run();
        scrolling.viewportBoundsProperty().addListener(viewportListener);
        // The deck stays attached while a slide changes and detaches when leaving Discover home.
        deck.sceneProperty().addListener((obs, old, scene) -> {
            if (scene == null) {
                scrolling.vvalueProperty().removeListener(scrollListener);
                scrolling.viewportBoundsProperty().removeListener(viewportListener);
            } else {
                scheduleVisibility.run();
            }
        });
        scheduleVisibility.run();
    }
    private final class DiscoverSection {
        final VBox box = new VBox(14);
        final VBox items = new VBox();
        final String mode, filter;
        List<Anime> data;
        boolean loading, visible;
        DiscoverSection(String title, String mode, String filter, VBox content, ScrollPane scrolling) {
            this.mode = mode; this.filter = filter;
            items.setMinHeight(422);
            box.getChildren().addAll(row(label(title, "section-title"), spacer(),
                button(t("Vedi tutti", "See all") + " →", "quiet", () -> categoryPage(content, title, mode, filter, scrolling))), items);
        }
        void checkVisibility(Bounds viewport) {
            Bounds bounds = box.localToScene(box.getLayoutBounds());
            // Keep nearby rows warm when scrolling back across a boundary.
            double margin = visible ? 750 : 260;
            boolean near = bounds.getMaxY() >= viewport.getMinY() - margin && bounds.getMinY() <= viewport.getMaxY() + margin;
            if (!near) {
                if (visible) {
                    visible = false;
                    items.getChildren().clear();
                }
                return;
            }
            if (visible) return;
            visible = true;
            if (data != null) show(); else if (!loading) load();
        }
        void load() {
            loading = true;
            items.getChildren().setAll(label(t("Caricamento…", "Loading…"), "muted"));
            async(() -> api.browse(mode, filter, 1, 30), box, found -> {
                loading = false; data = found; if (visible) show();
            }, error -> { loading = false; if (visible) networkRetry(items, this::load); });
        }
        void show() {
            if (data.isEmpty()) { items.getChildren().setAll(label(t("Nessun titolo disponibile.", "No titles available."), "muted")); return; }
            DiscoverStrip strip = new DiscoverStrip(data);
            HBox controls = row(spacer(), button("←", "quiet", () -> strip.page(-1)),
                button("→", "quiet", () -> strip.page(1)));
            items.getChildren().setAll(strip.pane, controls);
        }
    }

    /** Fixed-width slots let the scrollbar represent every title while only visible cards exist. */
    private final class DiscoverStrip {
        private static final double CARD_WIDTH = 200, STEP = 218, PADDING = 10;
        private final HBox cards = new HBox(STEP - CARD_WIDTH);
        private final ScrollPane pane = new ScrollPane(cards);
        private final Timeline motion = new Timeline();
        private final List<Anime> titles;
        private final List<StackPane> slots = new ArrayList<>();
        private int first, end;
        private double target;

        DiscoverStrip(List<Anime> titles) {
            this.titles = titles;
            cards.setPadding(new Insets(7, PADDING, 20, PADDING));
            cards.setAlignment(Pos.TOP_CENTER);
            for (Anime title : titles) {
                StackPane slot = new StackPane();
                slot.setAlignment(Pos.TOP_CENTER);
                slot.setMinWidth(CARD_WIDTH);
                slot.setPrefWidth(CARD_WIDTH);
                slot.setMaxWidth(CARD_WIDTH);
                slots.add(slot);
            }
            cards.getChildren().setAll(slots);
            pane.setId("discover-strip");
            pane.setMinHeight(382);
            pane.setPrefHeight(382);
            pane.setMaxHeight(382);
            pane.setFitToWidth(true);
            pane.setFitToHeight(true);
            pane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            pane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
            pane.viewportBoundsProperty().addListener((obs, old, bounds) -> updateVisibleCards());
            pane.hvalueProperty().addListener((obs, old, value) -> updateVisibleCards());
            pane.sceneProperty().addListener((obs, old, scene) -> { if (scene == null) motion.stop(); });
            pane.addEventFilter(ScrollEvent.SCROLL, event -> {
                boolean horizontal = Math.abs(event.getDeltaX()) > Math.abs(event.getDeltaY());
                if (!event.isShiftDown() && !horizontal) return;
                double range = overflow();
                if (range == 0) return;
                double delta = horizontal ? event.getDeltaX() : event.getDeltaY();
                motion.stop();
                pane.setHvalue(clamp(pane.getHvalue() - delta * (event.isDirect() ? 1 : 2) / range));
                event.consume();
            });
        }

        private double overflow() {
            double total = PADDING * 2 + (titles.size() - 1) * STEP + CARD_WIDTH;
            return Math.max(0, total - pane.getViewportBounds().getWidth());
        }

        private void updateVisibleCards() {
            double width = pane.getViewportBounds().getWidth();
            if (width <= 0) return;
            double left = overflow() * pane.getHvalue() - PADDING;
            int nextFirst = Math.max(0, (int) Math.floor(left / STEP) - 1);
            int nextEnd = Math.min(titles.size(), (int) Math.ceil((left + width) / STEP) + 1);
            for (int index = first; index < end; index++)
                if (index < nextFirst || index >= nextEnd) slots.get(index).getChildren().clear();
            for (int index = nextFirst; index < nextEnd; index++)
                if (index < first || index >= end) slots.get(index).getChildren().add(animeCard(titles.get(index)));
            first = nextFirst;
            end = nextEnd;
        }

        void page(int direction) {
            double range = overflow();
            if (range == 0) return;
            double from = motion.getStatus() == Animation.Status.RUNNING ? target : pane.getHvalue();
            target = clamp(from + direction * pane.getViewportBounds().getWidth() * .85 / range);
            motion.stop();
            motion.getKeyFrames().setAll(new KeyFrame(Duration.millis(180),
                new KeyValue(pane.hvalueProperty(), target, Interpolator.EASE_OUT)));
            motion.playFromStart();
        }

        private double clamp(double value) {
            return Math.max(0, Math.min(1, value));
        }
    }
    private final class BannerDeck extends StackPane {
        private static final double ASPECT_RATIO = 16.0 / 9;
        private static final double MAX_FEATURED_WIDTH = 1040;
        private static final double PANEL_GAP = 14;

        private final StackPane banner = bannerFrame("banner");
        private final StackPane left = bannerFrame("banner-side");
        private final StackPane right = bannerFrame("banner-side");
        private final ImageView posterImage = new ImageView();
        private final StackPane poster = new StackPane(posterImage);
        private final BannerArt artwork = new BannerArt(banner, posterImage);
        private final BannerArt leftArt = new BannerArt(left, null);
        private final BannerArt rightArt = new BannerArt(right, null);
        private final Label section = label("", "banner-kicker");
        private final Label title = label("", "banner-title");
        private final Label metadata = label("", "banner-meta");
        private final Label count = label("", "banner-count");
        private final Label previousTitle = label("", "banner-side-title");
        private final Label nextTitle = label("", "banner-side-title");
        private final HBox progress = new HBox(6);
        private final HBox positionIndicator = new HBox(12, progress, count);
        private final Button open = button(t("Apri scheda", "Open details"), "banner-open", this::openCurrent);
        private final Button previous = arrow(-1);
        private final Button next = arrow(1);
        private final Button retry = button(t("Riprova", "Retry"), "quiet", this::fetch);
        private final VBox caption = new VBox(10, section, title, metadata, open);
        private final HBox footer = new HBox(12, positionIndicator, spacer(), retry);
        private final Region shade = new Region();
        private List<Anime> slides = List.of();
        private int position;
        private Animation captionMotion;

        BannerDeck() {
            getStyleClass().add("banner-deck");
            setPrefWidth(1160);
            setMaxWidth(Double.MAX_VALUE);
            setMinWidth(0);

            configureNeighbour(left, leftArt, previous, previousTitle, -1);
            configureNeighbour(right, rightArt, next, nextTitle, 1);
            artwork.setId("discover-featured-backdrop");
            artwork.outgoing.setId("discover-featured-outgoing");
            left.setVisible(false);
            right.setVisible(false);

            shade.getStyleClass().add("banner-gradient");
            shade.setMouseTransparent(true);
            configurePoster();
            caption.getStyleClass().add("banner-caption");
            caption.setAlignment(Pos.CENTER_LEFT);
            caption.setPickOnBounds(false);
            caption.setMaxHeight(Region.USE_PREF_SIZE);
            caption.maxWidthProperty().bind(banner.widthProperty().multiply(.61));
            title.setMaxHeight(126);
            open.focusedProperty().addListener((obs, before, focused) -> syncCarousel());
            StackPane.setAlignment(caption, Pos.BOTTOM_LEFT);

            progress.setAlignment(Pos.CENTER_LEFT);
            positionIndicator.setAlignment(Pos.CENTER_LEFT);
            positionIndicator.setMouseTransparent(true);
            footer.setAlignment(Pos.CENTER_LEFT);
            footer.getStyleClass().add("banner-footer");
            footer.setPickOnBounds(false);
            footer.setMaxHeight(Region.USE_PREF_SIZE);
            StackPane.setAlignment(footer, Pos.BOTTOM_CENTER);
            banner.setOnMouseClicked(event -> {
                if (event.getTarget() == banner) openCurrent();
            });
            getChildren().addAll(left, right, banner);

            // ScrollPane can attach its content after the first pulse. Wait before using the deck as the async owner.
            sceneProperty().addListener((obs, before, scene) -> {
                if (scene != null) {
                    fetch();
                } else {
                    stopCarousel();
                }
            });
            setOnMouseEntered(event -> syncCarousel());
            setOnMouseExited(event -> syncCarousel());
        }

        private void configureNeighbour(StackPane panel, BannerArt art, Button control, Label name, int direction) {
            panel.setId(direction < 0 ? "discover-featured-left-panel" : "discover-featured-right-panel");
            Region veil = new Region();
            veil.getStyleClass().add("banner-side-veil");
            veil.setMouseTransparent(true);
            name.setMouseTransparent(true);
            name.setMaxHeight(48);
            name.visibleProperty().bind(panel.widthProperty().greaterThanOrEqualTo(205));
            name.managedProperty().bind(name.visibleProperty());
            HBox navigation = direction < 0 ? new HBox(13, control, name) : new HBox(13, name, control);
            navigation.getStyleClass().add("banner-neighbour-navigation");
            navigation.setPickOnBounds(false);
            navigation.setMaxHeight(Region.USE_PREF_SIZE);
            navigation.alignmentProperty().bind(Bindings.when(name.visibleProperty())
                .then(direction < 0 ? Pos.CENTER_LEFT : Pos.CENTER_RIGHT).otherwise(Pos.CENTER));
            StackPane.setAlignment(navigation, Pos.BOTTOM_CENTER);
            panel.getChildren().addAll(art.outgoing, art, veil, navigation);
            panel.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && event.getTarget() == panel) moveManually(direction);
            });
        }

        private void configurePoster() {
            posterImage.setId("discover-featured-cover");
            posterImage.setPreserveRatio(true);
            posterImage.setSmooth(true);
            posterImage.fitWidthProperty().bind(banner.widthProperty().multiply(.29));
            posterImage.fitHeightProperty().bind(banner.heightProperty().multiply(.69));
            Rectangle clip = new Rectangle();
            clip.setArcWidth(16);
            clip.setArcHeight(16);
            posterImage.layoutBoundsProperty().addListener((obs, before, bounds) -> {
                clip.setWidth(bounds.getWidth());
                clip.setHeight(bounds.getHeight());
            });
            posterImage.setClip(clip);
            poster.getStyleClass().add("banner-poster");
            poster.setMouseTransparent(true);
            poster.setMaxSize(Region.USE_PREF_SIZE, Region.USE_PREF_SIZE);
            poster.visibleProperty().bind(posterImage.imageProperty().isNotNull());
            StackPane.setAlignment(poster, Pos.CENTER_RIGHT);
            StackPane.setMargin(poster, new Insets(0, 46, 28, 0));
        }

        private StackPane bannerFrame(String style) {
            StackPane frame = new StackPane();
            frame.setManaged(false);
            frame.getStyleClass().add(style);
            Rectangle clip = new Rectangle();
            clip.setArcWidth(36);
            clip.setArcHeight(36);
            clip.widthProperty().bind(frame.widthProperty());
            clip.heightProperty().bind(frame.heightProperty());
            frame.setClip(clip);
            return frame;
        }

        private Button arrow(int direction) {
            Button arrow = button("", "banner-button", () -> moveManually(direction));
            SVGPath chevron = new SVGPath();
            chevron.setContent(direction < 0 ? "M 11 2 L 4 9 L 11 16" : "M 5 2 L 12 9 L 5 16");
            chevron.getStyleClass().add("banner-chevron");
            chevron.setMouseTransparent(true);
            arrow.setGraphic(chevron);
            arrow.setId(direction < 0 ? "discover-featured-previous" : "discover-featured-next");
            arrow.setAccessibleText(direction < 0 ? t("Anime precedente", "Previous anime") : t("Anime successivo", "Next anime"));
            arrow.focusedProperty().addListener((obs, before, focused) -> syncCarousel());
            return arrow;
        }

        private void fetch() {
            stopCarousel();
            slides = List.of();
            left.setVisible(false);
            right.setVisible(false);
            ProgressIndicator spinner = new ProgressIndicator();
            spinner.setMaxSize(24, 24);
            showMessage(spinner, label(t("Caricamento tendenze…", "Loading trending anime…"), "muted"));
            async(() -> api.browse("TRENDING", null, 1, 8), this, found -> {
                if (found.isEmpty()) showFallback();
                else showSlides(found, t("Di tendenza", "Trending"), false);
            }, failure -> showFallback());
        }

        private void showFallback() {
            List<Anime> saved = library.all().stream().filter(anime -> hasArtwork(anime.coverImage) || hasArtwork(anime.bannerImage))
                .limit(6).toList();
            if (saved.isEmpty()) {
                showMessage(label(t("Tendenze non disponibili", "Trending anime unavailable"), "section-title"),
                    button(t("Riprova", "Retry"), "quiet", this::fetch));
            } else {
                showSlides(saved, t("Dalla tua lista", "From your library"), true);
            }
        }

        private void showMessage(Node... children) {
            VBox state = new VBox(12, children);
            state.setAlignment(Pos.CENTER);
            banner.getChildren().setAll(state);
        }

        private void showSlides(List<Anime> found, String category, boolean fallback) {
            slides = found.stream().limit(6).toList();
            position = 0;
            section.setText(category);
            retry.setVisible(fallback);
            retry.setManaged(fallback);
            boolean multiple = slides.size() > 1;
            left.setVisible(multiple);
            right.setVisible(multiple);
            positionIndicator.setVisible(multiple);
            positionIndicator.setManaged(multiple);
            banner.getChildren().setAll(artwork.outgoing, artwork, shade, poster, caption, footer);
            showCurrent(0);
            if (multiple) {
                carousel = new Timeline(new KeyFrame(Duration.seconds(8), event -> move(1)));
                carousel.setCycleCount(Animation.INDEFINITE);
                carouselHoverTarget = this;
                syncCarousel();
            }
        }

        private void openCurrent() {
            if (!slides.isEmpty()) showDetails(slides.get(position), false, poster);
        }

        private void moveManually(int direction) {
            move(direction);
            if (carousel != null) {
                carousel.jumpTo(Duration.ZERO);
                syncCarousel();
            }
        }

        private void move(int direction) {
            if (slides.size() < 2) return;
            position = Math.floorMod(position + direction, slides.size());
            showCurrent(direction);
        }

        private void showCurrent(int direction) {
            Anime item = slides.get(position);
            Image outgoingFeature = artwork.getImage();
            Image incomingPreview = direction == 0 ? null : (direction > 0 ? rightArt : leftArt).getImage();
            title.setText(item.title == null || item.title.isBlank() ? t("Anime senza titolo", "Untitled anime") : item.title);
            title.getStyleClass().removeAll("banner-title-long", "banner-title-very-long");
            if (title.getText().length() > 65) title.getStyleClass().add("banner-title-very-long");
            else if (title.getText().length() > 38) title.getStyleClass().add("banner-title-long");
            metadata.setText(meta(item.format) + "  ·  " + meta(item.year));
            artwork.show(item, incomingPreview, direction);
            count.setText((position + 1) + " / " + slides.size());
            count.setAccessibleText("Anime " + (position + 1) + t(" di ", " of ") + slides.size());

            if (slides.size() > 1) {
                Anime before = slides.get(Math.floorMod(position - 1, slides.size()));
                Anime after = slides.get((position + 1) % slides.size());
                leftArt.show(before, direction > 0 ? outgoingFeature : null, direction);
                rightArt.show(after, direction < 0 ? outgoingFeature : null, direction);
                previousTitle.setText(before.title);
                nextTitle.setText(after.title);
                previous.setAccessibleText(t("Anime precedente", "Previous anime") + " · " + previousTitle.getText());
                next.setAccessibleText(t("Anime successivo", "Next anime") + " · " + nextTitle.getText());
                progress.getChildren().clear();
                for (int i = 0; i < slides.size(); i++) {
                    Region step = new Region();
                    step.getStyleClass().add("banner-step");
                    if (i == position) step.getStyleClass().add("banner-step-active");
                    progress.getChildren().add(step);
                }
            }

            if (captionMotion != null) captionMotion.stop();
            caption.setTranslateX(direction * 14);
            caption.setOpacity(.66);
            FadeTransition fade = new FadeTransition(Duration.millis(180), caption);
            fade.setToValue(1);
            TranslateTransition slide = new TranslateTransition(Duration.millis(220), caption);
            slide.setToX(0);
            slide.setInterpolator(Interpolator.EASE_OUT);
            captionMotion = new ParallelTransition(fade, slide);
            captionMotion.play();
        }

        private boolean isInteracting() {
            return isHover() || previous.isFocused() || next.isFocused() || open.isFocused();
        }

        private void stopCarousel() {
            if (carouselHoverTarget == this) {
                if (carousel != null) carousel.stop();
                carousel = null;
                carouselHoverTarget = null;
            }
            if (captionMotion != null) captionMotion.stop();
            artwork.clear();
            leftArt.clear();
            rightArt.clear();
        }

        @Override public Orientation getContentBias() { return Orientation.HORIZONTAL; }
        @Override protected double computeMinHeight(double width) { return featuredHeight(width); }
        @Override protected double computePrefHeight(double width) { return featuredHeight(width); }
        @Override protected double computeMaxHeight(double width) { return featuredHeight(width); }

        private double featuredHeight(double width) {
            return featuredWidth(width > 0 ? width : 1160) / ASPECT_RATIO;
        }

        private double featuredWidth(double width) {
            return Math.min(MAX_FEATURED_WIDTH, width * .73);
        }

        @Override protected void layoutChildren() {
            double width = getWidth();
            double height = featuredHeight(width);
            double centralWidth = featuredWidth(width);
            double sideWidth = Math.max(0, (width - centralWidth) / 2 - PANEL_GAP);
            double sideHeight = height * .88;
            double sideY = (height - sideHeight) / 2;
            left.resizeRelocate(0, sideY, sideWidth, sideHeight);
            right.resizeRelocate(width - sideWidth, sideY, sideWidth, sideHeight);
            banner.resizeRelocate((width - centralWidth) / 2, 0, centralWidth, height);
        }
    }

    private final class BannerArt extends ImageView {
        private final ImageView posterImage;
        private final ImageView outgoing = new ImageView();
        private long revision;
        private Timeline motion;
        private ParallelTransition posterMotion;

        private BannerArt(StackPane frame, ImageView posterImage) {
            this.posterImage = posterImage;
            for (ImageView layer : List.of(this, outgoing)) {
                layer.setManaged(false);
                layer.setMouseTransparent(true);
                layer.setSmooth(true);
                layer.fitWidthProperty().bind(frame.widthProperty());
                layer.fitHeightProperty().bind(frame.heightProperty());
                layer.fitWidthProperty().addListener((obs, before, after) -> crop(layer));
                layer.fitHeightProperty().addListener((obs, before, after) -> crop(layer));
            }
        }

        void show(Anime anime, Image preview, int direction) {
            long request = ++revision;
            if (motion != null) motion.stop();
            Image old = getImage() == null ? outgoing.getImage() : getImage();
            holdOutgoing(old);
            setImage(null);
            setTranslateX(0);
            if (posterImage != null) {
                if (posterMotion != null) posterMotion.stop();
                posterImage.setImage(null);
            }
            if (preview != null) display(preview, direction);
            else if (old != null) animate(direction, 0);

            String cover = anime.coverImage;
            String wide = anime.bannerImage;
            boolean hasWide = hasArtwork(wide) && !Objects.equals(cover, wide);
            if (posterImage != null) {
                covers.load(cover, 520, 740, image -> {
                    if (revision != request) return;
                    showPoster(image, direction);
                    if (getImage() == null || isPortrait(getImage())) display(image, direction);
                });
                if (hasWide) covers.load(wide, 1280, 720, image -> {
                    if (revision == request && (getImage() == null || !isPortrait(image))) display(image, direction);
                });
            } else if (hasWide) {
                covers.loadOrNull(wide, 1280, 720, image -> {
                    if (revision != request) return;
                    if (image != null) display(image, direction);
                    else showNeighbourCover(cover, request, direction);
                });
            } else {
                showNeighbourCover(cover, request, direction);
            }
        }

        private void showNeighbourCover(String cover, long request, int direction) {
            covers.load(cover, 520, 740, image -> {
                if (revision == request) display(image, direction);
            });
        }

        private void showPoster(Image image, int direction) {
            posterImage.setImage(image);
            posterImage.setOpacity(.35);
            posterImage.setTranslateX(direction * 30);
            FadeTransition fade = new FadeTransition(Duration.millis(230), posterImage);
            fade.setToValue(1);
            TranslateTransition slide = new TranslateTransition(Duration.millis(265), posterImage);
            slide.setToX(0);
            slide.setInterpolator(Interpolator.EASE_OUT);
            posterMotion = new ParallelTransition(fade, slide);
            posterMotion.play();
        }

        private void display(Image image, int direction) {
            if (getImage() == image) return;
            double offset = getTranslateX();
            double opacity = getOpacity();
            double outgoingOffset = outgoing.getTranslateX();
            double outgoingOpacity = outgoing.getOpacity();
            boolean sliding = getImage() != null && direction != 0 && Math.abs(offset) > 1;
            if (motion != null) motion.stop();
            if (getImage() != null && !sliding) {
                holdOutgoing(getImage());
                direction = 0;
            }
            setImage(image);
            crop(this);
            if (sliding) {
                // A higher-resolution asset may arrive while the neighbour preview is already moving.
                setOpacity(opacity);
                setTranslateX(offset);
                outgoing.setOpacity(outgoingOpacity);
                outgoing.setTranslateX(outgoingOffset);
            } else {
                setOpacity(.13);
                setTranslateX(direction * Math.min(130, getFitWidth() * .16));
            }
            animate(direction, backdropOpacity(image));
        }

        private void holdOutgoing(Image image) {
            outgoing.setImage(image);
            outgoing.setOpacity(image == null ? 0 : backdropOpacity(image));
            outgoing.setTranslateX(0);
            crop(outgoing);
        }

        private void animate(int direction, double opacity) {
            double duration = direction == 0 ? 180 : 310;
            double exit = direction * -Math.min(84, getFitWidth() * .11);
            motion = new Timeline(new KeyFrame(Duration.millis(duration),
                new KeyValue(translateXProperty(), 0, Interpolator.EASE_OUT),
                new KeyValue(opacityProperty(), opacity, Interpolator.EASE_OUT),
                new KeyValue(outgoing.translateXProperty(), exit, Interpolator.EASE_OUT),
                new KeyValue(outgoing.opacityProperty(), 0, Interpolator.EASE_IN)));
            motion.setOnFinished(event -> outgoing.setImage(null));
            motion.play();
        }

        void clear() {
            revision++;
            if (motion != null) motion.stop();
            if (posterMotion != null) posterMotion.stop();
            setImage(null);
            outgoing.setImage(null);
            if (posterImage != null) posterImage.setImage(null);
        }

        private boolean isPortrait(Image image) {
            return image.getWidth() < image.getHeight() * 1.2;
        }

        private double backdropOpacity(Image image) {
            return posterImage != null && isPortrait(image) ? .40 : 1;
        }

        private void crop(ImageView layer) {
            Image image = layer.getImage();
            if (image == null || layer.getFitWidth() <= 0 || layer.getFitHeight() <= 0) return;
            double ratio = layer.getFitWidth() / layer.getFitHeight();
            double width = Math.min(image.getWidth(), image.getHeight() * ratio);
            double height = Math.min(image.getHeight(), image.getWidth() / ratio);
            double focusY = image.getHeight() > image.getWidth() ? .29 : .5;
            layer.setViewport(new Rectangle2D((image.getWidth() - width) / 2, (image.getHeight() - height) * focusY, width, height));
        }
    }

    private static boolean hasArtwork(String url) { return url != null && !url.isBlank(); }
    private void categoryPage(VBox content, String title, String mode, String filter, ScrollPane scrolling) {
        if (carousel != null) { carousel.stop(); carousel = null; }
        VBox results = new VBox(20); TilePane grid = posterGrid(20, 24);
        Button more = button(t("Carica altri", "Load more"), "quiet", () -> {});
        more.setMaxWidth(Double.MAX_VALUE); more.setMinHeight(58); more.getStyleClass().add("load-more");
        int[] page = {1}; Set<Integer> shown = new HashSet<>();
        Runnable load = () -> {
            more.setDisable(true); more.setText(t("Caricamento…", "Loading…"));
            async(() -> api.browse(mode, filter, page[0], 30), results, found -> {
                int added = 0;
                for (Anime anime : found) if (shown.add(anime.id)) {
                    Node card = animeCard(anime);
                    grid.getChildren().add(card);
                    if (page[0] == 1 && added < 8) entrance(card, added);
                    added++;
                }
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
        VBox page = page(routeName(Route.SETTINGS), null);
        page.getStyleClass().add("settings-page");
        ComboBox<String> language = combo(List.of("it", "en"), prefs.language(), code -> code.equals("it") ? "Italiano" : "English");
        language.setId("settings-language");
        language.setOnAction(e -> {
            if (savePreferences(() -> prefs.values.setProperty("language", language.getValue()))) navigate(Route.SETTINGS);
        });
        page.getChildren().add(panel(label(t("Lingua", "Language"), "section-title"), language,
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
            row(button(t("Esporta classifica", "Export ranking"), "quiet", () -> exportData(true)),
                button(t("Importa classifica", "Import ranking"), "quiet", () -> importData(true)),
                button(t("Resetta classifica", "Reset ranking"), "danger", () ->
                    confirm(t("Azzerare tutti i voti?", "Reset all ratings?"),
                        t("La classifica verrà svuotata. Anime, cartelle e ore guardate resteranno invariati. Puoi prima esportare una copia.",
                            "Your ranking will be cleared. Anime, folders and watched hours will remain unchanged. You can export a backup first."), () -> {
                            if (mutate(library::resetRanking)) { settingsPage(); message(t("Classifica resettata", "Ranking reset"), t("Tutti i voti sono stati rimossi.", "All ratings have been removed.")); }
                        })))));
        page.getChildren().add(panel(label(t("File e backup", "Files & backup"), "section-title"),
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
            row(check, button(t("Apri le release", "Open releases"), "quiet", () -> openUrl(RELEASES)))));
        List<Node> sections = new ArrayList<>(page.getChildren());
        VBox rankingCard = (VBox) sections.get(3), filesCard = (VBox) sections.get(4);
        HBox rankingButtons = (HBox) rankingCard.getChildren().remove(1);
        HBox libraryButtons = (HBox) filesCard.getChildren().remove(1);
        FlowPane rankingActions = new FlowPane(10, 10), libraryActions = new FlowPane(10, 10);
        List<Node> rankingNodes = List.copyOf(rankingButtons.getChildren()); rankingButtons.getChildren().clear();
        List<Node> libraryNodes = List.copyOf(libraryButtons.getChildren()); libraryButtons.getChildren().clear();
        rankingActions.getChildren().setAll(rankingNodes); libraryActions.getChildren().setAll(libraryNodes);
        VBox libraryFiles = new VBox(12, label(t("Lista", "Library"), "settings-subtitle"), libraryActions);
        VBox rankingFiles = new VBox(12, label(t("Classifica", "Ranking"), "settings-subtitle"), rankingActions);
        libraryFiles.getStyleClass().add("settings-file-group"); rankingFiles.getStyleClass().add("settings-file-group");
        libraryFiles.setMaxWidth(Double.MAX_VALUE); rankingFiles.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(libraryFiles, Priority.ALWAYS); HBox.setHgrow(rankingFiles, Priority.ALWAYS);
        filesCard.getChildren().addAll(new HBox(16, libraryFiles, rankingFiles), new Separator(),
            label(t("Cartella dati: ", "Data folder: ") + profile, "settings-data-path"));
        GridPane grid = new GridPane(); grid.setHgap(18); grid.setVgap(18);
        for (int i = 0; i < 2; i++) {
            ColumnConstraints column = new ColumnConstraints(); column.setPercentWidth(50); column.setHgrow(Priority.ALWAYS);
            grid.getColumnConstraints().add(column);
        }
        Node[] ordered = {sections.get(1), sections.get(2), filesCard, sections.get(5)};
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
        }
        grid.add(ordered[0], 0, 0); grid.add(ordered[1], 1, 0);
        grid.add(ordered[2], 0, 1, 2, 1); grid.add(ordered[3], 0, 2, 2, 1);
        shell.setCenter(scroll(page));
    }
    private void onboarding(boolean genresOnly) {
        Set<String> selected = new LinkedHashSet<>(prefs.genres());
        VBox body = new VBox(20);
        StackPane dialog = modal(genresOnly ? t("Generi anime", "Anime genres") : "MyAnimeDesk", body, 820, genresOnly);
        Runnable genresStep = () -> {
            body.getChildren().clear();
            body.getChildren().add(label(t("Che generi anime ti piacciono?", "Which anime genres do you like?"), "title"));
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
            HBox actions = row(spacer(),
                button(genresOnly ? t("Salva preferenze", "Save preferences") : t("Iniziamo", "Let's begin") + " →", "primary", complete));
            body.getChildren().add(actions);
        };
        if (genresOnly) genresStep.run();
        else {
            Label welcome = label(t("Benvenuto", "Welcome"), "hero-title");
            Label prompt = label("Scegli la tua lingua / Choose your language", "subtitle");
            ComboBox<String> language = combo(List.of("it", "en"), prefs.language(), code -> code.equals("it") ? "Italiano" : "English");
            language.setId("welcome-language");
            Button next = button(t("Continua", "Continue") + " →", "primary", genresStep);
            language.setOnAction(e -> {
                prefs.values.setProperty("language", language.getValue());
                welcome.setText(t("Benvenuto", "Welcome"));
                next.setText(t("Continua", "Continue") + " →");
            });
            body.getChildren().addAll(label("✦", "loading-emblem"), welcome, prompt, language, next);
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
        async(() -> AppUpdater.latest(VERSION), output, release -> {
            check.setDisable(false);
            output.setText(release != null ? t("Aggiornamento disponibile: ", "Update available: ") + release.version()
                : t("Nessun nuovo aggiornamento installabile.", "No new installable update."));
            if (release != null) installUpdate(release);
        }, error -> { check.setDisable(false); output.setText(t("Controllo non disponibile. Riprova più tardi.", "Check unavailable. Try again later.")); });
    }
    private void installUpdate(AppUpdater.Release release) {
        if (updateInProgress) return;
        try { AppUpdater.installation(); }
        catch (IOException error) { message(t("Aggiornamento", "Update"), t("L'aggiornamento automatico funziona dalla versione .exe. Questa è una sessione di sviluppo.", "Automatic updates work in the packaged .exe. This is a development session.")); return; }
        updateInProgress = true;
        VBox body = new VBox(18, new ProgressIndicator(), label(t("Scarico e verifico la versione ", "Downloading and verifying version ") + release.version(), "body"),
            label(t("L'app si riavvierà automaticamente. Lista e impostazioni saranno conservate.", "The app will restart automatically. Your library and settings will be kept."), "muted"));
        StackPane dialog = modal(t("Aggiornamento in corso", "Updating"), body, 600, false);
        async(() -> { AppUpdater.stageAndLaunch(release, profile); return true; }, root,
            done -> { shutdown(); window.close(); Platform.exit(); },
            error -> { updateInProgress = false; closeModal(dialog); message(t("Aggiornamento non completato", "Update not completed"), error.getMessage()); });
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
        return modal(title, body, width, dismissible, true);
    }

    private StackPane modal(String title, VBox body, double width, boolean dismissible, boolean enter) {
        hideCardPopup();
        VBox surface = new VBox(20); surface.getStyleClass().add("modal-surface");
        surface.setMaxWidth(width); surface.setMaxHeight(Region.USE_PREF_SIZE);
        surface.prefWidthProperty().bind(root.widthProperty().subtract(64).map(w -> Math.min(width, w.doubleValue())));
        double preferredHeight = width >= 1000 ? 10000 : width >= 800 ? 550 : width >= 650 ? 750 : 440;
        surface.maxHeightProperty().bind(root.heightProperty().subtract(60).map(h -> Math.min(preferredHeight, h.doubleValue())));
        HBox heading = row(label(title, "section-title"), spacer());
        heading.getStyleClass().add("modal-heading");
        StackPane overlay = new StackPane(surface); overlay.getStyleClass().add("modal-shade"); overlay.setPadding(new Insets(30));
        overlay.getProperties().put("dismissible", dismissible);
        if (dismissible) {
            Button close = button("✕", "quiet", () -> closeModal(overlay));
            close.getStyleClass().add("modal-close");
            close.setAccessibleText(t("Chiudi", "Close"));
            heading.getChildren().add(close);
        }
        surface.getChildren().addAll(heading, body);
        VBox.setVgrow(body, Priority.ALWAYS); body.setMinHeight(0);
        if (!modals.isEmpty()) modals.peek().setDisable(true);
        if (shell != null) shell.setDisable(true);
        surface.setFocusTraversable(true);
        modals.push(overlay); root.getChildren().add(overlay); syncCarousel();
        if (enter) {
            entrance(surface, 0);
            Platform.runLater(surface::requestFocus);
        }
        return overlay;
    }
    private void closeModal(StackPane overlay) {
        if (!closed && overlay == detailModal && overlay == modals.peek() && detailMotion != null) {
            detailGeneration++;
            detailMotion.close(() -> removeModal(overlay));
            return;
        }
        removeModal(overlay);
    }
    private void removeModal(StackPane overlay) {
        Object dispose = overlay.getProperties().remove("dispose");
        if (dispose instanceof Runnable action) action.run();
        root.getChildren().remove(overlay); modals.remove(overlay);
        if (!modals.isEmpty()) modals.peek().setDisable(false);
        if (shell != null) shell.setDisable(!modals.isEmpty());
        if (overlay == detailModal) {
            if (detailMotion != null) detailMotion.dispose();
            detailMotion = null;
            if (!closed && modals.isEmpty() && detailReturnFocus != null && detailReturnFocus.getScene() == root.getScene())
                detailReturnFocus.requestFocus();
            detailReturnFocus = null;
            detailGeneration++;
            detailModal = null;
            detailBody = null;
            currentDetail = null;
            detailHistory.clear();
        }
        syncCarousel();
    }
    private void closeTopModal() {
        if (!modals.isEmpty() && Boolean.TRUE.equals(modals.peek().getProperties().get("dismissible"))) closeModal(modals.peek());
    }
    private void closeAllModals() { while (!modals.isEmpty()) removeModal(modals.peek()); }
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
        if (!closed && route == Route.DISCOVER && carouselInView && modals.isEmpty() && window.isShowing() && !window.isIconified() && window.isFocused()
            && (carouselHoverTarget == null || !carouselHoverTarget.isInteracting()))
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
        if (root != null) closeAllModals();
        if (carousel != null) carousel.stop();
        if (searchDelay != null) searchDelay.stop();
        for (Future<?> job : pending) job.cancel(true); pending.clear();
        workers.shutdownNow();
        if (api != null) api.close();
        if (covers != null) covers.close();
        if (animeNews != null) animeNews.close();
        detailCache.clear();
        try { if (instanceLock != null && instanceLock.isValid()) instanceLock.release(); } catch (IOException ignored) { }
        try { if (lockChannel != null) lockChannel.close(); } catch (IOException ignored) { }
    }
    @Override public void stop() { shutdown(); }
}
