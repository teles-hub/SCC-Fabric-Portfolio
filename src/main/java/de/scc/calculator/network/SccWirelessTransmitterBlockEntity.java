package de.scc.calculator.network;

import de.scc.calculator.registry.SccBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Fabric transmitter endpoint: heartbeat, upgrades, hosted parts and directory booking.
 * Remote chunks are only inspected when already loaded; this device never force-loads them.
 */
public final class SccWirelessTransmitterBlockEntity extends SccNetworkBlockEntity {
    public static final int BASE_RANGE = 32;
    public static final int RANGE_PER_UPGRADE = 32;
    public static final int MAX_RANGE_UPGRADES = 8;
    public static final int HEARTBEAT_EVERY = 20;
    public static final int HEARTBEAT_TIMEOUT = 100;
    public static final int MAX_KNOWN_RECEIVERS = 256;

    private int rangeUpgrades;
    private boolean dimensional;
    private boolean infinite;
    private final Map<GlobalPos, Long> live = new HashMap<>();
    private final LinkedHashSet<GlobalPos> known = new LinkedHashSet<>();
    private boolean bookedKnown;
    private UUID owner;

    public SccWirelessTransmitterBlockEntity(BlockPos pos, BlockState state) {
        super(SccBlockEntities.SCC_WIRELESS_TRANSMITTER.get(), pos, state);
    }

    @Override
    public boolean canConnect(Direction face) {
        if (face == null || level == null || !level.hasChunkAt(worldPosition)) return false;
        BlockState state = getBlockState();
        return state.getBlock() instanceof SccWirelessTransmitterBlock
                && state.getValue(SccWirelessTransmitterBlock.propertyFor(face));
    }

    public UUID owner() { return owner; }
    public void setOwner(UUID owner) {
        if (java.util.Objects.equals(this.owner, owner)) return;
        this.owner = owner;
        setChanged();
    }

    public int rangeUpgrades() { return rangeUpgrades; }
    public boolean dimensional() { return dimensional; }
    public boolean infinite() { return infinite; }
    public int range() { return BASE_RANGE + rangeUpgrades * RANGE_PER_UPGRADE; }

    public boolean addRangeUpgrade() {
        if (rangeUpgrades >= MAX_RANGE_UPGRADES) return false;
        rangeUpgrades++;
        changedAndInvalidate();
        return true;
    }

    public boolean addDimensionalUpgrade() {
        if (dimensional) return false;
        dimensional = true;
        changedAndInvalidate();
        return true;
    }

    public boolean addInfiniteUpgrade() {
        if (infinite) return false;
        infinite = true;
        changedAndInvalidate();
        return true;
    }

    public int takeRangeUpgrades() {
        int result = rangeUpgrades;
        if (result != 0) {
            rangeUpgrades = 0;
            changedAndInvalidate();
        }
        return result;
    }

    public boolean takeDimensionalUpgrade() {
        if (!dimensional) return false;
        dimensional = false;
        changedAndInvalidate();
        return true;
    }

    public boolean takeInfiniteUpgrade() {
        if (!infinite) return false;
        infinite = false;
        changedAndInvalidate();
        return true;
    }

    public boolean reaches(ResourceKey<Level> targetDimension, BlockPos targetPos) {
        if (targetDimension == null || targetPos == null || level == null) return false;
        if (dimensional) return true;
        if (!level.dimension().equals(targetDimension)) return false;
        if (infinite) return true;
        double max = range();
        return worldPosition.distSqr(targetPos) <= max * max;
    }

    public void heartbeat(ResourceKey<Level> receiverDimension, BlockPos receiverPos) {
        if (level == null || level.isClientSide || receiverDimension == null || receiverPos == null) return;
        GlobalPos endpoint = GlobalPos.of(receiverDimension, receiverPos.immutable());
        if (live.put(endpoint, level.getGameTime()) == null) SccNetworkCache.invalidate(level);
        if (!known.contains(endpoint) && known.size() < MAX_KNOWN_RECEIVERS) {
            known.add(endpoint);
            setChanged();
        }
    }

    public List<GlobalPos> receivers() {
        if (level == null) return List.of();
        long now = level.getGameTime();
        List<GlobalPos> result = new ArrayList<>();
        live.forEach((endpoint, seen) -> {
            if (now - seen <= HEARTBEAT_TIMEOUT) result.add(endpoint);
        });
        return List.copyOf(result);
    }

    public List<GlobalPos> knownReceivers() {
        LinkedHashSet<GlobalPos> result = new LinkedHashSet<>(known);
        result.addAll(receivers());
        return List.copyOf(result);
    }

    public int receiverCount() { return receivers().size(); }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  SccWirelessTransmitterBlockEntity entity) {
        if (level.isClientSide || level.getGameTime() % HEARTBEAT_EVERY != 0L) return;
        long now = level.getGameTime();
        if (entity.live.entrySet().removeIf(entry -> now - entry.getValue() > HEARTBEAT_TIMEOUT)) {
            SccNetworkCache.invalidate(level);
        }
        entity.pruneLoadedReceivers();
        entity.bookKnownReceivers();
        BlockState current = level.getBlockState(pos);
        boolean active = entity.receiverCount() > 0;
        if (current.hasProperty(SccWirelessTransmitterBlock.ACTIVE)
                && current.getValue(SccWirelessTransmitterBlock.ACTIVE) != active) {
            BlockState updated = current.setValue(SccWirelessTransmitterBlock.ACTIVE, active);
            if (entity.sccHost() != null && entity.sccHostSide() != null) {
                entity.sccHost().updatePartState(entity.sccHostSide(), updated);
            } else {
                level.setBlock(pos, updated, 2);
            }
        }
    }

    private void bookKnownReceivers() {
        if (bookedKnown || level == null || level.isClientSide || level.getServer() == null) return;
        bookedKnown = true;
        SccWirelessDirectory directory = SccWirelessDirectory.get(level.getServer());
        GlobalPos transmitter = GlobalPos.of(level.dimension(), worldPosition);
        for (GlobalPos endpoint : known) {
            directory.noteKnown(endpoint.dimension(), endpoint.pos(), transmitter);
        }
    }

    private void pruneLoadedReceivers() {
        if (level == null || level.getServer() == null || known.isEmpty()) return;
        boolean changed = known.removeIf(endpoint -> {
            if (live.containsKey(endpoint)) return false;
            ServerLevel target = level.getServer().getLevel(endpoint.dimension());
            if (target == null) return true;
            if (!target.hasChunkAt(endpoint.pos())) return false;
            BlockEntity blockEntity = target.getBlockEntity(endpoint.pos());
            return !(blockEntity instanceof SccWirelessReceiverBlockEntity receiver
                    && worldPosition.equals(receiver.linkPos())
                    && level.dimension().equals(receiver.linkDimension()));
        });
        if (changed) setChanged();
    }

    @Override public void setRemoved() {
        Level current = level;
        super.setRemoved();
        if (current != null) SccNetworkCache.invalidate(current);
    }

    private void changedAndInvalidate() {
        setChanged();
        if (level != null) SccNetworkCache.invalidate(level);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("range", rangeUpgrades);
        tag.putBoolean("dimensional", dimensional);
        tag.putBoolean("infinite", infinite);
        if (owner != null) tag.putUUID("placedBy", owner);
        ListTag list = new ListTag();
        for (GlobalPos endpoint : known) {
            CompoundTag entry = new CompoundTag();
            entry.putString("dim", endpoint.dimension().location().toString());
            entry.putLong("pos", endpoint.pos().asLong());
            list.add(entry);
        }
        if (!list.isEmpty()) tag.put("known", list);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        rangeUpgrades = Math.max(0, Math.min(MAX_RANGE_UPGRADES, tag.getInt("range")));
        dimensional = tag.getBoolean("dimensional");
        infinite = tag.getBoolean("infinite");
        owner = tag.hasUUID("placedBy") ? tag.getUUID("placedBy") : null;
        known.clear();
        ListTag list = tag.getList("known", 10);
        for (int i = 0; i < list.size() && known.size() < MAX_KNOWN_RECEIVERS; i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceKey<Level> dimension = WirelessLink.dimFromName(entry.getString("dim"));
            if (dimension != null) known.add(GlobalPos.of(dimension, BlockPos.of(entry.getLong("pos"))));
        }
        live.clear();
    }
}
