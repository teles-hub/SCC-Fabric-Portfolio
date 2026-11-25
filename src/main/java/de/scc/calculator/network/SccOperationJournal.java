package de.scc.calculator.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bounded write-ahead operation journal stored with an SCC terminal.
 *
 * <p>The PREPARING record must be saved before material extraction. Later
 * phases describe reservation, dispatch, observed output, durable SCC output
 * insertion and machine acknowledgement. This per-terminal journal narrows
 * crash ambiguity but is not a world-global transaction across chunks.</p>
 */
public final class SccOperationJournal {
    public static final int MAX_OPERATIONS = 128;
    public static final int MAX_OUTPUTS = 64;
    public static final int MAX_STEPS = 2_048;
    public static final int MAX_TEXT = 256;
    public static final long MAX_AMOUNT = 1_000_000_000_000L;

    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private final Runnable dirtyCallback;

    public SccOperationJournal() { this(() -> { }); }

    public SccOperationJournal(Runnable dirtyCallback) {
        this.dirtyCallback = dirtyCallback == null ? () -> { } : dirtyCallback;
    }

    public synchronized List<Entry> entries() { return List.copyOf(entries.values()); }
    public synchronized Entry byOperationId(String operationId) { return entries.get(operationId); }

    /** Write-ahead intent. Repeating the exact same ID/payload is idempotent. */
    public synchronized boolean begin(Entry intent) {
        if (intent == null || !Phase.PREPARING.key().equals(intent.phase())) return false;
        Entry existing = entries.get(intent.operationId());
        if (existing != null) return existing.sameIntent(intent);
        if (entries.size() >= MAX_OPERATIONS) pruneAcknowledged();
        if (entries.size() >= MAX_OPERATIONS) return false;
        entries.put(intent.operationId(), intent);
        dirtyCallback.run();
        return true;
    }

    public synchronized boolean markReserved(String operationId) {
        return transition(operationId, Phase.PREPARING, Phase.RESERVED, null);
    }

    public synchronized boolean markDispatching(String operationId) {
        return transition(operationId, Phase.RESERVED, Phase.DISPATCHING, null);
    }

    public synchronized boolean markDispatched(String operationId) {
        return transition(operationId, Phase.DISPATCHING, Phase.DISPATCHED, null);
    }

    /** Stores the authoritative, non-destructively observed output snapshot. */
    public synchronized boolean observeOutputs(String operationId, List<OutputLot> outputs) {
        Entry current = entries.get(operationId);
        if (current == null || Phase.DISPATCHED != Phase.from(current.phase())) return false;
        List<OutputLot> normalized = normalizeOutputs(outputs);
        if (normalized == null || !normalized.equals(current.expectedOutputs())) return false;
        entries.put(operationId, current.with(Phase.OUTPUT_OBSERVED, normalized));
        dirtyCallback.run();
        return true;
    }

    /** Call only after products were inserted into SCC storage and that mutation was saved. */
    public synchronized boolean markOutputCommitted(String operationId) {
        return transition(operationId, Phase.OUTPUT_OBSERVED, Phase.OUTPUT_COMMITTED, null);
    }

    /** Call only after the processor accepted the idempotent acknowledgement. */
    public synchronized boolean markAcknowledged(String operationId) {
        return transition(operationId, Phase.OUTPUT_COMMITTED, Phase.ACKNOWLEDGED, null);
    }

    /** Ambiguous outcomes are never automatically retried. */
    public synchronized boolean markRecoveryRequired(String operationId) {
        Entry current = entries.get(operationId);
        if (current == null || Phase.from(current.phase()) == null
                || Phase.from(current.phase()).terminal()) return false;
        entries.put(operationId, current.with(Phase.RECOVERY_REQUIRED, current.observedOutputs()));
        dirtyCallback.run();
        return true;
    }

    /**
     * Resolves a PREPARING/RESERVED intent as safely aborted when no dispatch
     * call was made. The caller must first release every durable storage lot.
     */
    public synchronized boolean abortBeforeDispatch(String operationId) {
        Entry current = entries.get(operationId);
        if (current == null) return false;
        Phase phase = Phase.from(current.phase());
        if (phase != Phase.PREPARING && phase != Phase.RESERVED) return false;
        entries.put(operationId, current.with(Phase.ABORTED, current.observedOutputs()));
        dirtyCallback.run();
        return true;
    }

    /** Mirrors a terminal local journal receipt into the world journal after a partial save. */
    public synchronized boolean reconcileAcknowledged(String operationId) {
        Entry current = entries.get(operationId);
        if (current == null) return false;
        Phase phase = Phase.from(current.phase());
        if (phase == Phase.ACKNOWLEDGED) return true;
        // ACKNOWLEDGED and ABORTED are mutually exclusive terminal receipts.
        // A conflicting durable rollback must never be silently promoted to success.
        if (phase == Phase.ABORTED) return false;
        entries.put(operationId, current.with(Phase.ACKNOWLEDGED, current.observedOutputs()));
        dirtyCallback.run();
        return true;
    }

    /** Mirrors a terminal local rollback receipt into the world journal after a partial save. */
    public synchronized boolean reconcileAborted(String operationId) {
        Entry current = entries.get(operationId);
        if (current == null) return false;
        Phase phase = Phase.from(current.phase());
        if (phase == Phase.ABORTED) return true;
        if (phase == Phase.ACKNOWLEDGED) return false;
        entries.put(operationId, current.with(Phase.ABORTED, current.observedOutputs()));
        dirtyCallback.run();
        return true;
    }

    /** A processor's definitive rejection proves that this token was never accepted. */
    public synchronized boolean markDispatchRejected(String operationId) {
        return transition(operationId, Phase.DISPATCHING, Phase.ABORTED, null);
    }

    /**
     * Clears an ambiguous journal phase only after the persisted machine
     * operation token is found in an authoritative accepted state.
     */
    public synchronized boolean reconcileAccepted(String operationId) {
        Entry current = entries.get(operationId);
        if (current == null || Phase.from(current.phase()) != Phase.RECOVERY_REQUIRED) return false;
        entries.put(operationId, current.with(Phase.DISPATCHED, current.observedOutputs()));
        dirtyCallback.run();
        return true;
    }

    /** An intent can be aborted only before reservation begins. */
    public synchronized boolean abortPreparing(String operationId) {
        return transition(operationId, Phase.PREPARING, Phase.ABORTED, null);
    }

    private boolean transition(String operationId, Phase expected, Phase next, List<OutputLot> outputs) {
        Entry current = entries.get(operationId);
        if (current == null || Phase.from(current.phase()) != expected) return false;
        entries.put(operationId, current.with(next, outputs == null ? current.observedOutputs() : outputs));
        dirtyCallback.run();
        return true;
    }

    private void pruneAcknowledged() {
        entries.entrySet().removeIf(entry -> Phase.ACKNOWLEDGED.key().equals(entry.getValue().phase())
                || Phase.ABORTED.key().equals(entry.getValue().phase()));
    }

    public synchronized void save(CompoundTag target) {
        if (target == null) return;
        ListTag list = new ListTag();
        for (Entry entry : entries.values()) list.add(entry.write());
        target.put("scc_operation_journal", list);
    }

    public synchronized void load(CompoundTag source) {
        entries.clear();
        if (source == null || !source.contains("scc_operation_journal")) return;
        ListTag list = source.getList("scc_operation_journal", 10);
        if (list.size() > MAX_OPERATIONS) return;
        boolean recoveredAmbiguousIntent = false;
        for (int i = 0; i < list.size(); i++) {
            Entry decoded = Entry.read(list.getCompound(i));
            if (decoded == null || entries.containsKey(decoded.operationId())) {
                entries.clear();
                return;
            }
            // PREPARING is reconciled against the durable per-storage escrow
            // ledgers. Those ledgers persist item movement and operation receipts
            // together in each SCC storage BlockEntity, so a partial reservation
            // can be resumed without guessing. DISPATCHING remains ambiguous:
            // the machine may have accepted the idempotency token before the crash.
            Phase phase = Phase.from(decoded.phase());
            if (phase == Phase.DISPATCHING) {
                decoded = decoded.with(Phase.RECOVERY_REQUIRED, decoded.observedOutputs());
                recoveredAmbiguousIntent = true;
            }
            entries.put(decoded.operationId(), decoded);
        }
        if (recoveredAmbiguousIntent) dirtyCallback.run();
    }

    private record ReservationKey(String itemKey, boolean consumed) { }

    private static ListTag writeReservationLots(List<ReservationLot> lots) {
        ListTag list = new ListTag();
        for (ReservationLot lot : lots) {
            CompoundTag tag = new CompoundTag();
            tag.putString("item", lot.itemKey()); tag.putLong("amount", lot.amount());
            tag.putBoolean("consumed", lot.consumed()); list.add(tag);
        }
        return list;
    }

    private static List<ReservationLot> readReservationLots(ListTag list) {
        if (list.size() > MAX_OUTPUTS) return null;
        List<ReservationLot> result = new ArrayList<>();
        try {
            for (int i = 0; i < list.size(); i++) {
                CompoundTag tag = list.getCompound(i);
                result.add(new ReservationLot(tag.getString("item"), tag.getLong("amount"), tag.getBoolean("consumed")));
            }
        } catch (RuntimeException invalid) { return null; }
        return List.copyOf(result);
    }

    private static List<OutputLot> normalizeOutputs(List<OutputLot> outputs) {
        if (outputs == null || outputs.isEmpty() || outputs.size() > MAX_OUTPUTS) return null;
        Map<String, Long> merged = new LinkedHashMap<>();
        for (OutputLot output : outputs) {
            if (output == null) return null;
            long sum;
            try { sum = Math.addExact(merged.getOrDefault(output.itemKey(), 0L), output.amount()); }
            catch (ArithmeticException overflow) { return null; }
            if (sum > MAX_AMOUNT) return null;
            merged.put(output.itemKey(), sum);
        }
        List<OutputLot> normalized = new ArrayList<>();
        merged.forEach((key, amount) -> normalized.add(new OutputLot(key, amount)));
        return List.copyOf(normalized);
    }

    public enum Phase {
        PREPARING("preparing"), RESERVED("reserved"), DISPATCHING("dispatching"),
        DISPATCHED("dispatched"), OUTPUT_OBSERVED("output_observed"),
        OUTPUT_COMMITTED("output_committed"), ACKNOWLEDGED("acknowledged"),
        ABORTED("aborted"), RECOVERY_REQUIRED("recovery_required");
        private final String key;
        Phase(String key) { this.key = key; }
        public String key() { return key; }
        public boolean terminal() { return this == ACKNOWLEDGED || this == ABORTED; }
        public static Phase from(String key) {
            for (Phase phase : values()) if (phase.key.equals(key)) return phase;
            return null;
        }
    }

    public record OutputLot(String itemKey, long amount) {
        public OutputLot {
            if (!validItemKey(itemKey) || amount < 1L || amount > MAX_AMOUNT)
                throw new IllegalArgumentException("Invalid journal output lot");
        }
    }

    /** Exact material demand written before extraction starts. */
    public record ReservationLot(String itemKey, long amount, boolean consumed) {
        public ReservationLot {
            if (!validItemKey(itemKey) || amount < 1L || amount > MAX_AMOUNT)
                throw new IllegalArgumentException("Invalid journal reservation lot");
        }
    }

    public record Entry(long jobId, int stepIndex, String operationId, String machineAlias,
                        int machineX, int machineY, int machineZ, String recipeId,
                        String recipeType, long crafts, String phase,
                        List<OutputLot> expectedOutputs, List<OutputLot> observedOutputs,
                        List<ReservationLot> reservedInputs) {
        /** Backward-compatible constructor for legacy journal fixtures and old saved data. */
        public Entry(long jobId, int stepIndex, String operationId, String machineAlias,
                     int machineX, int machineY, int machineZ, String recipeId,
                     String recipeType, long crafts, String phase,
                     List<OutputLot> expectedOutputs, List<OutputLot> observedOutputs) {
            this(jobId, stepIndex, operationId, machineAlias, machineX, machineY, machineZ,
                    recipeId, recipeType, crafts, phase, expectedOutputs, observedOutputs, List.of());
        }
        public Entry {
            if (jobId < 1L || stepIndex < 0 || stepIndex >= MAX_STEPS
                    || !validToken(operationId) || !validId(machineAlias) || !validId(recipeId)
                    || !validId(recipeType) || crafts < 1L || crafts > MAX_AMOUNT
                    || Phase.from(phase) == null || expectedOutputs == null || expectedOutputs.isEmpty()
                    || expectedOutputs.size() > MAX_OUTPUTS || observedOutputs == null
                    || observedOutputs.size() > MAX_OUTPUTS || reservedInputs == null
                    || reservedInputs.size() > MAX_OUTPUTS) {
                throw new IllegalArgumentException("Invalid journal entry");
            }
            List<OutputLot> normalizedExpected = normalizeOutputs(expectedOutputs);
            List<OutputLot> normalizedObserved = observedOutputs.isEmpty()
                    ? List.of() : normalizeOutputs(observedOutputs);
            if (normalizedExpected == null || normalizedObserved == null
                    || reservedInputs.stream().anyMatch(java.util.Objects::isNull))
                throw new IllegalArgumentException("Invalid journal lot list");
            Map<ReservationKey, Long> normalizedInputs = new LinkedHashMap<>();
            for (ReservationLot lot : reservedInputs) {
                ReservationKey key = new ReservationKey(lot.itemKey(), lot.consumed());
                long total;
                try { total = Math.addExact(normalizedInputs.getOrDefault(key, 0L), lot.amount()); }
                catch (ArithmeticException overflow) { throw new IllegalArgumentException("Reservation overflow"); }
                if (total > MAX_AMOUNT) throw new IllegalArgumentException("Reservation amount overflow");
                normalizedInputs.put(key, total);
            }
            List<ReservationLot> mergedInputs = new ArrayList<>();
            normalizedInputs.forEach((key, amount) -> mergedInputs.add(new ReservationLot(key.itemKey(), amount, key.consumed())));
            expectedOutputs = normalizedExpected;
            observedOutputs = normalizedObserved;
            reservedInputs = List.copyOf(mergedInputs);
        }
        private Entry with(Phase next, List<OutputLot> observed) {
            return new Entry(jobId, stepIndex, operationId, machineAlias, machineX, machineY, machineZ,
                    recipeId, recipeType, crafts, next.key(), expectedOutputs, observed, reservedInputs);
        }
        private boolean sameIntent(Entry other) {
            return jobId == other.jobId && stepIndex == other.stepIndex
                    && operationId.equals(other.operationId) && machineAlias.equals(other.machineAlias)
                    && machineX == other.machineX && machineY == other.machineY && machineZ == other.machineZ
                    && recipeId.equals(other.recipeId) && recipeType.equals(other.recipeType)
                    && crafts == other.crafts && expectedOutputs.equals(other.expectedOutputs)
                    && reservedInputs.equals(other.reservedInputs);
        }
        private CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putLong("job", jobId); tag.putInt("step", stepIndex); tag.putString("operation", operationId);
            tag.putString("machine", machineAlias); tag.putInt("x", machineX); tag.putInt("y", machineY); tag.putInt("z", machineZ);
            tag.putString("recipe", recipeId); tag.putString("recipe_type", recipeType); tag.putLong("crafts", crafts);
            tag.putString("phase", phase); tag.put("expected", writeLots(expectedOutputs));
            tag.put("observed", writeLots(observedOutputs)); tag.put("reserved_inputs", writeReservationLots(reservedInputs));
            return tag;
        }
        private static Entry read(CompoundTag tag) {
            try {
                List<OutputLot> expected = readLots(tag.getList("expected", 10));
                List<OutputLot> observed = readLots(tag.getList("observed", 10));
                List<ReservationLot> reserved = tag.contains("reserved_inputs")
                        ? readReservationLots(tag.getList("reserved_inputs", 10)) : List.of();
                if (expected == null || observed == null || reserved == null) return null;
                return new Entry(tag.getLong("job"), tag.getInt("step"), tag.getString("operation"),
                        tag.getString("machine"), tag.getInt("x"), tag.getInt("y"), tag.getInt("z"),
                        tag.getString("recipe"), tag.getString("recipe_type"), tag.getLong("crafts"),
                        tag.getString("phase"), expected, observed, reserved);
            } catch (RuntimeException invalid) { return null; }
        }
    }

    private static ListTag writeLots(List<OutputLot> lots) {
        ListTag list = new ListTag();
        for (OutputLot lot : lots) {
            CompoundTag tag = new CompoundTag(); tag.putString("item", lot.itemKey()); tag.putLong("amount", lot.amount()); list.add(tag);
        }
        return list;
    }
    private static List<OutputLot> readLots(ListTag list) {
        if (list.size() > MAX_OUTPUTS) return null;
        List<OutputLot> result = new ArrayList<>();
        try { for (int i = 0; i < list.size(); i++) { CompoundTag tag = list.getCompound(i); result.add(new OutputLot(tag.getString("item"), tag.getLong("amount"))); } }
        catch (RuntimeException invalid) { return null; }
        return List.copyOf(result);
    }
    private static boolean validItemKey(String value) {
        if (value == null || value.isBlank() || value.length() > 4_000) return false;
        int split = value.indexOf('@'); String id = split < 0 ? value : value.substring(0, split);
        return validId(id);
    }
    private static boolean validId(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TEXT) return false;
        int split = value.indexOf(':');
        return split > 0 && split == value.lastIndexOf(':') && split < value.length() - 1
                && value.substring(0, split).matches("[a-z0-9_.-]+")
                && value.substring(split + 1).matches("[a-z0-9/._-]+");
    }
    private static boolean validToken(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_TEXT
                && value.matches("[A-Za-z0-9_.:-]+");
    }
}
