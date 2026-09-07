package com.myanimedesk;

import org.junit.*;
import java.util.List;
import static org.junit.Assert.*;

/** Explicit opt-in: mvn -Dmyanimedesk.onlineTests=true test (requires internet). */
public class AniListIntegrationTest {
    @Test public void extendedDetailsCastPaginationTrendingAndSearchUseValidGraphQL() throws Exception {
        Assume.assumeTrue(Boolean.getBoolean("myanimedesk.onlineTests"));
        try (AniListClient api = new AniListClient()) {
            Anime anime = api.getAnimeById(16498);
            assertNotNull(anime); assertEquals(16498,anime.id); assertTrue(anime.detailsLoaded);
            assertFalse(anime.description.isBlank()); assertFalse(anime.cast.isEmpty()); assertFalse(anime.relations.isEmpty());
            Anime page = api.loadCastPage(16498,2); assertEquals(16498,page.id); assertFalse(page.cast.isEmpty());
            assertFalse(api.browse("TRENDING",null,1,6).isEmpty());
            List<Anime> search = api.search("Frieren",5); assertFalse(search.isEmpty());
            System.out.println("Live AniList OK: details, synopsis, trailer=" + (App.trailerUrl(anime)!=null) + ", cast="+anime.cast.size()+", page2="+page.cast.size()+", related="+anime.relations.size());
        }
    }
}
