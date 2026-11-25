package de.scc.calculator.compat.cobblemon;

import de.scc.calculator.SccLog;
import de.scc.calculator.calc.StackKey;
import de.scc.calculator.network.SccDurableMaterialEscrow;
import de.scc.calculator.network.SccNetworkCache;
import de.scc.calculator.network.SccNetworkView;
import de.scc.calculator.network.SccItemStore;
import de.scc.calculator.platform.SccMachineProcessor;
import de.scc.calculator.platform.storage.SccItemHandler;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Real, idempotent machine processor for Cobblemon's Campfire Pot.
 *
 * <p>The SCC scheduler polls {@link #state} every five ticks, so this processor
 * never mutates the world there. Ingredients are placed, the durable escrow is
 * consumed and Cobblemon's own {@code consumeCraftingIngredients} is invoked
 * exactly once inside {@link #dispatch}. The resulting output snapshot is
 * persisted in the dimension's {@link SccCobblemonPotLedger} and stays in the
 * pot until {@link #acknowledgeOutputs}, which is called only after the
 * scheduler has journaled its SCC storage receipts.</p>
 */
public final class SccCobblemonCampfirePotProcessor implements SccMachineProcessor {
    public static final SccCobblemonCampfirePotProcessor INSTANCE = new SccCobblemonCampfirePotProcessor();

    private static final String RECIPE_BASE = "com.cobblemon.mod.common.item.crafting.CookingPotRecipeBase";
    private static final String CONSUME_METHOD = "consumeCraftingIngredients";
    private static final int GRID_FROM = 1;
    private static final int GRID = 9;
    private static final int SEASONING_FROM = 10;
    private static final int SEASONING_SLOTS = 3;
    private static final int RESULT_SLOT = 0;

    public SccCobblemonCampfirePotProcessor() { }

    @Override
    public Validation validate(Level level, BlockPos position, Operation operation) {
        String reason = planError(level, position, operation);
        return reason == null ? Validation.accept() : Validation.reject(reason);
    }

    @Override
    public DispatchResult dispatch(Level level, BlockPos position, Operation operation) {
        String reason = planError(level, position, operation);
        if (reason != null) return DispatchResult.rejected(reason);

        SccCobblemonPotLedger ledger = SccCobblemonPotLedger.get(level);
        if (ledger == null) return DispatchResult.unknown("pot_ledger_unavailable");
        String digest = digest(operation);
        String machineKey = position.getX() + "," + position.getY() + "," + position.getZ();
        SccCobblemonPotLedger.Entry existing = ledger.byOperationId(operation.operationId());
        if (existing != null) {
            if (!existing.digest().equals(digest) || !existing.machineKey().equals(machineKey)) {
                return DispatchResult.rejected("operation_payload_mismatch");
            }
            return switch (existing.phase()) {
                case ACKNOWLEDGED, OUTPUT_READY -> DispatchResult.accepted();
                case FAILED -> DispatchResult.unknown(existing.reason().isEmpty()
                        ? "pot_operation_failed" : existing.reason());
                case PREPARING -> resume(level, position, operation, ledger);
            };
        }
        if (!ledger.begin(operation.operationId(), digest, machineKey)) {
            return DispatchResult.unknown("pot_ledger_rejected_intent");
        }
        return resume(level, position, operation, ledger);
    }

    @Override
    public OperationState state(Level level, BlockPos position, String operationId) {
        SccCobblemonPotLedger.Entry entry = entry(level, operationId);
        if (entry == null) return OperationState.UNKNOWN;
        return switch (entry.phase()) {
            case PREPARING -> OperationState.QUEUED;
            case OUTPUT_READY, ACKNOWLEDGED -> OperationState.OUTPUT_READY;
            case FAILED -> OperationState.FAILED;
        };
    }

    @Override
    public OutputSnapshot outputs(Level level, BlockPos position, String operationId) {
        SccCobblemonPotLedger.Entry entry = entry(level, operationId);
        if (entry == null || entry.phase() == SccCobblemonPotLedger.Phase.ACKNOWLEDGED
                || entry.phase() == SccCobblemonPotLedger.Phase.FAILED
                || entry.products().isEmpty()) {
            return new OutputSnapshot(operationId == null ? "unknown" : operationId, List.of(), false);
        }
        List<Product> products = new ArrayList<>();
        for (SccCobblemonPotLedger.Product product : entry.products()) {
            products.add(new Product(product.itemKey(), product.amount()));
        }
        boolean complete = entry.phase() == SccCobblemonPotLedger.Phase.OUTPUT_READY
                && resultMatches(level, position, products);
        return new OutputSnapshot(operationId, products, complete);
    }

    @Override
    public boolean acknowledgeOutputs(Level level, BlockPos position, String operationId) {
        SccCobblemonPotLedger ledger = SccCobblemonPotLedger.get(level);
        if (ledger == null) return false;
        SccCobblemonPotLedger.Entry entry = ledger.byOperationId(operationId);
        if (entry == null) return false;
        if (entry.phase() == SccCobblemonPotLedger.Phase.ACKNOWLEDGED) return true;
        if (entry.phase() != SccCobblemonPotLedger.Phase.OUTPUT_READY) return false;

        BlockEntity entity = level == null || position == null ? null : level.getBlockEntity(position);
        if (entity instanceof Container container) {
            if (container.getContainerSize() > RESULT_SLOT) {
                ItemStack result = container.getItem(RESULT_SLOT);
                if (!result.isEmpty()) container.removeItem(RESULT_SLOT, result.getCount());
            }
            Set<Integer> usedSlots = new HashSet<>();
            for (SccCobblemonPotLedger.Placement placement : entry.placements()) {
                int slot = placement.slot();
                if (slot < GRID_FROM || slot >= GRID_FROM + GRID || !usedSlots.add(slot)) continue;
                if (slot >= container.getContainerSize()) continue;
                StackKey key = StackKey.decode(placement.itemKey());
                ItemStack current = container.getItem(slot);
                if (key != null && key.matches(current)) {
                    container.removeItem(slot, Math.min(current.getCount(), (int) Math.min(placement.amount(), Integer.MAX_VALUE)));
                }
            }
            container.setChanged();
        }
        return ledger.markAcknowledged(operationId);
    }

    private static SccCobblemonPotLedger.Entry entry(Level level, String operationId) {
        SccCobblemonPotLedger ledger = SccCobblemonPotLedger.peek(level);
        return ledger == null ? null : ledger.byOperationId(operationId);
    }

    private DispatchResult resume(Level level, BlockPos position, Operation operation,
                                   SccCobblemonPotLedger ledger) {
        String operationId = operation.operationId();
        SccCobblemonPotLedger.Entry current = ledger.byOperationId(operationId);
        if (current == null || current.phase() != SccCobblemonPotLedger.Phase.PREPARING) {
            return DispatchResult.unknown("pot_ledger_state_changed");
        }

        StackKey outputKey = StackKey.decode(operation.products().get(0).itemKey());
        Map<String, StackKey> ingredientKeys = new LinkedHashMap<>();
        for (Ingredient ingredient : operation.ingredients()) {
            StackKey key = StackKey.decode(ingredient.itemKey());
            if (key == null) return fail(ledger, operationId, "ingredient_key_invalid");
            ingredientKeys.putIfAbsent(ingredient.itemKey(), key);
        }
        if (outputKey == null) return fail(ledger, operationId, "product_key_invalid");

        BlockEntity entity = level.getBlockEntity(position);
        if (!(entity instanceof Container container)) return fail(ledger, operationId, "pot_container_missing");
        if (!container.getItem(RESULT_SLOT).isEmpty() && current.phase() == SccCobblemonPotLedger.Phase.PREPARING) {
            return fail(ledger, operationId, "pot_result_slot_occupied");
        }
        Set<StackKey> available = new HashSet<>(ingredientKeys.values());
        SccItemHandler handler = CobblemonPot.items(level, position, () -> operation.recipeId(),
                () -> available, () -> outputKey);
        if (handler == null || handler.getSlots() < SEASONING_FROM + SEASONING_SLOTS) {
            return fail(ledger, operationId, "pot_handler_unavailable");
        }

        Map<Integer, Integer> planned = planSlots(handler, level, operation, ingredientKeys, outputKey);
        if (planned == null || planned.size() != operation.ingredients().size()) {
            return fail(ledger, operationId, "pot_slot_plan_rejected");
        }
        current = ledger.byOperationId(operationId);
        Set<String> recorded = new HashSet<>();
        for (SccCobblemonPotLedger.Placement placement : current.placements()) {
            recorded.add(placement.itemKey() + "@" + placement.slot());
        }
        for (Map.Entry<Integer, Integer> slotPlan : planned.entrySet()) {
            int slot = slotPlan.getKey();
            Ingredient ingredient = operation.ingredients().get(slotPlan.getValue());
            StackKey key = ingredientKeys.get(ingredient.itemKey());
            String receiptKey = ingredient.itemKey() + "@" + slot;
            if (recorded.contains(receiptKey)) continue;
            ItemStack stack = key.stack((int) ingredient.amount());
            ItemStack inPot = handler.getStackInSlot(slot);
            boolean alreadyPresent = !inPot.isEmpty() && key.matches(inPot) && inPot.getCount() >= 1;
            if (!alreadyPresent && !handler.insertItem(slot, stack, true).isEmpty()) {
                return fail(ledger, operationId, "pot_slot_insert_rejected");
            }
            if (!alreadyPresent && !handler.insertItem(slot, stack, false).isEmpty()) {
                return fail(ledger, operationId, "pot_slot_insert_incomplete");
            }
            if (!ledger.place(operationId, new SccCobblemonPotLedger.Placement(
                    slot, ingredient.itemKey(), ingredient.amount(), ingredient.consumed()))) {
                return fail(ledger, operationId, "pot_placement_not_persisted");
            }
        }

        current = ledger.byOperationId(operationId);
        if (!current.escrowConsumed()) {
            SccNetworkView network = SccNetworkCache.view(level, position);
            List<SccItemStore> stores = network == null ? List.of() : network.itemStores();
            if (!SccDurableMaterialEscrow.consume(operationId, stores)) {
                return DispatchResult.unknown("escrow_consume_failed");
            }
            ledger.markEscrowConsumed(operationId);
        }

        Recipe<?> recipe = resolveRecipe(level, operation.recipeId());
        if (recipe == null || !isCookingPotRecipe(recipe.getClass())) {
            return fail(ledger, operationId, "pot_recipe_unavailable");
        }
        Method consume = findMethod(entity.getClass(), CONSUME_METHOD, 5);
        if (consume == null) return fail(ledger, operationId, "pot_consume_api_missing");
        try {
            consume.setAccessible(true);
            Object blockState = entity.getBlockState();
            consume.invoke(entity, recipe, level, position, blockState, entity);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException error) {
            SccLog.warn("cobblemon", "Campfire Pot consume failed at " + position.toShortString());
            return fail(ledger, operationId, "pot_consume_failed");
        }

        List<ItemStack> seasonings = seasonings(entity);
        ItemStack result = recipeResult(recipe, seasonings);
        Product expected = operation.products().get(0);
        if (result == null || result.isEmpty() || result.getCount() != expected.amount()
                || !outputKey.matches(result)) {
            return fail(ledger, operationId, "pot_result_mismatch");
        }
        container.setItem(RESULT_SLOT, result.copy());
        container.setChanged();
        List<SccCobblemonPotLedger.Product> persistedProducts = operation.products().stream()
                .map(product -> new SccCobblemonPotLedger.Product(product.itemKey(), product.amount()))
                .toList();
        if (!ledger.markReady(operationId, persistedProducts)) {
            return fail(ledger, operationId, "pot_output_not_persisted");
        }
        return DispatchResult.accepted();
    }

    private DispatchResult fail(SccCobblemonPotLedger ledger, String operationId, String reason) {
        ledger.markFailed(operationId, reason);
        return DispatchResult.unknown(reason);
    }

    private static Map<Integer, Integer> planSlots(SccItemHandler handler, Level level, Operation operation,
                                                   Map<String, StackKey> keys, StackKey outputKey) {
        Map<Integer, Integer> plan = new LinkedHashMap<>();
        Set<Integer> usedIngredients = new HashSet<>();
        Set<StackKey> available = new HashSet<>(keys.values());
        ItemStack[] layout = CobblemonPot.layout(level, operation.recipeId(), available, outputKey);
        if (layout != null) {
            for (int index = 0; index < GRID; index++) {
                ItemStack wanted = layout[index];
                if (wanted.isEmpty()) continue;
                int found = -1;
                for (int i = 0; i < operation.ingredients().size(); i++) {
                    if (usedIngredients.contains(i)) continue;
                    StackKey key = keys.get(operation.ingredients().get(i).itemKey());
                    if (key != null && key.matches(wanted)) { found = i; break; }
                }
                if (found < 0) return null;
                plan.put(GRID_FROM + index, found);
                usedIngredients.add(found);
            }
        }
        // Consumable planner ingredients occupy only the 3x3 grid. Slots 10-12
        // are Cobblemon's seasoning inputs: they are not consumed by
        // consumeCraftingIngredients and must never receive a planned ingredient.
        for (int i = 0; i < operation.ingredients().size(); i++) {
            if (usedIngredients.contains(i)) continue;
            StackKey key = keys.get(operation.ingredients().get(i).itemKey());
            ItemStack stack = key.stack((int) operation.ingredients().get(i).amount());
            int slot = -1;
            for (int candidate = GRID_FROM; candidate < GRID_FROM + GRID; candidate++) {
                if (!plan.containsKey(candidate) && handler.isItemValid(candidate, stack)) {
                    slot = candidate;
                    break;
                }
            }
            if (slot < 0) return null;
            plan.put(slot, i);
            usedIngredients.add(i);
        }
        return plan;
    }

    private static boolean resultMatches(Level level, BlockPos position, List<Product> products) {
        if (products.size() != 1) return false;
        BlockEntity entity = level == null || position == null ? null : level.getBlockEntity(position);
        if (!(entity instanceof Container container)) return false;
        ItemStack result = container.getItem(RESULT_SLOT);
        StackKey key = StackKey.decode(products.get(0).itemKey());
        return key != null && key.matches(result) && result.getCount() == products.get(0).amount();
    }

    private static Recipe<?> resolveRecipe(Level level, String recipeId) {
        if (level == null || recipeId == null) return null;
        String baseId = recipeId;
        int suffix = baseId.indexOf('#');
        if (suffix >= 0) baseId = baseId.substring(0, suffix);
        ResourceLocation parsed = ResourceLocation.tryParse(baseId);
        if (parsed == null) return null;
        return level.getRecipeManager().byKey(parsed).map(RecipeHolder::value).orElse(null);
    }

    private static ItemStack recipeResult(Recipe<?> recipe, List<ItemStack> seasonings) {
        Method getter = findMethod(recipe.getClass(), "getResult", 0);
        if (getter == null) {
            return recipe.getResultItem(null);
        }
        try {
            getter.setAccessible(true);
            Object value = getter.invoke(recipe);
            if (!(value instanceof ItemStack result)) return null;
            Method apply = findMethod(recipe.getClass(), "applySeasoning", 2);
            if (apply != null && !seasonings.isEmpty()) {
                apply.setAccessible(true);
                apply.invoke(recipe, result, seasonings);
            }
            return result;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException error) {
            return null;
        }
    }

    private static List<ItemStack> seasonings(BlockEntity entity) {
        Method getter = findMethod(entity.getClass(), "getSeasonings", 0);
        if (getter == null) return List.of();
        try {
            getter.setAccessible(true);
            Object value = getter.invoke(entity);
            if (!(value instanceof List<?> list)) return List.of();
            List<ItemStack> result = new ArrayList<>();
            for (Object item : list) if (item instanceof ItemStack stack) result.add(stack);
            return result;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException error) {
            return List.of();
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameters) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == parameters) return method;
            }
        }
        return null;
    }

    private static boolean isCookingPotRecipe(Class<?> type) {
        List<Class<?>> pending = new ArrayList<>();
        Set<Class<?>> seen = new HashSet<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            if (!seen.add(current)) break;
            pending.add(current);
        }
        for (int i = 0; i < pending.size(); i++) {
            for (Class<?> parent : pending.get(i).getInterfaces()) if (seen.add(parent)) pending.add(parent);
        }
        for (Class<?> candidate : pending) if (RECIPE_BASE.equals(candidate.getName())) return true;
        return false;
    }

    private static String planError(Level level, BlockPos position, Operation operation) {
        if (level == null || level.isClientSide || position == null || operation == null) return "invalid_pot_target";
        if (operation.crafts() != 1L) return "pot_requires_single_craft";
        ResourceLocation type = ResourceLocation.tryParse(operation.recipeType());
        if (!CobblemonPot.isCampfirePotRecipeType(type)) return "unsupported_recipe_type";
        if (operation.ingredients().isEmpty() || operation.ingredients().size() > GRID) {
            return "invalid_ingredient_count";
        }
        for (Ingredient ingredient : operation.ingredients()) {
            if (ingredient.amount() != 1L || !ingredient.consumed()) return "pot_requires_unit_consumed_inputs";
        }
        if (operation.products().size() != 1 || operation.products().get(0).amount() < 1L) {
            return "invalid_product_plan";
        }
        return null;
    }

    private static String digest(Operation operation) {
        StringBuilder source = new StringBuilder();
        source.append(operation.operationId()).append('\u001f')
                .append(operation.recipeId()).append('\u001f')
                .append(operation.recipeType()).append('\u001f')
                .append(operation.crafts());
        for (Ingredient ingredient : operation.ingredients()) {
            source.append('\u001e').append(ingredient.itemKey()).append('\u001d')
                    .append(ingredient.amount()).append('\u001d').append(ingredient.consumed());
        }
        for (Product product : operation.products()) {
            source.append('\u001c').append(product.itemKey()).append('\u001d').append(product.amount());
        }
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte value : hash) hex.append(Character.forDigit((value >> 4) & 0xF, 16))
                    .append(Character.forDigit(value & 0xF, 16));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }
}
