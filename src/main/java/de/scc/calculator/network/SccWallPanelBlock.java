package de.scc.calculator.network;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** Shared six-direction, waterloggable wall-panel geometry for SCC terminals. */
public abstract class SccWallPanelBlock extends Block implements EntityBlock, SimpleWaterloggedBlock {
    public static final BooleanProperty NECK = BooleanProperty.create("neck");
    private static final VoxelShape[] SHAPES = {
            Block.box(2, 2, 0, 14, 14, 2),   // north
            Block.box(2, 2, 14, 14, 14, 16), // south
            Block.box(0, 2, 2, 2, 14, 14),   // west
            Block.box(14, 2, 2, 16, 14, 14), // east
            Block.box(2, 14, 2, 14, 16, 14), // up
            Block.box(2, 0, 2, 14, 2, 14)   // down
    };

    protected SccWallPanelBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any()
                .setValue(BlockStateProperties.FACING, Direction.NORTH)
                .setValue(NECK, false)
                .setValue(BlockStateProperties.WATERLOGGED, false));
    }

    @Override
    protected abstract MapCodec<? extends Block> codec();

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(BlockStateProperties.FACING, NECK, BlockStateProperties.WATERLOGGED);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace();
        BlockPos supportPos = context.getClickedPos().relative(facing.getOpposite());
        BlockState support = context.getLevel().getBlockState(supportPos);
        if (!support.isFaceSturdy(context.getLevel(), supportPos, facing)
                && !(support.getBlock() instanceof SccCableBlock)
                && !(context.getLevel().getBlockEntity(supportPos) instanceof NetworkNode)) {
            return null;
        }
        FluidState fluid = context.getLevel().getFluidState(context.getClickedPos());
        return defaultBlockState()
                .setValue(BlockStateProperties.FACING, facing)
                .setValue(NECK, support.getBlock() instanceof SccCableBlock)
                .setValue(BlockStateProperties.WATERLOGGED, fluid.getType() == Fluids.WATER);
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(BlockStateProperties.WATERLOGGED)) {
            level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        }
        if (direction == state.getValue(BlockStateProperties.FACING).getOpposite()) {
            if (!canSurvive(state, level, pos)) return Blocks.AIR.defaultBlockState();
            boolean neck = neighborState.getBlock() instanceof SccCableBlock;
            if (state.getValue(NECK) != neck) state = state.setValue(NECK, neck);
        }
        return super.updateShape(state, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        Direction facing = state.getValue(BlockStateProperties.FACING);
        BlockPos supportPos = pos.relative(facing.getOpposite());
        BlockState support = level.getBlockState(supportPos);
        return support.isFaceSturdy(level, supportPos, facing)
                || support.getBlock() instanceof SccCableBlock
                || level.getBlockEntity(supportPos) instanceof NetworkNode;
    }

    @Override
    protected FluidState getFluidState(BlockState state) {
        return state.getValue(BlockStateProperties.WATERLOGGED)
                ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                  CollisionContext context) {
        return switch (state.getValue(BlockStateProperties.FACING)) {
            case NORTH -> SHAPES[0];
            case SOUTH -> SHAPES[1];
            case WEST -> SHAPES[2];
            case EAST -> SHAPES[3];
            case UP -> SHAPES[4];
            case DOWN -> SHAPES[5];
        };
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos,
                        BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        SccNetworkCache.invalidate(level);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) SccNetworkCache.invalidate(level);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Nullable
    @Override
    public abstract BlockEntity newBlockEntity(BlockPos pos, BlockState state);
}
