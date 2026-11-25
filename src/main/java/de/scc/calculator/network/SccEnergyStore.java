package de.scc.calculator.network;

import de.scc.calculator.platform.storage.SccEnergyStorage;
import net.minecraft.nbt.CompoundTag;

/**
 * Long-capacity Forge Energy-compatible store reconstructed from SCC 1.6.5.
 * The public IEnergyStorage-shaped view saturates to int while exact operations
 * retain long precision for the original large-capacity path.
 */
public final class SccEnergyStore extends SccLongResourceStore implements SccEnergyStorage {
    private static final long INT_MAX = Integer.MAX_VALUE;
    private static final long DISPLAY_FLOOR = 1_073_741_823L;

    public SccEnergyStore(long capacity, Runnable changed) {
        super(capacity, changed);
    }

    public boolean unlimited() {
        return isUnlimitedCapacity();
    }

    public long storedExact() {
        return stored();
    }

    public long capacityExact() {
        return capacity();
    }

    public long roomExact() {
        return Math.max(0L, capacityExact() - storedExact());
    }

    public long give(long amount, boolean simulate) {
        return receive(amount, simulate);
    }

    public long take(long amount, boolean simulate) {
        return extract(amount, simulate);
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        return (int) receive(maxReceive, simulate);
    }

    @Override
    public int extractEnergy(int maxExtract, boolean simulate) {
        return (int) extract(maxExtract, simulate);
    }

    @Override
    public int getEnergyStored() {
        long capacity = capacityExact();
        long stored = storedExact();
        if (capacity <= INT_MAX) return (int) Math.min(INT_MAX, stored);
        long boundedRoom = Math.min(INT_MAX, roomExact());
        return (int) Math.max(INT_MAX - boundedRoom, Math.min(stored, DISPLAY_FLOOR));
    }

    @Override
    public int getMaxEnergyStored() {
        return (int) Math.min(INT_MAX, capacityExact());
    }

    @Override public boolean canExtract() { return true; }
    @Override public boolean canReceive() { return true; }

    public CompoundTag save() {
        return save("fe");
    }

    public void load(CompoundTag tag) {
        load(tag, "fe");
    }

    /** Restores a Transfer API rollback snapshot and refreshes block state/output. */
    public void restoreTransactionSnapshot(CompoundTag tag) {
        load(tag);
        notifyChanged();
    }
}
