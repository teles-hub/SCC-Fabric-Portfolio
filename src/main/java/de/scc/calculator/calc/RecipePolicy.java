package de.scc.calculator.calc;

import net.minecraft.world.level.Level;
import de.scc.calculator.SccConfig;

/**
 * Fabric-side policy seam for recipe filtering and optional integrations.
 *
 * <p>The original SCC delegates these decisions to SccConfig and optional
 * integrations (Create / Custom Machinery). Keeping the seam here avoids
 * hard-linking the calculator core to NeoForge or optional mods.</p>
 */
public interface RecipePolicy {
    RecipePolicy ALLOW_ALL = new RecipePolicy() { };

    static RecipePolicy configured() {
        return new RecipePolicy() {
            private SccConfig config() { return SccConfig.get(); }
            @Override public boolean isRecipeBlocked(String id) { return config().isBlockedRecipe(id); }
            @Override public boolean isRecipeTypeBlocked(String id) { return config().isBlockedRecipeType(id); }
            @Override public boolean hasBlockedItems() { return config().hasBlockedItems(); }
            @Override public boolean isItemBlocked(String encoded) { return config().isBlockedItem(encoded); }
        };
    }

    default boolean isRecipeBlocked(String recipeId) {
        return false;
    }

    default boolean isRecipeTypeBlocked(String recipeTypeId) {
        return false;
    }

    default boolean hasBlockedItems() {
        return false;
    }

    default boolean isItemBlocked(String encodedStackKey) {
        return false;
    }

    default boolean isShadowed(Level world, SccRecipe recipe) {
        return false;
    }

    default String normalizeCatalystId(String catalystId) {
        return catalystId;
    }
}
