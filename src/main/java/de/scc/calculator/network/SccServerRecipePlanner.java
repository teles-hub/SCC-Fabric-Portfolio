package de.scc.calculator.network;

import de.scc.calculator.api.SccNetwork;
import de.scc.calculator.api.SccRecipeProviders;
import de.scc.calculator.calc.CalcResult;
import de.scc.calculator.calc.CalcSettings;
import de.scc.calculator.calc.CalculationEngine;
import de.scc.calculator.calc.CraftingPlan;
import de.scc.calculator.calc.ItemGraph;
import de.scc.calculator.calc.LevelRecipeSource;
import de.scc.calculator.calc.RecipeBook;
import de.scc.calculator.calc.SccRecipe;
import de.scc.calculator.calc.StackKey;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Builds bounded, server-authoritative craft plans using the connected network's stock snapshot. */
public final class SccServerRecipePlanner {
    public static final int MAX_RECIPES_PER_REQUEST = 8_192;
    public static final int MAX_ORDER_LIST_TARGETS = 16;
    private static final int SCAN_CHUNK = 256;
    private static final int MAX_SCAN_CHUNKS = MAX_RECIPES_PER_REQUEST / SCAN_CHUNK;

    private SccServerRecipePlanner() { }

    public static Outcome plan(ServerLevel level, BlockPos terminalPos, String encodedTarget,
                               long amount, UUID owner) {
        if (level == null || terminalPos == null || owner == null) return Outcome.failure("job_invalid");
        if (level.isClientSide || amount < 1L || amount > SccCraftJobQueue.MAX_AMOUNT) {
            return Outcome.failure("job_invalid");
        }
        StackKey target = StackKey.decode(encodedTarget);
        if (target == null) return Outcome.failure("job_invalid");
        Prepared prepared = prepare(level, terminalPos);
        if (prepared.failure() != null) return Outcome.failure(prepared.failure());
        return planPrepared(prepared, target, amount, owner);
    }

    /**
     * Plans a bounded saved order list after scanning the recipe book only once.
     * Jobs remain plans, not reservations: the future scheduler must revalidate
     * and transactionally acquire inputs before dispatching any machine step.
     */
    public static BatchOutcome planOrderList(ServerLevel level, BlockPos terminalPos,
                                             String listId, UUID owner) {
        if (level == null || terminalPos == null || owner == null || listId == null || listId.isBlank()) {
            return BatchOutcome.failure("order_plan_invalid");
        }
        if (level.isClientSide || !level.hasChunkAt(terminalPos)
                || !(level.getBlockEntity(terminalPos) instanceof SccTerminalBlockEntity terminal)) {
            return BatchOutcome.failure("order_plan_unavailable");
        }
        OrderBook.OrderList order = terminal.orderBook().byId(listId);
        if (order == null || order.lines().isEmpty()) return BatchOutcome.failure("order_plan_not_found");
        if (order.lines().size() > MAX_ORDER_LIST_TARGETS) {
            return BatchOutcome.failure("order_plan_limit");
        }

        Prepared prepared = prepare(level, terminalPos);
        if (prepared.failure() != null) return BatchOutcome.failure(prepared.failure());

        int created = 0;
        int skipped = 0;
        int failed = 0;
        for (OrderBook.Line line : order.lines()) {
            if (terminal.craftJobs().hasActiveTarget(line.key())) {
                skipped++;
                continue;
            }
            StackKey target = StackKey.decode(line.key());
            if (target == null) {
                failed++;
                continue;
            }
            Outcome outcome = planPrepared(prepared, target, line.amount(), owner);
            if (outcome.successful()) created++;
            else failed++;
        }
        if (created > 0) terminal.setChanged();
        String status = created > 0 && failed == 0 ? "order_plan_created"
                : created > 0 ? "order_plan_partial"
                : skipped > 0 && failed == 0 ? "order_plan_already_active" : "order_plan_failed";
        return new BatchOutcome(status, created, skipped, failed);
    }

    private static Prepared prepare(ServerLevel level, BlockPos terminalPos) {
        if (!level.hasChunkAt(terminalPos)
                || !(level.getBlockEntity(terminalPos) instanceof SccTerminalBlockEntity terminal)) {
            return Prepared.failure("job_unavailable");
        }
        SccNetworkView view = SccNetworkCache.view(level, terminalPos);
        if (view.truncated() || !view.online() || !view.terminals().contains(terminalPos)
                || view.storageCount() == 0 || view.itemStorageCount() == 0) {
            return Prepared.failure("job_network_unavailable");
        }
        if (!SccRecipeProviders.allReady()) return Prepared.failure("job_providers_busy");

        RecipeBook book = new RecipeBook();
        LevelRecipeSource source = new LevelRecipeSource(level);
        source.begin();
        if (source.total() > MAX_RECIPES_PER_REQUEST) return Prepared.failure("job_recipe_limit");
        int chunks = 0;
        while (source.scanChunk(book, SCAN_CHUNK)) {
            if (++chunks >= MAX_SCAN_CHUNKS) return Prepared.failure("job_recipe_limit");
        }
        book.sort();
        if (book.isEmpty()) return Prepared.failure("job_no_recipes");

        ItemGraph graph = new ItemGraph(book);
        graph.refreshPreferred();
        Map<StackKey, SccRecipe> preferred = new HashMap<>();
        for (StackKey output : book.craftableItems()) {
            List<SccRecipe> options = book.recipesFor(output);
            if (!options.isEmpty()) preferred.put(output, options.getFirst());
        }
        String fingerprint = "network:" + view.nodeCount() + ":" + view.storageCount()
                + ":recipes:" + book.recipeCount();
        CraftingPlan selected = new CraftingPlan(preferred, Set.of(), fingerprint,
                source.name(), book.allItems().size(), book.recipeCount(), 0L);
        return new Prepared(level, terminal, view, book, graph, selected, null);
    }

    private static Outcome planPrepared(Prepared prepared, StackKey target, long amount, UUID owner) {
        if (target == null || amount < 1L || amount > SccCraftJobQueue.MAX_AMOUNT) {
            return Outcome.failure("job_invalid");
        }
        if (prepared.terminal().craftJobs().hasActiveTarget(target.encode())) {
            return Outcome.failure("job_already_active");
        }
        CalculationEngine engine = SccNetwork.calculationEngine(prepared.view(), prepared.book(),
                prepared.graph(), prepared.selected(), CalcSettings.get());
        CalcResult result;
        try {
            result = engine.calculate(target, amount);
        } catch (RuntimeException calculationFailure) {
            return Outcome.failure("job_calculation_failed");
        }
        if (result == null || result.truncated()) return Outcome.failure("job_calculation_limited");
        if (!result.isPossible()) return Outcome.failure("job_no_plan");

        List<SccCraftJobQueue.Requirement> missing = new ArrayList<>();
        for (CalcResult.Entry entry : result.baseResources()) {
            if (entry.item() != null && entry.amount() > 0L) {
                if (entry.amount() > SccCraftJobQueue.MAX_AMOUNT) {
                    return Outcome.failure("job_requirement_limit");
                }
                missing.add(new SccCraftJobQueue.Requirement(entry.item().encode(), entry.amount()));
            }
        }
        if (result.steps().size() > SccCraftJobQueue.MAX_STEPS) {
            return Outcome.failure("job_step_limit");
        }
        List<SccCraftJobQueue.PlannedStep> plannedSteps = new ArrayList<>(result.steps().size());
        long crafts = 0L;
        for (CalcResult.Step step : result.steps()) {
            if (step == null || step.item() == null || step.recipe() == null
                    || step.recipe().output() == null || !step.item().equals(step.recipe().output())
                    || step.recipe().typeId() == null || step.recipe().id() == null) {
                return Outcome.failure("job_step_invalid");
            }
            List<SccCraftJobQueue.PlannedInput> plannedInputs = new ArrayList<>();
            for (de.scc.calculator.calc.ItemGraph.Child child : prepared.graph().childrenOf(step.recipe())) {
                if (child == null || child.item() == null || child.count() <= 0
                        || child.key() == null || child.key().length() > SccCraftJobQueue.MAX_INGREDIENT_KEY_LENGTH) {
                    return Outcome.failure("job_step_inputs_invalid");
                }
                plannedInputs.add(new SccCraftJobQueue.PlannedInput(child.item().encode(), child.count(),
                        child.consumed(), child.key()));
            }
            if (plannedInputs.isEmpty() || plannedInputs.size() > SccCraftJobQueue.MAX_INPUTS_PER_STEP) {
                return Outcome.failure("job_step_inputs_invalid");
            }
            plannedSteps.add(new SccCraftJobQueue.PlannedStep(step.item().encode(),
                    step.recipe().id(), step.recipe().typeId().toString(), step.recipe().typeName(),
                    step.produced(), step.needed(), step.crafts(), step.depth(), plannedInputs));
            if (step.crafts() < 0L || crafts > SccCraftJobQueue.MAX_CRAFTS - step.crafts()) {
                return Outcome.failure("job_craft_limit");
            }
            crafts += step.crafts();
        }
        SccCraftJobQueue.Job job = prepared.terminal().craftJobs().enqueuePlanned(target.encode(), amount,
                owner, missing, plannedSteps, crafts, result.truncated(), prepared.level().getGameTime());
        if (job == null) return Outcome.failure("job_plan_invalid_or_queue_full");
        prepared.terminal().setChanged();
        return Outcome.success(job);
    }

    private record Prepared(ServerLevel level, SccTerminalBlockEntity terminal, SccNetworkView view,
                             RecipeBook book, ItemGraph graph, CraftingPlan selected, String failure) {
        private static Prepared failure(String failure) {
            return new Prepared(null, null, null, null, null, null, failure);
        }
    }

    public record Outcome(String status, SccCraftJobQueue.Job job) {
        public static Outcome success(SccCraftJobQueue.Job job) {
            return new Outcome("job_created", job);
        }
        public static Outcome failure(String status) {
            return new Outcome(status, null);
        }
        public boolean successful() { return job != null; }
    }

    public record BatchOutcome(String status, int created, int skipped, int failed) {
        public static BatchOutcome failure(String status) {
            return new BatchOutcome(status, 0, 0, 0);
        }
    }
}
