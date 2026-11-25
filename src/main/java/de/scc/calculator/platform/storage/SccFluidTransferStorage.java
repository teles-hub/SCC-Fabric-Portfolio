package de.scc.calculator.platform.storage;

import de.scc.calculator.network.SccFluidStore;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Transactional Fabric Storage view of SCC's multi-tank mB store. */
public final class SccFluidTransferStorage extends SnapshotParticipant<SccFluidStore.State>
        implements Storage<FluidVariant> {
    private static final long DROPLETS_PER_MB = SccFluidStack.DROPLETS_PER_MB;
    private final SccFluidStore store;

    public SccFluidTransferStorage(SccFluidStore store) {
        this.store = store;
    }

    @Override
    public long insert(FluidVariant resource, long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maxAmount);
        if (resource.isBlank() || maxAmount < DROPLETS_PER_MB) return 0L;

        long requestedMb = maxAmount / DROPLETS_PER_MB;
        long insertedMb = 0L;
        while (insertedMb < requestedMb) {
            int chunkMb = (int) Math.min((long) Integer.MAX_VALUE, requestedMb - insertedMb);
            updateSnapshots(transaction);
            SccFluidStack stack = new SccFluidStack(resource.getFluid(), chunkMb, resource.getComponents());
            int movedMb = store.fill(stack, false);
            if (movedMb <= 0) break;
            insertedMb += movedMb;
            if (movedMb < chunkMb) break;
        }
        return insertedMb * DROPLETS_PER_MB;
    }

    @Override
    public long extract(FluidVariant resource, long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maxAmount);
        if (resource.isBlank() || maxAmount < DROPLETS_PER_MB) return 0L;

        long requestedMb = maxAmount / DROPLETS_PER_MB;
        long extractedMb = 0L;
        while (extractedMb < requestedMb) {
            int chunkMb = (int) Math.min((long) Integer.MAX_VALUE, requestedMb - extractedMb);
            updateSnapshots(transaction);
            SccFluidStack taken = store.drain(
                    new SccFluidStack(resource.getFluid(), chunkMb, resource.getComponents()), false);
            if (taken.isEmpty()) break;
            extractedMb += taken.getAmount();
            if (taken.getAmount() < chunkMb) break;
        }
        return extractedMb * DROPLETS_PER_MB;
    }

    @Override
    public Iterator<StorageView<FluidVariant>> iterator() {
        Set<FluidVariant> variants = new LinkedHashSet<>();
        for (SccFluidStack stack : store.entries()) {
            if (stack != null && !stack.isEmpty()) variants.add(stack.toVariant());
        }
        List<StorageView<FluidVariant>> views = new ArrayList<>(variants.size());
        for (FluidVariant variant : variants) views.add(new VariantView(variant));
        return views.iterator();
    }

    @Override
    protected SccFluidStore.State createSnapshot() {
        return store.snapshotState();
    }

    @Override
    protected void readSnapshot(SccFluidStore.State snapshot) {
        store.restoreState(snapshot);
    }

    private long amount(FluidVariant variant) {
        return store.amountOf(new SccFluidStack(variant.getFluid(), 1, variant.getComponents())) * DROPLETS_PER_MB;
    }

    private long capacity() {
        long mb = store.capacityMb();
        return mb == Long.MAX_VALUE || mb > Long.MAX_VALUE / DROPLETS_PER_MB
                ? Long.MAX_VALUE : mb * DROPLETS_PER_MB;
    }

    private final class VariantView implements StorageView<FluidVariant> {
        private final FluidVariant variant;

        private VariantView(FluidVariant variant) {
            this.variant = variant;
        }

        @Override
        public long extract(FluidVariant resource, long maxAmount, TransactionContext transaction) {
            if (!variant.equals(resource)) return 0L;
            return SccFluidTransferStorage.this.extract(resource, maxAmount, transaction);
        }

        @Override
        public boolean isResourceBlank() {
            return amount(variant) <= 0L;
        }

        @Override
        public FluidVariant getResource() {
            return isResourceBlank() ? FluidVariant.blank() : variant;
        }

        @Override
        public long getAmount() {
            return amount(variant);
        }

        @Override
        public long getCapacity() {
            return capacity();
        }
    }
}
