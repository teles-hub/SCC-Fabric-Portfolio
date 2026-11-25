package de.scc.calculator.platform;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/**
 * Small UTF-8 persistence helper for SCC config files.
 *
 * <p>The payload is written to a unique sibling file and only replaces the
 * destination after the writer closes successfully. A failed serialization
 * therefore leaves the last known-good configuration intact.</p>
 */
public final class AtomicFiles {
    private AtomicFiles() { }

    @FunctionalInterface
    public interface WriterAction {
        void write(BufferedWriter writer) throws IOException;
    }

    public static void writeUtf8(Path destination, WriterAction action) throws IOException {
        if (destination == null) throw new IllegalArgumentException("destination cannot be null");
        if (action == null) throw new IllegalArgumentException("action cannot be null");

        Path target = destination.toAbsolutePath();
        Path parent = target.getParent();
        if (parent == null) throw new IOException("Destination has no parent directory: " + target);
        Files.createDirectories(parent);

        String prefix = target.getFileName().toString() + ".";
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temporary,
                    StandardCharsets.UTF_8, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                action.write(writer);
            }

            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
