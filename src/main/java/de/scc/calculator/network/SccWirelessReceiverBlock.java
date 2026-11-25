package de.scc.calculator.network;

import com.mojang.serialization.MapCodec;
import de.scc.calculator.registry.SccBlockEntities;
import de.scc.calculator.registry.SccNetworkBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelReader;
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

public final class SccWirelessReceiverBlock extends Block implements EntityBlock {
    public static final MapCodec<SccWirelessReceiverBlock> CODEC = simpleCodec(SccWirelessReceiverBlock::new);
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

    public SccWirelessReceiverBlock(BlockBehaviour.Properties properties) {
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

    @Override public void setPlacedBy(Level level, BlockPos pos, BlockState state, @Nullable LivingEntity placer, ItemStack stack) {
        super.setPlacedBy(level, pos, state, placer, stack);
        if (!level.isClientSide && level.getBlockEntity(pos) instanceof SccWirelessReceiverBlockEntity receiver) {
            if (!(placer instanceof Player player) || level.getServer() == null) return;
            receiver.setOwner(player.getUUID());
            BlockPos linkedPos = WirelessLink.posOf(stack);
            net.minecraft.resources.ResourceKey<Level> linkedDimension = WirelessLink.dimOf(stack);
            if (linkedPos == null || linkedDimension == null) {
                net.minecraft.core.GlobalPos defaultPos = SccWirelessDirectory.get(level.getServer()).defaultOf(player.getUUID());
                if (defaultPos != null) {
                    net.minecraft.server.level.ServerLevel target = level.getServer().getLevel(defaultPos.dimension());
                    if (target == null) return;
                    if (target.hasChunkAt(defaultPos.pos())) {
                        net.minecraft.world.level.block.entity.BlockEntity candidate = target.getBlockEntity(defaultPos.pos());
                        if (!(candidate instanceof SccWirelessTransmitterBlockEntity transmitter)) {
                            SccWirelessDirectory.get(level.getServer()).clearDefault(player.getUUID());
                            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("scc.msg.transmitter.default.gone"), true);
                            return;
                        }
                        if (transmitter.owner() != null && !player.getUUID().equals(transmitter.owner())) {
                            player.displayClientMessage(net.minecraft.network.chat.Component.translatable("scc.msg.wireless.permission"), true);
                            return;
                        }
                    }
                    linkedPos = defaultPos.pos();
                    linkedDimension = defaultPos.dimension();
                }
            }
            if (linkedPos != null && linkedDimension != null) receiver.link(linkedPos, linkedDimension);
        }
    }

    @Override public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        ItemStack stack = super.getCloneItemStack(level, pos, state);
        if (level.getBlockEntity(pos) instanceof SccWirelessReceiverBlockEntity receiver
                && receiver.linkPos() != null && receiver.linkDimension() != null) {
            WirelessLink.write(stack, receiver.linkPos(), receiver.linkDimension());
        }
        return stack;
    }

    @Override protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                                         Player player, InteractionHand hand, BlockHitResult hit) {
        if (level.isClientSide) return ItemInteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof SccWirelessReceiverBlockEntity receiver)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }
        if (stack.getItem() instanceof BlockItem item && item.getBlock() == SccNetworkBlocks.WIRELESS_RECEIVER.get()) {
            if (!WirelessAccess.canConfigure(player, receiver)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("scc.msg.wireless.permission"), true);
                return ItemInteractionResult.SUCCESS;
            }
            BlockPos linkedPos = WirelessLink.posOf(stack);
            net.minecraft.resources.ResourceKey<Level> linkedDimension = WirelessLink.dimOf(stack);
            if (linkedPos != null && linkedDimension != null) receiver.link(linkedPos, linkedDimension);
            return ItemInteractionResult.SUCCESS;
        }
        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    @Override protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                           Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(level.getBlockEntity(pos) instanceof SccWirelessReceiverBlockEntity receiver)) return InteractionResult.PASS;
        if (player.isShiftKeyDown()) {
            if (!WirelessAccess.canConfigure(player, receiver)) {
                player.displayClientMessage(net.minecraft.network.chat.Component.translatable("scc.msg.wireless.permission"), true);
                return InteractionResult.CONSUME;
            }
            receiver.unlink();
        }
        return InteractionResult.CONSUME;
    }

    @Override public void onRemove(BlockState state, Level level, BlockPos pos,
                                   BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())) {
            if (level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                SccWirelessDirectory.get(serverLevel.getServer()).forget(level.dimension(), pos);
            }
            // Invalida mesmo se o BlockEntity já foi removido por outro hook de ciclo de vida.
            SccNetworkCache.invalidate(level);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Nullable @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SccWirelessReceiverBlockEntity(pos, state);
    }

    @Nullable @Override public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != SccBlockEntities.SCC_WIRELESS_RECEIVER.get()) return null;
        return (world, blockPos, blockState, blockEntity) -> {
            if (blockEntity instanceof SccWirelessReceiverBlockEntity endpoint) {
                SccWirelessReceiverBlockEntity.serverTick(world, blockPos, blockState, endpoint);
            }
        };
    }
}
