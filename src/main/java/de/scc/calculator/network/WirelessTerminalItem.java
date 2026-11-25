package de.scc.calculator.network;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.level.Level;

import java.util.List;

/** Handheld terminal linked to an SCC network antenna. */
public final class WirelessTerminalItem extends Item {
    private final boolean crafting;

    public WirelessTerminalItem(Properties properties) { this(properties, false); }

    public WirelessTerminalItem(Properties properties, boolean crafting) {
        super(properties.stacksTo(1));
        this.crafting = crafting;
    }

    public boolean crafting() { return crafting; }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide && player instanceof ServerPlayer serverPlayer) {
            WirelessAccess.open(serverPlayer, stack, crafting);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> tooltip, TooltipFlag flag) {
        BlockPos pos = WirelessAccess.linkedPos(stack);
        if (pos == null) {
            tooltip.add(Component.translatable("scc.tip.wireless.unlinked").withStyle(ChatFormatting.GRAY));
            return;
        }
        tooltip.add(Component.translatable("scc.tip.wireless.linked", pos.getX(), pos.getY(), pos.getZ())
                .withStyle(ChatFormatting.GRAY));
        var dimension = WirelessAccess.linkedDim(stack);
        if (dimension != null) tooltip.add(Component.literal(dimension.location().toString()).withStyle(ChatFormatting.DARK_GRAY));
    }
}
