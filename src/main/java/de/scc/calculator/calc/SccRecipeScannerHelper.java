package de.scc.calculator.calc;

import de.scc.calculator.SuperCoolCalculator;
import de.scc.calculator.platform.PlatformIngredients;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.RecipeType;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Helper de indexação do RecipeManager para o motor de cálculo (calc/*).
 * Elimina dependências diretas de subclasses de receitas específicas do NeoForge.
 */
public final class SccRecipeScannerHelper {

    public record ScannedRecipe(
            ResourceLocation id,
            RecipeType<?> type,
            List<List<ItemStack>> inputSlots,
            ItemStack primaryOutput
    ) {
    }

    private SccRecipeScannerHelper() {
    }

    public static List<ScannedRecipe> scanAllRecipes(RecipeManager recipeManager, RegistryAccess registryAccess) {
        Collection<RecipeHolder<?>> allHolders = recipeManager.getRecipes();
        List<ScannedRecipe> scanned = new ArrayList<>(allHolders.size());

        for (RecipeHolder<?> holder : allHolders) {
            try {
                Recipe<?> recipe = holder.value();
                // Ignora receitas especiais dinâmicas (ex: tingimento de armadura, mapas, fogos)
                if (recipe.isSpecial()) {
                    continue;
                }

                ItemStack output = recipe.getResultItem(registryAccess);
                if (output == null || output.isEmpty()) {
                    continue;
                }

                NonNullList<Ingredient> ingredients = recipe.getIngredients();
                if (ingredients == null || ingredients.isEmpty()) {
                    continue;
                }

                List<List<ItemStack>> resolvedInputs = new ArrayList<>(ingredients.size());
                boolean hasValidInput = false;

                for (Ingredient ingredient : ingredients) {
                    if (ingredient.isEmpty()) {
                        continue;
                    }
                    List<ItemStack> variants = PlatformIngredients.getMatchingStacksSafe(ingredient);
                    if (!variants.isEmpty()) {
                        resolvedInputs.add(variants);
                        hasValidInput = true;
                    }
                }

                if (hasValidInput) {
                    scanned.add(new ScannedRecipe(
                            holder.id(),
                            recipe.getType(),
                            resolvedInputs,
                            output.copy()
                    ));
                }
            } catch (Exception ex) {
                SuperCoolCalculator.LOGGER.debug("Ignorando receita incompatível durante varredura: {}", holder.id(), ex);
            }
        }

        return scanned;
    }
}

