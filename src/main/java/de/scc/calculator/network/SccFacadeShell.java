package de.scc.calculator.network;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.storage.loot.LootParams;

import java.util.List;

/** Fabric-side facade state and block-shape helpers ported from the original SCC shell. */
public final class SccFacadeShell {
    public static final BooleanProperty COVERED = BooleanProperty.create("covered");

    private SccFacadeShell() { }

    public static void add(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(COVERED);
    }

    public static BlockState off(BlockState state) {
        return state.hasProperty(COVERED) ? state.setValue(COVERED, false) : state;
    }

    public static boolean covered(BlockState state) {
        return state.hasProperty(COVERED) && state.getValue(COVERED);
    }

    public static net.minecraft.world.phys.shapes.VoxelShape shape(BlockState state,
            net.minecraft.world.phys.shapes.VoxelShape original) {
        return covered(state) ? net.minecraft.world.phys.shapes.Shapes.block() : original;
    }

    public static int lightBlock(BlockState state, int original) {
        return covered(state) ? 15 : original;
    }

    public static boolean skylight(BlockState state, boolean original) {
        return !covered(state) && original;
    }

    public static net.minecraft.world.phys.shapes.VoxelShape occlusion(BlockState state,
            net.minecraft.world.phys.shapes.VoxelShape original) {
        return covered(state) ? net.minecraft.world.phys.shapes.Shapes.block() : original;
    }

    public static BlockState appearance(BlockState state, BlockGetter world, BlockPos pos) {
        if (!covered(state) || !(world.getBlockEntity(pos) instanceof SccNetworkBlockEntity entity)) return state;
        Block facade = entity.facade();
        return facade == null ? state : facade.defaultBlockState();
    }

    public static void addDrops(List<ItemStack> drops, LootParams.Builder params) {
        if (params.getOptionalParameter(net.minecraft.world.level.storage.loot.parameters.LootContextParams.BLOCK_ENTITY)
                instanceof SccNetworkBlockEntity entity && entity.facade() != null) {
            drops.add(SccFacadeItem.of(entity.facade()));
        }
    }

    public static boolean canCover(Block block) {
        if (block == null || block instanceof net.minecraft.world.level.block.EntityBlock
                || block == de.scc.calculator.registry.SccNetworkBlocks.CABLE.get()) return false;
        BlockState state = block.defaultBlockState();
        if (!state.getFluidState().isEmpty()) return false;
        try {
            return Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
        } catch (RuntimeException exception) {
            return false;
        }
    }
}
