package de.scc.calculator.network;

import java.math.BigInteger;

/** Overflow-safe display and accounting helpers for very large SCC stores. */
public final class SccStorageMath {
    private static final BigInteger FOUR = BigInteger.valueOf(4L);
    private static final BigInteger FIFTEEN = BigInteger.valueOf(15L);

    private SccStorageMath() { }

    /**
     * Maps used/total to SCC's five block states (0..4).
     * Positive usage in an unlimited/unknown store is shown as level 1.
     */
    public static int fillLevel(long used, long total) {
        if (used <= 0L) return 0;
        if (total <= 0L) return 1;
        if (used >= total) return 4;
        return (int) Math.max(1L, Math.min(4L, scaledRatio(used, total, FOUR, true)));
    }

    /** Maps used/total to a vanilla comparator signal (0..15). */
    public static int analogSignal(long used, long total) {
        if (used <= 0L) return 0;
        if (total <= 0L) return 1;
        if (used >= total) return 15;
        return (int) Math.max(1L, Math.min(15L, scaledRatio(used, total, FIFTEEN, false)));
    }

    /** Ceiling division for a non-negative value and positive divisor. */
    public static long ceilDiv(long value, long divisor) {
        if (divisor <= 0L) throw new IllegalArgumentException("divisor must be positive");
        if (value <= 0L) return 0L;
        long quotient = value / divisor;
        return value % divisor == 0L ? quotient : quotient + 1L;
    }

    private static long scaledRatio(long used, long total, BigInteger scale, boolean roundUp) {
        BigInteger[] quotientAndRemainder = BigInteger.valueOf(used)
                .multiply(scale)
                .divideAndRemainder(BigInteger.valueOf(total));
        BigInteger quotient = quotientAndRemainder[0];
        if (roundUp && quotientAndRemainder[1].signum() != 0) {
            quotient = quotient.add(BigInteger.ONE);
        }
        return quotient.longValueExact();
    }
}
