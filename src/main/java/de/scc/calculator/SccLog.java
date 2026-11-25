package de.scc.calculator;

import java.util.logging.Level;
import java.util.logging.Logger;

/** Small Fabric-neutral logger bridge required by the embedded StackKey class. */
public final class SccLog {
    private static final Logger LOGGER = Logger.getLogger("scc");

    private SccLog() {
    }

    public static void info(String category, String message) {
        LOGGER.log(Level.INFO, "[{0}] {1}", new Object[]{category, message});
    }

    public static void debug(String category, String message) {
        LOGGER.log(Level.FINE, "[{0}] {1}", new Object[]{category, message});
    }

    public static void warn(String category, String message) {
        LOGGER.log(Level.WARNING, "[{0}] {1}", new Object[]{category, message});
    }

    public static void error(String category, String message, Throwable error) {
        LOGGER.log(Level.SEVERE, "[" + category + "] " + message, error);
    }

    public static void caught(String category, String message, Throwable error) {
        LOGGER.log(Level.WARNING, "[" + category + "] " + message, error);
    }

    public static void block(String category, String message, Iterable<String> values) {
        LOGGER.log(Level.INFO, "[{0}] {1}: {2}", new Object[]{category, message, values});
    }

    public static boolean on() {
        return LOGGER.isLoggable(Level.FINE);
    }
}
