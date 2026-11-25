package de.scc.calculator.api;

import de.scc.calculator.SccLog;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Public extension API for machine-specific recipe-step compatibility.
 *
 * <p>Filters are advisory compatibility predicates. A filter returning
 * {@code Boolean.FALSE} rejects a step; {@code TRUE} or {@code null} leaves
 * the decision open for other filters and the machine itself.</p>
 */
public final class SccMachines {
    private static volatile boolean warned;
    private static final List<StepFilter> FILTERS = new CopyOnWriteArrayList<>();

    private SccMachines() { }

    /** Returns a machine's carried tools without loading an unloaded chunk. */
    public static List<ItemStack> heldTools(Level level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) return List.of();
        try {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Machine machine) {
                List<ItemStack> tools = machine.sccHeldTools();
                return tools == null ? List.of() : List.copyOf(tools);
            }
        } catch (RuntimeException error) {
            SccLog.caught("machine", "held tools failed at " + pos.toShortString(), error);
        }
        return List.of();
    }

    /** Notifies an implemented machine after a recipe step has been dispatched. */
    public static void pushed(Level level, BlockPos pos, String step, String recipeId) {
        if (level == null || pos == null || step == null || !level.isLoaded(pos)) return;
        try {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Machine machine) {
                machine.sccStepPushed(step, recipeId);
            }
        } catch (RuntimeException error) {
            SccLog.caught("machine", "step notification failed at " + pos.toShortString(), error);
        }
    }

    /** Registers an additional compatibility filter; duplicate registrations are retained. */
    public static void register(StepFilter filter) {
        if (filter != null) FILTERS.add(filter);
    }

    /**
     * Returns false only when a machine or registered filter explicitly rejects
     * this step. Unloaded positions are not queried and therefore are not forced
     * to load as a side effect of recipe planning.
     */
    public static boolean accepts(Level level, BlockPos pos, String step, String recipeId) {
        if (level == null || pos == null || step == null || !level.isLoaded(pos)) return true;
        try {
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof Machine machine && !machine.sccRunsStep(step, recipeId)) {
                return false;
            }
            for (StepFilter filter : FILTERS) {
                if (Boolean.FALSE.equals(filter.runsStep(level, pos, step, recipeId))) return false;
            }
        } catch (RuntimeException error) {
            if (!warned) {
                warned = true;
                SccLog.caught("machine", "step compatibility check failed at " + pos.toShortString(), error);
            }
        }
        return true;
    }

    /** Implement on a machine BlockEntity to participate in SCC compatibility checks. */
    public interface Machine {
        boolean sccRunsStep(String step, String recipeId);

        default void sccStepPushed(String step, String recipeId) { }

        default List<ItemStack> sccHeldTools() { return List.of(); }
    }

    /** Third-party compatibility filter. Return false to reject; null means no opinion. */
    @FunctionalInterface
    public interface StepFilter {
        Boolean runsStep(Level level, BlockPos pos, String step, String recipeId);
    }
}
