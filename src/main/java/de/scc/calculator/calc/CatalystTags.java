package de.scc.calculator.calc;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Index for SCC catalyst tags.
 *
 * <p>Tag keys use {@code scc:catalyst/<machine>/<category>} and
 * {@code scc:not_catalyst/<machine>/<category>}. The original NeoForge code
 * enumerated item registry tags directly. On Fabric, the platform adapter
 * feeds the resolved tag-to-item-id mapping through {@link #reload(Map)}.
 * This class deliberately contains no loader-specific API.</p>
 */
public final class CatalystTags {
    private static final String ADD_PREFIX = "scc:catalyst/";
    private static final String REMOVE_PREFIX = "scc:not_catalyst/";

    private static volatile boolean built;
    private static volatile Runnable changeListener = () -> { };
    private static Map<String, Set<String>> added = Map.of();
    private static Map<String, Set<String>> removed = Map.of();
    private static Map<String, Set<String>> byMachine = Map.of();

    private CatalystTags() {
    }

    /**
     * Rebuilds the index from fully-qualified tag identifiers to item IDs.
     * Invalid tag identifiers are ignored, matching the original parser.
     */
    public static void setChangeListener(Runnable listener) {
        changeListener = listener == null ? () -> { } : listener;
    }

    public static void reload(Map<String, ? extends Collection<String>> resolvedItemTags) {
        synchronized (CatalystTags.class) {
            Map<String, Set<String>> nextAdded = new LinkedHashMap<>();
            Map<String, Set<String>> nextRemoved = new LinkedHashMap<>();
            Map<String, Set<String>> nextByMachine = new LinkedHashMap<>();

            if (resolvedItemTags != null) {
                resolvedItemTags.forEach((tagId, itemIds) -> {
                    if (tagId == null || itemIds == null) {
                        return;
                    }
                    boolean isAddition = tagId.startsWith(ADD_PREFIX);
                    boolean isRemoval = tagId.startsWith(REMOVE_PREFIX);
                    if (!isAddition && !isRemoval) {
                        return;
                    }

                    String path = tagId.substring(isAddition ? ADD_PREFIX.length() : REMOVE_PREFIX.length());
                    int separator = path.indexOf('/');
                    if (separator <= 0 || separator == path.length() - 1) {
                        return;
                    }

                    String machineCategory = path.substring(0, separator) + ":" + path.substring(separator + 1);
                    Map<String, Set<String>> destination = isAddition ? nextAdded : nextRemoved;
                    for (String itemId : itemIds) {
                        if (itemId == null || itemId.isBlank()) {
                            continue;
                        }
                        destination.computeIfAbsent(machineCategory, ignored -> new LinkedHashSet<>()).add(itemId);
                        if (isAddition) {
                            nextByMachine.computeIfAbsent(itemId, ignored -> new LinkedHashSet<>()).add(machineCategory);
                        }
                    }
                });
            }

            added = freeze(nextAdded);
            removed = freeze(nextRemoved);
            byMachine = freeze(nextByMachine);
            built = true;
        }
        notifyChanged();
    }

    /** Invalidates the current snapshot after a data-pack reload. */
    public static void invalidate() {
        synchronized (CatalystTags.class) {
            built = false;
            added = Map.of();
            removed = Map.of();
            byMachine = Map.of();
        }
        notifyChanged();
    }

    /** Stable fingerprint component used to invalidate analysis snapshots. */
    public static synchronized String stamp() {
        ensureBuilt();
        StringBuilder value = new StringBuilder();
        appendSorted(value, "A", added);
        appendSorted(value, "R", removed);
        return value.toString();
    }

    private static void appendSorted(StringBuilder output, String prefix, Map<String, Set<String>> map) {
        map.keySet().stream().sorted().forEach(category -> {
            output.append(prefix).append(':').append(category).append('=');
            map.get(category).stream().sorted().forEach(item -> output.append(item).append(','));
            output.append(';');
        });
    }

    private static void notifyChanged() {
        try {
            changeListener.run();
        } catch (Throwable ignored) {
            // Tag reload must never break the datapack reload lifecycle.
        }
    }

    public static Set<String> added(String machineCategory) {
        ensureBuilt();
        return added.getOrDefault(machineCategory, Set.of());
    }

    public static Set<String> removed(String machineCategory) {
        ensureBuilt();
        return removed.getOrDefault(machineCategory, Set.of());
    }

    public static Set<String> categoriesOf(String itemId) {
        ensureBuilt();
        return byMachine.getOrDefault(itemId, Set.of());
    }

    public static boolean any() {
        ensureBuilt();
        return !added.isEmpty() || !removed.isEmpty();
    }

    private static synchronized void ensureBuilt() {
        // Before the first Fabric tag reload, behave as an empty tag index.
        // A platform adapter should call reload(...) on data-pack reload.
        if (!built) {
            added = Map.of();
            removed = Map.of();
            byMachine = Map.of();
            built = true;
        }
    }

    private static Map<String, Set<String>> freeze(Map<String, Set<String>> source) {
        Map<String, Set<String>> frozen = new LinkedHashMap<>();
        source.forEach((key, values) -> frozen.put(key,
                Collections.unmodifiableSet(new LinkedHashSet<>(values))));
        return Collections.unmodifiableMap(frozen);
    }
}
