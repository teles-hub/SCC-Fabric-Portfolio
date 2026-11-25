package de.scc.calculator.platform;

import de.scc.calculator.platform.storage.SccEnergyStorage;
import de.scc.calculator.platform.storage.SccFluidHandler;
import de.scc.calculator.platform.storage.SccFluidStack;
import de.scc.calculator.platform.storage.SccItemHandler;
import de.scc.calculator.platform.storage.SccTransactions;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.SlottedStorage;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.storage.base.SingleSlotStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.jetbrains.annotations.Nullable;
import team.reborn.energy.api.EnergyStorage;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiFunction;

/**
 * Exposicao e consulta de Itens/Fluidos/Energia no Fabric 1.21.1.
 *
 * CORRECAO (bugs criticos da Parte 2): os tres adaptadores FabricItemStorageAdapter/FabricFluidStorageAdapter/
 * FabricEnergyStorageAdapter foram REMOVIDOS. Eles simulavam cada operacao isolada e aplicavam a mutacao em
 * addCloseCallback, o que duplica/perde itens. Agora cada BlockEntity entrega um Storage NATIVO e transacional
 * (FabricItemStorage via SccItemStackHandler.asStorage(), FabricFluidStorage, SimpleEnergyStorage).
 * Aqui sobrou o lado de EXPOSICAO (registro) e o lado de LEITURA (SccXFromFabric), que usa Transaction corretamente.
 */
public final class PlatformCapabilities {

    private PlatformCapabilities() {
    }

    // =========================================================================
    // 1. EXPOSICAO: BlockEntity -> mundo (funis, cabos, outros mods)
    // =========================================================================

    public static <T extends BlockEntity> void registerItemStorage(
            BlockEntityType<T> type,
            BiFunction<T, @Nullable Direction, @Nullable Storage<ItemVariant>> provider) {
        ItemStorage.SIDED.registerForBlockEntity((be, side) -> provider.apply(be, side), type);
    }

    public static <T extends BlockEntity> void registerFluidStorage(
            BlockEntityType<T> type,
            BiFunction<T, @Nullable Direction, @Nullable Storage<FluidVariant>> provider) {
        FluidStorage.SIDED.registerForBlockEntity((be, side) -> provider.apply(be, side), type);
    }

    /** Use team.reborn.energy.api.base.SimpleEnergyStorage (ja transacional) como implementacao. */
    public static <T extends BlockEntity> void registerEnergyStorage(
            BlockEntityType<T> type,
            BiFunction<T, @Nullable Direction, @Nullable EnergyStorage> provider) {
        EnergyStorage.SIDED.registerForBlockEntity((be, side) -> provider.apply(be, side), type);
    }

    // =========================================================================
    // 2. LEITURA: SCC -> baus/maquinas externas
    // =========================================================================

    @Nullable
    public static SccItemHandler getItemHandler(Level level, BlockPos pos, @Nullable Direction side) {
        Storage<ItemVariant> storage = ItemStorage.SIDED.find(level, pos, side);
        return storage == null ? null : new SccItemHandlerFromFabric(storage);
    }

    @Nullable
    public static SccFluidHandler getFluidHandler(Level level, BlockPos pos, @Nullable Direction side) {
        Storage<FluidVariant> storage = FluidStorage.SIDED.find(level, pos, side);
        return storage == null ? null : new SccFluidHandlerFromFabric(storage);
    }

    @Nullable
    public static SccEnergyStorage getEnergyStorage(Level level, BlockPos pos, @Nullable Direction side) {
        EnergyStorage storage = EnergyStorage.SIDED.find(level, pos, side);
        return storage == null ? null : new SccEnergyStorageFromFabric(storage);
    }

    // =========================================================================
    // 3. Storage<ItemVariant> -> SccItemHandler
    // =========================================================================

    private static final class SccItemHandlerFromFabric implements SccItemHandler {
        private final Storage<ItemVariant> storage;

        private SccItemHandlerFromFabric(Storage<ItemVariant> storage) {
            this.storage = storage;
        }

        private List<StorageView<ItemVariant>> views() {
            List<StorageView<ItemVariant>> list = new ArrayList<>();
            for (StorageView<ItemVariant> view : storage) {
                list.add(view);
            }
            return list;
        }

        @Override
        public int getSlots() {
            if (storage instanceof SlottedStorage<ItemVariant> slotted) return slotted.getSlotCount();
            return views().size();
        }

        @Override
        public ItemStack getStackInSlot(int slot) {
            if (storage instanceof SlottedStorage<ItemVariant> slotted) {
                if (slot < 0 || slot >= slotted.getSlotCount()) return ItemStack.EMPTY;
                return toStack(slotted.getSlot(slot));
            }
            List<StorageView<ItemVariant>> v = views();
            if (slot < 0 || slot >= v.size()) return ItemStack.EMPTY;
            return toStack(v.get(slot));
        }

        private static ItemStack toStack(StorageView<ItemVariant> v) {
            return v.isResourceBlank() ? ItemStack.EMPTY
                    : v.getResource().toStack((int) Math.min(v.getAmount(), Integer.MAX_VALUE));
        }

        @Override
        public void setStackInSlot(int slot, ItemStack stack) {
            // Nao suportado por Storage generico. Use insertItem/extractItem.
            throw new UnsupportedOperationException("setStackInSlot nao existe em Storage<ItemVariant> externo");
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack.isEmpty()) return ItemStack.EMPTY;

            // O contrato do SccItemHandler e indexado por slot. Nunca transforme um
            // indice invalido em uma insercao global, pois isso colocaria o item em
            // outro slot sem que o chamador soubesse.
            SingleSlotStorage<ItemVariant> target;
            if (storage instanceof SlottedStorage<ItemVariant> slotted) {
                if (slot < 0 || slot >= slotted.getSlotCount()) return stack.copy();
                target = slotted.getSlot(slot);
            } else {
                List<StorageView<ItemVariant>> slotViews = views();
                if (slot < 0 || slot >= slotViews.size()) return stack.copy();
                StorageView<ItemVariant> view = slotViews.get(slot);
                if (!(view instanceof SingleSlotStorage<?> single)) return stack.copy();
                @SuppressWarnings("unchecked")
                SingleSlotStorage<ItemVariant> typed = (SingleSlotStorage<ItemVariant>) single;
                target = typed;
            }

            ItemVariant variant = ItemVariant.of(stack);
            try (Transaction tx = SccTransactions.open()) {
                long inserted = target.insert(variant, stack.getCount(), tx);
                if (!simulate) tx.commit();
                int remaining = stack.getCount() - (int) inserted;
                return remaining <= 0 ? ItemStack.EMPTY : stack.copyWithCount(remaining);
            }
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (amount <= 0) return ItemStack.EMPTY;
            StorageView<ItemVariant> view;
            if (storage instanceof SlottedStorage<ItemVariant> slotted) {
                if (slot < 0 || slot >= slotted.getSlotCount()) return ItemStack.EMPTY;
                view = slotted.getSlot(slot);
            } else {
                List<StorageView<ItemVariant>> v = views();
                if (slot < 0 || slot >= v.size()) return ItemStack.EMPTY;
                view = v.get(slot);
            }
            if (view.isResourceBlank()) return ItemStack.EMPTY;
            ItemVariant resource = view.getResource();
            try (Transaction tx = SccTransactions.open()) {
                long extracted = view.extract(resource, amount, tx);
                if (!simulate) tx.commit();
                return extracted <= 0 ? ItemStack.EMPTY : resource.toStack((int) extracted);
            }
        }

        @Override
        public int getSlotLimit(int slot) {
            if (storage instanceof SlottedStorage<ItemVariant> slotted) {
                if (slot < 0 || slot >= slotted.getSlotCount()) return 0;
                return (int) Math.min(slotted.getSlot(slot).getCapacity(), Integer.MAX_VALUE);
            }
            List<StorageView<ItemVariant>> slotViews = views();
            if (slot < 0 || slot >= slotViews.size()) return 0;
            return (int) Math.min(slotViews.get(slot).getCapacity(), Integer.MAX_VALUE);
        }

        @Override
        public boolean isItemValid(int slot, ItemStack stack) {
            if (stack.isEmpty()) return false;
            if (storage instanceof SlottedStorage<ItemVariant> slotted) {
                return slot >= 0 && slot < slotted.getSlotCount();
            }
            List<StorageView<ItemVariant>> slotViews = views();
            return slot >= 0 && slot < slotViews.size();
        }
    }

    // =========================================================================
    // 4. Storage<FluidVariant> -> SccFluidHandler (conversao exata mB <-> droplets)
    // =========================================================================

    private static final class SccFluidHandlerFromFabric implements SccFluidHandler {
        private final Storage<FluidVariant> storage;

        private SccFluidHandlerFromFabric(Storage<FluidVariant> storage) {
            this.storage = storage;
        }

        private List<StorageView<FluidVariant>> views() {
            List<StorageView<FluidVariant>> list = new ArrayList<>();
            for (StorageView<FluidVariant> view : storage) {
                list.add(view);
            }
            return list;
        }

        @Override
        public int getTanks() {
            return views().size();
        }

        @Override
        public SccFluidStack getFluidInTank(int tank) {
            List<StorageView<FluidVariant>> v = views();
            if (tank < 0 || tank >= v.size()) return SccFluidStack.EMPTY;
            return SccFluidStack.fromVariant(v.get(tank).getResource(), v.get(tank).getAmount());
        }

        @Override
        public int getTankCapacity(int tank) {
            List<StorageView<FluidVariant>> v = views();
            if (tank < 0 || tank >= v.size()) return 0;
            return (int) Math.min(v.get(tank).getCapacity() / SccFluidStack.DROPLETS_PER_MB, Integer.MAX_VALUE);
        }

        @Override
        public boolean isFluidValid(int tank, SccFluidStack stack) {
            return !stack.isEmpty() && tank >= 0 && tank < getTanks();
        }

        /**
         * CORRECAO: o resultado e arredondado para baixo a multiplos de 81 droplets ANTES de confirmar,
         * para que o que e movido seja exatamente o que e reportado em mB (sem perda/duplicacao de sub-mB).
         */
        @Override
        public int fill(SccFluidStack resource, boolean simulate) {
            if (resource.isEmpty()) return 0;
            FluidVariant variant = resource.toVariant();
            long exactDroplets;
            try (Transaction tx = SccTransactions.open()) {
                long sim = storage.insert(variant, resource.toDroplets(), tx);
                exactDroplets = (sim / SccFluidStack.DROPLETS_PER_MB) * SccFluidStack.DROPLETS_PER_MB;
            }
            if (simulate || exactDroplets <= 0) return (int) (exactDroplets / SccFluidStack.DROPLETS_PER_MB);
            try (Transaction tx = SccTransactions.open()) {
                long real = storage.insert(variant, exactDroplets, tx);
                if (real != exactDroplets) return 0; // estado mudou entre simulacao e execucao: aborta
                tx.commit();
                return (int) (real / SccFluidStack.DROPLETS_PER_MB);
            }
        }

        @Override
        public SccFluidStack drain(SccFluidStack resource, boolean simulate) {
            if (resource.isEmpty()) return SccFluidStack.EMPTY;
            return drainVariant(resource.toVariant(), resource.toDroplets(), simulate);
        }

        @Override
        public SccFluidStack drain(int maxDrainMb, boolean simulate) {
            if (maxDrainMb <= 0) return SccFluidStack.EMPTY;
            FluidVariant first = null;
            for (StorageView<FluidVariant> view : storage) {
                if (!view.isResourceBlank() && view.getAmount() >= SccFluidStack.DROPLETS_PER_MB) {
                    first = view.getResource();
                    break;
                }
            }
            if (first == null) return SccFluidStack.EMPTY;
            return drainVariant(first, (long) maxDrainMb * SccFluidStack.DROPLETS_PER_MB, simulate);
        }

        private SccFluidStack drainVariant(FluidVariant variant, long maxDroplets, boolean simulate) {
            long exactDroplets;
            try (Transaction tx = SccTransactions.open()) {
                long sim = storage.extract(variant, maxDroplets, tx);
                exactDroplets = (sim / SccFluidStack.DROPLETS_PER_MB) * SccFluidStack.DROPLETS_PER_MB;
            }
            if (exactDroplets <= 0) return SccFluidStack.EMPTY;
            if (simulate) return SccFluidStack.fromVariant(variant, exactDroplets);
            try (Transaction tx = SccTransactions.open()) {
                long real = storage.extract(variant, exactDroplets, tx);
                if (real != exactDroplets) return SccFluidStack.EMPTY;
                tx.commit();
                return SccFluidStack.fromVariant(variant, real);
            }
        }
    }

    // =========================================================================
    // 5. Team Reborn EnergyStorage -> SccEnergyStorage
    // =========================================================================

    private static final class SccEnergyStorageFromFabric implements SccEnergyStorage {
        private final EnergyStorage storage;

        private SccEnergyStorageFromFabric(EnergyStorage storage) {
            this.storage = storage;
        }

        @Override
        public int receiveEnergy(int maxReceive, boolean simulate) {
            if (!storage.supportsInsertion() || maxReceive <= 0) return 0;
            try (Transaction tx = SccTransactions.open()) {
                long inserted = storage.insert(maxReceive, tx);
                if (!simulate) tx.commit();
                return (int) inserted;
            }
        }

        @Override
        public int extractEnergy(int maxExtract, boolean simulate) {
            if (!storage.supportsExtraction() || maxExtract <= 0) return 0;
            try (Transaction tx = SccTransactions.open()) {
                long extracted = storage.extract(maxExtract, tx);
                if (!simulate) tx.commit();
                return (int) extracted;
            }
        }

        @Override
        public int getEnergyStored() {
            return (int) Math.min(storage.getAmount(), Integer.MAX_VALUE);
        }

        @Override
        public int getMaxEnergyStored() {
            return (int) Math.min(storage.getCapacity(), Integer.MAX_VALUE);
        }

        @Override
        public boolean canExtract() {
            return storage.supportsExtraction();
        }

        @Override
        public boolean canReceive() {
            return storage.supportsInsertion();
        }
    }
}
