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

/** Independent metadata source used when AniList is temporarily unavailable. */
final class KitsuClient implements AutoCloseable {
    private static final URI API = URI.create("https://kitsu.io/api/edge/");
    private static final int ID_OFFSET = 1_100_000_000;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path cacheDirectory;
    private final URI endpoint;

    KitsuClient(Path profile) { this(profile, API); }
    KitsuClient(Path profile, URI endpoint) { this.cacheDirectory = profile.resolve("kitsu_cache"); this.endpoint = endpoint; }

    Anime enrich(Anime seed) throws IOException, InterruptedException {
        JsonNode node;
        if (seed.kitsuId > 0) node = data(request("anime/" + seed.kitsuId));
        else {
            List<JsonNode> matches = searchNodes(seed.title, 20);
            node = bestMatch(seed.title, matches);
            if (node == null) throw new IOException("Anime not found on Kitsu");
        }
        int kitsuId = integer(node.path("id"));
        Anime anime = parseAnime(node, seed.id > 0 ? seed.id : localId(kitsuId), true);
        anime.kitsuId = kitsuId;
        copyMissing(seed, anime);
        if (anime.malId <= 0) anime.malId = mapping(kitsuId, "myanimelist/anime");
        if (anime.cast.isEmpty()) try { loadCharacters(kitsuId, anime); } catch (IOException ignored) { }
        return anime;
    }

    List<Anime> search(String query, int limit) throws IOException, InterruptedException {
        List<Anime> result = new ArrayList<>();
        for (JsonNode node : searchNodes(query, Math.min(20, Math.max(1, limit)))) {
            int kitsuId = integer(node.path("id"));
            result.add(parseAnime(node, localId(kitsuId), false));
        }
        return result;
    }

    List<Anime> browse(String mode, String filter, int page, int limit) throws IOException, InterruptedException {
        int safeLimit = Math.min(20, Math.max(1, limit));
        int offset = Math.max(0, page - 1) * safeLimit;
        String path;
        if ("TRENDING".equalsIgnoreCase(mode) && page <= 1) {
            path = "trending/anime?limit=" + safeLimit;
        } else {
            StringBuilder query = new StringBuilder("anime?page%5Blimit%5D=").append(safeLimit)
                .append("&page%5Boffset%5D=").append(offset);
            if ("RECENT".equalsIgnoreCase(mode)) query.append("&sort=-startDate");
            else query.append("&sort=-userCount");
            if (("GENRE".equalsIgnoreCase(mode) || "TAG".equalsIgnoreCase(mode)) && filter != null && !filter.isBlank())
                query.append("&filter%5Bcategories%5D=").append(encoded(category(filter)));
            path = query.toString();
        }
        JsonNode entries = request(path).path("data");
        List<Anime> result = new ArrayList<>();
        if (entries.isArray()) for (JsonNode node : entries) {
            int kitsuId = integer(node.path("id"));
            result.add(parseAnime(node, localId(kitsuId), false));
        }
        return result;
    }

    private List<JsonNode> searchNodes(String title, int limit) throws IOException, InterruptedException {
        String path = "anime?filter%5Btext%5D=" + encoded(title == null ? "" : title.strip()) + "&page%5Blimit%5D=" + limit;
        JsonNode entries = request(path).path("data");
        List<JsonNode> result = new ArrayList<>();
        if (entries.isArray()) entries.forEach(result::add);
        return result;
    }

    private JsonNode bestMatch(String requested, List<JsonNode> nodes) {
        String wanted = normalized(requested);
        return nodes.stream().min(Comparator.comparingInt(node -> {
            JsonNode attributes = node.path("attributes");
            int best = titleScore(wanted, attributes.path("canonicalTitle").asText(""));
            for (JsonNode title : attributes.path("titles")) best = Math.min(best, titleScore(wanted, title.asText("")));
            return best;
        })).orElse(null);
    }

    private int mapping(int kitsuId, String site) throws IOException, InterruptedException {
        JsonNode mappings = request("anime/" + kitsuId + "/mappings").path("data");
        if (!mappings.isArray()) return 0;
        for (JsonNode mapping : mappings) if (site.equalsIgnoreCase(mapping.path("attributes").path("externalSite").asText()))
            return integer(mapping.path("attributes").path("externalId"));
        return 0;
    }

    private void loadCharacters(int kitsuId, Anime anime) throws IOException, InterruptedException {
        JsonNode root = request("anime/" + kitsuId + "/characters?page%5Blimit%5D=20&include=character");
        JsonNode included = root.path("included");
        if (!included.isArray()) return;
        for (JsonNode character : included) {
            if (!"characters".equals(character.path("type").asText())) continue;
            JsonNode attributes = character.path("attributes");
            Anime.CastMember member = new Anime.CastMember();
            member.id = integer(character.path("id"));
            member.name = text(attributes, "canonicalName", "name", "names.en", "names.ja_jp");
            member.image = text(attributes, "image.original", "image.medium", "image.small");
            member.role = "SUPPORTING";
            anime.cast.add(member);
        }
        JsonNode entries = root.path("data");
        if (entries.isArray()) for (JsonNode entry : entries) {
            int characterId = integer(entry.path("relationships").path("character").path("data").path("id"));
            if (!"main".equalsIgnoreCase(entry.path("attributes").path("role").asText())) continue;
            anime.cast.stream().filter(member -> member.id == characterId).findFirst().ifPresent(member -> member.role = "MAIN");
        }
        anime.castPartial = !anime.cast.isEmpty();
    }

    private Anime parseAnime(JsonNode node, int appId, boolean detailed) {
        JsonNode attributes = node.path("attributes");
        Anime anime = new Anime();
        anime.id = appId; anime.kitsuId = integer(node.path("id")); anime.provider = "KITSU";
        anime.title = text(attributes, "canonicalTitle", "titles.en", "titles.en_jp", "titles.ja_jp");
        if (anime.title.isBlank()) anime.title = "Anime #" + anime.kitsuId;
        anime.coverImage = text(attributes, "posterImage.original", "posterImage.large", "posterImage.medium");
        anime.bannerImage = text(attributes, "coverImage.original", "coverImage.large", "coverImage.small");
        if (anime.bannerImage.isBlank()) anime.bannerImage = anime.coverImage;
        anime.description = attributes.path("synopsis").asText("");
        anime.trailerId = attributes.path("youtubeVideoId").asText("");
        anime.trailerSite = anime.trailerId.isBlank() ? "" : "youtube";
        String slug = attributes.path("slug").asText("");
        anime.siteUrl = slug.isBlank() ? "https://kitsu.app/anime/" + anime.kitsuId : "https://kitsu.app/anime/" + slug;
        anime.averageScore = decimal(attributes.path("averageRating").asText("0"));
        anime.episodes = attributes.path("episodeCount").asInt(0);
        anime.duration = attributes.path("episodeLength").asInt(0);
        anime.genres = new ArrayList<>();
        anime.format = format(attributes.path("subtype").asText(""));
        anime.airingStatus = status(attributes.path("status").asText(""));
        String start = attributes.path("startDate").asText("");
        anime.year = start.length() >= 4 ? start.substring(0, 4) : "N/D";
        anime.season = season(start); anime.studio = "N/D"; anime.detailsLoaded = detailed;
        return anime;
    }

    private void copyMissing(Anime seed, Anime target) {
        if (target.genres.isEmpty() && seed.genres != null) target.genres = new ArrayList<>(seed.genres);
        if ("N/D".equals(target.studio) && seed.studio != null) target.studio = seed.studio;
        if ((target.format == null || "N/D".equals(target.format)) && seed.format != null) target.format = seed.format;
        if (target.episodes <= 0) target.episodes = seed.episodes;
        if (target.duration <= 0) target.duration = seed.duration;
        target.malId = seed.malId;
        if (seed.relations != null) target.relations = new ArrayList<>(seed.relations);
    }

    private JsonNode request(String path) throws IOException, InterruptedException {
        String key;
        try { key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(path.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IOException(e); }
        Path saved = cacheDirectory.resolve(key + ".json");
        try {
            String body = fetch(path);
            JsonNode root = mapper.readTree(body);
            if (!root.has("data")) throw new IOException("Invalid Kitsu response");
            try { AtomicFiles.write(saved, body.getBytes(StandardCharsets.UTF_8)); } catch (IOException ignored) { }
            return root;
        } catch (IOException failure) {
            if (Files.exists(saved) && Files.size(saved) <= 12 * 1024 * 1024) return mapper.readTree(saved.toFile());
            throw failure;
        }
    }

    private String fetch(String path) throws IOException, InterruptedException {
        for (int attempt = 0; attempt < 3; attempt++) {
            HttpURLConnection connection = (HttpURLConnection) endpoint.resolve(path).toURL().openConnection();
            connection.setConnectTimeout(12_000); connection.setReadTimeout(20_000);
            connection.setRequestProperty("Accept", "application/vnd.api+json");
            connection.setRequestProperty("User-Agent", "MyAnimeDesk/0.4.0");
            connection.setRequestProperty("Connection", "close");
            int status; String body;
            try {
                status = connection.getResponseCode();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                if (stream != null) stream.close();
            } finally { connection.disconnect(); }
            if (status == 200) return body;
            if ((status != 429 && status < 500) || attempt == 2) throw new IOException("Kitsu HTTP " + status);
            Thread.sleep(600L * (attempt + 1));
        }
        throw new IOException("Kitsu temporarily unavailable");
    }

    private JsonNode data(JsonNode root) throws IOException {
        JsonNode value = root.path("data");
        if (value.isMissingNode() || value.isNull()) throw new IOException("Missing Kitsu data");
        return value;
    }
    private static int localId(int kitsuId) { return ID_OFFSET + kitsuId; }
    private static String encoded(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static int integer(JsonNode value) { try { return Integer.parseInt(value.asText("0")); } catch (NumberFormatException e) { return 0; } }
    private static int decimal(String value) { try { return (int) Math.round(Double.parseDouble(value)); } catch (NumberFormatException e) { return 0; } }
    private static String normalized(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD).replaceAll("\\p{M}", "")
            .replaceAll("[^a-zA-Z0-9]", "").toLowerCase(Locale.ROOT);
    }
    private static int titleScore(String wanted, String title) {
        String candidate = normalized(title);
        if (candidate.equals(wanted)) return 0;
        if (candidate.contains(wanted) || wanted.contains(candidate)) return 10 + Math.abs(candidate.length() - wanted.length());
        return 100 + Math.abs(candidate.length() - wanted.length());
    }
    private static String text(JsonNode root, String... paths) {
        for (String path : paths) {
            JsonNode value = root;
            for (String part : path.split("\\.")) value = value.path(part);
            String text = value.asText(""); if (!text.isBlank()) return text;
        }
        return "";
    }
    private static String category(String value) { return value.toLowerCase(Locale.ROOT).replace('ō', 'o').replaceAll("[^a-z0-9]+", "-"); }
    private static String format(String value) {
        return switch (value.toUpperCase(Locale.ROOT)) {
            case "MOVIE" -> "Film"; case "SPECIAL" -> "Special"; case "OVA" -> "OVA";
            case "ONA" -> "ONA"; case "MUSIC" -> "Music"; case "TV" -> "TV"; default -> "N/D";
        };
    }
    private static String status(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "current" -> "In corso"; case "finished" -> "Concluso"; case "tba", "unreleased", "upcoming" -> "Non ancora uscito";
            default -> "N/D";
        };
    }
    private static String season(String date) {
        if (date.length() < 7) return "N/D";
        try {
            int month = Integer.parseInt(date.substring(5, 7));
            if (month <= 2 || month == 12) return "Inverno";
            if (month <= 5) return "Primavera"; if (month <= 8) return "Estate"; return "Autunno";
        } catch (NumberFormatException e) { return "N/D"; }
    }
    @Override public void close() { }
}
