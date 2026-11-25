package de.scc.calculator.network;

import de.scc.calculator.registry.SccBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Multipart host with six independent face slots. Parts are reconstructed from
 * their registered BlockEntityType and saved BlockState; no NeoForge capability
 * or model API is used.
 */
public final class SccCableBusBlockEntity extends SccNetworkBlockEntity {
    private final BlockEntity[] parts = new BlockEntity[6];
    private final BlockState[] partStates = new BlockState[6];
    private Direction lastMenuSide = Direction.NORTH;
    @SuppressWarnings("unchecked")
    private final BlockEntityTicker<BlockEntity>[] tickers = (BlockEntityTicker<BlockEntity>[]) new BlockEntityTicker<?>[6];

    public SccCableBusBlockEntity(BlockPos pos, BlockState state) {
        super(SccBlockEntities.SCC_CABLE_BUS.get(), pos, state);
    }

    @Override public BlockPos getBlockPos() { return worldPosition; }

    @Nullable public BlockEntity part(Direction side) {
        return side == null ? null : parts[side.get3DDataValue()];
    }

    @Nullable public BlockState partState(Direction side) {
        return side == null ? null : partStates[side.get3DDataValue()];
    }

    public List<BlockEntity> parts() {
        List<BlockEntity> result = new ArrayList<>(6);
        for (BlockEntity part : parts) if (part != null) result.add(part);
        return List.copyOf(result);
    }

    public int partCount() {
        int count = 0;
        for (BlockEntity part : parts) if (part != null) count++;
        return count;
    }

    public boolean hasCable() {
        return getBlockState().hasProperty(SccCableBusBlock.CABLE)
                && getBlockState().getValue(SccCableBusBlock.CABLE);
    }

    public Direction lastMenuSide() { return lastMenuSide; }

    public void setLastMenuSide(Direction side) {
        if (side == null || lastMenuSide == side) return;
        lastMenuSide = side;
        changedParts();
    }

    @Override public boolean canConnect(Direction face) {
        if (face == null || level == null || !level.hasChunkAt(worldPosition) || !hasCable()) return false;
        BlockState state = getBlockState();
        return state.getValue(SccCableBusBlock.propertyFor(face));
    }

    public boolean conducts() { return hasCable(); }

    public synchronized boolean addPart(Direction side, BlockState state, BlockEntity part) {
        if (side == null || state == null || part == null || level == null || level.isClientSide) return false;
        if (!(part instanceof SccHostedNetworkPart hosted) || !(part instanceof NetworkNode)) return false;
        int slot = side.get3DDataValue();
        // A hosted BlockEntity occupies exactly one face slot. Reject an
        // already-attached instance even when it belongs to this same host;
        // otherwise one mutable part could be ticked and serialized twice.
        if (parts[slot] != null || hosted.sccHost() != null
                || !part.getBlockPos().equals(worldPosition)) return false;
        part.setBlockState(state);
        parts[slot] = part;
        partStates[slot] = state;
        tickers[slot] = null;
        hosted.sccAttachTo(this, side);
        part.setLevel(level);
        part.clearRemoved();
        refreshPort(side);
        changedParts();
        return true;
    }

    @Nullable public synchronized BlockEntity removePart(Direction side) {
        if (side == null) return null;
        int slot = side.get3DDataValue();
        BlockEntity part = parts[slot];
        if (part == null) return null;
        parts[slot] = null;
        partStates[slot] = null;
        tickers[slot] = null;
        if (part instanceof SccHostedNetworkPart hosted) hosted.sccAttachTo(null, null);
        part.setRemoved();
        refreshPort(side);
        changedParts();
        return part;
    }

    public void cableChanged() {
        for (int i = 0; i < 6; i++) {
            BlockEntity part = parts[i];
            if (part instanceof SccHostedNetworkPart hosted) {
                hosted.sccAttachTo(this, Direction.from3DDataValue(i));
            }
            tickers[i] = null;
            refreshPort(Direction.from3DDataValue(i));
        }
        changedParts();
    }

    private void refreshPort(Direction side) {
        if (level == null || side == null || !level.hasChunkAt(worldPosition.relative(side))) return;
        boolean linked = hasCable()
                && SccCableBusBlock.hasNetworkPort(level, worldPosition.relative(side), side.getOpposite());
        BlockState state = getBlockState();
        BooleanProperty property = SccCableBusBlock.propertyFor(side);
        if (state.getValue(property) != linked) {
            level.setBlock(worldPosition, state.setValue(property, linked), 3);
        }
    }

    /** Updates one hosted part's state without replacing the world host block. */
    public synchronized void updatePartState(Direction side, BlockState state) {
        if (side == null || state == null) return;
        int slot = side.get3DDataValue();
        BlockEntity part = parts[slot];
        if (part == null || state.getBlock() != partStates[slot].getBlock()) return;
        if (partStates[slot] == state) return;
        partStates[slot] = state;
        part.setBlockState(state);
        tickers[slot] = null;
        changedParts();
    }

    private void changedParts() {
        setChanged();
        if (level != null) {
            SccNetworkCache.invalidate(level);
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
            level.updateNeighbourForOutputSignal(worldPosition, state.getBlock());
        }
    }

    /** Public lifecycle bridge for hosted parts whose data changes outside a tick. */
    public void syncParts() { changedParts(); }

    public static void serverTick(Level level, BlockPos pos, BlockState state, SccCableBusBlockEntity bus) {
        if (level.isClientSide) return;
        for (int i = 0; i < 6; i++) {
            BlockEntity part = bus.parts[i];
            BlockState partState = bus.partStates[i];
            if (part == null || partState == null || part.isRemoved()) continue;
            BlockEntityTicker<BlockEntity> ticker = bus.tickers[i];
            if (ticker == null) {
                if (!(partState.getBlock() instanceof EntityBlock entityBlock)) continue;
                ticker = tickerFor(entityBlock, level, partState, part);
                if (ticker == null) continue;
                bus.tickers[i] = ticker;
            }
            ticker.tick(level, pos, partState, part);
        }
    }

    @Override public void setRemoved() {
        Level current = level;
        super.setRemoved();
        // A hosted part is not an independently registered world BlockEntity.
        // Detach it before forwarding the lifecycle callback so its setRemoved()
        // cannot dirty/synchronize a host that is itself being unloaded/removed.
        for (int i = 0; i < 6; i++) {
            BlockEntity part = parts[i];
            if (part == null) continue;
            if (part instanceof SccHostedNetworkPart hosted) hosted.sccAttachTo(null, null);
            part.setRemoved();
        }
        if (current != null) SccNetworkCache.invalidate(current);
    }

    @Override public void clearRemoved() {
        super.clearRemoved();
        for (int i = 0; i < 6; i++) {
            BlockEntity part = parts[i];
            if (part == null) continue;
            if (part instanceof SccHostedNetworkPart hosted) {
                hosted.sccAttachTo(this, Direction.from3DDataValue(i));
            }
            part.clearRemoved();
        }
    }

    @Override public void setLevel(Level level) {
        super.setLevel(level);
        for (BlockEntity part : parts) if (part != null) part.setLevel(level);
    }


    @SuppressWarnings({"rawtypes", "unchecked"})
    private static BlockEntityTicker<BlockEntity> tickerFor(EntityBlock block, Level level,
                                                               BlockState state, BlockEntity part) {
        return (BlockEntityTicker) block.getTicker(level, state, (net.minecraft.world.level.block.entity.BlockEntityType) part.getType());
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ListTag list = new ListTag();
        for (int i = 0; i < 6; i++) {
            BlockEntity part = parts[i];
            BlockState state = partStates[i];
            if (part == null || state == null) continue;
            ResourceLocation typeId = BlockEntityTypeId.key(part);
            if (typeId == null) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("Side", i);
            entry.put("State", NbtUtils.writeBlockState(state));
            CompoundTag data = part.saveWithoutMetadata(registries);
            data.putString("id", typeId.toString());
            entry.put("Data", data);
            list.add(entry);
        }
        tag.put("Parts", list);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        boolean[] loaded = new boolean[6];
        if (tag.contains("Parts", 9)) {
            ListTag list = tag.getList("Parts", 10);
            var blocks = registries.lookupOrThrow(Registries.BLOCK);
            for (int i = 0; i < list.size(); i++) {
                CompoundTag entry = list.getCompound(i);
                int side = entry.getInt("Side");
                if (side < 0 || side >= 6) continue;
                BlockState state = NbtUtils.readBlockState(blocks, entry.getCompound("State"));
                if (!(state.getBlock() instanceof EntityBlock)) continue;
                BlockEntity restored = BlockEntity.loadStatic(worldPosition, state, entry.getCompound("Data"), registries);
                if (!(restored instanceof SccHostedNetworkPart hosted)) continue;
                BlockEntity old = parts[side];
                if (old != null && old != restored) {
                    // Repeated/corrupt side entries must not leave the replaced
                    // instance attached to this host while it is being removed.
                    if (old instanceof SccHostedNetworkPart oldHosted) oldHosted.sccAttachTo(null, null);
                    old.setRemoved();
                }
                parts[side] = restored;
                partStates[side] = state;
                tickers[side] = null;
                hosted.sccAttachTo(this, Direction.from3DDataValue(side));
                if (level != null) restored.setLevel(level);
                loaded[side] = true;
            }
        }
        for (int i = 0; i < 6; i++) {
            if (!loaded[i] && parts[i] != null) {
                if (parts[i] instanceof SccHostedNetworkPart hosted) hosted.sccAttachTo(null, null);
                parts[i].setRemoved();
                parts[i] = null;
                partStates[i] = null;
                tickers[i] = null;
            }
        }
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Nullable @Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    /** Drops each hosted part through its own loot function, preserving its BE payload. */
    public void dropHostedParts() {
        if (!(level instanceof ServerLevel serverLevel)) return;
        boolean ownedCable = onCable();
        if (facade() != null) Block.popResource(level, worldPosition, SccFacadeItem.of(facade()));
        if (ownedCable) {
            Block.popResource(level, worldPosition, new ItemStack(de.scc.calculator.registry.SccNetworkBlocks.CABLE.get()));
        }
        for (int i = 0; i < 6; i++) {
            BlockEntity part = parts[i];
            BlockState state = partStates[i];
            if (part == null || state == null) continue;
            for (ItemStack drop : Block.getDrops(state, serverLevel, worldPosition, part, null, ItemStack.EMPTY)) {
                if (ownedCable && drop.is(de.scc.calculator.registry.SccNetworkBlocks.CABLE.get().asItem())) continue;
                Block.popResource(level, worldPosition, drop);
            }
            if (part instanceof SccHostedNetworkPart hosted) hosted.sccAttachTo(null, null);
            part.setRemoved();
            parts[i] = null;
            partStates[i] = null;
            tickers[i] = null;
        }
    }

    private static final class BlockEntityTypeId {
        private static ResourceLocation key(BlockEntity entity) {
            return net.minecraft.world.level.block.entity.BlockEntityType.getKey(entity.getType());
        }
    }
}
