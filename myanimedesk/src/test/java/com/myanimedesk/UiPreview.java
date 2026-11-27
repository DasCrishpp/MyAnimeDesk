package com.myanimedesk;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.beans.value.ChangeListener;
import javafx.event.Event;
import javafx.geometry.Bounds;
import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.PickResult;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.TilePane;
import javafx.scene.robot.Robot;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Isolated interactive preview with explicitly labelled fixture data; never uses the user's library. */
public class UiPreview {
    static final List<Anime> FIXTURES = new ArrayList<>();
    public static void main(String[] args) throws Exception {
        int sampleSize = Math.max(4, Integer.getInteger("myanimedesk.preview.sampleSize", 4));
        boolean longTitle = Boolean.getBoolean("myanimedesk.preview.longTitle");
        String capture = System.getProperty("myanimedesk.preview.capture", "");
        boolean emptyDashboard = capture.equals("dashboard-empty");
        boolean missingArt = capture.equals("dashboard-missing-art");
        boolean dashboardArtwork = capture.equals("dashboard-artwork");
        boolean discoverMotion = capture.equals("discover-motion");
        boolean animeDetail = isAnimeDetail(capture);
        if (dashboardArtwork) sampleSize = Math.max(sampleSize, 8);
        String profileName = "ui-preview-profile" + (sampleSize == 4 ? "" : "-" + sampleSize)
            + (longTitle ? "-long" : "") + (emptyDashboard || missingArt || dashboardArtwork || discoverMotion || animeDetail ? "-" + capture : "");
        Path profile = Path.of("target", profileName).toAbsolutePath().normalize();
        System.setProperty("user.home", profile.toString());
        System.setProperty("myanimedesk.windowSuffix", "-PREVIEW-FIXTURES");
        String[] titles={"Tokyo Ghoul","The Promised Neverland","Chainsaw Man","Akame ga Kill!"};
        String[] urls={"https://s4.anilist.co/file/anilistcdn/media/anime/cover/medium/b20605-k665mVkSug8D.jpg",
            "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx101759-8UR7r9MNVpz2.jpg",
            "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx127230-DdP4vAdssLoz.png",
            "https://s4.anilist.co/file/anilistcdn/media/anime/cover/large/bx20613-HXHpec4bemk5.jpg"};
        int[] ids={20605,101759,127230,20613};
        for(int i=0;i<4;i++) {
            Anime a=new Anime();a.id=ids[i];a.title=titles[i];a.coverImage=urls[i];a.bannerImage=urls[i];
            a.episodes=12;a.duration=24;a.format="TV";a.year=Integer.toString(2020+i);a.season="Primavera";a.studio="TEST FIXTURE";
            a.genres=List.of("Action","Drama");a.detailsLoaded=true;
            a.description="TEST FIXTURE — This is sample text used only to verify the layout, not the anime's real synopsis.\n\nA longer paragraph checks wrapping and readability on smaller windows. Characters, relationships and scores in this preview are test data.";
            a.trailerSite="youtube";a.trailerId="test_fixture";
            Anime.CastMember member=new Anime.CastMember();member.id=i+1;member.name="Test character";member.role="MAIN";
            member.voiceActor="Test voice actor";member.image=urls[i];member.voiceImage=urls[i];a.cast.add(member);FIXTURES.add(a);
        }
        if (longTitle)
            FIXTURES.get(0).title = "Tokyo Ghoul: Stories Beyond the Last Winter";
        for(int i=0;i<4;i++){Anime.Relation relation=new Anime.Relation();relation.type="SEQUEL";Anime next=new Anime();next.id=ids[(i+1)%4];next.title=titles[(i+1)%4];next.coverImage=urls[(i+1)%4];relation.anime=next;FIXTURES.get(i).relations.add(relation);}
        for (int i = 4; i < sampleSize; i++) {
            Anime extra = new Anime();
            int sample = i % titles.length;
            extra.id = 2_000_000 + i;
            extra.title = titles[sample] + " · " + (i / titles.length + 1);
            extra.coverImage = urls[sample];
            extra.format = "TV"; extra.year = Integer.toString(2024 - sample);
            extra.episodes = 12; extra.duration = 24;
            FIXTURES.add(extra);
        }
        if (missingArt) {
            Anime item = FIXTURES.get(sampleSize > 4 ? 4 : 3);
            item.title = "Tokyo Ghoul: Stories Beyond the Last Winter";
            item.coverImage = null;
            item.bannerImage = null;
        }
        Path directory=profile.resolve(".myanimedesk");
        if (animeDetail) seedAnimeDetail(directory, capture);
        if (dashboardArtwork) {
            Anime item = FIXTURES.get(4);
            item.coverImage = seedArtwork(directory, "portrait", 260, 360);
            item.bannerImage = seedArtwork(directory, "landscape", 1050, 420);
        }
        if (discoverMotion) {
            for (int i = 0; i < 2; i++) {
                Anime item = FIXTURES.get(i);
                item.coverImage = seedArtwork(directory, "poster-" + i, 260, 375);
                item.bannerImage = seedArtwork(directory, "wide-" + i, 1050, 435);
            }
        }
        AnimeListManager library=new AnimeListManager(directory.resolve("library.json"));
        if (!Files.exists(directory.resolve("library.json"))) {
            String folder=library.createFolder("Test collection");
            for(int i=0;i<(emptyDashboard ? 0 : FIXTURES.size());i++) {
                Anime a=FIXTURES.get(i); library.add(a);
                if(i<3 || (sampleSize>4 && i==3)){library.setViewCount(a.id,i+1);Map<String,Integer> scores=new LinkedHashMap<>();for(RatingCategory c:RatingCategory.values())scores.put(c.name(),9-i);library.setRating(a.id,scores);}
                else if(i<6) library.updateStatus(a.id,Anime.Status.WATCHING);
                else if(dashboardArtwork && i==6) library.updateStatus(a.id,Anime.Status.DROPPED);
                if(i==0){library.setFolders(a.id,Set.of(folder));a.favorite=true;}
            }
            library.saveToDefault();
            Preferences prefs=new Preferences(directory.resolve("app.properties"));prefs.values.setProperty("onboarding.complete","true");
            prefs.values.setProperty("language","it");prefs.values.setProperty("preferred.genres","Action,Drama");prefs.save();
        }
        Application.launch(PreviewApp.class,args);
    }

    private static String seedArtwork(Path directory, String name, int width, int height) throws Exception {
        String url = "https://fixture.invalid/" + name + ".png";
        writeArtwork(imageFile(directory, url), width, height);
        return url;
    }

    private static Path imageFile(Path directory, String url) throws Exception {
        return directory.resolve("image_cache").resolve(hash(url) + ".img");
    }

    private static String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static void writeArtwork(Path file, int width, int height) throws Exception {
        Files.createDirectories(file.getParent());
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) image.setRGB(x, y, ((80 + x * 120 / width) << 16) | ((45 + y * 80 / height) << 8) | 178);
        }
        ImageIO.write(image, "png", file.toFile());
    }

    private static boolean isAnimeDetail(String capture) {
        return switch (capture) {
            case "anime-detail", "anime-detail-compact", "anime-detail-expanded", "anime-detail-motion",
                 "anime-rating", "anime-rating-compact" -> true;
            default -> false;
        };
    }

    private static boolean isAnimeRating(String capture) {
        return capture.equals("anime-rating") || capture.equals("anime-rating-compact");
    }

    private static void seedAnimeDetail(Path directory, String capture) throws Exception {
        Path existing = Path.of("target", "ui-preview-profile", ".myanimedesk");
        for (Anime anime : FIXTURES) {
            Path source = imageFile(existing, anime.coverImage);
            Path target = imageFile(directory, anime.coverImage);
            if (Files.isRegularFile(target)) continue;
            if (Files.isRegularFile(source)) {
                Files.createDirectories(target.getParent());
                Files.copy(source, target);
            } else writeArtwork(target, 300, 450);
        }

        Anime anime = FIXTURES.getFirst();
        anime.airingStatus = "Concluso";
        anime.studio = "Studio Pierrot";
        anime.averageScore = 79;
        anime.genres = List.of("Action", "Drama", "Horror", "Mystery", "Psychological", "Supernatural");
        anime.description = "Preview fixture. A student's everyday life is upended by a chance encounter. He must rebuild his relationships while learning "
            + "how to live in a city where different communities struggle to coexist. Familiar streets become unfamiliar, and every choice brings him "
            + "closer to people he was once taught to fear.";
        String translation = "Testo di prova. La vita quotidiana di uno studente cambia dopo un incontro imprevisto. Mentre cerca di ricostruire i suoi rapporti, "
            + "deve imparare a vivere in una città dove comunità diverse faticano a convivere. Persino le strade che conosceva gli sembrano nuove, "
            + "e ogni scelta lo avvicina alle persone che aveva imparato a temere.";
        if (capture.equals("anime-detail-compact")) {
            anime.title = "Tokyo Ghoul: Stories Beyond the Last Winter and the Last Lights of the Forgotten City";
            anime.coverImage = null;
            anime.bannerImage = null;
            translation = (translation + "\n\n").repeat(3).stripTrailing();
        }
        Path translations = Files.createDirectories(directory.resolve("translations"));
        Files.writeString(translations.resolve(hash(Texts.synopsis(anime.description)) + "-it.txt"), translation);
    }

    public static class PreviewApp extends App {
        @Override public void start(Stage stage) {
            super.start(stage);
            String previewWidth = System.getProperty("myanimedesk.preview.width", "");
            if (!previewWidth.isBlank()) stage.setWidth(Double.parseDouble(previewWidth));
            String previewHeight = System.getProperty("myanimedesk.preview.height", "");
            if (!previewHeight.isBlank()) stage.setHeight(Double.parseDouble(previewHeight));
            String capture = System.getProperty("myanimedesk.preview.capture", "");
            if (capture.isBlank()) return;
            PauseTransition ready = new PauseTransition(Duration.seconds(2));
            ready.setOnFinished(e -> {
                String navigation = switch (capture) {
                    case "dashboard", "dashboard-next", "dashboard-hover", "dashboard-empty", "dashboard-missing-art", "dashboard-artwork" -> "#nav-home";
                    case "settings" -> "#nav-settings";
                    case "ranking" -> "#nav-ranking";
                    case "library", "library-wide", "hover", "anime-detail", "anime-detail-compact", "anime-detail-expanded",
                         "anime-detail-motion", "anime-rating", "anime-rating-compact" -> "#nav-library";
                    default -> "#nav-discover";
                };
                if (!(stage.getScene().lookup(navigation) instanceof Button navButton)) {
                    System.err.println("Navigation control missing: " + navigation);
                    System.exit(1);
                    return;
                }
                navButton.fire();
                BorderPane shell = (BorderPane) stage.getScene().lookup(".shell");
                Node page = shell.getCenter();
                if ((!navigation.equals("#nav-library") && page.getOpacity() >= 1) || shell.getBottom() != null) {
                    System.err.println("The page entrance or footer state is wrong");
                    System.exit(1);
                    return;
                }
                boolean dashboardHover = capture.equals("dashboard-hover");
                PauseTransition settle = new PauseTransition(Duration.millis(capture.equals("hover") ? 650 : 1000));
                settle.setOnFinished(event -> {
                    try {
                        if (page.getOpacity() != 1 || page.getTranslateY() != 0)
                            throw new IllegalStateException("The page entrance did not finish");
                        if (capture.equals("library-wide")) verifyGridBalance(stage);
                        if (capture.equals("discover-strip")) verifyDiscoverStrip(stage);
                        if (capture.equals("dashboard-artwork")) verifyDashboardArtwork(stage);
                        if (capture.equals("dashboard") || capture.equals("dashboard-artwork")) verifyDashboardNews(stage);
                        if (capture.equals("discover-featured") || capture.equals("discover-fallback") || capture.equals("discover-motion"))
                            verifyFeatured(stage, capture);
                        if (capture.equals("discover-filters")) verifyDiscoverFilters(stage);
                        if (capture.equals("discover-suggestions")) verifyStableSuggestions(stage);
                        if (isAnimeDetail(capture)) verifyAnimeDetail(stage, capture);
                        if (isAnimeRating(capture)) ((Button) stage.getScene().lookup("#detail-rating")).fire();
                        if (dashboardHover) {
                            Label title = (Label) stage.getScene().lookup(".dashboard-spotlight-title");
                            Node hoverTarget = stage.getScene().lookup("#anime-2000005");
                            if (hoverTarget == null || !title.getText().equals(hoverTarget.getAccessibleText()))
                                throw new IllegalStateException("Hover did not change the dashboard spotlight");
                        }
                    } catch (Exception error) {
                        error.printStackTrace();
                        System.exit(1);
                    }
                    PauseTransition paint = new PauseTransition(Duration.millis(450));
                    paint.setOnFinished(painted -> {
                        try {
                            if (capture.equals("anime-detail-expanded")) {
                                TitledPane seasons = (TitledPane) stage.getScene().lookup("#detail-seasons");
                                if (seasons.getContent().lookupAll(".season-entry").size() != FIXTURES.size())
                                    throw new IllegalStateException("Opening Seasons must show the fixture season entries");
                                ((ScrollPane) stage.getScene().lookup("#detail-scroll")).setVvalue(1);
                            }
                            if (isAnimeRating(capture)) verifyAnimeRating(stage);
                            if (capture.equals("anime-detail-motion")) verifyDetailClosing(stage, capture);
                            else savePreview(stage, capture);
                        } catch (Exception error) {
                            error.printStackTrace();
                            System.exit(1);
                        }
                    });
                    paint.play();
                });
                if (capture.equals("hover") || capture.equals("carousel") || capture.equals("dashboard-next") || dashboardHover
                    || capture.equals("discover-suggestions") || isAnimeDetail(capture)) {
                    PauseTransition layout = new PauseTransition(Duration.millis(350));
                    layout.setOnFinished(event -> {
                        if (capture.equals("hover") || dashboardHover) {
                            String cardSelector = dashboardHover ? "#anime-2000005" : "#anime-20605";
                            Node card = stage.getScene().lookup(cardSelector);
                            if (card != null) {
                                Point2D position = card.localToScreen(45, 120);
                                if (position != null) new Robot().mouseMove(position.getX(), position.getY());
                            }
                        } else if (isAnimeDetail(capture)) {
                            openAnimeDetail(stage, capture);
                            if (capture.equals("anime-detail-motion")) {
                                try {
                                    verifyDetailOpening(stage);
                                } catch (Exception error) {
                                    error.printStackTrace();
                                    System.exit(1);
                                }
                            }
                        } else if (capture.equals("discover-suggestions")) {
                            TextField search = (TextField) stage.getScene().lookup("#online-search");
                            search.requestFocus();
                            search.setText("Tokyo");
                        } else if (capture.equals("dashboard-next")) {
                            Label spotlight = (Label) stage.getScene().lookup(".dashboard-spotlight-title");
                            Button next = (Button) stage.getScene().lookup("#dashboard-spotlight-next");
                            String previous = spotlight.getText();
                            next.fire();
                            if (previous.equals(spotlight.getText())) {
                                System.err.println("The next arrow did not change the spotlight");
                                System.exit(1);
                            }
                        } else {
                            if (stage.getScene().lookup("#discover-featured-next") instanceof Button next) next.fire();
                        }
                        settle.play();
                    });
                    layout.play();
                } else settle.play();
            });
            ready.play();
        }

        private void savePreview(Stage stage, String capture) {
            try {
                writePreview(stage, capture);
                stage.close();
                Platform.exit();
            } catch (Exception error) {
                error.printStackTrace();
                System.exit(1);
            }
        }

        private void writePreview(Stage stage, String capture) throws Exception {
            boolean capturePopup = capture.equals("hover") || capture.equals("discover-suggestions");
            WritableImage shot = capturePopup
                ? new Robot().getScreenCapture(null, new Rectangle2D(stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()))
                : stage.getScene().getRoot().snapshot(new SnapshotParameters(), null);
            BufferedImage output = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < output.getHeight(); y++) for (int x = 0; x < output.getWidth(); x++)
                output.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
            ImageIO.write(output, "png", Path.of("target", "preview-" + capture + ".png").toFile());
        }

        private void openAnimeDetail(Stage stage, String capture) {
            Node card = stage.getScene().lookup("#anime-" + FIXTURES.getFirst().id);
            if (card == null) {
                System.err.println("The anime detail fixture is missing from My List");
                System.exit(1);
                return;
            }
            if (capture.equals("anime-detail-compact")) {
                card.requestFocus();
                Event.fireEvent(card, new KeyEvent(KeyEvent.KEY_PRESSED, "\r", "", KeyCode.ENTER, false, false, false, false));
            } else {
                Event.fireEvent(card, new MouseEvent(MouseEvent.MOUSE_CLICKED, 30, 30, 30, 30, MouseButton.PRIMARY, 1,
                    false, false, false, false, false, false, false, false, false, true, new PickResult(card, 30, 30)));
            }
        }

        private void verifyAnimeDetail(Stage stage, String capture) {
            Node overlay = stage.getScene().lookup(".anime-detail-modal");
            Node surface = overlay == null ? null : overlay.lookup(".modal-surface");
            if (surface == null || !(surface.lookup("#anime-detail-title") instanceof Label title)
                    || !FIXTURES.getFirst().title.equals(title.getText())) {
                throw new IllegalStateException("Activating a library card must open its anime details");
            }
            Bounds bounds = surface.localToScene(surface.getLayoutBounds());
            if (bounds.getMinX() < 0 || bounds.getMinY() < 0
                    || bounds.getMaxX() > stage.getScene().getWidth() || bounds.getMaxY() > stage.getScene().getHeight()) {
                throw new IllegalStateException("The anime detail surface must fit within the window");
            }
            for (String id : List.of("detail-status", "view-count", "detail-favourite", "detail-trailer", "detail-source",
                    "detail-folders", "detail-rating", "detail-remove", "detail-plot", "detail-cast", "detail-seasons", "detail-scroll")) {
                if (surface.lookup("#" + id) == null) throw new IllegalStateException("Missing anime detail control: " + id);
            }
            if (!(surface.lookup("#detail-status") instanceof ComboBox<?> status) || status.getValue() != Anime.Status.WATCHED
                    || !(surface.lookup("#view-count") instanceof Spinner<?> viewings) || !Integer.valueOf(1).equals(viewings.getValue())
                    || surface.lookup("#detail-trailer").isDisabled()) {
                throw new IllegalStateException("The detail controls must reflect the saved watch status, viewing count and available trailer");
            }
            if (capture.equals("anime-detail-expanded")) {
                TitledPane cast = (TitledPane) surface.lookup("#detail-cast");
                TitledPane seasons = (TitledPane) surface.lookup("#detail-seasons");
                cast.setExpanded(true);
                if (cast.getContent().lookupAll(".list-row").isEmpty())
                    throw new IllegalStateException("Opening Cast must show the fixture characters");
                seasons.setExpanded(true);
            }
        }

        private void verifyDetailOpening(Stage stage) {
            Node overlay = stage.getScene().lookup(".anime-detail-modal");
            Node surface = overlay == null ? null : overlay.lookup(".modal-surface");
            Node card = stage.getScene().lookup("#anime-" + FIXTURES.getFirst().id);
            Node cover = card == null ? null : card.lookup(".image-frame");
            if (surface == null || cover == null || surface.getScaleX() >= .92 || surface.getScaleY() >= .92) {
                throw new IllegalStateException("The anime detail must grow from the library card rather than the centre of the screen");
            }
            Bounds artwork = cover.localToScene(cover.getLayoutBounds());
            Bounds start = surface.localToScene(surface.getLayoutBounds());
            if (Math.hypot(artwork.getCenterX() - start.getCenterX(), artwork.getCenterY() - start.getCenterY()) > 3)
                throw new IllegalStateException("The detail transition must start at the cover's actual position");
        }

        private void verifyDetailClosing(Stage stage, String capture) throws Exception {
            Node overlay = stage.getScene().lookup(".anime-detail-modal");
            Node surface = overlay.lookup(".modal-surface");
            if (Math.abs(surface.getScaleX() - 1) > .01 || Math.abs(surface.getScaleY() - 1) > .01
                || Math.hypot(surface.getTranslateX(), surface.getTranslateY()) > 1)
                throw new IllegalStateException("The opening transition must settle at the full anime detail surface");
            writePreview(stage, capture);
            boolean[] shrinking = {false};
            ChangeListener<Number> scale = (obs, before, after) -> {
                if (after.doubleValue() < .92) shrinking[0] = true;
            };
            surface.scaleXProperty().addListener(scale);
            clickDetailAt(surface, surface.localToScene(10, 10));
            Point2D outside = overlay.localToScene(4, 4);
            if (surface.localToScene(surface.getLayoutBounds()).contains(outside))
                throw new IllegalStateException("The backdrop click must be outside the detail surface");
            PauseTransition close = new PauseTransition(Duration.millis(550));
            close.setOnFinished(event -> {
                surface.scaleXProperty().removeListener(scale);
                if (!shrinking[0] || overlay.getScene() != null || stage.getScene().lookup(".shell").isDisabled()) {
                    System.err.println("Closing must shrink the detail, remove the overlay and restore the library");
                    System.exit(1);
                }
                stage.close();
                Platform.exit();
            });
            PauseTransition inside = new PauseTransition(Duration.millis(350));
            inside.setOnFinished(event -> {
                if (overlay.getScene() == null || shrinking[0]) {
                    System.err.println("A click inside the detail must leave the modal open");
                    System.exit(1);
                    return;
                }
                clickDetailAt(overlay, outside);
                if (overlay.getScene() == null) {
                    System.err.println("Closing removed the detail before it could animate");
                    System.exit(1);
                    return;
                }
                close.play();
            });
            inside.play();
        }

        private void clickDetailAt(Node target, Point2D point) {
            Point2D onScreen = target.localToScreen(target.sceneToLocal(point));
            Event.fireEvent(target, new MouseEvent(MouseEvent.MOUSE_CLICKED, point.getX(), point.getY(),
                onScreen.getX(), onScreen.getY(), MouseButton.PRIMARY, 1,
                false, false, false, false, false, false, false, false, false, true,
                new PickResult(target, point.getX(), point.getY())));
        }

        private void verifyAnimeRating(Stage stage) {
            Node rating = stage.getScene().lookup("#rating-dialog");
            if (rating == null || !(rating.lookup("#rating-overall") instanceof Label overall)
                || !(rating.lookup("#save-rating") instanceof Button save))
                throw new IllegalStateException("The rating dialog needs a visible overall score and save action");
            Bounds saveArea = save.localToScene(save.getLayoutBounds());
            if (!save.isVisible() || saveArea.getMinY() < 0 || saveArea.getMaxY() > stage.getScene().getHeight())
                throw new IllegalStateException("Save must be within reach at the current window size");

            for (RatingCategory category : RatingCategory.values()) {
                if (!(rating.lookup("#rating-" + category.name()) instanceof Slider slider)
                    || slider.getMin() != 0 || slider.getMax() != 10 || !slider.isSnapToTicks()
                    || !(rating.lookup("#rating-bonus-" + category.name()) instanceof ToggleButton))
                    throw new IllegalStateException("Missing rating controls for " + category);
            }

            Slider design = (Slider) rating.lookup("#rating-CHARACTER_DESIGN");
            Slider animation = (Slider) rating.lookup("#rating-ANIMATION");
            ToggleButton designBonus = (ToggleButton) rating.lookup("#rating-bonus-CHARACTER_DESIGN");
            ToggleButton animationBonus = (ToggleButton) rating.lookup("#rating-bonus-ANIMATION");
            assertRatingAverage(overall, 9.0);
            design.setValue(8);
            assertRatingAverage(overall, 8.9);
            designBonus.fire();
            if (!designBonus.isSelected() || !design.isDisabled())
                throw new IllegalStateException("The active special score must replace the category's normal score");
            assertRatingAverage(overall, 9.3);
            animationBonus.fire();
            if (designBonus.isSelected() || design.isDisabled() || !animationBonus.isSelected() || !animation.isDisabled())
                throw new IllegalStateException("Selecting a special score must restore the previously selected category");
            assertRatingAverage(overall, 9.2);
            animationBonus.fire();
            if (animationBonus.isSelected() || animation.isDisabled())
                throw new IllegalStateException("The special score must be removable from a category");
            assertRatingAverage(overall, 8.9);
            animationBonus.fire();
        }

        private void assertRatingAverage(Label overall, double expected) {
            double actual = Double.parseDouble(overall.getText().replace(',', '.'));
            if (Math.abs(actual - expected) > .01)
                throw new IllegalStateException("Expected overall score " + expected + ", got " + actual);
        }

        private void verifyDashboardArtwork(Stage stage) {
            ImageView cover = (ImageView) stage.getScene().lookup("#dashboard-spotlight-cover");
            ImageView banner = (ImageView) stage.getScene().lookup("#dashboard-spotlight-backdrop");
            if (cover.getImage() == null || !cover.getParent().isVisible() || banner.getImage() == null
                || cover.getImage() == banner.getImage() || banner.getImage().getWidth() <= banner.getImage().getHeight()) {
                throw new IllegalStateException("The dashboard must show the portrait with a separate wide banner");
            }
            Node root = stage.getScene().getRoot();
            if (root.lookupAll(".dashboard-stats-reading").size() != 4)
                throw new IllegalStateException("All four viewing statuses must be shown");
            if (root.lookupAll(".dashboard-news-article").size() != 3)
                throw new IllegalStateException("The dashboard must display the fixture headlines");
        }

        private void verifyDashboardNews(Stage stage) {
            Node news = stage.getScene().lookup("#dashboard-news");
            Node stats = stage.getScene().lookup(".dashboard-stats");
            Bounds newsBounds = news.localToScene(news.getLayoutBounds());
            Bounds statsBounds = stats.localToScene(stats.getLayoutBounds());
            long articleRows = news.lookupAll(".dashboard-news-article").stream().map(Node::getLayoutY).distinct().count();
            boolean wide = stage.getScene().getWidth() >= 1660;
            if (wide && (newsBounds.getMinX() < statsBounds.getMaxX() || articleRows != 3))
                throw new IllegalStateException("Wide dashboard news must form a vertical column beside the stats");
            if (!wide && (newsBounds.getMinY() < statsBounds.getMaxY() || articleRows != 1))
                throw new IllegalStateException("Narrow dashboard news must stay below the dashboard");
        }

        private void verifyStableSuggestions(Stage stage) {
            Window popup = Window.getWindows().stream().filter(window -> window.getScene() != null
                && window.getScene().lookup("#suggestion-20605") != null).findFirst()
                .orElseThrow(() -> new IllegalStateException("Discover must show suggestions for a recognized title"));
            boolean[] hidden = {false};
            ChangeListener<Boolean> visibility = (obs, before, showing) -> { if (!showing) hidden[0] = true; };
            popup.showingProperty().addListener(visibility);
            TextField search = (TextField) stage.getScene().lookup("#online-search");
            search.setText("Toky");
            search.setText("Tokyox");
            search.setText("Tokyo");
            popup.showingProperty().removeListener(visibility);
            if (hidden[0] || !popup.isShowing()) throw new IllegalStateException("Typing must not hide and reopen the suggestions popup");
        }

        private void verifyFeatured(Stage stage, String capture) {
            Region banner = (Region) stage.getScene().lookup(".banner");
            Label title = (Label) stage.getScene().lookup(".banner-title");
            if (banner == null || title == null || title.getText().isBlank()
                || Math.abs(banner.getWidth() - banner.getHeight() * 16 / 9) > 2) {
                throw new IllegalStateException("The featured anime must render in a 16:9 frame");
            }
            Region left = (Region) stage.getScene().lookup("#discover-featured-left-panel");
            Region right = (Region) stage.getScene().lookup("#discover-featured-right-panel");
            Region deck = (Region) banner.getParent();
            if (!left.isVisible() || !right.isVisible() || left.getWidth() < 90 || right.getWidth() < 90
                || Math.abs(left.getLayoutX()) > 1 || Math.abs(right.getLayoutX() + right.getWidth() - deck.getWidth()) > 1
                || Math.abs(left.getWidth() - right.getWidth()) > 1) {
                throw new IllegalStateException("Neighbouring previews must use both sides of the featured deck");
            }
            Label category = (Label) stage.getScene().lookup(".banner-kicker");
            if (capture.equals("discover-fallback") && !category.getText().equals("Dalla tua lista"))
                throw new IllegalStateException("An unavailable feed must show the library fallback");
            Button next = (Button) stage.getScene().lookup("#discover-featured-next");
            Button previous = (Button) stage.getScene().lookup("#discover-featured-previous");
            if (next == null || previous == null || !next.isVisible() || !previous.isVisible())
                throw new IllegalStateException("Both featured arrows must be visible");
            boolean verifyMotion = capture.equals("discover-motion");
            ImageView backdrop = (ImageView) stage.getScene().lookup("#discover-featured-backdrop");
            ImageView cover = (ImageView) stage.getScene().lookup("#discover-featured-cover");
            if (verifyMotion && (cover == null || cover.getImage() == null || !cover.getParent().isVisible()
                || backdrop.getImage() == null || backdrop.getImage().getWidth() < backdrop.getImage().getHeight() * 1.4)) {
                throw new IllegalStateException("A wide Discover banner must appear with its own cover");
            }
            String original = title.getText();
            next.fire();
            if (title.getText().equals(original)) throw new IllegalStateException("The next feature did not advance");
            if (verifyMotion && (backdrop.getTranslateX() <= 0 || stage.getScene().lookup("#discover-featured-outgoing") == null))
                throw new IllegalStateException("The next feature must slide forward while keeping the outgoing artwork");
            previous.fire();
            if (!title.getText().equals(original)) throw new IllegalStateException("The previous feature did not restore the earlier title");
            if (verifyMotion && backdrop.getTranslateX() >= 0)
                throw new IllegalStateException("The previous feature must slide in the opposite direction");
        }

        private void verifyDiscoverFilters(Stage stage) {
            ToggleButton toggle = (ToggleButton) stage.getScene().lookup("#discover-filters-toggle");
            Node filters = stage.getScene().lookup("#discover-filter-panel");
            if (filters.isVisible()) throw new IllegalStateException("The filters should start collapsed");
            toggle.fire();
            if (!filters.isVisible()) throw new IllegalStateException("The filters did not expand");
            ComboBox<?> genre = (ComboBox<?>) stage.getScene().lookup("#discover-genre");
            genre.getSelectionModel().select(1);
            if (!toggle.getText().contains("1")) throw new IllegalStateException("The active filter count was not updated");
            Button reset = (Button) stage.getScene().lookup("#discover-filters-reset");
            reset.fire();
            if (!"".equals(genre.getValue()) || !"Filtri".equals(toggle.getText()))
                throw new IllegalStateException("Reset did not restore the default filters");
        }

        private void verifyGridBalance(Stage stage) {
            TilePane grid = (TilePane) stage.getScene().lookup(".poster-grid");
            if (grid == null || grid.getChildren().size() < 30)
                throw new IllegalStateException("The wide library requires at least 30 sample covers");
            Node first = grid.getChildren().getFirst();
            double firstRowY = first.getLayoutY();
            Node last = grid.getChildren().stream().filter(card -> Math.abs(card.getLayoutY() - firstRowY) < 1)
                .reduce((a, b) -> b).orElseThrow();
            double left = first.getBoundsInParent().getMinX();
            double right = grid.getWidth() - last.getBoundsInParent().getMaxX();
            if (Math.abs(left - right) > 3)
                throw new IllegalStateException("Unbalanced wide grid: " + left + " vs " + right);
            System.out.printf("Wide library gutters: %.1f px left, %.1f px right%n", left, right);
        }

        private void verifyDiscoverStrip(Stage stage) {
            ScrollPane strip = (ScrollPane) stage.getScene().lookup("#discover-strip");
            if (strip == null) throw new IllegalStateException("The first Discover row did not load");
            Node cards = strip.getContent();
            int initialVisible = cards.lookupAll(".anime-card").size();
            if (initialVisible < 2 || initialVisible >= 15 || cards.lookup("#anime-1000000") == null)
                throw new IllegalStateException("Unexpected initial card count: " + initialVisible);
            strip.setHvalue(1);
            if (cards.lookup("#anime-1000029") == null || cards.lookup("#anime-1000000") != null)
                throw new IllegalStateException("The last card must be reachable and the first card released");
            strip.setHvalue(0);
            if (cards.lookup("#anime-1000000") == null || cards.lookup("#anime-1000029") != null)
                throw new IllegalStateException("Scrolling back did not rebuild the first cards");
            System.out.println("Discover row: " + initialVisible + " live cards, all 30 reachable");
        }

        @Override AniListClient createApi(Path directory) {
            return new AniListClient(directory) {
                @Override public List<Anime> search(String query,int amount) {return FIXTURES.stream().filter(a->a.title.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();}
                @Override public List<Anime> search(DiscoverQuery query,int page,int amount) {
                    if(page>1)return List.of();
                    return FIXTURES.stream().filter(a->query.text().isBlank()||a.title.toLowerCase(Locale.ROOT).contains(query.text().toLowerCase(Locale.ROOT)))
                        .filter(a->query.genre().isBlank()||a.genres.contains(query.genre())).toList();
                }
                @Override public List<Anime> browse(String mode,String filter,int page,int amount) {
                    if (page != 1) return List.of();
                    String capture = System.getProperty("myanimedesk.preview.capture");
                    if ("discover-fallback".equals(capture) && "TRENDING".equals(mode)) return List.of();
                    if (!"discover-strip".equals(capture) || amount != 30) return FIXTURES;
                    List<Anime> row = new ArrayList<>();
                    for (int i = 0; i < amount; i++) {
                        Anime original = FIXTURES.get(i % FIXTURES.size());
                        Anime entry = new Anime();
                        entry.id = 1_000_000 + i;
                        entry.title = original.title + " " + (i + 1);
                        entry.coverImage = original.coverImage;
                        entry.episodes = original.episodes;
                        entry.format = original.format;
                        row.add(entry);
                    }
                    return row;
                }
                @Override public Anime getAnimeById(int id){return FIXTURES.stream().filter(a->a.id==id).findFirst().orElse(null);}
                @Override public List<Anime> seasons(Anime anime) {return FIXTURES;}
            };
        }

        @Override AnimeNews createAnimeNews() {
            Instant date = Instant.now();
            return new AnimeNews(english -> List.of(
                new AnimeNews.Article("Anteprima di una nuova serie anime", URI.create("https://example.com/anime"),
                    date.minusSeconds(3600), "Notizie di prova", AnimeNews.Topic.ANIME),
                new AnimeNews.Article("In arrivo una nuova edizione del manga", URI.create("https://example.com/manga"),
                    date.minusSeconds(7200), "Notizie di prova", AnimeNews.Topic.MANGA),
                new AnimeNews.Article("Il prossimo evento dedicato agli anime", URI.create("https://example.com/evento"),
                    date.minusSeconds(10800), "Notizie di prova", AnimeNews.Topic.ANIME)));
        }
    }
}
