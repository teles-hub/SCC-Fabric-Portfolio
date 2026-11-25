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

/** Wall-mounted variant of the wired network terminal. */
public final class SccWallTerminalBlock extends SccWallPanelBlock {
    public static final MapCodec<SccWallTerminalBlock> CODEC = simpleCodec(SccWallTerminalBlock::new);

    public SccWallTerminalBlock(BlockBehaviour.Properties properties) { super(properties); }

    public static BlockBehaviour.Properties deviceProperties() {
        return BlockBehaviour.Properties.of().strength(2.0F, 6.0F)
                .sound(SoundType.METAL).noOcclusion();
    }

    @Override protected MapCodec<? extends Block> codec() { return CODEC; }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SccTerminalBlockEntity(pos, state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                               Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;
        if (!(player instanceof ServerPlayer serverPlayer)) return InteractionResult.PASS;
        BlockEntity entity = level.getBlockEntity(pos);
        if (!(entity instanceof SccTerminalBlockEntity terminal)) return InteractionResult.PASS;
        PlatformMenuHelper.openBlockMenu(serverPlayer, terminal, pos);
        return InteractionResult.CONSUME;
    }
}
