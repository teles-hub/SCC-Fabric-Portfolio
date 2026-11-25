package de.scc.calculator.calc;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recipe index reconstructed from SCC 1.6.5 bytecode.
 *
 * <p>The indexing, duplicate handling, blocked ingredient cleanup, cooking
 * fallback, and lookup behavior are based on the original class file. SCC's
 * config and optional integrations are represented by {@link RecipePolicy};
 * the Fabric adapter must provide a policy backed by the mod config.</p>
 */
public final class RecipeBook {
    private static final Set<String> VIEW_ONLY_TYPES = Set.of(
            "jeistuff:villager_trade",
            "jeistuff:wandering_trade"
    );
    private static final Set<String> COOKING_ALTERNATIVES = Set.of(
            "minecraft:blasting",
            "minecraft:smoking",
            "minecraft:campfire_cooking"
    );
    private static final Set<String> FAST_COOKING = Set.of(
            "minecraft:blasting",
            "minecraft:smoking"
    );
    private static final String SMELTING = "minecraft:smelting";

    private static volatile Map<String, List<ChemicalInput>> customChemicals = Map.of();

    private final Map<StackKey, List<SccRecipe>> byOutput = new HashMap<>();
    private final Map<String, SccRecipe> byId = new LinkedHashMap<>();
    private final Map<ResourceLocation, Integer> typeCounts = new LinkedHashMap<>();
    private final Set<StackKey> allItems = new LinkedHashSet<>();
    private final Map<String, Set<String>> catalysts = new LinkedHashMap<>();
    private final Map<String, Set<String>> machineCategories = new LinkedHashMap<>();
    private final Map<StackKey, Integer> inputUses = new HashMap<>();
    private final List<SccRecipe> pendingCooking = new ArrayList<>();
    private final Map<String, List<ChemicalInput>> chemicalInputs = new HashMap<>();

    private final RecipePolicy policy;
    private String source = "?";
    private Level world;
    private int shadowed;
    private int blocked;
    private Map<Item, List<StackKey>> variantsByItem;
    private int variantsBookSize = -1;

    public RecipeBook() {
        this(RecipePolicy.configured());
    }

    public RecipeBook(RecipePolicy policy) {
        this.policy = policy == null ? RecipePolicy.ALLOW_ALL : policy;
    }

    public void setWorld(Level world) {
        this.world = world;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public String source() {
        return source;
    }

    /** Returns true when this recipe ID already points at a vanilla crafting recipe of a compatible type. */
    public boolean duplicateOfCrafting(String recipeId, ResourceLocation incomingType) {
        SccRecipe existing = byId.get(recipeId);
        if (existing == null || !"minecraft:crafting".equals(existing.typeId().toString())) {
            return false;
        }
        if (existing.typeId().equals(incomingType)) {
            return true;
        }
        if (FAST_COOKING.contains(incomingType.toString())) {
            return false;
        }
        return !existing.typeId().getNamespace().equals(incomingType.getNamespace());
    }

    public void add(SccRecipe incoming) {
        SccRecipe recipe = incoming;
        if (recipe == null) {
            return;
        }
        if (recipe.inputs().isEmpty() && recipe.fluids().isEmpty() && !chemOnly(recipe)) {
            return;
        }
        if (VIEW_ONLY_TYPES.contains(recipe.typeId().toString())) {
            return;
        }

        if (byId.containsKey(recipe.id())) {
            SccRecipe existing = byId.get(recipe.id());
            if (existing.typeId().equals(recipe.typeId())) {
                return;
            }
            boolean oneIsFastCooking = FAST_COOKING.contains(recipe.typeId().toString())
                    || FAST_COOKING.contains(existing.typeId().toString());
            if (!oneIsFastCooking
                    && existing.typeId().getNamespace().equals(recipe.typeId().getNamespace())) {
                return;
            }

            String alternateId = recipe.id() + "#" + recipe.typeId().getPath();
            if (byId.containsKey(alternateId)) {
                return;
            }
            if (policy.isRecipeBlocked(recipe.id())) {
                blocked++;
                return;
            }
            recipe = recipe.withId(alternateId);
        }

        if (policy.isRecipeTypeBlocked(recipe.typeId().toString())) {
            if (COOKING_ALTERNATIVES.contains(recipe.typeId().toString())) {
                pendingCooking.add(recipe);
            }
            return;
        }
        if (policy.isRecipeBlocked(recipe.id())) {
            blocked++;
            return;
        }

        recipe = clean(recipe);
        if (recipe == null) {
            blocked++;
            return;
        }
        if (world != null && policy.isShadowed(world, recipe)) {
            shadowed++;
            blocked++;
            return;
        }
        if (noGain(recipe)) {
            blocked++;
            return;
        }

        index(recipe);
    }

    private void index(SccRecipe recipe) {
        byId.put(recipe.id(), recipe);
        byOutput.computeIfAbsent(recipe.output(), ignored -> new ArrayList<>()).add(recipe);
        typeCounts.merge(recipe.typeId(), 1, Integer::sum);
        allItems.add(recipe.output());
        for (SccRecipe.Input input : recipe.inputs()) {
            allItems.addAll(input.options());
            for (StackKey option : input.options()) {
                inputUses.merge(option, 1, Integer::sum);
            }
        }
    }

    private SccRecipe clean(SccRecipe recipe) {
        if (!policy.hasBlockedItems()) {
            return recipe;
        }
        if (policy.isItemBlocked(recipe.output().encode())) {
            return null;
        }

        List<SccRecipe.Input> cleanedInputs = null;
        for (int inputIndex = 0; inputIndex < recipe.inputs().size(); inputIndex++) {
            SccRecipe.Input input = recipe.inputs().get(inputIndex);
            List<StackKey> keptOptions = null;
            for (int optionIndex = 0; optionIndex < input.options().size(); optionIndex++) {
                StackKey option = input.options().get(optionIndex);
                boolean blockedOption = policy.isItemBlocked(option.encode());
                if (!blockedOption && keptOptions == null) {
                    continue;
                }
                if (keptOptions == null) {
                    keptOptions = new ArrayList<>(input.options().subList(0, optionIndex));
                }
                if (!blockedOption) {
                    keptOptions.add(option);
                }
            }

            if (keptOptions == null) {
                if (cleanedInputs != null) {
                    cleanedInputs.add(input);
                }
                continue;
            }
            if (keptOptions.isEmpty()) {
                return null;
            }
            if (cleanedInputs == null) {
                cleanedInputs = new ArrayList<>(recipe.inputs().subList(0, inputIndex));
            }
            cleanedInputs.add(new SccRecipe.Input(List.copyOf(keptOptions), input.count(), input.consumed()));
        }

        if (cleanedInputs == null) {
            return recipe;
        }
        return new SccRecipe(recipe.id(), recipe.typeId(), recipe.typeName(), recipe.output(),
                recipe.outputCount(), List.copyOf(cleanedInputs), recipe.fluids(), recipe.chance());
    }

    private static boolean noGain(SccRecipe recipe) {
        long requiredOutput = Math.max(1, recipe.outputCount());
        long selfConsumed = 0L;
        for (SccRecipe.Input input : recipe.inputs()) {
            if (!input.consumed() || input.options().isEmpty()) {
                continue;
            }
            boolean allOptionsAreOutput = true;
            for (StackKey option : input.options()) {
                if (!option.equals(recipe.output())) {
                    allOptionsAreOutput = false;
                    break;
                }
            }
            if (allOptionsAreOutput) {
                selfConsumed = selfConsumed > Long.MAX_VALUE - Math.max(1, input.count())
                        ? Long.MAX_VALUE : selfConsumed + Math.max(1, input.count());
            }
        }
        return selfConsumed > 0L && selfConsumed >= requiredOutput;
    }

    public static boolean isFastCooking(SccRecipe recipe) {
        return recipe != null && FAST_COOKING.contains(recipe.typeId().toString());
    }

    public static boolean cookingTwins(SccRecipe first, SccRecipe second) {
        if (first == null || second == null || first == second) {
            return false;
        }
        String firstType = first.typeId().toString();
        String secondType = second.typeId().toString();
        boolean firstCooking = SMELTING.equals(firstType) || FAST_COOKING.contains(firstType);
        boolean secondCooking = SMELTING.equals(secondType) || FAST_COOKING.contains(secondType);
        if (firstCooking && secondCooking && firstType.equals(secondType)) {
            return false;
        }
        return first.output().equals(second.output())
                && first.outputCount() == second.outputCount()
                && first.inputs().equals(second.inputs());
    }

    public int blockedCount() {
        return blocked;
    }

    public int shadowedCount() {
        return shadowed;
    }

    public void addCatalyst(String machine, String catalyst) {
        if (machine == null || catalyst == null || machine.isEmpty() || catalyst.isEmpty()) {
            return;
        }
        String normalizedCatalyst = policy.normalizeCatalystId(catalyst);
        if (normalizedCatalyst == null || normalizedCatalyst.isEmpty()) {
            return;
        }
        catalysts.computeIfAbsent(machine, ignored -> new LinkedHashSet<>()).add(normalizedCatalyst);
        machineCategories.computeIfAbsent(normalizedCatalyst, ignored -> new LinkedHashSet<>()).add(machine);
    }

    public Set<String> catalystsOf(String machine) {
        Set<String> base = catalysts.getOrDefault(machine, Set.of());
        if (!CatalystTags.any()) {
            return base;
        }
        Set<String> additions = CatalystTags.added(machine);
        Set<String> removals = CatalystTags.removed(machine);
        if (additions.isEmpty() && removals.isEmpty()) {
            return base;
        }
        Set<String> result = new LinkedHashSet<>(base);
        result.addAll(additions);
        result.removeAll(removals);
        return result;
    }

    public Set<String> categoriesForMachine(String machine) {
        Set<String> base = machineCategories.getOrDefault(machine, Set.of());
        if (!CatalystTags.any()) {
            return base;
        }
        Set<String> result = new LinkedHashSet<>(base);
        result.addAll(CatalystTags.categoriesOf(machine));
        result.removeIf(category -> CatalystTags.removed(machine).contains(category));
        return result;
    }

    public Map<String, Set<String>> catalysts() {
        return catalysts;
    }

    public void addChemicalInput(String recipeId, String chemicalId, long mbPerRun, List<StackKey> items) {
        if (recipeId == null || chemicalId == null || mbPerRun <= 0L || items == null) {
            return;
        }
        chemicalInputs.computeIfAbsent(recipeId, ignored -> new ArrayList<>(1))
                .add(new ChemicalInput(chemicalId, mbPerRun, List.copyOf(items)));
    }

    public boolean chemOnly(SccRecipe recipe) {
        if (recipe == null || !recipe.inputs().isEmpty() || !recipe.fluids().isEmpty()) {
            return false;
        }
        List<ChemicalInput> chemicalList = chemicalInputsOf(recipe.id());
        if (chemicalList == null || chemicalList.isEmpty()) {
            return false;
        }
        for (ChemicalInput input : chemicalList) {
            if (!input.items().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public boolean noItemInputs(SccRecipe recipe) {
        return recipe != null && (recipe.fluidOnly() || chemOnly(recipe));
    }

    public List<ChemicalInput> chemicalInputsOf(String recipeId) {
        List<ChemicalInput> local = chemicalInputs.get(recipeId);
        return local != null ? local : customChemicals.getOrDefault(recipeId, List.of());
    }

    public static void setCustomChemicals(Map<String, List<ChemicalInput>> chemicals) {
        customChemicals = chemicals == null || chemicals.isEmpty() ? Map.of() : Map.copyOf(chemicals);
    }

    public Map<String, List<ChemicalInput>> chemicalInputs() {
        return chemicalInputs;
    }

    private void adoptLonelyCooking() {
        if (pendingCooking.isEmpty()) {
            return;
        }
        for (SccRecipe pending : pendingCooking) {
            List<SccRecipe> sameOutput = byOutput.get(pending.output());
            boolean hasSmelting = false;
            if (sameOutput != null) {
                for (SccRecipe existing : sameOutput) {
                    if (SMELTING.equals(existing.typeId().toString())) {
                        hasSmelting = true;
                        break;
                    }
                }
            }
            if (hasSmelting) {
                continue;
            }

            SccRecipe adopted = pending;
            if (byId.containsKey(adopted.id())) {
                String ovenId = adopted.id() + "#oven";
                if (byId.containsKey(ovenId)) {
                    continue;
                }
                adopted = adopted.withId(ovenId);
            }
            index(adopted);
        }
        pendingCooking.clear();
    }

    public void sort() {
        adoptLonelyCooking();
        shadowed = 0;
        for (List<SccRecipe> recipes : byOutput.values()) {
            recipes.sort((left, right) -> left.id().compareTo(right.id()));
        }
    }

    public int inputUses(StackKey key) {
        return inputUses.getOrDefault(key, 0);
    }

    public List<SccRecipe> recipesFor(StackKey output) {
        return byOutput.getOrDefault(output, Collections.emptyList());
    }

    public List<SccRecipe> recipesFor(Item item) {
        return recipesFor(StackKey.of(item));
    }

    public SccRecipe byId(String id) {
        return id == null ? null : byId.get(id);
    }

    public Map<String, SccRecipe> all() {
        return byId;
    }

    public boolean isCraftable(StackKey key) {
        return byOutput.containsKey(key);
    }

    public boolean isCraftable(Item item) {
        return isCraftable(StackKey.of(item));
    }

    public StackKey orderKeyFor(StackKey key) {
        if (key == null) {
            return null;
        }
        if (isCraftable(key)) {
            return key;
        }
        if (!key.plain()) {
            if (!key.onlyModData()) {
                return null;
            }
            StackKey plainKey = StackKey.of(key.item());
            return isCraftable(plainKey) ? plainKey : null;
        }

        StackKey uniqueMatch = null;
        for (StackKey variant : craftableVariantsOf(key.item())) {
            if (!variant.onlyModData()) {
                continue;
            }
            if (uniqueMatch != null) {
                return null;
            }
            uniqueMatch = variant;
        }
        return uniqueMatch;
    }

    private List<StackKey> craftableVariantsOf(Item item) {
        if (variantsByItem == null || variantsBookSize != byOutput.size()) {
            Map<Item, List<StackKey>> rebuilt = new HashMap<>();
            for (StackKey output : byOutput.keySet()) {
                if (!output.plain()) {
                    rebuilt.computeIfAbsent(output.item(), ignored -> new ArrayList<>()).add(output);
                }
            }
            variantsByItem = rebuilt;
            variantsBookSize = byOutput.size();
        }
        return variantsByItem.getOrDefault(item, Collections.emptyList());
    }

    public Set<StackKey> allItems() {
        return allItems;
    }

    public Set<StackKey> craftableItems() {
        return byOutput.keySet();
    }

    public Map<ResourceLocation, Integer> typeCounts() {
        return typeCounts;
    }

    public int recipeCount() {
        return byId.size();
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }

    public record ChemicalInput(String chemical, long mbPerRun, List<StackKey> items) {
    }
}
