package de.scc.calculator.api;

import de.scc.calculator.calc.StackKey;
import de.scc.calculator.calc.CalcSettings;
import de.scc.calculator.calc.CalcResult;
import de.scc.calculator.calc.CalculationEngine;
import de.scc.calculator.calc.CraftingPlan;
import de.scc.calculator.calc.ItemGraph;
import de.scc.calculator.calc.RecipeBook;
import de.scc.calculator.network.LongStoreHandler;
import de.scc.calculator.network.SccNetworkCache;
import de.scc.calculator.network.SccNetworkChemicalAccess;
import de.scc.calculator.network.SccNetworkView;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import team.reborn.energy.api.EnergyStorage;

import java.util.Map;

/**
 * Public Fabric-native access to an SCC network.
 *
 * <p>This facade deliberately exposes Fabric Transfer API and SCC resource
 * contracts instead of leaking NeoForge types from the original mod. All
 * mutation remains server-authoritative: callers should invoke mutating
 * methods from server code and use the transaction-aware storage endpoints
 * when interoperating with Fabric pipes or machines.</p>
 */
public final class SccNetwork {
    private SccNetwork() { }

    /** Resolve the connected network rooted at {@code origin}; unloaded chunks are never forced. */
    public static SccNetworkView at(Level level, BlockPos origin) {
        return SccNetworkCache.view(level, origin);
    }

    public static boolean connected(SccNetworkView view) {
        return view != null && view.online() && !view.truncated();
    }

    public static Map<StackKey, Long> itemContents(SccNetworkView view) {
        return view == null ? Map.of() : view.itemContents();
    }

    /**
     * Immutable inventory snapshot for calculation/planning. The returned map
     * can be passed to {@link CalculationEngine#stock(Map)} and is detached
     * from subsequent network mutations.
     */
    public static Map<StackKey, Long> stockSnapshot(SccNetworkView view) {
        return itemContents(view);
    }

    /**
     * Creates a calculation engine seeded with the connected network's current
     * item stock. It plans only missing resources instead of asking machines to
     * craft items already present in storage.
     */
    public static CalculationEngine calculationEngine(SccNetworkView view,
            RecipeBook recipes, ItemGraph graph, CraftingPlan plan, CalcSettings settings) {
        return new CalculationEngine(recipes, graph, plan, settings).stock(stockSnapshot(view));
    }

    /** Convenience preview using a single consistent stock snapshot. */
    public static CalcResult calculate(SccNetworkView view, RecipeBook recipes, ItemGraph graph,
            CraftingPlan plan, CalcSettings settings, StackKey target, long amount) {
        if (view == null || view.truncated()) return null;
        return calculationEngine(view, recipes, graph, plan, settings).calculate(target, amount);
    }

    public static long count(SccNetworkView view, StackKey item) {
        return view == null || item == null ? 0L : view.count(item);
    }

    /** Inserts into the connected item stores and returns the uninserted remainder. */
    public static ItemStack insert(SccNetworkView view, ItemStack stack, boolean simulate) {
        if (view == null || stack == null || stack.isEmpty()) return stack == null ? ItemStack.EMPTY : stack.copy();
        if (view.truncated()) return stack.copy();
        return view.insert(stack, simulate);
    }

    /** Extracts a component-sensitive item variant from connected stores. */
    public static ItemStack extract(SccNetworkView view, StackKey key, ItemStack exemplar,
                                    int amount, boolean simulate) {
        if (view == null || view.truncated()) return ItemStack.EMPTY;
        return view.extract(key, exemplar, amount, simulate);
    }

    /** Transaction-aware aggregate item endpoint for Fabric Transfer API integrations. */
    public static Storage<ItemVariant> items(SccNetworkView view) {
        return view == null || view.truncated() ? null : view.itemTransferStorage();
    }

    /** Transaction-aware aggregate fluid endpoint for Fabric Transfer API integrations. */
    public static Storage<FluidVariant> fluids(SccNetworkView view) {
        return view == null || view.truncated() ? null : view.fluidTransferStorage();
    }

    /** Aggregate Team Reborn Energy endpoint. */
    public static EnergyStorage energy(SccNetworkView view) {
        return view == null || view.truncated() ? null : view.energyTransferStorage();
    }

    /** SCC-native chemical access; intentionally does not claim Mekanism compatibility. */
    public static SccNetworkChemicalAccess chemicals(SccNetworkView view) {
        return view == null || view.truncated() ? null : view.chemicalTransferStorage();
    }

    public static LongStoreHandler source(SccNetworkView view) {
        return view == null || view.truncated() ? null : view.sourceTransferStorage();
    }

    public static LongStoreHandler soul(SccNetworkView view) {
        return view == null || view.truncated() ? null : view.soulTransferStorage();
    }
}
