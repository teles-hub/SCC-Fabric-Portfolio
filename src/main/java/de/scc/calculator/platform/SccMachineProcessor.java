package de.scc.calculator.platform;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

import java.util.List;

/**
 * Idempotent processing contract for a real machine adapter.
 *
 * <p>Implementations must persist operation IDs on the machine side. Repeating
 * dispatch with the same ID must never enqueue a second craft. Output snapshots
 * are non-destructive and remain available until acknowledgeOutputs succeeds.
 * The SCC scheduler must persist its journal before acknowledging machine output.</p>
 */
public interface SccMachineProcessor {
    int MAX_COMPONENTS = 64;
    int MAX_KEY_LENGTH = 4_000;
    long MAX_AMOUNT = 1_000_000_000_000L;

    Validation validate(Level level, BlockPos position, Operation operation);

    /** Idempotent: same operation ID and identical payload returns the original result. */
    DispatchResult dispatch(Level level, BlockPos position, Operation operation);

    /** Returns authoritative state for an operation ID, including UNKNOWN after loss of state. */
    OperationState state(Level level, BlockPos position, String operationId);

    /** Non-destructive snapshot. Implementations must not remove outputs in this method. */
    OutputSnapshot outputs(Level level, BlockPos position, String operationId);

    /** Acknowledges outputs only after SCC has durably recorded them; repeated calls are safe. */
    boolean acknowledgeOutputs(Level level, BlockPos position, String operationId);

    record Operation(String operationId, String recipeId, String recipeType, long crafts,
                     List<Ingredient> ingredients, List<Product> products) {
        public Operation {
            if (!validToken(operationId) || !validId(recipeId) || !validId(recipeType)
                    || crafts < 1L || crafts > MAX_AMOUNT
                    || ingredients == null || ingredients.isEmpty() || ingredients.size() > MAX_COMPONENTS
                    || products == null || products.isEmpty() || products.size() > MAX_COMPONENTS) {
                throw new IllegalArgumentException("Invalid machine operation");
            }
            ingredients = List.copyOf(ingredients);
            products = List.copyOf(products);
            if (ingredients.stream().anyMatch(java.util.Objects::isNull)
                    || products.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("Null machine operation component");
            }
        }
    }

    record Ingredient(String itemKey, long amount, boolean consumed) {
        public Ingredient {
            if (!validKey(itemKey) || amount < 1L || amount > MAX_AMOUNT)
                throw new IllegalArgumentException("Invalid machine ingredient");
        }
    }

    record Product(String itemKey, long amount) {
        public Product {
            if (!validKey(itemKey) || amount < 1L || amount > MAX_AMOUNT)
                throw new IllegalArgumentException("Invalid machine product");
        }
    }

    record Validation(boolean accepted, String reason) {
        public Validation {
            reason = boundedReason(reason);
        }
        public static Validation accept() { return new Validation(true, ""); }
        public static Validation reject(String reason) { return new Validation(false, reason); }
    }

    record DispatchResult(DispatchState state, String reason) {
        public DispatchResult {
            if (state == null) state = DispatchState.UNKNOWN;
            reason = boundedReason(reason);
        }
        public static DispatchResult accepted() { return new DispatchResult(DispatchState.ACCEPTED, ""); }
        public static DispatchResult rejected(String reason) { return new DispatchResult(DispatchState.REJECTED, reason); }
        public static DispatchResult unknown(String reason) { return new DispatchResult(DispatchState.UNKNOWN, reason); }
    }

    record OutputSnapshot(String operationId, List<Product> products, boolean complete) {
        public OutputSnapshot {
            if (!validToken(operationId) || products == null || products.size() > MAX_COMPONENTS
                    || products.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("Invalid machine output snapshot");
            }
            products = List.copyOf(products);
        }
    }

    enum DispatchState { ACCEPTED, REJECTED, UNKNOWN }
    enum OperationState { QUEUED, RUNNING, OUTPUT_READY, FAILED, REJECTED, UNKNOWN }

    private static boolean validKey(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_KEY_LENGTH) return false;
        int componentSeparator = value.indexOf('@');
        String itemId = componentSeparator < 0 ? value : value.substring(0, componentSeparator);
        return validId(itemId);
    }

    private static boolean validId(String value) {
        if (value == null || value.isBlank() || value.length() > 256) return false;
        int split = value.indexOf(':');
        return split > 0 && split == value.lastIndexOf(':') && split < value.length() - 1
                && value.substring(0, split).matches("[a-z0-9_.-]+")
                && value.substring(split + 1).matches("[a-z0-9/._-]+");
    }

    private static boolean validToken(String value) {
        return value != null && !value.isBlank() && value.length() <= 256
                && value.matches("[A-Za-z0-9_.:-]+");
    }

    private static String boundedReason(String reason) {
        if (reason == null) return "";
        return reason.length() <= 128 ? reason : reason.substring(0, 128);
    }
}
