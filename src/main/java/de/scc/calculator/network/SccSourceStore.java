package de.scc.calculator.network;

import net.minecraft.nbt.CompoundTag;

/** Long-valued source store, preserving SCC's NBT key and capacity semantics. */
public final class SccSourceStore extends SccLongResourceStore implements SourceHandler {
    public SccSourceStore(long capacity, Runnable changed) {
        super(capacity, changed);
    }

    public CompoundTag save() {
        return save("source");
    }

    public void load(CompoundTag tag) {
        load(tag, "source");
    }
}
