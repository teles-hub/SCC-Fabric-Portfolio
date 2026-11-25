package de.scc.calculator.calc;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.AbstractCookingRecipe;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SmithingRecipe;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Recipe compatibility helpers reconstructed from the SCC 1.6.5 bytecode.
 *
 * <p>This is a Fabric-side source implementation. Optional recipe extensions are
 * discovered reflectively; the class has no NeoForge/FML compile-time dependency.
 * The limits and ordering rules intentionally follow the original implementation.</p>
 */
public final class RecipeUtil {
    public static final int MAX_OPTIONS = 512;

    private static final List<String> COLOURS = List.of(
            "light_gray", "light_blue", "white", "orange", "magenta", "yellow", "lime",
            "pink", "gray", "grey", "cyan", "purple", "blue", "brown", "green", "red", "black");
    private static final Set<String> DYE_WORDS = Set.of("stained", "dyed", "colored", "coloured");
    // Optional NeoForge type lookup: no linkage or compile-time dependency on that platform.
    private static final Class<?> SIZED_CLASS = tryClass("net.neoforged.neoforge.common.crafting.SizedIngredient");
    private static final Method SIZED_INGREDIENT = tryMethod(SIZED_CLASS, "ingredient");
    private static final Method SIZED_COUNT = tryMethod(SIZED_CLASS, "count");
    private static final Map<Class<?>, Optional<Field>> PATTERN_FIELDS = new ConcurrentHashMap<>();
    private static final Map<List<Item>, List<StackKey>> OPTIONS = new ConcurrentHashMap<>();
    private static final Map<Item, Boolean> REMAINS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> OVERRIDES_REMAINING = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> LOUD_ON_REMAINING = new ConcurrentHashMap<>();
    private static final Set<Class<?>> THROWS_ON_REMAINING = ConcurrentHashMap.newKeySet();
    // NeoForge exposes stack-aware remainders; probe reflectively without linking it on Fabric.
    private static final Method STACK_REMAINING_METHOD = findStackRemainingMethod();

    private RecipeUtil() { }

    public record SizedEntry(Ingredient ingredient, int count) {
        public SizedEntry {
            if (ingredient == null) throw new IllegalArgumentException("ingredient cannot be null");
            count = Math.max(1, count);
        }
    }

    public static ItemStack result(RecipeHolder<?> holder, HolderLookup.Provider registries) {
        if (holder == null) return ItemStack.EMPTY;
        Recipe<?> recipe = holder.value();
        if (recipe == null) return ItemStack.EMPTY;
        try {
            ItemStack result = recipe.getResultItem(registries);
            if (result != null && !result.isEmpty()) return result;
        } catch (Throwable ignored) {
            // Third-party recipe implementations sometimes throw from result lookup.
        }
        return reflectResult(recipe);
    }

    private static ItemStack reflectResult(Recipe<?> recipe) {
        ItemStack preferred = scanForStack(recipe, true);
        return preferred.isEmpty() ? scanForStack(recipe, false) : preferred;
    }

    private static ItemStack scanForStack(Object value, boolean resultFieldsOnly) {
        if (value == null) return ItemStack.EMPTY;
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            Field[] fields;
            try { fields = type.getDeclaredFields(); } catch (Throwable ignored) { continue; }
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                String name = field.getName().toLowerCase(java.util.Locale.ROOT);
                if (resultFieldsOnly && !name.contains("result") && !name.contains("output")) continue;
                if (!resultFieldsOnly && (name.contains("input") || name.contains("ingredient"))) continue;
                ItemStack stack = asStack(read(field, value));
                if (!stack.isEmpty()) return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    private static ItemStack asStack(Object value) {
        if (value instanceof ItemStack stack) return stack.isEmpty() ? ItemStack.EMPTY : stack;
        if (value instanceof Optional<?> optional) return optional.map(RecipeUtil::asStack).orElse(ItemStack.EMPTY);
        if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) {
                ItemStack stack = asStack(element);
                if (!stack.isEmpty()) return stack;
            }
        }
        return ItemStack.EMPTY;
    }

    public static ShapedRecipePattern shapedPattern(Object recipe) {
        if (recipe == null) return null;
        try {
            Optional<Field> cached = PATTERN_FIELDS.computeIfAbsent(recipe.getClass(), RecipeUtil::findPatternField);
            if (cached.isPresent()) {
                Object value = read(cached.get(), recipe);
                if (value instanceof ShapedRecipePattern pattern) return pattern;
            }
            for (Class<?> type = recipe.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
                Field[] fields;
                try { fields = type.getDeclaredFields(); } catch (Throwable ignored) { continue; }
                for (Field field : fields) {
                    if (Modifier.isStatic(field.getModifiers())) continue;
                    if (!ShapedRecipe.class.isAssignableFrom(field.getType())) continue;
                    Object nested = read(field, recipe);
                    if (nested == null || nested == recipe) continue;
                    Optional<Field> nestedField = PATTERN_FIELDS.computeIfAbsent(nested.getClass(), RecipeUtil::findPatternField);
                    if (nestedField.isPresent()) {
                        Object value = read(nestedField.get(), nested);
                        if (value instanceof ShapedRecipePattern pattern) return pattern;
                    }
                }
            }
        } catch (Throwable ignored) {
            // Optional mod recipe wrappers are not required to expose a pattern.
        }
        return null;
    }

    private static Optional<Field> findPatternField(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            Field[] fields;
            try { fields = current.getDeclaredFields(); } catch (Throwable ignored) { continue; }
            for (Field field : fields) {
                if (Modifier.isStatic(field.getModifiers())) continue;
                if (!ShapedRecipePattern.class.isAssignableFrom(field.getType())) continue;
                try { field.setAccessible(true); return Optional.of(field); }
                catch (Throwable ignored) { /* try the next field */ }
            }
        }
        return Optional.empty();
    }

    public static List<SizedEntry> ingredients(Recipe<?> recipe) {
        if (recipe == null) return List.of();
        List<SizedEntry> sized = new ArrayList<>();
        if (SIZED_CLASS != null) collectSized(recipe, sized);
        if (!sized.isEmpty()) return List.copyOf(sized);

        List<SizedEntry> plain = new ArrayList<>();
        try {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient != null && !ingredient.isEmpty()) plain.add(new SizedEntry(ingredient, 1));
            }
        } catch (Throwable ignored) {
            // Some custom recipe types do not expose the vanilla ingredient list.
        }
        if (!plain.isEmpty()) return List.copyOf(plain);
        collectPlain(recipe, plain);
        return List.copyOf(plain);
    }

    // Mirrors the original traversal: inspect direct fields, then unwrap only
    // Optional/Iterable containers and the exact optional SizedIngredient type.
    private static void collectSized(Object value, List<SizedEntry> output) {
        if (value == null) return;
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            Field[] fields;
            try { fields = type.getDeclaredFields(); } catch (Throwable ignored) { continue; }
            for (Field field : fields) {
                if (!Modifier.isStatic(field.getModifiers())) addSized(read(field, value), output);
            }
        }
    }

    private static void addSized(Object value, List<SizedEntry> output) {
        if (value == null) return;
        if (SIZED_CLASS != null && SIZED_CLASS.isInstance(value)) {
            try {
                Object ingredientValue = SIZED_INGREDIENT == null ? null : SIZED_INGREDIENT.invoke(value);
                Object countValue = SIZED_COUNT == null ? Integer.valueOf(1) : SIZED_COUNT.invoke(value);
                if (ingredientValue instanceof Ingredient ingredient && !ingredient.isEmpty()) {
                    int count = countValue instanceof Number number ? number.intValue() : 1;
                    output.add(new SizedEntry(ingredient, Math.max(1, count)));
                }
            } catch (Throwable ignored) { }
            return;
        }
        if (value instanceof Optional<?> optional) {
            optional.ifPresent(element -> addSized(element, output));
        } else if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) addSized(element, output);
        }
    }

    private static void collectPlain(Object value, List<SizedEntry> output) {
        if (value == null) return;
        for (Class<?> type = value.getClass(); type != null && type != Object.class; type = type.getSuperclass()) {
            Field[] fields;
            try { fields = type.getDeclaredFields(); } catch (Throwable ignored) { continue; }
            for (Field field : fields) {
                if (!Modifier.isStatic(field.getModifiers())) addPlain(read(field, value), output);
            }
        }
    }

    private static void addPlain(Object value, List<SizedEntry> output) {
        if (value instanceof Ingredient ingredient) {
            if (!ingredient.isEmpty()) output.add(new SizedEntry(ingredient, 1));
        } else if (value instanceof Optional<?> optional) {
            optional.ifPresent(element -> addPlain(element, output));
        } else if (value instanceof Iterable<?> iterable) {
            for (Object element : iterable) addPlain(element, output);
        }
    }

    private static Class<?> tryClass(String name) {
        try { return Class.forName(name, false, RecipeUtil.class.getClassLoader()); }
        catch (Throwable ignored) { return null; }
    }

    private static Method tryMethod(Class<?> type, String name) {
        if (type == null) return null;
        try {
            Method method = type.getMethod(name);
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) { return null; }
    }

    public static List<StackKey> options(Ingredient ingredient) {
        if (ingredient == null || ingredient.isEmpty()) return List.of();
        try {
            ItemStack[] stacks = ingredient.getItems();
            if (stacks == null || stacks.length == 0) return List.of();
            List<StackKey> cached = cachedOptions(Arrays.asList(stacks));
            if (cached != null) return cached;
            return normalizedOptions(Arrays.asList(stacks));
        } catch (Throwable ignored) {
            return List.of();
        }
    }

    private static List<StackKey> normalizedOptions(List<ItemStack> stacks) {
        List<StackKey> result = new ArrayList<>();
        for (ItemStack stack : stacks) {
            if (stack != null && !stack.isEmpty()) result.add(StackKey.of(stack));
        }
        return result.stream().distinct().sorted(RecipeUtil::compareKeys).toList();
    }

    private static List<StackKey> cachedOptions(List<ItemStack> stacks) {
        List<Item> items = new ArrayList<>(stacks.size());
        for (ItemStack stack : stacks) {
            if (stack == null || stack.isEmpty()) continue;
            if (!stack.getComponentsPatch().isEmpty()) return null;
            items.add(stack.getItem());
        }
        List<Item> key = List.copyOf(items);
        List<StackKey> cached = OPTIONS.get(key);
        if (cached != null) return cached;

        List<StackKey> values = items.stream().map(StackKey::of)
                .distinct().sorted(RecipeUtil::compareKeys).toList();
        if (OPTIONS.size() < 200_000) OPTIONS.putIfAbsent(key, values);
        return values;
    }

    public static List<StackKey> capOptions(List<StackKey> options) {
        if (options == null || options.isEmpty()) return List.of();
        if (options.size() <= MAX_OPTIONS) return List.copyOf(options);
        List<StackKey> result = new ArrayList<>(MAX_OPTIONS);
        Set<StackKey> selected = new LinkedHashSet<>();
        for (StackKey key : options) {
            if (result.size() >= MAX_OPTIONS) break;
            if (key != null && isVanilla(key.item()) && selected.add(key)) result.add(key);
        }
        Set<String> namespaces = new LinkedHashSet<>();
        for (StackKey key : options) {
            if (result.size() >= MAX_OPTIONS) break;
            if (key == null || selected.contains(key)) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(key.item());
            String namespace = id == null ? "" : id.getNamespace();
            if (namespaces.add(namespace) && selected.add(key)) result.add(key);
        }
        for (StackKey key : options) {
            if (result.size() >= MAX_OPTIONS) break;
            if (key != null && selected.add(key)) result.add(key);
        }
        return List.copyOf(result);
    }

    private static String namespaceOf(Item item) {
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "" : id.getNamespace();
    }

    public static boolean isVanilla(Item item) {
        ResourceLocation id = item == null ? null : BuiltInRegistries.ITEM.getKey(item);
        return id != null && "minecraft".equals(id.getNamespace());
    }

    private static Method findStackRemainingMethod() {
        try {
            Method method = ItemStack.class.getMethod("getCraftingRemainingItem");
            return ItemStack.class.isAssignableFrom(method.getReturnType()) ? method : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Preserves stack-aware remainder semantics when a compatible extension is present.
     * Vanilla Fabric falls back to Item#getCraftingRemainingItem without a NeoForge link.
     */
    private static ItemStack craftingRemainder(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        if (STACK_REMAINING_METHOD != null) {
            try {
                Object value = STACK_REMAINING_METHOD.invoke(stack);
                return value instanceof ItemStack remainder && !remainder.isEmpty()
                        ? remainder : ItemStack.EMPTY;
            } catch (Throwable ignored) {
                // An incompatible optional extension must not break recipe discovery.
            }
        }
        Item item = stack.getItem();
        if (item == null) return ItemStack.EMPTY;
        Item remainder = item.getCraftingRemainingItem();
        return remainder == null ? ItemStack.EMPTY : new ItemStack(remainder);
    }

    public static List<SccRecipe.Input> consumeReturnedOutput(List<SccRecipe.Input> inputs, StackKey output) {
        if (inputs == null || output == null) return inputs;
        List<SccRecipe.Input> changed = null;
        for (int i = 0; i < inputs.size(); i++) {
            SccRecipe.Input input = inputs.get(i);
            if (input.consumed() || input.options().isEmpty()) continue;
            boolean returnsOutput = false;
            for (StackKey option : input.options()) {
                try {
                    ItemStack remaining = craftingRemainder(option.item().getDefaultInstance());
                    if (!remaining.isEmpty() && remaining.getItem() == output.item()) {
                        returnsOutput = true;
                        break;
                    }
                } catch (Throwable ignored) { }
            }
            if (returnsOutput) {
                if (changed == null) changed = new ArrayList<>(inputs);
                changed.set(i, new SccRecipe.Input(input.options(), input.count(), true));
            }
        }
        return changed == null ? inputs : List.copyOf(changed);
    }

    public static List<SccRecipe.Input> mergeInputs(List<SccRecipe.Input> inputs) {
        if (inputs == null || inputs.isEmpty()) return List.of();
        Map<String, SccRecipe.Input> merged = new LinkedHashMap<>();
        for (SccRecipe.Input input : inputs) {
            if (input == null || input.options() == null || input.options().isEmpty()) continue;
            String key = keyOf(input.options()) + (input.consumed() ? "" : "#keep");
            SccRecipe.Input previous = merged.get(key);
            if (previous == null) merged.put(key, input);
            else {
                int count = input.consumed()
                        ? previous.count() + input.count()
                        : Math.max(previous.count(), input.count());
                merged.put(key, new SccRecipe.Input(previous.options(), count, input.consumed()));
            }
        }
        return List.copyOf(merged.values());
    }

    public static SccRecipe.Input input(List<StackKey> options, int count) {
        return input(options, count, null);
    }

    public static SccRecipe.Input input(List<StackKey> options, int count, Set<Item> keptBy) {
        // The original implementation decides consumption from the full option set,
        // before applying the display/graph cap. Do not let truncation change semantics.
        List<StackKey> source = options == null ? List.of() : options;
        boolean consumed = source.isEmpty();
        if (!source.isEmpty() && keptBy != null) {
            consumed = true;
            for (StackKey key : source) {
                if (key != null && keptBy.contains(key.item())) {
                    consumed = false;
                    break;
                }
            }
        } else if (!source.isEmpty()) {
            consumed = Consumption.isConsumed(source.getFirst().item());
        }
        return new SccRecipe.Input(capOptions(source), Math.max(1, count), consumed);
    }

    public static Set<Item> keptBy(Recipe<?> recipe) {
        if (recipe == null) return null;
        if (recipe instanceof AbstractCookingRecipe || recipe instanceof SingleItemRecipe
                || recipe instanceof SmithingRecipe) return Set.of();
        if (!(recipe instanceof CraftingRecipe crafting)) return null;
        if (overridesRemaining(crafting.getClass())) {
            if (THROWS_ON_REMAINING.contains(crafting.getClass()) || loudOnRemaining(crafting.getClass())) return null;
            List<List<ItemStack>> alternatives = new ArrayList<>();
            try {
                for (Ingredient ingredient : recipe.getIngredients()) {
                    ItemStack[] stacks = ingredient == null ? new ItemStack[0] : ingredient.getItems();
                    if (stacks.length == 0) continue;
                    List<ItemStack> nonEmpty = new ArrayList<>();
                    for (ItemStack stack : stacks) if (stack != null && !stack.isEmpty()) nonEmpty.add(stack);
                    if (!nonEmpty.isEmpty()) alternatives.add(nonEmpty);
                }
            } catch (Throwable ignored) { return null; }
            if (alternatives.isEmpty()) return null;
            try {
                List<ItemStack> firsts = alternatives.stream().map(values -> values.getFirst().copyWithCount(1)).toList();
                CraftingInput input = CraftingInput.of(1, firsts.size(), firsts);
                Method remainingMethod = remainingMethod(crafting.getClass());
                if (remainingMethod == null) return null;
                Object remainingValue = remainingMethod.invoke(crafting, input);
                if (!(remainingValue instanceof List<?> remaining)) return null;
                Set<Item> kept = new LinkedHashSet<>();
                for (int slot = 0; slot < Math.min(remaining.size(), alternatives.size()); slot++) {
                    Object entry = remaining.get(slot);
                    if (!(entry instanceof ItemStack returned) || returned.isEmpty()) continue;
                    ItemStack offered = firsts.get(slot);
                    if (returnsItself(offered, returned)) {
                        for (ItemStack alternative : alternatives.get(slot)) kept.add(alternative.getItem());
                    }
                }
                return kept.isEmpty() ? null : Set.copyOf(kept);
            } catch (Throwable ignored) {
                THROWS_ON_REMAINING.add(crafting.getClass());
                return null;
            }
        }

        Set<Item> kept = new LinkedHashSet<>();
        try {
            for (Ingredient ingredient : recipe.getIngredients()) {
                if (ingredient == null) continue;
                for (ItemStack stack : ingredient.getItems()) {
                    if (remainsCached(stack)) kept.add(stack.getItem());
                }
            }
        } catch (Throwable ignored) { return null; }
        return kept.isEmpty() ? null : Set.copyOf(kept);
    }

    public static Set<Item> keptBy(Object recipeOrHolder) {
        if (recipeOrHolder instanceof RecipeHolder<?> holder) return keptBy(holder.value());
        if (recipeOrHolder instanceof Recipe<?> recipe) return keptBy(recipe);
        return null;
    }

    private static boolean returnsItself(ItemStack offered, ItemStack returned) {
        return offered != null && returned != null && !offered.isEmpty() && !returned.isEmpty()
                && offered.getItem() == returned.getItem();
    }

    static boolean remainsCached(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        if (!stack.getComponentsPatch().isEmpty()) return remainsItself(stack);
        return REMAINS.computeIfAbsent(stack.getItem(), ignored -> remainsItself(stack));
    }

    public static boolean remainsItself(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        ItemStack remaining = craftingRemainder(stack.copyWithCount(1));
        return !remaining.isEmpty() && remaining.getItem() == stack.getItem();
    }

    public static ItemStack containerReturn(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return ItemStack.EMPTY;
        ItemStack remaining = craftingRemainder(stack.copyWithCount(1));
        return remaining.isEmpty() || remaining.getItem() == stack.getItem()
                ? ItemStack.EMPTY : remaining;
    }

    public static boolean ownRemainders(Recipe<?> recipe) {
        return recipe != null && overridesRemaining(recipe.getClass());
    }

    private static boolean overridesRemaining(Class<?> type) {
        return OVERRIDES_REMAINING.computeIfAbsent(type, current -> {
            Method method = remainingMethod(current);
            return method == null || method.getDeclaringClass() != Recipe.class;
        });
    }

    private static Method remainingMethod(Class<?> type) {
        try {
            for (Method method : type.getMethods()) {
                if (method.getName().equals("getRemainingItems") && method.getParameterCount() == 1
                        && method.getParameterTypes()[0].getName().equals(
                        "net.minecraft.world.item.crafting.RecipeInput")) {
                    method.setAccessible(true);
                    return method;
                }
            }
        } catch (Throwable ignored) { }
        return null;
    }

    private static boolean loudOnRemaining(Class<?> type) {
        return LOUD_ON_REMAINING.computeIfAbsent(type, current -> {
            for (Class<?> candidate = current; candidate != null; candidate = candidate.getSuperclass()) {
                for (Class<?> contract : candidate.getInterfaces()) {
                    if (fluidAware(contract)) return true;
                }
            }
            return false;
        });
    }

    private static boolean fluidAware(Class<?> contract) {
        if (contract == null) return false;
        if (contract.getName().endsWith(".IFluidAwareRecipe")
                || contract.getSimpleName().equals("IFluidAwareRecipe")) return true;
        for (Class<?> parent : contract.getInterfaces()) {
            if (fluidAware(parent)) return true;
        }
        return false;
    }

    public static String keyOf(List<StackKey> options) {
        if (options == null || options.isEmpty()) return "empty";
        StringBuilder key = new StringBuilder();
        for (StackKey option : options) {
            if (key.length() > 0) key.append('|');
            key.append(option.encode());
        }
        String value = key.toString();
        return options.size() <= 4 ? value : "t" + Integer.toHexString(value.hashCode()) + ":" + options.size();
    }

    public static int compareItems(Item left, Item right) {
        return String.valueOf(BuiltInRegistries.ITEM.getKey(left))
                .compareTo(String.valueOf(BuiltInRegistries.ITEM.getKey(right)));
    }

    public static int compareKeys(StackKey left, StackKey right) {
        int rank = Integer.compare(originRank(left), originRank(right));
        if (rank != 0) return rank;
        String a = left.encode();
        String b = right.encode();
        int length = Integer.compare(a.length(), b.length());
        return length != 0 ? length : a.compareTo(b);
    }

    private static int originRank(StackKey key) {
        return key.encode().startsWith("minecraft:") ? 0 : 1;
    }

    public static boolean hasColourWord(StackKey key) {
        if (key == null) return false;
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(key.item());
        if (id == null) return false;
        for (String part : id.getPath().split("_")) if (COLOURS.contains(part)) return true;
        return false;
    }

    public static boolean isColourVariantOf(StackKey candidate, StackKey output) {
        if (candidate == null || output == null || candidate.equals(output)) return false;
        ResourceLocation candidateId = BuiltInRegistries.ITEM.getKey(candidate.item());
        ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(output.item());
        if (candidateId == null || outputId == null || hasColourWord(output)) return false;
        List<String> rest = colourRest(candidateId.getPath());
        if (rest == null) return false;
        List<String> outputParts = List.of(outputId.getPath().split("_"));
        if (rest.size() < outputParts.size()) return false;
        int offset = rest.size() - outputParts.size();
        for (int i = 0; i < outputParts.size(); i++) if (!rest.get(offset + i).equals(outputParts.get(i))) return false;
        return true;
    }

    public static List<String> colourRest(String path) {
        if (path == null || path.isBlank()) return null;
        String[] parts = path.split("_");
        List<String> rest = new ArrayList<>();
        boolean foundColour = false;
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.equals("light") && i + 1 < parts.length
                    && Set.of("gray", "grey", "blue").contains(parts[i + 1])) {
                foundColour = true;
                i++;
            } else if (COLOURS.contains(part)) {
                foundColour = true;
            } else if (!DYE_WORDS.contains(part)) {
                rest.add(part);
            }
        }
        return !foundColour || rest.isEmpty() ? null : List.copyOf(rest);
    }

    public static StackKey ownMaterial(StackKey output, List<StackKey> candidates) {
        if (output == null || candidates == null || candidates.size() < 2) return null;
        ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(output.item());
        if (outputId == null) return null;
        String[] outputParts = outputId.getPath().split("[_/]");
        if (outputParts.length < 2) return null;
        String[] outputPrefix = Arrays.copyOf(outputParts, outputParts.length - 1);
        StackKey best = null;
        int bestRun = 0;
        for (StackKey candidate : candidates) {
            if (candidate == null) continue;
            ResourceLocation id = BuiltInRegistries.ITEM.getKey(candidate.item());
            if (id == null) continue;
            String[] parts = id.getPath().split("[_/]");
            int run = Math.max(1, parts.length - 1);
            if (run <= bestRun || String.join("_", Arrays.copyOf(parts, run)).length() < 3) continue;
            if (containsRun(outputPrefix, parts, run)) {
                best = candidate;
                bestRun = run;
            }
        }
        return best;
    }

    private static boolean containsRun(String[] haystack, String[] needle, int length) {
        for (int start = 0; start + length <= haystack.length; start++) {
            boolean match = true;
            for (int i = 0; i < length; i++) {
                if (!haystack[start + i].equals(needle[i])) { match = false; break; }
            }
            if (match) return true;
        }
        return false;
    }

    public static String nameOf(StackKey key) {
        try {
            ItemStack display = key.display();
            Object component = display.getClass().getMethod("getHoverName").invoke(display);
            return String.valueOf(component.getClass().getMethod("getString").invoke(component));
        } catch (Throwable ignored) { return key.encode(); }
    }

    public static String itemId(Item item) {
        ResourceLocation id = item == null ? null : BuiltInRegistries.ITEM.getKey(item);
        return id == null ? "?" : id.toString();
    }

    public static Item itemOf(String id) {
        ResourceLocation location = ResourceLocation.tryParse(id);
        if (location == null || !BuiltInRegistries.ITEM.containsKey(location)) return null;
        return BuiltInRegistries.ITEM.get(location);
    }

    private static Object read(Field field, Object owner) {
        try {
            if (!field.canAccess(owner)) field.setAccessible(true);
            return field.get(owner);
        } catch (Throwable ignored) { return null; }
    }
}
