package de.scc.calculator.calc;

import java.util.Map;
import java.util.Set;

/** Immutable snapshot of the preferred recipe for each selected output. */
public final class CraftingPlan {
    private final Map<StackKey, SccRecipe> best;
    private final Set<StackKey> brokenCycles;

    public final String fingerprint;
    public final String source;
    public final int itemCount;
    public final int recipeCount;
    public final long millis;

    public CraftingPlan(Map<StackKey, SccRecipe> best, Set<StackKey> brokenCycles,
                        String fingerprint, String source, int itemCount,
                        int recipeCount, long millis) {
        this.best = best;
        this.brokenCycles = brokenCycles;
        this.fingerprint = fingerprint;
        this.source = source;
        this.itemCount = itemCount;
        this.recipeCount = recipeCount;
        this.millis = millis;
    }

    public SccRecipe bestRecipe(StackKey item) {
        return best.get(item);
    }

    public Map<StackKey, SccRecipe> all() {
        return best;
    }

    public Set<StackKey> brokenCycles() {
        return brokenCycles;
    }
}
