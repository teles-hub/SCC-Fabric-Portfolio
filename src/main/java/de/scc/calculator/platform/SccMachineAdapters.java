package de.scc.calculator.platform;

import de.scc.calculator.SccLog;
import de.scc.calculator.calc.StackKey;
import de.scc.calculator.compat.cobblemon.CobblemonPot;
import de.scc.calculator.compat.cobblemon.SccCobblemonCampfirePotProcessor;
import de.scc.calculator.compat.cobblecuisine.CobbleCuisineMixer;
import de.scc.calculator.platform.storage.SccItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Loader-neutral integration point for machine inventories exposed to SCC.
 *
 * <p>Processing machines query aliases and virtual item handlers through this
 * registry instead of depending on mod-specific APIs. Optional adapters are
 * isolated: one incompatible third-party block entity cannot abort machine
 * discovery for all other adapters.</p>
 */
public final class SccMachineAdapters {
    private static final Map<ResourceLocation, Adapter> ADAPTERS = new LinkedHashMap<>();
    private static boolean builtInsRegistered;

    private SccMachineAdapters() { }

    public static synchronized void register(ResourceLocation id, Adapter adapter) {
        if (id == null || adapter == null) return;
        ADAPTERS.put(id, adapter);
    }

    /** Registers built-in optional integrations once during mod initialization. */
    public static synchronized void registerBuiltIns() {
        if (builtInsRegistered) return;
        builtInsRegistered = true;
        register(ResourceLocation.parse("scc:cobblemon_campfire_pot"), new Adapter() {
            @Override
            public ResourceLocation machineAlias(Level level, BlockPos pos) {
                return CobblemonPot.machineAlias(level, pos);
            }

            @Override
            public SccMachineProcessor processor(Level level, BlockPos pos) {
                return CobblemonPot.machineAlias(level, pos) == null
                        ? null : SccCobblemonCampfirePotProcessor.INSTANCE;
            }

            @Override
            public SccItemHandler items(Level level, BlockPos pos, Supplier<String> recipeId,
                                        Supplier<Set<StackKey>> inputs, Supplier<StackKey> output) {
                return CobblemonPot.items(level, pos, recipeId, inputs, output);
            }
        });
        // Discovery-only integration. CobbleCuisine's Mixer does not expose
        // an idempotent operation API, so this adapter deliberately has no
        // inventory bridge or SccMachineProcessor.
        register(ResourceLocation.parse("scc:cobblecuisine_mixer"), new Adapter() {
            @Override
            public ResourceLocation machineAlias(Level level, BlockPos pos) {
                return CobbleCuisineMixer.machineAlias(level, pos);
            }

            @Override
            public SccItemHandler items(Level level, BlockPos pos, Supplier<String> recipeId,
                                        Supplier<Set<StackKey>> inputs, Supplier<StackKey> output) {
                return null;
            }
        });
    }

    public static ResourceLocation machineAlias(Level level, BlockPos pos) {
        for (Map.Entry<ResourceLocation, Adapter> entry : snapshot()) {
            try {
                ResourceLocation alias = entry.getValue().machineAlias(level, pos);
                if (alias != null) return alias;
            } catch (Throwable error) {
                SccLog.caught("machine-adapters", "alias detection failed for " + entry.getKey(), error);
            }
        }
        return null;
    }

    /** Returns a processor only when an adapter explicitly implements real machine execution. */
    public static SccMachineProcessor processor(Level level, BlockPos pos) {
        for (Map.Entry<ResourceLocation, Adapter> entry : snapshot()) {
            try {
                SccMachineProcessor processor = entry.getValue().processor(level, pos);
                if (processor != null) return processor;
            } catch (Throwable error) {
                SccLog.caught("machine-adapters", "processor lookup failed for " + entry.getKey(), error);
            }
        }
        return null;
    }

    public static SccItemHandler items(Level level, BlockPos pos,
                                       Supplier<String> recipeId,
                                       Supplier<Set<StackKey>> inputs,
                                       Supplier<StackKey> output) {
        for (Map.Entry<ResourceLocation, Adapter> entry : snapshot()) {
            try {
                SccItemHandler handler = entry.getValue().items(level, pos, recipeId, inputs, output);
                if (handler != null) return handler;
            } catch (Throwable error) {
                SccLog.caught("machine-adapters", "inventory bridge failed for " + entry.getKey(), error);
            }
        }
        return null;
    }

    public static synchronized List<ResourceLocation> registeredAdapters() {
        return List.copyOf(ADAPTERS.keySet());
    }

    private static synchronized List<Map.Entry<ResourceLocation, Adapter>> snapshot() {
        return ADAPTERS.entrySet().stream()
                .map(entry -> Map.entry(entry.getKey(), entry.getValue()))
                .toList();
    }

    public interface Adapter {
        ResourceLocation machineAlias(Level level, BlockPos pos);

        /** Existing inventory-only adapters remain source compatible and do not execute recipes. */
        default SccMachineProcessor processor(Level level, BlockPos pos) { return null; }

        SccItemHandler items(Level level, BlockPos pos,
                             Supplier<String> recipeId,
                             Supplier<Set<StackKey>> inputs,
                             Supplier<StackKey> output);
    }
}
