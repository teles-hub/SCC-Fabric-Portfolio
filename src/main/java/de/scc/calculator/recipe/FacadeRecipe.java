package de.scc.calculator.recipe;

import de.scc.calculator.network.SccFacadeItem;
import de.scc.calculator.network.SccFacadeShell;
import de.scc.calculator.registry.SccItems;
import de.scc.calculator.registry.SccRecipes;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CustomRecipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

/** Fabric port of the original SCC dynamic facade recipe. */
public final class FacadeRecipe extends CustomRecipe {
    public FacadeRecipe(CraftingBookCategory category) { super(category); }

    @Override
    public boolean matches(CraftingInput input, Level level) {
        return !assemble(input, null).isEmpty();
    }

    @Override
    public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack crossed = fromCross(input);
        return crossed.isEmpty() ? fromStamp(input) : crossed;
    }

    private static ItemStack fromCross(CraftingInput input) {
        if (input.width() != 3 || input.height() != 3) return ItemStack.EMPTY;
        for (int slot = 0; slot < 9; slot++) {
            ItemStack stack = input.getItem(slot);
            boolean corner = slot == 0 || slot == 2 || slot == 6 || slot == 8;
            boolean edge = slot == 1 || slot == 3 || slot == 5 || slot == 7;
            if (corner && !stack.isEmpty()) return ItemStack.EMPTY;
            if (edge && !stack.is(Items.IRON_NUGGET)) return ItemStack.EMPTY;
        }
        ItemStack center = input.getItem(4);
        if (center.isEmpty()) return new ItemStack(SccItems.FACADE.get(), 4);
        Block block = blockFrom(center);
        if (block == null) return ItemStack.EMPTY;
        ItemStack result = SccFacadeItem.of(block);
        result.setCount(4);
        return result;
    }

    private static ItemStack fromStamp(CraftingInput input) {
        ItemStack facade = ItemStack.EMPTY;
        ItemStack blockStack = ItemStack.EMPTY;
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack stack = input.getItem(slot);
            if (stack.isEmpty()) continue;
            if (stack.is(SccItems.FACADE.get())) {
                if (!facade.isEmpty()) return ItemStack.EMPTY;
                facade = stack;
            } else {
                if (!blockStack.isEmpty()) return ItemStack.EMPTY;
                blockStack = stack;
            }
        }
        if (facade.isEmpty() || blockStack.isEmpty()) return ItemStack.EMPTY;
        Block block = blockFrom(blockStack);
        return block == null ? ItemStack.EMPTY : SccFacadeItem.of(block);
    }

    private static Block blockFrom(ItemStack stack) {
        if (stack.isEmpty() || !(stack.getItem() instanceof BlockItem blockItem)) return null;
        Block block = blockItem.getBlock();
        return SccFacadeShell.canCover(block) ? block : null;
    }

    @Override public boolean canCraftInDimensions(int width, int height) { return width * height >= 2; }
    @Override public RecipeSerializer<?> getSerializer() { return SccRecipes.FACADE_SERIALIZER.get(); }
}
