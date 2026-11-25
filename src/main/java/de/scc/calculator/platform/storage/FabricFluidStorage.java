package de.scc.calculator.platform.storage;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleVariantStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;

public class FabricFluidStorage extends SingleVariantStorage<FluidVariant> {
    public static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000L; // 81 droplets

    private final long capacityDroplets;
    private final Runnable onCommitCallback;

    public FabricFluidStorage(int capacityMb, Runnable onCommitCallback) {
        this.capacityDroplets = (long) capacityMb * DROPLETS_PER_MB;
        this.onCommitCallback = onCommitCallback;
    }

    @Override
    protected FluidVariant getBlankVariant() {
        return FluidVariant.blank();
    }

    @Override
    protected long getCapacity(FluidVariant variant) {
        return this.capacityDroplets;
    }

    @Override
    protected void onFinalCommit() {
        if (this.onCommitCallback != null) {
            this.onCommitCallback.run();
        }
    }

    public int getFluidAmountMb() {
        return (int) (this.amount / DROPLETS_PER_MB);
    }

    public int getCapacityMb() {
        return (int) (this.capacityDroplets / DROPLETS_PER_MB);
    }

    public long insertMb(FluidVariant resource, int maxMb, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maxMb);
        if (maxMb <= 0) return 0;

        // Limita a solicitacao antes de mutar. Inserir, por exemplo, 80 droplets e
        // reportar 0 mB seria perda silenciosa para quem usa a interface em mB.
        long freeDroplets = Math.max(0L, getCapacity(resource) - this.amount);
        long wholeMb = Math.min((long) maxMb, freeDroplets / DROPLETS_PER_MB);
        if (wholeMb <= 0) return 0;
        long insertedDroplets = insert(resource, wholeMb * DROPLETS_PER_MB, transaction);
        return insertedDroplets / DROPLETS_PER_MB;
    }

    public long extractMb(FluidVariant resource, int maxMb, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maxMb);
        if (maxMb <= 0) return 0;

        long wholeMb = Math.min((long) maxMb, this.amount / DROPLETS_PER_MB);
        if (wholeMb <= 0) return 0;
        long extractedDroplets = extract(resource, wholeMb * DROPLETS_PER_MB, transaction);
        return extractedDroplets / DROPLETS_PER_MB;
    }
}
