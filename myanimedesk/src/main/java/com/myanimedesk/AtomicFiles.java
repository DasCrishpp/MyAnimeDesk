package com.myanimedesk;

import java.io.IOException;
import java.nio.file.*;

final class AtomicFiles {
    private AtomicFiles() { }
    static void write(Path destination, byte[] bytes) throws IOException {
        Path path = destination.toAbsolutePath();
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".myanimedesk-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(temporary); }
    }
}
