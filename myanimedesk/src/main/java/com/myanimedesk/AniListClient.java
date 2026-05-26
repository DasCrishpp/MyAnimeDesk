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
        // Query GraphQL aggiornata per estrarre tutti i dettagli precisi dell'anime e dello studio principale
        String gql = "query ($search: String) { " +
                "Page(perPage:10) { " +
                "media(search: $search, type: ANIME) { " +
                "id title { romaji } coverImage { large } episodes duration genres format status seasonYear season " +
                "studios(isMain: true) { nodes { name } } " +
                "} } }";

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
            a.title = node.path("title").path("romaji").asText("Titolo Sconosciuto");
            a.coverImage = node.path("coverImage").path("large").asText(null);
            a.episodes = node.path("episodes").asInt(0);
            a.duration = node.path("duration").asInt(0);
            
            if (node.has("genres")) {
                List<String> g = new ArrayList<>();
                for (JsonNode gn : node.path("genres")) g.add(gn.asText());
                a.genres = g;
            }
            
            // --- PARSING E TRADUZIONE IN ITALIANO DEI NUOVI DATI ---
            
            // 1. Tipo di Formato (TV, MOVIE, OVA, ecc.)
            a.format = node.path("format").asText("TV");
            
            // 2. Stato della Trasmissione
            String rawStatus = node.path("status").asText("UNKNOWN");
            a.airingStatus = switch (rawStatus) {
                case "RELEASING" -> "In Corso";
                case "FINISHED" -> "Concluso";
                case "NOT_YET_RELEASED" -> "Non ancora iniziato";
                case "CANCELLED" -> "Cancellato";
                case "HIATUS" -> "In Pausa";
                default -> "N/D";
            };
            
            // 3. Anno di Uscita
            a.year = node.has("seasonYear") && !node.path("seasonYear").isNull() 
                    ? String.valueOf(node.path("seasonYear").asInt()) : "N/D";
            
            // 4. Stagione dell'anno
            String rawSeason = node.path("season").asText("UNKNOWN");
            a.season = switch (rawSeason) {
                case "WINTER" -> "Inverno";
                case "SPRING" -> "Primavera";
                case "SUMMER" -> "Estate";
                case "FALL" -> "Autunno";
                default -> "N/D";
            };
            
            // 5. Studio di Animazione principale (isMain: true)
            JsonNode studioNodes = node.path("studios").path("nodes");
            if (studioNodes.isArray() && studioNodes.size() > 0) {
                a.studio = studioNodes.get(0).path("name").asText("N/D");
            } else {
                a.studio = "N/D";
            }

            out.add(a);
        }
        return out;
    }
}