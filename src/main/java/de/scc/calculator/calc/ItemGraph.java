package de.scc.calculator.calc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Cached, ingredient-level view of a {@link RecipeBook}. */
public final class ItemGraph {
    private final RecipeBook book;
    private final Map<String, List<Child>> children = new HashMap<>();

    public record Child(StackKey item, int count, boolean consumed, String key) {
    }

    public ItemGraph(RecipeBook book) {
        this.book = book == null ? new RecipeBook() : book;
    }

    /** Discards cached ingredient choices after settings or provider data change. */
    public void refreshPreferred() {
        children.clear();
    }

    public List<Child> childrenOf(SccRecipe recipe) {
        if (recipe == null || recipe.inputs().isEmpty()) {
            return List.of();
        }
        String cachedKey = recipe.id() + "|" + recipe.output().encode();
        List<Child> existing = children.get(cachedKey);
        if (existing != null) {
            return existing;
        }

        List<Child> result = new ArrayList<>(recipe.inputs().size());
        Set<StackKey> preferred = CalcSettings.preferredKeys();
        for (int index = 0; index < recipe.inputs().size(); index++) {
            SccRecipe.Input input = recipe.inputs().get(index);
            List<StackKey> options = input.options();
            if (options == null || options.isEmpty()) {
                continue;
            }
            String slot = slotKey(recipe.id(), index);
            StackKey chosen = choose(options, preferred, slot);
            if (chosen == null) {
                continue;
            }
            result.add(new Child(chosen, Math.max(1, input.count()), input.consumed(), slot));
        }
        List<Child> immutable = List.copyOf(result);
        children.put(cachedKey, immutable);
        return immutable;
    }

    private static StackKey choose(List<StackKey> options, Set<StackKey> preferred, String slot) {
        StackKey override = CalcSettings.get().ingredientOverride(slot);
        if (override != null && options.contains(override)) {
            return override;
        }
        for (StackKey option : options) {
            if (option != null && preferred.contains(option)) {
                return option;
            }
        }

        StackKey best = null;
        int bestRank = Integer.MAX_VALUE;
        for (StackKey option : options) {
            if (option == null) {
                continue;
            }
            int rank = option.plain() ? 0 : option.onlyModData() ? 1 : 2;
            if (best == null || rank < bestRank
                    || rank == bestRank && RecipeUtil.compareKeys(option, best) < 0) {
                best = option;
                bestRank = rank;
            }
        }
        return best;
    }

    private static String slotKey(String recipeId, int index) {
        return (recipeId == null ? "?" : recipeId) + "#" + index;
    }

    public Set<StackKey> allItems() {
        return book.allItems();
    }

    public int totalItems() {
        return new LinkedHashSet<>(allItems()).size();
    }
}
