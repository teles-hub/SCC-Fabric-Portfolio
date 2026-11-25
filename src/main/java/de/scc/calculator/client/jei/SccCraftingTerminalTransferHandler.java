package de.scc.calculator.client.jei;

import de.scc.calculator.calc.StackKey;
import de.scc.calculator.client.PlatformNetworkClient;
import de.scc.calculator.network.SccCraftingTerminalMenu;
import de.scc.calculator.registry.SccMenus;
import mezz.jei.api.constants.RecipeTypes;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.transfer.IRecipeTransferError;
import mezz.jei.api.recipe.transfer.IRecipeTransferHandler;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JEI transfer bridge for the network-backed crafting terminal.
 * It sends only a recipe id and nine selected variants; the server reconstructs
 * and validates the grid against its own RecipeManager before touching storage.
 */
final class SccCraftingTerminalTransferHandler
        implements IRecipeTransferHandler<SccCraftingTerminalMenu, RecipeHolder<CraftingRecipe>> {

    @Override
    public Class<? extends SccCraftingTerminalMenu> getContainerClass() {
        return SccCraftingTerminalMenu.class;
    }

    @Override
    public Optional<MenuType<SccCraftingTerminalMenu>> getMenuType() {
        return Optional.of(SccMenus.CRAFTING_TERMINAL.get());
    }

    @Override
    public RecipeType<RecipeHolder<CraftingRecipe>> getRecipeType() {
        return RecipeTypes.CRAFTING;
    }

    @Nullable
    @Override
    public IRecipeTransferError transferRecipe(
            SccCraftingTerminalMenu menu,
            RecipeHolder<CraftingRecipe> recipe,
            IRecipeSlotsView recipeSlots,
            Player player,
            boolean maxTransfer,
            boolean doTransfer) {
        if (!doTransfer) return null;

        List<String> pattern = patternFor(recipe.value(), recipeSlots);
        if (pattern == null) return null;

        PlatformNetworkClient.transferCraftingRecipe(
                menu.pos(), menu.containerId, recipe.id().toString(), pattern);
        return null;
    }

    private static List<String> patternFor(CraftingRecipe recipe, IRecipeSlotsView recipeSlots) {
        List<mezz.jei.api.gui.ingredient.IRecipeSlotView> inputViews =
                recipeSlots.getSlotViews(RecipeIngredientRole.INPUT);
        List<String> result = new ArrayList<>(java.util.Collections.nCopies(9, ""));
        List<Ingredient> ingredients = recipe.getIngredients();

        if (recipe instanceof ShapedRecipe shaped) {
            int width = shaped.getWidth();
            int height = shaped.getHeight();
            if (width < 1 || height < 1 || width > 3 || height > 3
                    || ingredients.size() != width * height) return null;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    int ingredientIndex = y * width + x;
                    String encoded = displayedVariant(inputViews, ingredientIndex);
                    if (encoded == null) encoded = firstVariant(ingredients.get(ingredientIndex));
                    if (encoded == null) return null;
                    result.set(y * 3 + x, encoded);
                }
            }
        } else {
            if (ingredients.size() > 9) return null;
            for (int slot = 0; slot < ingredients.size(); slot++) {
                String encoded = displayedVariant(inputViews, slot);
                if (encoded == null) encoded = firstVariant(ingredients.get(slot));
                if (encoded == null) return null;
                result.set(slot, encoded);
            }
        }
        return List.copyOf(result);
    }

    /** Uses JEI's currently displayed choice when available. */
    @Nullable
    private static String displayedVariant(
            List<mezz.jei.api.gui.ingredient.IRecipeSlotView> inputViews, int index) {
        if (index < 0 || index >= inputViews.size()) return null;
        return inputViews.get(index).getDisplayedItemStack()
                .filter(stack -> !stack.isEmpty())
                .map(StackKey::of)
                .map(StackKey::encode)
                .orElse(null);
    }

    @Nullable
    private static String firstVariant(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) return "";
        for (ItemStack candidate : ingredient.getItems()) {
            if (candidate == null || candidate.isEmpty()) continue;
            StackKey key = StackKey.of(candidate);
            if (key != null) return key.encode();
        }
        return null;
    }
}
