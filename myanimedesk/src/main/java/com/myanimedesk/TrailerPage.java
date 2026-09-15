package com.myanimedesk;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.Executors;

/** A real enclosing document gives the embedded player its required HTTP referrer. */
final class TrailerPage implements AutoCloseable {
    private final HttpServer server;
    private final java.util.concurrent.ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "trailer-page"); thread.setDaemon(true); return thread;
    });
    private final String path = "/" + UUID.randomUUID() + "/";
    TrailerPage(String embed) throws java.io.IOException {
        if (!embed.matches("https://www\\.(youtube-nocookie\\.com/embed/|dailymotion\\.com/embed/video/)[A-Za-z0-9_?=&.-]+"))
            throw new java.io.IOException("Invalid trailer URL");
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String html = "<!doctype html><html><head><meta charset='utf-8'><meta name='referrer' content='strict-origin-when-cross-origin'>"
            + "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            + "<style>html,body{margin:0;width:100%;height:100%;overflow:hidden;background:#000}iframe{position:absolute;inset:0;border:0;width:100%;height:100%;display:block}</style></head>"
            + "<body><iframe title='Trailer' allow='autoplay; encrypted-media; fullscreen; picture-in-picture' allowfullscreen src='"
            + embed.replace("&", "&amp;") + "'></iframe></body></html>";
        byte[] body = html.getBytes(StandardCharsets.UTF_8);
        server.createContext(path, exchange -> {
            if (!exchange.getRequestURI().getPath().equals(path) || !exchange.getRequestMethod().equals("GET")) {
                exchange.sendResponseHeaders(404, -1); exchange.close(); return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.getResponseHeaders().set("Referrer-Policy", "strict-origin-when-cross-origin");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.setExecutor(worker); server.start();
    }
    String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
    @Override public void close() { server.stop(0); worker.shutdownNow(); }
}
