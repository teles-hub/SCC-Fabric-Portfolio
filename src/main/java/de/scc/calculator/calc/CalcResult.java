package de.scc.calculator.calc;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable calculation output reconstructed from the SCC 1.6.5 bytecode. */
public record CalcResult(
        StackKey target,
        long amount,
        CalcNode tree,
        List<CalcResult.Entry> baseResources,
        List<CalcResult.Step> steps,
        Map<StackKey, Long> leftovers,
        Set<StackKey> cycles,
        boolean truncated,
        int nodeCount
) {
    public boolean isPossible() {
        return tree != null;
    }

    public record Entry(StackKey item, long amount) {
    }

    public record Step(StackKey item, long produced, long needed, long crafts,
                       SccRecipe recipe, int depth) {
    }
}
