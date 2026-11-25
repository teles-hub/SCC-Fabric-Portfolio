package de.scc.calculator.network;

import net.minecraft.nbt.CompoundTag;

/** Long-valued soul store, preserving SCC's NBT key and capacity semantics. */
public final class SccSoulStore extends SccLongResourceStore implements SoulHandler {
    public SccSoulStore(long capacity, Runnable changed) {
        super(capacity, changed);
    }

    public CompoundTag save() {
        return save("souls");
    }

    public void load(CompoundTag tag) {
        load(tag, "souls");
    }
}
