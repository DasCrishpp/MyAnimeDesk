package com.myanimedesk;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class UpdateAndTrailerTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    private Path archive(String name) throws Exception {
        Path file = temporary.newFile().toPath();
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            zip.putNextEntry(new ZipEntry(name)); zip.write(new byte[]{1,2,3}); zip.closeEntry();
        }
        return file;
    }
    @Test public void extractsNestedRelease() throws Exception {
        Path destination = temporary.newFolder().toPath();
        AppUpdater.extract(archive("MyAnimeDesk/app/main.jar"), destination);
        assertArrayEquals(new byte[]{1,2,3}, Files.readAllBytes(destination.resolve("MyAnimeDesk/app/main.jar")));
    }
    @Test public void rejectsUnsafeZipPaths() throws Exception {
        for (String name : new String[]{"../escaped.exe", "C:/escaped.exe", "folder\\escaped.exe", "/escaped.exe"}) {
            Path destination = temporary.newFolder().toPath();
            try { AppUpdater.extract(archive(name), destination); fail("Unsafe path accepted"); }
            catch (java.io.IOException expected) { }
        }
    }
    @Test public void translatorChunksStayWithinUtf8Limit() {
        String text = "Una storia di amicizia 日本語 😀 ".repeat(100);
        var parts = SynopsisTranslator.chunks(text);
        assertTrue(parts.size() > 1);
        for (String part : parts) assertTrue(part.getBytes(StandardCharsets.UTF_8).length <= 480);
        assertEquals(text.strip(), String.join(" ", parts));
    }
    @Test public void trailerHasEnclosingDocumentAndReferrer() throws Exception {
        try (TrailerPage page = new TrailerPage("https://www.youtube-nocookie.com/embed/abc123"); HttpClient http = HttpClient.newHttpClient()) {
            var response = http.send(HttpRequest.newBuilder(URI.create(page.url())).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, response.statusCode());
            assertTrue(response.body().contains("<iframe"));
            assertEquals("strict-origin-when-cross-origin", response.headers().firstValue("Referrer-Policy").orElse(""));
        }
        try (TrailerPage ignored = new TrailerPage("https://example.com/video")) { fail("External URL accepted"); }
        catch (java.io.IOException expected) { }
    }
}
