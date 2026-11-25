package de.scc.calculator.recipe;

import com.mojang.serialization.MapCodec;
import de.scc.calculator.network.SccStorageBlock;
import de.scc.calculator.registry.SccRecipes;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.crafting.CraftingBookCategory;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.Level;

/** Fabric port of the original SCC StorageUpgradeRecipe: shaped crafting plus block-entity data carry-over. */
public final class StorageUpgradeRecipe implements CraftingRecipe {
    private final ShapedRecipe shaped;
    public StorageUpgradeRecipe(ShapedRecipe shaped) { this.shaped = shaped; }
    ShapedRecipe shaped() { return shaped; }

    @Override public boolean matches(CraftingInput input, Level level) { return shaped.matches(input, level); }

    @Override public ItemStack assemble(CraftingInput input, HolderLookup.Provider registries) {
        ItemStack result = shaped.assemble(input, registries);
        if (result.isEmpty()) return result;
        for (int slot = 0; slot < input.size(); slot++) {
            ItemStack ingredient = input.getItem(slot);
            if (ingredient.isEmpty() || !(ingredient.getItem() instanceof BlockItem blockItem)
                    || !(blockItem.getBlock() instanceof SccStorageBlock)) continue;
            CustomData blockEntityData = ingredient.get(DataComponents.BLOCK_ENTITY_DATA);
            if (blockEntityData != null) result.set(DataComponents.BLOCK_ENTITY_DATA, blockEntityData);
            break;
        }
        return result;
    }

    @Override public boolean canCraftInDimensions(int width, int height) { return shaped.canCraftInDimensions(width, height); }
    @Override public ItemStack getResultItem(HolderLookup.Provider registries) { return shaped.getResultItem(registries); }
    @Override public NonNullList<Ingredient> getIngredients() { return shaped.getIngredients(); }
    @Override public CraftingBookCategory category() { return shaped.category(); }
    @Override public RecipeSerializer<?> getSerializer() { return SccRecipes.STORAGE_UPGRADE_SERIALIZER.get(); }
    @Override public RecipeType<?> getType() { return RecipeType.CRAFTING; }

    public static final class Serializer implements RecipeSerializer<StorageUpgradeRecipe> {
        public static final MapCodec<StorageUpgradeRecipe> CODEC = ShapedRecipe.Serializer.CODEC
                .xmap(StorageUpgradeRecipe::new, StorageUpgradeRecipe::shaped);
        public static final StreamCodec<RegistryFriendlyByteBuf, StorageUpgradeRecipe> STREAM_CODEC =
                ShapedRecipe.Serializer.STREAM_CODEC.map(StorageUpgradeRecipe::new, StorageUpgradeRecipe::shaped);
        @Override public MapCodec<StorageUpgradeRecipe> codec() { return CODEC; }
        @Override public StreamCodec<RegistryFriendlyByteBuf, StorageUpgradeRecipe> streamCodec() { return STREAM_CODEC; }
    }
}
