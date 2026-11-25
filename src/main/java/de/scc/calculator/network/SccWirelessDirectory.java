package de.scc.calculator.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Fabric port of the original DefaultTransmitters SavedData.
 *
 * <p>The index is global to the server and stored in the Overworld. It records
 * player defaults, receiver ownership/link metadata, and links waiting for a
 * receiver chunk to load. It never requests chunk loads.</p>
 */
public final class SccWirelessDirectory extends SavedData {
    public static final String DATA_ID = "scc_default_transmitters";
    public static final int MAX_RECEIVERS = 20_000;
    public static final int MAX_PENDING_LINKS = MAX_RECEIVERS;
    private static final Factory<SccWirelessDirectory> FACTORY = new Factory<>(
            SccWirelessDirectory::new, SccWirelessDirectory::load, DataFixTypes.LEVEL);

    private final Map<UUID, GlobalPos> defaults = new HashMap<>();
    private final Map<GlobalPos, Rx> receivers = new HashMap<>();
    private final Map<GlobalPos, GlobalPos> pending = new HashMap<>();

    private SccWirelessDirectory() { }

    public static SccWirelessDirectory get(MinecraftServer server) {
        if (server == null) throw new IllegalArgumentException("server cannot be null");
        return server.overworld().getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    @Nullable public synchronized GlobalPos defaultOf(@Nullable UUID player) {
        return player == null ? null : defaults.get(player);
    }

    public synchronized boolean isDefault(@Nullable UUID player, ResourceKey<Level> dimension, BlockPos pos) {
        GlobalPos value = defaultOf(player);
        return value != null && value.dimension().equals(dimension) && value.pos().equals(pos);
    }

    public synchronized void setDefault(UUID player, GlobalPos transmitter) {
        if (player == null || transmitter == null) return;
        if (!transmitter.equals(defaults.put(player, transmitter))) setDirty();
    }

    public synchronized void clearDefault(UUID player) {
        if (player != null && defaults.remove(player) != null) setDirty();
    }

    public synchronized void note(ResourceKey<Level> dimension, BlockPos pos,
                                  @Nullable UUID owner, @Nullable GlobalPos link) {
        GlobalPos receiver = GlobalPos.of(dimension, pos.immutable());
        Rx next = new Rx(owner, link);
        Rx previous = receivers.get(receiver);
        if (next.equals(previous)) return;
        if (previous == null && receivers.size() >= MAX_RECEIVERS) return;
        receivers.put(receiver, next);
        setDirty();
    }

    public synchronized void noteKnown(ResourceKey<Level> dimension, BlockPos pos, GlobalPos link) {
        GlobalPos receiver = GlobalPos.of(dimension, pos.immutable());
        if (receivers.containsKey(receiver) || receivers.size() >= MAX_RECEIVERS) return;
        receivers.put(receiver, new Rx(null, link));
        setDirty();
    }

    public synchronized void forget(ResourceKey<Level> dimension, BlockPos pos) {
        GlobalPos receiver = GlobalPos.of(dimension, pos);
        boolean changed = receivers.remove(receiver) != null;
        changed |= pending.remove(receiver) != null;
        if (changed) setDirty();
    }

    @Nullable public synchronized GlobalPos takePending(ResourceKey<Level> dimension, BlockPos pos) {
        GlobalPos result = pending.remove(GlobalPos.of(dimension, pos));
        if (result != null) setDirty();
        return result;
    }

    public synchronized void putPending(GlobalPos receiver, GlobalPos transmitter) {
        if (receiver == null || transmitter == null) return;
        GlobalPos previous = pending.get(receiver);
        if (transmitter.equals(previous)) return;
        if (previous == null && pending.size() >= MAX_PENDING_LINKS) return;
        pending.put(receiver, transmitter);
        setDirty();
    }

    /**
     * Removes directory references to a transmitter that was actually broken.
     * Receiver-side links are intentionally retained: their block entities own
     * the authoritative address and will report GONE until relinked.
     */
    public synchronized void forgetTransmitter(GlobalPos transmitter) {
        if (transmitter == null) return;
        boolean changed = defaults.entrySet().removeIf(entry -> transmitter.equals(entry.getValue()));
        changed |= pending.entrySet().removeIf(entry -> transmitter.equals(entry.getValue()));
        if (changed) setDirty();
    }

    public synchronized boolean pendingTo(GlobalPos receiver, GlobalPos transmitter) {
        return transmitter != null && transmitter.equals(pending.get(receiver));
    }

    public synchronized Map<GlobalPos, Rx> receivers() {
        return new HashMap<>(receivers);
    }

    public synchronized Map<GlobalPos, GlobalPos> pending() {
        return new HashMap<>(pending);
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag defaultList = new ListTag();
        defaults.forEach((player, pos) -> {
            CompoundTag entry = new CompoundTag();
            entry.putUUID("player", player);
            writePos(entry, pos);
            defaultList.add(entry);
        });
        tag.put("defaults", defaultList);

        ListTag receiverList = new ListTag();
        receivers.forEach((pos, rx) -> {
            CompoundTag entry = posTag(pos);
            if (rx.owner() != null) entry.putUUID("owner", rx.owner());
            if (rx.link() != null) entry.put("link", posTag(rx.link()));
            receiverList.add(entry);
        });
        if (!receiverList.isEmpty()) tag.put("receivers", receiverList);

        ListTag pendingList = new ListTag();
        pending.forEach((receiver, transmitter) -> {
            CompoundTag entry = posTag(receiver);
            entry.put("to", posTag(transmitter));
            pendingList.add(entry);
        });
        if (!pendingList.isEmpty()) tag.put("pending", pendingList);
        return tag;
    }

    private static SccWirelessDirectory load(CompoundTag tag, HolderLookup.Provider registries) {
        SccWirelessDirectory result = new SccWirelessDirectory();
        ListTag defaultList = tag.getList("defaults", 10);
        for (int i = 0; i < defaultList.size(); i++) {
            CompoundTag entry = defaultList.getCompound(i);
            GlobalPos pos = readPos(entry);
            if (entry.hasUUID("player") && pos != null) result.defaults.put(entry.getUUID("player"), pos);
        }

        ListTag receiverList = tag.getList("receivers", 10);
        for (int i = 0; i < receiverList.size() && result.receivers.size() < MAX_RECEIVERS; i++) {
            CompoundTag entry = receiverList.getCompound(i);
            GlobalPos receiver = readPos(entry);
            GlobalPos link = entry.contains("link", 10) ? readPos(entry.getCompound("link")) : null;
            if (receiver != null) result.receivers.put(receiver,
                    new Rx(entry.hasUUID("owner") ? entry.getUUID("owner") : null, link));
        }

        ListTag pendingList = tag.getList("pending", 10);
        for (int i = 0; i < pendingList.size() && result.pending.size() < MAX_PENDING_LINKS; i++) {
            CompoundTag entry = pendingList.getCompound(i);
            GlobalPos receiver = readPos(entry);
            GlobalPos target = entry.contains("to", 10) ? readPos(entry.getCompound("to")) : null;
            if (receiver != null && target != null) result.pending.put(receiver, target);
        }
        return result;
    }

    private static CompoundTag posTag(GlobalPos pos) {
        CompoundTag tag = new CompoundTag();
        writePos(tag, pos);
        return tag;
    }

    private static void writePos(CompoundTag tag, GlobalPos pos) {
        tag.putString("dim", pos.dimension().location().toString());
        tag.putLong("pos", pos.pos().asLong());
    }

    @Nullable private static GlobalPos readPos(CompoundTag tag) {
        ResourceKey<Level> dimension = WirelessLink.dimFromName(tag.getString("dim"));
        return dimension == null || !tag.contains("pos")
                ? null : GlobalPos.of(dimension, BlockPos.of(tag.getLong("pos")));
    }

    public record Rx(@Nullable UUID owner, @Nullable GlobalPos link) { }
}
