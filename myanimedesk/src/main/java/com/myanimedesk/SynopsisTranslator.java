package com.myanimedesk;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Only public catalogue text is translated; successful translations are cached locally. */
final class SynopsisTranslator {
    static String italian(String original, Path profile) throws Exception {
        String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original.getBytes(StandardCharsets.UTF_8)));
        Path cached = profile.resolve("translations").resolve(key + "-it.txt");
        if (Files.exists(cached)) return Files.readString(cached);
        StringBuilder translated = new StringBuilder();
        try (HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build()) {
            for (String part : chunks(original)) {
                URI uri = URI.create("https://api.mymemory.translated.net/get?langpair=en%7Cit&q=" + URLEncoder.encode(part, StandardCharsets.UTF_8));
                var response = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofString());
                var json = new ObjectMapper().readTree(response.body());
                if (response.statusCode() != 200 || json.path("responseStatus").asInt() != 200 || json.path("quotaFinished").asBoolean())
                    throw new IOException("Translation unavailable");
                String text = json.path("responseData").path("translatedText").asText("");
                if (text.isBlank()) throw new IOException("Empty translation");
                if (!translated.isEmpty()) translated.append(' ');
                translated.append(Texts.synopsis(text));
            }
        }
        AtomicFiles.write(cached, translated.toString().getBytes(StandardCharsets.UTF_8));
        return translated.toString();
    }
    static List<String> chunks(String text) {
        List<String> chunks = new ArrayList<>();
        String remaining = text.strip();
        while (!remaining.isEmpty()) {
            int end = 0, bytes = 0;
            while (end < remaining.length()) {
                int cp = remaining.codePointAt(end);
                int size = new String(Character.toChars(cp)).getBytes(StandardCharsets.UTF_8).length;
                if (bytes + size > 480) break;
                bytes += size; end += Character.charCount(cp);
            }
            if (end < remaining.length()) {
                int space = remaining.lastIndexOf(' ', end);
                if (space > end / 2) end = space;
            }
            chunks.add(remaining.substring(0, end)); remaining = remaining.substring(end).stripLeading();
        }
        return chunks;
    }
}
