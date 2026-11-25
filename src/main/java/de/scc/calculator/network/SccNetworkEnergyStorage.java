package de.scc.calculator.network;

import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import team.reborn.energy.api.EnergyStorage;

import java.util.ArrayList;
import java.util.List;

/** Aggregate Team Reborn Energy API endpoint over all connected SCC energy stores. */
public final class SccNetworkEnergyStorage implements EnergyStorage {
    private final List<EnergyStorage> parts;

    public SccNetworkEnergyStorage(Iterable<? extends EnergyStorage> parts) {
        List<EnergyStorage> copy = new ArrayList<>();
        if (parts != null) for (EnergyStorage part : parts) if (part != null) copy.add(part);
        this.parts = List.copyOf(copy);
    }

    @Override
    public long insert(long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notNegative(maxAmount);
        if (maxAmount <= 0L) return 0L;
        long remaining = maxAmount;
        long inserted = 0L;
        for (EnergyStorage part : parts) {
            if (remaining <= 0L) break;
            long moved = part.insert(remaining, transaction);
            validateMoved(moved, remaining, "energy insertion");
            inserted += moved;
            remaining -= moved;
        }
        return inserted;
    }

    @Override
    public long extract(long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notNegative(maxAmount);
        if (maxAmount <= 0L) return 0L;
        long remaining = maxAmount;
        long extracted = 0L;
        for (EnergyStorage part : parts) {
            if (remaining <= 0L) break;
            long moved = part.extract(remaining, transaction);
            validateMoved(moved, remaining, "energy extraction");
            extracted += moved;
            remaining -= moved;
        }
        return extracted;
    }

    @Override
    public long getAmount() {
        long total = 0L;
        for (EnergyStorage part : parts) total = saturatedAdd(total, part.getAmount());
        return total;
    }

    @Override
    public long getCapacity() {
        long total = 0L;
        for (EnergyStorage part : parts) total = saturatedAdd(total, part.getCapacity());
        return total;
    }

    private static void validateMoved(long moved, long requested, String operation) {
        if (moved < 0L || moved > requested) {
            throw new IllegalStateException(operation + " returned invalid amount " + moved
                    + " for request " + requested);
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
