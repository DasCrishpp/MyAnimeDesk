package com.myanimedesk;

import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

public class KitsuContractTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private HttpServer server;
    private static final String ANIME = """
        {"id":"42","type":"anime","attributes":{"slug":"fixture-anime","canonicalTitle":"Fixture Anime",
        "synopsis":"Fixture synopsis","averageRating":"84.7","startDate":"2024-04-10","subtype":"TV","status":"finished",
        "episodeCount":12,"episodeLength":24,"youtubeVideoId":"abc_123",
        "posterImage":{"original":"https://example.org/poster.jpg"},"coverImage":{"original":"https://example.org/banner.jpg"}}}
        """;

    @Before public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body;
            if (path.startsWith("/jikan/")) body = "{\"data\":[{\"character\":{\"mal_id\":7,\"name\":\"Hero\",\"images\":{\"jpg\":{\"image_url\":\"hero.jpg\"}}},\"role\":\"Main\",\"voice_actors\":[{\"language\":\"Japanese\",\"person\":{\"name\":\"Actor\",\"images\":{\"jpg\":{\"image_url\":\"actor.jpg\"}}}}]}]}";
            else if (path.endsWith("/characters")) body = "{\"data\":[{\"attributes\":{\"role\":\"main\"},\"relationships\":{\"character\":{\"data\":{\"id\":\"7\"}}}}],\"included\":[{\"id\":\"7\",\"type\":\"characters\",\"attributes\":{\"canonicalName\":\"Hero\",\"image\":{\"original\":\"hero.jpg\"}}}]}";
            else if (path.endsWith("/mappings")) body = "{\"data\":[{\"attributes\":{\"externalSite\":\"myanimelist/anime\",\"externalId\":\"38524\"}}]}";
            else if (path.endsWith("/anime/42")) body = "{\"data\":" + ANIME + "}";
            else body = "{\"data\":[" + ANIME + "]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }

    @After public void stop() { server.stop(0); }

    @Test public void enrichesDetailsTrailerMappingCastAndBanner() throws Exception {
        URI kitsu = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/kitsu/");
        Anime seed = new Anime(); seed.id = 90; seed.kitsuId = 42; seed.title = "Fixture Anime";
        try (KitsuClient api = new KitsuClient(temp.getRoot().toPath(), kitsu)) {
            Anime anime = api.enrich(seed);
            assertEquals(90, anime.id); assertEquals(42, anime.kitsuId); assertEquals(38524, anime.malId);
            assertEquals("KITSU", anime.provider); assertEquals("abc_123", anime.trailerId);
            assertEquals("https://example.org/banner.jpg", anime.bannerImage);
            assertEquals(1, anime.cast.size()); assertEquals("Hero", anime.cast.get(0).name); assertTrue(anime.castPartial);
            assertEquals(1, api.browse("TRENDING", null, 1, 8).size());
            assertEquals(1, api.search("Fixture", 5).size());
        }
    }
}
