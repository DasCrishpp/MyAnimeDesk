package com.myanimedesk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

/** A single entry per AniList ID; folders contain references, never copies. */
public class AnimeListManager {
    public static final int MAX_VIEWS = 10000;
    private final ObjectMapper mapper = new ObjectMapper()
        .disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT)
        .disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    private final Path defaultPath;
    private LibraryData data = new LibraryData();

    public static class LibraryData {
        public int version = 1;
        public List<Anime> anime = new ArrayList<>();
        public Map<String, String> folders = new LinkedHashMap<>();
    }
    public AnimeListManager() {
        this(Path.of(System.getProperty("user.home"), ".myanimedesk", "library.json"));
    }
    public AnimeListManager(Path path) { defaultPath = path; }

    public void add(Anime anime) {
        Anime existing = findById(anime.id);
        if (existing != null && existing != anime) {
            anime.favorite = existing.favorite;
            anime.completedViews = existing.viewCount();
            anime.folderIds = new LinkedHashSet<>(existing.folderIds);
            anime.ratings = new LinkedHashMap<>(existing.ratings);
        }
        anime.completedViews = anime.viewCount();
        data.anime.removeIf(a -> a.id == anime.id);
        data.anime.add(anime);
    }
    public List<Anime> all() { return new ArrayList<>(data.anime); }
    public Anime findById(int id) { return data.anime.stream().filter(a -> a.id == id).findFirst().orElse(null); }
    public List<Anime> byStatus(Anime.Status status) { return data.anime.stream().filter(a -> a.status == status).collect(Collectors.toList()); }
    public List<Anime> favorites() { return data.anime.stream().filter(a -> a.favorite).collect(Collectors.toList()); }
    public List<Anime> ranking() {
        return data.anime.stream().filter(a -> a.rated() && a.viewCount() > 0)
            .sorted(Comparator.comparingDouble(Anime::overall).reversed()
                .thenComparing(a -> a.title, String.CASE_INSENSITIVE_ORDER).thenComparingInt(a -> a.id))
            .collect(Collectors.toList());
    }
    public double watchedHours() { return data.anime.stream().mapToDouble(Anime::watchedHours).sum(); }
    public boolean toggleFavorite(Anime anime) {
        Anime stored = findById(anime.id);
        if (stored == null) { add(anime); stored = anime; }
        stored.favorite = !stored.favorite;
        anime.favorite = stored.favorite;
        return stored.favorite;
    }
    public void updateStatus(int id, Anime.Status status) {
        Anime anime = findById(id);
        if (anime == null) return;
        anime.completedViews = anime.viewCount(); // Preserve completed viewings when starting a rewatch.
        anime.status = status;
        if (status == Anime.Status.WATCHED) anime.completedViews = Math.max(1, anime.completedViews);
    }
    public void setViewCount(int id, int count) {
        if (count < 0 || count > MAX_VIEWS) throw new IllegalArgumentException("Invalid view count");
        Anime anime = Objects.requireNonNull(findById(id));
        if (count == 0 && anime.rated()) throw new IllegalArgumentException("Remove the rating before clearing viewings");
        anime.completedViews = count;
        if (count > 0) anime.status = Anime.Status.WATCHED;
        else if (anime.status == Anime.Status.WATCHED) anime.status = Anime.Status.TO_WATCH;
    }
    public void setRating(int id, Map<String, Integer> scores) {
        RatingCategory.validate(scores);
        Anime anime = Objects.requireNonNull(findById(id));
        if (!scores.isEmpty() && anime.viewCount() == 0) throw new IllegalArgumentException("Watch first");
        anime.ratings = new LinkedHashMap<>(scores);
    }
    public void resetRanking() { data.anime.forEach(a -> a.ratings.clear()); }
    public void remove(int id) { data.anime.removeIf(a -> a.id == id); }
    public Map<String, String> folders() { return new LinkedHashMap<>(data.folders); }
    public String createFolder(String name) {
        String valid = folderName(name, null);
        String id = UUID.randomUUID().toString();
        data.folders.put(id, valid);
        return id;
    }
    public void renameFolder(String id, String name) {
        if (!data.folders.containsKey(id)) throw new IllegalArgumentException("Unknown folder");
        data.folders.put(id, folderName(name, id));
    }
    private String folderName(String name, String exceptId) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > 60 || trimmed.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid folder name");
        if (data.folders.entrySet().stream().anyMatch(e -> !e.getKey().equals(exceptId) && e.getValue().equalsIgnoreCase(trimmed)))
            throw new IllegalArgumentException("Duplicate folder name");
        return trimmed;
    }
    public void removeFolder(String id) {
        data.folders.remove(id);
        data.anime.forEach(a -> a.folderIds.remove(id));
    }
    public void setFolders(int animeId, Set<String> ids) {
        if (!data.folders.keySet().containsAll(ids)) throw new IllegalArgumentException("Unknown folder");
        Objects.requireNonNull(findById(animeId)).folderIds = new LinkedHashSet<>(ids);
    }

    public void saveToDefault() throws IOException {
        validate(data);
        // Keep the existing file recoverable when upgrading or editing.
        if (Files.exists(defaultPath)) {
            Path migrationBackup = defaultPath.resolveSibling("library.before-upgrade.json");
            if (!Files.exists(migrationBackup)) Files.copy(defaultPath, migrationBackup);
            Files.copy(defaultPath, defaultPath.resolveSibling("library.previous.json"), StandardCopyOption.REPLACE_EXISTING);
        }
        AtomicFiles.write(defaultPath, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
    }
    public void loadFromDefault() throws IOException {
        if (Files.exists(defaultPath)) data = read(defaultPath);
    }
    public LibraryData read(Path path) throws IOException {
        if (Files.size(path) > 32 * 1024 * 1024) throw new IOException("Backup too large");
        JsonNode root = mapper.readTree(path.toFile());
        LibraryData loaded;
        if (root != null && root.isArray()) {
            loaded = new LibraryData();
            loaded.anime = new ArrayList<>(Arrays.asList(mapper.treeToValue(root, Anime[].class)));
        } else if (root != null && root.isObject() && root.has("anime")) {
            loaded = mapper.treeToValue(root, LibraryData.class);
        } else throw new IOException("Invalid library backup");
        try { validate(loaded); } catch (RuntimeException e) { throw new IOException("Invalid library backup: " + e.getMessage(), e); }
        return loaded;
    }
    public void importLibrary(Path path) throws IOException {
        LibraryData candidate = read(path);
        LibraryData previous = data;
        data = candidate;
        try { saveToDefault(); } catch (IOException | RuntimeException e) { data = previous; throw e; }
    }
    public void exportLibrary(Path path) throws IOException {
        validate(data);
        AtomicFiles.write(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(data));
    }
    public void exportRanking(Path path) throws IOException {
        LibraryData export = new LibraryData();
        for (Anime anime : ranking()) {
            Anime copy = mapper.convertValue(anime, Anime.class);
            copy.folderIds.clear();
            export.anime.add(copy);
        }
        AtomicFiles.write(path, mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(export));
    }
    public void importRanking(Path path) throws IOException {
        LibraryData imported = read(path);
        for (Anime anime : imported.anime)
            if (!anime.rated() || anime.viewCount() == 0) throw new IOException("Every ranking entry must have valid scores and a completed viewing");
        LibraryData previous = data;
        LibraryData candidate = mapper.convertValue(data, LibraryData.class);
        for (Anime incoming : imported.anime) {
            Anime existing = candidate.anime.stream().filter(a -> a.id == incoming.id).findFirst().orElse(null);
            if (existing == null) {
                incoming.folderIds.clear();
                candidate.anime.add(incoming);
            } else {
                existing.ratings = new LinkedHashMap<>(incoming.ratings);
                if (existing.viewCount() == 0) {
                    existing.completedViews = 1;
                    existing.status = Anime.Status.WATCHED;
                }
            }
        }
        data = candidate;
        try { saveToDefault(); } catch (IOException | RuntimeException e) { data = previous; throw e; }
    }
    /** Mutations are committed to disk together or restored if writing fails. */
    public void transaction(Runnable mutation) throws IOException {
        LibraryData previous = mapper.convertValue(data, LibraryData.class);
        try { mutation.run(); saveToDefault(); }
        catch (IOException | RuntimeException e) { data = previous; throw e; }
    }
    static void validate(LibraryData loaded) {
        if (loaded == null || loaded.version != 1 || loaded.anime == null || loaded.folders == null)
            throw new IllegalArgumentException("Invalid library");
        Set<Integer> ids = new HashSet<>();
        Set<String> names = new HashSet<>();
        for (var entry : loaded.folders.entrySet()) {
            String name = entry.getValue();
            if (entry.getKey().isBlank() || name == null || name.isBlank() || name.length() > 60 ||
                name.chars().anyMatch(Character::isISOControl) || !names.add(name.trim().toLowerCase(Locale.ROOT)))
                throw new IllegalArgumentException("Invalid folders");
        }
        for (Anime a : loaded.anime) {
            if (a == null || a.id <= 0 || !ids.add(a.id)) throw new IllegalArgumentException("Duplicate or invalid anime ID");
            if (a.title == null || a.title.isBlank()) a.title = "Anime #" + a.id;
            if (a.provider == null || a.provider.isBlank()) a.provider = "ANILIST";
            if (a.status == null) a.status = Anime.Status.TO_WATCH;
            if (a.completedViews < 0 || a.completedViews > MAX_VIEWS || a.episodes < 0 || a.duration < 0)
                throw new IllegalArgumentException("Invalid anime data");
            a.completedViews = a.viewCount();
            if (a.folderIds == null) a.folderIds = new LinkedHashSet<>();
            if (!loaded.folders.keySet().containsAll(a.folderIds)) throw new IllegalArgumentException("Unknown folder reference");
            if (a.ratings == null) a.ratings = new LinkedHashMap<>();
            RatingCategory.validate(a.ratings);
            if (a.rated() && a.viewCount() == 0) throw new IllegalArgumentException("Unwatched rating");
            if (a.genres == null) a.genres = new ArrayList<>();
            if (a.cast == null) a.cast = new ArrayList<>();
            if (a.relations == null) a.relations = new ArrayList<>();
        }
    }
}
