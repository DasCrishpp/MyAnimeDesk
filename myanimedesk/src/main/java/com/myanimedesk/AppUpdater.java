package com.myanimedesk;

import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.zip.*;

/** Stages a verified release. The separate helper swaps files only after this process exits. */
final class AppUpdater {
    record Release(String version, String zipUrl, String hashUrl) { }
    static Path installation() throws IOException {
        String executable = System.getProperty("jpackage.app-path", "");
        if (executable.isBlank()) throw new IOException("L'aggiornamento automatico è disponibile nell'app installata (.exe).");
        Path root = Path.of(executable).toAbsolutePath().normalize().getParent();
        if (!Files.isRegularFile(root.resolve("MyAnimeDesk.exe")) || !Files.isDirectory(root.resolve("app")) || !Files.isDirectory(root.resolve("runtime")))
            throw new IOException("Cartella dell'app non riconosciuta.");
        return root;
    }
    static Release latest(String current) throws Exception {
        try (HttpClient http = client()) {
            JsonNode releases = new ObjectMapper().readTree(get(http, "https://api.github.com/repos/DasCrishpp/MyAnimeDesk/releases", 2_000_000));
            Release best = null;
            for (JsonNode release : releases) {
                String version = release.path("tag_name").asText("").replaceFirst("^v", "");
                if (release.path("draft").asBoolean() || release.path("prerelease").asBoolean() || !version.matches("\\d+\\.\\d+\\.\\d+") || !App.isNewerVersion(version, current)) continue;
                String zipName = "MyAnimeDesk-" + version + ".zip", zip = null, hash = null;
                for (JsonNode asset : release.path("assets")) {
                    if (zipName.equals(asset.path("name").asText())) zip = asset.path("browser_download_url").asText();
                    if ((zipName + ".sha256").equals(asset.path("name").asText())) hash = asset.path("browser_download_url").asText();
                }
                if (zip != null && hash != null && (best == null || App.isNewerVersion(version, best.version()))) best = new Release(version, zip, hash);
            }
            return best;
        }
    }
    static void stageAndLaunch(Release release, Path profile) throws Exception {
        Path root = installation();
        // Test write permission before the running application is closed.
        Path probe = Files.createTempFile(root, ".update-write-", ".tmp"); Files.delete(probe);
        Path stage = Files.createTempDirectory(root, ".update-stage-");
        Path archive = stage.resolve("release.zip");
        try (HttpClient http = client()) {
            requireReleaseUrl(release.zipUrl()); requireReleaseUrl(release.hashUrl());
            String checksum = new String(get(http, release.hashUrl(), 4096), StandardCharsets.UTF_8).strip().split("\\s+")[0];
            if (!checksum.matches("[a-fA-F0-9]{64}")) throw new IOException("Checksum non valido.");
            var response = http.send(HttpRequest.newBuilder(URI.create(release.zipUrl())).timeout(Duration.ofMinutes(10)).GET().build(), HttpResponse.BodyHandlers.ofInputStream());
            MessageDigest hash = MessageDigest.getInstance("SHA-256");
            try (InputStream input = response.body(); OutputStream output = Files.newOutputStream(archive)) {
                if (response.statusCode() != 200) throw new IOException("Download non disponibile.");
                byte[] buffer = new byte[65536]; long size = 0; int read;
                while ((read = input.read(buffer)) != -1) {
                    size += read; if (size > 600L * 1024 * 1024) throw new IOException("Archivio troppo grande.");
                    hash.update(buffer, 0, read); output.write(buffer, 0, read);
                }
            }
            if (!HexFormat.of().formatHex(hash.digest()).equalsIgnoreCase(checksum)) throw new IOException("Verifica aggiornamento fallita.");
        }
        Path unpacked = stage.resolve("unpacked"); Files.createDirectories(unpacked);
        extract(archive, unpacked);
        List<Path> executables;
        try (var files = Files.walk(unpacked, 4)) {
            executables = files.filter(p -> p.getFileName().toString().equals("MyAnimeDesk.exe")).toList();
        }
        if (executables.size() != 1) throw new IOException("Struttura aggiornamento non valida.");
        Path payload = executables.get(0).getParent();
        if (!Files.isDirectory(payload.resolve("app")) || !Files.isDirectory(payload.resolve("runtime"))) throw new IOException("Runtime mancante.");
        Path script = stage.resolve("install.ps1");
        try (InputStream source = AppUpdater.class.getResourceAsStream("/com/myanimedesk/install-update.ps1")) {
            if (source == null) throw new IOException("Installer mancante.");
            AtomicFiles.write(script, source.readAllBytes());
        }
        String command = "& " + quote(script) + " -Install " + quote(root) + " -Payload " + quote(payload)
            + " -OldPid " + ProcessHandle.current().pid();
        String encoded = Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_16LE));
        new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-ExecutionPolicy", "Bypass", "-EncodedCommand", encoded).start();
    }
    static void extract(Path archive, Path destination) throws IOException {
        Path root = destination.toAbsolutePath().normalize();
        long total = 0; int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(Files.newInputStream(archive))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (++entries > 30000 || entry.getName().contains(":") || entry.getName().contains("\\")) throw new IOException("Invalid ZIP entry");
                Path file = root.resolve(entry.getName()).normalize();
                if (!file.startsWith(root) || file.equals(root)) throw new IOException("ZIP path outside staging folder");
                if (entry.isDirectory()) { Files.createDirectories(file); continue; }
                Files.createDirectories(file.getParent());
                try (OutputStream output = Files.newOutputStream(file, StandardOpenOption.CREATE_NEW)) {
                    byte[] buffer = new byte[65536]; int read;
                    while ((read = zip.read(buffer)) != -1) {
                        total += read; if (total > 2L * 1024 * 1024 * 1024) throw new IOException("ZIP too large");
                        output.write(buffer, 0, read);
                    }
                }
            }
        }
    }
    private static String quote(Path path) { return "'" + path.toString().replace("'", "''") + "'"; }
    private static void requireReleaseUrl(String url) throws IOException {
        if (!url.startsWith("https://github.com/DasCrishpp/MyAnimeDesk/releases/download/")) throw new IOException("Invalid release source");
    }
    private static HttpClient client() { return HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(15)).build(); }
    private static byte[] get(HttpClient client, String url, int limit) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("User-Agent", "MyAnimeDesk").GET().build(), HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream input = response.body()) {
            if (response.statusCode() != 200) throw new IOException("Servizio aggiornamenti non disponibile.");
            byte[] bytes = input.readNBytes(limit + 1); if (bytes.length > limit) throw new IOException("Response too large"); return bytes;
        }
    }
}
