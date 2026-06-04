package com.myanimedesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class AniListClient {
    private static final String API = "https://graphql.anilist.co";
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, List<Anime>> cache = new ConcurrentHashMap<>();
    // Filtro 18+ rimosso: il client mostra i risultati restituiti da AniList senza bloccarli.
    public void setHideAdultContent(boolean hideAdultContent) {
        // Metodo lasciato vuoto per compatibilità con vecchie versioni dell'interfaccia.
    }

    public List<Anime> search(String query) throws IOException, InterruptedException {
        return search(query, 10);
    }

    public List<Anime> search(String query, int perPage) throws IOException, InterruptedException {
        String cacheKey = "search:" + query.toLowerCase().trim() + ":" + perPage;
        if (cache.containsKey(cacheKey)) return new ArrayList<>(cache.get(cacheKey));

        String gql = "query ($search: String, $perPage: Int) { " +
                "Page(page: 1, perPage: $perPage) { " +
                "media(search: $search, type: ANIME, sort: POPULARITY_DESC) { " +
                animeFields() +
                "} } }";

        var variables = mapper.createObjectNode()
                .put("search", query)
                .put("perPage", perPage);

        String payload = mapper.createObjectNode()
                .put("query", gql)
                .set("variables", variables)
                .toString();

        List<Anime> results = executePageQuery(payload);
        cache.put(cacheKey, new ArrayList<>(results));
        return results;
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

        HttpResponse<String> resp = sendWithRetry(payload);
        if (resp.statusCode() != 200) {
            throw new IOException("AniList HTTP " + resp.statusCode() + " - " + resp.body());
        }

        JsonNode root = mapper.readTree(resp.body());
        throwIfGraphQLError(root);
        JsonNode node = root.path("data").path("Media");
        if (node.isMissingNode() || node.isNull()) return null;
        return parseAnime(node);
    }

    public List<Anime> browse(String mode, String filter, int page, int perPage) throws IOException, InterruptedException {
        String cacheKey = "browse:" + mode + ":" + filter + ":" + page + ":" + perPage;
        if (cache.containsKey(cacheKey)) return new ArrayList<>(cache.get(cacheKey));

        String sort = "POPULARITY_DESC";
        if ("RECENT".equalsIgnoreCase(mode)) {
            sort = "START_DATE_DESC";
        }

        boolean useTag = "TAG".equalsIgnoreCase(mode) && filter != null && !filter.isBlank();
        boolean useGenre = !useTag && filter != null && !filter.isBlank();

        StringBuilder mediaArgs = new StringBuilder("type: ANIME, sort: ").append(sort);
        if (useTag) mediaArgs.append(", tag: $tag");
        if (useGenre) mediaArgs.append(", genre: $genre");

        String gql = "query ($page: Int, $perPage: Int" +
                (useGenre ? ", $genre: String" : "") +
                (useTag ? ", $tag: String" : "") +
                ") { " +
                "Page(page: $page, perPage: $perPage) { " +
                "media(" + mediaArgs + ") { " +
                animeFields() +
                "} } }";

        var variables = mapper.createObjectNode()
                .put("page", page)
                .put("perPage", perPage);
        if (useGenre) variables.put("genre", filter);
        if (useTag) variables.put("tag", filter);

        String payload = mapper.createObjectNode()
                .put("query", gql)
                .set("variables", variables)
                .toString();

        List<Anime> results = executePageQuery(payload);
        if (results.isEmpty() && useTag && filter != null && !filter.isBlank()) {
            // Alcuni tag di AniList sono più delicati dei generi: se il tag non rende risultati,
            // faccio una ricerca testuale di fallback così la sezione non rimane vuota.
            results = search(filter, perPage);
        }
        cache.put(cacheKey, new ArrayList<>(results));
        return results;
    }

    private String animeFields() {
        return "id " +
                "isAdult " +
                "title { romaji english native } " +
                "coverImage { extraLarge large medium color } " +
                "episodes duration genres format status seasonYear season " +
                "startDate { year } " +
                "studios(isMain: true) { nodes { name } } ";
    }

    private List<Anime> executePageQuery(String payload) throws IOException, InterruptedException {
        HttpResponse<String> resp = sendWithRetry(payload);
        if (resp.statusCode() != 200) {
            throw new IOException("AniList HTTP " + resp.statusCode() + " - " + resp.body());
        }

        JsonNode root = mapper.readTree(resp.body());
        throwIfGraphQLError(root);
        JsonNode media = root.path("data").path("Page").path("media");

        List<Anime> out = new ArrayList<>();
        if (media.isArray()) {
            for (JsonNode node : media) {
                Anime parsed = parseAnime(node);
                if (parsed != null) out.add(parsed);
            }
        }
        return out;
    }

    private void throwIfGraphQLError(JsonNode root) throws IOException {
        JsonNode errors = root.path("errors");
        if (errors.isArray() && errors.size() > 0) {
            StringBuilder message = new StringBuilder();
            for (JsonNode error : errors) {
                if (message.length() > 0) message.append(" | ");
                message.append(error.path("message").asText("Errore GraphQL AniList"));
            }
            throw new IOException(message.toString());
        }
    }


    private HttpResponse<String> sendWithRetry(String payload) throws IOException, InterruptedException {
        IOException lastIo = null;
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                HttpResponse<String> resp = client.send(createRequest(payload), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() != 429 && resp.statusCode() < 500) return resp;
                if (attempt == 3) return resp;
            } catch (IOException e) {
                lastIo = e;
                if (attempt == 3) throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw e;
            }
            Thread.sleep(500L * attempt);
        }
        if (lastIo != null) throw lastIo;
        throw new IOException("Errore di rete sconosciuto con AniList");
    }

    private HttpRequest createRequest(String payload) {
        return HttpRequest.newBuilder()
                .uri(URI.create(API))
                .timeout(Duration.ofSeconds(18))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("User-Agent", "MyAnimeDesk/0.3.8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
    }

    private Anime parseAnime(JsonNode node) {
        Anime a = new Anime();
        a.id = node.path("id").asInt();
        a.title = readTitle(node.path("title"));
        a.coverImage = readCover(node.path("coverImage"));
        a.episodes = node.path("episodes").isMissingNode() || node.path("episodes").isNull() ? 0 : node.path("episodes").asInt(0);
        a.duration = node.path("duration").isMissingNode() || node.path("duration").isNull() ? 0 : node.path("duration").asInt(0);

        List<String> genres = new ArrayList<>();
        if (node.has("genres") && node.path("genres").isArray()) {
            for (JsonNode gn : node.path("genres")) genres.add(gn.asText());
        }
        a.genres = genres;

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
