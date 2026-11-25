package de.scc.calculator.calc;

import net.minecraft.resources.ResourceLocation;

import java.util.Set;

/** Recipe-index change summary reconstructed from SCC 1.6.5 bytecode. */
public record AnalysisDelta(
        Set<String> newIds,
        Set<String> goneIds,
        Set<String> changedNs,
        Set<String> fullTypes
) {
    public static final Set<String> VANILLA = Set.of(
            "minecraft:crafting",
            "minecraft:smelting",
            "minecraft:blasting",
            "minecraft:smoking",
            "minecraft:campfire_cooking",
            "minecraft:stonecutting",
            "minecraft:smithing"
    );

    public AnalysisDelta(Set<String> newIds, Set<String> goneIds, Set<String> changedNs) {
        this(newIds, goneIds, changedNs, Set.of());
    }

    private boolean refreshVanilla(String recipeType) {
        return !changedNs.isEmpty() && VANILLA.contains(recipeType);
    }

    public boolean walkNeeded() {
        return !newIds.isEmpty() || !changedNs.isEmpty() || !fullTypes.isEmpty();
    }

    public boolean fullCategory(String recipeType) {
        return changedNs.contains(namespace(recipeType))
                || refreshVanilla(recipeType)
                || fullTypes.contains(recipeType);
    }

    public boolean wants(String recipeType, String recipeId) {
        if (fullCategory(recipeType)) {
            return true;
        }
        if (recipeId == null) {
            return false;
        }
        return newIds.contains(recipeId) || changedNs.contains(namespace(recipeId));
    }

    public boolean keeps(SccRecipe recipe) {
        String baseId = base(recipe.id());
        if (goneIds.contains(baseId) || newIds.contains(baseId)) {
            return false;
        }

        String recipeType = recipe.typeId().toString();
        if (refreshVanilla(recipeType) || fullTypes.contains(recipeType)) {
            return false;
        }

        return !changedNs.contains(namespace(baseId))
                && !changedNs.contains(recipe.typeId().getNamespace());
    }

    public String describe() {
        String full = fullTypes.isEmpty() ? "" : " full=" + fullTypes;
        return "recipes +" + newIds.size() + " -" + goneIds.size()
                + " namespaces=" + changedNs + full;
    }

    static String base(String id) {
        int fragment = id.indexOf('#');
        return fragment < 0 ? id : id.substring(0, fragment);
    }

    private static String namespace(String id) {
        int separator = id.indexOf(':');
        return separator < 0 ? "minecraft" : id.substring(0, separator);
    }
}
