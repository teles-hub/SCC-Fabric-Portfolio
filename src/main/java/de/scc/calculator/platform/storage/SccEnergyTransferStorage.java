package de.scc.calculator.platform.storage;

import de.scc.calculator.network.SccEnergyStore;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.nbt.CompoundTag;
import team.reborn.energy.api.EnergyStorage;

/** Transactional Team Reborn Energy API bridge over SCC's long-capacity energy store. */
public final class SccEnergyTransferStorage extends SnapshotParticipant<CompoundTag> implements EnergyStorage {
    private final SccEnergyStore store;

    public SccEnergyTransferStorage(SccEnergyStore store) {
        this.store = store;
    }

    @Override
    public long insert(long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notNegative(maxAmount);
        if (maxAmount <= 0L) return 0L;
        updateSnapshots(transaction);
        return store.give(maxAmount, false);
    }

    @Override
    public long extract(long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notNegative(maxAmount);
        if (maxAmount <= 0L) return 0L;
        updateSnapshots(transaction);
        return store.take(maxAmount, false);
    }

    @Override
    public long getAmount() {
        return store.storedExact();
    }

    @Override
    public long getCapacity() {
        return store.capacityExact();
    }

    @Override
    protected CompoundTag createSnapshot() {
        return store.save().copy();
    }

    @Override
    protected void readSnapshot(CompoundTag snapshot) {
        store.restoreTransactionSnapshot(snapshot.copy());
    }
}
