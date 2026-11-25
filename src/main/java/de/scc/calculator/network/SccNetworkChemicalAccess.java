package de.scc.calculator.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Deterministic aggregate access to SCC chemical tanks without binding to Mekanism classes. */
public final class SccNetworkChemicalAccess {
    private final List<SccChemicalAccess> parts;

    public SccNetworkChemicalAccess(Iterable<? extends SccChemicalAccess> parts) {
        List<SccChemicalAccess> copy = new ArrayList<>();
        if (parts != null) for (SccChemicalAccess part : parts) if (part != null) copy.add(part);
        this.parts = List.copyOf(copy);
    }

    public Map<String, Long> contents() {
        Map<String, Long> result = new LinkedHashMap<>();
        for (SccChemicalAccess part : parts) part.collect(result);
        return Collections.unmodifiableMap(result);
    }

    public long count(String chemicalId) {
        if (chemicalId == null || chemicalId.isBlank()) return 0L;
        long total = 0L;
        for (SccChemicalAccess part : parts) total = saturatedAdd(total, part.count(chemicalId));
        return total;
    }

    public long insert(String chemicalId, long amount, boolean simulate) {
        if (chemicalId == null || chemicalId.isBlank() || amount <= 0L) return 0L;
        long planned = transfer(chemicalId, amount, true, true);
        return simulate ? planned : transfer(chemicalId, planned, false, true);
    }

    public long extract(String chemicalId, long amount, boolean simulate) {
        if (chemicalId == null || chemicalId.isBlank() || amount <= 0L) return 0L;
        long planned = transfer(chemicalId, amount, true, false);
        return simulate ? planned : transfer(chemicalId, planned, false, false);
    }

    private long transfer(String chemicalId, long amount, boolean simulate, boolean insert) {
        long remaining = amount;
        long movedTotal = 0L;
        for (SccChemicalAccess part : parts) {
            if (remaining <= 0L) break;
            long moved = insert ? part.insert(chemicalId, remaining, simulate)
                    : part.drain(chemicalId, remaining, simulate);
            if (moved < 0L || moved > remaining) {
                throw new IllegalStateException("SCC network chemical child returned invalid amount " + moved);
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
