package de.scc.calculator.client;

import de.scc.calculator.calc.AnalysisDelta;
import de.scc.calculator.calc.AnalysisJob;
import de.scc.calculator.calc.CalcSettings;
import de.scc.calculator.calc.CatalystTags;
import de.scc.calculator.calc.CraftingPlan;
import de.scc.calculator.calc.ItemGraph;
import de.scc.calculator.calc.RecipeBook;
import de.scc.calculator.calc.RecipeSource;
import de.scc.calculator.calc.StackKey;

import de.scc.calculator.SccConfig;
import de.scc.calculator.SccLog;
import de.scc.calculator.api.SccRecipeProviders;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;

/**
 * Client-owned lifecycle for the original SCC AnalysisJob. Only the job's
 * platform-neutral Java 21 bytecode is embedded; source discovery and lifecycle
 * are Fabric-native.
 */
@Environment(EnvType.CLIENT)
public final class AnalysisManager {
    public enum State { IDLE, RUNNING, READY, ERROR }

    private static final AnalysisManager INSTANCE = new AnalysisManager();

    private volatile State state = State.IDLE;
    private RecipeBook book;
    private ItemGraph graph;
    private CraftingPlan plan;
    private AnalysisJob job;
    private RecipeSource source;
    private String message = "";
    private String fingerprint = "";
    private Map<StackKey, Double> costs = Map.of();
    private boolean dirty = true;
    private boolean initialStartQueued;
    private int initialDelay;
    private boolean loadedFromCache;
    private int addonWaitTicks;
    private Object observedLevel;

    private AnalysisManager() {
        CalcSettings.get().setInvalidationCallbacks(this::markDirty, this::invalidateIngredients);
        SccRecipeProviders.setChangeListener(this::onRecipesChanged);
        CatalystTags.setChangeListener(this::onRecipesChanged);
    }

    public static AnalysisManager get() {
        return INSTANCE;
    }

    public synchronized void markDirty() {
        dirty = true;
    }

    public synchronized boolean isDirty() {
        return dirty;
    }

    public synchronized void reanalyse() {
        dirty = true;
        onRecipesChanged();
        start();
    }

    public synchronized void onRecipesChanged() {
        dirty = true;
        if (job != null) job.cancel();
        job = null;
        source = null;
        book = null;
        graph = null;
        plan = null;
        costs = Map.of();
        state = State.IDLE;
        message = "";
    }

    /** Called from END_CLIENT_TICK. The budget is milliseconds for this tick. */
    public synchronized void tick(long millisBudget) {
        Object level = Minecraft.getInstance().level;
        if (level == null) {
            initialStartQueued = false;
            initialDelay = 0;
            addonWaitTicks = 0;
            observedLevel = null;
            if (job != null) job.cancel();
            job = null;
            if (state == State.RUNNING) state = State.IDLE;
            dirty = true;
            return;
        }

        if (observedLevel != level) {
            observedLevel = level;
            onRecipesChanged();
            initialStartQueued = false;
            initialDelay = 20;
            addonWaitTicks = 0;
        }
        if (!initialStartQueued) {
            initialStartQueued = true;
            initialDelay = 20;
        }
        if (state == State.IDLE && SccConfig.get().autoAnalyse) {
            if (initialDelay > 0) {
                initialDelay--;
            } else if (dirty) {
                if (!SccRecipeProviders.allReady() && addonWaitTicks < 12_000) {
                    addonWaitTicks++;
                    message = "Aguardando provedores de receitas (" + Math.round(SccRecipeProviders.progress() * 100) + "%)";
                    return;
                }
                addonWaitTicks = 0;
                start();
            }
        }

        if (state != State.RUNNING || job == null) return;
        try {
            job.step(Math.max(1L, Math.min(50L, millisBudget)));
            Throwable failure = job.failure();
            if (failure != null) throw new IllegalStateException("AnalysisJob failed in phase " + job.phase(), failure);
            if (job.done()) {
                book = job.book();
                graph = job.graph();
                plan = job.result();
                costs = Map.copyOf(job.costs());
                state = State.READY;
                dirty = false;
                loadedFromCache = false;
                message = "";
                int mappingCount = plan == null ? 0 : plan.all().size();
                SccLog.info("analysis", "analysis complete: " + book.recipeCount() + " recipes, "
                        + book.allItems().size() + " items, " + mappingCount + " mappings");
                job = null;
            }
        } catch (Throwable error) {
            state = State.ERROR;
            message = error.getClass().getSimpleName() + ": " + String.valueOf(error.getMessage());
            SccLog.error("analysis", "recipe analysis failed", error);
            if (job != null) job.cancel();
            job = null;
        }
    }

    public synchronized Double costOf(StackKey item) {
        return item == null ? null : costs.get(item);
    }

    public synchronized void start() {
        start(false);
    }

    public synchronized void start(boolean keepPreviousSnapshot) {
        if (Minecraft.getInstance().level == null) {
            state = State.ERROR;
            message = "no world";
            return;
        }
        if (state == State.RUNNING && job != null) job.cancel();
        if (!keepPreviousSnapshot) {
            book = null;
            graph = null;
            plan = null;
            costs = Map.of();
        }
        if (!SccRecipeProviders.allReady()) {
            state = State.IDLE;
            dirty = true;
            message = "Aguardando provedores de receitas";
            return;
        }
        source = new VanillaRecipeSource();
        fingerprint = computeFingerprint();
        job = new AnalysisJob(source, fingerprint);
        state = State.RUNNING;
        dirty = false;
        message = "";
        SccLog.info("analysis", "analysis started, source=" + source.name() + ", fingerprint=" + fingerprint);
    }

    public synchronized boolean waitingForAddons() {
        return state == State.IDLE && !SccRecipeProviders.allReady();
    }

    public synchronized void cancel() {
        if (job != null) job.cancel();
        job = null;
        if (state == State.RUNNING) state = plan == null ? State.IDLE : State.READY;
    }

    public synchronized void clearCache() {
        book = null;
        graph = null;
        plan = null;
        costs = Map.of();
        loadedFromCache = false;
        dirty = true;
        state = State.IDLE;
    }

    public synchronized void invalidateIngredients() {
        dirty = true;
    }

    public synchronized State state() { return state; }
    public synchronized boolean isReady() { return state == State.READY && plan != null; }
    public synchronized boolean isRunning() { return state == State.RUNNING; }
    public synchronized boolean loadedFromCache() { return loadedFromCache; }
    public synchronized String message() { return message; }
    public synchronized double progress() { return job == null ? (isReady() ? 1.0d : 0.0d) : job.progress(); }
    public synchronized AnalysisJob.Phase phase() { return job == null ? null : job.phase(); }
    public synchronized String detail() { return job == null ? message : job.detail(); }
    public synchronized int recipesFound() { return job == null ? (book == null ? 0 : book.recipeCount()) : job.recipesFound(); }
    public synchronized int itemsResolved() { return job == null ? (graph == null ? 0 : graph.allItems().size()) : job.itemsResolved(); }
    public synchronized int totalItems() { return job == null ? (graph == null ? 0 : graph.allItems().size()) : job.totalItems(); }
    public synchronized ItemGraph graph() { return graph != null ? graph : job == null ? null : job.graph(); }
    public synchronized CraftingPlan plan() { return plan; }
    public synchronized RecipeBook book() { return book != null ? book : job == null ? null : job.book(); }
    public synchronized String sourceName() { return source == null ? (book == null ? "?" : book.source()) : source.name(); }

    private static String computeFingerprint() {
        StringBuilder input = new StringBuilder("scc-fabric-core-v1\n");
        if (Minecraft.getInstance().level != null) {
            Minecraft.getInstance().level.getRecipeManager().getRecipes().stream()
                    .map(holder -> holder.id().toString()).sorted().forEach(id -> input.append(id).append('\n'));
        }
        input.append("providers=").append(SccRecipeProviders.stamp()).append('\n');
        input.append("catalysts=").append(CatalystTags.stamp()).append('\n');
        SccConfig config = SccConfig.get();
        input.append("blockedRecipes=").append(config.blockedRecipes).append('\n')
                .append("blockedItems=").append(config.blockedItems).append('\n')
                .append("blockedTypes=").append(config.blockedRecipeTypes).append('\n')
                .append("preferredNamespaces=").append(config.preferredRecipeNamespaces).append('\n')
                .append("preferSameMod=").append(config.preferSameModRecipes);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(input.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, 12);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
