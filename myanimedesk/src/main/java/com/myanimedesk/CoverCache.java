package com.myanimedesk;

import javafx.application.Platform;
import javafx.scene.image.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.Duration;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Bounded decoded thumbnail cache. Full-size originals are kept on disk only. */
final class CoverCache implements AutoCloseable {
    private final Path directory;
    private final ExecutorService workers = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "myanimedesk-covers"); thread.setDaemon(true); return thread;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private final Map<String, Image> memory = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Image> entry) { return size() > 96; }
    };
    private final Map<String, List<Consumer<Image>>> waiting = new HashMap<>();
    private volatile boolean closed;
    CoverCache(Path directory) { this.directory = directory; }

    void load(String url, int width, int height, Consumer<Image> consumer) {
        if (closed || url == null || url.isBlank()) return;
        String key = url + "|" + width + "x" + height;
        Image cached = memory.get(key);
        if (cached != null) { consumer.accept(cached); return; }
        if (waiting.containsKey(key)) { waiting.get(key).add(consumer); return; }
        waiting.put(key, new ArrayList<>(List.of(consumer)));
        workers.submit(() -> {
            Image image = null;
            try {
                String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(url.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
                Path file = directory.resolve(hash + ".img");
                byte[] bytes;
                if (Files.exists(file)) bytes = Files.readAllBytes(file);
                else {
                    URI uri = URI.create(url);
                    if (!Set.of("https", "http").contains(uri.getScheme())) throw new IOException("Invalid image URL");
                    HttpResponse<InputStream> response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
                    try (InputStream body = response.body()) {
                        if (response.statusCode() != 200) throw new IOException("Image unavailable");
                        bytes = body.readNBytes(8 * 1024 * 1024 + 1);
                        if (bytes.length > 8 * 1024 * 1024) throw new IOException("Image too large");
                    }
                    if (!closed) AtomicFiles.write(file, bytes);
                }
                image = new Image(new ByteArrayInputStream(bytes), width, height, true, true);
                if (image.isError()) image = null;
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception ignored) { /* Keep the visible placeholder; no retained failed callbacks. */ }
            Image result = image;
            if (!closed) Platform.runLater(() -> {
                List<Consumer<Image>> consumers = waiting.remove(key);
                if (closed) return;
                if (result != null) {
                    memory.put(key, result);
                    if (consumers != null) consumers.forEach(c -> c.accept(result));
                }
            });
        });
    }
    void forgetPendingViews() { waiting.values().forEach(List::clear); }
    @Override public void close() {
        closed = true;
        workers.shutdownNow();
        http.shutdownNow();
        waiting.clear(); memory.clear();
    }
}
