package com.myanimedesk;

final class Texts {
    private Texts() { }
    static String genre(String raw, boolean english) {
        if (english || raw == null) return raw;
        return switch (raw) {
            case "Action" -> "Azione"; case "Adventure" -> "Avventura";
            case "Comedy" -> "Commedia"; case "Drama" -> "Drammatico";
            case "Music" -> "Musicale"; case "Mystery" -> "Mistero";
            case "Psychological" -> "Psicologico"; case "Romance" -> "Romantico";
            case "Sci-Fi" -> "Fantascienza"; case "Sports" -> "Sport";
            case "Supernatural" -> "Soprannaturale"; default -> raw;
        };
    }
    static String metadata(String raw, boolean english) {
        if (raw == null || raw.isBlank() || raw.equals("N/D")) return "—";
        if (!english) return raw;
        return switch (raw) {
            case "Film" -> "Movie"; case "In corso" -> "Airing"; case "Concluso" -> "Finished";
            case "Non ancora uscito" -> "Not yet released"; case "Cancellato" -> "Cancelled";
            case "In pausa" -> "On hiatus"; case "Inverno" -> "Winter"; case "Primavera" -> "Spring";
            case "Estate" -> "Summer"; case "Autunno" -> "Fall"; default -> raw;
        };
    }
    static String relation(String raw, boolean english) {
        return switch (raw == null ? "" : raw) {
            case "PREQUEL" -> "Prequel"; case "SEQUEL" -> "Sequel";
            case "SIDE_STORY" -> english ? "Side story" : "Storia secondaria";
            case "PARENT" -> english ? "Parent story" : "Serie principale";
            case "ALTERNATIVE" -> english ? "Alternative version" : "Versione alternativa";
            case "CHARACTER" -> english ? "Shared characters" : "Personaggi in comune";
            case "SUMMARY" -> english ? "Recap" : "Riepilogo";
            case "SPIN_OFF" -> "Spin-off"; case "ADAPTATION" -> english ? "Adaptation" : "Adattamento";
            case "COMPILATION" -> "Compilation";
            default -> english ? "Related" : "Collegato";
        };
    }
    static String synopsis(String value) {
        if (value == null) return "";
        return value.replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("<[^>]+>", "")
            .replace("&amp;", "&").replace("&quot;", "\"").replace("&#039;", "'")
            .replace("&#39;", "'").replace("&apos;", "'").replace("&lt;", "<")
            .replace("&gt;", ">").replace("&nbsp;", " ").strip();
    }
}
