package de.scc.calculator.network;

import de.scc.calculator.registry.SccBlockEntities;

import de.scc.calculator.platform.storage.SccEnergyStorage;
import de.scc.calculator.platform.storage.SccBulkItemStorage;
import de.scc.calculator.platform.storage.SccFluidTransferStorage;
import de.scc.calculator.platform.storage.SccFluidStack;
import de.scc.calculator.platform.storage.SccEnergyTransferStorage;
import de.scc.calculator.platform.storage.SccFluidHandler;
import de.scc.calculator.platform.storage.SccItemHandler;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import team.reborn.energy.api.EnergyStorage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import de.scc.calculator.platform.storage.SccTransactions;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;

/**
 * Fabric-side local storage block entity. It composes SccStorageData while inheriting
 * the shared, Fabric-native connector configuration and transfer contract.
 */
public final class SccStorageBlockEntity extends SccStorageConnectorBlockEntity {
    private final SccStorageData storage;
    private final SccBulkItemStorage itemTransfer;
    private final SccFluidTransferStorage fluidTransfer;
    private final SccEnergyTransferStorage energyTransfer;

    public SccStorageBlockEntity(BlockPos pos, BlockState state) {
        super(SccBlockEntities.SCC_STORAGE.get(), pos, state);
        if (state.getBlock() instanceof SccStorageBlock storageBlock) {
            this.storage = new SccStorageData(storageBlock.kind(), storageBlock.tier(), this::onStoreChanged);
        } else {
            this.storage = new SccStorageData(SccStorageBlock.Kind.ITEM, StorageTier.BASIC, this::onStoreChanged);
        }
        this.itemTransfer = this.storage.kind() == SccStorageBlock.Kind.ITEM
                ? new SccBulkItemStorage(this.storage.items()) : null;
        this.fluidTransfer = this.storage.kind() == SccStorageBlock.Kind.FLUID
                ? new SccFluidTransferStorage(this.storage.fluids()) : null;
        this.energyTransfer = this.storage.kind() == SccStorageBlock.Kind.ENERGY
                ? new SccEnergyTransferStorage(this.storage.energy()) : null;
        this.storage.limitItemsPerKind(() -> voidPerKind() ? kindSlotSize() : 0L);
        this.storage.limitFluidsPerKind(() -> voidPerKind() ? kindSlotSize() : 0L);
    }

    @Override
    public BlockPos getBlockPos() {
        return worldPosition;
    }


    public SccStorageData storage() {
        return storage;
    }

    public StorageTier grade() {
        return storage.tier();
    }

    public SccStorageBlock.Kind kind() {
        return storage.kind();
    }

    public boolean isFluid() { return kind() == SccStorageBlock.Kind.FLUID; }
    public boolean isEnergy() { return kind() == SccStorageBlock.Kind.ENERGY; }
    public boolean isChemical() { return kind() == SccStorageBlock.Kind.CHEMICAL; }
    public boolean isSource() { return kind() == SccStorageBlock.Kind.SOURCE; }
    public boolean isSoul() { return kind() == SccStorageBlock.Kind.SOUL; }

    public SccItemHandler itemHandler() { return storage.itemHandler(); }
    public SccFluidHandler fluidHandler() { return storage.fluidHandler(); }
    public SccEnergyStorage energyHandler() { return storage.energyHandler(); }

    /** Fabric Transfer API storage exposed to hoppers, pipes and compatible mods. */
    @Override @Nullable public Storage<ItemVariant> itemTransferStorage() { return super.itemTransferStorage(); }
    @Override @Nullable protected Storage<ItemVariant> externalItems() { return itemTransfer; }

    /** Fabric Transfer API fluid storage; values are represented in 81-droplet mB units. */
    @Override @Nullable public Storage<FluidVariant> fluidTransferStorage() { return super.fluidTransferStorage(); }
    @Override @Nullable protected Storage<FluidVariant> externalFluids() { return fluidTransfer; }

    /** Team Reborn Energy API storage exposed to compatible cables and machines. */
    @Override @Nullable public EnergyStorage energyTransferStorage() { return super.energyTransferStorage(); }
    @Override @Nullable protected EnergyStorage externalEnergy() { return energyTransfer; }

    @Override protected boolean isExternalConnector() { return false; }

    @Override public boolean supportsVoidPerKind() {
        if (kindSlotSize() <= 0L) return false;
        return kind() == SccStorageBlock.Kind.ITEM || kind() == SccStorageBlock.Kind.FLUID || isChemical();
    }

    @Override public boolean supportsVoidByFilter() { return !isChemical(); }

    @Override public long kindSlotSize() {
        StorageTier tier = grade();
        if (kind() == SccStorageBlock.Kind.ITEM) {
            return tier.items() <= 0L || tier.tanks() <= 0 ? 0L : Math.max(1L, tier.items() / tier.tanks());
        }
        if (kind() == SccStorageBlock.Kind.FLUID) return Math.max(0, tier.perTank());
        if (isChemical()) return Math.max(0L, tier.perChemicalTank());
        return 0L;
    }

    @Override protected boolean kindSlotFull(ItemVariant resource, long amount) {
        if (!voidPerKind() || kind() != SccStorageBlock.Kind.ITEM || resource == null || resource.isBlank()) return false;
        long limit = kindSlotSize();
        return limit > 0L && storage.items().countOf(resource.toStack()) + amount >= limit;
    }

    @Override protected boolean kindTankFull(FluidVariant resource, long amount) {
        if (!voidPerKind() || kind() != SccStorageBlock.Kind.FLUID || resource == null || resource.isBlank()) return false;
        long limit = kindSlotSize();
        long current = storage.fluids().amountOf(SccFluidStack.fromVariant(resource, SccFluidStack.DROPLETS_PER_MB));
        long addedMb = (amount + SccFluidStack.DROPLETS_PER_MB - 1L) / SccFluidStack.DROPLETS_PER_MB;
        return limit > 0L && current + addedMb >= limit;
    }

    @Override protected void onVoidModeChanged() {
        storage.limitItemsPerKind(() -> voidPerKind() ? kindSlotSize() : 0L);
        storage.limitFluidsPerKind(() -> voidPerKind() ? kindSlotSize() : 0L);
        super.onVoidModeChanged();
    }
    public SourceHandler sourceHandler() {
        SourceHandler delegate = storage.sourceHandler();
        return new SourceHandler() {
            @Override public long stored() { return flow().reads() ? delegate.stored() : 0L; }
            @Override public long capacity() { return delegate.capacity(); }
            @Override public long receive(long amount, boolean simulate) {
                return flow().writes() ? delegate.receive(amount, simulate) : 0L;
            }
            @Override public long extract(long amount, boolean simulate) {
                return flow().reads() ? delegate.extract(amount, simulate) : 0L;
            }
        };
    }
    public SoulHandler soulHandler() {
        SoulHandler delegate = storage.soulHandler();
        return new SoulHandler() {
            @Override public long stored() { return flow().reads() ? delegate.stored() : 0L; }
            @Override public long capacity() { return delegate.capacity(); }
            @Override public long receive(long amount, boolean simulate) {
                return flow().writes() ? delegate.receive(amount, simulate) : 0L;
            }
            @Override public long extract(long amount, boolean simulate) {
                return flow().reads() ? delegate.extract(amount, simulate) : 0L;
            }
        };
    }
    public SccChemicalAccess chemicalAccess() {
        SccChemicalStore delegate = storage.chemicals();
        return new SccChemicalAccess() {
            @Override public void collect(java.util.Map<String, Long> target) {
                if (flow().reads()) delegate.collect(target);
            }
            @Override public long count(String chemicalId) {
                return flow().reads() ? delegate.count(chemicalId) : 0L;
            }
            @Override public long insert(String chemicalId, long amount, boolean simulate) {
                return flow().writes() ? fillChemical(chemicalId, amount, simulate) : 0L;
            }
            @Override public long drain(String chemicalId, long amount, boolean simulate) {
                return flow().reads() ? drainChemical(chemicalId, amount, simulate) : 0L;
            }
        };
    }

    public long fillUsed() { return storage.fillUsed(); }
    public long fillTotal() { return storage.fillTotal(); }
    public boolean unlimitedStore() { return storage.unlimited(); }
    public long[] slotUsage() { return storage.slotUsage(); }
    public boolean emptyStore() { return storage.empty(); }

    /** Moves all local item entries into other writable network endpoints atomically. */
    public long drainIntoNetwork() {
        Level currentLevel = level;
        if (currentLevel == null || currentLevel.isClientSide || kind() != SccStorageBlock.Kind.ITEM
                || itemTransfer == null) return 0L;
        SccNetworkView network = SccNetworkCache.view(currentLevel, worldPosition);
        if (!network.online()) return 0L;
        long movedTotal = 0L;
        for (net.minecraft.world.item.ItemStack snapshot : storage.items().entries().stream()
                .map(net.minecraft.world.item.ItemStack::copy).toList()) {
            if (snapshot.isEmpty()) continue;
            ItemVariant variant = ItemVariant.of(snapshot);
            try (Transaction transaction = SccTransactions.open()) {
                long extracted = itemTransfer.extract(variant, snapshot.getCount(), transaction);
                if (extracted <= 0L) continue;
                long inserted = network.insertExcluding(this, variant, extracted, transaction);
                long remainder = extracted - inserted;
                if (remainder > 0L) itemTransfer.insert(variant, remainder, transaction);
                transaction.commit();
                movedTotal += inserted;
            }
        }
        return movedTotal;
    }

    public void collectChemicals(java.util.Map<String, Long> target) {
        if (flow().reads()) storage.collectChemicals(target);
    }
    public long countChemical(String id) { return flow().reads() ? storage.countChemical(id) : 0L; }
    public long fillChemical(String id, long amount, boolean simulate) {
        if (!flow().writes()) return 0L;
        long inserted = storage.fillChemical(id, amount, simulate);
        if (voidPerKind() && kind() == SccStorageBlock.Kind.CHEMICAL && amount > inserted
                && kindChemicalFull(id, amount - inserted)) return amount;
        return inserted;
    }
    public long drainChemical(String id, long amount, boolean simulate) {
        return flow().reads() ? storage.drainChemical(id, amount, simulate) : 0L;
    }
    private boolean kindChemicalFull(String id, long amount) {
        long limit = kindSlotSize();
        return limit > 0L && storage.countChemical(id) + amount >= limit;
    }

    private void onStoreChanged() {
        setChanged();
        Level currentLevel = level;
        if (currentLevel == null || currentLevel.isClientSide) return;

        BlockState current = getBlockState();
        if (current.hasProperty(FabricSccStorageBlock.FILL)) {
            int fill = FabricSccStorageBlock.fillLevel(fillUsed(), fillTotal());
            if (current.getValue(FabricSccStorageBlock.FILL) != fill) {
                BlockState updated = current.setValue(FabricSccStorageBlock.FILL, fill);
                if (sccHost() != null && sccHostSide() != null) {
                    sccHost().updatePartState(sccHostSide(), updated);
                    return;
                }
                currentLevel.setBlock(worldPosition, updated, 3);
            }
        }
        if (sccHost() == null) currentLevel.updateNeighbourForOutputSignal(worldPosition, current.getBlock());
    }

    /**
     * Server refresh used by the block ticker. SCC recalculated its fill display
     * every 20 ticks, which also makes capacity-config reloads visible even when
     * no item/fluid/energy operation has fired the changed callback.
     */
    public static void serverTick(Level level, BlockPos pos, BlockState state, SccStorageBlockEntity blockEntity) {
        if (level.isClientSide || level.getGameTime() % 20L != 0L) return;
        BlockState partState = blockEntity.getBlockState();
        if (!partState.hasProperty(FabricSccStorageBlock.FILL)) return;
        int fill = FabricSccStorageBlock.fillLevel(blockEntity.fillUsed(), blockEntity.fillTotal());
        if (partState.getValue(FabricSccStorageBlock.FILL) == fill) return;
        BlockState updated = partState.setValue(FabricSccStorageBlock.FILL, fill);
        if (blockEntity.sccHost() != null && blockEntity.sccHostSide() != null) {
            blockEntity.sccHost().updatePartState(blockEntity.sccHostSide(), updated);
        } else {
            level.setBlock(pos, updated, 2);
            blockEntity.setChanged();
            level.updateNeighbourForOutputSignal(pos, updated.getBlock());
        }
    }

    /** Keeps the original nested "store" key used by SCC block-item payloads. */
    public void saveStore(CompoundTag target, HolderLookup.Provider registries) {
        storage.saveStore(target, registries);
    }

    /** Full block-item payload: shared connector configuration plus the local store. */
    public CompoundTag saveItemData(HolderLookup.Provider registries) {
        CompoundTag tag = new CompoundTag();
        super.saveAdditional(tag, registries);
        storage.saveStore(tag, registries);
        return tag;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        storage.saveStore(tag, registries);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        storage.loadStore(tag, registries);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Nullable
    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
