package com.myanimedesk;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Reads public publisher feeds and keeps the latest successful headlines for each language. */
final class AnimeNews implements AutoCloseable {
    private static final Duration CACHE_TTL = Duration.ofMinutes(20);
    private static final Duration RETRY_DELAY = Duration.ofMinutes(1);
    private static final int REQUEST_TIMEOUT_MS = 3500;
    private static final int MAX_FEED_BYTES = 512 * 1024;
    private static final int ARTICLE_LIMIT = 8;
    private static final Edition ITALIAN = new Edition(
        URI.create("https://www.animeclick.it/rss"), "AnimeClick",
        Set.of("www.animeclick.it", "animeclick.it"), false);
    private static final Edition ENGLISH = new Edition(
        URI.create("https://www.animenewsnetwork.com/news/rss.xml?ann-edition=us"), "Anime News Network",
        Set.of("www.animenewsnetwork.com", "animenewsnetwork.com"), false);

    enum Topic { ANIME, MANGA, BOTH }

    record Article(String title, URI url, Instant published, String source, Topic topic) {}
    private record Edition(URI feed, String name, Set<String> articleHosts, boolean generalNews) {}
    private record Cached(List<Article> articles, Instant retryAfter, boolean failed) {}

    @FunctionalInterface
    interface Fetcher {
        List<Article> fetch(boolean english) throws IOException;
    }

    private final Fetcher fetcher;
    private final ExecutorService workers = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<Boolean, Cached> cache = new HashMap<>();
    private final Map<Boolean, CompletableFuture<List<Article>>> pending = new HashMap<>();
    private boolean closed;

    AnimeNews() { this(AnimeNews::fetch); }

    AnimeNews(Fetcher fetcher) { this.fetcher = fetcher; }

    synchronized List<Article> cached(boolean english) {
        Cached result = cache.get(english);
        return result == null ? List.of() : result.articles();
    }

    synchronized CompletableFuture<List<Article>> load(boolean english, boolean refresh) {
        if (closed) return CompletableFuture.failedFuture(new CancellationException());
        CompletableFuture<List<Article>> active = pending.get(english);
        if (active != null) return active;
        Cached previous = cache.get(english);
        if (!refresh && previous != null && Instant.now().isBefore(previous.retryAfter())) {
            return previous.failed() ? CompletableFuture.failedFuture(new IOException("News feed unavailable"))
                : CompletableFuture.completedFuture(previous.articles());
        }

        CompletableFuture<List<Article>> result = new CompletableFuture<>();
        pending.put(english, result);
        workers.execute(() -> {
            try {
                List<Article> articles = fetcher.fetch(english).stream()
                    .sorted(Comparator.comparing(Article::published).reversed()).limit(ARTICLE_LIMIT).toList();
                if (articles.isEmpty()) throw new IOException("The news feed was empty");
                finish(english, result, articles, null);
            } catch (IOException | RuntimeException error) {
                finish(english, result, List.of(), error);
            }
        });
        return result;
    }

    private synchronized void finish(boolean english, CompletableFuture<List<Article>> future,
                                     List<Article> articles, Exception error) {
        if (closed) return;
        Instant now = Instant.now();
        if (error == null)
            cache.put(english, new Cached(articles, now.plus(CACHE_TTL), false));
        else
            cache.put(english, new Cached(cached(english), now.plus(RETRY_DELAY), true));
        pending.remove(english);
        if (error == null) future.complete(articles);
        else future.completeExceptionally(error);
    }

    private static List<Article> fetch(boolean english) throws IOException {
        Edition primary = english ? ENGLISH : ITALIAN;
        try {
            List<Article> articles = read(primary);
            if (!articles.isEmpty()) return articles;
        } catch (IOException error) {
            if (Thread.currentThread().isInterrupted()) throw error;
        }
        String locale = english ? "en-US" : "it-IT";
        return read(new Edition(
            URI.create("https://cr-news-api-service.prd.crunchyrollsvc.com/v1/" + locale + "/rss"),
            "Crunchyroll News", Set.of("crunchyroll.com", "www.crunchyroll.com"), true));
    }

    private static List<Article> read(Edition edition) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) edition.feed().toURL().openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(REQUEST_TIMEOUT_MS);
        connection.setReadTimeout(REQUEST_TIMEOUT_MS);
        connection.setRequestProperty("User-Agent", "MyAnimeDesk/0.4 (RSS reader)");
        connection.setRequestProperty("Accept", "application/rss+xml, application/xml, text/xml;q=0.9");
        try {
            if (connection.getResponseCode() != HttpURLConnection.HTTP_OK
                    || connection.getContentLengthLong() > MAX_FEED_BYTES) {
                throw new IOException("Invalid news feed response");
            }
            try (InputStream input = connection.getInputStream()) {
                byte[] document = input.readNBytes(MAX_FEED_BYTES + 1);
                if (document.length > MAX_FEED_BYTES) throw new IOException("News feed is too large");
                return parse(document, edition);
            }
        } finally {
            connection.disconnect();
        }
    }

    private static List<Article> parse(byte[] document, Edition edition) throws IOException {
        NodeList items;
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler());
            items = builder.parse(new ByteArrayInputStream(document)).getElementsByTagName("item");
        } catch (ParserConfigurationException | SAXException error) {
            throw new IOException("Invalid news feed", error);
        }

        Map<URI, Article> articles = new LinkedHashMap<>();
        Instant earliest = Instant.now().minus(Duration.ofDays(30));
        for (int i = 0; i < items.getLength(); i++) {
            Element item = (Element) items.item(i);
            String title = field(item, "title").replaceAll("\\s+", " ");
            Topic topic = topic(item, title, edition.generalNews());
            if (title.isEmpty() || title.length() > 350 || topic == null) continue;
            try {
                URI url = URI.create(field(item, "link"));
                Instant date = ZonedDateTime.parse(field(item, "pubDate"), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant();
                if (articleLink(url, edition) && !date.isBefore(earliest))
                    articles.putIfAbsent(url, new Article(title, url, date, edition.name(), topic));
            } catch (IllegalArgumentException | DateTimeParseException ignored) {
                // Skip entries with an invalid publication date or URL.
            }
        }
        return List.copyOf(articles.values());
    }

    private static boolean articleLink(URI url, Edition edition) {
        String host = url.getHost();
        return "https".equalsIgnoreCase(url.getScheme()) && host != null
            && edition.articleHosts().contains(host.toLowerCase(Locale.ROOT))
            && url.getUserInfo() == null && url.getPort() == -1
            && (url.getPath().startsWith("/news/") || url.getPath().startsWith("/it/news/"));
    }

    private static Topic topic(Element item, String title, boolean generalNews) {
        boolean anime = false;
        boolean manga = false;
        NodeList tags = item.getElementsByTagName("category");
        for (int i = 0; i < tags.getLength(); i++) {
            String category = tags.item(i).getTextContent().strip().toLowerCase(Locale.ROOT);
            anime |= category.equals("anime");
            manga |= category.equals("manga");
            if (generalNews && (category.equals("latest news") || category.equals("ultime notizie sugli anime"))) {
                manga |= title.toLowerCase(Locale.ROOT).contains("manga");
                anime |= !manga;
            }
        }
        if (anime && manga) return Topic.BOTH;
        if (manga) return Topic.MANGA;
        return anime ? Topic.ANIME : null;
    }

    private static String field(Element item, String name) {
        NodeList fields = item.getElementsByTagName(name);
        return fields.getLength() == 0 ? "" : fields.item(0).getTextContent().strip();
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        workers.shutdownNow();
        List.copyOf(pending.values()).forEach(future -> future.cancel(false));
        pending.clear();
        cache.clear();
    }
}
