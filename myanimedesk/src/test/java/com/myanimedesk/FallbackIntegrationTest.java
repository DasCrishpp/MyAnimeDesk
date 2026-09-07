package com.myanimedesk;

import org.junit.Assume;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import static org.junit.Assert.*;

/** Opt-in live check: normal tests remain deterministic and work offline. */
public class FallbackIntegrationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void liveFallbackIncludesSynopsisTrailerBannerAndJapaneseCast() throws Exception {
        Assume.assumeTrue("enable with -Dfallback.integration=true", Boolean.getBoolean("fallback.integration"));
        Anime seed = new Anime(); seed.id = 104578; seed.title = "Attack on Titan Season 3 Part 2";
        try (KitsuClient api = new KitsuClient(temp.getRoot().toPath())) {
            Anime anime = api.enrich(seed);
            assertTrue(anime.detailsLoaded); assertFalse(anime.description.isBlank());
            assertFalse(anime.trailerId.isBlank()); assertFalse(anime.bannerImage.isBlank());
            assertEquals(38524, anime.malId);
            assertFalse(anime.cast.isEmpty());
            assertTrue(anime.cast.stream().anyMatch(member -> member.name != null && !member.name.isBlank()));
        }
    }
}
