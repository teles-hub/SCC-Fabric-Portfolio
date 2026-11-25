package de.scc.calculator.network;

import de.scc.calculator.registry.SccBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/** Fabric receiver endpoint: link state, heartbeat, hosted parts and global directory booking. */
public final class SccWirelessReceiverBlockEntity extends SccNetworkBlockEntity {
    private GlobalPos link;
    private State state = State.UNLINKED;
    private UUID owner;
    private boolean booked;

    public SccWirelessReceiverBlockEntity(BlockPos pos, BlockState blockState) {
        super(SccBlockEntities.SCC_WIRELESS_RECEIVER.get(), pos, blockState);
    }

    @Override
    public boolean canConnect(Direction face) {
        if (face == null || level == null || !level.hasChunkAt(worldPosition)) return false;
        BlockState blockState = getBlockState();
        return blockState.getBlock() instanceof SccWirelessReceiverBlock
                && blockState.getValue(SccWirelessReceiverBlock.propertyFor(face));
    }

    public boolean linked() { return link != null; }
    public BlockPos linkPos() { return link == null ? null : link.pos(); }
    public ResourceKey<Level> linkDimension() { return link == null ? null : link.dimension(); }
    public State state() { return state; }
    public UUID owner() { return owner; }

    public void setOwner(UUID owner) {
        if (java.util.Objects.equals(this.owner, owner)) return;
        this.owner = owner;
        sync();
        bookLink();
    }

    public void link(BlockPos transmitterPos, ResourceKey<Level> dimension) {
        if (transmitterPos == null || dimension == null) return;
        link = GlobalPos.of(dimension, transmitterPos.immutable());
        setState(State.ASLEEP);
        sync();
        bookLink();
        invalidateNetwork();
    }

    public void unlink() {
        link = null;
        setState(State.UNLINKED);
        sync();
        setActiveState(false);
        bookLink();
        invalidateNetwork();
    }

    private void setState(State next) {
        if (state == next) return;
        state = next;
        sync();
    }

    private void setActiveState(boolean active) {
        if (level == null) return;
        BlockState current = level.getBlockState(worldPosition);
        if (!current.hasProperty(SccWirelessReceiverBlock.ACTIVE)
                || current.getValue(SccWirelessReceiverBlock.ACTIVE) == active) return;
        BlockState updated = current.setValue(SccWirelessReceiverBlock.ACTIVE, active);
        if (sccHost() != null && sccHostSide() != null) {
            sccHost().updatePartState(sccHostSide(), updated);
        } else {
            level.setBlock(worldPosition, updated, 2);
        }
    }

    private void bookLink() {
        if (level == null || level.isClientSide || level.getServer() == null) return;
        SccWirelessDirectory.get(level.getServer()).note(level.dimension(), worldPosition, owner, link);
    }

    private void bookTick() {
        if (level == null || level.isClientSide || level.getServer() == null) return;
        SccWirelessDirectory directory = SccWirelessDirectory.get(level.getServer());
        if (!booked) {
            booked = true;
            GlobalPos pending = directory.takePending(level.dimension(), worldPosition);
            if (pending != null) link(pending.pos(), pending.dimension());
        }
        bookLink();
    }

    public SccWirelessTransmitterBlockEntity transmitter() {
        if (level == null || level.isClientSide || link == null) {
            if (link == null) setState(State.UNLINKED);
            return null;
        }
        MinecraftServer server = level.getServer();
        if (server == null) return null;
        ServerLevel target = server.getLevel(link.dimension());
        if (target == null || !target.hasChunkAt(link.pos())) {
            setState(State.ASLEEP);
            return null;
        }
        BlockEntity entity = target.getBlockEntity(link.pos());
        if (!(entity instanceof SccWirelessTransmitterBlockEntity transmitter)) {
            setState(State.GONE);
            return null;
        }
        if (!transmitter.reaches(level.dimension(), worldPosition)) {
            setState(State.OUT_OF_RANGE);
            return null;
        }
        setState(State.LINKED);
        return transmitter;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  SccWirelessReceiverBlockEntity entity) {
        if (level.isClientSide || level.getGameTime() % SccWirelessTransmitterBlockEntity.HEARTBEAT_EVERY != 0L) return;
        entity.bookTick();
        SccWirelessTransmitterBlockEntity transmitter = entity.transmitter();
        if (transmitter != null) transmitter.heartbeat(level.dimension(), pos);
        entity.setActiveState(transmitter != null);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override public void setRemoved() {
        Level current = level;
        super.setRemoved();
        if (current != null) SccNetworkCache.invalidate(current);
    }

    private void invalidateNetwork() {
        if (level != null) SccNetworkCache.invalidate(level);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (link != null) {
            tag.putLong("scc_link", link.pos().asLong());
            tag.putString("scc_link_dim", link.dimension().location().toString());
        }
        if (owner != null) tag.putUUID("owner", owner);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        ResourceKey<Level> dimension = WirelessLink.dimFromName(
                tag.contains("scc_link_dim") ? tag.getString("scc_link_dim") : null);
        link = tag.contains("scc_link") && dimension != null
                ? GlobalPos.of(dimension, BlockPos.of(tag.getLong("scc_link"))) : null;
        owner = tag.hasUUID("owner") ? tag.getUUID("owner") : null;
        state = link == null ? State.UNLINKED : State.ASLEEP;
    }

    public enum State { UNLINKED, ASLEEP, GONE, OUT_OF_RANGE, LINKED }
}
