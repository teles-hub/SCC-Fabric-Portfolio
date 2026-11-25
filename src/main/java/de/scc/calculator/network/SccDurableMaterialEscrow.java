package de.scc.calculator.network;

import de.scc.calculator.calc.StackKey;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Recoverable reservation coordinator for SCC-owned item stores.
 *
 * <p>Each SccItemStore persists its inventory and operation escrow in the same
 * BlockEntity payload. A crash between stores can therefore leave a partial
 * reservation, but the exact completed lots remain discoverable and this
 * coordinator can resume the same operation without extracting them twice.</p>
 *
 * <p>This deliberately does not accept arbitrary third-party Fabric Storage
 * instances: those do not expose SCC's per-BlockEntity durable escrow ledger.</p>
 */
public final class SccDurableMaterialEscrow {
    public static final int MAX_INPUTS = 64;
    public static final long MAX_CRAFTS = 1_000_000_000L;
    public static final long MAX_AMOUNT_PER_KEY = 1_000_000_000_000L;

    private SccDurableMaterialEscrow() { }

    /**
     * Reserves or resumes one operation across stores in their supplied stable
     * order. Partial results remain escrowed and are safe to resume with the
     * same operation ID and identical demand after restart.
     */
    public static Outcome reserve(String operationId, Collection<SccItemStore> stores,
                                  Collection<Input> inputs, long crafts) {
        if (!validOperationId(operationId) || stores == null || stores.isEmpty()
                || inputs == null || inputs.isEmpty() || inputs.size() > MAX_INPUTS
                || crafts < 1L || crafts > MAX_CRAFTS) {
            return Outcome.failure("reservation_invalid_plan", List.of(), List.of());
        }

        List<SccItemStore> orderedStores = stores.stream().filter(java.util.Objects::nonNull).toList();
        if (orderedStores.isEmpty()) return Outcome.failure("reservation_no_stores", List.of(), List.of());
        Map<DemandKey, Long> demand = normalize(inputs, crafts);
        if (demand == null || demand.isEmpty()) {
            return Outcome.failure("reservation_invalid_plan", List.of(), List.of());
        }

        Map<DemandKey, Long> reserved = new LinkedHashMap<>();
        for (SccItemStore store : orderedStores) {
            SccItemStore.EscrowOperation operation = store.reservationOperation(operationId);
            if (operation == null) continue;
            if (operation.state() != SccItemStore.ReservationState.ACTIVE) {
                return Outcome.failure("reservation_operation_terminal", snapshot(reserved), List.of());
            }
            for (SccItemStore.ReservationLot lot : operation.lots()) {
                DemandKey key = new DemandKey(lot.itemKey(), lot.consumed());
                Long expected = demand.get(key);
                if (expected == null) {
                    return Outcome.failure("reservation_operation_payload_mismatch", snapshot(reserved), List.of());
                }
                long total;
                try {
                    total = Math.addExact(reserved.getOrDefault(key, 0L), lot.amount());
                } catch (ArithmeticException overflow) {
                    return Outcome.failure("reservation_amount_overflow", snapshot(reserved), List.of());
                }
                if (total > expected) {
                    return Outcome.failure("reservation_operation_payload_mismatch", snapshot(reserved), List.of());
                }
                reserved.put(key, total);
            }
        }

        List<Shortage> shortages = new ArrayList<>();
        for (Map.Entry<DemandKey, Long> entry : demand.entrySet()) {
            DemandKey key = entry.getKey();
            long remaining = entry.getValue() - reserved.getOrDefault(key, 0L);
            if (remaining <= 0L) continue;

            for (SccItemStore store : orderedStores) {
                if (remaining <= 0L) break;
                SccItemStore.EscrowOperation operation = store.reservationOperation(operationId);
                if (containsLot(operation, key)) continue;
                long moved = store.reserveForOperation(operationId, key.itemKey(), key.consumed(), remaining);
                if (moved < 0L || moved > remaining) {
                    return Outcome.failure("reservation_store_rejected", snapshot(reserved), shortages);
                }
                if (moved > 0L) {
                    reserved.merge(key, moved, Math::addExact);
                    remaining -= moved;
                }
            }
            if (remaining > 0L) shortages.add(new Shortage(key.itemKey(), entry.getValue(),
                    entry.getValue() - remaining, key.consumed()));
        }

        List<Lot> lots = snapshot(reserved);
        return shortages.isEmpty()
                ? Outcome.success(lots)
                : Outcome.failure("reservation_insufficient_materials", lots, shortages);
    }

    /** Releases all partial/complete lots. Safe to repeat after a partial cross-store release. */
    public static boolean release(String operationId, Collection<SccItemStore> stores) {
        List<SccItemStore> ordered = ordered(stores);
        if (!validOperationId(operationId) || ordered == null) return false;
        for (SccItemStore store : ordered) {
            SccItemStore.EscrowOperation operation = store.reservationOperation(operationId);
            if (operation != null && operation.state() == SccItemStore.ReservationState.CONSUMED) return false;
        }
        boolean success = true;
        for (SccItemStore store : ordered) success &= store.releaseReservation(operationId);
        return success;
    }

    /** Consumes all lots. Safe to repeat after a partial cross-store consume. */
    public static boolean consume(String operationId, Collection<SccItemStore> stores) {
        List<SccItemStore> ordered = ordered(stores);
        if (!validOperationId(operationId) || ordered == null) return false;
        for (SccItemStore store : ordered) {
            SccItemStore.EscrowOperation operation = store.reservationOperation(operationId);
            if (operation != null && operation.state() == SccItemStore.ReservationState.RELEASED) return false;
        }
        boolean success = true;
        for (SccItemStore store : ordered) success &= store.consumeReservation(operationId);
        return success;
    }

    /**
     * Inserts expected machine outputs through per-store idempotency receipts.
     * A partial result can be resumed after a crash without duplicating outputs
     * already committed to any SCC storage BlockEntity.
     */
    public static OutputOutcome insertOutputs(String operationId, Collection<SccItemStore> stores,
                                              Collection<Product> products) {
        List<SccItemStore> orderedStores = ordered(stores);
        if (!validOperationId(operationId) || orderedStores == null
                || products == null || products.isEmpty() || products.size() > MAX_INPUTS) {
            return OutputOutcome.failure("output_invalid_plan", List.of(), List.of());
        }
        Map<String, Long> expected = normalizeProducts(products);
        if (expected == null || expected.isEmpty()) {
            return OutputOutcome.failure("output_invalid_plan", List.of(), List.of());
        }

        Map<String, Long> inserted = new LinkedHashMap<>();
        for (SccItemStore store : orderedStores) {
            SccItemStore.OutputOperation operation = store.outputOperation(operationId);
            if (operation == null) continue;
            for (SccItemStore.OutputLot lot : operation.lots()) {
                Long target = expected.get(lot.itemKey());
                if (target == null) {
                    return OutputOutcome.failure("output_operation_payload_mismatch",
                            outputSnapshot(inserted), List.of());
                }
                long total;
                try { total = Math.addExact(inserted.getOrDefault(lot.itemKey(), 0L), lot.amount()); }
                catch (ArithmeticException overflow) {
                    return OutputOutcome.failure("output_amount_overflow", outputSnapshot(inserted), List.of());
                }
                if (total > target) {
                    return OutputOutcome.failure("output_operation_payload_mismatch",
                            outputSnapshot(inserted), List.of());
                }
                inserted.put(lot.itemKey(), total);
            }
        }

        List<OutputShortage> shortages = new ArrayList<>();
        for (Map.Entry<String, Long> entry : expected.entrySet()) {
            String itemKey = entry.getKey();
            long remaining = entry.getValue() - inserted.getOrDefault(itemKey, 0L);
            if (remaining <= 0L) continue;
            for (SccItemStore store : orderedStores) {
                if (remaining <= 0L) break;
                SccItemStore.OutputOperation operation = store.outputOperation(operationId);
                if (containsOutput(operation, itemKey)) continue;
                long moved = store.insertForOperation(operationId, itemKey, remaining);
                if (moved < 0L || moved > remaining) {
                    return OutputOutcome.failure("output_store_rejected", outputSnapshot(inserted), shortages);
                }
                if (moved > 0L) {
                    inserted.merge(itemKey, moved, Math::addExact);
                    remaining -= moved;
                }
            }
            if (remaining > 0L) shortages.add(new OutputShortage(itemKey, entry.getValue(),
                    entry.getValue() - remaining));
        }
        List<OutputLot> lots = outputSnapshot(inserted);
        return shortages.isEmpty() ? OutputOutcome.success(lots)
                : OutputOutcome.failure("output_storage_full", lots, shortages);
    }

    /** Call only after the journal records ACKNOWLEDGED; then marker removal is safe to retry. */
    public static boolean acknowledgeOutputs(String operationId, Collection<SccItemStore> stores) {
        List<SccItemStore> ordered = ordered(stores);
        if (!validOperationId(operationId) || ordered == null) return false;
        boolean success = true;
        for (SccItemStore store : ordered) success &= store.acknowledgeOutputOperation(operationId);
        return success;
    }

    private static boolean containsOutput(SccItemStore.OutputOperation operation, String itemKey) {
        return operation != null && operation.lots().stream().anyMatch(lot -> lot.itemKey().equals(itemKey));
    }

    private static Map<String, Long> normalizeProducts(Collection<Product> products) {
        Map<String, Long> result = new LinkedHashMap<>();
        for (Product product : products) {
            if (product == null || product.itemKey() == null || product.itemKey().isBlank()
                    || product.itemKey().length() > 4_000 || StackKey.decode(product.itemKey()) == null
                    || product.amount() < 1L || product.amount() > MAX_AMOUNT_PER_KEY) return null;
            try {
                long amount = Math.addExact(result.getOrDefault(product.itemKey(), 0L), product.amount());
                if (amount > MAX_AMOUNT_PER_KEY) return null;
                result.put(product.itemKey(), amount);
            } catch (ArithmeticException overflow) {
                return null;
            }
        }
        return result;
    }

    private static List<OutputLot> outputSnapshot(Map<String, Long> amounts) {
        List<OutputLot> result = new ArrayList<>();
        amounts.forEach((key, amount) -> result.add(new OutputLot(key, amount)));
        return List.copyOf(result);
    }

    /** Removes terminal per-store markers only after the journal/job has been durably acknowledged. */
    public static boolean acknowledge(String operationId, Collection<SccItemStore> stores) {
        List<SccItemStore> ordered = ordered(stores);
        if (!validOperationId(operationId) || ordered == null) return false;
        for (SccItemStore store : ordered) {
            SccItemStore.EscrowOperation operation = store.reservationOperation(operationId);
            if (operation != null && operation.state() == SccItemStore.ReservationState.ACTIVE) return false;
        }
        boolean success = true;
        for (SccItemStore store : ordered) success &= store.acknowledgeReservationOperation(operationId);
        return success;
    }

    private static List<SccItemStore> ordered(Collection<SccItemStore> stores) {
        if (stores == null) return null;
        List<SccItemStore> result = stores.stream().filter(java.util.Objects::nonNull).toList();
        return result.isEmpty() ? null : result;
    }

    private static boolean containsLot(SccItemStore.EscrowOperation operation, DemandKey wanted) {
        if (operation == null) return false;
        return operation.lots().stream().anyMatch(lot ->
                lot.itemKey().equals(wanted.itemKey()) && lot.consumed() == wanted.consumed());
    }

    private static Map<DemandKey, Long> normalize(Collection<Input> inputs, long crafts) {
        Map<DemandKey, Long> amounts = new LinkedHashMap<>();
        for (Input input : inputs) {
            if (input == null || input.itemKey() == null || input.itemKey().isBlank()
                    || input.itemKey().length() > 4_000 || StackKey.decode(input.itemKey()) == null
                    || input.countPerCraft() < 1 || input.countPerCraft() > Integer.MAX_VALUE) return null;
            long amount;
            try {
                amount = Math.multiplyExact((long) input.countPerCraft(), input.consumed() ? crafts : 1L);
                long combined = Math.addExact(amounts.getOrDefault(
                        new DemandKey(input.itemKey(), input.consumed()), 0L), amount);
                if (combined < 1L || combined > MAX_AMOUNT_PER_KEY) return null;
                amounts.put(new DemandKey(input.itemKey(), input.consumed()), combined);
            } catch (ArithmeticException overflow) {
                return null;
            }
        }
        return amounts;
    }

    private static List<Lot> snapshot(Map<DemandKey, Long> amounts) {
        List<Lot> result = new ArrayList<>();
        amounts.forEach((key, amount) -> result.add(new Lot(key.itemKey(), amount, key.consumed())));
        return List.copyOf(result);
    }

    private static boolean validOperationId(String value) {
        return value != null && !value.isBlank() && value.length() <= 256
                && value.matches("[A-Za-z0-9_.:-]+");
    }

    public record Input(String itemKey, int countPerCraft, boolean consumed) { }
    public record Lot(String itemKey, long amount, boolean consumed) { }
    public record Product(String itemKey, long amount) { }
    public record OutputLot(String itemKey, long amount) { }
    public record OutputShortage(String itemKey, long required, long inserted) {
        public long missing() { return Math.max(0L, required - inserted); }
    }
    public record OutputOutcome(String status, List<OutputLot> lots, List<OutputShortage> shortages) {
        public OutputOutcome {
            status = status == null ? "output_failed" : status;
            lots = lots == null ? List.of() : List.copyOf(lots);
            shortages = shortages == null ? List.of() : List.copyOf(shortages);
        }
        public static OutputOutcome success(List<OutputLot> lots) {
            return new OutputOutcome("inserted", lots, List.of());
        }
        public static OutputOutcome failure(String status, List<OutputLot> lots, List<OutputShortage> shortages) {
            return new OutputOutcome(status, lots, shortages);
        }
        public boolean successful() { return "inserted".equals(status); }
    }
    public record Shortage(String itemKey, long required, long reserved, boolean consumed) {
        public long missing() { return Math.max(0L, required - reserved); }
    }
    public record Outcome(String status, List<Lot> lots, List<Shortage> shortages) {
        public Outcome {
            status = status == null ? "reservation_failed" : status;
            lots = lots == null ? List.of() : List.copyOf(lots);
            shortages = shortages == null ? List.of() : List.copyOf(shortages);
        }
        public static Outcome success(List<Lot> lots) {
            return new Outcome("reserved", lots, List.of());
        }
        public static Outcome failure(String status, List<Lot> lots, List<Shortage> shortages) {
            return new Outcome(status, lots, shortages);
        }
        public boolean successful() { return "reserved".equals(status); }
    }

    private record DemandKey(String itemKey, boolean consumed) { }
}
