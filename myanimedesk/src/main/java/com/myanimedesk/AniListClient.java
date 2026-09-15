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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Arrays;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

public class AniListClient implements AutoCloseable {
    private static final String API = "https://graphql.anilist.co";
    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(12))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path profile;
    private final URI endpoint;
    private final JikanClient jikan;
    private final KitsuClient fallback;
    private volatile boolean offline;
    private volatile long retryAfter;
    public AniListClient() { this(Path.of(System.getProperty("user.home"), ".myanimedesk")); }
    AniListClient(Path profile) { this(profile, URI.create(API)); }
    AniListClient(Path profile, URI endpoint) {
        this.profile = profile; this.endpoint = endpoint;
        this.jikan = new JikanClient(profile); this.fallback = new KitsuClient(profile);
    }
    public boolean isOffline() { return offline; }
    private final Map<String, List<Anime>> cache = Collections.synchronizedMap(new LinkedHashMap<>(64, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, List<Anime>> entry) { return size() > 64; }
    });
    // Filtro 18+ rimosso: il client mostra i risultati restituiti da AniList senza bloccarli.
    public void setHideAdultContent(boolean hideAdultContent) {
        // Metodo lasciato vuoto per compatibilità con vecchie versioni dell'interfaccia.
    }

    public List<Anime> search(String query) throws IOException, InterruptedException {
        return search(query, 10);
    }

    public List<Anime> search(String query, int perPage) throws IOException, InterruptedException {
        String cacheKey = "search:" + query.toLowerCase().trim() + ":" + perPage;
        List<Anime> cached = cache.get(cacheKey);
        if (cached != null) return new ArrayList<>(cached);

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

        List<Anime> results;
        try { results = executePageQuery(payload); }
        catch (IOException failure) { throw failure; }
        cache.put(cacheKey, new ArrayList<>(results));
        return results;
    }

    /** Loads details from AniList, then transparently uses Jikan/MyAnimeList if needed. */
    public Anime getAnimeDetails(Anime anime) throws IOException, InterruptedException {
        if (anime == null) return null;
        if (anime.id < 1_000_000_000) return getAnimeById(anime.id);
        // Old fallback entries keep their library ID; resolve by MAL ID, never by fuzzy title.
        if (anime.malId <= 0) throw new IOException("Missing AniList mapping");
        String query = "query($id:Int){ Media(idMal:$id,type:ANIME){" + animeFields() + detailFields() + "}}";
        JsonNode node = responseData(mapper.writeValueAsString(Map.of("query", query, "variables", Map.of("id", anime.malId))))
            .path("data").path("Media");
        if (node.isNull() || node.isMissingNode()) throw new IOException("Anime unavailable");
        Anime result = parseAnime(node); result.id = anime.id;
        return result;
    }

    public Anime getAnimeById(int id) throws IOException, InterruptedException {
        String gql = "query ($id: Int) { " +
                "Media(id: $id, type: ANIME) { " +
                animeFields() + detailFields() +
                "} }";

        var variables = mapper.createObjectNode().put("id", id);
        String payload = mapper.createObjectNode()
                .put("query", gql)
                .set("variables", variables)
                .toString();

        JsonNode root = responseData(payload);
        JsonNode node = root.path("data").path("Media");
        if (node.isMissingNode() || node.isNull()) return null;
        return parseAnime(node);
    }

    private String detailFields() {
        return "description(asHtml: false) trailer { id site } siteUrl averageScore " +
            "characters(page: 1, perPage: 25, sort: [ROLE, RELEVANCE, ID]) { " +
            "pageInfo { hasNextPage } edges { role node { id name { full } image { medium } } " +
            "voiceActors(language: JAPANESE, sort: RELEVANCE) { name { full } image { medium } } } } " +
            "relations { edges { relationType(version: 2) node { id type title { romaji english native } " +
            "coverImage { large } format episodes duration } } } ";
    }

    public Anime loadCastPage(int id, int page) throws IOException, InterruptedException {
        String query = "query ($id: Int, $page: Int) { Media(id:$id, type:ANIME) { id " +
            "characters(page:$page, perPage:25, sort:[ROLE, RELEVANCE, ID]) { pageInfo { hasNextPage } " +
            "edges { role node { id name { full } image { medium } } " +
            "voiceActors(language:JAPANESE, sort:RELEVANCE) { name { full } image { medium } } } } } }";
        String payload = mapper.writeValueAsString(Map.of("query", query, "variables", Map.of("id", id, "page", page)));
        JsonNode root = responseData(payload);
        if (root.path("data").path("Media").isNull()) throw new IOException("Anime unavailable");
        return parseAnime(root.path("data").path("Media"));
    }

    public Anime retryAlternativeCast(Anime anime) throws IOException, InterruptedException {
        if (anime == null || anime.malId <= 0) throw new IOException("No cast provider ID");
        Anime loaded = new Anime();
        jikan.loadCast(anime.malId, loaded);
        if (loaded.cast.isEmpty()) throw new IOException("No cast available");
        return loaded;
    }

    public List<Anime> browse(String mode, String filter, int page, int perPage) throws IOException, InterruptedException {
        String cacheKey = "browse:" + mode + ":" + filter + ":" + page + ":" + perPage;
        List<Anime> cached = cache.get(cacheKey);
        if (cached != null) return new ArrayList<>(cached);

        String sort = "POPULARITY_DESC";
        if ("RECENT".equalsIgnoreCase(mode)) {
            sort = "START_DATE_DESC";
        }
        if ("TRENDING".equalsIgnoreCase(mode)) sort = "TRENDING_DESC";

        boolean useTag = filter != null && !filter.isBlank() && ("TAG".equalsIgnoreCase(mode) || Preferences.TAGS.contains(filter));
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

        List<Anime> results;
        try { results = executePageQuery(payload); }
        catch (IOException failure) {
            // Read the previous app's discover cache without changing it.
            results = legacyBrowse(mode, filter, page, perPage);
            if (results == null || useTag) throw failure;
        }
        if (useGenre) results.removeIf(a -> a.genres == null || !a.genres.contains(filter));
        cache.put(cacheKey, new ArrayList<>(results));
        return results;
    }

    private List<Anime> legacyBrowse(String mode, String filter, int page, int perPage) {
        try {
            Path legacy = profile.resolve("discover_cache.json");
            if (!Files.exists(legacy) || Files.size(legacy) > 16 * 1024 * 1024 || perPage > 30) return null;
            JsonNode saved = mapper.readTree(legacy.toFile());
            String key = mode + "|" + (filter == null ? "" : filter) + "|" + page + "|30";
            JsonNode entries = saved.path(key);
            if (!entries.isArray()) return null;
            List<Anime> results = new ArrayList<>(Arrays.asList(mapper.treeToValue(entries, Anime[].class)));
            if (perPage < results.size()) results = new ArrayList<>(results.subList(0, perPage));
            return results;
        } catch (IOException ignored) { return null; }
    }

    private String animeFields() {
        return "id idMal " +
                "isAdult bannerImage " +
                "title { romaji english native } " +
                "coverImage { extraLarge large medium color } " +
                "episodes duration genres format status seasonYear season " +
                "startDate { year } " +
                "studios(isMain: true) { nodes { name } } ";
    }

    private List<Anime> executePageQuery(String payload) throws IOException, InterruptedException {
        JsonNode root = responseData(payload);
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

    /** Cached API responses survive restarts; only successful, valid responses are cached. */
    private JsonNode responseData(String payload) throws IOException, InterruptedException {
        Path saved;
        try {
            String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload.getBytes(StandardCharsets.UTF_8)));
            saved = profile.resolve("api_cache").resolve(hash + ".json");
        } catch (Exception e) { throw new IOException(e); }
        // A fresh successful response is served immediately, including after restarting the app.
        // This avoids waiting for the network every time a Discover row or an anime sheet is reopened.
        boolean cataloguePage = payload.contains("Page(");
        if (cataloguePage && Files.exists(saved) && Files.size(saved) <= 8 * 1024 * 1024) {
            try {
                if (Files.getLastModifiedTime(saved).toInstant().isAfter(Instant.now().minus(6, ChronoUnit.HOURS))) {
                    JsonNode root = mapper.readTree(saved.toFile()); throwIfGraphQLError(root); offline = false; return root;
                }
            } catch (IOException ignored) { /* A broken cache is bypassed and replaced by the next valid response. */ }
        }
        try {
            if (System.currentTimeMillis() < retryAfter) throw new IOException("AniList temporarily unavailable");
            HttpResponse<String> response = sendWithRetry(payload);
            if (response.statusCode() != 200) {
                if (response.statusCode() == 403 || response.statusCode() == 429) retryAfter = System.currentTimeMillis() + 30_000;
                throw new IOException("AniList HTTP " + response.statusCode() + " - " + response.body());
            }
            JsonNode root = mapper.readTree(response.body()); throwIfGraphQLError(root); offline = false;
            try { AtomicFiles.write(saved, response.body().getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { }
            return root;
        } catch (IOException failure) {
            offline = true;
            if (Files.exists(saved) && Files.size(saved) <= 8 * 1024 * 1024) {
                JsonNode root = mapper.readTree(saved.toFile()); throwIfGraphQLError(root); return root;
            }
            throw failure;
        }
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
                if (resp.statusCode() < 500) return resp;
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
                .uri(endpoint)
                .timeout(Duration.ofSeconds(18))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Accept", "application/json")
                .header("User-Agent", "MyAnimeDesk/0.4.0")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
    }

    private Anime parseAnime(JsonNode node) {
        Anime a = new Anime();
        a.id = node.path("id").asInt();
        a.malId = node.path("idMal").asInt(0);
        a.provider = "ANILIST";
        a.title = readTitle(node.path("title"));
        a.coverImage = readCover(node.path("coverImage"));
        a.bannerImage = node.path("bannerImage").asText("");
        a.description = node.path("description").asText("");
        a.trailerId = node.path("trailer").path("id").asText("");
        a.trailerSite = node.path("trailer").path("site").asText("");
        a.siteUrl = node.path("siteUrl").asText("");
        a.averageScore = node.path("averageScore").asInt(0);
        a.detailsLoaded = node.has("description");
        a.hasMoreCast = node.path("characters").path("pageInfo").path("hasNextPage").asBoolean(false);
        for (JsonNode edge : node.path("characters").path("edges")) {
            Anime.CastMember member = new Anime.CastMember();
            member.id = edge.path("node").path("id").asInt();
            member.name = edge.path("node").path("name").path("full").asText("");
            member.image = edge.path("node").path("image").path("medium").asText("");
            member.role = edge.path("role").asText("");
            List<String> actors = new ArrayList<>();
            for (JsonNode actor : edge.path("voiceActors")) actors.add(actor.path("name").path("full").asText(""));
            member.voiceActor = String.join(", ", actors);
            member.voiceImage = edge.path("voiceActors").path(0).path("image").path("medium").asText("");
            a.cast.add(member);
        }
        for (JsonNode edge : node.path("relations").path("edges")) {
            if (!"ANIME".equals(edge.path("node").path("type").asText())) continue;
            Anime.Relation relation = new Anime.Relation();
            relation.type = edge.path("relationType").asText("");
            relation.anime = parseAnime(edge.path("node"));
            a.relations.add(relation);
        }
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

    @Override public void close() {
        cache.clear();
        jikan.close();
        fallback.close();
        client.shutdownNow();
    }
}
