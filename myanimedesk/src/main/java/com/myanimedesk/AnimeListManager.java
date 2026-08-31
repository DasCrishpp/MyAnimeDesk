package com.myanimedesk;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

public class AnimeListManager {
    private final List<Anime> library = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();
    private final Path defaultPath = Paths.get(System.getProperty("user.home"), ".myanimedesk", "library.json");

    public void add(Anime a) {
        library.removeIf(x -> x.id == a.id);
        library.add(a);
    }

    public List<Anime> all() { return new ArrayList<>(library); }

    public List<Anime> byStatus(Anime.Status s) {
        return library.stream().filter(x -> x.status == s).collect(Collectors.toList());
    }

    public Anime findById(int animeId) {
        return library.stream().filter(x -> x.id == animeId).findFirst().orElse(null);
    }

    public List<Anime> favorites() {
        return library.stream().filter(x -> x.favorite).collect(Collectors.toList());
    }

    public boolean toggleFavorite(Anime anime) {
        Anime stored = findById(anime.id);
        if (stored == null) {
            anime.favorite = true;
            add(anime);
            return true;
        }
        stored.favorite = !stored.favorite;
        anime.favorite = stored.favorite;
        return stored.favorite;
    }

    public void updateStatus(int animeId, Anime.Status s) {
        for (Anime a : library) if (a.id == animeId) a.status = s;
    }

    public void remove(int animeId) {
        library.removeIf(x -> x.id == animeId);
    }

    public void saveToDefault() throws IOException {
        Path dir = defaultPath.getParent();
        if (!Files.exists(dir)) Files.createDirectories(dir);
        mapper.writerWithDefaultPrettyPrinter().writeValue(defaultPath.toFile(), library);
    }

    public void loadFromDefault() throws IOException {
        if (!Files.exists(defaultPath)) return;
        List<Anime> loaded = mapper.readValue(defaultPath.toFile(), new TypeReference<List<Anime>>(){});
        library.clear();
        if (loaded != null) library.addAll(loaded);
    }
}
