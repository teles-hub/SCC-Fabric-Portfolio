package de.scc.calculator.network;

import de.scc.calculator.registry.SccBlockEntities;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/** Lightweight network conduit node; cable geometry/state lives in SccCableBlock. */
public final class SccCableBlockEntity extends SccNetworkBlockEntity {
    public SccCableBlockEntity(BlockPos pos, BlockState state) {
        super(SccBlockEntities.SCC_CABLE.get(), pos, state);
    }

    @Override
    public BlockPos getBlockPos() {
        return worldPosition;
    }

    /**
     * A cable exposes only the faces represented by its current connection
     * arms. This keeps topology traversal aligned with the block's visible
     * adjacency and prevents a cable from bridging through a disconnected face.
     */
    @Override
    public boolean canConnect(Direction face) {
        if (face == null || level == null || !level.hasChunkAt(worldPosition)) return false;
        BlockState state = getBlockState();
        if (!(state.getBlock() instanceof SccCableBlock)) return false;
        return state.getValue(SccCableBlock.propertyFor(face));
    }

    @Override
    public void setRemoved() {
        Level current = level;
        super.setRemoved();
        if (current != null) SccNetworkCache.invalidate(current);
    }
}
