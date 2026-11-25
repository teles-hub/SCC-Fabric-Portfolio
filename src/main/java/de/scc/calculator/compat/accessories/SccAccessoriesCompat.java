package de.scc.calculator.compat.accessories;

import io.wispforest.accessories.api.AccessoriesCapability;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;

import java.util.function.Predicate;

/**
 * Optional bridge to Accessories, which is present in the target Cobbleverse pack.
 *
 * <p>This class is only loaded after the caller verifies that the Accessories mod
 * is installed. The API dependency is compile-only and is never bundled by SCC.</p>
 */
public final class SccAccessoriesCompat {
    private SccAccessoriesCompat() { }

    public static ItemStack findEquipped(Player player, Predicate<ItemStack> predicate) {
        if (player == null || predicate == null) return ItemStack.EMPTY;
        return AccessoriesCapability.getOptionally(player)
                .map(capability -> capability.getAllEquipped().stream()
                        .map(entry -> entry.stack())
                        .filter(stack -> stack != null && !stack.isEmpty() && predicate.test(stack))
                        .findFirst()
                        .orElse(ItemStack.EMPTY))
                .orElse(ItemStack.EMPTY);
    }
}
