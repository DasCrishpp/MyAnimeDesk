package com.myanimedesk;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only fallback for anime metadata while AniList is unavailable. */
final class JikanClient implements AutoCloseable {
    private static final URI API = URI.create("https://api.jikan.moe/v4/");
    private static final int ID_OFFSET = 1_000_000_000;
    private static final Pattern MINUTES = Pattern.compile("(\\d+)\\s*min", Pattern.CASE_INSENSITIVE);
    private static final Pattern YOUTUBE_EMBED = Pattern.compile("(?:youtube(?:-nocookie)?\\.com/embed/)([A-Za-z0-9_-]{1,100})", Pattern.CASE_INSENSITIVE);
    private static final Map<String, Integer> GENRES = Map.ofEntries(
        Map.entry("Action", 1), Map.entry("Adventure", 2), Map.entry("Comedy", 4),
        Map.entry("Drama", 8), Map.entry("Fantasy", 10), Map.entry("Horror", 14),
        Map.entry("Mahou Shoujo", 17), Map.entry("Mecha", 18), Map.entry("Music", 19),
        Map.entry("Mystery", 7), Map.entry("Psychological", 40), Map.entry("Romance", 22),
        Map.entry("Sci-Fi", 24), Map.entry("Slice of Life", 36), Map.entry("Sports", 30),
        Map.entry("Supernatural", 37), Map.entry("Thriller", 41), Map.entry("Ecchi", 9),
        Map.entry("Hentai", 12), Map.entry("Isekai", 62), Map.entry("Shounen", 27)
    );

    private final ObjectMapper mapper = new ObjectMapper();
    private final Path cacheDirectory;
    private final URI endpoint;
    private long lastRequest;

    JikanClient(Path profile) { this(profile, API); }
    JikanClient(Path profile, URI endpoint) {
        this.cacheDirectory = profile.resolve("jikan_cache");
        this.endpoint = endpoint;
    }

    Anime enrich(Anime seed) throws IOException, InterruptedException {
        int malId = seed.malId;
        JsonNode match = null;
        if (malId <= 0) {
            List<JsonNode> matches = searchNodes(seed.title, 10);
            match = bestMatch(seed.title, matches);
            if (match == null) throw new IOException("Anime not found on fallback provider");
            malId = match.path("mal_id").asInt();
        }

        JsonNode details;
        try { details = data(request("anime/" + malId + "/full")); }
        catch (IOException failure) {
            if (match == null) throw failure;
            details = match;
        }
        Anime result = parseAnime(details, seed.id > 0 ? seed.id : localId(malId), true);
        result.malId = malId;
        result.provider = "JIKAN";
        if (result.trailerId == null || result.trailerId.isBlank()) {
            try { parseVideoTrailer(request("anime/" + malId + "/videos").path("data").path("promo"), result); }
            catch (IOException ignored) { }
        }
        try { loadCast(malId, result); }
        catch (IOException ignored) { }
        return result;
    }

    void loadCast(int malId, Anime anime) throws IOException, InterruptedException {
        parseCast(request("anime/" + malId + "/characters").path("data"), anime);
        anime.castPartial = false;
    }

    List<Anime> search(String query, int limit) throws IOException, InterruptedException {
        List<JsonNode> nodes = searchNodes(query, Math.min(25, Math.max(1, limit)));
        List<Anime> result = new ArrayList<>();
        for (JsonNode node : nodes) result.add(parseAnime(node, localId(node.path("mal_id").asInt()), false));
        return result;
    }

    List<Anime> browse(String mode, String filter, int page, int limit) throws IOException, InterruptedException {
        int safePage = Math.max(1, page), safeLimit = Math.min(25, Math.max(1, limit));
        String path;
        if ("RECENT".equalsIgnoreCase(mode)) {
            path = "seasons/now?page=" + safePage + "&limit=" + safeLimit;
        } else if (("GENRE".equalsIgnoreCase(mode) || "TAG".equalsIgnoreCase(mode)) && filter != null) {
            Integer genre = GENRES.get(filter);
            if (genre == null) throw new IOException("Unsupported fallback genre: " + filter);
            path = "anime?genres=" + genre + "&order_by=popularity&sort=asc&page=" + safePage + "&limit=" + safeLimit;
        } else {
            path = "top/anime?page=" + safePage + "&limit=" + safeLimit + "&filter=bypopularity";
        }
        JsonNode entries;
        try { entries = request(path).path("data"); }
        catch (IOException failure) {
            if (!path.startsWith("top/anime")) throw failure;
            entries = request("seasons/now?page=" + safePage + "&limit=" + safeLimit).path("data");
        }
        List<Anime> result = new ArrayList<>();
        if (entries.isArray()) for (JsonNode node : entries)
            result.add(parseAnime(node, localId(node.path("mal_id").asInt()), false));
        return result;
    }

    private List<JsonNode> searchNodes(String query, int limit) throws IOException, InterruptedException {
        String encoded = URLEncoder.encode(query == null ? "" : query.strip(), StandardCharsets.UTF_8);
        JsonNode entries = request("anime?q=" + encoded + "&limit=" + limit + "&order_by=popularity&sort=asc").path("data");
        List<JsonNode> result = new ArrayList<>();
        if (entries.isArray()) entries.forEach(result::add);
        return result;
    }

    private JsonNode bestMatch(String requested, List<JsonNode> nodes) {
        String wanted = normalized(requested);
        return nodes.stream().min(Comparator.comparingInt(node -> titleScore(wanted, node))).orElse(null);
    }

    private int titleScore(String wanted, JsonNode node) {
        int best = Integer.MAX_VALUE;
        List<String> titles = new ArrayList<>();
        titles.add(node.path("title").asText(""));
        titles.add(node.path("title_english").asText(""));
        titles.add(node.path("title_japanese").asText(""));
        node.path("title_synonyms").forEach(value -> titles.add(value.asText("")));
        for (String title : titles) {
            String candidate = normalized(title);
            if (candidate.equals(wanted)) return 0;
            if (candidate.contains(wanted) || wanted.contains(candidate)) best = Math.min(best, 10 + Math.abs(candidate.length() - wanted.length()));
            else best = Math.min(best, 100 + Math.abs(candidate.length() - wanted.length()));
        }
        return best;
    }

    private String normalized(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "").replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }

    private Anime parseAnime(JsonNode node, int appId, boolean detailed) {
        Anime anime = new Anime();
        anime.id = appId;
        anime.malId = node.path("mal_id").asInt();
        anime.provider = "JIKAN";
        anime.title = firstText(node, "title_english", "title", "title_japanese");
        if (anime.title.isBlank()) anime.title = "Anime #" + anime.malId;
        anime.coverImage = firstText(node.path("images").path("webp"), "large_image_url", "image_url");
        if (anime.coverImage.isBlank()) anime.coverImage = firstText(node.path("images").path("jpg"), "large_image_url", "image_url");
        anime.bannerImage = firstText(node.path("trailer").path("images"), "maximum_image_url", "large_image_url");
        if (anime.bannerImage.isBlank()) anime.bannerImage = anime.coverImage;
        anime.description = node.path("synopsis").asText("");
        anime.trailerId = node.path("trailer").path("youtube_id").asText("");
        anime.trailerSite = anime.trailerId.isBlank() ? "" : "youtube";
        anime.siteUrl = node.path("url").asText("");
        anime.averageScore = (int) Math.round(node.path("score").asDouble(0) * 10);
        anime.episodes = node.path("episodes").asInt(0);
        anime.duration = duration(node.path("duration").asText(""));
        anime.genres = new ArrayList<>();
        node.path("genres").forEach(value -> anime.genres.add(value.path("name").asText("")));
        anime.format = format(node.path("type").asText(""));
        anime.airingStatus = status(node.path("status").asText(""));
        anime.year = node.path("year").isInt() ? node.path("year").asText() :
            node.path("aired").path("prop").path("from").path("year").asText("N/D");
        anime.season = season(node.path("season").asText(""));
        anime.studio = node.path("studios").isArray() && !node.path("studios").isEmpty()
            ? node.path("studios").path(0).path("name").asText("N/D") : "N/D";
        anime.detailsLoaded = detailed;
        if (detailed) parseRelations(node.path("relations"), anime);
        return anime;
    }

    private void parseCast(JsonNode entries, Anime anime) {
        if (!entries.isArray()) return;
        for (JsonNode entry : entries) {
            Anime.CastMember member = new Anime.CastMember();
            JsonNode character = entry.path("character");
            member.id = character.path("mal_id").asInt();
            member.name = character.path("name").asText("");
            member.image = firstText(character.path("images").path("webp"), "image_url", "small_image_url");
            if (member.image.isBlank()) member.image = firstText(character.path("images").path("jpg"), "image_url", "small_image_url");
            member.role = "Main".equalsIgnoreCase(entry.path("role").asText()) ? "MAIN" : "SUPPORTING";
            for (JsonNode actor : entry.path("voice_actors")) {
                if (!"Japanese".equalsIgnoreCase(actor.path("language").asText())) continue;
                member.voiceActor = actor.path("person").path("name").asText("");
                member.voiceImage = firstText(actor.path("person").path("images").path("jpg"), "image_url");
                break;
            }
            anime.cast.add(member);
        }
        anime.hasMoreCast = false;
    }

    private void parseVideoTrailer(JsonNode promos, Anime anime) {
        if (!promos.isArray()) return;
        for (JsonNode promo : promos) {
            JsonNode trailer = promo.path("trailer");
            String id = trailer.path("youtube_id").asText("");
            if (id.isBlank()) {
                Matcher match = YOUTUBE_EMBED.matcher(trailer.path("embed_url").asText(""));
                if (match.find()) id = match.group(1);
            }
            if (!id.matches("[A-Za-z0-9_-]{1,100}")) continue;
            anime.trailerId = id; anime.trailerSite = "youtube";
            if (anime.bannerImage == null || anime.bannerImage.isBlank())
                anime.bannerImage = firstText(trailer.path("images"), "maximum_image_url", "large_image_url");
            return;
        }
    }

    private void parseRelations(JsonNode groups, Anime anime) {
        if (!groups.isArray()) return;
        for (JsonNode group : groups) for (JsonNode entry : group.path("entry")) {
            if (!"anime".equalsIgnoreCase(entry.path("type").asText())) continue;
            int malId = entry.path("mal_id").asInt();
            if (malId <= 0) continue;
            Anime related = new Anime();
            related.id = localId(malId); related.malId = malId; related.provider = "JIKAN";
            related.title = entry.path("name").asText("Anime #" + malId);
            related.siteUrl = entry.path("url").asText(""); related.genres = new ArrayList<>();
            Anime.Relation relation = new Anime.Relation();
            relation.type = group.path("relation").asText("").toUpperCase(Locale.ROOT).replace(' ', '_');
            relation.anime = related; anime.relations.add(relation);
        }
    }

    private JsonNode request(String path) throws IOException, InterruptedException {
        String key;
        try { key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IOException(e); }
        Path saved = cacheDirectory.resolve(key + ".json");
        try {
            Response response = send(path);
            if (response.statusCode() != 200) throw new IOException("Jikan HTTP " + response.statusCode());
            JsonNode root = mapper.readTree(response.body());
            if (!root.has("data")) throw new IOException("Invalid Jikan response");
            try { AtomicFiles.write(saved, response.body().getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { }
            return root;
        } catch (IOException failure) {
            if (Files.exists(saved) && Files.size(saved) <= 12 * 1024 * 1024) return mapper.readTree(saved.toFile());
            throw failure;
        }
    }

    private synchronized Response send(String path) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            throttle();
            HttpURLConnection connection = (HttpURLConnection) endpoint.resolve(path).toURL().openConnection();
            connection.setConnectTimeout(12_000); connection.setReadTimeout(20_000);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("User-Agent", "MyAnimeDesk/0.4.0");
            connection.setRequestProperty("Connection", "close");
            connection.setRequestMethod("GET");
            int status;
            String body;
            try {
                status = connection.getResponseCode();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                if (stream != null) stream.close();
            } finally {
                connection.disconnect();
            }
            lastRequest = System.currentTimeMillis();
            Response response = new Response(status, body);
            boolean temporary = status == 429 || status >= 500;
            if (!temporary || attempt == 2) return response;
            Thread.sleep(800L * (attempt + 1));
        }
        throw new IOException("Jikan temporarily unavailable");
    }

    private record Response(int statusCode, String body) { }

    private void throttle() throws InterruptedException {
        long wait = 450 - (System.currentTimeMillis() - lastRequest);
        if (wait > 0) Thread.sleep(wait);
    }

    private JsonNode data(JsonNode root) throws IOException {
        JsonNode value = root.path("data");
        if (value.isMissingNode() || value.isNull()) throw new IOException("Missing Jikan data");
        return value;
    }

    private static int localId(int malId) { return ID_OFFSET + malId; }
    private static int duration(String value) {
        Matcher match = MINUTES.matcher(value); return match.find() ? Integer.parseInt(match.group(1)) : 0;
    }
    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = node.path(field).asText(""); if (!value.isBlank()) return value;
        }
        return "";
    }
    private static String format(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "MOVIE" -> "Film"; case "SPECIAL" -> "Special"; case "OVA" -> "OVA";
            case "ONA" -> "ONA"; case "MUSIC" -> "Music"; case "TV", "TV SPECIAL" -> "TV";
            default -> value.isBlank() ? "N/D" : value;
        };
    }
    private static String status(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "finished airing" -> "Concluso"; case "currently airing" -> "In corso";
            case "not yet aired" -> "Non ancora uscito"; default -> value.isBlank() ? "N/D" : value;
        };
    }
    private static String season(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "winter" -> "Inverno"; case "spring" -> "Primavera";
            case "summer" -> "Estate"; case "fall" -> "Autunno"; default -> "N/D";
        };
    }

    @Override public void close() { }
}
