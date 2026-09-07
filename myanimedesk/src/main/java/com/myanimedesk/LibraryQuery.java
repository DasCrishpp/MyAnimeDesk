package com.myanimedesk;

import java.util.*;

/** Pure library selection: every filter composes with folders, genres and search. */
final class LibraryQuery {
    enum Filter { ALL, WATCHING, WATCHED, TO_WATCH, DROPPED, FAVORITES, REWATCHED }
    enum Order { RECENT, OLDEST, EPISODES_DESC, EPISODES_ASC, AZ, ZA, VIEWS_DESC, VIEWS_ASC }
    static boolean matches(Anime a, Filter filter) {
        return switch (filter) {
            case ALL -> true;
            case WATCHING -> a.status == Anime.Status.WATCHING;
            case WATCHED -> a.status == Anime.Status.WATCHED;
            case TO_WATCH -> a.status == Anime.Status.TO_WATCH;
            case DROPPED -> a.status == Anime.Status.DROPPED;
            case FAVORITES -> a.favorite;
            case REWATCHED -> a.viewCount() > 1;
        };
    }
    static List<Anime> select(List<Anime> source, Filter filter, Order order, String genre, String folder, String search) {
        String query = search == null ? "" : search.strip().toLowerCase(Locale.ROOT);
        Comparator<Anime> title = Comparator.comparing(a -> a.title, String.CASE_INSENSITIVE_ORDER);
        Comparator<Anime> comparator = switch (order) {
            case AZ -> title;
            case ZA -> title.reversed();
            case RECENT -> Comparator.comparingInt((Anime a) -> year(a, Integer.MIN_VALUE)).reversed();
            case OLDEST -> Comparator.comparingInt(a -> year(a, Integer.MAX_VALUE));
            case EPISODES_DESC -> Comparator.comparingInt((Anime a) -> a.episodes).reversed();
            case EPISODES_ASC -> Comparator.comparingInt(a -> a.episodes > 0 ? a.episodes : Integer.MAX_VALUE);
            case VIEWS_DESC -> Comparator.comparingInt(Anime::viewCount).reversed();
            case VIEWS_ASC -> Comparator.comparingInt(Anime::viewCount);
        };
        return source.stream().filter(a -> matches(a, filter))
            .filter(a -> genre == null || (a.genres != null && a.genres.contains(genre)))
            .filter(a -> folder == null || a.folderIds.contains(folder))
            .filter(a -> a.title.toLowerCase(Locale.ROOT).contains(query))
            .sorted(comparator.thenComparing(title).thenComparingInt(a -> a.id)).toList();
    }
    private static int year(Anime a, int fallback) {
        try { return Integer.parseInt(a.year); } catch (Exception e) { return fallback; }
    }
}
