package de.scc.calculator.network;

import net.minecraft.nbt.CompoundTag;

import java.util.Objects;

/**
 * Shared implementation for SCC's long-valued resource stores.
 * A negative capacity denotes an unlimited store, matching SCC 1.6.5.
 */
abstract class SccLongResourceStore implements LongStoreHandler {
    private long capacity;
    private final Runnable changed;
    private long stored;

    protected SccLongResourceStore(long capacity, Runnable changed) {
        this.capacity = capacity;
        this.changed = changed == null ? () -> { } : changed;
    }

    public final void setCapacity(long capacity) {
        this.capacity = capacity;
    }

    @Override
    public final long stored() {
        return stored;
    }

    protected final boolean isUnlimitedCapacity() {
        return capacity < 0L;
    }

    /** Notify the owning BlockEntity after restoring a transactional snapshot. */
    protected final void notifyChanged() {
        changed.run();
    }

    @Override
    public final long capacity() {
        return capacity < 0L ? Long.MAX_VALUE : capacity;
    }

    protected final long room() {
        return capacity < 0L
                ? Math.max(0L, Long.MAX_VALUE - stored)
                : Math.max(0L, capacity - stored);
    }

    @Override
    public final long receive(long amount, boolean simulate) {
        if (amount <= 0L) return 0L;
        long accepted = Math.min(amount, room());
        if (accepted <= 0L) return 0L;
        if (!simulate) {
            stored += accepted;
            changed.run();
        }
        return accepted;
    }

    @Override
    public final long extract(long amount, boolean simulate) {
        if (amount <= 0L) return 0L;
        long extracted = Math.min(amount, stored);
        if (extracted <= 0L) return 0L;
        if (!simulate) {
            stored -= extracted;
            changed.run();
        }
        return extracted;
    }

    protected final CompoundTag save(String key) {
        CompoundTag tag = new CompoundTag();
        tag.putLong(key, stored);
        return tag;
    }

    protected final void load(CompoundTag tag, String key) {
        Objects.requireNonNull(tag, "tag");
        stored = Math.max(0L, tag.getLong(key));
    }
}
