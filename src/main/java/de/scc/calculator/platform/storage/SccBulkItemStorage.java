package de.scc.calculator.platform.storage;

import de.scc.calculator.calc.StackKey;
import de.scc.calculator.network.SccItemStore;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Transactional Fabric Transfer API view of SCC's long-count bulk item store.
 * The adapter deliberately exposes one view per item+components variant and
 * retains SCC's >64 stack capacity semantics.
 */
public final class SccBulkItemStorage extends SnapshotParticipant<SccItemStore.State>
        implements Storage<ItemVariant> {
    private final SccItemStore store;

    public SccBulkItemStorage(SccItemStore store) {
        this.store = store;
    }

    @Override
    public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maxAmount);
        if (resource.isBlank() || maxAmount <= 0L) return 0L;

        ItemStack exemplar = resource.toStack(1);
        long inserted = 0L;
        while (inserted < maxAmount) {
            int chunk = (int) Math.min((long) Integer.MAX_VALUE, maxAmount - inserted);
            updateSnapshots(transaction);
            ItemStack remainder = store.insertAnywhere(exemplar.copyWithCount(chunk), false);
            int moved = chunk - (remainder.isEmpty() ? 0 : remainder.getCount());
            if (moved <= 0) break;
            inserted += moved;
            if (!remainder.isEmpty()) break;
        }
        return inserted;
    }

    @Override
    public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maxAmount);
        if (resource.isBlank() || maxAmount <= 0L) return 0L;

        ItemStack exemplar = resource.toStack(1);
        StackKey key = StackKey.of(exemplar);
        if (key == null) return 0L;

        long extracted = 0L;
        while (extracted < maxAmount) {
            int chunk = (int) Math.min((long) Integer.MAX_VALUE, maxAmount - extracted);
            if (store.count(key) <= 0L) break;
            updateSnapshots(transaction);
            ItemStack taken = store.take(key, exemplar, chunk, false);
            if (taken.isEmpty()) break;
            extracted += taken.getCount();
            if (taken.getCount() < chunk) break;
        }
        return extracted;
    }

    @Override
    public Iterator<StorageView<ItemVariant>> iterator() {
        Map<ItemVariant, ItemStack> unique = new LinkedHashMap<>();
        for (ItemStack stack : store.entries()) {
            if (stack != null && !stack.isEmpty()) unique.putIfAbsent(ItemVariant.of(stack), stack.copyWithCount(1));
        }
        List<StorageView<ItemVariant>> views = new ArrayList<>(unique.size());
        for (Map.Entry<ItemVariant, ItemStack> entry : unique.entrySet()) {
            views.add(new VariantView(entry.getKey(), entry.getValue()));
        }
        return views.iterator();
    }

    @Override
    protected SccItemStore.State createSnapshot() {
        return store.snapshotState();
    }

    @Override
    protected void readSnapshot(SccItemStore.State snapshot) {
        store.restoreState(snapshot);
    }

    private final class VariantView implements StorageView<ItemVariant> {
        private final ItemVariant variant;
        private final ItemStack exemplar;
        private final StackKey key;

        private VariantView(ItemVariant variant, ItemStack exemplar) {
            this.variant = variant;
            this.exemplar = exemplar;
            this.key = StackKey.of(exemplar);
        }

        @Override
        public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            if (!variant.equals(resource)) return 0L;
            return SccBulkItemStorage.this.extract(resource, maxAmount, transaction);
        }

        @Override
        public boolean isResourceBlank() {
            return key == null || store.count(key) <= 0L;
        }

        @Override
        public ItemVariant getResource() {
            return isResourceBlank() ? ItemVariant.blank() : variant;
        }

        @Override
        public long getAmount() {
            return key == null ? 0L : store.count(key);
        }

        @Override
        public long getCapacity() {
            long amount = getAmount();
            long room = store.room();
            return room > Long.MAX_VALUE - amount ? Long.MAX_VALUE : amount + room;
        }
    }
}
