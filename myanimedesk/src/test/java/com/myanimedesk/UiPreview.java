package com.myanimedesk;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Button;
import javafx.scene.image.WritableImage;
import javafx.stage.Stage;
import javafx.util.Duration;
import java.awt.image.BufferedImage;
import javax.imageio.ImageIO;
import java.nio.file.*;
import java.util.*;

/** Isolated interactive preview with explicitly labelled fixture data; never uses the user's library. */
public class UiPreview {
    static final List<Anime> FIXTURES = new ArrayList<>();
    public static void main(String[] args) throws Exception {
        Path profile = Path.of("target", "ui-preview-profile").toAbsolutePath().normalize();
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
        for(int i=0;i<4;i++){Anime.Relation relation=new Anime.Relation();relation.type="SEQUEL";Anime next=new Anime();next.id=ids[(i+1)%4];next.title=titles[(i+1)%4];next.coverImage=urls[(i+1)%4];relation.anime=next;FIXTURES.get(i).relations.add(relation);}
        Path directory=profile.resolve(".myanimedesk");
        AnimeListManager library=new AnimeListManager(directory.resolve("library.json"));
        if (!Files.exists(directory.resolve("library.json"))) {
            String folder=library.createFolder("Test collection");
            for(int i=0;i<4;i++) {
                Anime a=FIXTURES.get(i); library.add(a);
                if(i<3){library.setViewCount(a.id,i+1);Map<String,Integer> scores=new LinkedHashMap<>();for(RatingCategory c:RatingCategory.values())scores.put(c.name(),9-i);library.setRating(a.id,scores);}
                else library.updateStatus(a.id,Anime.Status.WATCHING);
                if(i==0){library.setFolders(a.id,Set.of(folder));a.favorite=true;}
            }
            library.saveToDefault();
            Preferences prefs=new Preferences(directory.resolve("app.properties"));prefs.values.setProperty("onboarding.complete","true");
            prefs.values.setProperty("language","it");prefs.values.setProperty("preferred.genres","Action,Drama");prefs.save();
        }
        Application.launch(PreviewApp.class,args);
    }
    public static class PreviewApp extends App {
        @Override public void start(Stage stage) {
            super.start(stage);
            String capture = System.getProperty("myanimedesk.preview.capture", "");
            if (capture.isBlank()) return;
            PauseTransition ready = new PauseTransition(Duration.seconds(2));
            ready.setOnFinished(e -> {
                String label = switch (capture) { case "settings" -> "Impostazioni"; case "ranking" -> "Classifica"; default -> "Scopri / Cerca"; };
                stage.getScene().getRoot().lookupAll(".button").stream()
                    .filter(node -> node instanceof Button button && button.getText().equals(label))
                    .findFirst().ifPresent(node -> ((Button) node).fire());
                PauseTransition settle = new PauseTransition(Duration.seconds(1));
                settle.setOnFinished(event -> {
                    try {
                        WritableImage shot = stage.getScene().getRoot().snapshot(new SnapshotParameters(), null);
                        BufferedImage output = new BufferedImage((int) shot.getWidth(), (int) shot.getHeight(), BufferedImage.TYPE_INT_ARGB);
                        for (int y = 0; y < output.getHeight(); y++) for (int x = 0; x < output.getWidth(); x++)
                            output.setRGB(x, y, shot.getPixelReader().getArgb(x, y));
                        ImageIO.write(output, "png", Path.of("target", "preview-" + capture + ".png").toFile());
                    } catch (Exception error) { error.printStackTrace(); }
                    stage.close(); Platform.exit();
                });
                settle.play();
            });
            ready.play();
        }
        @Override AniListClient createApi(Path directory) {
            return new AniListClient(directory) {
                @Override public List<Anime> search(String query,int amount) {return FIXTURES.stream().filter(a->a.title.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))).toList();}
                @Override public List<Anime> search(DiscoverQuery query,int page,int amount) {
                    if(page>1)return List.of();
                    return FIXTURES.stream().filter(a->query.text().isBlank()||a.title.toLowerCase(Locale.ROOT).contains(query.text().toLowerCase(Locale.ROOT)))
                        .filter(a->query.genre().isBlank()||a.genres.contains(query.genre())).toList();
                }
                @Override public List<Anime> browse(String mode,String filter,int page,int amount){return page==1?FIXTURES:List.of();}
                @Override public Anime getAnimeById(int id){return FIXTURES.stream().filter(a->a.id==id).findFirst().orElse(null);}
                @Override public List<Anime> seasons(Anime anime) {return FIXTURES;}
            };
        }
    }
}
