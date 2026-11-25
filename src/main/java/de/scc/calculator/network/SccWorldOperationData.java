package de.scc.calculator.network;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * World-global operation journal stored in the Overworld's SavedData directory.
 *
 * <p>This is the durable-world record, not a replacement for the terminal's
 * local queue. Callers must explicitly persist the world before and after any
 * inventory mutation and must treat ambiguous phases as recovery-only.</p>
 */
public final class SccWorldOperationData extends SavedData {
    public static final String DATA_ID = "scc_operations";
    public static final Factory<SccWorldOperationData> FACTORY = new Factory<>(
            SccWorldOperationData::new,
            SccWorldOperationData::load,
            DataFixTypes.LEVEL);

    private final SccOperationJournal journal = new SccOperationJournal(this::setDirty);

    public SccWorldOperationData() {
    }

    /** Retrieves the single world-wide journal from the Overworld. */
    public static SccWorldOperationData get(MinecraftServer server) {
        if (server == null) throw new IllegalArgumentException("server cannot be null");
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    public SccOperationJournal journal() {
        return journal;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        journal.save(tag);
        return tag;
    }

    private static SccWorldOperationData load(CompoundTag tag, HolderLookup.Provider registries) {
        SccWorldOperationData data = new SccWorldOperationData();
        data.journal.load(tag);
        return data;
    }
}
