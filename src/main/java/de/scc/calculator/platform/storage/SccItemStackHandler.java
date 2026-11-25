package de.scc.calculator.platform.storage;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.item.ItemStack;

/**
 * Casca com a API do ItemStackHandler do NeoForge (54 usos no codigo decompilado) sobre FabricItemStorage.
 * Usa COMPOSICAO (nao heranca) porque FabricItemStorage implementa SlottedStorage, cujo getSlots() retorna List
 * e conflitaria com getSlots() int de SccItemHandler.
 * Subclasses anonimas do original que sobrescrevem onContentsChanged(int), isItemValid(int, ItemStack) ou
 * getSlotLimit(int) continuam funcionando.
 * Para expor a outros mods/funis: BlockEntity devolve handler.asStorage() em ItemStorage.SIDED.
 */
public class SccItemStackHandler implements SccItemHandler {
    private final FabricItemStorage storage;

    public SccItemStackHandler() {
        this(1);
    }

    public SccItemStackHandler(int size) {
        this.storage = new FabricItemStorage(size, this::onContentsChanged) {
            @Override
            public boolean isItemValid(int slot, ItemVariant variant) {
                return SccItemStackHandler.this.isItemValid(slot, variant.toStack());
            }

            @Override
            public int getSlotLimit(int slot) {
                return SccItemStackHandler.this.getSlotLimit(slot);
            }
        };
    }

    /** Sobrescreva para marcar o BlockEntity como sujo (equivale ao hook do ItemStackHandler). */
    protected void onContentsChanged(int slot) {
    }

    public Storage<ItemVariant> asStorage() {
        return this.storage;
    }

    @Override
    public int getSlots() {
        return this.storage.getSlotCount();
    }

    @Override
    public ItemStack getStackInSlot(int slot) {
        return this.storage.getStackInSlot(slot);
    }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        this.storage.setStackInSlot(slot, stack);
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) return ItemStack.EMPTY;
        try (Transaction tx = SccTransactions.open()) {
            long inserted = this.storage.getSlot(slot).insert(ItemVariant.of(stack), stack.getCount(), tx);
            if (!simulate) tx.commit();
            return inserted >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - (int) inserted);
        }
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        ItemStack current = getStackInSlot(slot);
        if (current.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        ItemVariant variant = ItemVariant.of(current);
        try (Transaction tx = SccTransactions.open()) {
            long out = this.storage.getSlot(slot).extract(variant, amount, tx);
            if (!simulate) tx.commit();
            return out <= 0 ? ItemStack.EMPTY : variant.toStack((int) out);
        }
    }

    @Override
    public int getSlotLimit(int slot) {
        return 64;
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) {
        return true;
    }

    /** Equivale a serializeNBT(provider) do ItemStackHandler (formato proprio, ContainerHelper). */
    public CompoundTag serializeNBT(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        ContainerHelper.saveAllItems(tag, this.storage.getStacks(), provider);
        tag.putInt("Size", getSlots());
        return tag;
    }

    public void deserializeNBT(HolderLookup.Provider provider, CompoundTag tag) {
        for (int i = 0; i < getSlots(); i++) {
            this.storage.getStacks().set(i, ItemStack.EMPTY);
        }
        ContainerHelper.loadAllItems(tag, this.storage.getStacks(), provider);
    }
}
