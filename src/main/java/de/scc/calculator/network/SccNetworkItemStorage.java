package de.scc.calculator.network;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Transactional aggregate view over all item stores in a connected SCC network.
 * Every child adapter receives the same TransactionContext, so one outer
 * Fabric transaction commits or rolls back all touched storage blocks together.
 */
public final class SccNetworkItemStorage implements Storage<ItemVariant> {
    private final List<Storage<ItemVariant>> insertParts;
    private final List<Storage<ItemVariant>> extractParts;

    public SccNetworkItemStorage(Iterable<? extends Storage<ItemVariant>> parts) {
        this(parts, parts);
    }

    public SccNetworkItemStorage(Iterable<? extends Storage<ItemVariant>> insertParts,
                                 Iterable<? extends Storage<ItemVariant>> extractParts) {
        this.insertParts = copyNonNull(insertParts);
        this.extractParts = copyNonNull(extractParts);
    }

    @Override
    public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maxAmount);
        if (resource.isBlank() || maxAmount <= 0L) return 0L;
        long remaining = maxAmount;
        long inserted = 0L;
        for (Storage<ItemVariant> part : insertParts) {
            if (remaining <= 0L) break;
            long moved = part.insert(resource, remaining, transaction);
            validateMoved(moved, remaining, "item insertion");
            inserted += moved;
            remaining -= moved;
        }
        return inserted;
    }

    @Override
    public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maxAmount);
        if (resource.isBlank() || maxAmount <= 0L) return 0L;
        long remaining = maxAmount;
        long extracted = 0L;
        for (Storage<ItemVariant> part : extractParts) {
            if (remaining <= 0L) break;
            long moved = part.extract(resource, remaining, transaction);
            validateMoved(moved, remaining, "item extraction");
            extracted += moved;
            remaining -= moved;
        }
        return extracted;
    }

    @Override
    public Iterator<StorageView<ItemVariant>> iterator() {
        List<StorageView<ItemVariant>> views = new ArrayList<>();
        for (Storage<ItemVariant> part : extractParts) part.forEach(views::add);
        return views.iterator();
    }

    private static List<Storage<ItemVariant>> copyNonNull(
            Iterable<? extends Storage<ItemVariant>> parts) {
        List<Storage<ItemVariant>> copy = new ArrayList<>();
        if (parts != null) for (Storage<ItemVariant> part : parts) if (part != null) copy.add(part);
        return List.copyOf(copy);
    }

    private static void validateMoved(long moved, long requested, String operation) {
        if (moved < 0L || moved > requested) {
            throw new IllegalStateException(operation + " returned invalid amount " + moved
                    + " for request " + requested);
        }
    }
}
