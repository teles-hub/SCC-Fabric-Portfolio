package de.scc.calculator.network;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import de.scc.calculator.platform.SccMachineAdapters;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.storage.loot.LootParams;
import java.util.ArrayList;
import java.util.List;

/** Basic six-way SCC cable. It connects adjacent SCC network nodes only. */
public final class SccCableBlock extends Block implements EntityBlock, SccFacadeFaces {
    public static final MapCodec<SccCableBlock> CODEC = simpleCodec(SccCableBlock::new);
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");
    public static final BooleanProperty COVERED = SccFacadeShell.COVERED;

    private static final VoxelShape CORE = box(5, 5, 5, 11, 11, 11);
    private static final VoxelShape DOWN_ARM = box(5, 0, 5, 11, 5, 11);
    private static final VoxelShape UP_ARM = box(5, 11, 5, 11, 16, 11);
    private static final VoxelShape NORTH_ARM = box(5, 5, 0, 11, 11, 5);
    private static final VoxelShape SOUTH_ARM = box(5, 5, 11, 11, 11, 16);
    private static final VoxelShape WEST_ARM = box(0, 5, 5, 5, 11, 11);
    private static final VoxelShape EAST_ARM = box(11, 5, 5, 16, 11, 11);
    private static final Direction[] DIRECTIONS = {
            Direction.DOWN, Direction.UP, Direction.NORTH,
            Direction.SOUTH, Direction.WEST, Direction.EAST
    };

    public SccCableBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState()
                .setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false)
                .setValue(WEST, false).setValue(UP, false).setValue(DOWN, false).setValue(COVERED, false));
    }

    public static BlockBehaviour.Properties deviceProperties() {
        return BlockBehaviour.Properties.of().strength(0.6F, 1.5F).sound(SoundType.METAL).noOcclusion();
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN, COVERED);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        for (Direction direction : DIRECTIONS) {
            state = state.setValue(propertyFor(direction), isNetworkBlock(
                    context.getLevel(), context.getClickedPos().relative(direction), direction.getOpposite()));
        }
        return state;
    }

    @Override
    protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                     LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        BlockState updated = state.setValue(propertyFor(direction), isNetworkBlock(level, neighborPos, direction.getOpposite()));
        if (level instanceof Level world) SccNetworkCache.invalidate(world);
        return super.updateShape(updated, direction, neighborState, level, pos, neighborPos);
    }

    @Override
    public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        SccNetworkCache.invalidate(level);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) SccNetworkCache.invalidate(level);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override protected List<net.minecraft.world.item.ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<net.minecraft.world.item.ItemStack> drops = new ArrayList<>(super.getDrops(state, params));
        SccFacadeShell.addDrops(drops, params);
        return drops;
    }

    @Override protected int getLightBlock(BlockState state, net.minecraft.world.level.BlockGetter world, BlockPos pos) {
        return SccFacadeShell.lightBlock(state, super.getLightBlock(state, world, pos));
    }

    @Override protected boolean propagatesSkylightDown(BlockState state, net.minecraft.world.level.BlockGetter world, BlockPos pos) {
        return SccFacadeShell.skylight(state, super.propagatesSkylightDown(state, world, pos));
    }

    @Override protected VoxelShape getOcclusionShape(BlockState state, net.minecraft.world.level.BlockGetter world, BlockPos pos) {
        return SccFacadeShell.occlusion(state, super.getOcclusionShape(state, world, pos));
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                           Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (player.isShiftKeyDown() && level.getBlockEntity(pos) instanceof SccNetworkBlockEntity network
                && network.facade() != null) {
            Block.popResource(level, pos, SccFacadeItem.of(network.facade()));
            network.setFacade(null);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SccCableBlockEntity(pos, state);
    }

    @Override
    public VoxelShape getShape(BlockState state, net.minecraft.world.level.BlockGetter world,
                               BlockPos pos, net.minecraft.world.phys.shapes.CollisionContext context) {
        VoxelShape shape = CORE;
        for (Direction direction : DIRECTIONS) {
            if (state.getValue(propertyFor(direction))) shape = Shapes.or(shape, armFor(direction));
        }
        return SccFacadeShell.shape(state, shape);
    }

    private static VoxelShape armFor(Direction direction) {
        return switch (direction) {
            case DOWN -> DOWN_ARM;
            case UP -> UP_ARM;
            case NORTH -> NORTH_ARM;
            case SOUTH -> SOUTH_ARM;
            case WEST -> WEST_ARM;
            case EAST -> EAST_ARM;
        };
    }

    @Override public BlockState rotate(BlockState state, Rotation rotation) {
        BlockState result = state;
        for (Direction direction : Direction.values()) {
            result = result.setValue(propertyFor(rotation.rotate(direction)), state.getValue(propertyFor(direction)));
        }
        return result;
    }

    @Override public BlockState mirror(BlockState state, Mirror mirror) {
        BlockState result = state;
        for (Direction direction : Direction.values()) {
            Direction mirrored = mirror.getRotation(direction).rotate(direction);
            result = result.setValue(propertyFor(mirrored), state.getValue(propertyFor(direction)));
        }
        return result;
    }

    public static BooleanProperty propertyFor(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH;
            case EAST -> EAST;
            case SOUTH -> SOUTH;
            case WEST -> WEST;
            case UP -> UP;
            case DOWN -> DOWN;
        };
    }

    private static boolean isNetworkBlock(LevelAccessor level, BlockPos pos, Direction faceTowardCable) {
        if (!level.hasChunkAt(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof SccWirelessTransmitterBlock
                || state.getBlock() instanceof SccWirelessReceiverBlock) return true;
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof NetworkNode node) return node.canConnect(faceTowardCable);
        return level instanceof Level world && entity != null
                && SccMachineAdapters.machineAlias(world, pos) != null;
    }
}
