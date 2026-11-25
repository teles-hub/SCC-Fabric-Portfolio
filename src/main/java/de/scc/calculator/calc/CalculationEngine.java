package de.scc.calculator.calc;

import de.scc.calculator.SccConfig;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Collections;
import java.util.function.BiFunction;
import java.util.function.Predicate;

/**
 * Core material calculation engine reconstructed from the SCC 1.6.5 bytecode.
 *
 * <p>The iterative traversals are intentional: large recipe graphs must not
 * consume the Java call stack while discovering a plan or calculating node
 * depths. The result tree is built recursively only after the node/depth/time
 * budgets have been established.</p>
 */
public final class CalculationEngine {
    private final RecipeBook book;
    private final ItemGraph graph;
    private final CraftingPlan plan;
    private final CalcSettings settings;
    private final SccConfig config;

    private Predicate<SccRecipe> runnable = recipe -> true;
    private long deadline;
    private boolean timedOut;
    private boolean truncated;
    private int nodeBudget;
    private BiFunction<StackKey, String, SccRecipe> lookup = (item, id) -> null;
    private Map<StackKey, Long> availableStock = Map.of();

    public CalculationEngine(RecipeBook book, ItemGraph graph, CraftingPlan plan, CalcSettings settings) {
        this.book = book;
        this.graph = graph;
        this.plan = plan;
        this.settings = settings;
        this.config = SccConfig.get();
    }

    /**
     * Supplies an immutable stock snapshot, usually from SccNetwork.itemContents(view).
     * Values are normalized to positive quantities and copied so the calculation
     * cannot observe inventory mutations halfway through a plan.
     */
    public CalculationEngine stock(Map<StackKey, Long> stock) {
        if (stock == null || stock.isEmpty()) {
            availableStock = Map.of();
            return this;
        }
        Map<StackKey, Long> normalized = new HashMap<>();
        stock.forEach((key, amount) -> {
            if (key != null && amount != null && amount > 0L) normalized.put(key, amount);
        });
        availableStock = normalized.isEmpty() ? Map.of() : Collections.unmodifiableMap(normalized);
        return this;
    }

    public Map<StackKey, Long> stockSnapshot() {
        return availableStock;
    }

    public CalculationEngine recipeLookup(BiFunction<StackKey, String, SccRecipe> lookup) {
        this.lookup = lookup == null ? (item, id) -> null : lookup;
        return this;
    }

    private SccRecipe byId(StackKey item, String id) {
        SccRecipe recipe = book.byId(id);
        return recipe != null ? recipe : lookup.apply(item, id);
    }

    public CalculationEngine runnableCheck(Predicate<SccRecipe> runnable) {
        this.runnable = runnable == null ? recipe -> true : runnable;
        return this;
    }

    public CalcResult calculate(StackKey target, long requestedAmount) {
        long amount = Math.max(1L, requestedAmount);
        deadline = System.nanoTime() + Math.max(200L, config.maxMillis) * 1_000_000L;
        timedOut = false;
        truncated = false;
        nodeBudget = config.maxNodes;

        Set<StackKey> baseItems = new HashSet<>(settings.allBaseItems());
        Set<StackKey> cycles = new LinkedHashSet<>(plan.brokenCycles());
        for (StackKey cycle : plan.brokenCycles()) {
            if (settings.recipeOverride(cycle) == null && settings.ranking(cycle).isEmpty()) {
                baseItems.add(cycle);
            }
        }

        Map<StackKey, SccRecipe> selected = new LinkedHashMap<>();
        for (int attempt = 0; attempt < 256; attempt++) {
            selected.clear();
            StackKey broken = collectPlan(target, selected, baseItems);
            if (broken == null) {
                break;
            }
            baseItems.add(broken);
            cycles.add(broken);
        }

        Map<StackKey, Integer> depths = new HashMap<>();
        computeDepths(target, selected, depths);
        List<StackKey> orderedItems = new ArrayList<>(depths.keySet());
        orderedItems.sort(Comparator
                .comparingInt((StackKey key) -> -depths.getOrDefault(key, 0))
                .thenComparing(CalculationEngine::compareKeys));

        Map<StackKey, Long> needed = new HashMap<>();
        Map<StackKey, Long> catalysts = new HashMap<>();
        Map<StackKey, Long> remainingStock = new HashMap<>(availableStock);
        needed.put(target, amount);
        Map<StackKey, Long> leftovers = new LinkedHashMap<>();
        Map<StackKey, Long> surplus = new LinkedHashMap<>();
        List<CalcResult.Step> steps = new ArrayList<>();

        for (StackKey item : orderedItems) {
            long demand = needed.getOrDefault(item, 0L);
            long catalystDemand = catalysts.getOrDefault(item, 0L);
            long stock = remainingStock.getOrDefault(item, 0L);
            long usedFromStock = Math.min(demand, stock);
            if (usedFromStock > 0L) {
                stock -= usedFromStock;
                remainingStock.put(item, stock);
            }
            long craftDemand = demand - usedFromStock;
            long missingCatalysts = Math.max(0L, catalystDemand - stock);
            long effectiveDemand = safeAdd(craftDemand, missingCatalysts);
            if (effectiveDemand <= 0L) {
                continue;
            }

            SccRecipe recipe = selected.get(item);
            if (recipe == null) {
                baseItems.add(item);
                surplus.merge(item, effectiveDemand, CalculationEngine::safeAdd);
                continue;
            }

            int outputCount = Math.max(1, recipe.outputCount());
            long crafts = recipe.runsFor(effectiveDemand);
            long producedByCrafting = recipe.chancy()
                    ? effectiveDemand
                    : safeMul(crafts, outputCount);
            long produced = safeAdd(usedFromStock, producedByCrafting);
            if (producedByCrafting > effectiveDemand) {
                leftovers.put(item, producedByCrafting - effectiveDemand);
            }
            steps.add(new CalcResult.Step(item, producedByCrafting, effectiveDemand, crafts, recipe,
                    depths.getOrDefault(item, 0)));

            for (ItemGraph.Child child : graph.childrenOf(recipe)) {
                if (child.consumed()) {
                    long required = safeMul(crafts, child.count());
                    needed.merge(child.item(), required, CalculationEngine::safeAdd);
                } else {
                    catalysts.merge(child.item(), (long) child.count(), Math::max);
                }
            }
        }

        steps.sort(Comparator.comparingInt(CalcResult.Step::depth)
                .thenComparing(step -> step.item().encode()));

        List<CalcResult.Entry> baseResources = new ArrayList<>();
        surplus.forEach((key, value) -> baseResources.add(new CalcResult.Entry(key, value)));
        baseResources.sort(Comparator.comparingLong((CalcResult.Entry entry) -> -entry.amount())
                .thenComparing(entry -> entry.item().encode()));

        CalcNode tree = buildTree(target, amount, selected, 0, "r", null, new HashMap<>(availableStock), true);
        if (timedOut) {
            truncated = true;
        }

        Set<StackKey> includedCycles = new LinkedHashSet<>();
        for (StackKey cycle : cycles) {
            if (depths.containsKey(cycle)) {
                includedCycles.add(cycle);
            }
        }

        return new CalcResult(target, amount, tree, List.copyOf(baseResources), List.copyOf(steps),
                Map.copyOf(leftovers), Set.copyOf(includedCycles), truncated,
                tree == null ? 0 : tree.flatten().size());
    }

    private SccRecipe recipeFor(StackKey item, Set<StackKey> excluded) {
        if (excluded.contains(item)) {
            return null;
        }

        for (String recipeId : settings.ranking(item)) {
            SccRecipe ranked = byId(item, recipeId);
            if (ranked != null && runnable.test(ranked)) {
                return ranked;
            }
        }

        String overrideId = settings.recipeOverride(item);
        if (overrideId != null) {
            SccRecipe override = byId(item, overrideId);
            if (override != null) {
                return override;
            }
        }

        SccRecipe preferred = plan.bestRecipe(item);
        if (preferred != null && runnable.test(preferred)) {
            return preferred;
        }

        SccRecipe smallest = null;
        int smallestInputs = Integer.MAX_VALUE;
        for (SccRecipe candidate : book.recipesFor(item)) {
            List<ItemGraph.Child> children = graph.childrenOf(candidate);
            if (children.isEmpty() && !book.noItemInputs(candidate)) {
                continue;
            }
            if (!runnable.test(candidate)) {
                continue;
            }
            if (children.size() < smallestInputs) {
                smallestInputs = children.size();
                smallest = candidate;
            }
        }
        return smallest != null ? smallest : preferred;
    }

    /**
     * Builds a cycle-free recipe selection. When a cycle closes at the root,
     * the parent immediately above the root is returned as the next base item;
     * otherwise the repeated active item is returned.
     */
    private StackKey collectPlan(StackKey root, Map<StackKey, SccRecipe> selected,
                                 Set<StackKey> excluded) {
        Deque<StackKey> path = new ArrayDeque<>();
        Set<StackKey> active = new HashSet<>();
        Set<StackKey> complete = new HashSet<>();
        Deque<Frame> frames = new ArrayDeque<>();
        frames.push(new Frame(root));

        while (!frames.isEmpty()) {
            if (checkTime()) {
                return null;
            }
            Frame frame = frames.peek();
            if (frame.children == null) {
                StackKey item = frame.item;
                if (complete.contains(item)) {
                    frames.pop();
                    continue;
                }
                if (active.contains(item)) {
                    if (item.equals(root) && !path.isEmpty() && !path.peek().equals(root)) {
                        return path.peek();
                    }
                    return item;
                }

                SccRecipe recipe = recipeFor(item, excluded);
                if (recipe == null) {
                    complete.add(item);
                    frames.pop();
                    continue;
                }
                selected.put(item, recipe);
                frame.children = graph.childrenOf(recipe);
                path.push(item);
                active.add(item);
            }

            if (frame.next < frame.children.size()) {
                ItemGraph.Child child = frame.children.get(frame.next++);
                frames.push(new Frame(child.item()));
                continue;
            }

            active.remove(path.pop());
            complete.add(frame.item);
            frames.pop();
        }
        return null;
    }

    private void computeDepths(StackKey root, Map<StackKey, SccRecipe> selected,
                               Map<StackKey, Integer> depths) {
        Deque<StackKey> stack = new ArrayDeque<>();
        Set<StackKey> active = new HashSet<>();
        Set<StackKey> visited = new HashSet<>();
        stack.push(root);

        while (!stack.isEmpty()) {
            StackKey item = stack.peek();
            if (depths.containsKey(item)) {
                stack.pop();
                active.remove(item);
                visited.remove(item);
                continue;
            }

            SccRecipe recipe = selected.get(item);
            if (recipe == null) {
                depths.put(item, 0);
                stack.pop();
                active.remove(item);
                visited.remove(item);
                continue;
            }

            if (active.add(item)) {
                visited.add(item);
                boolean dependenciesResolved = true;
                for (ItemGraph.Child child : graph.childrenOf(recipe)) {
                    StackKey childKey = child.item();
                    if (depths.containsKey(childKey)) {
                        continue;
                    }
                    if (visited.contains(childKey)) {
                        depths.put(childKey, 0);
                    } else {
                        stack.push(childKey);
                        dependenciesResolved = false;
                    }
                }
                if (!dependenciesResolved) {
                    continue;
                }
            }

            int depth = 0;
            for (ItemGraph.Child child : graph.childrenOf(recipe)) {
                depth = Math.max(depth, depths.getOrDefault(child.item(), 0) + 1);
            }
            depths.put(item, depth);
            stack.pop();
            active.remove(item);
            visited.remove(item);
        }
    }

    private CalcNode buildTree(StackKey item, long amount, Map<StackKey, SccRecipe> selected,
                               int depth, String key, String ingredientKey,
                               Map<StackKey, Long> remainingStock, boolean consumedByParent) {
        int alternatives = book.recipesFor(item).size();
        nodeBudget--;
        if (nodeBudget <= 0 || depth >= config.maxDepth || checkTime()) {
            truncated = true;
            return new CalcNode(item, amount, CalcNode.Kind.LIMIT, depth, key,
                    null, 0L, amount, alternatives, ingredientKey, List.of());
        }

        long stock = remainingStock.getOrDefault(item, 0L);
        long usedFromStock = Math.min(amount, stock);
        if (consumedByParent && usedFromStock > 0L) {
            remainingStock.put(item, stock - usedFromStock);
        }
        long uncovered = amount - usedFromStock;
        if (uncovered <= 0L) {
            return new CalcNode(item, amount, CalcNode.Kind.BASE, depth, key,
                    null, 0L, usedFromStock, alternatives, ingredientKey, List.of());
        }

        SccRecipe recipe = selected.get(item);
        if (recipe == null) {
            CalcNode.Kind kind = alternatives > 0 && !settings.isBase(item)
                    ? CalcNode.Kind.CYCLE
                    : CalcNode.Kind.BASE;
            return new CalcNode(item, amount, kind, depth, key,
                    null, 0L, usedFromStock, alternatives, ingredientKey, List.of());
        }

        int outputCount = Math.max(1, recipe.outputCount());
        long crafts = recipe.runsFor(uncovered);
        long producedByCrafting = recipe.chancy() ? uncovered : safeMul(crafts, outputCount);
        long produced = safeAdd(usedFromStock, producedByCrafting);
        List<CalcNode> children = new ArrayList<>();
        int childIndex = 0;
        for (ItemGraph.Child child : graph.childrenOf(recipe)) {
            long childAmount = child.consumed()
                    ? safeMul(crafts, child.count())
                    : child.count();
            CalcNode childNode = buildTree(child.item(), childAmount, selected,
                    depth + 1, key + "/" + childIndex++, child.key(), remainingStock, child.consumed());
            if (childNode != null) {
                children.add(childNode);
            }
        }

        return new CalcNode(item, amount, CalcNode.Kind.CRAFTED, depth, key,
                recipe, crafts, produced, alternatives, ingredientKey, children);
    }

    private boolean checkTime() {
        if (timedOut) {
            return true;
        }
        if (System.nanoTime() > deadline) {
            timedOut = true;
            return true;
        }
        return false;
    }

    public static long ceilDiv(long numerator, long denominator) {
        if (denominator <= 0L) {
            return numerator;
        }
        return Math.ceilDiv(numerator, denominator);
    }

    private static final long SATURATED_AMOUNT = 2_305_843_009_213_693_951L;

    private static long safeMul(long first, long second) {
        if (first <= 0L || second <= 0L) return 0L;
        long result = first * second;
        return result / second != first ? SATURATED_AMOUNT : result;
    }

    private static long safeAdd(long first, long second) {
        if (first <= 0L) return Math.max(0L, second);
        if (second <= 0L) return first;
        return first > Long.MAX_VALUE - second ? SATURATED_AMOUNT : first + second;
    }

    private static int compareKeys(StackKey left, StackKey right) {
        int origin = Integer.compare(left.encode().startsWith("minecraft:") ? 0 : 1,
                right.encode().startsWith("minecraft:") ? 0 : 1);
        if (origin != 0) {
            return origin;
        }
        String a = left.encode();
        String b = right.encode();
        int length = Integer.compare(a.length(), b.length());
        return length != 0 ? length : a.compareTo(b);
    }

    private static final class Frame {
        private final StackKey item;
        private List<ItemGraph.Child> children;
        private int next;

        private Frame(StackKey item) {
            this.item = item;
        }
    }
}
