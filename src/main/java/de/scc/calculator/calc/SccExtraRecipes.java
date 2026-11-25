package de.scc.calculator.calc;

import de.scc.calculator.SccLog;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.alchemy.PotionContents;
import net.minecraft.world.item.alchemy.Potions;
import net.minecraft.world.level.block.ComposterBlock;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Fabric implementation of SCC's synthetic vanilla-only recipes. */
public final class SccExtraRecipes {
    public static final ResourceLocation DIRECT = id("network");
    public static final ResourceLocation COMPOSTING = id("composting");
    public static final int BOTTLE_MB = 250;
    private static final int BUCKET_MB = 1000;

    private SccExtraRecipes() { }

    public static boolean isDirect(ResourceLocation type) {
        return DIRECT.equals(type);
    }

    public static boolean isComposting(ResourceLocation type) {
        return COMPOSTING.equals(type);
    }

    public static StackKey waterBottle() {
        try {
            ItemStack bottle = new ItemStack(Items.POTION);
            bottle.set(net.minecraft.core.component.DataComponents.POTION_CONTENTS, new PotionContents(Potions.WATER));
            return StackKey.of(bottle);
        } catch (Throwable error) {
            SccLog.caught("analysis", "Could not create water bottle stack key", error);
            return null;
        }
    }

    public static void add(RecipeBook book) {
        if (book == null) return;
        addBottle(book);
        addVanillaBuckets(book);
        addComposting(book);
    }

    private static void addBottle(RecipeBook book) {
        StackKey waterBottle = waterBottle();
        StackKey glassBottle = StackKey.of(Items.GLASS_BOTTLE);
        if (waterBottle == null || glassBottle == null) return;
        book.add(new SccRecipe("scc:fill_bottle", DIRECT, "SCC", waterBottle, 1,
                List.of(new SccRecipe.Input(List.of(glassBottle), 1, true)),
                List.of(new SccRecipe.Fluid("minecraft:water", BOTTLE_MB))));
    }

    /**
     * Vanilla's water/lava buckets are the only vanilla bucket items with a
     * Fluid API mapping. Modded item containers are intentionally left to
     * Fabric Transfer API integrations rather than guessed from an item name.
     */
    private static void addVanillaBuckets(RecipeBook book) {
        addBucket(book, Fluids.WATER, Items.WATER_BUCKET);
        addBucket(book, Fluids.LAVA, Items.LAVA_BUCKET);
    }

    private static void addBucket(RecipeBook book, Fluid fluid, Item filledBucket) {
        ResourceLocation fluidId = BuiltInRegistries.FLUID.getKey(fluid);
        StackKey output = StackKey.of(filledBucket);
        StackKey emptyBucket = StackKey.of(Items.BUCKET);
        if (fluidId == null || output == null || emptyBucket == null) return;
        book.add(new SccRecipe("scc:bucket/" + fluidId.getNamespace() + "/" + fluidId.getPath(),
                DIRECT, "SCC", output, 1,
                List.of(new SccRecipe.Input(List.of(emptyBucket), 1, true)),
                List.of(new SccRecipe.Fluid(fluidId.toString(), BUCKET_MB))));
    }

    private static void addComposting(RecipeBook book) {
        StackKey boneMeal = StackKey.of(Items.BONE_MEAL);
        if (boneMeal == null) return;

        Map<Integer, List<StackKey>> byChance = new TreeMap<>();
        for (Item item : BuiltInRegistries.ITEM) {
            if (item == Items.AIR || item == Items.BONE_MEAL) continue;
            float compostChance;
            try {
                compostChance = ComposterBlock.COMPOSTABLES.getFloat(item);
            } catch (Throwable ignored) {
                continue;
            }
            if (!(compostChance > 0.0f)) continue;
            StackKey input = StackKey.of(item);
            if (input == null) continue;
            int chancePercent = Math.max(1, Math.min(100, Math.round(compostChance * 100.0f)));
            byChance.computeIfAbsent(chancePercent, ignored -> new ArrayList<>()).add(input);
        }

        String typeName = Component.translatable("scc.type.composting").getString();
        int added = 0;
        for (Map.Entry<Integer, List<StackKey>> entry : byChance.entrySet()) {
            List<StackKey> options = entry.getValue();
            options.sort(Comparator.comparing(StackKey::encode));
            double layerChance = entry.getKey() / 100.0d;
            float effectiveChance = (float) (layerChance / (layerChance + 6.0d));
            SccRecipe recipe = new SccRecipe("scc:composting/" + entry.getKey(), COMPOSTING, typeName,
                    boneMeal, 1,
                    List.of(new SccRecipe.Input(List.copyOf(options), 1, true)), List.of(), effectiveChance);
            book.add(recipe);
            added++;
        }
        if (added > 0) book.addCatalyst(COMPOSTING.toString(), "minecraft:composter");
        SccLog.info("analysis", "Added " + added + " synthetic composting recipe groups");
    }

    private static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath("scc", path);
    }
}
