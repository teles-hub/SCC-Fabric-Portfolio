package de.scc.calculator.network;

import de.scc.calculator.platform.storage.SccEnergyStorage;
import de.scc.calculator.platform.storage.SccFluidHandler;
import de.scc.calculator.platform.storage.SccItemHandler;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;

import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Local storage composition extracted from SCC's SccStorageBlockEntity.
 *
 * <p>This class deliberately contains no BlockEntity or network inheritance.
 * It owns the six lazy stores, follows tier-limit reloads and persists the
 * original nested {@code store} payload. A Fabric BlockEntity can compose it
 * without pulling NeoForge's StorageConnectorBlockEntity into the port.</p>
 */
public final class SccStorageData {
    private final SccStorageBlock.Kind kind;
    private final Runnable changed;
    private LongSupplier itemKindLimit = () -> 0L;
    private LongSupplier fluidKindLimit = () -> 0L;
    private StorageTier tier;
    private int limitsSeen = Integer.MIN_VALUE;

    private SccItemStore items;
    private SccFluidStore fluids;
    private SccEnergyStore energy;
    private SccChemicalStore chemicals;
    private SccSourceStore source;
    private SccSoulStore souls;

    public SccStorageData(SccStorageBlock.Kind kind, StorageTier tier, Runnable changed) {
        this.kind = kind == null ? SccStorageBlock.Kind.ITEM : kind;
        this.tier = tier == null ? StorageTier.BASIC : tier;
        this.changed = changed == null ? () -> { } : changed;
    }

    public SccStorageBlock.Kind kind() { return kind; }
    public StorageTier tier() { return tier; }

    /** Reapplies the current tier to already-created stores after an upgrade. */
    public void setTier(StorageTier tier) {
        this.tier = tier == null ? StorageTier.BASIC : tier;
        limitsSeen = Integer.MIN_VALUE;
        followLimits();
    }

    /** Mirrors SCC's void-per-kind limiter for its bulk item store. */
    public void limitItemsPerKind(LongSupplier supplier) {
        itemKindLimit = supplier == null ? () -> 0L : supplier;
        if (items != null) items.limitPerKind(itemKindLimit);
    }

    /** Mirrors SCC's per-fluid-kind tank limit used by void-per-kind mode. */
    public void limitFluidsPerKind(LongSupplier supplier) {
        if (fluids != null) fluids.limitPerKind(supplier);
        this.fluidKindLimit = supplier == null ? () -> 0L : supplier;
    }

    public SccItemStore items() {
        if (kind != SccStorageBlock.Kind.ITEM) return null;
        if (items == null) {
            items = new SccItemStore(tier.items(), changed);
            items.limitPerKind(itemKindLimit);
        }
        followLimits();
        return items;
    }

    public SccFluidStore fluids() {
        if (kind != SccStorageBlock.Kind.FLUID) return null;
        if (fluids == null) { fluids = new SccFluidStore(tier.tanks(), tier.perTank(), changed); fluids.limitPerKind(fluidKindLimit); }
        followLimits();
        return fluids;
    }

    public SccEnergyStore energy() {
        if (kind != SccStorageBlock.Kind.ENERGY) return null;
        if (energy == null) energy = new SccEnergyStore(tier.energy(), changed);
        followLimits();
        return energy;
    }

    public SccChemicalStore chemicals() {
        if (kind != SccStorageBlock.Kind.CHEMICAL) return null;
        if (chemicals == null) chemicals = new SccChemicalStore(tier.tanks(), tier.perChemicalTank(), changed);
        followLimits();
        return chemicals;
    }

    public SccSourceStore source() {
        if (kind != SccStorageBlock.Kind.SOURCE) return null;
        if (source == null) source = new SccSourceStore(tier.source(), changed);
        followLimits();
        return source;
    }

    public SccSoulStore souls() {
        if (kind != SccStorageBlock.Kind.SOUL) return null;
        if (souls == null) souls = new SccSoulStore(tier.souls(), changed);
        followLimits();
        return souls;
    }

    private void followLimits() {
        int generation = StorageTier.generation();
        if (generation == limitsSeen) return;
        limitsSeen = generation;
        if (items != null) items.setCapacity(tier.items());
        if (fluids != null) fluids.setLimits(tier.tanks(), tier.perTank());
        if (energy != null) energy.setCapacity(tier.energy());
        if (chemicals != null) chemicals.setLimits(tier.tanks(), tier.perChemicalTank());
        if (source != null) source.setCapacity(tier.source());
        if (souls != null) souls.setCapacity(tier.souls());
    }

    public SccItemHandler itemHandler() { return items(); }
    public SccFluidHandler fluidHandler() { return fluids(); }
    public SccEnergyStorage energyHandler() { return energy(); }
    public SourceHandler sourceHandler() { return source(); }
    public SoulHandler soulHandler() { return souls(); }

    public void collectChemicals(Map<String, Long> target) {
        if (kind == SccStorageBlock.Kind.CHEMICAL && target != null) chemicals().collect(target);
    }

    public long countChemical(String id) {
        return kind == SccStorageBlock.Kind.CHEMICAL ? chemicals().count(id) : 0L;
    }

    public long fillChemical(String id, long amount, boolean simulate) {
        return kind == SccStorageBlock.Kind.CHEMICAL ? chemicals().insert(id, amount, simulate) : 0L;
    }

    public long drainChemical(String id, long amount, boolean simulate) {
        return kind == SccStorageBlock.Kind.CHEMICAL ? chemicals().drain(id, amount, simulate) : 0L;
    }

    public long fillUsed() {
        return switch (kind) {
            case ITEM -> items().stored();
            case FLUID -> fluids().totalAmount();
            case ENERGY -> energy().storedExact();
            case CHEMICAL -> chemicals().totalAmount();
            case SOURCE -> source().stored();
            case SOUL -> souls().stored();
        };
    }

    /** Original UI convention: unlimited/zero totals are represented as 0. */
    public long fillTotal() {
        long total = tier.total(kind);
        return total < 0L ? 0L : total;
    }

    public boolean unlimited() { return tier.unlimited(kind); }

    /** Original SccStorageBlockEntity method returned the item-store case only. */
    public boolean unlimitedItemStore() {
        return kind == SccStorageBlock.Kind.ITEM && tier.unlimited(SccStorageBlock.Kind.ITEM);
    }

    public boolean empty() {
        return switch (kind) {
            case ITEM -> items == null || (items.stored() == 0L && !items.hasUnreadable());
            case FLUID -> fluids == null || fluids.totalAmount() <= 0L;
            case ENERGY -> energy == null || energy.storedExact() <= 0L;
            case CHEMICAL -> chemicals == null || chemicals.totalAmount() <= 0L;
            case SOURCE -> source == null || source.stored() <= 0L;
            case SOUL -> souls == null || souls.stored() <= 0L;
        };
    }

    public long[] slotUsage() {
        if (kind != SccStorageBlock.Kind.ITEM || tier.unlimited(SccStorageBlock.Kind.ITEM)) return new long[]{0L, 0L};
        long used = SccStorageMath.ceilDiv(items().stored(), 64L);
        long total = SccStorageMath.ceilDiv(tier.items(), 64L);
        return new long[]{used, total};
    }

    /** Writes the exact original outer key used by SCC's storage block entity. */
    public void saveStore(CompoundTag target, HolderLookup.Provider registries) {
        switch (kind) {
            case ITEM -> target.put("store", items().save(registries));
            case FLUID -> target.put("store", fluids().save(registries));
            case ENERGY -> target.put("store", energy().save());
            case CHEMICAL -> target.put("store", chemicals().save());
            case SOURCE -> target.put("store", source().save());
            case SOUL -> target.put("store", souls().save());
        }
    }

    /** Loads the original nested {@code store} payload after its BlockEntity base data. */
    public void loadStore(CompoundTag root, HolderLookup.Provider registries) {
        if (root == null || !root.contains("store")) return;
        CompoundTag saved = root.getCompound("store");
        switch (kind) {
            case ITEM -> items().load(saved, registries);
            case FLUID -> fluids().load(saved, registries);
            case ENERGY -> energy().load(saved);
            case CHEMICAL -> chemicals().load(saved);
            case SOURCE -> source().load(saved);
            case SOUL -> souls().load(saved);
        }
    }

    public List<SccItemStore.Kind> itemSummary(int limit) {
        return kind == SccStorageBlock.Kind.ITEM ? items().summary(limit) : List.of();
    }
}
