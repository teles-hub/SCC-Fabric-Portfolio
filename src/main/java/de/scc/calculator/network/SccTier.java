package de.scc.calculator.network;

import de.scc.calculator.data.SccBalance;
import net.minecraft.util.StringRepresentable;

import java.util.Locale;

/**
 * Original SCC progression tiers used by processing calculators and network
 * nodes. Constants, built-in limits, aliases, colors and tier progression were
 * reconstructed from SCC 1.6.5 bytecode (not inferred from item names).
 */
public enum SccTier implements StringRepresentable {
    BASIC("basic", 40, 1, 1_000L, 0),
    RED_DWARF("red_dwarf", 20, 2, 8_000L, 16),
    YELLOW_STAR("yellow_star", 10, 4, 64_000L, 32),
    BLUE_GIANT("blue_giant", 5, 8, 512_000L, 64),
    NEUTRON_STAR("neutron_star", 2, 16, 0L, 128),
    SINGULARITY("singularity", 1, 64, 0L, 256);

    private final String id;
    public volatile int ticksPerOperation;
    public volatile int parallelJobs;
    private volatile long energyRate;
    private volatile int rangeBonus;
    private final SccBalance.TierValues builtIn;

    SccTier(String id, int ticksPerOperation, int parallelJobs, long energyPerTick, int rangeBonus) {
        this.id = id;
        this.builtIn = new SccBalance.TierValues(
                ticksPerOperation, parallelJobs, energyPerTick, rangeBonus);
        this.ticksPerOperation = ticksPerOperation;
        this.parallelJobs = parallelJobs;
        this.energyRate = builtIn.energyRate();
        this.rangeBonus = rangeBonus;
    }

    public SccBalance.TierValues builtIn() {
        return builtIn;
    }

    public void balance(int ticksPerOperation, int parallelJobs, long energyRate, int rangeBonus) {
        this.ticksPerOperation = Math.max(1, ticksPerOperation);
        this.parallelJobs = Math.max(1, parallelJobs);
        this.energyRate = Math.max(1L, energyRate);
        this.rangeBonus = Math.max(0, rangeBonus);
    }

    @Override
    public String getSerializedName() {
        return id;
    }

    public String id() {
        return id;
    }

    public boolean isSingularity() {
        return this == SINGULARITY;
    }

    /** Resolves canonical ids and the eight legacy material aliases in SCC. */
    public static SccTier byId(String rawId) {
        if (rawId == null || rawId.isEmpty()) {
            return null;
        }
        for (SccTier tier : values()) {
            if (tier.id.equals(rawId) || tier.name().equalsIgnoreCase(rawId)) {
                return tier;
            }
        }
        return switch (rawId.toLowerCase(Locale.ROOT)) {
            case "iron" -> RED_DWARF;
            case "gold" -> YELLOW_STAR;
            case "diamond" -> BLUE_GIANT;
            case "netherite" -> NEUTRON_STAR;
            case "infinite", "allthemodium", "vibranium", "unobtainium" -> SINGULARITY;
            default -> null;
        };
    }

    public SccTier next() {
        int nextIndex = ordinal() + 1;
        return nextIndex < values().length ? values()[nextIndex] : null;
    }

    public long energyRate() {
        return energyRate;
    }

    public int color() {
        return switch (this) {
            case BASIC -> 9_082_280;
            case RED_DWARF -> 13_781_050;
            case YELLOW_STAR -> 15_777_324;
            case BLUE_GIANT -> 4_161_010;
            case NEUTRON_STAR -> 14_215_926;
            case SINGULARITY -> 16_756_312;
        };
    }

    public float step() {
        return ordinal() / (float) (values().length - 1);
    }

    public int rangeBonus() {
        return rangeBonus;
    }
}
