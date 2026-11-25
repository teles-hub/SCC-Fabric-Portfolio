package de.scc.calculator.network;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic aggregate port for SCC long-valued resources such as Source and Soul.
 * The two-pass simulate/commit contract prevents a later store from being skipped
 * after earlier stores were already mutated when a network is only partially filled.
 */
public final class SccNetworkLongStore implements LongStoreHandler {
    private final List<LongStoreHandler> parts;

    public SccNetworkLongStore(Iterable<? extends LongStoreHandler> parts) {
        List<LongStoreHandler> copy = new ArrayList<>();
        if (parts != null) for (LongStoreHandler part : parts) if (part != null) copy.add(part);
        this.parts = List.copyOf(copy);
    }

    @Override
    public long stored() {
        long total = 0L;
        for (LongStoreHandler part : parts) total = saturatedAdd(total, part.stored());
        return total;
    }

    @Override
    public long capacity() {
        long total = 0L;
        for (LongStoreHandler part : parts) total = saturatedAdd(total, part.capacity());
        return total;
    }

    @Override
    public long receive(long amount, boolean simulate) {
        if (amount <= 0L) return 0L;
        long planned = transfer(amount, true, true);
        return simulate ? planned : transfer(planned, false, true);
    }

    @Override
    public long extract(long amount, boolean simulate) {
        if (amount <= 0L) return 0L;
        long planned = transfer(amount, true, false);
        return simulate ? planned : transfer(planned, false, false);
    }

    private long transfer(long amount, boolean simulate, boolean receive) {
        long remaining = amount;
        long movedTotal = 0L;
        for (LongStoreHandler part : parts) {
            if (remaining <= 0L) break;
            long moved = receive ? part.receive(remaining, simulate) : part.extract(remaining, simulate);
            if (moved < 0L || moved > remaining) {
                throw new IllegalStateException("SCC network long-store child returned invalid amount " + moved);
            }
            movedTotal = saturatedAdd(movedTotal, moved);
            remaining -= moved;
        }
        return movedTotal;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
