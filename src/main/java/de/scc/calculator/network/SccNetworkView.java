package de.scc.calculator.network;

import de.scc.calculator.calc.StackKey;
import de.scc.calculator.network.SccItemStore;
import de.scc.calculator.platform.storage.SccFluidStack;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import de.scc.calculator.platform.storage.SccTransactions;
import team.reborn.energy.api.EnergyStorage;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable topology snapshot plus live aggregate operations over connected SCC storages.
 * Contents are read from the backing stores on demand; no stale inventory copy is retained.
 */
public final class SccNetworkView {
    private final Level level;
    private final BlockPos origin;
    private final List<BlockPos> nodes;
    private final List<BlockPos> cables;
    private final List<SccStorageBlockEntity> storages;
    private final List<SccStorageConnectorBlockEntity> connectors;
    private final List<BlockPos> terminals;
    private final List<BlockPos> devices;
    private final List<MachineEndpoint> machines;
    private final boolean truncated;
    private final Storage<ItemVariant> itemTransfer;
    private final Storage<FluidVariant> fluidTransfer;
    private final EnergyStorage energyTransfer;
    private final SccNetworkChemicalAccess chemicalTransfer;
    private final SccNetworkLongStore sourceTransfer;
    private final SccNetworkLongStore soulTransfer;

    private SccNetworkView(Level level, BlockPos origin, List<BlockPos> nodes,
                           List<BlockPos> cables, List<SccStorageBlockEntity> storages,
                           List<SccStorageConnectorBlockEntity> connectors, List<BlockPos> terminals, List<BlockPos> devices, List<MachineEndpoint> machines, boolean truncated) {
        this.level = level;
        this.origin = origin == null ? null : origin.immutable();
        this.nodes = List.copyOf(nodes);
        this.cables = List.copyOf(cables);
        this.storages = List.copyOf(storages);
        this.connectors = List.copyOf(connectors);
        this.terminals = List.copyOf(terminals);
        this.devices = List.copyOf(devices);
        this.machines = List.copyOf(machines);
        this.truncated = truncated;
        List<ItemEndpoint> insertEndpoints = new ArrayList<>();
        List<ItemEndpoint> extractEndpoints = new ArrayList<>();
        List<Storage<FluidVariant>> fluidParts = new ArrayList<>();
        List<EnergyStorage> energyParts = new ArrayList<>();
        List<SccChemicalAccess> chemicalParts = new ArrayList<>();
        List<LongStoreHandler> sourceParts = new ArrayList<>();
        List<LongStoreHandler> soulParts = new ArrayList<>();
        for (SccStorageBlockEntity storage : this.storages) {
            Storage<ItemVariant> items = storage.itemTransferStorage();
            if (items != null) {
                if (storage.flow().writes()) insertEndpoints.add(new ItemEndpoint(
                        storage.priority(), storage.getBlockPos().asLong(), -1, items));
                if (storage.flow().reads()) extractEndpoints.add(new ItemEndpoint(
                        storage.extractPriority(), storage.getBlockPos().asLong(), -1, items));
            }
            if (storage.fluidTransferStorage() != null) fluidParts.add(storage.fluidTransferStorage());
            if (storage.energyTransferStorage() != null) energyParts.add(storage.energyTransferStorage());
            if (storage.kind() == SccStorageBlock.Kind.CHEMICAL) chemicalParts.add(storage.chemicalAccess());
            if (storage.kind() == SccStorageBlock.Kind.SOURCE) sourceParts.add(storage.sourceHandler());
            if (storage.kind() == SccStorageBlock.Kind.SOUL) soulParts.add(storage.soulHandler());
        }
        for (SccStorageConnectorBlockEntity connector : this.connectors) {
            int side = connector.sccHostSide() == null ? -1 : connector.sccHostSide().get3DDataValue();
            if (connector.hasItemEndpoint()) {
                if (connector.flow().writes()) insertEndpoints.add(new ItemEndpoint(
                        connector.priority(), connector.getBlockPos().asLong(), side, connector.itemTransferStorage()));
                if (connector.flow().reads()) extractEndpoints.add(new ItemEndpoint(
                        connector.extractPriority(), connector.getBlockPos().asLong(), side, connector.itemTransferStorage()));
            }
            if (connector.hasFluidEndpoint()) fluidParts.add(connector.fluidTransferStorage());
            if (connector.hasEnergyEndpoint()) energyParts.add(connector.energyTransferStorage());
        }
        Comparator<ItemEndpoint> endpointOrder = Comparator.comparingInt(ItemEndpoint::priority).reversed()
                .thenComparingLong(ItemEndpoint::position).thenComparingInt(ItemEndpoint::side);
        insertEndpoints.sort(endpointOrder);
        extractEndpoints.sort(endpointOrder);
        List<Storage<ItemVariant>> itemInsertParts = insertEndpoints.stream().map(ItemEndpoint::storage).toList();
        List<Storage<ItemVariant>> itemExtractParts = extractEndpoints.stream().map(ItemEndpoint::storage).toList();
        this.itemTransfer = new SccNetworkItemStorage(itemInsertParts, itemExtractParts);
        this.fluidTransfer = new SccNetworkFluidStorage(fluidParts);
        this.energyTransfer = new SccNetworkEnergyStorage(energyParts);
        this.chemicalTransfer = new SccNetworkChemicalAccess(chemicalParts);
        this.sourceTransfer = new SccNetworkLongStore(sourceParts);
        this.soulTransfer = new SccNetworkLongStore(soulParts);
    }

    private record ItemEndpoint(int priority, long position, int side, Storage<ItemVariant> storage) { }

    static SccNetworkView empty(Level level, BlockPos origin) {
        return new SccNetworkView(level, origin, List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), false);
    }

    static SccNetworkView from(Level level, BlockPos origin, SccNetworkTopology.Result topology) {
        List<BlockPos> nodes = new ArrayList<>();
        List<BlockPos> cables = new ArrayList<>();
        List<SccStorageBlockEntity> storages = new ArrayList<>();
        List<SccStorageConnectorBlockEntity> connectors = new ArrayList<>();
        List<BlockPos> terminals = new ArrayList<>();
        List<BlockPos> devices = new ArrayList<>();
        List<MachineEndpoint> machines = new ArrayList<>();
        if (level != null && topology != null) {
            for (SccNetworkTopology.Node node : topology.nodes()) {
                BlockPos pos = new BlockPos(node.position().x(), node.position().y(), node.position().z());
                nodes.add(pos.immutable());
                if (node.kind() == SccNetworkTopology.Kind.CABLE) cables.add(pos.immutable());
                else if (node.kind() == SccNetworkTopology.Kind.TERMINAL) terminals.add(pos.immutable());
                else if (node.kind() == SccNetworkTopology.Kind.DEVICE) devices.add(pos.immutable());
                else if (node.kind() == SccNetworkTopology.Kind.MACHINE) {
                    ResourceLocation alias = ResourceLocation.tryParse(node.machineAlias());
                    if (alias != null) machines.add(new MachineEndpoint(pos.immutable(), alias));
                }
                else if (level.getBlockEntity(pos) instanceof SccStorageBlockEntity storage) storages.add(storage);
                else if (level.getBlockEntity(pos) instanceof SccStorageConnectorBlockEntity connector) connectors.add(connector);

                // A Cable Bus occupies one world position but may host multiple
                // network endpoints. Count those hosted endpoints exactly once
                // in the aggregate view; do not enqueue them as extra graph cells.
                if (level.getBlockEntity(pos) instanceof SccCableBusBlockEntity bus) {
                    for (net.minecraft.world.level.block.entity.BlockEntity part : bus.parts()) {
                        if (part instanceof SccStorageBlockEntity storagePart) {
                            storages.add(storagePart);
                        } else if (part instanceof SccStorageConnectorBlockEntity connectorPart) {
                            connectors.add(connectorPart);
                        } else if (part instanceof SccTerminalBlockEntity) {
                            terminals.add(pos.immutable());
                        } else if (part instanceof SccRedstoneEmitterBlockEntity) {
                            devices.add(pos.immutable());
                        }
                    }
                }
            }
        }
        storages.sort(Comparator.comparingLong(storage -> storage.getBlockPos().asLong()));
        connectors.sort(Comparator.comparingLong(connector -> connector.getBlockPos().asLong()));
        machines.sort(Comparator.comparingLong(machine -> machine.position().asLong()));
        return new SccNetworkView(level, origin, nodes, cables, storages, connectors, terminals, devices, machines,
                topology != null && topology.truncated());
    }

    public Level level() { return level; }
    public BlockPos origin() { return origin; }
    public List<BlockPos> nodes() { return nodes; }
    public List<BlockPos> cables() { return cables; }
    public List<SccStorageBlockEntity> storages() { return storages; }
    public List<SccStorageConnectorBlockEntity> connectors() { return connectors; }
    /** Durable internal item stores; external connector endpoints are not escrow owners. */
    public List<SccItemStore> itemStores() {
        List<SccItemStore> result = new ArrayList<>();
        for (SccStorageBlockEntity storage : storages) {
            if (storage.kind() != SccStorageBlock.Kind.ITEM) continue;
            SccItemStore store = storage.storage().items();
            if (store != null) result.add(store);
        }
        return List.copyOf(result);
    }

    public List<BlockPos> terminals() { return terminals; }
    public List<BlockPos> devices() { return devices; }
    public int deviceCount() { return devices.size(); }
    public List<MachineEndpoint> machines() { return machines; }
    public int machineCount() { return machines.size(); }
    public int nodeCount() { return nodes.size(); }
    public int cableCount() { return cables.size(); }
    public int storageCount() {
        return storages.size() + (int) connectors.stream()
                .filter(connector -> connector.hasItemEndpoint() || connector.hasFluidEndpoint() || connector.hasEnergyEndpoint())
                .count();
    }
    public int terminalCount() { return terminals.size(); }
    public int itemStorageCount() { return countStores(SccStorageBlock.Kind.ITEM) + (int) connectors.stream().filter(SccStorageConnectorBlockEntity::hasItemEndpoint).count(); }
    public int fluidStorageCount() { return countStores(SccStorageBlock.Kind.FLUID) + (int) connectors.stream().filter(SccStorageConnectorBlockEntity::hasFluidEndpoint).count(); }
    public int energyStorageCount() { return countStores(SccStorageBlock.Kind.ENERGY) + (int) connectors.stream().filter(SccStorageConnectorBlockEntity::hasEnergyEndpoint).count(); }
    public int chemicalStorageCount() { return countStores(SccStorageBlock.Kind.CHEMICAL); }
    public int sourceStorageCount() { return countStores(SccStorageBlock.Kind.SOURCE); }
    public int soulStorageCount() { return countStores(SccStorageBlock.Kind.SOUL); }
    public boolean truncated() { return truncated; }
    public boolean online() {
        return level != null && !nodes.isEmpty() && (!storages.isEmpty()
                || connectors.stream().anyMatch(connector -> connector.hasItemEndpoint()
                || connector.hasFluidEndpoint() || connector.hasEnergyEndpoint()));
    }

    /** A loaded, adapter-recognized machine attached to this network component. */
    public record MachineEndpoint(BlockPos position, ResourceLocation alias) {
        public MachineEndpoint {
            if (position == null || alias == null) throw new IllegalArgumentException("Machine endpoint needs position and alias");
            position = position.immutable();
        }
    }

    /** Aggregated transactional item view spanning every connected item store. */
    public Storage<ItemVariant> itemTransferStorage() { return itemTransfer; }

    /** Aggregated transactional fluid view spanning every connected fluid store. */
    public Storage<FluidVariant> fluidTransferStorage() { return fluidTransfer; }

    /** Aggregated transactional energy view spanning every connected energy store. */
    public EnergyStorage energyTransferStorage() { return energyTransfer; }

    /** Aggregate access for SCC's registry-independent chemical tanks. */
    public SccNetworkChemicalAccess chemicalTransferStorage() { return chemicalTransfer; }

    /** Aggregate access for Source values across connected SCC stores. */
    public LongStoreHandler sourceTransferStorage() { return sourceTransfer; }

    /** Aggregate access for Soul values across connected SCC stores. */
    public LongStoreHandler soulTransferStorage() { return soulTransfer; }

    public Map<StackKey, Long> itemContents() {
        Map<StackKey, Long> contents = new LinkedHashMap<>();
        for (StorageView<ItemVariant> view : itemTransfer) {
            if (view.isResourceBlank() || view.getAmount() <= 0L) continue;
            ItemStack stack = view.getResource().toStack((int) Math.min(view.getAmount(), Integer.MAX_VALUE));
            StackKey key = StackKey.of(stack);
            if (key != null) contents.merge(key, view.getAmount(), SccNetworkView::saturatedAdd);
        }
        return Collections.unmodifiableMap(contents);
    }

    public Map<Item, Long> itemCountsByItem() {
        Map<Item, Long> counts = new LinkedHashMap<>();
        for (Map.Entry<StackKey, Long> entry : itemContents().entrySet()) {
            if (entry.getKey() != null && entry.getKey().item() != null) {
                counts.merge(entry.getKey().item(), entry.getValue(), SccNetworkView::saturatedAdd);
            }
        }
        return Collections.unmodifiableMap(counts);
    }

    public long count(StackKey key) {
        if (key == null) return 0L;
        long total = 0L;
        for (StorageView<ItemVariant> view : itemTransfer) {
            if (view.isResourceBlank() || view.getAmount() <= 0L) continue;
            ItemStack stack = view.getResource().toStack((int) Math.min(view.getAmount(), Integer.MAX_VALUE));
            if (key.matches(stack)) total = saturatedAdd(total, view.getAmount());
        }
        return total;
    }

    /** Inserts transactionally into internal stores and external connector targets. */
    public ItemStack insert(ItemStack stack, boolean simulate) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        try (Transaction transaction = SccTransactions.open()) {
            long moved = itemTransfer.insert(ItemVariant.of(stack), stack.getCount(), transaction);
            if (!simulate) transaction.commit();
            long remaining = stack.getCount() - moved;
            return remaining <= 0 ? ItemStack.EMPTY : stack.copyWithCount((int) remaining);
        }
    }

    /** Transactional insertion into all writable endpoints except the source local store. */
    public long insertExcluding(SccStorageBlockEntity excluded, ItemVariant variant,
                                long amount, TransactionContext transaction) {
        if (variant == null || variant.isBlank() || amount <= 0L || transaction == null) return 0L;
        List<ItemEndpoint> endpoints = new ArrayList<>();
        for (SccStorageBlockEntity storage : storages) {
            if (storage == excluded || !storage.flow().writes()) continue;
            Storage<ItemVariant> endpoint = storage.itemTransferStorage();
            if (endpoint != null) endpoints.add(new ItemEndpoint(
                    storage.priority(), storage.getBlockPos().asLong(), -1, endpoint));
        }
        for (SccStorageConnectorBlockEntity connector : connectors) {
            if (!connector.flow().writes() || !connector.hasItemEndpoint()) continue;
            int side = connector.sccHostSide() == null ? -1 : connector.sccHostSide().get3DDataValue();
            endpoints.add(new ItemEndpoint(connector.priority(), connector.getBlockPos().asLong(), side,
                    connector.itemTransferStorage()));
        }
        endpoints.sort(Comparator.comparingInt(ItemEndpoint::priority).reversed()
                .thenComparingLong(ItemEndpoint::position).thenComparingInt(ItemEndpoint::side));
        SccNetworkItemStorage targets = new SccNetworkItemStorage(
                endpoints.stream().map(ItemEndpoint::storage).toList(), List.of());
        return targets.insert(variant, amount, transaction);
    }

    /** Inserts into every network destination except the local storage being drained. */
    public ItemStack insertExcluding(SccStorageBlockEntity excluded, ItemStack stack, boolean simulate) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        List<ItemEndpoint> endpoints = new ArrayList<>();
        for (SccStorageBlockEntity storage : storages) {
            if (storage == excluded || !storage.flow().writes()) continue;
            Storage<ItemVariant> endpoint = storage.itemTransferStorage();
            if (endpoint != null) endpoints.add(new ItemEndpoint(
                    storage.priority(), storage.getBlockPos().asLong(), -1, endpoint));
        }
        for (SccStorageConnectorBlockEntity connector : connectors) {
            if (!connector.flow().writes() || !connector.hasItemEndpoint()) continue;
            int side = connector.sccHostSide() == null ? -1 : connector.sccHostSide().get3DDataValue();
            endpoints.add(new ItemEndpoint(connector.priority(), connector.getBlockPos().asLong(), side,
                    connector.itemTransferStorage()));
        }
        endpoints.sort(Comparator.comparingInt(ItemEndpoint::priority).reversed()
                .thenComparingLong(ItemEndpoint::position).thenComparingInt(ItemEndpoint::side));
        SccNetworkItemStorage targets = new SccNetworkItemStorage(
                endpoints.stream().map(ItemEndpoint::storage).toList(), List.of());
        try (Transaction transaction = SccTransactions.open()) {
            long inserted = targets.insert(ItemVariant.of(stack), stack.getCount(), transaction);
            if (!simulate) transaction.commit();
            long remaining = stack.getCount() - inserted;
            return remaining <= 0L ? ItemStack.EMPTY : stack.copyWithCount((int) remaining);
        }
    }

    /** Extracts a matching component-sensitive variant across internal and external stores. */
    public ItemStack extract(StackKey key, ItemStack exemplar, int amount, boolean simulate) {
        if (key == null || exemplar == null || exemplar.isEmpty() || amount <= 0) return ItemStack.EMPTY;
        try (Transaction transaction = SccTransactions.open()) {
            long moved = itemTransfer.extract(ItemVariant.of(exemplar), amount, transaction);
            if (!simulate) transaction.commit();
            return moved <= 0 ? ItemStack.EMPTY : exemplar.copyWithCount((int) Math.min(moved, Integer.MAX_VALUE));
        }
    }

    public Map<Fluid, Long> fluidContents() {
        Map<Fluid, Long> contents = new LinkedHashMap<>();
        for (StorageView<FluidVariant> view : fluidTransfer) {
            if (view.isResourceBlank() || view.getAmount() < SccFluidStack.DROPLETS_PER_MB) continue;
            long amountMb = view.getAmount() / SccFluidStack.DROPLETS_PER_MB;
            contents.merge(view.getResource().getFluid(), amountMb, SccNetworkView::saturatedAdd);
        }
        return Collections.unmodifiableMap(contents);
    }

    public Map<String, Long> chemicalContents() {
        Map<String, Long> contents = new LinkedHashMap<>();
        for (SccStorageBlockEntity storage : storages) {
            if (storage.kind() == SccStorageBlock.Kind.CHEMICAL) storage.storage().collectChemicals(contents);
        }
        return Collections.unmodifiableMap(contents);
    }

    public long energyStored() { return energyTransfer.getAmount(); }

    public long sourceStored() {
        long total = 0L;
        for (SccStorageBlockEntity storage : storages) {
            if (storage.kind() == SccStorageBlock.Kind.SOURCE) total = saturatedAdd(total, storage.storage().source().stored());
        }
        return total;
    }

    public long soulStored() {
        long total = 0L;
        for (SccStorageBlockEntity storage : storages) {
            if (storage.kind() == SccStorageBlock.Kind.SOUL) total = saturatedAdd(total, storage.storage().souls().stored());
        }
        return total;
    }

    public long totalItemAmount() {
        long total = 0L;
        for (StorageView<ItemVariant> view : itemTransfer) {
            if (!view.isResourceBlank()) total = saturatedAdd(total, view.getAmount());
        }
        return total;
    }

    public long totalFluidAmountMb() {
        long total = 0L;
        for (StorageView<FluidVariant> view : fluidTransfer) {
            if (!view.isResourceBlank()) total = saturatedAdd(total, view.getAmount() / SccFluidStack.DROPLETS_PER_MB);
        }
        return total;
    }

    public boolean empty() {
        for (SccStorageBlockEntity storage : storages) if (!storage.emptyStore()) return false;
        for (StorageView<ItemVariant> view : itemTransfer) if (!view.isResourceBlank() && view.getAmount() > 0L) return false;
        for (StorageView<FluidVariant> view : fluidTransfer) if (!view.isResourceBlank() && view.getAmount() > 0L) return false;
        return energyTransfer.getAmount() <= 0L;
    }

    private int countStores(SccStorageBlock.Kind kind) {
        int count = 0;
        for (SccStorageBlockEntity storage : storages) if (storage.kind() == kind) count++;
        return count;
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }
}
