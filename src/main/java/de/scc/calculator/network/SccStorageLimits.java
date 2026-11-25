package de.scc.calculator.network;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;

/**
 * Server-side storage capacity settings migrated from SCC's SccServerConfig.
 * Property names are deliberately identical to the original config fields.
 */
public final class SccStorageLimits {
    public static final String[] PREFIXES = {
            "storageItems", "storageTanks", "storageFluidPerTank",
            "storageChemicalPerTank", "storageEnergy", "storageSource", "storageSouls"
    };
    public static final String[] TIER_SUFFIXES = {
            "Basic", "RedDwarf", "YellowStar", "BlueGiant", "NeutronStar", "Singularity"
    };

    private static volatile Map<String, Long> values = defaults();

    private SccStorageLimits() { }

    public static synchronized void load() {
        Path file = configPath();
        boolean createDefaults = !Files.isRegularFile(file);
        load(file);
        if (createDefaults) save(file);
    }

    /** Loads one file; exposed for deterministic migration tests. */
    public static synchronized void load(Path file) {
        Map<String, Long> next = new LinkedHashMap<>(defaults());
        if (Files.isRegularFile(file)) {
            Properties properties = new Properties();
            try (Reader reader = Files.newBufferedReader(file)) {
                properties.load(reader);
                for (String prefix : PREFIXES) {
                    for (int tier = 0; tier < TIER_SUFFIXES.length; tier++) {
                        String key = fieldName(prefix, tier);
                        String raw = properties.getProperty(key);
                        if (raw != null) {
                            try {
                                next.put(key, Long.parseLong(raw.trim()));
                            } catch (NumberFormatException invalid) {
                                throw new IllegalArgumentException("Invalid storage capacity " + key + "=" + raw, invalid);
                            }
                        }
                    }
                }
            } catch (IOException error) {
                throw new IllegalStateException("Could not read SCC storage limits: " + file, error);
            }
        }
        values = Map.copyOf(next);
    }

    public static synchronized void save() {
        save(configPath());
    }

    /** Writes only known settings, atomically where the filesystem supports it. */
    public static synchronized void save(Path file) {
        Properties properties = new Properties();
        Map<String, Long> snapshot = values;
        for (String prefix : PREFIXES) {
            for (int tier = 0; tier < TIER_SUFFIXES.length; tier++) {
                String key = fieldName(prefix, tier);
                properties.setProperty(key, Long.toString(snapshot.get(key)));
            }
        }

        Path parent = file.toAbsolutePath().getParent();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            if (parent != null) Files.createDirectories(parent);
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                properties.store(writer, "SCC 1.6.5 storage capacity settings (0 or negative = unlimited)");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException error) {
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
            throw new IllegalStateException("Could not save SCC storage limits: " + file, error);
        }
    }

    public static long tiered(String prefix, int tierOrdinal) {
        if (!isPrefix(prefix)) throw new IllegalArgumentException("Unknown SCC storage prefix: " + prefix);
        int safeOrdinal = Math.max(0, Math.min(TIER_SUFFIXES.length - 1, tierOrdinal));
        return values.get(fieldName(prefix, safeOrdinal));
    }

    /** Atomically replaces one limit; caller decides when to persist/reload runtime limits. */
    public static synchronized void set(String prefix, int tierOrdinal, long value) {
        if (!isPrefix(prefix)) throw new IllegalArgumentException("Unknown SCC storage prefix: " + prefix);
        if (tierOrdinal < 0 || tierOrdinal >= TIER_SUFFIXES.length) {
            throw new IndexOutOfBoundsException("tier ordinal: " + tierOrdinal);
        }
        Map<String, Long> next = new LinkedHashMap<>(values);
        next.put(fieldName(prefix, tierOrdinal), value);
        values = Map.copyOf(next);
    }

    public static Map<String, Long> snapshot() {
        return values;
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("scc").resolve("storage-limits.properties");
    }

    private static boolean isPrefix(String prefix) {
        for (String known : PREFIXES) if (known.equals(prefix)) return true;
        return false;
    }

    private static String fieldName(String prefix, int tierOrdinal) {
        return prefix + TIER_SUFFIXES[tierOrdinal];
    }

    private static Map<String, Long> defaults() {
        long[][] defaults = {
                {4_000, 16_000, 64_000, 256_000, 1_000_000, 0},
                {4, 6, 8, 10, 12, 0},
                {4_000, 8_000, 16_000, 32_000, 64_000, 0},
                {64_000, 256_000, 1_000_000, 4_000_000, 16_000_000, 0},
                {1_000_000, 4_000_000, 16_000_000, 64_000_000, 256_000_000, 0},
                {40_000, 160_000, 640_000, 2_560_000, 10_240_000, 0},
                {5_000, 20_000, 80_000, 320_000, 1_280_000, 0}
        };
        Map<String, Long> result = new LinkedHashMap<>();
        for (int prefix = 0; prefix < PREFIXES.length; prefix++) {
            for (int tier = 0; tier < TIER_SUFFIXES.length; tier++) {
                result.put(fieldName(PREFIXES[prefix], tier), defaults[prefix][tier]);
            }
        }
        return Map.copyOf(result);
    }
}
