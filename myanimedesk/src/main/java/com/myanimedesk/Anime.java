package com.myanimedesk;

import java.util.List;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class Anime {
    public int id;
    /** Optional provider ID used when AniList is temporarily unavailable. */
    public int malId;
    public int kitsuId;
    public String provider = "ANILIST";
    public String title;
    public String coverImage;
    public int episodes;
    public int duration;
    public List<String> genres;
    public Status status = Status.TO_WATCH;
    public boolean favorite = false;
    // Complete viewings, not episodes. Old WATCHED entries migrate to one viewing.
    public int completedViews;
    public Set<String> folderIds = new LinkedHashSet<>();
    public Map<String, Integer> ratings = new LinkedHashMap<>();
    public String bannerImage, description, trailerId, trailerSite, siteUrl;
    public int averageScore;
    public boolean detailsLoaded;
    public List<CastMember> cast = new ArrayList<>();
    public boolean castPartial;
    public List<Relation> relations = new ArrayList<>();
    public boolean hasMoreCast;
    public int castPage = 1;

    public static class CastMember {
        public int id;
        public String name, image, role, voiceActor, voiceImage;
    }

    public static class Relation {
        public String type;
        public Anime anime;
    }
    
    // Nuovi campi reali richiesti per le info estese
    public String format;        // es. TV, MOVIE, OVA
    public String airingStatus;  // es. In Corso, Concluso
    public String year;          // es. 2026
    public String season;        // es. Primavera, Estate
    public String studio;        // es. BUG FILMS

    public enum Status { TO_WATCH, WATCHING, WATCHED, DROPPED }

    public String statusToString() {
        return switch (status) {
            case TO_WATCH -> "Da vedere";
            case WATCHING -> "In visione";
            case WATCHED -> "Visto"; // Corretto da "Visti" a "Visto"
            case DROPPED -> "Droppato";
        };
    }

    public static Status fromStringLocalized(String s) {
        if (s == null) return Status.TO_WATCH;
        return switch (s) {
            case "Da vedere" -> Status.TO_WATCH;
            case "In visione" -> Status.WATCHING;
            case "Visto", "Visti" -> Status.WATCHED; // Accetta entrambi per compatibilità con vecchi salvataggi
            case "Droppato" -> Status.DROPPED;
            default -> {
                try {
                    yield Status.valueOf(s);
                } catch (Exception e) { yield Status.TO_WATCH; }
            }
        };
    }

    public double totalHours() {
        if (episodes <= 0 || duration <= 0) return 0.0;
        return (episodes * (double) duration) / 60.0;
    }

    public int viewCount() {
        return Math.max(completedViews, status == Status.WATCHED ? 1 : 0);
    }

    public double watchedHours() { return totalHours() * viewCount(); }

    public boolean rated() { return ratings != null && !ratings.isEmpty(); }

    public double overall() {
        return rated() ? ratings.values().stream().mapToInt(Integer::intValue).average().orElse(0) : 0;
    }

    @Override
    public String toString() {
        return title != null ? title : "(unknown)";
    }
}
