package de.scc.calculator.network;

import com.mojang.serialization.MapCodec;
import de.scc.calculator.registry.SccBlockEntities;
import de.scc.calculator.registry.SccNetworkBlocks;
import de.scc.calculator.registry.SccItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

public final class SccWirelessTransmitterBlock extends Block implements EntityBlock {
    public static final MapCodec<SccWirelessTransmitterBlock> CODEC = simpleCodec(SccWirelessTransmitterBlock::new);
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    public static final BooleanProperty NORTH = BooleanProperty.create("north");
    public static final BooleanProperty EAST = BooleanProperty.create("east");
    public static final BooleanProperty SOUTH = BooleanProperty.create("south");
    public static final BooleanProperty WEST = BooleanProperty.create("west");
    public static final BooleanProperty UP = BooleanProperty.create("up");
    public static final BooleanProperty DOWN = BooleanProperty.create("down");
    private static final VoxelShape BODY = Shapes.or(box(5, 0, 5, 11, 11, 11), box(2, 4, 7, 14, 16, 9));
    private static final VoxelShape[] ARMS = {
            box(5, 0, 5, 11, 5, 11), box(5, 11, 5, 11, 16, 11),
            box(5, 5, 0, 11, 11, 5), box(5, 5, 11, 11, 11, 16),
            box(0, 5, 5, 5, 11, 11), box(11, 5, 5, 16, 11, 11)
    };
    private static final Direction[] DIRECTIONS = Direction.values();

    public SccWirelessTransmitterBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(defaultBlockState().setValue(ACTIVE, false)
                .setValue(NORTH, false).setValue(EAST, false).setValue(SOUTH, false)
                .setValue(WEST, false).setValue(UP, false).setValue(DOWN, false));
    }

    public static BlockBehaviour.Properties deviceProperties() {
        return BlockBehaviour.Properties.of().strength(1.0F, 3.0F).sound(SoundType.METAL).noOcclusion();
    }

    @Override protected MapCodec<? extends Block> codec() { return CODEC; }

    @Override protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(ACTIVE, NORTH, EAST, SOUTH, WEST, UP, DOWN);
    }

    @Override public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state = defaultBlockState();
        for (Direction direction : DIRECTIONS) state = state.setValue(propertyFor(direction),
                SccNetworkScanner.isNode(context.getLevel(), context.getClickedPos().relative(direction)));
        return state;
    }

    @Override protected BlockState updateShape(BlockState state, Direction direction, BlockState neighborState,
                                                 LevelAccessor level, BlockPos pos, BlockPos neighborPos) {
        BlockState updated = state.setValue(propertyFor(direction),
                SccNetworkScanner.isNode(level instanceof Level world ? world : null, neighborPos));
        if (level instanceof Level world) SccNetworkCache.invalidate(world);
        return super.updateShape(updated, direction, neighborState, level, pos, neighborPos);
    }

    @Override public void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        SccNetworkCache.invalidate(level);
    }

    @Override public VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos,
                                          net.minecraft.world.phys.shapes.CollisionContext context) {
        VoxelShape shape = BODY;
        for (Direction direction : DIRECTIONS) if (state.getValue(propertyFor(direction))) {
            shape = Shapes.or(shape, arm(direction));
        }
        return shape;
    }

    public static BooleanProperty propertyFor(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH; case EAST -> EAST; case SOUTH -> SOUTH;
            case WEST -> WEST; case UP -> UP; case DOWN -> DOWN;
        };
    }

    private static VoxelShape arm(Direction direction) {
        return switch (direction) {
            case DOWN -> ARMS[0]; case UP -> ARMS[1]; case NORTH -> ARMS[2];
            case SOUTH -> ARMS[3]; case WEST -> ARMS[4]; case EAST -> ARMS[5];
        };
    }

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state,
                                      @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && placer instanceof Player player
                && level.getBlockEntity(pos) instanceof SccWirelessTransmitterBlockEntity transmitter) {
            transmitter.setOwner(player.getUUID());
        }
    }

    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                         Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        if (level.getBlockEntity(pos) instanceof SccWirelessTransmitterBlockEntity transmitter) {
            boolean configurationItem = stack.is(SccItems.RANGE_UPGRADE.get())
                    || stack.is(SccItems.INFINITE_RANGE_UPGRADE.get())
                    || stack.is(SccItems.DIMENSIONAL_UPGRADE.get())
                    || (stack.getItem() instanceof BlockItem receiverItem
                    && receiverItem.getBlock() == SccNetworkBlocks.WIRELESS_RECEIVER.get());
            if (configurationItem && !WirelessAccess.canConfigure(player, transmitter)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("scc.msg.wireless.permission"), true);
                return ItemInteractionResult.SUCCESS;
            }
            if (stack.is(SccItems.RANGE_UPGRADE.get())) {
                if (transmitter.addRangeUpgrade()) consume(stack, player);
                return ItemInteractionResult.SUCCESS;
            }
            if (stack.is(SccItems.INFINITE_RANGE_UPGRADE.get())) {
                if (transmitter.addInfiniteUpgrade()) consume(stack, player);
                return ItemInteractionResult.SUCCESS;
            }
            if (stack.is(SccItems.DIMENSIONAL_UPGRADE.get())) {
                if (transmitter.addDimensionalUpgrade()) consume(stack, player);
                return ItemInteractionResult.SUCCESS;
            }
            if (stack.getItem() instanceof BlockItem item && item.getBlock() == SccNetworkBlocks.WIRELESS_RECEIVER.get()) {
                if (player.isShiftKeyDown()) {
                    if (transmitter.owner() == null || !transmitter.owner().equals(player.getUUID())) {
                        player.displayClientMessage(net.minecraft.network.chat.Component.translatable("scc.msg.wireless.permission"), true);
                        return ItemInteractionResult.SUCCESS;
                    }
                    SccWirelessDirectory directory = SccWirelessDirectory.get(level.getServer());
                    GlobalPos current = directory.defaultOf(player.getUUID());
                    GlobalPos target = GlobalPos.of(level.dimension(), pos);
                    if (target.equals(current)) {
                        directory.clearDefault(player.getUUID());
                        player.displayClientMessage(net.minecraft.network.chat.Component.translatable("scc.msg.transmitter.default.off"), true);
                    } else {
                        directory.setDefault(player.getUUID(), target);
                        player.displayClientMessage(net.minecraft.network.chat.Component.translatable(
                                "scc.msg.transmitter.default.on", pos.getX(), pos.getY(), pos.getZ()), true);
                    }
                    return ItemInteractionResult.SUCCESS;
                }
                ItemStack bound = stack.split(1);
                WirelessLink.write(bound, pos, level.dimension());
                if (!player.getInventory().add(bound)) player.drop(bound, false);
                return ItemInteractionResult.SUCCESS;
            }
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                           Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof SccWirelessTransmitterBlockEntity transmitter)) {
            return InteractionResult.PASS;
        }
        if (player.isShiftKeyDown()) {
            if (!WirelessAccess.canConfigure(player, transmitter)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("scc.msg.wireless.permission"), true);
                return InteractionResult.CONSUME;
            }
            int range = transmitter.takeRangeUpgrades();
            boolean dimensional = transmitter.takeDimensionalUpgrade();
            boolean infinite = transmitter.takeInfiniteUpgrade();
            for (int i = 0; i < range; i++) give(player, SccItems.RANGE_UPGRADE.get());
            if (dimensional) give(player, SccItems.DIMENSIONAL_UPGRADE.get());
            if (infinite) give(player, SccItems.INFINITE_RANGE_UPGRADE.get());
        }
        return InteractionResult.CONSUME;
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos,
                                   BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level.getBlockEntity(pos) instanceof SccWirelessTransmitterBlockEntity transmitter) {
                int range = transmitter.takeRangeUpgrades();
                for (int i = 0; i < range; i++) popResource(level, pos, new ItemStack(SccItems.RANGE_UPGRADE.get()));
                if (transmitter.takeDimensionalUpgrade()) {
                    popResource(level, pos, new ItemStack(SccItems.DIMENSIONAL_UPGRADE.get()));
                }
                if (transmitter.takeInfiniteUpgrade()) {
                    popResource(level, pos, new ItemStack(SccItems.INFINITE_RANGE_UPGRADE.get()));
                }
                if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                    SccWirelessDirectory.get(serverLevel.getServer()).forgetTransmitter(
                            GlobalPos.of(level.dimension(), pos));
                }
            }
            // Invalida mesmo se o BlockEntity já foi removido por outro hook de ciclo de vida.
            SccNetworkCache.invalidate(level);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    private static void consume(ItemStack stack, Player player) {
        if (!player.getAbilities().instabuild) stack.shrink(1);
    }

    private static void give(Player player, Item item) {
        ItemStack stack = new ItemStack(item);
        if (!player.getInventory().add(stack)) player.drop(stack, false);
    }

    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SccWirelessTransmitterBlockEntity(pos, state);
    }

    @Nullable @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != SccBlockEntities.SCC_WIRELESS_TRANSMITTER.get()) return null;
        return (world, blockPos, blockState, blockEntity) -> {
            if (blockEntity instanceof SccWirelessTransmitterBlockEntity endpoint) {
                SccWirelessTransmitterBlockEntity.serverTick(world, blockPos, blockState, endpoint);
            }
        };
    }
}
