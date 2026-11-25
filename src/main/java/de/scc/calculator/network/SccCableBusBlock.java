package de.scc.calculator.network;

import com.mojang.serialization.MapCodec;
import de.scc.calculator.registry.SccBlockEntities;
import de.scc.calculator.platform.SccMachineAdapters;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.BlockItem;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleMenuProvider;
import de.scc.calculator.platform.registry.PlatformMenuHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
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
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

/** Fabric host for up to six network BlockEntity parts and an optional central cable. */
public final class SccCableBusBlock extends Block implements EntityBlock, SimpleWaterloggedBlock, SccFacadeFaces {
    public static final MapCodec<SccCableBusBlock> CODEC = simpleCodec(SccCableBusBlock::new);
    public static final BooleanProperty CABLE = BooleanProperty.create("cable");
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");
    public static final BooleanProperty COVERED = SccFacadeShell.COVERED;
    private static final ThreadLocal<Boolean> MOVING = ThreadLocal.withInitial(() -> false);

    private static final Direction[] DIRECTIONS = Direction.values();
    private static final VoxelShape CORE = Block.box(6, 6, 6, 10, 10, 10);
    private static final VoxelShape[] ARMS = {
            Block.box(6, 0, 6, 10, 6, 10), Block.box(6, 10, 6, 10, 16, 10),
            Block.box(6, 6, 0, 10, 10, 6), Block.box(6, 6, 10, 10, 10, 16),
            Block.box(0, 6, 6, 6, 10, 10), Block.box(10, 6, 6, 16, 10, 10)
    };

    public SccCableBusBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(CABLE, true).setValue(WATERLOGGED, false)
                .setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false)
                .setValue(WEST, false).setValue(UP, false).setValue(DOWN, false).setValue(COVERED, false));
    }

    public static BlockBehaviour.Properties deviceProperties() {
        return BlockBehaviour.Properties.of().strength(0.6F, 1.5F).sound(SoundType.METAL).noOcclusion();
    }

    @Override protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(CABLE, WATERLOGGED, NORTH, EAST, SOUTH, WEST, UP, DOWN, COVERED);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(WATERLOGGED,
                context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER);
        for (Direction direction : DIRECTIONS) {
            state = state.setValue(propertyFor(direction),
                    hasNetworkPort(context.getLevel(), context.getClickedPos().relative(direction), direction.getOpposite()));
        }
        return state;
    }

    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                                 LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        boolean linked = state.getValue(CABLE) && hasNetworkPort(level, neighborPos, direction.getOpposite());
        BlockState updated = state.setValue(propertyFor(direction), linked);
        if (level instanceof Level world) SccNetworkCache.invalidate(world);
        return super.updateShape(updated, direction, neighborState, level, pos, neighborPos);
    }

    @Override public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        SccNetworkCache.invalidate(level);
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (!MOVING.get() && level.getBlockEntity(pos) instanceof SccCableBusBlockEntity bus) {
                bus.dropHostedParts();
            }
            SccNetworkCache.invalidate(level);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }


    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                           Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof SccCableBusBlockEntity bus)) return InteractionResult.PASS;
        Direction side = hit.getDirection();
        BlockState hostedState = bus.partState(side);
        if (player.isShiftKeyDown() && hostedState != null) {
            BlockEntity hosted = bus.removePart(side);
            if (hosted != null && level instanceof net.minecraft.server.level.ServerLevel server) {
                for (ItemStack drop : Block.getDrops(hostedState, server, pos, hosted, player, player.getMainHandItem())) {
                    Block.popResource(level, pos, drop);
                }
            }
            if (bus.partCount() == 0) collapseEmptyBus(level, pos, state, bus);
            return InteractionResult.CONSUME;
        }
        if (player.isShiftKeyDown() && hostedState == null && bus.facade() != null) {
            Block.popResource(level, pos, SccFacadeItem.of(bus.facade()));
            bus.setFacade(null);
            return InteractionResult.CONSUME;
        }
        if (player.isShiftKeyDown() && hostedState == null && bus.hasCable()) {
            if (bus.partCount() == 0) {
                BlockState cableState = de.scc.calculator.registry.SccNetworkBlocks.CABLE.get().defaultBlockState();
                for (Direction direction : DIRECTIONS) {
                    cableState = cableState.setValue(SccCableBlock.propertyFor(direction),
                            state.getValue(propertyFor(direction)));
                }
                level.setBlock(pos, cableState, 3);
            } else {
                boolean dropCable = bus.onCable() && !player.getAbilities().instabuild;
                setCablePresent(level, pos, bus, false);
                if (dropCable) Block.popResource(level, pos, new ItemStack(de.scc.calculator.registry.SccNetworkBlocks.CABLE.get()));
            }
            return InteractionResult.CONSUME;
        }
        if (hostedState != null && bus.part(side) instanceof MenuProvider menuProvider
                && player instanceof ServerPlayer serverPlayer
                && hostedState.getBlock() instanceof SccTerminalBlock) {
            PlatformMenuHelper.openBlockMenu(serverPlayer, menuProvider, pos);
            return InteractionResult.CONSUME;
        }
        if (hostedState != null && bus.part(side) instanceof SccStorageConnectorBlockEntity
                && player instanceof ServerPlayer serverPlayer) {
            bus.setLastMenuSide(side);
            SimpleMenuProvider provider = new SimpleMenuProvider(
                    (containerId, inventory, user) -> new SccStorageConnectorMenu(containerId, inventory, pos),
                    net.minecraft.network.chat.Component.translatable("block.scc.storage_connector"));
            PlatformMenuHelper.openBlockMenu(serverPlayer, provider, pos);
            return InteractionResult.CONSUME;
        }
        return hostedState == null ? InteractionResult.PASS : hostedState.useWithoutItem(level, player, hit);
    }

    @Override protected boolean isSignalSource(BlockState state) { return true; }

    @Override protected int getSignal(BlockState state, BlockGetter world, BlockPos pos, Direction direction) {
        if (!(world.getBlockEntity(pos) instanceof SccCableBusBlockEntity bus)) return 0;
        for (Direction side : DIRECTIONS) {
            BlockState part = bus.partState(side);
            if (part != null && part.hasProperty(SccRedstoneEmitterBlock.LIT)
                    && part.getValue(SccRedstoneEmitterBlock.LIT)) return 15;
        }
        return 0;
    }

    @Override protected int getDirectSignal(BlockState state, BlockGetter world, BlockPos pos, Direction direction) {
        return getSignal(state, world, pos, direction);
    }

    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                          Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof SccCableBusBlockEntity bus)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.is(de.scc.calculator.registry.SccNetworkBlocks.CABLE.get().asItem())) {
            if (!bus.hasCable()) {
                setCablePresent(level, pos, bus, true);
                if (!player.getAbilities().instabuild) stack.shrink(1);
            }
            return ItemInteractionResult.SUCCESS;
        }
        BlockState hostedState = bus.partState(hit.getDirection());
        if (hostedState != null) {
            return hostedState.useItemOn(stack, level, player, hand, hit);
        }
        if (stack.getItem() instanceof BlockItem blockItem
                && blockItem.getBlock() instanceof EntityBlock entityBlock
                && blockItem.getBlock() != this) {
            BlockPlaceContext placement = new BlockPlaceContext(player, hand, stack, hit);
            BlockState partState = blockItem.getBlock().getStateForPlacement(placement);
            if (partState == null) return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
            BlockEntity part = entityBlock.newBlockEntity(pos, partState);
            if (part instanceof SccHostedNetworkPart) {
                CustomData blockEntityData = stack.get(DataComponents.BLOCK_ENTITY_DATA);
                if (blockEntityData != null) part.loadWithComponents(blockEntityData.copyTag(), level.registryAccess());
            }
            if (part instanceof SccHostedNetworkPart && bus.addPart(hit.getDirection(), partState, part)) {
                if (part instanceof SccStorageConnectorBlockEntity connector) {
                    connector.setPlacedBy(player.getUUID());
                }
                if (!player.getAbilities().instabuild) stack.shrink(1);
                return ItemInteractionResult.SUCCESS;
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    /**
     * Converts an independent SCC network part into a one-face cable bus while
     * preserving its exact block state and BlockEntity payload.
     */
    public static boolean convertPartToBus(Level level, BlockPos pos, Direction face) {
        if (level == null || level.isClientSide || face == null || !level.hasChunkAt(pos)) return false;
        BlockState originalState = level.getBlockState(pos);
        BlockEntity original = level.getBlockEntity(pos);
        if (!(original instanceof SccHostedNetworkPart) || !(originalState.getBlock() instanceof EntityBlock)) return false;
        net.minecraft.nbt.CompoundTag payload = original.saveWithoutMetadata(level.registryAccess());
        ResourceLocation typeId = BlockEntityType.getKey(original.getType());
        if (typeId == null) return false;
        payload.putString("id", typeId.toString());
        BlockState busState = de.scc.calculator.registry.SccNetworkBlocks.CABLE_BUS.get().defaultBlockState();
        for (Direction direction : DIRECTIONS) {
            boolean linked = hasNetworkPort(level, pos.relative(direction), direction.getOpposite());
            busState = busState.setValue(propertyFor(direction), linked);
        }
        if (originalState.hasProperty(WATERLOGGED)) {
            busState = busState.setValue(WATERLOGGED, originalState.getValue(WATERLOGGED));
        } else if (level.getFluidState(pos).getType() == Fluids.WATER) {
            busState = busState.setValue(WATERLOGGED, true);
        }
        if (!level.setBlock(pos, busState, 3)) return false;
        if (!(level.getBlockEntity(pos) instanceof SccCableBusBlockEntity bus)) {
            level.setBlock(pos, originalState, 3);
            return false;
        }
        boolean inheritedOnCable = original instanceof SccNetworkBlockEntity networkPart && networkPart.onCable();
        BlockEntity restored = BlockEntity.loadStatic(pos, originalState, payload, level.registryAccess());
        if (!(restored instanceof SccHostedNetworkPart) || !bus.addPart(face, originalState, restored)) {
            level.setBlock(pos, originalState, 3);
            return false;
        }
        bus.setOnCable(inheritedOnCable);
        SccNetworkCache.invalidate(level);
        return true;
    }

    private static void collapseEmptyBus(Level level, BlockPos pos, BlockState busState, SccCableBusBlockEntity bus) {
        if (!bus.onCable()) {
            MOVING.set(true);
            try {
                level.removeBlock(pos, false);
            } finally {
                MOVING.set(false);
            }
            return;
        }
        BlockState cableState = de.scc.calculator.registry.SccNetworkBlocks.CABLE.get().defaultBlockState();
        for (Direction direction : DIRECTIONS) {
            cableState = cableState.setValue(SccCableBlock.propertyFor(direction),
                    busState.getValue(propertyFor(direction)));
        }
        MOVING.set(true);
        try {
            level.setBlock(pos, cableState, 3);
        } finally {
            MOVING.set(false);
        }
    }

    /** Toggles the central cable without replacing or losing hosted parts. */
    public static void setCablePresent(Level level, BlockPos pos, SccCableBusBlockEntity bus, boolean present) {
        if (level.isClientSide || !(level.getBlockState(pos).getBlock() instanceof SccCableBusBlock)) return;
        BlockState state = level.getBlockState(pos).setValue(CABLE, present);
        for (Direction direction : DIRECTIONS) {
            boolean linked = present && hasNetworkPort(level, pos.relative(direction), direction.getOpposite());
            state = state.setValue(propertyFor(direction), linked);
        }
        level.setBlock(pos, state, 3);
        bus.setOnCable(present);
        bus.cableChanged();
        SccNetworkCache.invalidate(level);
    }

    @Override public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SccCableBusBlockEntity(pos, state);
    }

    @Nullable @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (type != SccBlockEntities.SCC_CABLE_BUS.get()) return null;
        return (world, pos, blockState, entity) -> {
            if (entity instanceof SccCableBusBlockEntity bus) {
                SccCableBusBlockEntity.serverTick(world, pos, blockState, bus);
            }
        };
    }

    @Override public VoxelShape getShape(BlockState state, BlockGetter world, BlockPos pos,
                                          net.minecraft.world.phys.shapes.CollisionContext context) {
        VoxelShape shape = state.getValue(CABLE) ? CORE : Shapes.empty();
        for (Direction direction : DIRECTIONS) {
            if (state.getValue(propertyFor(direction))) shape = Shapes.or(shape, ARMS[direction.get3DDataValue()]);
        }
        if (world.getBlockEntity(pos) instanceof SccCableBusBlockEntity bus) {
            for (Direction direction : DIRECTIONS) {
                BlockState part = bus.partState(direction);
                if (part != null) shape = Shapes.or(shape, part.getShape(world, pos, context));
            }
        }
        return SccFacadeShell.shape(state, shape.optimize());
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
            case NORTH -> NORTH; case EAST -> EAST; case SOUTH -> SOUTH;
            case WEST -> WEST; case UP -> UP; case DOWN -> DOWN;
        };
    }

    public static boolean hasNetworkPort(LevelAccessor level, BlockPos pos, Direction faceTowardCable) {
        if (!level.hasChunkAt(pos)) return false;
        BlockState neighborState = level.getBlockState(pos);
        if (neighborState.getBlock() instanceof SccWirelessTransmitterBlock
                || neighborState.getBlock() instanceof SccWirelessReceiverBlock) return true;
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof NetworkNode node) return node.canConnect(faceTowardCable);
        return level instanceof Level world && entity != null
                && SccMachineAdapters.machineAlias(world, pos) != null;
    }
}
