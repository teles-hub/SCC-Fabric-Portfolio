package de.scc.calculator.calc;

import de.scc.calculator.api.SccRecipeProviders;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Common-side incremental adapter for the recipes belonging to a specific
 * Level. It can be used by a client analysis preview and by server-authoritative
 * network crafting requests without loading net.minecraft.client classes.
 */
public final class LevelRecipeSource implements RecipeSource {
    private final net.minecraft.world.level.Level level;

    public LevelRecipeSource(net.minecraft.world.level.Level level) {
        this.level = level;
    }
    private List<RecipeHolder<?>> recipes = List.of();
    private HolderLookup.Provider registries;
    private int index;
    private boolean catalystsDone;
    private boolean snapshotContributionsDone;

    @Override
    public String name() {
        return "Minecraft";
    }

    @Override
    public void begin() {
        if (level == null) {
            recipes = List.of();
            registries = null;
            index = 0;
            catalystsDone = false;
            snapshotContributionsDone = false;
            return;
        }
        registries = level.registryAccess();
        recipes = new ArrayList<>(level.getRecipeManager().getRecipes());
        recipes.sort((left, right) -> left.id().toString().compareTo(right.id().toString()));
        index = 0;
        catalystsDone = false;
        snapshotContributionsDone = false;
    }

    @Override
    public int total() {
        return recipes.size();
    }

    @Override
    public int done() {
        return index;
    }

    @Override
    public boolean scanChunk(RecipeBook book, int budget) {
        if (book == null) throw new IllegalArgumentException("RecipeBook cannot be null");
        book.setSource(name());
        if (level != null) book.setWorld(level);
        if (!catalystsDone) {
            catalystsDone = true;
            addVanillaCatalysts(book);
        }
        int end = Math.min(recipes.size(), index + budget);
        while (index < end) {
            try {
                convert(book, recipes.get(index));
            } catch (Throwable ignored) {
                // Keep the scan incremental: one third-party recipe must not abort the whole index.
            }
            index++;
        }

        // Synthetic SCC recipes and registered third-party providers are part
        // of the same immutable analysis snapshot. Add them once, only after
        // vanilla discovery is complete, so an incremental scan cannot duplicate
        // provider output on every client tick.
        if (index >= recipes.size() && !snapshotContributionsDone) {
            snapshotContributionsDone = true;
            try {
                SccExtraRecipes.add(book);
            } catch (Throwable ignored) {
                // Synthetic integration is isolated from the vanilla recipe index.
            }
            SccRecipeProviders.addAll(book);
        }
        return index < recipes.size();
    }

    private void convert(RecipeBook book, RecipeHolder<?> holder) {
        Recipe<?> recipe = holder.value();
        if (!usable(recipe)) {
            return;
        }

        ItemStack result = RecipeUtil.result(holder, registries);
        if (result == null || result.isEmpty()) {
            return;
        }

        List<RecipeUtil.SizedEntry> sizedEntries = RecipeUtil.ingredients(recipe);
        if (sizedEntries.isEmpty()) {
            return;
        }

        Set<net.minecraft.world.item.Item> keptBy = RecipeUtil.keptBy(recipe);
        List<SccRecipe.Input> inputs = new ArrayList<>();
        for (RecipeUtil.SizedEntry entry : sizedEntries) {
            List<StackKey> options = RecipeUtil.options(entry.ingredient());
            if (options.isEmpty()) {
                continue;
            }
            inputs.add(RecipeUtil.input(options, entry.count(), keptBy));
        }
        if (inputs.isEmpty()) {
            return;
        }

        ResourceLocation typeId = typeIdOf(recipe.getType(), holder);
        SccRecipe converted = new SccRecipe(
                holder.id().toString(),
                typeId,
                typeNameOf(recipe.getType()),
                StackKey.of(result),
                Math.max(1, result.getCount()),
                RecipeUtil.mergeInputs(inputs)
        );
        book.add(converted);
    }

    /** Mirrors the original SCC special-recipe filter without depending on CraftOrders. */
    private static boolean usable(Recipe<?> recipe) {
        if (!recipe.isSpecial()) {
            return true;
        }
        if (recipe instanceof ShapedRecipe || recipe instanceof ShapelessRecipe) {
            return true;
        }
        if (isCobblemonCampfirePotRecipe(recipe)) {
            return true;
        }
        return RecipeUtil.shapedPattern(recipe) != null;
    }

    private static boolean isCobblemonCampfirePotRecipe(Recipe<?> recipe) {
        ResourceLocation id = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        return id != null && "cobblemon".equals(id.getNamespace())
                && ("campfire_pot".equals(id.getPath())
                || "cooking_pot".equals(id.getPath())
                || "cooking_pot_shapeless".equals(id.getPath()));
    }

    public static void addVanillaCatalysts(RecipeBook book) {
        book.addCatalyst("minecraft:crafting", "minecraft:crafting_table");
        book.addCatalyst("minecraft:smelting", "minecraft:furnace");
        book.addCatalyst("minecraft:blasting", "minecraft:blast_furnace");
        book.addCatalyst("minecraft:smoking", "minecraft:smoker");
        book.addCatalyst("minecraft:campfire_cooking", "minecraft:campfire");
        book.addCatalyst("minecraft:stonecutting", "minecraft:stonecutter");
        book.addCatalyst("minecraft:smithing", "minecraft:smithing_table");
        book.addCatalyst("minecraft:smithing_transform", "minecraft:smithing_table");
        book.addCatalyst("minecraft:smithing_trim", "minecraft:smithing_table");

        // Cobblemon 1.8.x registers its cooking station as campfire_pot. Keep the
        // legacy cooking_pot category too, since datapacks/addons may expose it.
        // Registry presence is the optional-mod guard: no hard link to Cobblemon
        // or FabricLoader is needed in the recipe scanner's test/runtime surface.
        String[] colours = {"red", "yellow", "green", "blue", "pink", "black", "white"};
        for (String colour : colours) {
            ResourceLocation pot = ResourceLocation.parse("cobblemon:campfire_pot_" + colour);
            if (!BuiltInRegistries.ITEM.containsKey(pot)) continue;
            book.addCatalyst("cobblemon:campfire_pot", pot.toString());
            book.addCatalyst("cobblemon:cooking_pot", pot.toString());
        }
    }

    private static ResourceLocation typeIdOf(RecipeType<?> type, RecipeHolder<?> holder) {
        ResourceLocation id = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        return id == null ? holder.id() : id;
    }

    private static String typeNameOf(RecipeType<?> type) {
        if (type == RecipeType.CRAFTING) {
            return Component.translatable("scc.type.crafting").getString();
        }
        if (type == RecipeType.SMELTING) {
            return Component.translatable("scc.type.smelting").getString();
        }
        if (type == RecipeType.BLASTING) {
            return Component.translatable("scc.type.blasting").getString();
        }
        if (type == RecipeType.SMOKING) {
            return Component.translatable("scc.type.smoking").getString();
        }
        if (type == RecipeType.CAMPFIRE_COOKING) {
            return Component.translatable("scc.type.campfire").getString();
        }
        if (type == RecipeType.STONECUTTING) {
            return Component.translatable("scc.type.stonecutting").getString();
        }
        if (type == RecipeType.SMITHING) {
            return Component.translatable("scc.type.smithing").getString();
        }
        ResourceLocation id = BuiltInRegistries.RECIPE_TYPE.getKey(type);
        return id == null ? "?" : id.getPath();
    }
}
