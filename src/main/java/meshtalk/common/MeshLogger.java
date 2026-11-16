package meshtalk.common;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Simple structured logger with timestamps and thread names.
 * Designed to make thread behaviour visible during demos.
 *
 * Usage:
 *   MeshLogger log = new MeshLogger("Server");
 *   log.info("Listening on port %d", port);
 *   log.error("Client disconnected: %s", e.getMessage());
 */
public class MeshLogger {

    private static final DateTimeFormatter FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    private final String component;
    private static volatile boolean debugEnabled = false;

    public MeshLogger(String component) {
        this.component = component;
    }

    public static void setDebugEnabled(boolean enabled) {
        debugEnabled = enabled;
    }

    public void info(String format, Object... args) {
        log("INFO", format, args);
    }

    public void warn(String format, Object... args) {
        log("WARN", format, args);
    }

    public void error(String format, Object... args) {
        log("ERROR", format, args);
    }

    public void debug(String format, Object... args) {
        if (debugEnabled) {
            log("DEBUG", format, args);
        }
    }

    private void log(String level, String format, Object... args) {
        String time = LocalDateTime.now().format(FMT);
        String thread = Thread.currentThread().getName();
        String message = (args.length > 0) ? String.format(format, args) : format;
        String line = String.format("[%s] [%-5s] [%-20s] [%s] %s",
                time, level, thread, component, message);
        if ("ERROR".equals(level)) {
            System.err.println(line);
        } else {
            System.out.println(line);
        }
    }
}
