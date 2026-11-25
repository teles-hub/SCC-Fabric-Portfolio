package de.scc.calculator.network;

import de.scc.calculator.registry.SccMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Server-authoritative link/unlink slot for a network antenna. */
public final class SccAntennaMenu extends AbstractContainerMenu {
    public static final int ACTION_LINK = 0;
    public static final int ACTION_UNLINK = 1;
    private final BlockPos pos;
    private final SimpleContainer linkSlot = new SimpleContainer(1);

    public SccAntennaMenu(int syncId, Inventory inventory, BlockPos pos) {
        super(SccMenus.ANTENNA.get(), syncId);
        this.pos = pos.immutable();
        addSlot(new Slot(linkSlot, 0, 80, 34) {
            @Override public boolean mayPlace(ItemStack stack) {
                return stack.getItem() instanceof WirelessTerminalItem;
            }
        });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        8 + column * 18, 106 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, 164));
        }
    }

    public BlockPos pos() { return pos; }
    public ItemStack linkStack() { return linkSlot.getItem(0); }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (player.level().isClientSide) return true;
        if (id != ACTION_LINK && id != ACTION_UNLINK) return false;
        if (!player.level().hasChunkAt(pos)
                || !(player.level().getBlockEntity(pos) instanceof SccNetworkAntennaBlockEntity antenna)) {
            return false;
        }
        if (!WirelessAccess.canConfigure(player, antenna)) {
            player.displayClientMessage(Component.translatable("scc.msg.wireless.permission"), true);
            return true;
        }
        ItemStack stack = linkSlot.getItem(0);
        if (stack.isEmpty()) {
            player.displayClientMessage(Component.translatable("scc.msg.antenna.empty"), true);
            return true;
        }
        if (!(stack.getItem() instanceof WirelessTerminalItem)) {
            player.displayClientMessage(Component.translatable("scc.msg.antenna.unsupported", stack.getHoverName()), true);
            return true;
        }
        if (id == ACTION_LINK) {
            WirelessAccess.link(stack, pos, player.level().dimension());
            player.displayClientMessage(Component.translatable("scc.msg.antenna.linked", stack.getHoverName()), true);
        } else {
            WirelessAccess.unlink(stack);
            player.displayClientMessage(Component.translatable("scc.msg.antenna.unlinked", stack.getHoverName()), true);
        }
        broadcastChanges();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot source = slots.get(index);
        if (!source.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = source.getItem();
        ItemStack original = stack.copy();
        if (index == 0) {
            if (!moveItemStackTo(stack, 1, slots.size(), true)) return ItemStack.EMPTY;
        } else if (source.mayPlace(stack)) {
            if (!moveItemStackTo(stack, 0, 1, false)) return ItemStack.EMPTY;
        } else if (index < 28) {
            if (!moveItemStackTo(stack, 28, 37, false)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(stack, 1, 28, false)) {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) source.set(ItemStack.EMPTY);
        else source.setChanged();
        return original;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        if (!player.level().isClientSide) {
            ItemStack stack = linkSlot.removeItemNoUpdate(0);
            if (!stack.isEmpty() && !player.getInventory().add(stack)) player.drop(stack, false);
        }
    }

    @Override
    public boolean stillValid(Player player) {
        if (!player.level().hasChunkAt(pos)
                || !(player.level().getBlockEntity(pos) instanceof SccNetworkAntennaBlockEntity antenna)
                || player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) > 64.0D) {
            return false;
        }
        return WirelessAccess.canConfigure(player, antenna);
    }
}
