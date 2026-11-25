package de.scc.calculator.compat.cobblemon;

import de.scc.calculator.SccLog;
import de.scc.calculator.calc.StackKey;
import de.scc.calculator.platform.storage.SccItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.ShapedRecipePattern;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Fabric-side adapter for Cobblemon's Campfire Pot.
 *
 * <p>The original SCC 1.6.5 bridge was inspected with {@code javap -c -p}.
 * This port keeps its externally visible slot layout and transfer rules while
 * replacing NeoForge's IItemHandler with the project's SccItemHandler.
 * Cobblemon classes are intentionally discovered by name/reflection so this
 * integration remains optional and does not hard-link Cobblemon on startup.</p>
 */
public final class CobblemonPot {
    private static final String TILE = "com.cobblemon.mod.common.block.entity.CampfireBlockEntity";
    private static final String BETTER_TILE = "com.coolerpromc.bettercampfirepot.block.entity.BetterCampfireBlockEntity";
    private static final int RESULT = 0;
    private static final int GRID_FROM = 1;
    private static final int GRID = 9;
    private static final int SEASONING_FROM = 10;
    private static final int SEASONING_SLOTS = 3;
    private static final int SIZE = 13;

    private CobblemonPot() { }

    /** Recipe type ids accepted by Cobblemon 1.8.x and older datapacks. */
    public static boolean isCampfirePotRecipeType(ResourceLocation typeId) {
        return typeId != null && "cobblemon".equals(typeId.getNamespace())
                && ("campfire_pot".equals(typeId.getPath()) || "cooking_pot".equals(typeId.getPath())
                        || "cooking_pot_shapeless".equals(typeId.getPath()));
    }

    /** All standard coloured campfire pots used as SCC recipe catalysts. */
    public static List<ResourceLocation> catalystItems() {
        return List.of(
                ResourceLocation.fromNamespaceAndPath("cobblemon", "campfire_pot_red"),
                ResourceLocation.fromNamespaceAndPath("cobblemon", "campfire_pot_yellow"),
                ResourceLocation.fromNamespaceAndPath("cobblemon", "campfire_pot_green"),
                ResourceLocation.fromNamespaceAndPath("cobblemon", "campfire_pot_blue"),
                ResourceLocation.fromNamespaceAndPath("cobblemon", "campfire_pot_pink"),
                ResourceLocation.fromNamespaceAndPath("cobblemon", "campfire_pot_black"),
                ResourceLocation.fromNamespaceAndPath("cobblemon", "campfire_pot_white")
        );
    }

    private static boolean hasType(BlockEntity entity, String expectedName) {
        if (entity == null) return false;
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            if (expectedName.equals(type.getName())) return true;
        }
        return false;
    }

    private static boolean isPot(BlockEntity entity) {
        return entity instanceof Container container
                && hasType(entity, TILE)
                && container.getContainerSize() >= SIZE;
    }

    private static boolean isBetter(BlockEntity entity) {
        return hasType(entity, BETTER_TILE);
    }

    /**
     * Resolves Better Campfire Pot's three inventories by their semantic sizes,
     * not by an undocumented array order. Current 1.3.x exposes nine ingredient
     * slots, three seasoning slots and a single result slot. Both arrays and
     * iterable return values are accepted to tolerate Java/Kotlin API wrappers.
     */
    private static Container[] betterContainers(BlockEntity entity) {
        try {
            Object result = entity.getClass().getMethod("getContainers").invoke(entity);
            List<Container> found = new ArrayList<>();
            if (result instanceof Iterable<?> iterable) {
                for (Object value : iterable) {
                    if (value instanceof Container container) found.add(container);
                }
            } else if (result != null && result.getClass().isArray()) {
                int length = Array.getLength(result);
                for (int i = 0; i < length; i++) {
                    Object value = Array.get(result, i);
                    if (value instanceof Container container) found.add(container);
                }
            }

            Container input = null;
            Container seasoning = null;
            Container output = null;
            for (Container container : found) {
                int size = container.getContainerSize();
                if (size >= GRID && (input == null || size > input.getContainerSize())) {
                    input = container;
                } else if (size >= SEASONING_SLOTS
                        && (seasoning == null || size < seasoning.getContainerSize())) {
                    seasoning = container;
                } else if (size >= 1 && output == null) {
                    output = container;
                }
            }
            if (input != null && seasoning != null && output != null
                    && input != seasoning && input != output && seasoning != output
                    && input.getContainerSize() >= GRID
                    && seasoning.getContainerSize() >= SEASONING_SLOTS
                    && output.getContainerSize() >= 1) {
                return new Container[]{input, seasoning, output};
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Optional Better Campfire Pot integration. A missing/incompatible
            // method means this entity is not exposed as an SCC inventory.
        }
        return null;
    }

    public static ResourceLocation machineAlias(Level level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) return null;
        BlockEntity entity = level.getBlockEntity(pos);
        if (!isPot(entity) && !isBetter(entity)) return null;
        try {
            Object value = entity.getClass().getMethod("getPotItem").invoke(entity);
            if (value instanceof ItemStack stack && !stack.isEmpty()) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (key != null) return key;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Optional getter may not exist on third-party pot tiers.
        }
        // Better Campfire Pot tiers may not expose getPotItem(). Use their block
        // registry id instead of incorrectly aliasing every tier as Cobblemon's red pot.
        try {
            Object state = entity.getClass().getMethod("getBlockState").invoke(entity);
            Object block = state.getClass().getMethod("getBlock").invoke(state);
            ResourceLocation key = BuiltInRegistries.BLOCK.getKey((net.minecraft.world.level.block.Block) block);
            if (key != null) return key;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Older BlockEntity test fixtures and unusual wrappers may not expose state.
        }
        return ResourceLocation.fromNamespaceAndPath("cobblemon", "campfire_pot_red");
    }

    /**
     * Returns a virtual inventory for a server-side Cobblemon Campfire Pot.
     * Slot 0 is the result, slots 1-9 are the 3x3 ingredients, and slots 10-12
     * are Cobblemon 1.8.x seasoning inputs. The virtual layout is kept identical
     * for the optional Better Campfire Pot adapter.
     */
    public static SccItemHandler items(Level level, BlockPos pos,
                                       Supplier<String> recipeId,
                                       Supplier<Set<StackKey>> inputs,
                                       Supplier<StackKey> output) {
        if (level == null || level.isClientSide || pos == null || !level.isLoaded(pos)) return null;
        BlockEntity entity = level.getBlockEntity(pos);
        if (isBetter(entity)) {
            Container[] containers = betterContainers(entity);
            return containers == null ? null : new BetterView(entity, containers, safeSupplier(inputs));
        }
        if (!isPot(entity)) return null;
        return new View(level, entity, (Container) entity, safeSupplier(recipeId),
                safeSupplier(inputs), safeSupplier(output));
    }

    private static <T> Supplier<T> safeSupplier(Supplier<T> source) {
        return source == null ? () -> null : source;
    }

    private static boolean isPotRecipe(Recipe<?> recipe) {
        if (recipe == null) return false;

        // Prefer the registered recipe type when available. Cobblemon 1.8.x
        // uses the campfire-pot datapack category; the class-name fallback keeps
        // compatibility with minor API/package changes without linking Cobblemon.
        ResourceLocation typeId = BuiltInRegistries.RECIPE_TYPE.getKey(recipe.getType());
        if (isCampfirePotRecipeType(typeId)) return true;

        String className = recipe.getClass().getName();
        return className.startsWith("com.cobblemon.mod.common.")
                && (className.contains("CookingPot") || className.contains("CampfirePot"));
    }

    /** Resolves the 3x3 input layout for the current SCC recipe selection. */
    static ItemStack[] layout(Level level, String recipeId, Set<StackKey> availableInputs, StackKey output) {
        if (level == null) return null;
        Recipe<?> selected = null;
        String baseId = recipeId;
        if (baseId != null) {
            int suffix = baseId.indexOf('#');
            if (suffix >= 0) baseId = baseId.substring(0, suffix);
            ResourceLocation parsed = ResourceLocation.tryParse(baseId);
            if (parsed != null) {
                selected = level.getRecipeManager().byKey(parsed)
                        .map(RecipeHolder::value)
                        .filter(CobblemonPot::isPotRecipe)
                        .orElse(null);
            }
        }

        Set<StackKey> chosenInputs = availableInputs == null ? Set.of() : availableInputs;
        if (selected == null && output != null) {
            for (RecipeHolder<?> holder : level.getRecipeManager().getRecipes()) {
                Recipe<?> candidate = holder.value();
                if (!isPotRecipe(candidate)) continue;
                ItemStack result = candidate.getResultItem(level.registryAccess());
                if (!output.matches(result)) continue;
                selected = candidate;
                if (coveredBy(candidate, chosenInputs)) break;
                selected = null;
            }
        }
        if (selected == null) return null;

        ItemStack[] grid = new ItemStack[GRID];
        Arrays.fill(grid, ItemStack.EMPTY);
        ShapedRecipePattern pattern = patternOf(selected);
        if (pattern != null) {
            for (int row = 0; row < pattern.height(); row++) {
                for (int col = 0; col < pattern.width(); col++) {
                    int destination = row * 3 + col;
                    int ingredientIndex = row * pattern.width() + col;
                    if (destination >= GRID || ingredientIndex >= pattern.ingredients().size()) return null;
                    grid[destination] = pick(pattern.ingredients().get(ingredientIndex), chosenInputs);
                }
            }
        } else {
            int index = 0;
            for (Ingredient ingredient : selected.getIngredients()) {
                if (ingredient == null || ingredient.isEmpty()) continue;
                if (index >= GRID) return null;
                grid[index++] = pick(ingredient, chosenInputs);
            }
        }
        return grid;
    }

    private static ShapedRecipePattern patternOf(Recipe<?> recipe) {
        try {
            Method method = recipe.getClass().getMethod("getPattern");
            Object value = method.invoke(recipe);
            return value instanceof ShapedRecipePattern pattern ? pattern : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static boolean coveredBy(Recipe<?> recipe, Set<StackKey> available) {
        for (Ingredient ingredient : recipe.getIngredients()) {
            if (ingredient != null && !ingredient.isEmpty() && pickFrom(ingredient, available) == null) {
                return false;
            }
        }
        return true;
    }

    private static ItemStack pick(Ingredient ingredient, Set<StackKey> available) {
        if (ingredient == null || ingredient.isEmpty()) return ItemStack.EMPTY;
        ItemStack matched = pickFrom(ingredient, available);
        if (matched != null) return matched;
        ItemStack[] alternatives = ingredient.getItems();
        return alternatives.length == 0 ? ItemStack.EMPTY : alternatives[0].copyWithCount(1);
    }

    private static ItemStack pickFrom(Ingredient ingredient, Set<StackKey> available) {
        if (available == null || available.isEmpty()) return null;
        for (StackKey key : available) {
            ItemStack stack = key.stack(1);
            if (ingredient.test(stack)) return stack;
        }
        return null;
    }

    public static String describe(Level level, BlockPos pos) {
        if (level == null) return "no level";
        BlockEntity entity = level.getBlockEntity(pos);
        SccItemHandler handler = items(level, pos, () -> null, Set::of, () -> null);
        if (handler != null) {
            StringBuilder summary = new StringBuilder();
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack stack = handler.getStackInSlot(slot);
                if (stack.isEmpty()) continue;
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (summary.length() > 0) summary.append(',');
                summary.append(slot).append(':').append(key == null ? "?" : key.getPath())
                        .append('x').append(stack.getCount());
            }
            return summary.toString();
        }
        if (isPot(entity)) {
            Container container = (Container) entity;
            StringBuilder summary = new StringBuilder();
            for (int slot = 0; slot < GRID_FROM + GRID + 3; slot++) {
                ItemStack stack = container.getItem(slot);
                if (stack.isEmpty()) continue;
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
                if (summary.length() > 0) summary.append(',');
                summary.append(slot).append(':').append(key == null ? "?" : key.getPath())
                        .append('x').append(stack.getCount());
            }
            return summary.toString();
        }
        return pos == null ? "not a campfire pot" : pos.toShortString() + " (not a campfire pot)";
    }

    private static void closeLid(BlockEntity entity) {
        try {
            // Verified against the Cobblemon 1.8.4 Fabric binary:
            // public final void toggleLid(boolean).
            try {
                entity.getClass().getMethod("toggleLid", boolean.class).invoke(entity, false);
            } catch (NoSuchMethodException missingSingleArgumentOverload) {
                // Compatibility fallback for third-party forks exposing a position overload.
                entity.getClass().getMethod("toggleLid", boolean.class, BlockPos.class)
                        .invoke(entity, false, entity.getBlockPos());
            }
        } catch (ReflectiveOperationException | RuntimeException error) {
            SccLog.warn("cobblemon", "Could not close Campfire Pot lid at " + entity.getBlockPos().toShortString());
        }
    }

    private static int simpleRoom(Container container, int slot, ItemStack stack) {
        if (container == null || stack == null || stack.isEmpty()
                || slot < 0 || slot >= container.getContainerSize()
                || !container.canPlaceItem(slot, stack)) return 0;
        ItemStack current = container.getItem(slot);
        if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, stack)) return 0;
        int limit = Math.min(container.getMaxStackSize(), stack.getMaxStackSize());
        return Math.max(0, limit - current.getCount());
    }

    private static ItemStack insertSimple(Container container, BlockEntity entity, int slot,
                                          ItemStack stack, boolean simulate) {
        int moved = Math.min(stack.getCount(), simpleRoom(container, slot, stack));
        if (moved <= 0) return stack;
        if (!simulate) {
            ItemStack current = container.getItem(slot);
            container.setItem(slot, current.isEmpty() ? stack.copyWithCount(moved)
                    : current.copyWithCount(current.getCount() + moved));
            container.setChanged();
            closeLid(entity);
        }
        return moved >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - moved);
    }

    private static final class View implements SccItemHandler {
        private final Level level;
        private final BlockEntity entity;
        private final Container pot;
        private final Supplier<String> recipeId;
        private final Supplier<Set<StackKey>> inputs;
        private final Supplier<StackKey> output;

        private View(Level level, BlockEntity entity, Container pot, Supplier<String> recipeId,
                     Supplier<Set<StackKey>> inputs, Supplier<StackKey> output) {
            this.level = level;
            this.entity = entity;
            this.pot = pot;
            this.recipeId = recipeId;
            this.inputs = inputs;
            this.output = output;
        }

        private Set<StackKey> availableInputs() {
            Set<StackKey> available = inputs.get();
            return available == null ? Set.of() : available;
        }

        private ItemStack[] wanted() {
            // Re-resolve on every query: SCC can change the selected recipe while
            // retaining the same virtual handler instance.
            return layout(level, recipeId.get(), availableInputs(), output.get());
        }

        private boolean selectedInput(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return false;
            for (StackKey key : availableInputs()) {
                if (key.matches(stack)) return true;
            }
            return false;
        }

        private boolean gridFits(ItemStack[] layout) {
            for (int index = 0; index < GRID; index++) {
                ItemStack current = pot.getItem(GRID_FROM + index);
                if (current.isEmpty()) continue;
                ItemStack expected = layout[index];
                if (expected.isEmpty() || !ItemStack.isSameItemSameComponents(current, expected)) return false;
            }
            return true;
        }

        private int room(int slot, ItemStack[] layout) {
            ItemStack expected = layout[slot];
            ItemStack current = pot.getItem(GRID_FROM + slot);
            int room = Math.min(pot.getMaxStackSize(), expected.getMaxStackSize()) - current.getCount();
            int leastDuplicate = Integer.MAX_VALUE;
            for (int other = 0; other < GRID; other++) {
                if (other == slot || layout[other].isEmpty()
                        || !ItemStack.isSameItemSameComponents(layout[other], expected)) continue;
                leastDuplicate = Math.min(leastDuplicate, pot.getItem(GRID_FROM + other).getCount());
            }
            if (leastDuplicate != Integer.MAX_VALUE) {
                room = Math.min(room, leastDuplicate + 1 - current.getCount());
            }
            return Math.max(0, room);
        }

        @Override public int getSlots() { return SIZE; }

        @Override public ItemStack getStackInSlot(int slot) {
            return slot < 0 || slot >= SIZE ? ItemStack.EMPTY : pot.getItem(slot);
        }

        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack == null || stack.isEmpty()) return stack == null ? ItemStack.EMPTY : stack;

            // Cobblemon 1.8.x has three real seasoning slots after its 3x3 grid.
            // Let the container apply Cobblemon's own slot validation where present.
            if (slot >= SEASONING_FROM && slot < SEASONING_FROM + SEASONING_SLOTS) {
                return insertSimple(pot, entity, slot, stack, simulate);
            }

            if (slot < GRID_FROM || slot >= GRID_FROM + GRID) return stack;
            ItemStack[] layout = wanted();
            if (layout == null || !gridFits(layout)) return stack;
            int expectedIndex = slot - GRID_FROM;
            ItemStack expected = layout[expectedIndex];
            if (expected.isEmpty() || !ItemStack.isSameItemSameComponents(expected, stack)
                    || !selectedInput(stack)) return stack;
            int moved = Math.min(stack.getCount(), room(expectedIndex, layout));
            if (moved <= 0) return stack;
            if (!simulate) {
                ItemStack current = pot.getItem(slot);
                pot.setItem(slot, current.isEmpty() ? stack.copyWithCount(moved)
                        : current.copyWithCount(current.getCount() + moved));
                pot.setChanged();
                closeLid(entity);
            }
            return moved >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - moved);
        }

        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot != RESULT || amount <= 0) return ItemStack.EMPTY;
            ItemStack current = pot.getItem(RESULT);
            if (current.isEmpty()) return ItemStack.EMPTY;
            int moved = Math.min(amount, current.getCount());
            ItemStack result = current.copyWithCount(moved);
            if (!simulate) {
                pot.removeItem(RESULT, moved);
                pot.setChanged();
            }
            return result;
        }

        @Override public int getSlotLimit(int slot) { return pot.getMaxStackSize(); }

        @Override public boolean isItemValid(int slot, ItemStack stack) {
            if (stack == null || stack.isEmpty()) return false;
            if (slot >= SEASONING_FROM && slot < SEASONING_FROM + SEASONING_SLOTS) {
                return pot.canPlaceItem(slot, stack) && simpleRoom(pot, slot, stack) > 0;
            }
            if (slot < GRID_FROM || slot >= GRID_FROM + GRID) return false;
            ItemStack[] layout = wanted();
            return layout != null && ItemStack.isSameItemSameComponents(layout[slot - GRID_FROM], stack)
                    && selectedInput(stack);
        }

        @Override public void setStackInSlot(int slot, ItemStack stack) {
            throw new UnsupportedOperationException("Campfire Pot view only supports validated insert/extract operations");
        }
    }

    private static final class BetterView implements SccItemHandler {
        private final BlockEntity entity;
        private final Container input;
        private final Container seasoning;
        private final Container result;
        private final Supplier<Set<StackKey>> inputs;

        private BetterView(BlockEntity entity, Container[] containers, Supplier<Set<StackKey>> inputs) {
            this.entity = entity;
            this.input = containers[0];
            this.seasoning = containers[1];
            this.result = containers[2];
            this.inputs = inputs;
        }

        private boolean wanted(ItemStack stack) {
            if (stack == null || stack.isEmpty()) return false;
            Set<StackKey> keys = inputs.get();
            if (keys == null || keys.isEmpty()) return false;
            for (StackKey key : keys) if (key.matches(stack)) return true;
            return false;
        }

        @Override public int getSlots() { return SIZE; }

        @Override public ItemStack getStackInSlot(int slot) {
            if (slot == RESULT) return result.getItem(0);
            if (slot >= GRID_FROM && slot < GRID_FROM + GRID) {
                int index = slot - GRID_FROM;
                return index < input.getContainerSize() ? input.getItem(index) : ItemStack.EMPTY;
            }
            if (slot >= SEASONING_FROM && slot < SEASONING_FROM + SEASONING_SLOTS) {
                int index = slot - SEASONING_FROM;
                return index < seasoning.getContainerSize() ? seasoning.getItem(index) : ItemStack.EMPTY;
            }
            return ItemStack.EMPTY;
        }

        @Override public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (stack == null || stack.isEmpty()) return stack == null ? ItemStack.EMPTY : stack;
            if (slot >= SEASONING_FROM && slot < SEASONING_FROM + SEASONING_SLOTS) {
                int index = slot - SEASONING_FROM;
                return insertSimple(seasoning, entity, index, stack, simulate);
            }
            if (slot < GRID_FROM || slot >= GRID_FROM + GRID) return stack;
            int index = slot - GRID_FROM;
            if (index >= input.getContainerSize() || !wanted(stack) || !input.canPlaceItem(index, stack)) return stack;
            ItemStack current = input.getItem(index);
            if (!current.isEmpty() && !ItemStack.isSameItemSameComponents(current, stack)) return stack;
            int moved = Math.min(stack.getCount(), simpleRoom(input, index, stack));
            if (moved <= 0) return stack;
            if (!simulate) {
                input.setItem(index, current.isEmpty() ? stack.copyWithCount(moved)
                        : current.copyWithCount(current.getCount() + moved));
                input.setChanged();
                closeLid(entity);
            }
            return moved >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - moved);
        }

        @Override public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (slot != RESULT || amount <= 0) return ItemStack.EMPTY;
            ItemStack current = result.getItem(0);
            if (current.isEmpty()) return ItemStack.EMPTY;
            int moved = Math.min(amount, current.getCount());
            ItemStack extracted = current.copyWithCount(moved);
            if (!simulate) { result.removeItem(0, moved); result.setChanged(); }
            return extracted;
        }

        @Override public int getSlotLimit(int slot) {
            if (slot == RESULT) return result.getMaxStackSize();
            if (slot >= GRID_FROM && slot < GRID_FROM + GRID) return input.getMaxStackSize();
            if (slot >= SEASONING_FROM && slot < SEASONING_FROM + SEASONING_SLOTS) return seasoning.getMaxStackSize();
            return 0;
        }

        @Override public boolean isItemValid(int slot, ItemStack stack) {
            if (stack == null || stack.isEmpty()) return false;
            if (slot >= SEASONING_FROM && slot < SEASONING_FROM + SEASONING_SLOTS) {
                int index = slot - SEASONING_FROM;
                return index < seasoning.getContainerSize() && seasoning.canPlaceItem(index, stack)
                        && simpleRoom(seasoning, index, stack) > 0;
            }
            if (slot < GRID_FROM || slot >= GRID_FROM + GRID) return false;
            int index = slot - GRID_FROM;
            return index < input.getContainerSize() && input.canPlaceItem(index, stack) && wanted(stack)
                    && simpleRoom(input, index, stack) > 0;
        }

        @Override public void setStackInSlot(int slot, ItemStack stack) {
            throw new UnsupportedOperationException("Better Campfire Pot view only supports validated insert/extract operations");
        }
    }
}
