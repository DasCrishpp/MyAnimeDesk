package com.myanimedesk;

import com.sun.net.httpserver.HttpServer;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import static org.junit.Assert.*;

/** Local HTTP fixtures test parsing and offline behaviour, not live API availability. */
public class AniListContractTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();
    private HttpServer server;
    private final AtomicBoolean unavailable = new AtomicBoolean();
    private final AtomicReference<String> request = new AtomicReference<>();
    private static final String MEDIA = """
        {"id":42,"title":{"romaji":"Romaji","english":"Fixture"},"episodes":12,"duration":24,
        "genres":["Action"],"format":"TV","status":"FINISHED","season":"SPRING","seasonYear":2024,
        "description":"<b>Story</b>","bannerImage":"https://example.org/banner.jpg",
        "coverImage":{"large":"https://example.org/cover.jpg"},"trailer":{"site":"youtube","id":"abc_123"},
        "characters":{"pageInfo":{"hasNextPage":true},"edges":[{"role":"MAIN","node":{"id":7,"name":{"full":"Character"},"image":{"medium":"https://example.org/character.jpg"}},
        "voiceActors":[{"name":{"full":"Actor"},"image":{"medium":"https://example.org/actor.jpg"}}]}]},
        "relations":{"edges":[{"relationType":"SEQUEL","node":{"id":43,"type":"ANIME","title":{"romaji":"Sequel"}}},{"relationType":"SOURCE","node":{"id":44,"type":"MANGA"}}]}}
        """;
    @Before public void startServer() throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String payload=new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);request.set(payload);
            String body=unavailable.get()?"{\"errors\":[{\"message\":\"Unavailable\"}]}"
                :payload.contains("Page(")?"{\"data\":{\"Page\":{\"media\":["+MEDIA+"]}}}":"{\"data\":{\"Media\":"+MEDIA+"}}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(unavailable.get()?403:200,bytes.length);
            exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
    }
    @After public void stopServer(){server.stop(0);}
    private AniListClient client(){return new AniListClient(temp.getRoot().toPath(),URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/"));}
    @Test public void parsesRichDetailsAndUsesCorrectPagination() throws Exception {
        try(AniListClient api=client()){
            Anime a=api.getAnimeById(42);assertEquals("Fixture",a.title);assertTrue(a.detailsLoaded);
            assertEquals(1,a.cast.size());assertEquals("Actor",a.cast.get(0).voiceActor);assertEquals(1,a.relations.size());
            assertEquals(43,a.relations.get(0).anime.id);assertEquals("https://www.youtube.com/watch?v=abc_123",App.trailerUrl(a));
            assertTrue(request.get().contains("description(asHtml: false)"));assertTrue(request.get().contains("relationType(version: 2)"));
            assertTrue(api.loadCastPage(42,2).hasMoreCast);assertTrue(request.get().contains("\"page\":2"));
            assertEquals(1,api.browse("TRENDING",null,1,6).size());assertTrue(request.get().contains("TRENDING_DESC"));
            assertEquals(1,api.search("test",5).size());
        }
    }
    @Test public void usesSuccessfulDiskResponseWhenServiceGoesOffline() throws Exception {
        try(AniListClient api=client()){assertEquals(42,api.getAnimeById(42).id);assertFalse(api.isOffline());}
        unavailable.set(true);
        try(AniListClient api=client()){assertEquals(42,api.getAnimeById(42).id);assertTrue(api.isOffline());}
    }
}
