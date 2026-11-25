package de.scc.calculator.network;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.BlockBehaviour.Properties;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Cable-mounted six-way panel variant of the SCC Redstone Emitter. */
public final class SccWallRedstoneEmitterBlock extends SccRedstoneEmitterBlock {
    public static final MapCodec<SccWallRedstoneEmitterBlock> CODEC = simpleCodec(SccWallRedstoneEmitterBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;

    public SccWallRedstoneEmitterBlock(Properties properties) {
        super(properties);
        registerDefaultState(SccPanelProps.defaults(defaultBlockState().setValue(FACING, Direction.NORTH)));
    }

    @Override protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        SccPanelProps.add(builder, true);
        builder.add(FACING);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace();
        BlockState state = defaultBlockState().setValue(FACING, facing)
                .setValue(WATERLOGGED, context.getLevel().getFluidState(context.getClickedPos()).getType()
                        == net.minecraft.world.level.material.Fluids.WATER);
        for (Direction direction : Direction.values()) {
            state = state.setValue(propertyFor(direction),
                    SccCableBusBlock.hasNetworkPort(context.getLevel(),
                            context.getClickedPos().relative(direction), direction.getOpposite()));
        }
        return state;
    }

    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
                                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        return super.updateShape(state, direction, neighbor, level, pos, neighborPos);
    }

    @Override public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos,
                                          net.minecraft.world.phys.shapes.CollisionContext context) {
        return SccWallPanel.shape(state);
    }

    @Override protected VoxelShape getOcclusionShape(BlockState state, BlockGetter world, BlockPos pos) {
        return SccFacadeShell.occlusion(state, Shapes.empty());
    }

    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = new ArrayList<>(super.getDrops(state, params));
        BlockEntity entity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (entity instanceof SccNetworkBlockEntity network && network.onCable()) {
            drops.add(new ItemStack(de.scc.calculator.registry.SccNetworkBlocks.CABLE.get()));
        }
        return drops;
    }
}
