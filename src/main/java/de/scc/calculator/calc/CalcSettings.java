package de.scc.calculator.calc;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import de.scc.calculator.SccConfig;
import de.scc.calculator.platform.AtomicFiles;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.world.item.Item;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * User recipe/ingredient/machine choices and base-item overrides.
 *
 * <p>Storage format remains compatible with SCC's {@code scc/overrides.json}.
 * Loader-specific file resolution is handled by FabricLoader; simulation and
 * ingredient invalidation are exposed as callbacks rather than hard references
 * to client-only SCC classes.</p>
 */
public final class CalcSettings {
    private static final CalcSettings INSTANCE = new CalcSettings();
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Map<StackKey, String> recipeOverrides = new HashMap<>();
    private final Map<StackKey, List<String>> recipeRanks = new HashMap<>();
    private final Map<String, StackKey> ingredientOverrides = new HashMap<>();
    private final Map<String, String> machineOverrides = new HashMap<>();
    private final Set<StackKey> sessionBase = new LinkedHashSet<>();

    private volatile boolean loaded;
    private volatile Runnable simulationInvalidation = () -> { };
    private volatile Runnable ingredientInvalidation = () -> { };

    private CalcSettings() {
    }

    public static CalcSettings get() {
        CalcSettings settings = INSTANCE;
        if (!settings.loaded) {
            synchronized (settings) {
                if (!settings.loaded) {
                    settings.loaded = true;
                    settings.load();
                }
            }
        }
        return settings;
    }

    public void setInvalidationCallbacks(Runnable simulation, Runnable ingredients) {
        simulationInvalidation = simulation == null ? () -> { } : simulation;
        ingredientInvalidation = ingredients == null ? () -> { } : ingredients;
    }

    private static Path file() {
        return FabricLoader.getInstance().getConfigDir().resolve("scc").resolve("overrides.json");
    }

    private void load() {
        Path path = file();
        if (!Files.isRegularFile(path)) {
            return;
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            Stored stored = GSON.fromJson(reader, Stored.class);
            if (stored == null) {
                return;
            }
            if (stored.recipes != null) {
                stored.recipes.forEach((encoded, id) -> {
                    StackKey key = StackKey.decode(encoded);
                    if (key != null && id != null && !id.isBlank()) {
                        recipeOverrides.put(key, id);
                    }
                });
            }
            if (stored.ingredients != null) {
                stored.ingredients.forEach((slot, encoded) -> {
                    StackKey key = StackKey.decode(encoded);
                    if (slot != null && key != null) {
                        ingredientOverrides.put(slot, key);
                    }
                });
            }
            if (stored.recipeRanks != null) {
                stored.recipeRanks.forEach((encoded, ids) -> {
                    StackKey key = StackKey.decode(encoded);
                    if (key != null && ids != null) {
                        List<String> values = ids.stream().filter(id -> id != null && !id.isBlank())
                                .distinct().limit(8).toList();
                        if (!values.isEmpty()) {
                            recipeRanks.put(key, new ArrayList<>(values));
                        }
                    }
                });
            }
            if (stored.machines != null) {
                stored.machines.forEach((slot, id) -> {
                    if (slot != null && !slot.isBlank() && id != null && !id.isBlank()) {
                        machineOverrides.put(slot, id);
                    }
                });
            }
            if (stored.baseItems != null) {
                for (String encoded : stored.baseItems) {
                    StackKey key = StackKey.decode(encoded);
                    if (key != null) {
                        sessionBase.add(key);
                    }
                }
            }
        } catch (Exception error) {
            System.getLogger(CalcSettings.class.getName()).log(System.Logger.Level.WARNING,
                    "Could not read SCC overrides.json", error);
        }
    }

    public synchronized void save() {
        Stored stored = new Stored();
        recipeOverrides.forEach((key, id) -> stored.recipes.put(key.encode(), id));
        ingredientOverrides.forEach((slot, key) -> stored.ingredients.put(slot, key.encode()));
        for (StackKey key : sessionBase) {
            stored.baseItems.add(key.encode());
        }
        stored.machines.putAll(new TreeMap<>(machineOverrides));
        recipeRanks.forEach((key, ids) -> stored.recipeRanks.put(key.encode(), List.copyOf(ids)));

        Path path = file();
        try {
            AtomicFiles.writeUtf8(path, writer -> GSON.toJson(stored, writer));
        } catch (IOException | RuntimeException error) {
            System.getLogger(CalcSettings.class.getName()).log(System.Logger.Level.WARNING,
                    "Could not write SCC overrides.json; previous file was preserved", error);
        }
    }

    public synchronized void setRecipe(StackKey output, String recipeId) {
        if (output == null) {
            return;
        }
        if (recipeId == null) {
            recipeOverrides.remove(output);
            recipeRanks.remove(output);
        } else {
            recipeOverrides.put(output, recipeId);
            promote(output, recipeId);
        }
        save();
        simulationInvalidation.run();
    }

    private void promote(StackKey output, String recipeId) {
        List<String> ranking = recipeRanks.computeIfAbsent(output, ignored -> new ArrayList<>());
        ranking.remove(recipeId);
        ranking.addFirst(recipeId);
        while (ranking.size() > 8) {
            ranking.removeLast();
        }
    }

    public List<String> ranking(StackKey output) {
        List<String> values = recipeRanks.get(output);
        return values == null ? List.of() : Collections.unmodifiableList(values);
    }

    public String recipeOverride(StackKey output) {
        return recipeOverrides.get(output);
    }

    public Map<StackKey, String> recipeOverrides() {
        return Collections.unmodifiableMap(recipeOverrides);
    }

    public synchronized void cycleRecipe(StackKey output, RecipeBook book) {
        List<SccRecipe> recipes = book.recipesFor(output);
        if (recipes.isEmpty()) {
            return;
        }
        sessionBase.remove(output);
        if (recipes.size() == 1) {
            String id = recipes.getFirst().id();
            recipeOverrides.put(output, id);
            promote(output, id);
        } else {
            String current = recipeOverrides.get(output);
            int index = 0;
            if (current != null) {
                for (int i = 0; i < recipes.size(); i++) {
                    if (recipes.get(i).id().equals(current)) {
                        index = i;
                        break;
                    }
                }
                index = (index + 1) % recipes.size();
            }
            String id = recipes.get(index).id();
            recipeOverrides.put(output, id);
            promote(output, id);
        }
        save();
        simulationInvalidation.run();
    }

    public String machineOverride(String slot) {
        return slot == null ? null : machineOverrides.get(slot);
    }

    public Map<String, String> machineOverrides() {
        return Collections.unmodifiableMap(machineOverrides);
    }

    public synchronized void setMachine(String slot, String recipeId) {
        if (slot == null || slot.isBlank()) {
            return;
        }
        if (recipeId == null || recipeId.isBlank()) {
            machineOverrides.remove(slot);
        } else {
            machineOverrides.put(slot, recipeId);
        }
        save();
        simulationInvalidation.run();
    }

    public StackKey ingredientOverride(String slot) {
        return ingredientOverrides.get(slot);
    }

    public synchronized void setIngredient(String slot, StackKey item) {
        if (slot == null || slot.isBlank()) {
            return;
        }
        if (item == null) {
            ingredientOverrides.remove(slot);
        } else {
            ingredientOverrides.put(slot, item);
        }
        save();
        ingredientInvalidation.run();
        simulationInvalidation.run();
    }

    public <T> T withIngredient(String slot, StackKey item, Supplier<T> action) {
        StackKey previous = ingredientOverrides.put(slot, item);
        ingredientInvalidation.run();
        simulationInvalidation.run();
        try {
            return action.get();
        } finally {
            if (previous == null) {
                ingredientOverrides.remove(slot);
            } else {
                ingredientOverrides.put(slot, previous);
            }
            ingredientInvalidation.run();
            simulationInvalidation.run();
        }
    }

    public synchronized void cycleIngredient(String slot, List<StackKey> options, StackKey current) {
        if (options == null || options.size() <= 1 || slot == null) {
            return;
        }
        int index = options.indexOf(current);
        StackKey next = options.get((index + 1 + options.size()) % options.size());
        ingredientOverrides.put(slot, next);
        save();
        ingredientInvalidation.run();
        simulationInvalidation.run();
    }

    public boolean isBase(StackKey item) {
        return sessionBase.contains(item) || (item != null && SccConfig.get().isForcedBase(item.item()));
    }

    public synchronized void toggleSessionBase(StackKey item) {
        if (item == null) {
            return;
        }
        if (!sessionBase.remove(item)) {
            sessionBase.add(item);
        }
        save();
        simulationInvalidation.run();
    }

    public Set<StackKey> allBaseItems() {
        Set<StackKey> result = new HashSet<>(sessionBase);
        for (Item item : SccConfig.get().forcedBaseItemSet()) {
            result.add(StackKey.of(item));
        }
        return result;
    }

    public static Set<StackKey> preferredKeys() {
        Set<StackKey> result = new LinkedHashSet<>();
        for (Item item : SccConfig.get().preferredIngredientList()) {
            result.add(StackKey.of(item));
        }
        return result;
    }

    public synchronized void reset() {
        recipeOverrides.clear();
        recipeRanks.clear();
        ingredientOverrides.clear();
        machineOverrides.clear();
        sessionBase.clear();
        save();
        ingredientInvalidation.run();
        simulationInvalidation.run();
    }

    private static final class Stored {
        private Map<String, String> recipes = new HashMap<>();
        private Map<String, String> ingredients = new HashMap<>();
        private List<String> baseItems = new ArrayList<>();
        private Map<String, List<String>> recipeRanks = new HashMap<>();
        private Map<String, String> machines = new HashMap<>();
    }
}
