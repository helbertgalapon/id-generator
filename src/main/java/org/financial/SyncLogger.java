package org.financial;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

/**
 * Simple file-based logger for sync-related operations.
 * Writes to ~/IdGenerator/sync.log and never throws.
 */
public final class SyncLogger {

    private static final Path LOG_PATH = Path.of(
            System.getProperty("user.home"),
            "IdGenerator",
            "sync.log"
    );

    private SyncLogger() {
        // no instances
    }

    public static synchronized void log(String message) {
        log(message, null);
    }

    public static synchronized void log(String message, Throwable t) {
        try {
            Files.createDirectories(LOG_PATH.getParent());
            StringBuilder sb = new StringBuilder();
            sb.append(Instant.now())
              .append(" ")
              .append(message == null ? "" : message)
              .append(System.lineSeparator());
            if (t != null) {
                sb.append(t.getClass().getName())
                  .append(": ")
                  .append(t.getMessage())
                  .append(System.lineSeparator());
            }
            Files.writeString(LOG_PATH, sb.toString(),
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
            // never throw from logger
        }
    }
}

