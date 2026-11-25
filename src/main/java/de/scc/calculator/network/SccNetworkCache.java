package de.scc.calculator.network;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Dimension-scoped short-lived cache. Topology mutations invalidate the full dimension. */
public final class SccNetworkCache {
    private static final long CACHE_TICKS = 10L;
    private static final Map<Level, Map<BlockPos, Entry>> CACHE = new WeakHashMap<>();
    private static long hits;
    private static long misses;

    private SccNetworkCache() { }

    public static synchronized SccNetworkView peek(Level level, BlockPos pos) {
        if (level == null || pos == null) return null;
        Map<BlockPos, Entry> byOrigin = CACHE.get(level);
        if (byOrigin == null) return null;
        Entry entry = byOrigin.get(pos);
        SccNetworkView cached = entry == null ? null : entry.get();
        if (cached == null || level.getGameTime() - entry.createdAt > CACHE_TICKS) {
            if (entry != null) byOrigin.remove(pos);
            return null;
        }
        hits++;
        return cached;
    }

    public static synchronized SccNetworkView view(Level level, BlockPos pos) {
        if (level == null || pos == null) return SccNetworkView.empty(level, pos);
        SccNetworkView cached = peek(level, pos);
        if (cached != null) return cached;
        misses++;
        SccNetworkView scanned = SccNetworkScanner.scan(level, pos);
        Map<BlockPos, Entry> byOrigin = CACHE.computeIfAbsent(level, ignored -> new HashMap<>());
        Entry entry = new Entry(level.getGameTime(), scanned);
        for (BlockPos node : scanned.nodes()) byOrigin.put(node.immutable(), entry);
        byOrigin.put(pos.immutable(), entry);
        return scanned;
    }

    public static synchronized void invalidate(Level level) {
        if (level != null) CACHE.remove(level);
    }

    public static synchronized void invalidate(Level level, BlockPos pos) {
        // A single topology edge can split or merge two components; invalidating the dimension
        // is safer than trying to identify all affected cached components.
        invalidate(level);
    }

    public static synchronized void clear() {
        CACHE.clear();
        hits = 0L;
        misses = 0L;
    }

    public static synchronized long hits() { return hits; }
    public static synchronized long misses() { return misses; }

    private static final class Entry extends WeakReference<SccNetworkView> {
        private final long createdAt;
        private Entry(long createdAt, SccNetworkView view) {
            super(view);
            this.createdAt = createdAt;
        }
    }
}
