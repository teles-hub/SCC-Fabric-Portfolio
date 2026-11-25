package de.scc.calculator.registry;

import de.scc.calculator.platform.registry.PlatformRegistry;
import de.scc.calculator.platform.registry.SccHolder;
import de.scc.calculator.recipe.FacadeRecipe;
import de.scc.calculator.recipe.StorageUpgradeRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.SimpleCraftingRecipeSerializer;

public final class SccRecipes {
    public static final SccHolder<RecipeSerializer<StorageUpgradeRecipe>> STORAGE_UPGRADE_SERIALIZER =
            PlatformRegistry.registerRecipeSerializer("storage_upgrade", new StorageUpgradeRecipe.Serializer());
    public static final SccHolder<RecipeSerializer<FacadeRecipe>> FACADE_SERIALIZER =
            PlatformRegistry.registerRecipeSerializer("facade", new SimpleCraftingRecipeSerializer<>(FacadeRecipe::new));

    private SccRecipes() { }
    public static void init() { }
}
