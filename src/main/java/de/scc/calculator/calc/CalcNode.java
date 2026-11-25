package de.scc.calculator.calc;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Calculation-tree node, reconstructed from the SCC 1.6.5 class file. */
public final class CalcNode {
    public enum Kind {
        BASE,
        CRAFTED,
        CYCLE,
        LIMIT
    }

    public final StackKey item;
    public final long amount;
    public final Kind kind;
    public final int depth;
    public final String key;
    public final SccRecipe recipe;
    public final long crafts;
    public final long produced;
    public final int alternatives;
    public final String ingredientKey;
    public final List<CalcNode> children;

    CalcNode(StackKey item, long amount, Kind kind, int depth, String key,
             SccRecipe recipe, long crafts, long produced, int alternatives,
             String ingredientKey, List<CalcNode> children) {
        this.item = item;
        this.amount = amount;
        this.kind = kind;
        this.depth = depth;
        this.key = key;
        this.recipe = recipe;
        this.crafts = crafts;
        this.produced = produced;
        this.alternatives = alternatives;
        this.ingredientKey = ingredientKey;
        this.children = children == null ? Collections.emptyList() : children;
    }

    public boolean hasChildren() {
        return !children.isEmpty();
    }

    public long surplus() {
        return Math.max(0L, produced - amount);
    }

    public List<CalcNode> flatten() {
        List<CalcNode> flattened = new ArrayList<>();
        collect(flattened);
        return flattened;
    }

    private void collect(List<CalcNode> target) {
        target.add(this);
        for (CalcNode child : children) {
            child.collect(target);
        }
    }
}
