package de.scc.calculator.network;

import com.mojang.serialization.MapCodec;
import de.scc.calculator.platform.registry.PlatformMenuHelper;
import de.scc.calculator.registry.SccBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import net.minecraft.world.level.storage.loot.LootParams;
import java.util.ArrayList;
import java.util.List;

/** Fabric port of the original SCC stock-threshold redstone emitter. */
public class SccRedstoneEmitterBlock extends Block implements EntityBlock, SimpleWaterloggedBlock, SccFacadeFaces {
    public static final MapCodec<SccRedstoneEmitterBlock> CODEC = simpleCodec(SccRedstoneEmitterBlock::new);
    public static final BooleanProperty LIT = BooleanProperty.create("lit");
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");
    public static final BooleanProperty COVERED = SccFacadeShell.COVERED;
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final VoxelShape BODY = Block.box(3, 3, 3, 13, 13, 13);

    public SccRedstoneEmitterBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(WATERLOGGED, false).setValue(LIT, false)
                .setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false)
                .setValue(WEST, false).setValue(UP, false).setValue(DOWN, false).setValue(COVERED, false));
    }

    public static BlockBehaviour.Properties deviceProperties() {
        return BlockBehaviour.Properties.of().strength(0.6F, 1.5F).sound(SoundType.METAL).noOcclusion();
    }

    @Override protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(WATERLOGGED, LIT, NORTH, EAST, SOUTH, WEST, UP, DOWN, COVERED);
    }

    @Override public BlockState getStateForPlacement(net.minecraft.world.item.context.BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(WATERLOGGED,
                context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER);
        for (Direction direction : DIRECTIONS) {
            state = state.setValue(propertyFor(direction),
                    SccCableBusBlock.hasNetworkPort(context.getLevel(),
                            context.getClickedPos().relative(direction), direction.getOpposite()));
        }
        return state;
    }

    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighbor,
                                                  LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        boolean linked = SccCableBusBlock.hasNetworkPort(level, neighborPos, direction.getOpposite());
        BlockState updated = state.setValue(propertyFor(direction), linked);
        if (level instanceof Level world) SccNetworkCache.invalidate(world);
        return super.updateShape(updated, direction, neighbor, level, pos, neighborPos);
    }

    @Override public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        SccNetworkCache.invalidate(level);
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())) SccNetworkCache.invalidate(level);
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Override public boolean isSignalSource(BlockState state) { return true; }

    @Override protected int getSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(LIT) ? 15 : 0;
    }

    @Override protected int getDirectSignal(BlockState state, BlockGetter level, BlockPos pos, Direction direction) {
        return state.getValue(LIT) ? 15 : 0;
    }

    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                          net.minecraft.world.phys.shapes.CollisionContext context) {
        return SccFacadeShell.shape(state, BODY);
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

    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SccRedstoneEmitterBlockEntity(pos, state);
    }

    @Nullable @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != SccBlockEntities.SCC_REDSTONE_EMITTER.get()) return null;
        return (world, pos, blockState, entity) -> {
            if (entity instanceof SccRedstoneEmitterBlockEntity emitter) {
                SccRedstoneEmitterBlockEntity.serverTick(world, pos, blockState, emitter);
            }
        };
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                           Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        BlockEntity entity = level.getBlockEntity(pos);
        if (player.isShiftKeyDown() && entity instanceof SccNetworkBlockEntity network && network.facade() != null) {
            Block.popResource(level, pos, SccFacadeItem.of(network.facade()));
            network.setFacade(null);
            return InteractionResult.CONSUME;
        }
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        if (entity instanceof SccRedstoneEmitterBlockEntity emitter && emitter.sccHost() != null) {
            emitter.sccHost().setLastMenuSide(emitter.sccHostSide());
        }
        SimpleMenuProvider provider = new SimpleMenuProvider(
                (id, inventory, user) -> new SccRedstoneEmitterMenu(id, inventory, pos),
                net.minecraft.network.chat.Component.translatable("block.scc.redstone_emitter"));
        PlatformMenuHelper.openBlockMenu(serverPlayer, provider, pos);
        return InteractionResult.CONSUME;
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
}
