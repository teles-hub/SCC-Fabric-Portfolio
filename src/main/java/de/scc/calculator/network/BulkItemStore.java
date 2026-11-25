package de.scc.calculator.network;

import de.scc.calculator.calc.StackKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Bulk-count operations used by SCC's logistics network and crafting planner. */
public interface BulkItemStore {
    Iterable<ItemStack> entries();
    ItemStack takeMatching(Predicate<ItemStack> predicate, int amount, boolean simulate);
    boolean unlimited();
    long stored();
    long capacity();

    default ItemStack take(StackKey key, ItemStack exemplar, int amount, boolean simulate) {
        if (key == null || amount <= 0) return ItemStack.EMPTY;
        return takeMatching(stack -> key.matches(stack)
                && (exemplar == null || exemplar.isEmpty()
                || ItemStack.isSameItemSameComponents(exemplar, stack)), amount, simulate);
    }

    default long count(StackKey key) {
        if (key == null) return 0L;
        long total = 0L;
        for (ItemStack stack : entries()) if (key.matches(stack)) total += stack.getCount();
        return total;
    }

    default boolean has(StackKey key) {
        if (key == null) return false;
        for (ItemStack stack : entries()) if (stack.getCount() > 0 && key.matches(stack)) return true;
        return false;
    }

    default long countItem(Item item) {
        long total = 0L;
        for (ItemStack stack : entries()) if (!stack.isEmpty() && stack.getItem() == item) total += stack.getCount();
        return total;
    }

    default StackKey keyOf(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : StackKey.of(stack);
    }

    default void keyed(BiConsumer<ItemStack, StackKey> consumer) {
        for (ItemStack stack : entries()) if (!stack.isEmpty()) consumer.accept(stack, keyOf(stack));
    }

    default boolean indexed() { return false; }
}
