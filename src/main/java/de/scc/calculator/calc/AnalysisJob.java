package de.scc.calculator.calc;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded, incremental recipe analysis for a single source snapshot. */
public final class AnalysisJob {
    public enum Phase { SCANNING, GRAPH, PLAN, COSTS, DONE, CANCELLED, FAILED }

    private final RecipeSource source;
    private final String fingerprint;
    private final RecipeBook book = new RecipeBook();

    private volatile Phase phase = Phase.SCANNING;
    private volatile boolean done;
    private volatile boolean cancelled;
    private volatile Throwable failure;
    private boolean begun;

    private ItemGraph graph;
    private CraftingPlan plan;
    private Map<StackKey, Double> costs = Map.of();

    public AnalysisJob(RecipeSource source, String fingerprint) {
        this.source = source;
        this.fingerprint = fingerprint;
    }

    /** Performs at most one time-budgeted slice of work. */
    public void step(long millisBudget) {
        if (done || cancelled) {
            return;
        }
        long deadline = System.nanoTime() + Math.max(1L, Math.min(50L, millisBudget)) * 1_000_000L;
        try {
            switch (phase) {
                case SCANNING -> scan(deadline);
                case GRAPH -> {
                    graph = new ItemGraph(book);
                    graph.refreshPreferred();
                    phase = Phase.PLAN;
                }
                case PLAN -> {
                    plan = buildPlan(book, graph);
                    phase = Phase.COSTS;
                }
                case COSTS -> {
                    costs = buildCosts(book, graph);
                    phase = Phase.DONE;
                    done = true;
                }
                default -> done = true;
            }
        } catch (Throwable error) {
            failure = error;
            phase = Phase.FAILED;
            done = true;
        }
    }

    private void scan(long deadline) {
        if (!begun) {
            begun = true;
            source.begin();
        }
        int chunk = Math.max(16, Math.min(256, book.recipeCount() + 32));
        boolean more;
        do {
            more = source.scanChunk(book, chunk);
        } while (more && System.nanoTime() < deadline);

        if (!more) {
            book.sort();
            phase = Phase.GRAPH;
        }
    }

    private CraftingPlan buildPlan(RecipeBook book, ItemGraph graph) {
        Map<StackKey, SccRecipe> best = new LinkedHashMap<>();
        for (StackKey output : book.craftableItems()) {
            List<SccRecipe> options = book.recipesFor(output);
            if (!options.isEmpty()) {
                best.put(output, options.getFirst());
            }
        }

        Set<StackKey> cycles = new LinkedHashSet<>();
        for (StackKey root : best.keySet()) {
            if (hasCycle(root, best, graph)) {
                cycles.add(root);
            }
        }
        long millis = 0L;
        return new CraftingPlan(best, cycles, fingerprint, book.source(),
                book.allItems().size(), book.recipeCount(), millis);
    }

    private static boolean hasCycle(StackKey root, Map<StackKey, SccRecipe> best, ItemGraph graph) {
        Deque<StackKey> pending = new ArrayDeque<>();
        Set<StackKey> active = new LinkedHashSet<>();
        Set<StackKey> visited = new LinkedHashSet<>();
        pending.push(root);
        int guard = 0;
        while (!pending.isEmpty() && guard++ < 8_192) {
            StackKey item = pending.peek();
            if (active.contains(item)) {
                return true;
            }
            if (!visited.add(item)) {
                pending.pop();
                continue;
            }
            active.add(item);
            SccRecipe recipe = best.get(item);
            if (recipe == null) {
                active.remove(item);
                pending.pop();
                continue;
            }
            List<ItemGraph.Child> children = graph.childrenOf(recipe);
            boolean descending = false;
            for (ItemGraph.Child child : children) {
                if (active.contains(child.item())) {
                    return true;
                }
                if (!visited.contains(child.item())) {
                    pending.push(child.item());
                    descending = true;
                }
            }
            if (!descending) {
                active.remove(item);
                pending.pop();
            }
        }
        return false;
    }

    private static Map<StackKey, Double> buildCosts(RecipeBook book, ItemGraph graph) {
        Map<StackKey, Double> costs = new HashMap<>();
        for (StackKey item : book.allItems()) {
            costs.put(item, 1.0D);
        }
        for (int pass = 0; pass < 8; pass++) {
            boolean changed = false;
            for (SccRecipe recipe : book.all().values()) {
                double sum = 1.0D;
                for (ItemGraph.Child child : graph.childrenOf(recipe)) {
                    double childCost = costs.getOrDefault(child.item(), 1.0D);
                    sum += childCost * Math.max(1, child.count());
                }
                double unit = sum / Math.max(1, recipe.outputCount());
                Double previous = costs.get(recipe.output());
                if (previous == null || unit < previous) {
                    costs.put(recipe.output(), unit);
                    changed = true;
                }
            }
            if (!changed) {
                break;
            }
        }
        return Map.copyOf(costs);
    }

    public void cancel() {
        cancelled = true;
        done = true;
        phase = Phase.CANCELLED;
    }

    public Throwable failure() {
        return failure;
    }

    public boolean done() {
        return done;
    }

    public Phase phase() {
        return phase;
    }

    public RecipeBook book() {
        return book;
    }

    public ItemGraph graph() {
        return graph;
    }

    public CraftingPlan result() {
        return plan;
    }

    public Map<StackKey, Double> costs() {
        return costs;
    }

    public double progress() {
        if (done) {
            return 1.0D;
        }
        int total = source.total();
        if (phase == Phase.SCANNING) {
            int resolved = source.done();
            return total > 0 ? Math.min(0.75D, 0.75D * resolved / total) : 0.05D;
        }
        return switch (phase) {
            case GRAPH -> 0.8D;
            case PLAN -> 0.9D;
            case COSTS -> 0.97D;
            default -> 1.0D;
        };
    }

    public String detail() {
        return phase + " · " + book.recipeCount() + " recipes · " + book.allItems().size()
                + " items · " + fingerprint;
    }

    public int recipesFound() {
        return book.recipeCount();
    }

    public int itemsResolved() {
        return graph == null ? book.allItems().size() : graph.allItems().size();
    }

    public int totalItems() {
        return Math.max(itemsResolved(), book.allItems().size());
    }
}
