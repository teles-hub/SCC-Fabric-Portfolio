package de.scc.calculator.platform.storage;

import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.StoragePreconditions;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.NonNullList;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Iterator;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Armazenamento de itens com slots, transacional via SnapshotParticipant.
 * CORRECOES em relacao a errata: (1) import de SlottedStorage (pacote ...storage, nao ...storage.base);
 * (2) callback por slot (IntConsumer) para suportar onContentsChanged(slot); (3) limite de pilha
 * calculado com o ItemStack real (respeita DataComponents); (4) acesso a lista de pilhas para NBT.
 * Contrato: updateSnapshots(transaction) e chamado ANTES de qualquer mutacao.
 * NAO implemente SccItemHandler nesta classe: getSlots() (int) conflita com SlottedStorage.getSlots() (List).
 * Use SccItemStackHandler (composicao).
 */
public class FabricItemStorage extends SnapshotParticipant<FabricItemStorage.StorageSnapshot> implements SlottedStorage<ItemVariant> {
    /** O estado transacional inclui tambem os slots pendentes de notificacao. */
    public record StorageSnapshot(List<ItemStack> stacks, BitSet dirtySlots) {
    }
    private final NonNullList<ItemStack> stacks;
    private final List<SlotView> slotViews;
    private final IntConsumer onSlotChanged;
    private final BitSet dirtySlots = new BitSet();

    public FabricItemStorage(int size, IntConsumer onSlotChanged) {
        this.stacks = NonNullList.withSize(size, ItemStack.EMPTY);
        this.slotViews = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            this.slotViews.add(new SlotView(i));
        }
        this.onSlotChanged = onSlotChanged;
    }

    public FabricItemStorage(int size, Runnable onChange) {
        this(size, slot -> {
            if (onChange != null) onChange.run();
        });
    }

    public boolean isItemValid(int slot, ItemVariant variant) {
        return true;
    }

    public int getSlotLimit(int slot) {
        return 64;
    }

    public NonNullList<ItemStack> getStacks() {
        return this.stacks;
    }

    public ItemStack getStackInSlot(int slot) {
        return this.stacks.get(slot);
    }

    /** Escrita direta (semantica NeoForge setStackInSlot): NAO e transacional. */
    public void setStackInSlot(int slot, ItemStack stack) {
        this.stacks.set(slot, stack);
        if (this.onSlotChanged != null) this.onSlotChanged.accept(slot);
    }

    @Override
    public int getSlotCount() {
        return this.stacks.size();
    }

    @Override
    public SingleSlotStorage<ItemVariant> getSlot(int slot) {
        return this.slotViews.get(slot);
    }

    @Override
    public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maxAmount);
        long inserted = 0;
        for (SlotView slot : this.slotViews) {
            inserted += slot.insert(resource, maxAmount - inserted, transaction);
            if (inserted >= maxAmount) break;
        }
        return inserted;
    }

    @Override
    public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
        StoragePreconditions.notBlankNotNegative(resource, maxAmount);
        long extracted = 0;
        for (SlotView slot : this.slotViews) {
            extracted += slot.extract(resource, maxAmount - extracted, transaction);
            if (extracted >= maxAmount) break;
        }
        return extracted;
    }

    @Override
    @SuppressWarnings({"unchecked", "rawtypes"})
    public Iterator<StorageView<ItemVariant>> iterator() {
        return (Iterator) this.slotViews.iterator();
    }

    @Override
    protected StorageSnapshot createSnapshot() {
        List<ItemStack> stackCopies = new ArrayList<>(this.stacks.size());
        for (ItemStack stack : this.stacks) {
            stackCopies.add(stack.copy());
        }
        return new StorageSnapshot(stackCopies, (BitSet) this.dirtySlots.clone());
    }

    @Override
    protected void readSnapshot(StorageSnapshot snapshot) {
        for (int i = 0; i < this.stacks.size(); i++) {
            this.stacks.set(i, snapshot.stacks().get(i).copy());
        }
        // Rollback precisa restaurar as notificacoes junto com os itens. Sem isso,
        // um slot revertido ainda dispararia onContentsChanged no commit externo.
        this.dirtySlots.clear();
        this.dirtySlots.or(snapshot.dirtySlots());
    }

    @Override
    protected void onFinalCommit() {
        if (this.onSlotChanged == null) {
            this.dirtySlots.clear();
            return;
        }
        // Copia e limpa antes de notificar. onFinalCommit roda depois do fechamento
        // da transacao externa: o callback deve apenas notificar/marcar dirty, nao
        // iniciar outra transferencia dentro do callback de fechamento.
        BitSet copy = (BitSet) this.dirtySlots.clone();
        this.dirtySlots.clear();
        for (int i = copy.nextSetBit(0); i >= 0; i = copy.nextSetBit(i + 1)) {
            this.onSlotChanged.accept(i);
        }
    }

    private class SlotView implements SingleSlotStorage<ItemVariant> {
        private final int index;

        private SlotView(int index) {
            this.index = index;
        }

        @Override
        public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maxAmount);
            if (!isItemValid(this.index, resource)) return 0;

            ItemStack current = stacks.get(this.index);
            if (!current.isEmpty() && !resource.matches(current)) return 0;

            int limit = Math.min(getSlotLimit(this.index), resource.toStack().getMaxStackSize());
            int space = limit - current.getCount();
            if (space <= 0) return 0;

            int toInsert = (int) Math.min(space, maxAmount);
            if (toInsert <= 0) return 0;

            updateSnapshots(transaction);

            if (current.isEmpty()) {
                stacks.set(this.index, resource.toStack(toInsert));
            } else {
                current.grow(toInsert);
            }
            dirtySlots.set(this.index);
            return toInsert;
        }

        @Override
        public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            StoragePreconditions.notBlankNotNegative(resource, maxAmount);
            ItemStack current = stacks.get(this.index);
            if (current.isEmpty() || !resource.matches(current)) return 0;

            int toExtract = (int) Math.min(current.getCount(), maxAmount);
            if (toExtract <= 0) return 0;

            updateSnapshots(transaction);

            current.shrink(toExtract);
            if (current.isEmpty()) {
                stacks.set(this.index, ItemStack.EMPTY);
            }
            dirtySlots.set(this.index);
            return toExtract;
        }

        @Override
        public boolean isResourceBlank() {
            return stacks.get(this.index).isEmpty();
        }

        @Override
        public ItemVariant getResource() {
            return ItemVariant.of(stacks.get(this.index));
        }

        @Override
        public long getAmount() {
            return stacks.get(this.index).getCount();
        }

        @Override
        public long getCapacity() {
            ItemStack current = stacks.get(this.index);
            return current.isEmpty()
                    ? getSlotLimit(this.index)
                    : Math.min(getSlotLimit(this.index), current.getMaxStackSize());
        }
    }
}
