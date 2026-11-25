package de.scc.calculator.network;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Ingredient contract for direct, in-place storage tier upgrades.
 *
 * <p>The ingredient pairs intentionally mirror the existing vanilla tier
 * progression recipes. Each step consumes four of each ingredient; the
 * previous storage block is the target, not an additional inventory cost.</p>
 */
public final class SccStorageUpgrade {
    public static final int INGREDIENT_COUNT = 4;

    private SccStorageUpgrade() {
    }

    public static Map<Item, Integer> ingredientsFor(StorageTier target) {
        if (target == null) return Map.of();

        Item first;
        Item second;
        switch (target) {
            case RED_DWARF -> {
                first = Items.IRON_INGOT;
                second = Items.REDSTONE;
            }
            case YELLOW_STAR -> {
                first = Items.GOLD_INGOT;
                second = Items.AMETHYST_SHARD;
            }
            case BLUE_GIANT -> {
                first = Items.DIAMOND;
                second = Items.LAPIS_LAZULI;
            }
            case NEUTRON_STAR -> {
                first = Items.EMERALD;
                second = Items.QUARTZ;
            }
            case SINGULARITY -> {
                first = Items.NETHERITE_INGOT;
                second = Items.ENDER_PEARL;
            }
            case BASIC -> {
                return Map.of();
            }
            default -> throw new IllegalStateException("Unhandled SCC storage tier: " + target);
        }

        Map<Item, Integer> ingredients = new LinkedHashMap<>();
        ingredients.put(first, INGREDIENT_COUNT);
        ingredients.put(second, INGREDIENT_COUNT);
        return Map.copyOf(ingredients);
    }

    public static boolean isUpgrade(StorageTier current, StorageTier target) {
        return current != null && target != null && current.next() == target;
    }
}
