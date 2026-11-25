package de.scc.calculator.network;

import com.mojang.serialization.MapCodec;
import de.scc.calculator.platform.registry.PlatformMenuHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.jetbrains.annotations.Nullable;

/** Wall-mounted variant of the persistent 3x3 crafting terminal. */
public final class SccWallCraftingTerminalBlock extends SccWallPanelBlock {
    public static final MapCodec<SccWallCraftingTerminalBlock> CODEC =
            simpleCodec(SccWallCraftingTerminalBlock::new);

    public SccWallCraftingTerminalBlock(BlockBehaviour.Properties properties) { super(properties); }

    public static BlockBehaviour.Properties deviceProperties() {
        return BlockBehaviour.Properties.of().strength(2.0F, 6.0F)
                .sound(SoundType.METAL).noOcclusion();
    }

    @Override protected MapCodec<? extends Block> codec() { return CODEC; }

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
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof SccCraftingTerminalBlockEntity terminal)) return InteractionResult.PASS;
        PlatformMenuHelper.openBlockMenu(serverPlayer, terminal, pos);
        return InteractionResult.CONSUME;
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos,
                         BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof SccCraftingTerminalBlockEntity terminal) {
            terminal.dropGrid();
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }
}
