package com.myanimedesk;

import java.util.List;

public class Anime {
    public int id;
    public String title;
    public String coverImage;
    public int episodes;
    public int duration;
    public List<String> genres;
    public Status status = Status.TO_WATCH;
    
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

    @Override
    public String toString() {
        return title != null ? title : "(unknown)";
    }
}