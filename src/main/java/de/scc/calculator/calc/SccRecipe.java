package de.scc.calculator.calc;

import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToLongFunction;

/**
 * Immutable normalized recipe model reconstructed from SCC 1.6.5 bytecode.
 * The algorithmic behavior in this class follows the original class file.
 */
public record SccRecipe(
        String id,
        ResourceLocation typeId,
        String typeName,
        StackKey output,
        int outputCount,
        List<SccRecipe.Input> inputs,
        List<SccRecipe.Fluid> fluids,
        float chance
) {
    public SccRecipe {
        // Original constructor normalizes invalid chance values to 1.0f.
        if (!(chance > 0.0f && chance < 1.0f)) {
            chance = 1.0f;
        }
    }

    public SccRecipe(String id, ResourceLocation typeId, String typeName,
                     StackKey output, int outputCount,
                     List<Input> inputs, List<Fluid> fluids) {
        this(id, typeId, typeName, output, outputCount, inputs, fluids, 1.0f);
    }

    public SccRecipe(String id, ResourceLocation typeId, String typeName,
                     StackKey output, int outputCount, List<Input> inputs) {
        this(id, typeId, typeName, output, outputCount, inputs, List.of(), 1.0f);
    }

    public boolean chancy() {
        return chance < 1.0f;
    }

    public SccRecipe withId(String replacementId) {
        return new SccRecipe(replacementId, typeId, typeName, output, outputCount,
                inputs, fluids, chance);
    }

    public long runsFor(long requestedOutput) {
        return runsFor(requestedOutput, outputCount, chance);
    }

    public static long runsFor(long requestedOutput, int outputCount, float chance) {
        if (requestedOutput <= 0L) {
            return 0L;
        }

        int safeOutputCount = Math.max(1, outputCount);
        if (!(chance > 0.0f && chance < 1.0f)) {
            return Math.ceilDiv(requestedOutput, safeOutputCount);
        }

        double estimatedRuns = Math.ceil(
                ((double) requestedOutput / ((double) safeOutputCount * (double) chance)) - 1.0E-6d
        );
        return Math.max(1L, (long) Math.min(estimatedRuns, 2.305843009213694E18d));
    }

    public static int percent(float chance) {
        if (!(chance > 0.0f && chance < 1.0f)) {
            return 100;
        }
        return Math.max(1, Math.min(99, Math.round(chance * 100.0f)));
    }

    public boolean hasRequiredSubstance() {
        if (!inputs.isEmpty()) {
            return true;
        }
        for (Fluid fluid : fluids) {
            if (!fluid.optional()) {
                return true;
            }
        }
        return false;
    }

    public boolean fluidOnly() {
        return inputs.isEmpty() && !fluids.isEmpty();
    }

    public String machineNamespace() {
        return typeId.getNamespace();
    }

    public String recipeNamespace() {
        int separator = id.indexOf(':');
        return separator > 0 ? id.substring(0, separator) : "minecraft";
    }

    public record Input(List<StackKey> options, int count, boolean consumed) {
        public Input(List<StackKey> options, int count) {
            this(options, count, true);
        }
    }

    public record Fluid(String id, int amount, boolean optional, List<String> alts) {
        public Fluid {
            if (alts == null || alts.isEmpty()) {
                alts = List.of();
            } else {
                List<String> normalized = new ArrayList<>(alts.size());
                for (String alternative : alts) {
                    if (alternative != null && !alternative.equals(id) && !normalized.contains(alternative)) {
                        normalized.add(alternative);
                    }
                }
                alts = List.copyOf(normalized);
            }
        }

        public Fluid(String id, int amount, boolean optional) {
            this(id, amount, optional, List.of());
        }

        public Fluid(String id, int amount) {
            this(id, amount, false, List.of());
        }

        public Fluid withAmount(int replacementAmount) {
            return new Fluid(id, replacementAmount, optional, alts);
        }

        public String pick(ToLongFunction<String> availableAmount) {
            if (alts.isEmpty()) {
                return id;
            }
            long bestAmount = availableAmount.applyAsLong(id);
            String best = id;
            for (String alternative : alts) {
                long candidateAmount = availableAmount.applyAsLong(alternative);
                if (candidateAmount > bestAmount) {
                    bestAmount = candidateAmount;
                    best = alternative;
                }
            }
            return best;
        }

        public List<String> all() {
            if (alts.isEmpty()) {
                return List.of(id);
            }
            List<String> all = new ArrayList<>(alts.size() + 1);
            all.add(id);
            all.addAll(alts);
            return all;
        }
    }
}
