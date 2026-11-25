package de.scc.calculator.network;

import de.scc.calculator.calc.StackKey;
import de.scc.calculator.registry.SccMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;

public final class SccRedstoneEmitterMenu extends AbstractContainerMenu {
    public static final int ACTION_ITEM_BASE = 3_000_000;
    public static final int ACTION_AMOUNT_BASE = 4_000_000;
    public static final int ACTION_MODE = 2_000_000;
    private final BlockPos pos;
    private final Player player;

    public SccRedstoneEmitterMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(SccMenus.REDSTONE_EMITTER.get(), containerId);
        this.pos = pos.immutable();
        this.player = inventory.player;
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9, 8 + column * 18, 84 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, 142));
        }
    }

    public BlockPos pos() { return pos; }
    public SccRedstoneEmitterBlockEntity emitter() { return find(player, pos); }

    private static SccRedstoneEmitterBlockEntity find(Player player, BlockPos pos) {
        BlockEntity entity = player.level().getBlockEntity(pos);
        if (entity instanceof SccRedstoneEmitterBlockEntity emitter) return emitter;
        if (entity instanceof SccCableBusBlockEntity bus) {
            if (bus.part(bus.lastMenuSide()) instanceof SccRedstoneEmitterBlockEntity emitter) return emitter;
            for (BlockEntity part : bus.parts()) {
                if (part instanceof SccRedstoneEmitterBlockEntity emitter) return emitter;
            }
        }
        return null;
    }

    public static int itemButton(Item item) {
        int id = item == null ? 0 : BuiltInRegistries.ITEM.getId(item);
        return ACTION_ITEM_BASE + Math.max(0, id);
    }

    public static int amountButton(long amount) {
        return ACTION_AMOUNT_BASE + (int) Math.max(0L, Math.min(999_999L, amount));
    }

    @Override public boolean clickMenuButton(Player player, int id) {
        if (player.level().isClientSide) return true;
        SccRedstoneEmitterBlockEntity emitter = find(player, pos);
        if (emitter == null || !stillValid(player)) return false;
        if (id == ACTION_MODE) {
            emitter.setMode(emitter.mode() == SccRedstoneEmitterBlockEntity.MODE_AT_LEAST
                    ? SccRedstoneEmitterBlockEntity.MODE_AT_MOST : SccRedstoneEmitterBlockEntity.MODE_AT_LEAST);
            return true;
        }
        if (id >= ACTION_AMOUNT_BASE && id < ACTION_AMOUNT_BASE + 1_000_000) {
            emitter.setAmount(id - ACTION_AMOUNT_BASE);
            return true;
        }
        if (id >= ACTION_ITEM_BASE && id < ACTION_AMOUNT_BASE) {
            int itemId = id - ACTION_ITEM_BASE;
            Item item = itemId == 0 ? null : BuiltInRegistries.ITEM.byId(itemId);
            emitter.setItem(item == null ? null : StackKey.of(item));
            return true;
        }
        return false;
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot source = slots.get(index);
        if (!source.hasItem()) return ItemStack.EMPTY;
        ItemStack original = source.getItem().copy();
        ItemStack moving = source.getItem();
        if (!moveItemStackTo(moving, 27, 36, true)) return ItemStack.EMPTY;
        if (moving.isEmpty()) source.set(ItemStack.EMPTY); else source.setChanged();
        return original;
    }

    @Override public boolean stillValid(Player player) {
        return find(player, pos) != null && player.blockPosition().distSqr(pos) <= 64.0D;
    }
}
