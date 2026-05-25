package com.myanimedesk;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Utility per scaricare e ridimensionare immagini in modo asincrono.
 *
 * Nota importante: Swing NON va aggiornato fuori dall'EDT.
 * Qui richiamiamo sempre la callback dentro SwingUtilities.invokeLater(...).
 */
public class ImageUtils {
    private static final Map<String, ImageIcon> CACHE = new ConcurrentHashMap<>();
    private static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(4);

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    public static ImageIcon getCached(String url) {
        if (url == null || url.isBlank()) return null;
        return CACHE.get(url);
    }

    public static ImageIcon placeholder(int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setPaint(new Color(40, 50, 80));
        g.fillRect(0, 0, w, h);
        g.setPaint(new Color(120, 140, 200));
        g.drawRect(0, 0, w - 1, h - 1);
        g.setFont(new Font("Segoe UI", Font.PLAIN, 12));
        g.drawString("Caricamento...", 8, Math.max(18, h / 2));
        g.dispose();
        return new ImageIcon(img);
    }

    /**
     * Scarica l'immagine e la mette in cache. Quando finisce, chiama callback con l'ImageIcon (o null se fallisce).
     */
    public static void fetchAsync(String url, int w, int h, Consumer<ImageIcon> callback) {
        if (url == null || url.isBlank()) {
            if (callback != null) SwingUtilities.invokeLater(() -> callback.accept(null));
            return;
        }
        ImageIcon already = CACHE.get(url);
        if (already != null) {
            if (callback != null) SwingUtilities.invokeLater(() -> callback.accept(already));
            return;
        }

        EXECUTOR.submit(() -> {
            ImageIcon icon = null;
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(10))
                        .header("User-Agent", "MyAnimeDesk")
                        .GET()
                        .build();

                HttpResponse<InputStream> resp = HTTP.send(req, HttpResponse.BodyHandlers.ofInputStream());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    try (InputStream in = resp.body()) {
                        BufferedImage img = ImageIO.read(in);
                        if (img != null) {
                            Image scaled = scaleHighQuality(img, w, h);
                            icon = new ImageIcon(scaled);
                            CACHE.put(url, icon);
                        }
                    }
                }
            } catch (Exception ignored) {
            }

            if (callback != null) {
                ImageIcon finalIcon = icon;
                SwingUtilities.invokeLater(() -> callback.accept(finalIcon));
            }
        });
    }

    private static Image scaleHighQuality(BufferedImage img, int w, int h) {
        
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = out.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.drawImage(img, 0, 0, w, h, null);
        g2.dispose();
        return out;
    }
}
