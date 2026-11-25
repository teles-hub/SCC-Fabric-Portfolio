package de.scc.calculator.network;

import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.block.Block;

/**
 * Storage capacity tiers reconstructed from SCC 1.6.5 bytecode.
 * A configured value <= 0 means unlimited, matching the original server config.
 */
public enum StorageTier implements StringRepresentable {
    BASIC("basic", 4_000L, 4, 4_000, 1_000_000L, 64_000L, 40_000L),
    RED_DWARF("red_dwarf", 16_000L, 6, 8_000, 4_000_000L, 256_000L, 160_000L),
    YELLOW_STAR("yellow_star", 64_000L, 8, 16_000, 16_000_000L, 1_000_000L, 640_000L),
    BLUE_GIANT("blue_giant", 256_000L, 10, 32_000, 64_000_000L, 4_000_000L, 2_560_000L),
    NEUTRON_STAR("neutron_star", 1_000_000L, 12, 64_000, 256_000_000L, 16_000_000L, 10_240_000L),
    SINGULARITY("singularity", -1L, -1, -1, -1L, -1L, -1L);

    public static final int UNLIMITED = -1;

    private static final int ITEMS = 0;
    private static final int TANKS = 1;
    private static final int FLUID_PER_TANK = 2;
    private static final int CHEMICAL_PER_TANK = 3;
    private static final int ENERGY = 4;
    private static final int SOURCE = 5;
    private static final int SOULS = 6;

    private static volatile long[][] limits;
    private static volatile int generation;

    private final String id;
    private final long builtInItems;
    private final int builtInTanks;
    private final int builtInPerTank;
    private final long builtInEnergy;
    private final long builtInPerChemicalTank;
    private final long builtInSource;

    StorageTier(String id, long items, int tanks, int perTank, long energy,
                long perChemicalTank, long source) {
        this.id = id;
        this.builtInItems = items;
        this.builtInTanks = tanks;
        this.builtInPerTank = perTank;
        this.builtInEnergy = energy;
        this.builtInPerChemicalTank = perChemicalTank;
        this.builtInSource = source;
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String id() { return id; }
    public long items() { return limit(ITEMS); }
    public int tanks() { return (int) Math.min(Integer.MAX_VALUE, limit(TANKS)); }
    public int perTank() { return (int) Math.min(Integer.MAX_VALUE, limit(FLUID_PER_TANK)); }
    public long perChemicalTank() { return limit(CHEMICAL_PER_TANK); }
    public long energy() { return limit(ENERGY); }
    public long source() { return limit(SOURCE); }
    public long souls() { return limit(SOULS); }

    private long builtInSouls() {
        return switch (this) {
            case BASIC -> 5_000L;
            case RED_DWARF -> 20_000L;
            case YELLOW_STAR -> 80_000L;
            case BLUE_GIANT -> 320_000L;
            case NEUTRON_STAR -> 1_280_000L;
            case SINGULARITY -> UNLIMITED;
        };
    }

    public long builtIn(int kind) {
        return switch (kind) {
            case ITEMS -> builtInItems;
            case TANKS -> builtInTanks;
            case FLUID_PER_TANK -> builtInPerTank;
            case CHEMICAL_PER_TANK -> builtInPerChemicalTank;
            case ENERGY -> builtInEnergy;
            case SOURCE -> builtInSource;
            default -> builtInSouls();
        };
    }

    public boolean unlimited(SccStorageBlock.Kind kind) {
        return switch (kind) {
            case ITEM -> items() < 0L;
            case FLUID -> tanks() < 0 || perTank() < 0;
            case CHEMICAL -> tanks() < 0 || perChemicalTank() < 0L;
            case ENERGY -> energy() < 0L;
            case SOURCE -> source() < 0L;
            case SOUL -> souls() < 0L;
        };
    }

    public long total(SccStorageBlock.Kind kind) {
        if (unlimited(kind)) return UNLIMITED;
        return switch (kind) {
            case ITEM -> items();
            // int * int cannot overflow a signed long, even at Integer.MAX_VALUE.
            case FLUID -> (long) tanks() * (long) perTank();
            case CHEMICAL -> safeTimes(tanks(), perChemicalTank());
            case ENERGY -> energy();
            case SOURCE -> source();
            case SOUL -> souls();
        };
    }

    private static long safeTimes(long first, long second) {
        if (second != 0L && first > Long.MAX_VALUE / second) return Long.MAX_VALUE;
        return first * second;
    }

    public static int generation() { return generation; }

    /** Reloads all 42 tiered limits from the Fabric-side config migration. */
    public static synchronized void reloadLimits() {
        long[][] next = new long[SccStorageLimits.PREFIXES.length][values().length];
        for (int prefix = 0; prefix < SccStorageLimits.PREFIXES.length; prefix++) {
            for (StorageTier tier : values()) {
                long configured = SccStorageLimits.tiered(SccStorageLimits.PREFIXES[prefix], tier.ordinal());
                next[prefix][tier.ordinal()] = configured > 0L ? configured : UNLIMITED;
            }
        }
        limits = next;
        generation++;
    }

    private long limit(int index) {
        long[][] current = limits;
        if (current == null) {
            reloadLimits();
            current = limits;
        }
        return current[index][ordinal()];
    }

    public StorageTier next() {
        int nextIndex = ordinal() + 1;
        return nextIndex < values().length ? values()[nextIndex] : null;
    }

    public String blockName(String baseName) {
        return this == BASIC ? baseName : baseName + "_" + id;
    }

    public static StorageTier of(Block block) {
        return block instanceof SccStorageBlock storageBlock ? storageBlock.tier() : null;
    }

    public static StorageTier matching(SccTier tier) {
        return tier == null ? null : values()[tier.ordinal()];
    }

    public SccTier tier() {
        return SccTier.values()[ordinal()];
    }
}
