package de.scc.calculator.network;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Transactional aggregate Transfer API view over all connected SCC fluid stores. */
public final class SccNetworkFluidStorage implements Storage<FluidVariant> {
    private final List<Storage<FluidVariant>> parts;

    public SccNetworkFluidStorage(Iterable<? extends Storage<FluidVariant>> parts) {
        this.parts = copyNonNull(parts);
    }

    @Override
    public long insert(FluidVariant resource, long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maxAmount);
        if (resource.isBlank() || maxAmount <= 0L) return 0L;
        long remaining = maxAmount;
        long inserted = 0L;
        for (Storage<FluidVariant> part : parts) {
            if (remaining <= 0L) break;
            long moved = part.insert(resource, remaining, transaction);
            validateMoved(moved, remaining, "fluid insertion");
            inserted += moved;
            remaining -= moved;
        }
        return inserted;
    }

    @Override
    public long extract(FluidVariant resource, long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maxAmount);
        if (resource.isBlank() || maxAmount <= 0L) return 0L;
        long remaining = maxAmount;
        long extracted = 0L;
        for (Storage<FluidVariant> part : parts) {
            if (remaining <= 0L) break;
            long moved = part.extract(resource, remaining, transaction);
            validateMoved(moved, remaining, "fluid extraction");
            extracted += moved;
            remaining -= moved;
        }
        return extracted;
    }

    @Override
    public Iterator<StorageView<FluidVariant>> iterator() {
        List<StorageView<FluidVariant>> views = new ArrayList<>();
        for (Storage<FluidVariant> part : parts) part.forEach(views::add);
        return views.iterator();
    }

    private static List<Storage<FluidVariant>> copyNonNull(
            Iterable<? extends Storage<FluidVariant>> parts) {
        List<Storage<FluidVariant>> copy = new ArrayList<>();
        if (parts != null) for (Storage<FluidVariant> part : parts) if (part != null) copy.add(part);
        return List.copyOf(copy);
    }

    private static void validateMoved(long moved, long requested, String operation) {
        if (moved < 0L || moved > requested) {
            throw new IllegalStateException(operation + " returned invalid amount " + moved
                    + " for request " + requested);
        }
    }
}
