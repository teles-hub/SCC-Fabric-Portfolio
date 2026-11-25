package de.scc.calculator.network;

import de.scc.calculator.registry.SccDataComponents;
import de.scc.calculator.registry.SccItems;
import de.scc.calculator.calc.StackKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;

/**
 * Synthetic, non-stackable item used to let the recipe graph represent fluids
 * as graph nodes while retaining the original SCC encoding (fluid:<id>).
 * Mekanism chemical keys are deliberately unavailable unless that integration
 * is ported and present.
 */
public class SubstanceItem extends Item {
    public static final String FLUID = "fluid:";
    public static final String CHEMICAL = "chemical:";

    public SubstanceItem(Properties properties) {
        super(properties);
    }

    public static StackKey fluidKey(String fluidId) {
        Fluid fluid = FluidBuckets.parse(fluidId);
        return fluid == null ? null : StackKey.of(stack(FLUID + fluidId));
    }

    public static StackKey chemicalKey(String chemicalId) {
        return null;
    }

    private static ItemStack stack(String value) {
        ItemStack stack = new ItemStack(SccItems.SUBSTANCE.get());
        stack.set(SccDataComponents.SUBSTANCE_ID.get(), value);
        return stack;
    }

    public static boolean is(StackKey key) {
        return key != null && key.item() instanceof SubstanceItem;
    }

    public static String valueOf(StackKey key) {
        return is(key) ? valueOf(key.display()) : null;
    }

    public static String valueOf(ItemStack stack) {
        if (stack == null || stack.isEmpty() || !(stack.getItem() instanceof SubstanceItem)) return null;
        return stack.get(SccDataComponents.SUBSTANCE_ID.get());
    }

    public static Fluid fluidOf(StackKey key) {
        String value = valueOf(key);
        if (value == null || !value.startsWith(FLUID)) return null;
        return FluidBuckets.parse(value.substring(FLUID.length()));
    }

    public static Fluid fluidOf(ItemStack stack) {
        String value = valueOf(stack);
        if (value == null || !value.startsWith(FLUID)) return null;
        return FluidBuckets.parse(value.substring(FLUID.length()));
    }

    public static String chemicalOf(StackKey key) {
        String value = valueOf(key);
        return value != null && value.startsWith(CHEMICAL) ? value.substring(CHEMICAL.length()) : null;
    }

    public static String chemicalOf(ItemStack stack) {
        String value = valueOf(stack);
        return value != null && value.startsWith(CHEMICAL) ? value.substring(CHEMICAL.length()) : null;
    }
}
