package de.scc.calculator.data;

import de.scc.calculator.network.SccTier;

/**
 * Balance values needed by the non-combat network/calculator tier model.
 *
 * <p>The full original balance loader also contains astronomy, star and gear
 * values. Those systems are intentionally not part of this Fabric core port.
 * This source currently preserves the tier-value record contract used by
 * {@link SccTier}; additional balance domains must be ported from their own
 * source classes before they are referenced.</p>
 */
public final class SccBalance {
    private SccBalance() { }

    public record TierValues(int ticksPerOperation, int parallelJobs, long energyPerTick, int rangeBonus) {
        public static TierValues defaults(SccTier tier) {
            if (tier == null) {
                throw new IllegalArgumentException("tier cannot be null");
            }
            return tier.builtIn();
        }

        public long energyRate() {
            return energyPerTick <= 0L ? 9_007_199_254_740_991L : energyPerTick;
        }
    }
}
