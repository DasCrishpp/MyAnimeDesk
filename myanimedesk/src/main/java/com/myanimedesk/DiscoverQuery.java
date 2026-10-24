package com.myanimedesk;

/** Search options supported by the AniList catalogue. */
public record DiscoverQuery(String text, String genre, int year, Sort sort, Status status, Format format, Season season) {
    public enum Sort { POPULARITY, TITLE_ASC, TITLE_DESC, RATING }
    public enum Status { ANY, RELEASING, FINISHED, NOT_YET_RELEASED, CANCELLED, HIATUS }
    public enum Format { ANY, TV, TV_SHORT, OVA, ONA, SPECIAL, MOVIE, MUSIC }
    public enum Season { ANY, WINTER, SPRING, SUMMER, FALL }

    public DiscoverQuery {
        text = text == null ? "" : text.strip();
        genre = genre == null ? "" : genre;
        sort = sort == null ? Sort.POPULARITY : sort;
        status = status == null ? Status.ANY : status;
        format = format == null ? Format.ANY : format;
        season = season == null ? Season.ANY : season;
    }

    public static DiscoverQuery empty() {
        return new DiscoverQuery("", "", 0, Sort.POPULARITY, Status.ANY, Format.ANY, Season.ANY);
    }

    public DiscoverQuery withText(String value) {
        return new DiscoverQuery(value, genre, year, sort, status, format, season);
    }

    public boolean hasFilters() {
        return !genre.isBlank() || year > 0 || sort != Sort.POPULARITY || status != Status.ANY
            || format != Format.ANY || season != Season.ANY;
    }
}
