package com.myanimedesk;

import java.io.*;
import java.nio.file.*;
import java.util.*;

final class Preferences {
    static final List<String> TAGS = List.of("Isekai", "Shounen", "Seinen", "Shoujo", "Josei", "Romantic Comedy", "School", "Super Power", "Martial Arts", "Historical", "Military", "Detective", "Vampire", "Survival", "Time Manipulation", "Space", "Idol");
    static final List<String> GENRES = java.util.stream.Stream.concat(java.util.stream.Stream.of("Action", "Adventure", "Comedy", "Drama", "Fantasy", "Horror", "Mahou Shoujo", "Mecha", "Music", "Mystery", "Psychological", "Romance", "Sci-Fi", "Slice of Life", "Sports", "Supernatural", "Thriller", "Ecchi", "Hentai"), TAGS.stream()).toList();
    final Properties values = new Properties();
    private final Path path;
    Preferences(Path path) { this.path = path; }
    void load() throws IOException {
        if (Files.exists(path)) try (InputStream input = Files.newInputStream(path)) { values.load(input); }
    }
    String language() { return "en".equals(values.getProperty("language")) ? "en" : "it"; }
    boolean onboarded() { return Boolean.parseBoolean(values.getProperty("onboarding.complete", "false")); }
    List<String> genres() {
        return Arrays.stream(values.getProperty("preferred.genres", "").split(","))
            .filter(GENRES::contains).distinct().toList();
    }
    void save() throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        values.store(output, "MyAnimeDesk preferences");
        AtomicFiles.write(path, output.toByteArray());
    }
}
