package com.myanimedesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

public class AniListClient {
    private static final String API = "https://graphql.anilist.co";
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    public List<Anime> search(String query) throws IOException, InterruptedException {
        return search(query, 10);
    }

    public List<Anime> search(String query, int perPage) throws IOException, InterruptedException {
        String gql = "query ($search: String, $perPage: Int) { " +
                "Page(perPage: $perPage) { " +
                "media(search: $search, type: ANIME, sort: [POPULARITY_DESC]) { " +
                animeFields() +
                "} } }";

        var variables = mapper.createObjectNode()
                .put("search", query)
                .put("perPage", perPage);

        String payload = mapper.createObjectNode()
                .put("query", gql)
                .set("variables", variables)
                .toString();

        return executeAnimeQuery(payload);
    }

    public Anime getAnimeById(int id) throws IOException, InterruptedException {
        String gql = "query ($id: Int) { " +
                "Media(id: $id, type: ANIME) { " +
                animeFields() +
                "} }";

        var variables = mapper.createObjectNode().put("id", id);
        String payload = mapper.createObjectNode()
                .put("query", gql)
                .set("variables", variables)
                .toString();

        HttpRequest req = createRequest(payload);
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("AniList API returned " + resp.statusCode());

        JsonNode root = mapper.readTree(resp.body());
        JsonNode node = root.path("data").path("Media");
        if (node.isMissingNode() || node.isNull()) return null;
        return parseAnime(node);
    }

    public List<Anime> browse(String mode, String filter, int page, int perPage) throws IOException, InterruptedException {
        String gql = "query ($page: Int, $perPage: Int, $sort: [MediaSort], $genre: String, $tag: String) { " +
                "Page(page: $page, perPage: $perPage) { " +
                "media(type: ANIME, genre: $genre, tag: $tag, sort: $sort) { " +
                animeFields() +
                "} } }";

        String sort = "POPULARITY_DESC";
        if ("RECENT".equals(mode)) sort = "START_DATE_DESC";
        if ("POPULAR".equals(mode) || "GENRE".equals(mode) || "TAG".equals(mode)) sort = "POPULARITY_DESC";

        var variables = mapper.createObjectNode()
                .put("page", page)
                .put("perPage", perPage);
        variables.putArray("sort").add(sort);

        if ("TAG".equals(mode) && filter != null && !filter.isBlank()) {
            variables.put("tag", filter);
        } else if (filter != null && !filter.isBlank()) {
            variables.put("genre", filter);
        }

        String payload = mapper.createObjectNode()
                .put("query", gql)
                .set("variables", variables)
                .toString();

        return executeAnimeQuery(payload);
    }

    private String animeFields() {
        return "id " +
                "title { romaji english native } " +
                "coverImage { extraLarge large medium color } " +
                "episodes duration genres format status seasonYear season " +
                "startDate { year } " +
                "studios(isMain: true) { nodes { name } } ";
    }

    private List<Anime> executeAnimeQuery(String payload) throws IOException, InterruptedException {
        HttpRequest req = createRequest(payload);
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new IOException("AniList API returned " + resp.statusCode());

        JsonNode root = mapper.readTree(resp.body());
        JsonNode media = root.path("data").path("Page").path("media");
        List<Anime> out = new ArrayList<>();

        if (media.isArray()) {
            for (JsonNode node : media) {
                out.add(parseAnime(node));
            }
        }
        return out;
    }

    private HttpRequest createRequest(String payload) {
        return HttpRequest.newBuilder()
                .uri(URI.create(API))
                .timeout(Duration.ofSeconds(18))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("User-Agent", "MyAnimeDesk/0.3.6")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build();
    }

    private Anime parseAnime(JsonNode node) {
        Anime a = new Anime();
        a.id = node.path("id").asInt();
        a.title = readTitle(node.path("title"));
        a.coverImage = readCover(node.path("coverImage"));
        a.episodes = node.path("episodes").isMissingNode() || node.path("episodes").isNull() ? 0 : node.path("episodes").asInt(0);
        a.duration = node.path("duration").isMissingNode() || node.path("duration").isNull() ? 0 : node.path("duration").asInt(0);

        if (node.has("genres")) {
            List<String> g = new ArrayList<>();
            for (JsonNode gn : node.path("genres")) g.add(gn.asText());
            a.genres = g;
        }

        a.format = translateFormat(node.path("format").asText("N/D"));
        a.airingStatus = translateStatus(node.path("status").asText("UNKNOWN"));

        if (node.has("seasonYear") && !node.path("seasonYear").isNull()) {
            a.year = String.valueOf(node.path("seasonYear").asInt());
        } else if (node.path("startDate").has("year") && !node.path("startDate").path("year").isNull()) {
            a.year = String.valueOf(node.path("startDate").path("year").asInt());
        } else {
            a.year = "N/D";
        }

        a.season = translateSeason(node.path("season").asText("UNKNOWN"));

        JsonNode studioNodes = node.path("studios").path("nodes");
        if (studioNodes.isArray() && studioNodes.size() > 0) {
            a.studio = studioNodes.get(0).path("name").asText("N/D");
        } else {
            a.studio = "N/D";
        }

        return a;
    }

    private String readTitle(JsonNode title) {
        if (title.has("english") && !title.path("english").isNull() && !title.path("english").asText().isBlank()) return title.path("english").asText();
        if (title.has("romaji") && !title.path("romaji").isNull() && !title.path("romaji").asText().isBlank()) return title.path("romaji").asText();
        if (title.has("native") && !title.path("native").isNull() && !title.path("native").asText().isBlank()) return title.path("native").asText();
        return "Titolo sconosciuto";
    }

    private String readCover(JsonNode cover) {
        if (cover.has("extraLarge") && !cover.path("extraLarge").isNull() && !cover.path("extraLarge").asText().isBlank()) return cover.path("extraLarge").asText();
        if (cover.has("large") && !cover.path("large").isNull() && !cover.path("large").asText().isBlank()) return cover.path("large").asText();
        if (cover.has("medium") && !cover.path("medium").isNull() && !cover.path("medium").asText().isBlank()) return cover.path("medium").asText();
        return null;
    }

    private String translateFormat(String raw) {
        return switch (raw) {
            case "TV" -> "TV";
            case "TV_SHORT" -> "TV Short";
            case "MOVIE" -> "Film";
            case "SPECIAL" -> "Special";
            case "OVA" -> "OVA";
            case "ONA" -> "ONA";
            case "MUSIC" -> "Music";
            default -> "N/D";
        };
    }

    private String translateStatus(String raw) {
        return switch (raw) {
            case "RELEASING" -> "In corso";
            case "FINISHED" -> "Concluso";
            case "NOT_YET_RELEASED" -> "Non ancora uscito";
            case "CANCELLED" -> "Cancellato";
            case "HIATUS" -> "In pausa";
            default -> "N/D";
        };
    }

    private String translateSeason(String raw) {
        return switch (raw) {
            case "WINTER" -> "Inverno";
            case "SPRING" -> "Primavera";
            case "SUMMER" -> "Estate";
            case "FALL" -> "Autunno";
            default -> "N/D";
        };
    }
}
