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

public class JikanContractTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private HttpServer server;

    private static final String ANIME = """
        {"mal_id":42,"url":"https://myanimelist.net/anime/42/Test","title":"Test romaji","title_english":"Test Anime",
        "type":"TV","episodes":12,"duration":"24 min per ep","score":8.7,"status":"Finished Airing","year":2024,"season":"spring",
        "synopsis":"A fixture story.","images":{"webp":{"large_image_url":"https://example.org/cover.webp"}},
        "trailer":{"youtube_id":"abc_123","images":{"maximum_image_url":"https://example.org/banner.jpg"}},
        "genres":[{"name":"Action"}],"studios":[{"name":"Fixture Studio"}],
        "relations":[{"relation":"Sequel","entry":[{"mal_id":43,"type":"anime","name":"Test Sequel","url":"https://myanimelist.net/anime/43"}]}]}
        """;

    @Before public void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            String body = path.endsWith("/characters")
                ? "{\"data\":[{\"character\":{\"mal_id\":7,\"name\":\"Hero\",\"images\":{\"jpg\":{\"image_url\":\"https://example.org/hero.jpg\"}}},\"role\":\"Main\",\"voice_actors\":[{\"language\":\"Japanese\",\"person\":{\"name\":\"Actor\",\"images\":{\"jpg\":{\"image_url\":\"https://example.org/actor.jpg\"}}}}]}]}"
                : path.contains("/anime/42/full") ? "{\"data\":" + ANIME + "}" : "{\"data\":[" + ANIME + "]}";
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
    }

    @After public void stop() { server.stop(0); }

    private JikanClient client() {
        return new JikanClient(temp.getRoot().toPath(), URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/"));
    }

    @Test public void parsesDetailsCastTrailerRelationsAndBrowse() throws Exception {
        Anime seed = new Anime(); seed.id = 900; seed.malId = 42; seed.title = "Test Anime";
        try (JikanClient api = client()) {
            Anime anime = api.enrich(seed);
            assertEquals(900, anime.id); assertEquals(42, anime.malId); assertEquals("JIKAN", anime.provider);
            assertEquals("Test Anime", anime.title); assertEquals(12, anime.episodes); assertEquals(24, anime.duration);
            assertEquals(87, anime.averageScore); assertEquals("abc_123", anime.trailerId);
            assertEquals(1, anime.cast.size()); assertEquals("Actor", anime.cast.get(0).voiceActor);
            assertEquals(1, anime.relations.size()); assertEquals("Test Sequel", anime.relations.get(0).anime.title);
            assertEquals(1, api.browse("TRENDING", null, 1, 8).size());
            assertEquals(1, api.search("Test", 5).size());
        }
    }

    @Test public void buildsSafeEmbeddedTrailerUrls() {
        Anime anime = new Anime(); anime.trailerSite = "youtube"; anime.trailerId = "abc_123";
        assertEquals("https://www.youtube-nocookie.com/embed/abc_123?rel=0", App.trailerEmbedUrl(anime));
        anime.trailerId = "bad/id"; assertNull(App.trailerEmbedUrl(anime));
    }
}
