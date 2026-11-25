package de.scc.calculator.network;

import com.mojang.serialization.MapCodec;
import de.scc.calculator.platform.registry.PlatformMenuHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/**
 * Fabric implementation of the original SCC Crafting Terminal block.
 * Recipe execution is server-authoritative through the crafting terminal menu;
 * this block only owns placement, interaction, persistence lifecycle and network invalidation.
 */
public final class SccCraftingTerminalBlock extends Block implements EntityBlock {
    public static final MapCodec<SccCraftingTerminalBlock> CODEC =
            simpleCodec(SccCraftingTerminalBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public SccCraftingTerminalBlock(BlockBehaviour.Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    public static BlockBehaviour.Properties deviceProperties() {
        return BlockBehaviour.Properties.of().strength(2.0F, 6.0F)
                .sound(SoundType.METAL).noOcclusion();
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return defaultBlockState().setValue(FACING,
                context.getHorizontalDirection().getOpposite());
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return state.rotate(mirror.getRotation(state.getValue(FACING)));
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SccCraftingTerminalBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        BlockEntity blockEntity = level.getBlockEntity(pos);
        if (!(blockEntity instanceof SccCraftingTerminalBlockEntity terminal)) {
            return InteractionResult.PASS;
        }
        TerminalMenuOpenData data = new TerminalMenuOpenData(pos, level.dimension(), false, true);
        PlatformMenuHelper.openExtendedMenu(serverPlayer,
                new SimpleMenuProvider((id, inventory, user) ->
                        new SccCraftingTerminalMenu(id, inventory, data), terminal.getDisplayName()), data);
        return InteractionResult.CONSUME;
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
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof SccCraftingTerminalBlockEntity terminal) {
            terminal.dropGrid();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
        if (!state.is(newState.getBlock())) SccNetworkCache.invalidate(level);
    }
}
