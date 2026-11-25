package de.scc.calculator.api;

import de.scc.calculator.SccLog;
import de.scc.calculator.calc.RecipeBook;
import de.scc.calculator.calc.SccRecipe;
import de.scc.calculator.calc.StackKey;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.material.Fluid;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fabric-side port of SCC's recipe-provider API. Providers contribute recipes
 * to an analysis snapshot; failures are isolated so one optional integration
 * cannot discard vanilla recipe discovery.
 */
public final class SccRecipeProviders {
    private static final Map<ResourceLocation, Provider> PROVIDERS = new LinkedHashMap<>();
    private static final Set<String> CLAIMED = new LinkedHashSet<>();
    private static final Map<String, Double> STEP_COSTS = new ConcurrentHashMap<>();
    private static volatile Runnable changeListener = () -> { };

    private SccRecipeProviders() { }

    public static void register(ResourceLocation id, Provider provider) {
        if (id == null || provider == null) return;
        boolean changed;
        synchronized (SccRecipeProviders.class) {
            changed = PROVIDERS.put(id, provider) != provider;
        }
        if (changed) changed();
    }

    public static void setChangeListener(Runnable listener) {
        changeListener = listener == null ? () -> { } : listener;
    }

    public static void stepCost(ResourceLocation id, double cost) {
        if (id == null) return;
        String key = id.toString();
        Double previous;
        Double next = cost > 0.0d && Double.isFinite(cost) ? cost : null;
        if (next == null) previous = STEP_COSTS.remove(key);
        else previous = STEP_COSTS.put(key, next);

        boolean changed = previous == null ? next != null
                : next == null || Double.compare(previous, next) != 0;
        if (changed) changed();
    }

    public static double stepCostOf(String id) {
        return id == null ? 0.0d : STEP_COSTS.getOrDefault(id, 0.0d);
    }

    public static synchronized void claim(ResourceLocation id) {
        if (id != null) CLAIMED.add(id.toString());
    }

    public static synchronized boolean claimed(String id) {
        return id != null && CLAIMED.contains(id);
    }

    public static synchronized boolean any() {
        return !PROVIDERS.isEmpty();
    }

    public static void changed() {
        try {
            changeListener.run();
        } catch (Throwable error) {
            SccLog.caught("analysis", "recipe provider change callback failed", error);
        }
    }

    public static boolean allReady() {
        for (Provider provider : snapshotProviders()) {
            try {
                if (!provider.ready()) return false;
            } catch (Throwable error) {
                SccLog.caught("analysis", "recipe provider readiness check failed", error);
            }
        }
        return true;
    }

    public static float progress() {
        float minimum = 1.0f;
        for (Provider provider : snapshotProviders()) {
            try {
                if (!provider.ready()) minimum = Math.min(minimum, Math.max(0.0f, Math.min(1.0f, provider.progress())));
            } catch (Throwable error) {
                SccLog.caught("analysis", "recipe provider progress check failed", error);
            }
        }
        return minimum;
    }

    public static String stamp() {
        List<Map.Entry<ResourceLocation, Provider>> providers;
        synchronized (SccRecipeProviders.class) {
            providers = new ArrayList<>(PROVIDERS.entrySet());
        }
        providers.sort(Comparator.comparing(entry -> entry.getKey().toString()));
        StringBuilder stamp = new StringBuilder();
        for (Map.Entry<ResourceLocation, Provider> entry : providers) {
            String providerStamp;
            try {
                providerStamp = entry.getValue().stamp();
            } catch (Throwable error) {
                providerStamp = "error";
            }
            stamp.append(entry.getKey()).append('=').append(providerStamp == null ? "" : providerStamp).append(';');
        }
        return stamp.toString();
    }

    public static void addAll(RecipeBook book) {
        if (book == null) return;
        List<Map.Entry<ResourceLocation, Provider>> providers;
        synchronized (SccRecipeProviders.class) {
            providers = new ArrayList<>(PROVIDERS.entrySet());
        }
        for (Map.Entry<ResourceLocation, Provider> entry : providers) {
            BookOutput output = new BookOutput(book);
            try {
                entry.getValue().addRecipes(output);
            } catch (Throwable error) {
                SccLog.caught("analysis", "recipe provider " + entry.getKey() + " failed", error);
            }
            synchronized (SccRecipeProviders.class) {
                CLAIMED.addAll(output.categories);
            }
            SccLog.info("analysis", output.added + " recipes contributed by " + entry.getKey());
        }
    }

    private static List<Provider> snapshotProviders() {
        synchronized (SccRecipeProviders.class) {
            return List.copyOf(PROVIDERS.values());
        }
    }

    public interface Provider {
        void addRecipes(Output output);
        default String stamp() { return ""; }
        default boolean ready() { return true; }
        default float progress() { return 1.0f; }
    }

    public interface Output {
        Builder recipe(String id, ResourceLocation category);
        void catalyst(ResourceLocation machine, ItemLike item);
        void catalyst(ResourceLocation machine, String itemId);
        void add(SccRecipe recipe);
    }

    public interface Builder {
        Builder name(String name);
        Builder output(ItemStack output, int count);
        Builder chance(float chance);
        Builder input(ItemStack input, int count);
        Builder inputAnyOf(List<ItemStack> options, int count);
        Builder input(TagKey<Item> tag, int count);
        Builder tool(ItemStack tool);
        Builder toolAnyOf(List<ItemStack> tools);
        Builder fluid(String fluidId, int amount);
        Builder fluid(Fluid fluid, int amount);
        Builder optionalFluid(String fluidId, int amount);
        void add();
    }

    private static final class BookOutput implements Output {
        private final RecipeBook book;
        private final Set<String> categories = new LinkedHashSet<>();
        private int added;

        private BookOutput(RecipeBook book) { this.book = book; }

        @Override
        public Builder recipe(String id, ResourceLocation category) {
            if (category != null) categories.add(category.toString());
            return new RecipeBuilder(this, id, category);
        }

        @Override
        public void catalyst(ResourceLocation machine, ItemLike item) {
            if (machine == null || item == null) return;
            ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(item.asItem());
            if (itemId != null) catalyst(machine, itemId.toString());
        }

        @Override
        public void catalyst(ResourceLocation machine, String itemId) {
            if (machine == null || itemId == null || itemId.isBlank()) return;
            book.addCatalyst(machine.toString(), itemId);
            categories.add(machine.toString());
        }

        @Override
        public void add(SccRecipe recipe) {
            if (recipe == null) return;
            int before = book.recipeCount();
            book.add(recipe);
            if (book.recipeCount() > before) added++;
        }
    }

    private static final class RecipeBuilder implements Builder {
        private final BookOutput output;
        private final String id;
        private final ResourceLocation category;
        private String name;
        private StackKey result;
        private int resultCount = 1;
        private float chance = 1.0f;
        private final List<SccRecipe.Input> inputs = new ArrayList<>();
        private final List<SccRecipe.Fluid> fluids = new ArrayList<>();

        private RecipeBuilder(BookOutput output, String id, ResourceLocation category) {
            this.output = output;
            this.id = id;
            this.category = category;
        }

        @Override public Builder name(String value) { name = value; return this; }

        @Override
        public Builder output(ItemStack stack, int count) {
            result = StackKey.of(stack);
            resultCount = Math.max(1, count);
            return this;
        }

        @Override public Builder chance(float value) {
            chance = value > 0.0f && value < 1.0f ? value : 1.0f;
            return this;
        }

        @Override
        public Builder input(ItemStack stack, int count) {
            StackKey key = StackKey.of(stack);
            if (key != null) inputs.add(new SccRecipe.Input(List.of(key), Math.max(1, count), true));
            return this;
        }

        @Override
        public Builder inputAnyOf(List<ItemStack> stacks, int count) {
            List<StackKey> keys = keys(stacks);
            if (!keys.isEmpty()) inputs.add(new SccRecipe.Input(keys, Math.max(1, count), true));
            return this;
        }

        @Override
        public Builder input(TagKey<Item> tag, int count) {
            if (tag == null) return this;
            ItemStack[] matching = Ingredient.of(tag).getItems();
            List<StackKey> keys = new ArrayList<>(matching.length);
            for (ItemStack stack : matching) {
                StackKey key = StackKey.of(stack);
                if (key != null && !keys.contains(key)) keys.add(key);
            }
            if (!keys.isEmpty()) inputs.add(new SccRecipe.Input(List.copyOf(keys), Math.max(1, count), true));
            return this;
        }

        @Override
        public Builder tool(ItemStack stack) {
            StackKey key = StackKey.of(stack);
            if (key != null) inputs.add(new SccRecipe.Input(List.of(key), 1, false));
            return this;
        }

        @Override
        public Builder toolAnyOf(List<ItemStack> stacks) {
            List<StackKey> keys = keys(stacks);
            if (!keys.isEmpty()) inputs.add(new SccRecipe.Input(keys, 1, false));
            return this;
        }

        @Override public Builder fluid(String id, int amount) {
            if (id != null && !id.isBlank() && amount > 0) fluids.add(new SccRecipe.Fluid(id, amount));
            return this;
        }

        @Override public Builder fluid(Fluid fluid, int amount) {
            if (fluid != null) {
                ResourceLocation id = BuiltInRegistries.FLUID.getKey(fluid);
                if (id != null) fluid(id.toString(), amount);
            }
            return this;
        }

        @Override public Builder optionalFluid(String id, int amount) {
            if (id != null && !id.isBlank() && amount > 0) fluids.add(new SccRecipe.Fluid(id, amount, true));
            return this;
        }

        @Override
        public void add() {
            if (id == null || id.isBlank() || category == null || result == null) return;
            String displayName = name == null || name.isBlank() ? category.getPath() : name;
            output.add(new SccRecipe(id, category, displayName, result, resultCount,
                    List.copyOf(inputs), List.copyOf(fluids), chance));
        }

        private static List<StackKey> keys(List<ItemStack> stacks) {
            if (stacks == null || stacks.isEmpty()) return List.of();
            List<StackKey> keys = new ArrayList<>();
            for (ItemStack stack : stacks) {
                StackKey key = StackKey.of(stack);
                if (key != null && !keys.contains(key)) keys.add(key);
            }
            return List.copyOf(keys);
        }
    }
}
