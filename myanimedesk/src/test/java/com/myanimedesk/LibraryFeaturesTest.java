package com.myanimedesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.nio.file.*;
import java.util.*;
import static org.junit.Assert.*;

public class LibraryFeaturesTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private AnimeListManager manager;
    private Path file;
    private final ObjectMapper json = new ObjectMapper();
    @Before public void prepare() throws Exception {
        file = temporary.getRoot().toPath().resolve("library.json"); manager = new AnimeListManager(file);
    }
    private Anime anime(int id, String title) {
        Anime a = new Anime(); a.id=id; a.title=title; a.episodes=12; a.duration=24; a.genres=List.of("Action"); return a;
    }
    private Map<String,Integer> scores(int score) {
        Map<String,Integer> values = new LinkedHashMap<>();
        for (RatingCategory c : RatingCategory.values()) values.put(c.name(), score);
        return values;
    }
    private void rejects(Runnable work) {
        try { work.run(); fail("Should reject invalid input"); } catch (IllegalArgumentException | NullPointerException expected) { }
    }
    @Test public void legacyArrayMigratesAndKeepsOriginalBackup() throws Exception {
        Anime a=anime(1,"Legacy"); a.status=Anime.Status.WATCHED; a.favorite=true;
        byte[] legacy=json.writeValueAsBytes(List.of(a)); Files.write(file,legacy);
        manager.loadFromDefault(); assertEquals(1,manager.findById(1).viewCount()); assertEquals(4.8,manager.watchedHours(),0.001);
        manager.saveToDefault(); manager.setViewCount(1,3); manager.saveToDefault();
        assertArrayEquals(legacy,Files.readAllBytes(file.resolveSibling("library.before-upgrade.json")));
        AnimeListManager reloaded=new AnimeListManager(file); reloaded.loadFromDefault();
        assertEquals(3,reloaded.findById(1).viewCount()); assertTrue(reloaded.findById(1).favorite);
    }
    @Test public void manyFoldersNeverDuplicateHoursAndRenameKeepsMembership() throws Exception {
        Anime a=anime(1,"Anime"); manager.add(a); manager.setViewCount(1,3);
        String one=manager.createFolder("Together"), two=manager.createFolder("Best");
        manager.setFolders(1,Set.of(one,two)); manager.renameFolder(one,"Weekend");
        assertEquals(1,manager.all().size()); assertEquals(14.4,manager.watchedHours(),0.001);
        manager.removeFolder(two); assertEquals(Set.of(one),a.folderIds); assertEquals(14.4,manager.watchedHours(),0.001);
        manager.saveToDefault(); AnimeListManager loaded=new AnimeListManager(file); loaded.loadFromDefault();
        assertEquals("Weekend",loaded.folders().get(one)); assertEquals(Set.of(one),loaded.findById(1).folderIds);
    }
    @Test public void rewatchPreservesFinishedViewingsDuringWatching() {
        manager.add(anime(1,"Anime")); manager.updateStatus(1,Anime.Status.WATCHED);
        manager.updateStatus(1,Anime.Status.WATCHING); assertEquals(1,manager.findById(1).viewCount());
        assertEquals(4.8,manager.watchedHours(),0.001);
        manager.setViewCount(1,2); assertEquals(Anime.Status.WATCHED,manager.findById(1).status);
        assertEquals(9.6,manager.watchedHours(),0.001); rejects(()->manager.setViewCount(1,-1));
        rejects(()->manager.setViewCount(1,10001));
    }
    @Test public void ratingRulesAllowZeroAndOneTwelveButRejectOtherValues() {
        RatingCategory.validate(scores(0)); Map<String,Integer> values=scores(10);
        values.put("STORY",12); RatingCategory.validate(values);
        values.put("EMOTION",12); rejects(()->RatingCategory.validate(values));
        for(int invalid:new int[]{-1,11,13,99}) { Map<String,Integer> bad=scores(8); bad.put("STORY",invalid); rejects(()->RatingCategory.validate(bad)); }
        Map<String,Integer> missing=scores(8); missing.remove("STORY"); rejects(()->RatingCategory.validate(missing));
        Map<String,Integer> nil=scores(8); nil.put("STORY",null); rejects(()->RatingCategory.validate(nil));
    }
    @Test public void overallAndRankingUseEveryCategoryAndStableTies() {
        for(int id=1;id<=3;id++) { manager.add(anime(id,id==1?"Zulu":id==2?"Alpha":"Beta")); manager.setViewCount(id,1); }
        Map<String,Integer> high=scores(10); high.put("STORY",12); manager.setRating(3,high);
        manager.setRating(1,scores(8)); manager.setRating(2,scores(8));
        assertEquals(10.2,manager.findById(3).overall(),0.001);
        assertEquals(List.of(3,2,1),manager.ranking().stream().map(a->a.id).toList());
        manager.resetRanking(); assertTrue(manager.ranking().isEmpty()); assertEquals(14.4,manager.watchedHours(),0.001);
    }
    @Test public void unwatchedCannotBeRatedAndRatedCannotLoseAllViewings() {
        manager.add(anime(1,"Test")); rejects(()->manager.setRating(1,scores(8)));
        manager.setViewCount(1,1); manager.setRating(1,scores(8)); rejects(()->manager.setViewCount(1,0));
        manager.setRating(1,Map.of()); manager.setViewCount(1,0); assertEquals(Anime.Status.TO_WATCH,manager.findById(1).status);
    }
    @Test public void advancedFiltersComposeWithStatusFavoritesFoldersAndGenre() {
        Anime a=anime(1,"Alpha"), b=anime(2,"Beta"), c=anime(3,"Gamma"); a.year="2020"; b.year="2024"; c.year="N/D";
        manager.add(a);manager.add(b);manager.add(c); manager.setViewCount(1,3);manager.setViewCount(2,2); c.favorite=true;a.favorite=true;
        String folder=manager.createFolder("Test");manager.setFolders(1,Set.of(folder));manager.setFolders(3,Set.of(folder));
        assertEquals(List.of(a),LibraryQuery.select(manager.all(),LibraryQuery.Filter.REWATCHED,LibraryQuery.Order.VIEWS_DESC,"Action",folder,"alp"));
        assertEquals(List.of(a,c),LibraryQuery.select(manager.all(),LibraryQuery.Filter.FAVORITES,LibraryQuery.Order.AZ,null,null,""));
        assertEquals(List.of(b,a),LibraryQuery.select(manager.all(),LibraryQuery.Filter.WATCHED,LibraryQuery.Order.RECENT,null,null,""));
        assertEquals(List.of(a,b,c),LibraryQuery.select(manager.all(),LibraryQuery.Filter.ALL,LibraryQuery.Order.OLDEST,null,null,""));
        assertEquals(List.of(c,b,a),LibraryQuery.select(manager.all(),LibraryQuery.Filter.ALL,LibraryQuery.Order.ZA,null,null,""));
    }
    @Test public void invalidImportsNeverReplaceMemoryOrDisk() throws Exception {
        manager.add(anime(1,"Original"));manager.saveToDefault();byte[] before=Files.readAllBytes(file);
        Path invalid=temporary.getRoot().toPath().resolve("invalid.json");
        for(String bad:List.of("{}","null","[{\"id\":1},{\"id\":1}]","[{\"id\":2,\"episodes\":1.5}]","[{\"id\":2,\"completedViews\":\"2\"}]","[{\"id\":2,\"folderIds\":[\"missing\"]}]")) {
            Files.writeString(invalid,bad);
            try { manager.importLibrary(invalid);fail("Invalid import accepted: "+bad); } catch(java.io.IOException expected) { }
            assertEquals("Original",manager.findById(1).title); assertArrayEquals(before,Files.readAllBytes(file));
        }
    }
    @Test public void failedTransactionRollsBackChanges() throws Exception {
        manager.add(anime(1,"Original"));manager.saveToDefault();
        try {manager.transaction(()->{manager.setViewCount(1,2);throw new IllegalArgumentException("test");});fail();}catch(IllegalArgumentException expected){}
        assertEquals(0,manager.findById(1).viewCount());
    }
    @Test public void rankingImportMergesWithoutDuplicatingExistingHoursOrFolders() throws Exception {
        manager.add(anime(1,"Original"));manager.setViewCount(1,3);manager.setRating(1,scores(7));
        String folder=manager.createFolder("Keep");manager.setFolders(1,Set.of(folder));manager.findById(1).favorite=true;
        Path ranking=temporary.getRoot().toPath().resolve("ranking.json");
        AnimeListManager other=new AnimeListManager(temporary.getRoot().toPath().resolve("other.json"));
        other.add(anime(1,"Incoming"));other.setViewCount(1,9);other.setRating(1,scores(9));other.exportRanking(ranking);
        manager.importRanking(ranking);manager.importRanking(ranking);
        assertEquals(1,manager.all().size());assertEquals(3,manager.findById(1).viewCount());assertEquals(9,manager.findById(1).overall(),0.001);
        assertEquals(Set.of(folder),manager.findById(1).folderIds);assertTrue(manager.findById(1).favorite);assertEquals(14.4,manager.watchedHours(),0.001);
    }
    @Test public void foldersValidateNamesAndUnknownReferences() {
        manager.add(anime(1,"Test"));manager.createFolder("Weekend");
        rejects(()->manager.createFolder(" weekend "));rejects(()->manager.createFolder(" "));rejects(()->manager.createFolder("bad\nname"));
        rejects(()->manager.setFolders(1,Set.of("missing")));
    }
    @Test public void trailerAndTextAreSafe() {
        Anime a=anime(1,"Test");a.trailerSite="youtube";a.trailerId="abc_123";
        assertEquals("https://www.youtube.com/watch?v=abc_123",App.trailerUrl(a));
        a.trailerId="../../bad?x=1";assertNull(App.trailerUrl(a));
        assertEquals("One\nTwo & three",Texts.synopsis("<b>One</b><br>Two &amp; three"));
        assertTrue(App.isNewerVersion("v0.4.1","0.4.0"));assertFalse(App.isNewerVersion("v0.3.9","0.4.0"));
    }
}
