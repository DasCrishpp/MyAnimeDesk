package com.myanimedesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;

public class AniListClient {
    private static final String API = "https://graphql.anilist.co";
    private final HttpClient client = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public List<Anime> search(String query) throws IOException, InterruptedException {
        String gql = "query ($search: String) { Page(perPage:10) { media(search: $search, type: ANIME) { id title { romaji } coverImage { large } episodes duration genres } } }";
        String payload = mapper.createObjectNode()
                .put("query", gql)
                .set("variables", mapper.createObjectNode().put("search", query))
                .toString();

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();

        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("AniList API returned " + resp.statusCode());

        JsonNode root = mapper.readTree(resp.body());
        JsonNode media = root.path("data").path("Page").path("media");
        List<Anime> out = new ArrayList<>();
        for (JsonNode node : media) {
            Anime a = new Anime();
            a.id = node.path("id").asInt();
            a.title = node.path("title").path("romaji").asText(null);
            a.coverImage = node.path("coverImage").path("large").asText(null);
            a.episodes = node.path("episodes").asInt(0);
            a.duration = node.path("duration").asInt(0);
            if (node.has("genres")) {
                List<String> g = new ArrayList<>();
                for (JsonNode gn : node.path("genres")) g.add(gn.asText());
                a.genres = g;
            }
            out.add(a);
        }
        return out;
    }
}
