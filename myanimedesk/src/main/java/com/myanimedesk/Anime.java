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

    public enum Status { TO_WATCH, WATCHING, WATCHED, DROPPED }

    public String statusToString() {
        return switch (status) {
            case TO_WATCH -> "Da vedere";
            case WATCHING -> "In visione";
            case WATCHED -> "Visti";
            case DROPPED -> "Droppato";
        };
    }

    public static Status fromStringLocalized(String s) {
        if (s == null) return Status.TO_WATCH;
        return switch (s) {
            case "Da vedere" -> Status.TO_WATCH;
            case "In visione" -> Status.WATCHING;
            case "Visti" -> Status.WATCHED;
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
