package de.scc.calculator.calc;

import de.scc.calculator.SccConfig;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

import java.util.List;

/** Decides whether an ingredient is consumed, using the configured keep-item patterns. */
public final class Consumption {
    private Consumption() {
    }

    public static boolean isConsumed(Item item) {
        if (item == null) {
            return false;
        }
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        if (id == null) {
            return true;
        }
        List<String> kept = SccConfig.get().nonConsumedItems;
        if (kept == null) {
            return true;
        }
        String value = id.toString();
        for (String pattern : kept) {
            if (matches(pattern, value)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matches(String pattern, String value) {
        if (pattern == null || pattern.isBlank()) {
            return false;
        }
        int patternIndex = 0;
        int valueIndex = 0;
        int star = -1;
        int resume = -1;
        while (valueIndex < value.length()) {
            if (patternIndex < pattern.length()
                    && (pattern.charAt(patternIndex) == '?'
                    || Character.toLowerCase(pattern.charAt(patternIndex)) == Character.toLowerCase(value.charAt(valueIndex)))) {
                patternIndex++;
                valueIndex++;
            } else if (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
                star = patternIndex++;
                resume = valueIndex;
            } else if (star >= 0) {
                patternIndex = star + 1;
                valueIndex = ++resume;
            } else {
                return false;
            }
        }
        while (patternIndex < pattern.length() && pattern.charAt(patternIndex) == '*') {
            patternIndex++;
        }
        return patternIndex == pattern.length();
    }
}
