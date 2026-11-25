package de.scc.calculator.network;

import com.mojang.serialization.MapCodec;
import de.scc.calculator.registry.SccBlockEntities;
import de.scc.calculator.registry.SccItems;
import de.scc.calculator.registry.SccNetworkBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.SimpleWaterloggedBlock;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public final class SccNetworkAntennaBlock extends Block implements EntityBlock, SimpleWaterloggedBlock {
    public static final MapCodec<SccNetworkAntennaBlock> CODEC = simpleCodec(SccNetworkAntennaBlock::new);
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");
    public static final BooleanProperty WATERLOGGED = BlockStateProperties.WATERLOGGED;
    private static final Direction[] DIRECTIONS = Direction.values();
    private static final VoxelShape BODY = Shapes.or(box(3, 0, 3, 13, 2, 13),
            box(6, 1, 6, 10, 13, 10), box(2, 4, 6, 14, 7, 10), box(7, 12, 7, 9, 16, 9));

    public SccNetworkAntennaBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(NORTH, false).setValue(EAST, false)
                .setValue(SOUTH, false).setValue(WEST, false).setValue(UP, false).setValue(DOWN, false)
                .setValue(WATERLOGGED, false));
    }

    public static BlockBehaviour.Properties deviceProperties() {
        return BlockBehaviour.Properties.of().strength(1.0F, 3.0F).sound(SoundType.METAL).noOcclusion();
    }

    @Override protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(NORTH, EAST, SOUTH, WEST, UP, DOWN, WATERLOGGED);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState().setValue(WATERLOGGED,
                context.getLevel().getFluidState(context.getClickedPos()).getType() == Fluids.WATER);
        for (Direction direction : DIRECTIONS) state = state.setValue(property(direction),
                SccNetworkScanner.isNode(context.getLevel(), context.getClickedPos().relative(direction)));
        return state;
    }

    @Override public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        SccNetworkCache.invalidate(level);
    }

    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                                LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        if (state.getValue(WATERLOGGED)) level.scheduleTick(pos, Fluids.WATER, Fluids.WATER.getTickDelay(level));
        if (level instanceof Level world) SccNetworkCache.invalidate(world);
        return super.updateShape(state.setValue(property(direction),
                SccNetworkScanner.isNode(level instanceof Level world ? world : null, neighborPos)),
                direction, neighborState, level, pos, neighborPos);
    }

    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                          net.minecraft.world.phys.shapes.CollisionContext context) {
        VoxelShape shape = BODY;
        for (Direction direction : DIRECTIONS) if (state.getValue(property(direction))) {
            shape = Shapes.or(shape, switch (direction) {
                case DOWN -> box(5, 0, 5, 11, 5, 11);
                case UP -> box(5, 11, 5, 11, 16, 11);
                case NORTH -> box(5, 5, 0, 11, 11, 5);
                case SOUTH -> box(5, 5, 11, 11, 11, 16);
                case WEST -> box(0, 5, 5, 5, 11, 11);
                case EAST -> box(11, 5, 5, 16, 11, 11);
            });
        }
        return shape;
    }

    @Override public FluidState getFluidState(BlockState state) {
        return state.getValue(WATERLOGGED) ? Fluids.WATER.getSource(false) : super.getFluidState(state);
    }

    private static BooleanProperty property(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH; case EAST -> EAST; case SOUTH -> SOUTH;
            case WEST -> WEST; case UP -> UP; case DOWN -> DOWN;
        };
    }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                                      @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof SccNetworkAntennaBlockEntity antenna) {
            antenna.setPlacedBy(player.getUUID());
        }
    }

    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level,
            BlockPos pos, Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof SccNetworkAntennaBlockEntity antenna)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (!WirelessAccess.canConfigure(player, antenna)) {
            player.displayClientMessage(Component.translatable("scc.msg.wireless.permission"), true);
            return ItemInteractionResult.SUCCESS;
        }
        if (stack.getItem() instanceof WirelessTerminalItem) {
            WirelessAccess.link(stack, pos, level.dimension());
            player.displayClientMessage(Component.translatable("scc.msg.wireless.linked",
                    pos.getX(), pos.getY(), pos.getZ()), true);
            return ItemInteractionResult.SUCCESS;
        }
        if (stack.is(SccItems.RANGE_UPGRADE.get())) {
            if (!antenna.addRangeUpgrade()) player.displayClientMessage(Component.translatable("scc.msg.antenna.full"), true);
            else { consume(stack, player); tellStatus(player, antenna); }
            return ItemInteractionResult.SUCCESS;
        }
        if (stack.is(SccItems.INFINITE_RANGE_UPGRADE.get())) {
            if (!antenna.addInfiniteUpgrade()) player.displayClientMessage(Component.translatable("scc.msg.antenna.full"), true);
            else { consume(stack, player); tellStatus(player, antenna); }
            return ItemInteractionResult.SUCCESS;
        }
        if (stack.is(SccItems.DIMENSIONAL_UPGRADE.get())) {
            if (!antenna.addDimensionalUpgrade()) player.displayClientMessage(Component.translatable("scc.msg.antenna.full"), true);
            else { consume(stack, player); tellStatus(player, antenna); }
            return ItemInteractionResult.SUCCESS;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
            Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof SccNetworkAntennaBlockEntity antenna)) return InteractionResult.PASS;
        if (!WirelessAccess.canConfigure(player, antenna)) {
            player.displayClientMessage(Component.translatable("scc.msg.wireless.permission"), true);
            return InteractionResult.CONSUME;
        }
        antenna.refresh();
        if (!player.isShiftKeyDown()) {
            if (player instanceof net.minecraft.server.level.ServerPlayer serverPlayer) {
                net.minecraft.world.SimpleMenuProvider provider = new net.minecraft.world.SimpleMenuProvider(
                        (id, inventory, user) -> new SccAntennaMenu(id, inventory, pos),
                        Component.translatable("scc.gui.antenna.title"));
                de.scc.calculator.platform.registry.PlatformMenuHelper.openBlockMenu(serverPlayer, provider, pos);
            }
            return InteractionResult.CONSUME;
        }
        int range = antenna.takeRangeUpgrades();
        for (int i = 0; i < range; i++) give(player, SccItems.RANGE_UPGRADE.get());
        if (antenna.takeDimensionalUpgrade()) give(player, SccItems.DIMENSIONAL_UPGRADE.get());
        if (antenna.takeInfiniteUpgrade()) give(player, SccItems.INFINITE_RANGE_UPGRADE.get());
        tellStatus(player, antenna);
        return InteractionResult.CONSUME;
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos,
            BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level.getBlockEntity(pos) instanceof SccNetworkAntennaBlockEntity antenna) {
            int range = antenna.takeRangeUpgrades();
            for (int i = 0; i < range; i++) popResource(level, pos, new ItemStack(SccItems.RANGE_UPGRADE.get()));
            if (antenna.takeDimensionalUpgrade()) popResource(level, pos, new ItemStack(SccItems.DIMENSIONAL_UPGRADE.get()));
            if (antenna.takeInfiniteUpgrade()) popResource(level, pos, new ItemStack(SccItems.INFINITE_RANGE_UPGRADE.get()));
            SccNetworkCache.invalidate(level);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    private static void tellStatus(Player player, SccNetworkAntennaBlockEntity antenna) {
        String range = antenna.dimensional()
                ? Component.translatable("scc.msg.antenna.anywhere").getString()
                : antenna.infinite()
                    ? Component.translatable("scc.msg.antenna.dimension").getString()
                    : Integer.toString(antenna.range());
        player.displayClientMessage(Component.translatable("scc.msg.antenna.info", antenna.rangeUpgrades(), range), false);
    }

    private static void consume(ItemStack stack, Player player) {
        if (!player.getAbilities().instabuild) stack.shrink(1);
    }

    private static void give(Player player, Item item) {
        ItemStack stack = new ItemStack(item);
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SccNetworkAntennaBlockEntity(pos, state);
    }

    @Nullable @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != SccBlockEntities.SCC_NETWORK_ANTENNA.get()) return null;
        return (world, blockPos, blockState, blockEntity) -> {
            if (blockEntity instanceof SccNetworkAntennaBlockEntity antenna) {
                SccTerminalBlockEntity.serverTick(world, blockPos, blockState, antenna);
            }
        };
    }
}
