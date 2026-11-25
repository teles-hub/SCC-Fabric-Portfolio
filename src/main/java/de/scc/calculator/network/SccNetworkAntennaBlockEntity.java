package de.scc.calculator.network;

import de.scc.calculator.registry.SccBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.state.BlockState;

import java.util.UUID;

/**
 * Network antenna endpoint. It reuses the existing network terminal storage/job
 * state while keeping the original antenna range-upgrade contract.
 *
 * <p>Optional AE2 integration from the original mod is intentionally not loaded
 * here; it requires a separately verified Fabric-compatible adapter.</p>
 */
public final class SccNetworkAntennaBlockEntity extends SccTerminalBlockEntity {
    public static final int BASE_RANGE = 32;
    public static final int RANGE_PER_UPGRADE = 32;
    public static final int MAX_RANGE_UPGRADES = 8;

    private int rangeUpgrades;
    private boolean dimensional;
    private boolean infinite;
    private UUID placedBy;

    public SccNetworkAntennaBlockEntity(BlockPos pos, BlockState state) {
        super(SccBlockEntities.SCC_NETWORK_ANTENNA.get(), pos, state);
    }

    public void setPlacedBy(UUID owner) {
        if (java.util.Objects.equals(placedBy, owner)) return;
        placedBy = owner;
        setChanged();
    }

    public UUID placedBy() { return placedBy; }
    public int rangeUpgrades() { return rangeUpgrades; }
    public boolean dimensional() { return dimensional; }
    public boolean infinite() { return infinite; }
    public int range() { return BASE_RANGE + rangeUpgrades * RANGE_PER_UPGRADE; }

    public boolean reaches(net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                           net.minecraft.core.BlockPos target) {
        if (level == null || dimension == null || target == null) return false;
        if (dimensional) return true;
        if (!level.dimension().equals(dimension)) return false;
        return infinite || worldPosition.distSqr(target) <= (double) range() * range();
    }

    public boolean addRangeUpgrade() {
        if (rangeUpgrades >= MAX_RANGE_UPGRADES) return false;
        rangeUpgrades++;
        upgradesChanged();
        return true;
    }

    public boolean addInfiniteUpgrade() {
        if (infinite) return false;
        infinite = true;
        upgradesChanged();
        return true;
    }

    public boolean addDimensionalUpgrade() {
        if (dimensional) return false;
        dimensional = true;
        upgradesChanged();
        return true;
    }

    public int takeRangeUpgrades() {
        int result = rangeUpgrades;
        if (result != 0) { rangeUpgrades = 0; upgradesChanged(); }
        return result;
    }

    public boolean takeInfiniteUpgrade() {
        if (!infinite) return false;
        infinite = false;
        upgradesChanged();
        return true;
    }

    public boolean takeDimensionalUpgrade() {
        if (!dimensional) return false;
        dimensional = false;
        upgradesChanged();
        return true;
    }

    public void refresh() {
        if (level != null) SccNetworkCache.invalidate(level);
    }

    private void upgradesChanged() {
        setChanged();
        if (level != null) SccNetworkCache.invalidate(level);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.putInt("range", rangeUpgrades);
        tag.putBoolean("dimensional", dimensional);
        tag.putBoolean("infinite", infinite);
        if (placedBy != null) tag.putUUID("placedBy", placedBy);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        rangeUpgrades = Math.max(0, Math.min(MAX_RANGE_UPGRADES, tag.getInt("range")));
        dimensional = tag.getBoolean("dimensional");
        infinite = tag.getBoolean("infinite");
        placedBy = tag.hasUUID("placedBy") ? tag.getUUID("placedBy") : null;
    }
}
