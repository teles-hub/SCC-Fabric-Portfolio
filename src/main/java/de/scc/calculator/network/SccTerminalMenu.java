package de.scc.calculator.network;

import de.scc.calculator.registry.SccMenus;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Server-authoritative menu for wired, cable-bus hosted and wireless SCC terminals. */
public final class SccTerminalMenu extends AbstractContainerMenu {
    private final BlockPos pos;
    @org.jetbrains.annotations.Nullable private final ResourceKey<Level> remoteDimension;
    private final boolean wireless;
    private final boolean wirelessCrafting;

    public SccTerminalMenu(int containerId, Inventory playerInventory, TerminalMenuOpenData openingData) {
        super(SccMenus.TERMINAL.get(), containerId);
        this.pos = openingData.pos();
        this.remoteDimension = openingData.dimension();
        this.wireless = openingData.wireless();
        this.wirelessCrafting = openingData.wirelessCrafting();
        addPlayerInventory(playerInventory);
    }

    /** Compatibility constructor for direct/local menu creation in production code. */
    public SccTerminalMenu(int containerId, Inventory playerInventory, BlockPos pos) {
        this(containerId, playerInventory, pos, null, false, false);
    }

    public SccTerminalMenu(int containerId, Inventory playerInventory, BlockPos pos,
                           @org.jetbrains.annotations.Nullable ResourceKey<Level> dimension,
                           boolean wireless) {
        this(containerId, playerInventory, pos, dimension, wireless, false);
    }

    public SccTerminalMenu(int containerId, Inventory playerInventory, BlockPos pos,
                           @org.jetbrains.annotations.Nullable ResourceKey<Level> dimension,
                           boolean wireless, boolean wirelessCrafting) {
        super(SccMenus.TERMINAL.get(), containerId);
        this.pos = pos.immutable();
        this.remoteDimension = dimension;
        this.wireless = wireless;
        this.wirelessCrafting = wirelessCrafting;
        addPlayerInventory(playerInventory);
    }

    public BlockPos pos() { return pos; }
    @org.jetbrains.annotations.Nullable public ResourceKey<Level> remoteDimension() { return remoteDimension; }
    public boolean wireless() { return wireless; }
    public boolean wirelessCrafting() { return wirelessCrafting; }

    private void addPlayerInventory(Inventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        39 + column * 18, 110 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 39 + column * 18, 168));
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot source = slots.get(index);
        if (!source.hasItem()) return ItemStack.EMPTY;
        ItemStack sourceStack = source.getItem();
        ItemStack original = sourceStack.copy();
        if (index < 27) {
            if (!moveItemStackTo(sourceStack, 27, 36, true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(sourceStack, 0, 27, false)) {
            return ItemStack.EMPTY;
        }
        if (sourceStack.isEmpty()) source.set(ItemStack.EMPTY);
        else source.setChanged();
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        if (wireless) {
            return player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                    && remoteDimension != null
                    && WirelessAccess.stillValid(serverPlayer, pos, remoteDimension, wirelessCrafting);
        }
        if (!player.level().hasChunkAt(pos)) return false;
        boolean localRange = player.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D;
        if (!localRange) return false;
        var state = player.level().getBlockState(pos);
        if (state.is(de.scc.calculator.registry.SccNetworkBlocks.NETWORK_ANTENNA.get())) {
            return player instanceof net.minecraft.server.level.ServerPlayer serverPlayer
                    && WirelessAccess.ownerCanAccess(serverPlayer, pos, player.level().dimension());
        }
        if (state.is(de.scc.calculator.registry.SccNetworkBlocks.TERMINAL.get())) return true;
        if (player.level().getBlockEntity(pos) instanceof SccCableBusBlockEntity bus) {
            return bus.parts().stream().anyMatch(SccTerminalBlockEntity.class::isInstance);
        }
        return false;
    }
}
