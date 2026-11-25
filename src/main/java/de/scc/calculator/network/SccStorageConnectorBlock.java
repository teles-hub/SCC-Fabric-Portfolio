package de.scc.calculator.network;

import com.mojang.serialization.MapCodec;
import de.scc.calculator.platform.registry.PlatformMenuHelper;
import de.scc.calculator.registry.SccBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/** Directional external-storage bridge with Fabric Transfer API endpoints. */
public final class SccStorageConnectorBlock extends Block implements EntityBlock, SimpleWaterloggedBlock, SccFacadeFaces {
    public static final MapCodec<SccStorageConnectorBlock> CODEC = simpleCodec(SccStorageConnectorBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.FACING;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    public static final BooleanProperty COVERED = SccFacadeShell.COVERED;
    /** Original connector model state retained for byte-for-byte blockstate compatibility. */
    public static final BooleanProperty NECK = BooleanProperty.create("neck");
    public static final BooleanProperty NORTH = SccCableBlock.NORTH;
    public static final BooleanProperty EAST = SccCableBlock.EAST;
    public static final BooleanProperty SOUTH = SccCableBlock.SOUTH;
    public static final BooleanProperty WEST = SccCableBlock.WEST;
    public static final BooleanProperty UP = SccCableBlock.UP;
    public static final BooleanProperty DOWN = SccCableBlock.DOWN;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final VoxelShape BODY = Block.box(3, 3, 3, 13, 13, 13);
    private static final VoxelShape[] ARMS = {
            Block.box(6, 0, 6, 10, 3, 10), Block.box(6, 13, 6, 10, 16, 10),
            Block.box(6, 6, 0, 10, 10, 3), Block.box(6, 6, 13, 10, 10, 16),
            Block.box(0, 6, 6, 3, 10, 10), Block.box(13, 6, 6, 16, 10, 10)
    };

    public SccStorageConnectorBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(FACING, Direction.NORTH).setValue(WATERLOGGED, false)
                .setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false)
                .setValue(WEST, false).setValue(UP, false).setValue(DOWN, false).setValue(COVERED, false)
                .setValue(NECK, true));
    }

    public static BlockBehaviour.Properties deviceProperties() {
        return BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(SoundType.METAL).noOcclusion();
    }

    @Override protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, WATERLOGGED, NORTH, EAST, SOUTH, WEST, UP, DOWN, COVERED, NECK);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        Direction facing = context.getClickedFace().getOpposite();
        BlockState state = defaultBlockState().setValue(FACING, facing).setValue(WATERLOGGED,
                context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER);
        for (Direction direction : DIRECTIONS) {
            state = state.setValue(propertyFor(direction), direction != facing
                    && hasNeighborPort(context.getLevel(), context.getClickedPos().relative(direction), direction.getOpposite()));
        }
        return state;
    }

    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                                 LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        boolean linked = direction != state.getValue(FACING)
                && level instanceof Level world
                && hasNeighborPort(world, neighborPos, direction.getOpposite());
        BlockState updated = state.setValue(propertyFor(direction), linked);
        if (level instanceof Level world) SccNetworkCache.invalidate(world);
        return super.updateShape(updated, direction, neighborState, level, pos, neighborPos);
    }

    private static boolean hasNeighborPort(Level level, BlockPos pos, Direction faceTowardConnector) {
        return SccCableBusBlock.hasNetworkPort(level, pos, faceTowardConnector);
    }
    @Override public BlockState rotate(BlockState state, Rotation rotation) {
        BlockState result = state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
        for (Direction direction : DIRECTIONS) {
            result = result.setValue(propertyFor(rotation.rotate(direction)), state.getValue(propertyFor(direction)));
        }
        return result;
    }

    @Override public BlockState mirror(BlockState state, Mirror mirror) {
        BlockState result = state.setValue(FACING, mirror.getRotation(state.getValue(FACING)).rotate(state.getValue(FACING)));
        for (Direction direction : DIRECTIONS) {
            Direction mirrored = mirror.getRotation(direction).rotate(direction);
            result = result.setValue(propertyFor(mirrored), state.getValue(propertyFor(direction)));
        }
        return result;
    }

    @Override public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        SccNetworkCache.invalidate(level);
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) SccNetworkCache.invalidate(level);
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                                      @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof SccStorageConnectorBlockEntity connector) {
            connector.setPlacedBy(player.getUUID());
        }
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
        if (level.getBlockEntity(pos) instanceof SccStorageConnectorBlockEntity
                && player instanceof ServerPlayer serverPlayer) {
            SimpleMenuProvider provider = new SimpleMenuProvider(
                    (containerId, inventory, user) -> new SccStorageConnectorMenu(containerId, inventory, pos),
                    net.minecraft.network.chat.Component.translatable("block.scc.storage_connector"));
            PlatformMenuHelper.openBlockMenu(serverPlayer, provider, pos);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SccStorageConnectorBlockEntity(pos, state);
    }

    @Nullable @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != SccBlockEntities.SCC_STORAGE_CONNECTOR.get()) return null;
        return (world, pos, blockState, entity) -> {
            if (entity instanceof SccStorageConnectorBlockEntity connector) {
                SccStorageConnectorBlockEntity.serverTick(world, pos, blockState, connector);
            }
        };
    }

    @Override protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = new java.util.ArrayList<>(super.getDrops(state, params));
        SccFacadeShell.addDrops(drops, params);
        BlockEntity entity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (!(entity instanceof SccStorageConnectorBlockEntity connector)) return drops;
        Level level = connector.getLevel();
        if (level == null) return drops;
        CompoundTag data = connector.saveWithoutMetadata(level.registryAccess());
        for (ItemStack stack : drops) {
            if (!stack.isEmpty() && stack.is(asItem())) {
                BlockItem.setBlockEntityData(stack, SccBlockEntities.SCC_STORAGE_CONNECTOR.get(), data);
            }
        }
        return drops;
    }

    @Override public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos,
                                          net.minecraft.world.phys.shapes.CollisionContext context) {
        VoxelShape shape = BODY;
        for (Direction direction : DIRECTIONS) {
            if (direction != state.getValue(FACING) && state.getValue(propertyFor(direction))) {
                shape = Shapes.or(shape, ARMS[direction.get3DDataValue()]);
            }
        }
        return SccFacadeShell.shape(state, shape);
    }

    @Override protected int getLightBlock(BlockState state, BlockGetter world, BlockPos pos) {
        return SccFacadeShell.lightBlock(state, super.getLightBlock(state, world, pos));
    }

    @Override protected boolean propagatesSkylightDown(BlockState state, BlockGetter world, BlockPos pos) {
        return SccFacadeShell.skylight(state, super.propagatesSkylightDown(state, world, pos));
    }

    @Override protected VoxelShape getOcclusionShape(BlockState state, BlockGetter world, BlockPos pos) {
        return SccFacadeShell.occlusion(state, super.getOcclusionShape(state, world, pos));
    }

    public static BooleanProperty propertyFor(Direction direction) {
        return SccCableBlock.propertyFor(direction);
    }
}
