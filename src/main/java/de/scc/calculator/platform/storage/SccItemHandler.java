package de.scc.calculator.platform.storage;

import net.minecraft.world.item.ItemStack;

/**
 * Equivalente direto ao IItemHandler / IItemHandlerModifiable do NeoForge.
 * Permite manter a lógica interna de rede logística e autocrafting do SCC praticamente intacta.
 */
public interface SccItemHandler {

    int getSlots();

    ItemStack getStackInSlot(int slot);

    void setStackInSlot(int slot, ItemStack stack);

    ItemStack insertItem(int slot, ItemStack stack, boolean simulate);

    ItemStack extractItem(int slot, int amount, boolean simulate);

    int getSlotLimit(int slot);

    boolean isItemValid(int slot, ItemStack stack);

    /**
     * Utilitário auxiliar para inserção em qualquer slot disponível (análogo a ItemHandlerHelper.insertItem).
     */
    default ItemStack insertItemAnySlot(ItemStack stack, boolean simulate) {
        if (stack.isEmpty()) {
            return ItemStack.EMPTY;
        }
        ItemStack remainder = stack.copy();
        for (int i = 0; i < getSlots(); i++) {
            remainder = insertItem(i, remainder, simulate);
            if (remainder.isEmpty()) {
                return ItemStack.EMPTY;
            }
        }
        return remainder;
    }
}
