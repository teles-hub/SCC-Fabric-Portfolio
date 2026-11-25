package de.scc.calculator.network;

import de.scc.calculator.registry.SccMenus;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.List;

/** Server-authoritative configuration menu for a Storage Connector. */
public final class SccStorageConnectorMenu extends AbstractContainerMenu {
    private static final int LOCK_CURRENT = 0;
    private static final int CLEAR_LOCK = 1;
    private static final int LOCK_FROM_CARRIED = 2;
    private static final int CYCLE_FLOW = 3;
    private static final int CYCLE_VOID_MODE = 4;
    private static final int CLEAR_EXTRACT_PRIORITY = 5;
    private static final int FILTER_READS = 7;
    private static final int TOGGLE_BLACKLIST = 8;
    private static final int PRIORITY_BASE = 100;
    private static final int EXTRACT_PRIORITY_BASE = 2000;
    private static final int FLUID_ID_BASE = 10_000_000;

    private final BlockPos pos;
    private final Player player;
    private final Direction selectedSide;
    private final FilterContainer filter;

    public SccStorageConnectorMenu(int containerId, Inventory inventory, BlockPos pos) {
        super(SccMenus.STORAGE_CONNECTOR.get(), containerId);
        this.pos = pos.immutable();
        this.player = inventory.player;
        BlockEntity menuHost = player.level().getBlockEntity(pos);
        this.selectedSide = menuHost instanceof SccCableBusBlockEntity bus ? bus.lastMenuSide() : null;
        this.filter = new FilterContainer(findHosted(player, pos, selectedSide));
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                int slot = column + row * 9;
                addSlot(new Slot(filter, slot, 8 + column * 18, 28 + row * 18) {
                    @Override public int getMaxStackSize() { return 1; }
                });
            }
        }
        addPlayerInventory(inventory);
    }

    public BlockPos pos() { return pos; }
    public SccStorageConnectorBlockEntity connector() { return findHosted(player, pos, selectedSide); }

    private static SccStorageConnectorBlockEntity findHosted(Player player, BlockPos pos, Direction selectedSide) {
        BlockEntity entity = player.level().getBlockEntity(pos);
        if (entity instanceof SccStorageConnectorBlockEntity connector) return connector;
        if (entity instanceof SccCableBusBlockEntity bus) {
            if (selectedSide != null && bus.part(selectedSide) instanceof SccStorageConnectorBlockEntity connector) {
                return connector;
            }
            for (BlockEntity part : bus.parts()) {
                if (part instanceof SccStorageConnectorBlockEntity connector) return connector;
            }
        }
        return null;
    }

    @Override public boolean clickMenuButton(Player player, int id) {
        if (player.level().isClientSide) return true;
        SccStorageConnectorBlockEntity connector = findHosted(player, pos, selectedSide);
        if (connector == null || !stillValid(player)) return false;

        if (id >= FLUID_ID_BASE) {
            var fluid = BuiltInRegistries.FLUID.byId(id - FLUID_ID_BASE);
            if (fluid != null && fluid != Fluids.EMPTY) connector.setLockedFluid(fluid);
            return true;
        }
        if (id >= EXTRACT_PRIORITY_BASE) {
            int delta = id - EXTRACT_PRIORITY_BASE - 500;
            connector.setExtractPriority(connector.extractPriority() + delta);
            return true;
        }
        if (id >= PRIORITY_BASE) {
            int delta = id - PRIORITY_BASE - 500;
            connector.setPriority(connector.priority() + delta);
            return true;
        }

        switch (id) {
            case LOCK_CURRENT -> connector.lockToCurrent();
            case CLEAR_LOCK -> connector.clearFluidLock();
            case LOCK_FROM_CARRIED -> lockFromCarried(player, connector);
            case CYCLE_FLOW -> connector.cycleFlow();
            case CYCLE_VOID_MODE -> connector.cycleVoidMode();
            case CLEAR_EXTRACT_PRIORITY -> connector.clearExtractPriority();
            case 6 -> { if (connector instanceof SccStorageBlockEntity storage) storage.drainIntoNetwork(); else return false; }
            case FILTER_READS -> connector.setFilterReads(!connector.filterReads());
            case TOGGLE_BLACKLIST -> connector.setBlacklist(!connector.blacklist());
            default -> { return false; }
        }
        return true;
    }

    private void lockFromCarried(Player player, SccStorageConnectorBlockEntity connector) {
        Storage<FluidVariant> carried = ContainerItemContext.ofPlayerCursor(player, this).find(FluidStorage.ITEM);
        if (carried == null) return;
        for (StorageView<FluidVariant> view : carried) {
            if (!view.isResourceBlank() && view.getAmount() > 0L) {
                connector.setLockedFluid(view.getResource().getFluid());
                return;
            }
        }
    }

    @Override public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot source = slots.get(index);
        if (!source.hasItem()) return ItemStack.EMPTY;
        ItemStack sourceStack = source.getItem();
        ItemStack original = sourceStack.copy();
        if (index < 27) {
            if (!moveItemStackTo(sourceStack, 27, 63, true)) return ItemStack.EMPTY;
        } else if (!moveItemStackTo(sourceStack, 0, 27, false)) {
            return ItemStack.EMPTY;
        }
        if (sourceStack.isEmpty()) source.set(ItemStack.EMPTY); else source.setChanged();
        return original;
    }

    @Override public boolean stillValid(Player player) {
        return player.blockPosition().distSqr(pos) <= 64.0D && findHosted(player, pos, selectedSide) != null;
    }

    private void addPlayerInventory(Inventory inventory) {
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(inventory, column + row * 9 + 9,
                        8 + column * 18, 124 + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, 182));
        }
    }

    private static final class FilterContainer extends SimpleContainer {
        private final SccStorageConnectorBlockEntity owner;
        private boolean loading = true;

        private FilterContainer(SccStorageConnectorBlockEntity owner) {
            super(27);
            this.owner = owner;
            if (owner != null) {
                for (int i = 0; i < 27; i++) super.setItem(i, owner.filterEntry(i));
            }
            loading = false;
        }

        @Override public void setItem(int slot, ItemStack stack) {
            if (slot < 0 || slot >= getContainerSize()) return;
            ItemStack normalized = stack == null || stack.isEmpty()
                    ? ItemStack.EMPTY : stack.copyWithCount(1);
            super.setItem(slot, normalized);
        }

        @Override public void setChanged() {
            super.setChanged();
            if (loading || owner == null) return;
            List<ItemStack> snapshot = new ArrayList<>(getContainerSize());
            for (int slot = 0; slot < getContainerSize(); slot++) {
                snapshot.add(getItem(slot));
            }
            owner.setFilterEntries(snapshot);
        }
    }
}
