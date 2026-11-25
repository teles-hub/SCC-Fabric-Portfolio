package de.scc.calculator.network;

import de.scc.calculator.calc.StackKey;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.storage.Storage;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Atomic ingredient reservation primitive for a single planned recipe step.
 *
 * <p>Every extraction is executed inside one outer Fabric transaction. If any
 * ingredient is missing, invalid, or causes a storage error, closing the
 * transaction rolls every earlier extraction back. A successful result is an
 * escrow receipt describing exactly what was removed from the network. The
 * scheduler must persist that receipt with the job before dispatching a
 * processor; this class deliberately does not claim to provide cross-save-file
 * atomicity or machine execution.</p>
 */
public final class SccMaterialReservation {
    public static final long MAX_CRAFTS = 1_000_000_000L;
    public static final long MAX_AMOUNT_PER_KEY = 1_000_000_000_000L;
    public static final int MAX_INPUTS = 64;
    public static final int MAX_KEY_LENGTH = 4_000;

    private SccMaterialReservation() { }

    /**
     * Reserves the concrete inputs of one step. Consumed inputs scale by crafts;
     * catalysts/reusable inputs are reserved once for the whole step.
     */
    public static Outcome reserve(Storage<ItemVariant> storage, Collection<Input> inputs, long crafts) {
        if (storage == null || inputs == null || inputs.isEmpty()
                || inputs.size() > MAX_INPUTS || crafts < 1L || crafts > MAX_CRAFTS) {
            return Outcome.failure("reservation_invalid_plan", List.of());
        }

        List<Lot> required = normalize(inputs, crafts);
        if (required == null || required.isEmpty()) {
            return Outcome.failure("reservation_invalid_plan", List.of());
        }

        // A detached receipt is only truthful after its extraction transaction
        // commits independently. Reject an ambient transaction whose parent
        // could still roll back after this method returns.
        if (Transaction.getCurrentUnsafe() != null) {
            return Outcome.failure("reservation_transaction_already_open", List.of());
        }
        List<Shortage> shortages = new ArrayList<>();
        try (Transaction transaction = Transaction.openOuter()) {
            for (Lot lot : required) {
                StackKey key = StackKey.decode(lot.itemKey());
                if (key == null) return Outcome.failure("reservation_invalid_key", List.of());
                ItemStack sample = key.stack(1);
                if (sample == null || sample.isEmpty()) {
                    return Outcome.failure("reservation_invalid_key", List.of());
                }
                long extracted = storage.extract(ItemVariant.of(sample), lot.amount(), transaction);
                if (extracted < 0L || extracted > lot.amount()) {
                    return Outcome.failure("reservation_storage_contract", List.of());
                }
                if (extracted != lot.amount()) {
                    shortages.add(new Shortage(lot.itemKey(), lot.amount(), extracted));
                }
            }
            if (!shortages.isEmpty()) {
                // No commit: Fabric rolls every partial extraction back on close.
                return Outcome.failure("reservation_insufficient_materials", shortages);
            }
            transaction.commit();
            return Outcome.success(new Receipt(required));
        } catch (RuntimeException storageFailure) {
            return Outcome.failure("reservation_storage_error", List.of());
        }
    }

    /**
     * Returns a complete receipt after a processor rejects a dispatch or cannot
     * safely accept the escrow. The insertion is also all-or-nothing.
     */
    public static boolean restoreAll(Storage<ItemVariant> storage, Receipt receipt) {
        return restore(storage, receipt, false);
    }

    /** Returns only reusable inputs (catalysts) after a successful recipe step. */
    public static boolean returnReusable(Storage<ItemVariant> storage, Receipt receipt) {
        return restore(storage, receipt, true);
    }

    private static boolean restore(Storage<ItemVariant> storage, Receipt receipt, boolean reusableOnly) {
        if (storage == null || receipt == null || Transaction.getCurrentUnsafe() != null
                || !receipt.beginReturn()) return false;
        boolean completed = false;
        try (Transaction transaction = Transaction.openOuter()) {
            for (Lot lot : receipt.lots()) {
                if (reusableOnly && lot.consumed()) continue;
                StackKey key = StackKey.decode(lot.itemKey());
                if (key == null) return false;
                ItemStack sample = key.stack(1);
                if (sample == null || sample.isEmpty()) return false;
                long inserted = storage.insert(ItemVariant.of(sample), lot.amount(), transaction);
                if (inserted != lot.amount()) {
                    // No commit: partial returns are rolled back as a unit.
                    return false;
                }
            }
            transaction.commit();
            completed = true;
            return true;
        } catch (RuntimeException storageFailure) {
            return false;
        } finally {
            receipt.finishReturn(completed);
        }
    }

    private static List<Lot> normalize(Collection<Input> inputs, long crafts) {
        Map<DemandKey, Long> amounts = new LinkedHashMap<>();
        for (Input input : inputs) {
            if (input == null || input.itemKey() == null || input.itemKey().isBlank()
                    || input.itemKey().length() > MAX_KEY_LENGTH
                    || StackKey.decode(input.itemKey()) == null || input.countPerCraft() < 1) return null;
            long multiplier = input.consumed() ? crafts : 1L;
            long amount;
            try {
                amount = Math.multiplyExact((long) input.countPerCraft(), multiplier);
            } catch (ArithmeticException overflow) {
                return null;
            }
            if (amount < 1L || amount > MAX_AMOUNT_PER_KEY) return null;
            DemandKey key = new DemandKey(input.itemKey(), input.consumed());
            Long previous = amounts.get(key);
            long combined;
            try {
                combined = previous == null ? amount : Math.addExact(previous, amount);
            } catch (ArithmeticException overflow) {
                return null;
            }
            if (combined > MAX_AMOUNT_PER_KEY) return null;
            amounts.put(key, combined);
        }
        List<Lot> result = new ArrayList<>(amounts.size());
        amounts.forEach((key, amount) -> result.add(new Lot(key.itemKey(), amount, key.consumed())));
        return List.copyOf(result);
    }

    public record Input(String itemKey, int countPerCraft, boolean consumed) { }

    /** Exact amount removed for one key. Reusable lots are returned after success. */
    public record Lot(String itemKey, long amount, boolean consumed) {
        public Lot {
            if (itemKey == null || itemKey.isBlank() || amount < 1L
                    || amount > MAX_AMOUNT_PER_KEY || StackKey.decode(itemKey) == null) {
                throw new IllegalArgumentException("Invalid reserved material lot");
            }
        }
    }

    public record Shortage(String itemKey, long required, long available) {
        public Shortage {
            if (itemKey == null || itemKey.isBlank() || required < 1L
                    || available < 0L || available >= required) {
                throw new IllegalArgumentException("Invalid reservation shortage");
            }
        }
        public long missing() { return required - available; }
    }

    public static final class Receipt {
        private final List<Lot> lots;
        private boolean returned;
        private boolean returning;

        public Receipt(List<Lot> lots) {
            this.lots = lots == null ? List.of() : List.copyOf(lots);
            if (this.lots.isEmpty() || this.lots.size() > MAX_INPUTS
                    || this.lots.stream().anyMatch(java.util.Objects::isNull)) {
                throw new IllegalArgumentException("Invalid material reservation receipt");
            }
        }

        public List<Lot> lots() { return lots; }

        public long totalReserved() {
            long total = 0L;
            for (Lot lot : lots) total = Math.addExact(total, lot.amount());
            return total;
        }

        /** Writes the escrow receipt and its one-time return marker into a job NBT compound. */
        public synchronized void save(CompoundTag target) {
            if (target == null) return;
            ListTag encoded = new ListTag();
            for (Lot lot : lots) {
                CompoundTag entry = new CompoundTag();
                entry.putString("item", lot.itemKey());
                entry.putLong("amount", lot.amount());
                entry.putBoolean("consumed", lot.consumed());
                encoded.add(entry);
            }
            target.put("scc_material_lots", encoded);
            target.putBoolean("scc_materials_returned", returned);
        }

        /** Reads a bounded receipt. Invalid or incomplete escrow data is rejected. */
        public static Receipt read(CompoundTag source) {
            if (source == null || !source.contains("scc_material_lots")) return null;
            ListTag encoded = source.getList("scc_material_lots", 10);
            if (encoded.size() == 0 || encoded.size() > MAX_INPUTS) return null;
            List<Lot> lots = new ArrayList<>(encoded.size());
            try {
                for (int i = 0; i < encoded.size(); i++) {
                    CompoundTag entry = encoded.getCompound(i);
                    lots.add(new Lot(entry.getString("item"), entry.getLong("amount"),
                            entry.getBoolean("consumed")));
                }
                Receipt receipt = new Receipt(lots);
                receipt.returned = source.getBoolean("scc_materials_returned");
                return receipt;
            } catch (RuntimeException invalidReceipt) {
                return null;
            }
        }

        private synchronized boolean beginReturn() {
            if (returned || returning) return false;
            returning = true;
            return true;
        }

        private synchronized void finishReturn(boolean succeeded) {
            returning = false;
            if (succeeded) returned = true;
        }

        public synchronized boolean returned() { return returned; }
    }

    public record Outcome(String status, Receipt receipt, List<Shortage> shortages) {
        public Outcome {
            status = status == null ? "reservation_failed" : status;
            shortages = shortages == null ? List.of() : List.copyOf(shortages);
        }
        public static Outcome success(Receipt receipt) {
            return new Outcome("reserved", receipt, List.of());
        }
        public static Outcome failure(String status, List<Shortage> shortages) {
            return new Outcome(status, null, shortages);
        }
        public boolean successful() { return receipt != null; }
    }

    private record DemandKey(String itemKey, boolean consumed) { }
}
