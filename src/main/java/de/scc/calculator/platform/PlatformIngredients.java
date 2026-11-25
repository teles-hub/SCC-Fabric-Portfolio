package de.scc.calculator.platform;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.material.Fluid;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Substitui net.neoforged.neoforge.common.Tags. CORRECAO: todas as tags "c:*" sao criadas por TagKey.create,
 * sem depender de ConventionalItemTags/ConventionalFluidTags (CHESTS nao existe na Fabric API v2 e os nomes
 * mudam entre versoes). Tags sao apenas ids, entao isto compila em qualquer versao.
 */
public final class PlatformIngredients {

    public static final TagKey<Item> ORES = commonItemTag("ores");
    public static final TagKey<Item> INGOTS = commonItemTag("ingots");
    public static final TagKey<Item> DUSTS = commonItemTag("dusts");
    public static final TagKey<Item> GEMS = commonItemTag("gems");
    public static final TagKey<Item> NUGGETS = commonItemTag("nuggets");
    public static final TagKey<Item> STORAGE_BLOCKS = commonItemTag("storage_blocks");
    public static final TagKey<Item> CHESTS = commonItemTag("chests"); // so existe se alguem popular a tag

    public static final TagKey<Fluid> WATER = commonFluidTag("water");
    public static final TagKey<Fluid> LAVA = commonFluidTag("lava");

    private PlatformIngredients() {
    }

    public static TagKey<Item> itemTag(String namespace, String path) {
        return TagKey.create(Registries.ITEM, ResourceLocation.fromNamespaceAndPath(namespace, path));
    }

    public static TagKey<Item> commonItemTag(String path) {
        return itemTag("c", path);
    }

    public static TagKey<Fluid> commonFluidTag(String path) {
        return TagKey.create(Registries.FLUID, ResourceLocation.fromNamespaceAndPath("c", path));
    }

    public static boolean shareMaterialTag(ItemStack a, ItemStack b) {
        if (a.isEmpty() || b.isEmpty()) return false;
        if (ItemStack.isSameItem(a, b)) return true;
        return a.getTags().anyMatch(tag -> {
            String path = tag.location().getPath();
            boolean family = path.startsWith("ingots/") || path.startsWith("ores/") || path.startsWith("dusts/")
                    || path.startsWith("gems/") || path.startsWith("nuggets/") || path.startsWith("plates/")
                    || path.startsWith("gears/");
            return family && b.is(tag);
        });
    }

    public static List<ItemStack> getMatchingStacksSafe(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) return Collections.emptyList();
        try {
            ItemStack[] items = ingredient.getItems();
            return items == null || items.length == 0 ? Collections.emptyList() : Arrays.asList(items);
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
