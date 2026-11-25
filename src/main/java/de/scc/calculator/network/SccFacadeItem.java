package de.scc.calculator.network;

import de.scc.calculator.registry.SccDataComponents;
import de.scc.calculator.registry.SccItems;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.jetbrains.annotations.Nullable;

/** Original SCC facade item using the already-registered facade_block data component. */
public final class SccFacadeItem extends Item {
    public SccFacadeItem(Properties properties) { super(properties); }

    public static ItemStack of(Block block) {
        ItemStack stack = new ItemStack(SccItems.FACADE.get());
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id != null) stack.set(SccDataComponents.FACADE_BLOCK.get(), id);
        return stack;
    }

    @Nullable public static Block blockOf(ItemStack stack) {
        ResourceLocation id = stack.get(SccDataComponents.FACADE_BLOCK.get());
        if (id == null) return null;
        Block block = BuiltInRegistries.BLOCK.get(id);
        return block == Blocks.AIR ? null : block;
    }

    @Override public Component getName(ItemStack stack) {
        Block block = blockOf(stack);
        return block == null ? super.getName(stack)
                : Component.translatable("scc.tip.facade.of", block.getName());
    }

    @Override public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Player player = context.getPlayer();
        if (level.isClientSide) return InteractionResult.SUCCESS;
        Block facade = blockOf(context.getItemInHand());
        if (facade == null || !SccFacadeShell.canCover(facade)) {
            say(player, "scc.msg.facade.noblock");
            return InteractionResult.FAIL;
        }
        if (!level.getBlockState(pos).hasProperty(SccFacadeShell.COVERED)) {
            say(player, "scc.msg.facade.nocable");
            return InteractionResult.FAIL;
        }
        if (level.getBlockEntity(pos) instanceof SccNetworkBlockEntity entity) {
            if (entity.facade() != null) {
                say(player, "scc.msg.facade.already");
                return InteractionResult.FAIL;
            }
            entity.setFacade(facade);
            level.playSound(null, pos, facade.defaultBlockState().getSoundType().getPlaceSound(),
                    SoundSource.BLOCKS, 1.0F, 1.0F);
            if (player == null || !player.getAbilities().instabuild) context.getItemInHand().shrink(1);
            say(player, "scc.msg.facade.on");
            return InteractionResult.CONSUME;
        }
        say(player, "scc.msg.facade.nocable");
        return InteractionResult.FAIL;
    }

    private static void say(@Nullable Player player, String key) {
        if (player != null) player.displayClientMessage(Component.translatable(key), true);
    }
}
