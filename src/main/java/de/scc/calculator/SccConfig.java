package de.scc.calculator;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * Minimal configuration compatibility layer required by the original
 * StackKey bytecode embedded for Minecraft 1.21.1.
 *
 * <p>This is intentionally not the full SCC configuration. The full Fabric
 * config migration remains a separate task. Its current responsibility is
 * only the variant/ignored component policy queried by StackKey.</p>
 */
public final class SccConfig {
    private static final List<String> DEFAULT_VARIANTS = List.of(
            "minecraft:potion_contents",
            "minecraft:stored_enchantments",
            "minecraft:block_entity_data",
            "ironjetpacks:*"
    );

    private static final List<String> DEFAULT_PREFERRED = List.of(
            "minecraft:oak_log", "minecraft:oak_planks", "minecraft:cobblestone",
            "minecraft:stone", "minecraft:coal", "minecraft:white_wool",
            "minecraft:oak_slab", "minecraft:sand", "minecraft:glass",
            "minecraft:glass_pane", "minecraft:terracotta"
    );

    private static final List<String> DEFAULT_IGNORED = List.of(
            "minecraft:damage",
            "minecraft:repair_cost",
            "minecraft:custom_name",
            "minecraft:item_name",
            "minecraft:lore",
            "minecraft:rarity",
            "minecraft:custom_model_data",
            "minecraft:enchantment_glint_override",
            "minecraft:dyed_color",
            "minecraft:hide_additional_tooltip",
            "minecraft:hide_tooltip",
            "minecraft:tooltip_display",
            "minecraft:creative_slot_lock",
            "occultism:spirit_name",
            "sophisticatedcore:number_of_inventory_slots",
            "sophisticatedcore:number_of_upgrade_slots"
    );

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final String CONFIG_FILE = "config.json";
    private static volatile SccConfig instance = new SccConfig(DEFAULT_VARIANTS, DEFAULT_IGNORED);

    // Core calculation limits retained from SCC 1.6.5 defaults.
    public volatile int maxDepth = 48;
    public volatile int maxNodes = 20_000;
    public volatile int maxMillis = 1_500;
    public volatile List<String> forcedBaseItems = new ArrayList<>();
    public volatile List<String> preferredIngredients = new ArrayList<>(DEFAULT_PREFERRED);
    public volatile List<String> blockedRecipes = new ArrayList<>(List.of("excessive_utilities:keep_paintbrush/*"));
    public volatile List<String> blockedItems = new ArrayList<>(List.of("excessive_utilities:colored_*"));
    public volatile List<String> blockedRecipeTypes = new ArrayList<>(List.of("minecraft:campfire_cooking"));
    public volatile List<String> nonConsumedItems = new ArrayList<>(List.of(
            "*:*_press", "*:*_mold", "*:*_die", "*:*_template", "*:*_pattern", "*:*_stencil"
    ));
    public volatile boolean preferSameModRecipes = true;
    public volatile List<String> preferredRecipeNamespaces = new ArrayList<>();
    public volatile boolean autoAnalyse = true;
    public volatile boolean saveAnalysis = true;
    public volatile int analysisMillisPerTick = 4;
    public volatile int analysisMillisWhenMenu = 15;
    public volatile int analysisMillisWhenOpen = 12;
    public volatile int analysisMillisPerFrame = 12;

    private volatile List<String> variantComponents;
    private volatile List<String> ignoredComponents;
    private volatile Set<String> variantExact;
    private volatile Set<String> variantNamespaces;
    private volatile Set<String> ignoredExact;
    private volatile Set<String> ignoredNamespaces;
    private volatile List<java.util.regex.Pattern> blockedRecipePatterns;
    private volatile List<java.util.regex.Pattern> blockedItemPatterns;
    private volatile List<String> blockedRecipePatternValues;
    private volatile List<String> blockedItemPatternValues;
    private volatile Set<String> blockedRecipeTypeSet;
    private volatile List<String> blockedRecipeTypeValues;

    public SccConfig() {
        this(DEFAULT_VARIANTS, DEFAULT_IGNORED);
    }

    public SccConfig(Collection<String> variantComponents, Collection<String> ignoredComponents) {
        this.variantComponents = cleanList(variantComponents);
        this.ignoredComponents = cleanList(ignoredComponents);
    }

    public static SccConfig get() {
        return instance;
    }

    /** Replace the immutable policy snapshot; StackKey notices the new object identity and resets caches. */
    public static synchronized void replace(Collection<String> variants, Collection<String> ignored) {
        SccConfig next = instance.copy();
        next.update(variants, ignored);
        instance = next;
    }

    /** Loads the Fabric config without touching registries during early mod initialization. */
    public static synchronized void load() {
        Path file = configPath();
        if (!Files.isRegularFile(file)) {
            save();
            return;
        }
        try (Reader reader = Files.newBufferedReader(file)) {
            ConfigData data = GSON.fromJson(reader, ConfigData.class);
            if (data == null) throw new IllegalStateException("empty config file");
            instance = fromData(data);
        } catch (IOException | RuntimeException error) {
            SccLog.warn("config", "could not load SCC config; defaults retained: " + error.getMessage());
            try {
                Path backup = file.resolveSibling(CONFIG_FILE + ".broken");
                Files.copy(file, backup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException backupError) {
                SccLog.caught("config", "could not back up invalid config", backupError);
            }
        }
    }

    /** Writes the current config atomically so an interrupted shutdown cannot truncate it. */
    public static synchronized void save() {
        Path file = configPath();
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            try (Writer writer = Files.newBufferedWriter(temporary)) {
                GSON.toJson(ConfigData.from(instance), writer);
            }
            try {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException error) {
            SccLog.error("config", "could not save SCC config", error);
            try { Files.deleteIfExists(temporary); } catch (IOException ignored) { }
        }
    }

    private static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("scc").resolve(CONFIG_FILE);
    }

    private SccConfig copy() {
        SccConfig next = new SccConfig(variantComponents, ignoredComponents);
        next.maxDepth = maxDepth;
        next.maxNodes = maxNodes;
        next.maxMillis = maxMillis;
        next.forcedBaseItems = copyList(forcedBaseItems);
        next.preferredIngredients = copyList(preferredIngredients);
        next.blockedRecipes = copyList(blockedRecipes);
        next.blockedItems = copyList(blockedItems);
        next.blockedRecipeTypes = copyList(blockedRecipeTypes);
        next.nonConsumedItems = copyList(nonConsumedItems);
        next.preferSameModRecipes = preferSameModRecipes;
        next.preferredRecipeNamespaces = copyList(preferredRecipeNamespaces);
        next.autoAnalyse = autoAnalyse;
        next.saveAnalysis = saveAnalysis;
        next.analysisMillisPerTick = analysisMillisPerTick;
        next.analysisMillisWhenMenu = analysisMillisWhenMenu;
        next.analysisMillisWhenOpen = analysisMillisWhenOpen;
        next.analysisMillisPerFrame = analysisMillisPerFrame;
        return next;
    }

    private static SccConfig fromData(ConfigData data) {
        SccConfig config = new SccConfig(
                listOrDefault(data.variantComponents, DEFAULT_VARIANTS),
                listOrDefault(data.ignoredComponents, DEFAULT_IGNORED));
        config.maxDepth = clamp(data.maxDepth, 1, 512, 48);
        config.maxNodes = clamp(data.maxNodes, 100, 1_000_000, 20_000);
        config.maxMillis = clamp(data.maxMillis, 10, 60_000, 1_500);
        config.forcedBaseItems = listOrEmpty(data.forcedBaseItems);
        config.preferredIngredients = listOrDefault(data.preferredIngredients, DEFAULT_PREFERRED);
        config.blockedRecipes = listOrDefault(data.blockedRecipes, List.of("excessive_utilities:keep_paintbrush/*"));
        config.blockedItems = listOrDefault(data.blockedItems, List.of("excessive_utilities:colored_*"));
        config.blockedRecipeTypes = listOrDefault(data.blockedRecipeTypes, List.of("minecraft:campfire_cooking"));
        config.nonConsumedItems = listOrDefault(data.nonConsumedItems, List.of(
                "*:*_press", "*:*_mold", "*:*_die", "*:*_template", "*:*_pattern", "*:*_stencil"));
        config.preferSameModRecipes = data.preferSameModRecipes;
        config.preferredRecipeNamespaces = listOrEmpty(data.preferredRecipeNamespaces);
        config.autoAnalyse = data.autoAnalyse;
        config.saveAnalysis = data.saveAnalysis;
        config.analysisMillisPerTick = clamp(data.analysisMillisPerTick, 1, 50, 4);
        config.analysisMillisWhenMenu = clamp(data.analysisMillisWhenMenu, 1, 100, 15);
        config.analysisMillisWhenOpen = clamp(data.analysisMillisWhenOpen, 1, 100, 12);
        config.analysisMillisPerFrame = clamp(data.analysisMillisPerFrame, 1, 100, 12);
        return config;
    }

    private static int clamp(int value, int min, int max, int fallback) {
        return value < min || value > max ? fallback : value;
    }

    private static List<String> listOrEmpty(List<String> values) {
        return new ArrayList<>(cleanList(values));
    }

    private static List<String> listOrDefault(List<String> values, List<String> fallback) {
        return values == null ? new ArrayList<>(fallback) : new ArrayList<>(cleanList(values));
    }

    private static List<String> copyList(List<String> values) {
        return new ArrayList<>(cleanList(values));
    }

    private static final class ConfigData {
        int maxDepth = 48;
        int maxNodes = 20_000;
        int maxMillis = 1_500;
        List<String> forcedBaseItems = new ArrayList<>();
        List<String> preferredIngredients = new ArrayList<>(DEFAULT_PREFERRED);
        List<String> blockedRecipes = new ArrayList<>(List.of("excessive_utilities:keep_paintbrush/*"));
        List<String> blockedItems = new ArrayList<>(List.of("excessive_utilities:colored_*"));
        List<String> blockedRecipeTypes = new ArrayList<>(List.of("minecraft:campfire_cooking"));
        List<String> nonConsumedItems = new ArrayList<>(List.of(
                "*:*_press", "*:*_mold", "*:*_die", "*:*_template", "*:*_pattern", "*:*_stencil"));
        boolean preferSameModRecipes = true;
        List<String> preferredRecipeNamespaces = new ArrayList<>();
        boolean autoAnalyse = true;
        boolean saveAnalysis = true;
        int analysisMillisPerTick = 4;
        int analysisMillisWhenMenu = 15;
        int analysisMillisWhenOpen = 12;
        int analysisMillisPerFrame = 12;
        List<String> variantComponents = new ArrayList<>(DEFAULT_VARIANTS);
        List<String> ignoredComponents = new ArrayList<>(DEFAULT_IGNORED);

        static ConfigData from(SccConfig config) {
            ConfigData data = new ConfigData();
            data.maxDepth = config.maxDepth;
            data.maxNodes = config.maxNodes;
            data.maxMillis = config.maxMillis;
            data.forcedBaseItems = copyList(config.forcedBaseItems);
            data.preferredIngredients = copyList(config.preferredIngredients);
            data.blockedRecipes = copyList(config.blockedRecipes);
            data.blockedItems = copyList(config.blockedItems);
            data.blockedRecipeTypes = copyList(config.blockedRecipeTypes);
            data.nonConsumedItems = copyList(config.nonConsumedItems);
            data.preferSameModRecipes = config.preferSameModRecipes;
            data.preferredRecipeNamespaces = copyList(config.preferredRecipeNamespaces);
            data.autoAnalyse = config.autoAnalyse;
            data.saveAnalysis = config.saveAnalysis;
            data.analysisMillisPerTick = config.analysisMillisPerTick;
            data.analysisMillisWhenMenu = config.analysisMillisWhenMenu;
            data.analysisMillisWhenOpen = config.analysisMillisWhenOpen;
            data.analysisMillisPerFrame = config.analysisMillisPerFrame;
            data.variantComponents = copyList(config.variantComponents);
            data.ignoredComponents = copyList(config.ignoredComponents);
            return data;
        }
    }

    public synchronized void update(Collection<String> variants, Collection<String> ignored) {
        variantComponents = cleanList(variants);
        ignoredComponents = cleanList(ignored);
        variantExact = null;
        variantNamespaces = null;
        ignoredExact = null;
        ignoredNamespaces = null;
    }

    public boolean isVariantComponent(String componentId) {
        if (componentId == null) {
            return false;
        }
        buildVariantCache();
        if (variantExact.contains(componentId)) {
            return true;
        }
        int separator = componentId.indexOf(':');
        if (separator > 0 && variantNamespaces.contains(componentId.substring(0, separator))) {
            return true;
        }
        return !isIgnoredComponent(componentId);
    }

    public boolean isIgnoredComponent(String componentId) {
        if (componentId == null) {
            return true;
        }
        buildIgnoredCache();
        if (ignoredExact.contains(componentId)) {
            return true;
        }
        int separator = componentId.indexOf(':');
        return separator > 0 && ignoredNamespaces.contains(componentId.substring(0, separator));
    }


    public boolean isForcedBase(Item item) {
        if (item == null) {
            return false;
        }
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
        return key != null && forcedBaseItems.contains(key.toString());
    }

    public Set<Item> forcedBaseItemSet() {
        Set<Item> result = new LinkedHashSet<>();
        for (String id : forcedBaseItems) {
            Item item = itemById(id);
            if (item != null) {
                result.add(item);
            }
        }
        return Set.copyOf(result);
    }

    public List<Item> preferredIngredientList() {
        List<Item> result = new ArrayList<>();
        for (String id : preferredIngredients) {
            Item item = itemById(id);
            if (item != null) {
                result.add(item);
            }
        }
        return List.copyOf(result);
    }

    private static Item itemById(String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return null;
        }
        try {
            ResourceLocation id = ResourceLocation.parse(rawId);
            return BuiltInRegistries.ITEM.getOptional(id).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    public boolean isBlockedRecipe(String recipeId) {
        return matches(blockedRecipes, true, recipeId);
    }

    public boolean isBlockedItem(String encodedItem) {
        return matches(blockedItems, false, encodedItem);
    }

    public boolean isBlockedRecipeType(String recipeTypeId) {
        if (recipeTypeId == null) return false;
        List<String> source = cleanList(blockedRecipeTypes);
        Set<String> cached = blockedRecipeTypeSet;
        if (cached == null || !source.equals(blockedRecipeTypeValues)) {
            synchronized (this) {
                if (blockedRecipeTypeSet == null || !source.equals(blockedRecipeTypeValues)) {
                    blockedRecipeTypeValues = source;
                    blockedRecipeTypeSet = Set.copyOf(source);
                }
                cached = blockedRecipeTypeSet;
            }
        }
        return cached.contains(recipeTypeId);
    }

    public boolean hasBlockedItems() {
        return blockedItems != null && !blockedItems.isEmpty();
    }

    private boolean matches(List<String> source, boolean recipePatterns, String value) {
        if (value == null || source == null || source.isEmpty()) return false;
        List<String> values = cleanList(source);
        List<java.util.regex.Pattern> patterns = recipePatterns ? blockedRecipePatterns : blockedItemPatterns;
        List<String> cachedValues = recipePatterns ? blockedRecipePatternValues : blockedItemPatternValues;
        if (patterns == null || !values.equals(cachedValues)) {
            synchronized (this) {
                patterns = recipePatterns ? blockedRecipePatterns : blockedItemPatterns;
                cachedValues = recipePatterns ? blockedRecipePatternValues : blockedItemPatternValues;
                if (patterns == null || !values.equals(cachedValues)) {
                    List<java.util.regex.Pattern> compiled = new ArrayList<>();
                    for (String glob : values) {
                        if (glob != null && !glob.isBlank()) {
                            StringBuilder regex = new StringBuilder("^");
                            for (int i = 0; i < glob.length(); i++) {
                                char ch = glob.charAt(i);
                                if (ch == '*') regex.append(".*");
                                else if (ch == '?') regex.append('.');
                                else regex.append(java.util.regex.Pattern.quote(String.valueOf(ch)));
                            }
                            regex.append('$');
                            compiled.add(java.util.regex.Pattern.compile(regex.toString(), java.util.regex.Pattern.CASE_INSENSITIVE));
                        }
                    }
                    patterns = List.copyOf(compiled);
                    if (recipePatterns) {
                        blockedRecipePatternValues = values;
                        blockedRecipePatterns = patterns;
                    } else {
                        blockedItemPatternValues = values;
                        blockedItemPatterns = patterns;
                    }
                }
            }
        }
        for (java.util.regex.Pattern pattern : patterns) if (pattern.matcher(value).matches()) return true;
        return false;
    }

    public List<String> variantComponents() {
        return variantComponents;
    }

    public List<String> ignoredComponents() {
        return ignoredComponents;
    }

    private void buildVariantCache() {
        if (variantExact != null) {
            return;
        }
        Set<String> exact = new LinkedHashSet<>();
        Set<String> namespaces = new LinkedHashSet<>();
        for (String raw : cleanList(variantComponents)) {
            String value = raw.trim();
            if (value.endsWith(":*")) {
                namespaces.add(value.substring(0, value.length() - 2));
            } else if (value.contains(":")) {
                exact.add(value);
            } else {
                namespaces.add(value);
            }
        }
        variantExact = Set.copyOf(exact);
        variantNamespaces = Set.copyOf(namespaces);
    }

    private void buildIgnoredCache() {
        if (ignoredExact != null) {
            return;
        }
        Set<String> exact = new LinkedHashSet<>();
        Set<String> namespaces = new LinkedHashSet<>();
        for (String raw : cleanList(ignoredComponents)) {
            String value = raw.trim();
            if (value.endsWith(":*")) {
                namespaces.add(value.substring(0, value.length() - 2));
            } else if (value.contains(":")) {
                exact.add(value);
            } else {
                namespaces.add(value);
            }
        }
        ignoredExact = Set.copyOf(exact);
        ignoredNamespaces = Set.copyOf(namespaces);
    }

    private static List<String> cleanList(Collection<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> cleaned = new ArrayList<>(values.size());
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return List.copyOf(cleaned);
    }
}
