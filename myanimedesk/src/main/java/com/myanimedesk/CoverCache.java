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
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;
    private final Path directory;
    private final ExecutorService workers = Executors.newFixedThreadPool(5, r -> {
        Thread thread = new Thread(r, "myanimedesk-covers"); thread.setDaemon(true); return thread;
    });
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL).build();
    private final Map<String, Image> memory = new LinkedHashMap<>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Image> entry) { return size() > 96; }
    };
    private final Map<String, List<Consumer<Image>>> waiting = new HashMap<>();
    private volatile boolean closed;
    CoverCache(Path directory) { this.directory = directory; }

    void load(String url, int width, int height, Consumer<Image> consumer) {
        loadOrNull(url, width, height, image -> { if (image != null) consumer.accept(image); });
    }

    void loadOrNull(String url, int width, int height, Consumer<Image> consumer) {
        if (closed) return;
        if (url == null || url.isBlank()) { consumer.accept(null); return; }
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
                try {
                    if (Files.isRegularFile(file) && Files.size(file) <= MAX_IMAGE_BYTES)
                        image = decode(Files.readAllBytes(file), width, height);
                } catch (IOException ignored) { /* Refresh unreadable cache files from the source. */ }
                if (image == null) {
                    byte[] bytes = download(url);
                    image = decode(bytes, width, height);
                    if (!closed && image != null) {
                        try { AtomicFiles.write(file, bytes); }
                        catch (IOException ignored) { /* The decoded image is usable without a disk cache. */ }
                    }
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception ignored) { /* Keep the visible placeholder; no retained failed callbacks. */ }
            Image result = image;
            if (!closed) Platform.runLater(() -> {
                List<Consumer<Image>> consumers = waiting.remove(key);
                if (closed) return;
                if (result != null) memory.put(key, result);
                if (consumers != null) consumers.forEach(c -> c.accept(result));
            });
        });
    }

    private byte[] download(String url) throws IOException, InterruptedException {
        URI uri = URI.create(url);
        if (!Set.of("https", "http").contains(uri.getScheme())) throw new IOException("Invalid image URL");
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(12)).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream body = response.body()) {
            if (response.statusCode() != 200) throw new IOException("Image unavailable");
            byte[] bytes = body.readNBytes(MAX_IMAGE_BYTES + 1);
            if (bytes.length > MAX_IMAGE_BYTES) throw new IOException("Image too large");
            return bytes;
        }
    }

    private Image decode(byte[] bytes, int width, int height) {
        Image image = new Image(new ByteArrayInputStream(bytes), width, height, true, true);
        return image.isError() ? null : image;
    }
    void forgetPendingViews() { waiting.values().forEach(List::clear); }
    @Override public void close() {
        closed = true;
        workers.shutdownNow();
        http.shutdownNow();
        waiting.clear(); memory.clear();
    }
}
