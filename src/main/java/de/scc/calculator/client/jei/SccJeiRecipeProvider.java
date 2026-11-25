package de.scc.calculator.client.jei;

import de.scc.calculator.api.SccRecipeProviders;
import de.scc.calculator.calc.RecipeUtil;
import de.scc.calculator.calc.SccRecipe;
import de.scc.calculator.calc.StackKey;
import mezz.jei.api.recipe.IRecipeCategoriesLookup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Reads custom JEI recipe categories and contributes only recipe objects that
 * implement Minecraft's Recipe contract. JEI-only synthetic recipe types are
 * deliberately skipped: their flattened ingredient view cannot preserve slot
 * alternatives, counts, tools, or returned containers with sufficient fidelity.
 */
final class SccJeiRecipeProvider implements SccRecipeProviders.Provider {
    private static final Set<String> BUILT_IN_NAMESPACES = Set.of("minecraft", "jei");
    private static final AtomicLong REVISION = new AtomicLong();

    private volatile IJeiRuntime runtime;

    void setRuntime(IJeiRuntime runtime) {
        this.runtime = runtime;
        REVISION.incrementAndGet();
    }

    @Override
    public boolean ready() {
        return runtime != null;
    }

    @Override
    public String stamp() {
        return "jei-runtime=" + (runtime != null) + ";revision=" + REVISION.get();
    }

    @Override
    public void addRecipes(SccRecipeProviders.Output output) {
        IJeiRuntime activeRuntime = runtime;
        var level = Minecraft.getInstance().level;
        if (activeRuntime == null || level == null) return;

        IRecipeManager manager = activeRuntime.getRecipeManager();
        HolderLookup.Provider registries = level.registryAccess();
        IRecipeCategoriesLookup categories = manager.createRecipeCategoryLookup().includeHidden();
        for (IRecipeCategory<?> category : categories.get().toList()) {
            ResourceLocation categoryId = category.getRecipeType().getUid();
            if (categoryId == null || BUILT_IN_NAMESPACES.contains(categoryId.getNamespace())) continue;
            collectCategory(manager, category, categoryId, registries, output);
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void collectCategory(IRecipeManager manager, IRecipeCategory<?> category,
                                        ResourceLocation categoryId, HolderLookup.Provider registries,
                                        SccRecipeProviders.Output output) {
        RecipeType type = category.getRecipeType();
        List<?> recipes;
        try {
            recipes = manager.createRecipeLookup(type).includeHidden().get().toList();
        } catch (Throwable ignored) {
            return;
        }

        int syntheticIndex = 0;
        for (Object value : recipes) {
            int index = syntheticIndex++;
            try {
                RecipeHolder<?> holder = value instanceof RecipeHolder<?> found ? found : null;
                Recipe<?> recipe = holder != null ? holder.value() : value instanceof Recipe<?> found ? found : null;
                if (recipe == null || !usable(recipe)) continue;

                ItemStack result;
                try {
                    result = holder != null
                            ? RecipeUtil.result(holder, registries)
                            : recipe.getResultItem(registries);
                } catch (Throwable ignored) {
                    continue;
                }
                if (result == null || result.isEmpty()) continue;

                List<RecipeUtil.SizedEntry> sizedEntries = RecipeUtil.ingredients(recipe);
                if (sizedEntries.isEmpty()) continue;

                Set<net.minecraft.world.item.Item> keptBy = RecipeUtil.keptBy(recipe);
                List<SccRecipe.Input> inputs = new ArrayList<>();
                for (RecipeUtil.SizedEntry entry : sizedEntries) {
                    List<StackKey> options = RecipeUtil.options(entry.ingredient());
                    if (!options.isEmpty()) {
                        inputs.add(RecipeUtil.input(options, entry.count(), keptBy));
                    }
                }
                if (inputs.isEmpty()) continue;

                ResourceLocation actualType = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
                if (actualType == null) actualType = categoryId;
                String id = holder != null ? holder.id().toString()
                        : categoryId + "/jei/" + index;
                SccRecipe converted = new SccRecipe(
                        id,
                        actualType,
                        category.getTitle().getString(),
                        StackKey.of(result),
                        Math.max(1, result.getCount()),
                        RecipeUtil.mergeInputs(inputs)
                );
                output.add(converted);
            } catch (Throwable ignored) {
                // A broken third-party recipe must not discard the remaining JEI categories.
            }
        }
    }

    private static boolean usable(Recipe<?> recipe) {
        if (!recipe.isSpecial()) return true;
        if (recipe instanceof net.minecraft.world.item.crafting.ShapedRecipe
                || recipe instanceof net.minecraft.world.item.crafting.ShapelessRecipe) return true;
        ResourceLocation type = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        if (type != null && "cobblemon".equals(type.getNamespace())
                && ("campfire_pot".equals(type.getPath()) || "cooking_pot".equals(type.getPath()))) return true;
        return RecipeUtil.shapedPattern(recipe) != null;
    }
}
