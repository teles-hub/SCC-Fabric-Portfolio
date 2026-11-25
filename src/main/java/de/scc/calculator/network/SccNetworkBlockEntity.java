package de.scc.calculator.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Common Fabric base for SCC network parts. It centralizes the hosted-part
 * contract and delegates dirty-state propagation to the multipart host.
 *
 * <p>The class deliberately does not emulate NeoForge capabilities or rendering
 * hooks. Those contracts are provided by Fabric Transfer API adapters and the
 * host's own synchronization path.</p>
 */
public abstract class SccNetworkBlockEntity extends BlockEntity implements SccHostedNetworkPart {
    @Nullable private SccCableBusBlockEntity sccHost;
    @Nullable private Direction sccHostSide;
    private boolean onCable;
    @Nullable private ResourceLocation facadeId;
    @Nullable private Block facadeBlock;

    protected SccNetworkBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    @Override public final SccCableBusBlockEntity sccHost() { return sccHost; }
    @Override public final Direction sccHostSide() { return sccHostSide; }

    @Override
    public void sccAttachTo(@Nullable SccCableBusBlockEntity host, @Nullable Direction side) {
        if ((host == null) != (side == null)) {
            throw new IllegalArgumentException("Hosted part requires both host and face, or neither");
        }
        if (host != null && host.getLevel() != null && getLevel() != null && host.getLevel() != getLevel()) {
            throw new IllegalArgumentException("Cannot attach a network part across levels");
        }
        sccHost = host;
        sccHostSide = side;
    }

    @Override
    public void setChanged() {
        if (sccHost != null) sccHost.syncParts();
        else super.setChanged();
    }

    @Nullable public final Block facade() { return facadeBlock; }

    public void setFacade(@Nullable Block block) {
        if (sccHost != null) {
            sccHost.setFacade(block);
            return;
        }
        if (facadeBlock == block) return;
        facadeBlock = block;
        facadeId = block == null ? null : BuiltInRegistries.BLOCK.getKey(block);
        if (level != null && getBlockState().hasProperty(SccFacadeShell.COVERED)) {
            BlockState state = getBlockState();
            boolean covered = block != null;
            if (state.getValue(SccFacadeShell.COVERED) != covered) {
                level.setBlock(worldPosition, state.setValue(SccFacadeShell.COVERED, covered), 3);
            }
        }
        sync();
    }

    /** Original SCC flag indicating that this part belongs to a player-owned cable path. */
    public final boolean onCable() { return onCable; }

    public void setOnCable(boolean value) {
        if (onCable == value) return;
        onCable = value;
        sync();
    }

    /** Synchronizes this part through its host, or through the world when standalone. */
    protected void sync() {
        if (sccHost != null) {
            sccHost.syncParts();
        } else if (level != null && !level.isClientSide) {
            setChanged();
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 2);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        if (onCable) tag.putBoolean("OnCable", true);
        if (facadeId != null) tag.putString("Facade", facadeId.toString());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        onCable = tag.getBoolean("OnCable");
        facadeId = ResourceLocation.tryParse(tag.getString("Facade"));
        facadeBlock = facadeId == null ? null : BuiltInRegistries.BLOCK.get(facadeId);
        if (facadeBlock == Blocks.AIR) facadeBlock = null;
    }

    /** Parts conduct by default; endpoint-specific ports may override this. */
    public boolean conducts() { return true; }

    @Override
    public void setLevel(Level level) {
        super.setLevel(level);
        if (level != null && getBlockState().hasProperty(SccFacadeShell.COVERED)) {
            BlockState state = getBlockState();
            boolean covered = facadeBlock != null;
            if (state.getValue(SccFacadeShell.COVERED) != covered) {
                level.setBlock(worldPosition, state.setValue(SccFacadeShell.COVERED, covered), 3);
            }
        }
    }

    @Override
    public void setRemoved() {
        Level current = level;
        SccCableBusBlockEntity previousHost = sccHost;
        super.setRemoved();
        if (previousHost != null) previousHost.syncParts();
        if (current != null) SccNetworkCache.invalidate(current);
    }
}
