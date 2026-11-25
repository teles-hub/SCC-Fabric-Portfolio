package de.scc.calculator.calc;

/**
 * Compatibility hook for SCC's optional JEI/EMI viewer recipe extraction.
 * The Fabric build currently indexes the native RecipeManager plus registered
 * provider APIs; this deadline hook allows the bytecode-preserved analyzer to
 * run without linking the unavailable NeoForge viewer adapters.
 */
public final class ViewerRecipeSource {
    private static volatile long deadline;

    private ViewerRecipeSource() { }

    public static void setDeadline(long deadlineMillis) {
        deadline = Math.max(0L, deadlineMillis);
    }

    public static long deadline() {
        return deadline;
    }
}
