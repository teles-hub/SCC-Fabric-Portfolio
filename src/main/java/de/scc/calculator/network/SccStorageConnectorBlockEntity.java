package de.scc.calculator.network;

import de.scc.calculator.registry.SccBlockEntities;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorage;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import net.fabricmc.fabric.api.transfer.v1.item.ItemStorage;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.storage.StorageView;
import net.fabricmc.fabric.api.transfer.v1.transaction.TransactionContext;
import net.fabricmc.fabric.api.transfer.v1.transaction.base.SnapshotParticipant;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.Nullable;
import team.reborn.energy.api.EnergyStorage;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Fabric Transfer API bridge to an adjacent external inventory/tank/energy
 * endpoint. All mutations use the caller's TransactionContext; this class
 * never copies external contents into SCC-owned storage.
 */
public class SccStorageConnectorBlockEntity extends SccNetworkBlockEntity {
    public enum Flow {
        BOTH(true, true), IMPORT(true, false), EXPORT(false, true);
        private final boolean reads;
        private final boolean writes;
        Flow(boolean reads, boolean writes) { this.reads = reads; this.writes = writes; }
        public boolean reads() { return reads; }
        public boolean writes() { return writes; }
        public Flow next() { return values()[(ordinal() + 1) % values().length]; }
        static Flow parse(String value) {
            try { return value == null ? BOTH : valueOf(value); }
            catch (IllegalArgumentException ignored) { return BOTH; }
        }
    }

    private Flow flow = Flow.BOTH;
    private int priority;
    @Nullable private Integer extractPriority;
    private boolean filterReads;
    private boolean blacklist;
    private boolean voidOverflow;
    private boolean voidPerKind;
    private final List<ItemStack> filter = new ArrayList<>(27);
    private final List<FluidVariant> fluidFilter = new ArrayList<>(27);
    private FluidVariant lockedFluid = FluidVariant.blank();
    @Nullable private UUID placedBy;
    private long voidedItems;
    private long voidedFluid;
    private final SnapshotParticipant<Long> voidCounter = new SnapshotParticipant<>() {
        @Override protected Long createSnapshot() { return voidedItems; }
        @Override protected void readSnapshot(Long snapshot) { voidedItems = snapshot; }
        @Override protected void onFinalCommit() { setChangedAndSync(); }
    };
    private final SnapshotParticipant<Long> voidFluidCounter = new SnapshotParticipant<>() {
        @Override protected Long createSnapshot() { return voidedFluid; }
        @Override protected void readSnapshot(Long snapshot) { voidedFluid = snapshot; }
        @Override protected void onFinalCommit() { setChangedAndSync(); }
    };

    private final Storage<ItemVariant> itemStorage = new ConnectorItemStorage();
    private final Storage<FluidVariant> fluidStorage = new ConnectorFluidStorage();
    private final EnergyStorage energyStorage = new ConnectorEnergyStorage();

    protected SccStorageConnectorBlockEntity(net.minecraft.world.level.block.entity.BlockEntityType<?> type,
                                               BlockPos pos, BlockState state) {
        super(type, pos, state);
        for (int i = 0; i < 27; i++) { filter.add(ItemStack.EMPTY); fluidFilter.add(FluidVariant.blank()); }
    }

    public SccStorageConnectorBlockEntity(BlockPos pos, BlockState state) {
        this(SccBlockEntities.SCC_STORAGE_CONNECTOR.get(), pos, state);
    }

    /** Local storage blocks inherit the same connector configuration but do not bridge a neighbor. */
    protected boolean isExternalConnector() { return true; }

    @Override public boolean canConnect(Direction face) {
        if (face == null) return false;
        if (!isExternalConnector()) return true;
        if (level == null || !level.hasChunkAt(worldPosition) || face == facing()) return false;
        BlockState state = getBlockState();
        return state.getBlock() instanceof SccStorageConnectorBlock
                && state.getValue(SccStorageConnectorBlock.propertyFor(face));
    }

    public BlockPos storagePos() {
        return isExternalConnector() ? worldPosition.relative(facing()) : worldPosition;
    }

    public boolean voidPerKind() { return voidPerKind; }
    public void setVoidPerKind(boolean enabled) {
        if (enabled && !supportsVoidPerKind()) enabled = false;
        if (voidPerKind == enabled && (!enabled || !voidOverflow)) return;
        voidPerKind = enabled;
        if (enabled) voidOverflow = false;
        onVoidModeChanged();
    }
    public boolean supportsVoidPerKind() { return false; }
    public boolean supportsVoidByFilter() { return true; }
    public long kindSlotSize() { return 0L; }
    protected boolean kindSlotFull(ItemVariant resource, long amount) { return false; }
    protected boolean kindTankFull(FluidVariant resource, long amount) { return false; }
    protected void onVoidModeChanged() { setChangedAndSync(); }


    public Flow flow() { return flow; }
    public int priority() { return priority; }
    public int extractPriority() { return extractPriority == null ? priority : extractPriority; }
    public boolean hasOwnExtractPriority() { return extractPriority != null; }
    public boolean filterReads() { return filterReads; }
    public boolean blacklist() { return blacklist; }
    public boolean voidOverflow() { return voidOverflow; }
    public long voidedItems() { return voidedItems; }
    public long voidedFluid() { return voidedFluid; }
    @Nullable public UUID placedBy() { return placedBy; }
    public void setPlacedBy(@Nullable UUID owner) { placedBy = owner; setChangedAndSync(); }

    public Direction facing() {
        BlockState state = getBlockState();
        return state.hasProperty(SccStorageConnectorBlock.FACING)
                ? state.getValue(SccStorageConnectorBlock.FACING) : Direction.NORTH;
    }

    @Nullable protected Storage<ItemVariant> externalItems() {
        if (!isExternalConnector() || level == null || !level.hasChunkAt(storagePos())) return null;
        return ItemStorage.SIDED.find(level, storagePos(), facing().getOpposite());
    }

    @Nullable protected Storage<FluidVariant> externalFluids() {
        if (!isExternalConnector() || level == null || !level.hasChunkAt(storagePos())) return null;
        return FluidStorage.SIDED.find(level, storagePos(), facing().getOpposite());
    }

    @Nullable protected EnergyStorage externalEnergy() {
        if (!isExternalConnector() || level == null || !level.hasChunkAt(storagePos())) return null;
        return EnergyStorage.SIDED.find(level, storagePos(), facing().getOpposite());
    }

    public boolean hasItemEndpoint() { return externalItems() != null; }
    public boolean hasFluidEndpoint() { return externalFluids() != null; }
    public boolean hasEnergyEndpoint() { return externalEnergy() != null; }
    public Storage<ItemVariant> itemTransferStorage() { return itemStorage; }
    public Storage<FluidVariant> fluidTransferStorage() { return fluidStorage; }
    public EnergyStorage energyTransferStorage() { return energyStorage; }

    public void setFlow(Flow next) {
        if (next == null || next == flow) return;
        flow = next;
        setChangedAndSync();
    }
    public void cycleFlow() { setFlow(flow.next()); }
    public void setPriority(int value) {
        int bounded = Math.max(-999, Math.min(999, value));
        if (priority != bounded) { priority = bounded; setChangedAndSync(); }
    }
    public void setExtractPriority(int value) {
        int bounded = Math.max(-999, Math.min(999, value));
        if (extractPriority == null || extractPriority != bounded) {
            extractPriority = bounded;
            setChangedAndSync();
        }
    }
    public void clearExtractPriority() {
        if (extractPriority != null) { extractPriority = null; setChangedAndSync(); }
    }
    public void setFilterReads(boolean enabled) {
        if (filterReads != enabled) { filterReads = enabled; setChangedAndSync(); }
    }
    public void setBlacklist(boolean enabled) {
        if (blacklist != enabled) { blacklist = enabled; setChangedAndSync(); }
    }
    public void setVoidOverflow(boolean enabled) {
        if (voidOverflow == enabled && (!enabled || !voidPerKind)) return;
        voidOverflow = enabled;
        if (enabled) voidPerKind = false;
        onVoidModeChanged();
    }
    public void cycleVoidMode() {
        if (voidPerKind) setVoidPerKind(false);
        else if (voidOverflow) {
            if (supportsVoidPerKind()) setVoidPerKind(true);
            else setVoidOverflow(false);
        } else if (supportsVoidByFilter()) setVoidOverflow(true);
        else if (supportsVoidPerKind()) setVoidPerKind(true);
    }
    public void setFilterEntry(int slot, ItemStack stack) {
        if (slot < 0 || slot >= filter.size()) return;
        setFilterEntriesAt(slot, stack);
    }

    /** Applies a complete menu snapshot with one persistence/network update. */
    public void setFilterEntries(List<ItemStack> entries) {
        if (entries == null) return;
        boolean changed = false;
        for (int slot = 0; slot < filter.size(); slot++) {
            ItemStack stack = slot < entries.size() ? entries.get(slot) : ItemStack.EMPTY;
            ItemStack normalized = stack == null || stack.isEmpty()
                    ? ItemStack.EMPTY : stack.copyWithCount(1);
            if (sameFilterEntry(filter.get(slot), normalized)) continue;
            filter.set(slot, normalized);
            fluidFilter.set(slot, fluidFromContainer(normalized));
            changed = true;
        }
        if (changed) setChangedAndSync();
    }

    private void setFilterEntriesAt(int slot, ItemStack stack) {
        ItemStack normalized = stack == null || stack.isEmpty()
                ? ItemStack.EMPTY : stack.copyWithCount(1);
        if (sameFilterEntry(filter.get(slot), normalized)) return;
        filter.set(slot, normalized);
        fluidFilter.set(slot, fluidFromContainer(normalized));
        setChangedAndSync();
    }

    private static boolean sameFilterEntry(ItemStack left, ItemStack right) {
        if (left.isEmpty() || right.isEmpty()) return left.isEmpty() && right.isEmpty();
        return left.getCount() == right.getCount() && ItemStack.isSameItemSameComponents(left, right);
    }
    public ItemStack filterEntry(int slot) {
        return slot < 0 || slot >= filter.size() ? ItemStack.EMPTY : filter.get(slot).copy();
    }
    public boolean filterEmpty() {
        for (ItemStack stack : filter) if (!stack.isEmpty()) return false;
        return true;
    }
    public boolean fluidFilterEmpty() {
        for (FluidVariant variant : fluidFilter) if (!variant.isBlank()) return false;
        return true;
    }

    protected boolean matchesFilter(ItemVariant variant) {
        if (variant == null || variant.isBlank() || filterEmpty()) return true;
        ItemStack candidate = variant.toStack();
        boolean found = false;
        for (ItemStack entry : filter) {
            if (!entry.isEmpty() && ItemStack.isSameItemSameComponents(entry, candidate)) {
                found = true;
                break;
            }
        }
        return blacklist ? !found : found;
    }

    protected boolean allowRead(ItemVariant variant) {
        return flow.reads() && (!filterReads || matchesFilter(variant));
    }
    protected boolean allowWrite(ItemVariant variant) {
        return flow.writes() && matchesFilter(variant);
    }

    public void setLockedFluid(@Nullable Fluid fluid) {
        FluidVariant next = fluid == null || fluid == Fluids.EMPTY ? FluidVariant.blank() : FluidVariant.of(fluid);
        if (!next.equals(lockedFluid)) { lockedFluid = next; setChangedAndSync(); }
    }
    @Nullable public Fluid lockedFluid() {
        return lockedFluid.isBlank() ? null : lockedFluid.getFluid();
    }
    public boolean lockToCurrent() {
        Storage<FluidVariant> external = externalFluids();
        if (external == null) return false;
        for (StorageView<FluidVariant> view : external) {
            if (!view.isResourceBlank()) {
                lockedFluid = view.getResource();
                setChangedAndSync();
                return true;
            }
        }
        return false;
    }
    public void clearFluidLock() {
        if (!lockedFluid.isBlank()) { lockedFluid = FluidVariant.blank(); setChangedAndSync(); }
    }
    protected boolean allowsFluid(FluidVariant variant) {
        return lockedFluid.isBlank() || lockedFluid.equals(variant);
    }
    protected boolean matchesFluidFilter(FluidVariant variant) {
        if (variant == null || variant.isBlank() || fluidFilterEmpty()) return false;
        boolean found = fluidFilter.stream().anyMatch(entry -> !entry.isBlank() && entry.equals(variant));
        return blacklist ? !found : found;
    }
    private static FluidVariant fluidFromContainer(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return FluidVariant.blank();
        Storage<FluidVariant> itemStorage = ContainerItemContext.withConstant(stack).find(FluidStorage.ITEM);
        if (itemStorage == null) return FluidVariant.blank();
        for (StorageView<FluidVariant> view : itemStorage) {
            if (!view.isResourceBlank() && view.getAmount() > 0L) return view.getResource();
        }
        return FluidVariant.blank();
    }

    private void setChangedAndSync() {
        setChanged();
        if (sccHost() == null && level != null && !level.isClientSide) {
            BlockState state = getBlockState();
            level.sendBlockUpdated(worldPosition, state, state, 3);
        }
        invalidateNetwork();
    }
    private void invalidateNetwork() {
        if (level != null) SccNetworkCache.invalidate(level);
    }

    private static long saturatedAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  SccStorageConnectorBlockEntity connector) {
        if (level.isClientSide || level.getGameTime() % 10L != 0L) return;
        BlockState current = connector.getBlockState();
        Direction facing = connector.facing();
        BlockState updated = current;
        for (Direction direction : Direction.values()) {
            boolean linked = direction != facing && SccCableBusBlock.hasNetworkPort(level, pos.relative(direction), direction.getOpposite());
            BooleanProperty property = SccStorageConnectorBlock.propertyFor(direction);
            if (updated.getValue(property) != linked) updated = updated.setValue(property, linked);
        }
        if (updated == current) return;
        if (connector.sccHost() != null && connector.sccHostSide() != null) {
            connector.sccHost().updatePartState(connector.sccHostSide(), updated);
        } else {
            level.setBlock(pos, updated, 2);
            connector.setChanged();
            SccNetworkCache.invalidate(level);
        }
    }

    @Override protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putString("flow", flow.name());
        tag.putInt("priority", priority);
        if (extractPriority != null) tag.putInt("prioOut", extractPriority);
        tag.putBoolean("filterReads", filterReads);
        tag.putBoolean("blacklist", blacklist);
        tag.putBoolean("voidOver", voidOverflow);
        tag.putBoolean("voidKind", voidPerKind);
        tag.putLong("voidedCount", voidedItems);
        tag.putLong("voidedFluid", voidedFluid);
        if (placedBy != null) tag.putUUID("placedBy", placedBy);
        if (!lockedFluid.isBlank()) tag.putString("lockFluid", BuiltInRegistries.FLUID.getKey(lockedFluid.getFluid()).toString());
        ListTag entries = new ListTag();
        for (int i = 0; i < filter.size(); i++) {
            ItemStack stack = filter.get(i);
            if (stack.isEmpty()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("slot", i);
            entry.put("stack", stack.save(registries));
            entries.add(entry);
        }
        if (!entries.isEmpty()) tag.put("filter", entries);
        ListTag fluidEntries = new ListTag();
        for (int i = 0; i < fluidFilter.size(); i++) {
            FluidVariant variant = fluidFilter.get(i);
            if (variant.isBlank()) continue;
            CompoundTag entry = new CompoundTag();
            entry.putInt("slot", i);
            entry.putString("fluid", BuiltInRegistries.FLUID.getKey(variant.getFluid()).toString());
            fluidEntries.add(entry);
        }
        if (!fluidEntries.isEmpty()) tag.put("filterFluids", fluidEntries);
    }

    @Override protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        flow = Flow.parse(tag.getString("flow"));
        priority = Math.max(-999, Math.min(999, tag.getInt("priority")));
        extractPriority = tag.contains("prioOut") ? Math.max(-999, Math.min(999, tag.getInt("prioOut"))) : null;
        filterReads = tag.getBoolean("filterReads");
        blacklist = tag.getBoolean("blacklist");
        voidOverflow = tag.getBoolean("voidOver");
        voidPerKind = !voidOverflow && tag.getBoolean("voidKind");
        voidedItems = Math.max(0L, tag.getLong("voidedCount"));
        voidedFluid = Math.max(0L, tag.getLong("voidedFluid"));
        placedBy = tag.hasUUID("placedBy") ? tag.getUUID("placedBy") : null;
        lockedFluid = FluidVariant.blank();
        ResourceLocation fluidId = ResourceLocation.tryParse(tag.getString("lockFluid"));
        if (fluidId != null) {
            Fluid fluid = BuiltInRegistries.FLUID.get(fluidId);
            if (fluid != Fluids.EMPTY) lockedFluid = FluidVariant.of(fluid);
        }
        for (int i = 0; i < filter.size(); i++) {
            filter.set(i, ItemStack.EMPTY);
            fluidFilter.set(i, FluidVariant.blank());
        }
        ListTag fluidEntries = tag.getList("filterFluids", 10);
        for (int i = 0; i < fluidEntries.size(); i++) {
            CompoundTag entry = fluidEntries.getCompound(i);
            int slot = entry.getInt("slot");
            ResourceLocation entryFluidId = ResourceLocation.tryParse(entry.getString("fluid"));
            if (slot < 0 || slot >= fluidFilter.size() || entryFluidId == null) continue;
            Fluid fluid = BuiltInRegistries.FLUID.get(entryFluidId);
            if (fluid != Fluids.EMPTY) fluidFilter.set(slot, FluidVariant.of(fluid));
        }
        ListTag entries = tag.getList("filter", 10);
        for (int i = 0; i < entries.size(); i++) {
            CompoundTag entry = entries.getCompound(i);
            int slot = entry.getInt("slot");
            if (slot < 0 || slot >= filter.size()) continue;
            filter.set(slot, ItemStack.parse(registries, entry.getCompound("stack")).orElse(ItemStack.EMPTY));
        }
    }

    @Override public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }
    @Nullable @Override public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    private final class ConnectorItemStorage implements Storage<ItemVariant> {
        @Override public long insert(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            if (resource.isBlank() || maxAmount <= 0 || !allowWrite(resource)) return 0;
            Storage<ItemVariant> external = externalItems();
            if (external == null) return 0;
            long inserted = external.insert(resource, maxAmount, transaction);
            if (inserted < maxAmount && voidPerKind && kindSlotFull(resource, maxAmount - inserted)) {
                long voided = maxAmount - inserted;
                voidCounter.updateSnapshots(transaction);
                voidedItems = saturatedAdd(voidedItems, voided);
                return maxAmount;
            }
            if (inserted < maxAmount && voidOverflow && !voidPerKind && !blacklist && !filterEmpty()
                    && matchesFilter(resource)) {
                long voided = maxAmount - inserted;
                voidCounter.updateSnapshots(transaction);
                voidedItems = saturatedAdd(voidedItems, voided);
                return maxAmount;
            }
            return inserted;
        }
        @Override public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            if (resource.isBlank() || maxAmount <= 0 || !allowRead(resource)) return 0;
            Storage<ItemVariant> external = externalItems();
            return external == null ? 0 : external.extract(resource, maxAmount, transaction);
        }
        @Override public Iterator<StorageView<ItemVariant>> iterator() {
            Storage<ItemVariant> external = externalItems();
            if (external == null || !flow.reads()) return List.<StorageView<ItemVariant>>of().iterator();
            List<StorageView<ItemVariant>> visible = new ArrayList<>();
            for (StorageView<ItemVariant> view : external) {
                if (!view.isResourceBlank() && allowRead(view.getResource())) visible.add(new FilteredItemView(view));
            }
            return visible.iterator();
        }
    }

    private final class FilteredItemView implements StorageView<ItemVariant> {
        private final StorageView<ItemVariant> delegate;
        private FilteredItemView(StorageView<ItemVariant> delegate) { this.delegate = delegate; }
        @Override public boolean isResourceBlank() { return delegate.isResourceBlank() || !allowRead(delegate.getResource()); }
        @Override public ItemVariant getResource() { return isResourceBlank() ? ItemVariant.blank() : delegate.getResource(); }
        @Override public long getAmount() { return isResourceBlank() ? 0 : delegate.getAmount(); }
        @Override public long getCapacity() { return delegate.getCapacity(); }
        @Override public long extract(ItemVariant resource, long maxAmount, TransactionContext transaction) {
            return allowRead(resource) ? delegate.extract(resource, maxAmount, transaction) : 0;
        }
    }

    private final class ConnectorFluidStorage implements Storage<FluidVariant> {
        @Override public long insert(FluidVariant resource, long maxAmount, TransactionContext transaction) {
            if (resource.isBlank() || maxAmount <= 0 || !flow.writes() || !allowsFluid(resource)) return 0;
            Storage<FluidVariant> external = externalFluids();
            if (external == null) return 0;
            long inserted = external.insert(resource, maxAmount, transaction);
            if (inserted < maxAmount && voidPerKind && kindTankFull(resource, maxAmount - inserted)) {
                long voided = maxAmount - inserted;
                voidFluidCounter.updateSnapshots(transaction);
                voidedFluid = saturatedAdd(voidedFluid, voided);
                return maxAmount;
            }
            if (inserted < maxAmount && voidOverflow && !voidPerKind && !blacklist
                    && !fluidFilterEmpty() && matchesFluidFilter(resource)) {
                long voided = maxAmount - inserted;
                voidFluidCounter.updateSnapshots(transaction);
                voidedFluid = saturatedAdd(voidedFluid, voided);
                return maxAmount;
            }
            return inserted;
        }
        @Override public long extract(FluidVariant resource, long maxAmount, TransactionContext transaction) {
            if (resource.isBlank() || maxAmount <= 0 || !flow.reads() || !allowsFluid(resource)) return 0;
            Storage<FluidVariant> external = externalFluids();
            return external == null ? 0 : external.extract(resource, maxAmount, transaction);
        }
        @Override public Iterator<StorageView<FluidVariant>> iterator() {
            Storage<FluidVariant> external = externalFluids();
            if (external == null || !flow.reads()) return List.<StorageView<FluidVariant>>of().iterator();
            List<StorageView<FluidVariant>> visible = new ArrayList<>();
            for (StorageView<FluidVariant> view : external) {
                if (!view.isResourceBlank() && allowsFluid(view.getResource())) visible.add(new FilteredFluidView(view));
            }
            return visible.iterator();
        }
    }

    private final class FilteredFluidView implements StorageView<FluidVariant> {
        private final StorageView<FluidVariant> delegate;
        private FilteredFluidView(StorageView<FluidVariant> delegate) { this.delegate = delegate; }
        @Override public boolean isResourceBlank() { return delegate.isResourceBlank() || !allowsFluid(delegate.getResource()); }
        @Override public FluidVariant getResource() { return isResourceBlank() ? FluidVariant.blank() : delegate.getResource(); }
        @Override public long getAmount() { return isResourceBlank() ? 0 : delegate.getAmount(); }
        @Override public long getCapacity() { return delegate.getCapacity(); }
        @Override public long extract(FluidVariant resource, long maxAmount, TransactionContext transaction) {
            return flow.reads() && allowsFluid(resource) ? delegate.extract(resource, maxAmount, transaction) : 0;
        }
    }

    private final class ConnectorEnergyStorage implements EnergyStorage {
        @Override public long insert(long maxAmount, TransactionContext transaction) {
            if (maxAmount <= 0 || !flow.writes()) return 0;
            EnergyStorage external = externalEnergy();
            return external == null ? 0 : external.insert(maxAmount, transaction);
        }
        @Override public long extract(long maxAmount, TransactionContext transaction) {
            if (maxAmount <= 0 || !flow.reads()) return 0;
            EnergyStorage external = externalEnergy();
            return external == null ? 0 : external.extract(maxAmount, transaction);
        }
        @Override public long getAmount() {
            EnergyStorage external = externalEnergy();
            return external == null || !flow.reads() ? 0 : external.getAmount();
        }
        @Override public long getCapacity() {
            EnergyStorage external = externalEnergy();
            return external == null ? 0 : external.getCapacity();
        }
    }
}
