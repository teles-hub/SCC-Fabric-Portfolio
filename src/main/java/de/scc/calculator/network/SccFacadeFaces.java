package de.scc.calculator.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Fabric replacement for the original NeoForge IBlockExtension facade hooks.
 * Rendering is supplied by SCC block-entity renderers; these helpers preserve
 * the original face-culling and appearance decisions for Fabric adapters.
 */
public interface SccFacadeFaces {
    default boolean hidesNeighborFace(BlockGetter world, BlockPos pos, BlockState state,
                                      BlockState neighborState, Direction side) {
        if (!(world.getBlockEntity(pos) instanceof SccNetworkBlockEntity entity)) return false;
        Block facade = entity.facade();
        if (facade == null) return false;
        BlockState facadeState = facade.defaultBlockState();
        BlockPos neighborPos = pos.relative(side);
        if (world.getBlockEntity(neighborPos) instanceof SccNetworkBlockEntity neighbor
                && neighbor.facade() != null) {
            neighborState = neighbor.facade().defaultBlockState();
        }
        try {
            if (facadeState.canOcclude()
                    && Block.isFaceFull(facadeState.getFaceOcclusionShape(world, pos, side), side)) return true;
        } catch (RuntimeException ignored) { }
        return neighborState.skipRendering(facadeState, side.getOpposite());
    }

    default BlockState facadeAppearance(BlockState state, BlockGetter world, BlockPos pos) {
        return SccFacadeShell.appearance(state, world, pos);
    }
}
