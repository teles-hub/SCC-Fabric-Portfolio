package de.scc.calculator.network;

import de.scc.calculator.calc.StackKey;
import de.scc.calculator.platform.storage.SccItemHandler;
import de.scc.calculator.SccLog;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Large-capacity item inventory reconstructed from SCC 1.6.5 bytecode and
 * adapted to the Fabric-side SccItemHandler contract. Each entry can hold up
 * to Integer.MAX_VALUE items, so storage capacity is not limited to 64 stacks.
 */
public final class SccItemStore implements SccItemHandler, BulkItemStore {
    private static final int MAX_ENTRY_COUNT = Integer.MAX_VALUE;
    private static final int MAX_ESCROW_OPERATIONS = 256;
    private static final int MAX_ESCROW_LOTS = 64;
    private static final long MAX_ESCROW_AMOUNT = 1_000_000_000_000L;
    private static final int MAX_OUTPUT_OPERATIONS = 256;
    private static final int MAX_OUTPUT_LOTS = 64;
    private static final long SUMMARY_REFRESH_NANOS = 1_000_000_000L;
    private static final Set<ResourceLocation> REPAIRED = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceLocation> WARNED = ConcurrentHashMap.newKeySet();

    private long capacity;
    private final Runnable changed;
    private final List<ItemStack> content = new ArrayList<>();
    private long stored;
    private final Map<Item, List<ItemStack>> byItem = new IdentityHashMap<>();
    private final Map<ItemStack, StackKey> keys = new IdentityHashMap<>();
    private Object keysFor;
    private LongSupplier kindLimit = () -> 0L;
    private long version;
    private List<Kind> summaryCache;
    private long summaryVersion = -1L;
    private int summaryLimit = -1;
    private long summaryAt;
    private final List<CompoundTag> unreadable = new ArrayList<>();
    private final Map<String, EscrowOperation> escrowOperations = new LinkedHashMap<>();
    private final List<CompoundTag> unreadableEscrowOperations = new ArrayList<>();
    private final Map<String, OutputOperation> outputOperations = new LinkedHashMap<>();
    private final List<CompoundTag> unreadableOutputOperations = new ArrayList<>();

    public SccItemStore(long capacity, Runnable changed) {
        this.capacity = capacity;
        this.changed = changed == null ? () -> { } : changed;
    }

    private void addEntry(ItemStack stack) {
        content.add(stack);
        byItem.computeIfAbsent(stack.getItem(), ignored -> new ArrayList<>(1)).add(stack);
    }

    private void removeEntry(int index) {
        ItemStack removed = content.remove(index);
        keys.remove(removed);
        List<ItemStack> entries = byItem.get(removed.getItem());
        if (entries != null) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i) == removed) {
                    entries.remove(i);
                    break;
                }
            }
            if (entries.isEmpty()) byItem.remove(removed.getItem());
        }
    }

    private void removeEntry(ItemStack stack) {
        for (int i = 0; i < content.size(); i++) {
            if (content.get(i) == stack) {
                removeEntry(i);
                return;
            }
        }
    }

    @Override
    public StackKey keyOf(ItemStack stack) {
        checkKeys();
        return keyOfChecked(stack);
    }

    @Override
    public void keyed(BiConsumer<ItemStack, StackKey> consumer) {
        Objects.requireNonNull(consumer, "consumer");
        checkKeys();
        for (ItemStack stack : content) {
            if (!stack.isEmpty()) consumer.accept(stack, keyOfChecked(stack));
        }
    }

    public void forgetKeys() {
        keys.clear();
        version++;
    }

    private void checkKeys() {
        Object currentGeneration = StackKey.generation();
        if (currentGeneration != keysFor) {
            keys.clear();
            keysFor = currentGeneration;
        }
    }

    private StackKey keyOfChecked(ItemStack stack) {
        StackKey cached = keys.get(stack);
        if (cached != null) return cached;
        StackKey key = stack == null || stack.isEmpty() ? null : StackKey.of(stack);
        if (key != null && entriesOf(stack.getItem()).contains(stack)) keys.put(stack, key);
        return key;
    }

    private List<ItemStack> entriesOf(Item item) {
        List<ItemStack> entries = byItem.get(item);
        return entries == null ? List.of() : entries;
    }

    @Override
    public boolean unlimited() { return capacity < 0L; }

    public void setCapacity(long capacity) { this.capacity = capacity; }

    public void limitPerKind(LongSupplier supplier) {
        this.kindLimit = supplier == null ? () -> 0L : supplier;
    }

    public long countOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return 0L;
        ItemStack found = find(stack);
        return found == null ? 0L : found.getCount();
    }

    private long kindRoom(ItemStack stack) {
        long limit = kindLimit.getAsLong();
        if (limit <= 0L) return Long.MAX_VALUE;
        if (stack == null || stack.isEmpty()) return limit;
        StackKey key = StackKey.of(stack);
        long visible = key == null ? 0L : count(key);
        long reserved = reservedCountFor(stack);
        return Math.max(0L, limit - visible - reserved);
    }

    private long reservedCountFor(ItemStack exemplar) {
        if (exemplar == null || exemplar.isEmpty()) return 0L;
        long total = 0L;
        for (EscrowOperation operation : escrowOperations.values()) {
            if (operation.state() != ReservationState.ACTIVE) continue;
            for (ReservationLot lot : operation.lots()) {
                StackKey key = StackKey.decode(lot.itemKey());
                if (key != null && key.matches(exemplar)) total = saturatedAdd(total, lot.amount());
            }
        }
        return total;
    }

    public long version() { return version; }

    private void touched() {
        version++;
        summaryCache = null;
        changed.run();
    }

    @Override
    public long stored() { return stored; }

    @Override
    public long capacity() { return capacity < 0L ? Long.MAX_VALUE : capacity; }

    public long room() {
        if (!unreadableEscrowOperations.isEmpty() || !unreadableOutputOperations.isEmpty()) return 0L;
        return capacity < 0L
                ? Math.max(0L, Long.MAX_VALUE - stored)
                : Math.max(0L, capacity - stored);
    }

    public boolean hasUnreadable() { return !unreadable.isEmpty() || !unreadableEscrowOperations.isEmpty()
            || !unreadableOutputOperations.isEmpty(); }

    /** Deep snapshot used by Fabric Transfer API transactions. */
    public State snapshotState() {
        List<ItemStack> entries = content.stream().map(ItemStack::copy).toList();
        List<CompoundTag> unreadableCopy = unreadable.stream().map(CompoundTag::copy).toList();
        return new State(entries, unreadableCopy, List.copyOf(escrowOperations.values()),
                unreadableEscrowOperations.stream().map(CompoundTag::copy).toList(),
                List.copyOf(outputOperations.values()),
                unreadableOutputOperations.stream().map(CompoundTag::copy).toList());
    }

    /** Restores a snapshot and rebuilds the identity indexes without retaining stale ItemStack keys. */
    public void restoreState(State state) {
        Objects.requireNonNull(state, "state");
        content.clear();
        byItem.clear();
        keys.clear();
        keysFor = null;
        stored = 0L;
        unreadable.clear();
        escrowOperations.clear();
        unreadableEscrowOperations.clear();
        outputOperations.clear();
        unreadableOutputOperations.clear();
        for (ItemStack stack : state.entries()) {
            if (stack == null || stack.isEmpty()) continue;
            ItemStack copy = stack.copy();
            addEntry(copy);
            stored = saturatedAdd(stored, copy.getCount());
        }
        for (CompoundTag tag : state.unreadable()) unreadable.add(tag.copy());
        for (EscrowOperation operation : state.escrowOperations()) {
            if (operation == null || escrowOperations.containsKey(operation.operationId())) continue;
            escrowOperations.put(operation.operationId(), operation);
            if (operation.state() == ReservationState.ACTIVE) {
                for (ReservationLot lot : operation.lots()) stored = saturatedAdd(stored, lot.amount());
            }
        }
        for (CompoundTag tag : state.unreadableEscrowOperations()) unreadableEscrowOperations.add(tag.copy());
        for (OutputOperation operation : state.outputOperations()) {
            if (operation == null || outputOperations.containsKey(operation.operationId())) continue;
            outputOperations.put(operation.operationId(), operation);
        }
        for (CompoundTag tag : state.unreadableOutputOperations()) unreadableOutputOperations.add(tag.copy());
        version++;
        summaryCache = null;
        summaryVersion = -1L;
        // A transaction rollback restores the data, but must also refresh the
        // BlockEntity's dirty state, fill property and redstone output.
        changed.run();
    }

    public record State(List<ItemStack> entries, List<CompoundTag> unreadable,
                        List<EscrowOperation> escrowOperations,
                        List<CompoundTag> unreadableEscrowOperations,
                        List<OutputOperation> outputOperations,
                        List<CompoundTag> unreadableOutputOperations) {
        public State(List<ItemStack> entries, List<CompoundTag> unreadable) {
            this(entries, unreadable, List.of(), List.of(), List.of(), List.of());
        }
        public State(List<ItemStack> entries, List<CompoundTag> unreadable,
                     List<EscrowOperation> escrowOperations, List<CompoundTag> unreadableEscrowOperations) {
            this(entries, unreadable, escrowOperations, unreadableEscrowOperations, List.of(), List.of());
        }
        public State {
            entries = entries == null ? List.of() : entries.stream().map(ItemStack::copy).toList();
            unreadable = unreadable == null ? List.of() : unreadable.stream().map(CompoundTag::copy).toList();
            escrowOperations = escrowOperations == null ? List.of() : List.copyOf(escrowOperations);
            unreadableEscrowOperations = unreadableEscrowOperations == null ? List.of()
                    : unreadableEscrowOperations.stream().map(CompoundTag::copy).toList();
            outputOperations = outputOperations == null ? List.of() : List.copyOf(outputOperations);
            unreadableOutputOperations = unreadableOutputOperations == null ? List.of()
                    : unreadableOutputOperations.stream().map(CompoundTag::copy).toList();
        }
    }

    public enum ReservationState { ACTIVE, RELEASED, CONSUMED }

    public record ReservationLot(String itemKey, long amount, boolean consumed) {
        public ReservationLot {
            if (itemKey == null || itemKey.isBlank() || itemKey.length() > 4_000
                    || StackKey.decode(itemKey) == null || amount < 1L || amount > MAX_ESCROW_AMOUNT) {
                throw new IllegalArgumentException("Invalid durable reservation lot");
            }
        }
    }

    public record EscrowOperation(String operationId, ReservationState state, List<ReservationLot> lots) {
        public EscrowOperation {
            if (!validOperationId(operationId) || state == null || lots == null || lots.isEmpty()
                    || lots.size() > MAX_ESCROW_LOTS || lots.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Invalid durable escrow operation");
            }
            lots = List.copyOf(lots);
        }
    }

    private static boolean validOperationId(String value) {
        return value != null && !value.isBlank() && value.length() <= 256
                && value.matches("[A-Za-z0-9_.:-]+");
    }

    public record OutputLot(String itemKey, long amount) {
        public OutputLot {
            if (itemKey == null || itemKey.isBlank() || itemKey.length() > 4_000
                    || StackKey.decode(itemKey) == null || amount < 1L || amount > MAX_ESCROW_AMOUNT) {
                throw new IllegalArgumentException("Invalid durable output lot");
            }
        }
    }

    public record OutputOperation(String operationId, List<OutputLot> lots) {
        public OutputOperation {
            if (!validOperationId(operationId) || lots == null || lots.isEmpty()
                    || lots.size() > MAX_OUTPUT_LOTS || lots.stream().anyMatch(Objects::isNull)) {
                throw new IllegalArgumentException("Invalid durable output operation");
            }
            lots = List.copyOf(lots);
        }
    }
    public int types() { return content.size(); }
    public List<ItemStack> entries() { return Collections.unmodifiableList(content); }

    /**
     * Moves available items into a durable per-storage escrow record. The visible
     * inventory count decreases, but physical stored capacity is unchanged.
     * Repeating the same operation/key request returns the already reserved amount.
     */
    public synchronized long reserveForOperation(String operationId, String itemKey,
                                                  boolean consumed, long requested) {
        if (!validOperationId(operationId) || itemKey == null || itemKey.length() > 4_000
                || requested < 1L || requested > MAX_ESCROW_AMOUNT
                || !unreadableEscrowOperations.isEmpty()) return -1L;
        StackKey key = StackKey.decode(itemKey);
        if (key == null) return -1L;
        EscrowOperation operation = escrowOperations.get(operationId);
        if (operation != null && operation.state() != ReservationState.ACTIVE) return -1L;
        if (operation != null) {
            for (ReservationLot lot : operation.lots()) {
                if (lot.itemKey().equals(itemKey) && lot.consumed() == consumed) return lot.amount();
            }
            if (operation.lots().size() >= MAX_ESCROW_LOTS) return -1L;
        } else if (escrowOperations.size() >= MAX_ESCROW_OPERATIONS) {
            // Never evict a terminal idempotency marker automatically: only the
            // scheduler may acknowledge and remove it after durable completion.
            return -1L;
        }
        long moved = takeForEscrow(key, requested);
        if (moved <= 0L) return 0L;
        List<ReservationLot> lots = new ArrayList<>(operation == null ? List.of() : operation.lots());
        lots.add(new ReservationLot(itemKey, moved, consumed));
        escrowOperations.put(operationId, new EscrowOperation(operationId, ReservationState.ACTIVE, lots));
        touched();
        return moved;
    }

    /** Returns only active lots; terminal markers remain until acknowledged. */
    public synchronized EscrowOperation reservationOperation(String operationId) {
        return operationId == null ? null : escrowOperations.get(operationId);
    }

    public synchronized boolean releaseReservation(String operationId) {
        EscrowOperation operation = escrowOperations.get(operationId);
        if (operation == null || operation.state() == ReservationState.RELEASED) return true;
        if (operation.state() != ReservationState.ACTIVE) return false;
        for (ReservationLot lot : operation.lots()) {
            StackKey key = StackKey.decode(lot.itemKey());
            if (key == null || key.stack(1).isEmpty()) return false;
        }
        for (ReservationLot lot : operation.lots()) addReservedToContent(lot);
        escrowOperations.put(operationId, new EscrowOperation(operationId, ReservationState.RELEASED, operation.lots()));
        touched();
        return true;
    }

    /**
     * Finalizes a successful operation: consumed lots leave physical storage,
     * while reusable catalysts are returned to the visible inventory.
     */
    public synchronized boolean finishReservation(String operationId) {
        EscrowOperation operation = escrowOperations.get(operationId);
        if (operation == null || operation.state() == ReservationState.CONSUMED) return true;
        if (operation.state() != ReservationState.ACTIVE) return false;
        long consumedTotal = 0L;
        try {
            for (ReservationLot lot : operation.lots()) {
                StackKey key = StackKey.decode(lot.itemKey());
                if (key == null || key.stack(1).isEmpty()) return false;
                if (lot.consumed()) consumedTotal = Math.addExact(consumedTotal, lot.amount());
            }
        } catch (ArithmeticException overflow) {
            return false;
        }
        if (consumedTotal > stored) return false;
        for (ReservationLot lot : operation.lots()) {
            if (!lot.consumed()) addReservedToContent(lot);
        }
        stored -= consumedTotal;
        escrowOperations.put(operationId, new EscrowOperation(operationId, ReservationState.CONSUMED, operation.lots()));
        touched();
        return true;
    }

    /** Backward-compatible alias; successful completion returns reusable inputs. */
    public synchronized boolean consumeReservation(String operationId) {
        return finishReservation(operationId);
    }

    /** Drops a terminal idempotency marker only after the caller durably acknowledged the operation. */
    public synchronized boolean acknowledgeReservationOperation(String operationId) {
        EscrowOperation operation = escrowOperations.get(operationId);
        if (operation == null) return true;
        if (operation.state() == ReservationState.ACTIVE) return false;
        escrowOperations.remove(operationId);
        touched();
        return true;
    }

    /**
     * Inserts machine output and its operation receipt into the same storage
     * BlockEntity payload. A retry returns the recorded amount instead of
     * inserting it again.
     */
    public synchronized long insertForOperation(String operationId, String itemKey, long requested) {
        if (!validOperationId(operationId) || itemKey == null || itemKey.length() > 4_000
                || requested < 1L || requested > MAX_ESCROW_AMOUNT
                || !unreadableOutputOperations.isEmpty() || !unreadableEscrowOperations.isEmpty()) return -1L;
        StackKey key = StackKey.decode(itemKey);
        if (key == null) return -1L;
        OutputOperation operation = outputOperations.get(operationId);
        if (operation != null) {
            for (OutputLot lot : operation.lots()) {
                if (lot.itemKey().equals(itemKey)) return lot.amount();
            }
            if (operation.lots().size() >= MAX_OUTPUT_LOTS) return -1L;
        } else if (outputOperations.size() >= MAX_OUTPUT_OPERATIONS) {
            return -1L;
        }

        ItemStack sample = key.stack(1);
        long accepted = Math.min(requested, Math.min(room(), kindRoom(sample)));
        if (accepted <= 0L) return 0L;
        insertOutputRaw(key, accepted);
        List<OutputLot> lots = new ArrayList<>(operation == null ? List.of() : operation.lots());
        lots.add(new OutputLot(itemKey, accepted));
        outputOperations.put(operationId, new OutputOperation(operationId, lots));
        touched();
        return accepted;
    }

    public synchronized OutputOperation outputOperation(String operationId) {
        return operationId == null ? null : outputOperations.get(operationId);
    }

    /** Removes output receipts only after the machine acknowledgement is durable. */
    public synchronized boolean acknowledgeOutputOperation(String operationId) {
        OutputOperation operation = outputOperations.get(operationId);
        if (operation == null) return true;
        outputOperations.remove(operationId);
        touched();
        return true;
    }

    private void insertOutputRaw(StackKey key, long amount) {
        long remaining = amount;
        while (remaining > 0L) {
            ItemStack sample = key.stack(1);
            ItemStack target = null;
            for (ItemStack candidate : entriesOf(key.item())) {
                if (key.matches(candidate) && candidate.getCount() < MAX_ENTRY_COUNT) {
                    target = candidate;
                    break;
                }
            }
            int moved = (int) Math.min(remaining, target == null
                    ? (long) MAX_ENTRY_COUNT : (long) MAX_ENTRY_COUNT - target.getCount());
            if (target == null) addEntry(sample.copyWithCount(moved));
            else target.grow(moved);
            stored += moved;
            remaining -= moved;
        }
    }

    private long takeForEscrow(StackKey key, long requested) {
        long remaining = requested;
        List<ItemStack> scan = new ArrayList<>(entriesOf(key.item()));
        for (ItemStack entry : scan) {
            if (remaining <= 0L) break;
            if (!key.matches(entry)) continue;
            int take = (int) Math.min(remaining, (long) entry.getCount());
            if (take >= entry.getCount()) removeEntry(entry);
            else entry.shrink(take);
            remaining -= take;
        }
        return requested - remaining;
    }

    private void addReservedToContent(ReservationLot lot) {
        StackKey key = StackKey.decode(lot.itemKey());
        long remaining = lot.amount();
        while (remaining > 0L) {
            ItemStack sample = key.stack(1);
            ItemStack target = null;
            for (ItemStack candidate : entriesOf(key.item())) {
                if (key.matches(candidate) && candidate.getCount() < MAX_ENTRY_COUNT) {
                    target = candidate;
                    break;
                }
            }
            int amount = (int) Math.min(remaining, target == null
                    ? (long) MAX_ENTRY_COUNT : (long) MAX_ENTRY_COUNT - target.getCount());
            if (target == null) addEntry(sample.copyWithCount(amount));
            else target.grow(amount);
            remaining -= amount;
        }
    }

    private static CompoundTag writeEscrowOperation(EscrowOperation operation) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", operation.operationId());
        tag.putString("state", operation.state().name());
        ListTag lots = new ListTag();
        for (ReservationLot lot : operation.lots()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("item", lot.itemKey());
            entry.putLong("amount", lot.amount());
            entry.putString("consumed", Boolean.toString(lot.consumed()));
            lots.add(entry);
        }
        tag.put("lots", lots);
        return tag;
    }

    private static CompoundTag writeOutputOperation(OutputOperation operation) {
        CompoundTag tag = new CompoundTag();
        tag.putString("id", operation.operationId());
        ListTag lots = new ListTag();
        for (OutputLot lot : operation.lots()) {
            CompoundTag entry = new CompoundTag();
            entry.putString("item", lot.itemKey());
            entry.putLong("amount", lot.amount());
            lots.add(entry);
        }
        tag.put("lots", lots);
        return tag;
    }

    private static OutputOperation readOutputOperation(CompoundTag tag) {
        if (tag == null) return null;
        try {
            String id = tag.getString("id");
            ListTag encoded = tag.getList("lots", Tag.TAG_COMPOUND);
            if (encoded.size() > MAX_OUTPUT_LOTS) return null;
            List<OutputLot> lots = new ArrayList<>();
            Set<String> unique = new java.util.HashSet<>();
            for (int i = 0; i < encoded.size(); i++) {
                CompoundTag entry = encoded.getCompound(i);
                OutputLot lot = new OutputLot(entry.getString("item"), entry.getLong("amount"));
                if (!unique.add(lot.itemKey())) return null;
                lots.add(lot);
            }
            return new OutputOperation(id, lots);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    private static EscrowOperation readEscrowOperation(CompoundTag tag) {
        if (tag == null) return null;
        try {
            String id = tag.getString("id");
            ReservationState state = ReservationState.valueOf(tag.getString("state"));
            ListTag encoded = tag.getList("lots", Tag.TAG_COMPOUND);
            if (encoded.size() > MAX_ESCROW_LOTS) return null;
            List<ReservationLot> lots = new ArrayList<>();
            Set<String> uniqueLots = new java.util.HashSet<>();
            for (int i = 0; i < encoded.size(); i++) {
                CompoundTag entry = encoded.getCompound(i);
                ReservationLot lot = new ReservationLot(entry.getString("item"), entry.getLong("amount"),
                        Boolean.parseBoolean(entry.getString("consumed")));
                if (!uniqueLots.add(lot.itemKey() + "|" + lot.consumed())) return null;
                lots.add(lot);
            }
            return new EscrowOperation(id, state, lots);
        } catch (RuntimeException invalid) {
            return null;
        }
    }

    @Override
    public ItemStack takeMatching(Predicate<ItemStack> predicate, int amount, boolean simulate) {
        if (amount <= 0 || predicate == null) return ItemStack.EMPTY;
        ItemStack result = ItemStack.EMPTY;
        int remaining = amount;
        boolean mutated = false;
        int index = 0;
        while (index < content.size() && remaining > 0) {
            ItemStack entry = content.get(index);
            if (!predicate.test(entry) || (!result.isEmpty() && !ItemStack.isSameItemSameComponents(result, entry))) {
                index++;
                continue;
            }
            int take = Math.min(remaining, entry.getCount());
            if (result.isEmpty()) result = entry.copyWithCount(take);
            else result.grow(take);
            remaining -= take;
            if (simulate) {
                index++;
                continue;
            }
            mutated = true;
            stored -= take;
            if (take >= entry.getCount()) removeEntry(index);
            else {
                entry.shrink(take);
                index++;
            }
        }
        if (mutated) touched();
        return result;
    }

    @Override
    public ItemStack take(StackKey key, ItemStack exemplar, int amount, boolean simulate) {
        if (amount <= 0 || key == null) return ItemStack.EMPTY;
        List<ItemStack> source = entriesOf(key.item());
        if (source.isEmpty()) return ItemStack.EMPTY;
        List<ItemStack> scan = simulate ? source : new ArrayList<>(source);
        ItemStack result = ItemStack.EMPTY;
        int remaining = amount;
        boolean mutated = false;
        for (ItemStack entry : scan) {
            if (remaining <= 0) break;
            if (!key.matches(entry)) continue;
            if (exemplar != null && !exemplar.isEmpty() && !ItemStack.isSameItemSameComponents(exemplar, entry)) continue;
            if (!result.isEmpty() && !ItemStack.isSameItemSameComponents(result, entry)) continue;
            int take = Math.min(remaining, entry.getCount());
            if (result.isEmpty()) result = entry.copyWithCount(take);
            else result.grow(take);
            remaining -= take;
            if (simulate) break;
            mutated = true;
            stored -= take;
            if (take >= entry.getCount()) removeEntry(entry);
            else entry.shrink(take);
        }
        if (mutated) touched();
        return result;
    }

    @Override
    public long count(StackKey key) {
        if (key == null) return 0L;
        long total = 0L;
        for (ItemStack entry : entriesOf(key.item())) {
            if (key.matches(entry)) total = saturatedAdd(total, entry.getCount());
        }
        return total;
    }

    @Override
    public boolean has(StackKey key) {
        if (key == null) return false;
        for (ItemStack entry : entriesOf(key.item())) if (entry.getCount() > 0 && key.matches(entry)) return true;
        return false;
    }

    @Override
    public long countItem(Item item) {
        long total = 0L;
        for (ItemStack entry : entriesOf(item)) {
            if (!entry.isEmpty()) total = saturatedAdd(total, entry.getCount());
        }
        return total;
    }

    public List<Kind> summary() { return summary(Integer.MAX_VALUE); }

    public List<Kind> summary(int requestedLimit) {
        int limit = Math.max(0, requestedLimit);
        long now = System.nanoTime();
        if (summaryCache != null && summaryLimit == limit) {
            if (summaryVersion == version || (limit != Integer.MAX_VALUE && now - summaryAt < SUMMARY_REFRESH_NANOS)) {
                return summaryCache;
            }
        }

        List<KindAccumulator> totals = new ArrayList<>();
        for (ItemStack entry : content) {
            if (entry.isEmpty()) continue;
            KindAccumulator accumulator = null;
            for (KindAccumulator candidate : totals) {
                if (ItemStack.isSameItemSameComponents(candidate.item, entry)) {
                    accumulator = candidate;
                    break;
                }
            }
            if (accumulator == null) totals.add(new KindAccumulator(entry.copyWithCount(1), entry.getCount()));
            else accumulator.count = saturatedAdd(accumulator.count, entry.getCount());
        }

        List<Kind> sorted = new ArrayList<>(totals.size());
        for (KindAccumulator entry : totals) sorted.add(new Kind(entry.item, entry.count));
        sorted.sort((left, right) -> Long.compare(right.count(), left.count()));
        if (sorted.size() > limit) sorted = new ArrayList<>(sorted.subList(0, limit));

        summaryCache = Collections.unmodifiableList(sorted);
        summaryVersion = version;
        summaryLimit = limit;
        summaryAt = now;
        return summaryCache;
    }

    @Override
    public boolean indexed() { return true; }

    public boolean indexConsistent() {
        Set<ItemStack> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        seen.addAll(content);
        int count = 0;
        for (Map.Entry<Item, List<ItemStack>> group : byItem.entrySet()) {
            if (group.getValue().isEmpty()) return false;
            int previousIndex = -1;
            for (ItemStack entry : group.getValue()) {
                if (!seen.contains(entry) || entry.getItem() != group.getKey()) return false;
                int index = identityIndex(content, entry);
                if (index <= previousIndex) return false;
                previousIndex = index;
                count++;
            }
        }
        return count == content.size();
    }

    private static int identityIndex(List<ItemStack> list, ItemStack wanted) {
        for (int i = 0; i < list.size(); i++) if (list.get(i) == wanted) return i;
        return -1;
    }

    private ItemStack entry(int slot) {
        return slot >= 0 && slot < content.size() ? content.get(slot) : ItemStack.EMPTY;
    }

    @Override
    public int getSlots() { return content.size() + (room() > 0L ? 1 : 0); }

    @Override
    public ItemStack getStackInSlot(int slot) { return entry(slot); }

    @Override
    public int getSlotLimit(int slot) {
        ItemStack entry = entry(slot);
        if (entry.isEmpty()) return (int) Math.min(64L, room());
        long free = Math.min((long) Integer.MAX_VALUE, room());
        return (int) Math.min((long) Integer.MAX_VALUE, (long) entry.getCount() + free);
    }

    @Override
    public boolean isItemValid(int slot, ItemStack stack) { return true; }

    @Override
    public void setStackInSlot(int slot, ItemStack stack) {
        throw new UnsupportedOperationException(
                "SccItemStore is a bulk IItemHandler; use insertItem/extractItem instead of direct slot mutation");
    }

    @Override
    public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
        if (stack == null || stack.isEmpty() || slot < 0 || slot >= getSlots()) return stack == null ? ItemStack.EMPTY : stack;
        long freeRoom = room();
        if (freeRoom <= 0L) return stack;

        ItemStack target = entry(slot);
        if (target.isEmpty()) {
            ItemStack existing = find(stack);
            if (simulate && existing != null) return stack;
            target = existing == null ? ItemStack.EMPTY : existing;
        } else if (!ItemStack.isSameItemSameComponents(target, stack)) {
            return stack;
        }

        int accepted = (int) Math.min(Math.min(freeRoom, kindRoom(target.isEmpty() ? stack : target)), stack.getCount());
        if (!target.isEmpty()) accepted = (int) Math.min((long) accepted, (long) Integer.MAX_VALUE - target.getCount());
        if (accepted <= 0) return stack;

        if (!simulate) {
            if (target.isEmpty()) addEntry(stack.copyWithCount(accepted));
            else target.grow(accepted);
            stored += accepted;
            touched();
        }
        return accepted >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - accepted);
    }

    public ItemStack insertAnywhere(ItemStack stack, boolean simulate) {
        if (stack == null || stack.isEmpty()) return stack == null ? ItemStack.EMPTY : stack;
        long freeRoom = room();
        if (freeRoom <= 0L) return stack;
        ItemStack target = find(stack);
        int accepted = (int) Math.min(Math.min(freeRoom, kindRoom(target == null ? stack : target)), stack.getCount());
        if (target != null) accepted = (int) Math.min((long) accepted, (long) Integer.MAX_VALUE - target.getCount());
        if (accepted <= 0) return stack;
        if (!simulate) {
            if (target == null) addEntry(stack.copyWithCount(accepted));
            else target.grow(accepted);
            stored += accepted;
            touched();
        }
        return accepted >= stack.getCount() ? ItemStack.EMPTY : stack.copyWithCount(stack.getCount() - accepted);
    }

    private ItemStack find(ItemStack target) {
        if (target == null || target.isEmpty()) return null;
        for (ItemStack entry : entriesOf(target.getItem())) {
            if (ItemStack.isSameItemSameComponents(entry, target)) return entry;
        }
        return null;
    }

    @Override
    public ItemStack extractItem(int slot, int amount, boolean simulate) {
        if (amount <= 0) return ItemStack.EMPTY;
        ItemStack target = entry(slot);
        if (target.isEmpty()) return ItemStack.EMPTY;
        int taken = Math.min(Math.min(amount, target.getMaxStackSize()), target.getCount());
        if (simulate) return target.copyWithCount(taken);
        ItemStack result = target.copyWithCount(taken);
        if (taken >= target.getCount()) removeEntry(slot);
        else target.shrink(taken);
        stored -= taken;
        touched();
        return result;
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        ListTag savedEntries = new ListTag();
        for (ItemStack entry : content) {
            if (entry.isEmpty()) continue;
            CompoundTag wrapper = new CompoundTag();
            wrapper.putInt("n", entry.getCount());
            wrapper.put("it", saveOne(entry, registries));
            savedEntries.add(wrapper);
        }
        for (CompoundTag savedUnreadable : unreadable) savedEntries.add(savedUnreadable.copy());
        CompoundTag tag = new CompoundTag();
        tag.put("entries", savedEntries);
        ListTag savedEscrow = new ListTag();
        for (EscrowOperation operation : escrowOperations.values()) savedEscrow.add(writeEscrowOperation(operation));
        for (CompoundTag unreadableOperation : unreadableEscrowOperations) savedEscrow.add(unreadableOperation.copy());
        tag.put("scc_escrow_operations", savedEscrow);
        ListTag savedOutputs = new ListTag();
        for (OutputOperation operation : outputOperations.values()) savedOutputs.add(writeOutputOperation(operation));
        for (CompoundTag unreadableOperation : unreadableOutputOperations) savedOutputs.add(unreadableOperation.copy());
        tag.put("scc_output_operations", savedOutputs);
        return tag;
    }

    private static Tag saveOne(ItemStack stack, HolderLookup.Provider registries) {
        ResourceLocation itemId = BuiltInRegistries.ITEM.getKey(stack.getItem());
        try {
            DataComponentPatch repair = StackKey.patchInSet(stack, registries);
            if (repair != null) {
                // SCC applies this patch to the stored stack before serializing it.
                stack.applyComponents(repair);
                if (itemId != null && REPAIRED.add(itemId)) {
                    SccLog.warn("storage", "Repaired item component patch before saving " + itemId);
                }
            }
        } catch (Throwable ignored) {
            // Some mods expose component codecs that are not available in this registry context.
            // Preserve the same best-effort fallback behavior as the original SCC implementation.
        }

        try {
            return stack.copyWithCount(1).save(registries);
        } catch (Throwable failure) {
            if (itemId != null && WARNED.add(itemId)) {
                SccLog.warn("storage", "Could not fully serialize " + itemId + ": " + failure);
            }
            try {
                return new ItemStack(stack.getItem()).save(registries);
            } catch (Throwable ignoredAgain) {
                CompoundTag fallback = new CompoundTag();
                if (itemId != null) fallback.putString("id", itemId.toString());
                fallback.putInt("count", 1);
                return fallback;
            }
        }
    }

    public void load(CompoundTag tag, HolderLookup.Provider registries) {
        version++;
        content.clear();
        byItem.clear();
        keys.clear();
        stored = 0L;
        unreadable.clear();
        escrowOperations.clear();
        unreadableEscrowOperations.clear();
        summaryCache = null;

        ListTag savedEntries = tag.getList("entries", Tag.TAG_COMPOUND);
        for (int i = 0; i < savedEntries.size(); i++) {
            CompoundTag wrapper = savedEntries.getCompound(i);
            CompoundTag itemTag = wrapper.getCompound("it");
            ItemStack parsed = ItemStack.parseOptional(registries, itemTag);
            if (parsed.isEmpty()) {
                if (itemTag.contains("id")) unreadable.add(wrapper.copy());
                continue;
            }
            add(parsed.copyWithCount(1), Math.max(1, wrapper.getInt("n")));
        }

        // Legacy list used by older SCC versions before the long-count entry format.
        ListTag legacyItems = tag.getList("items", Tag.TAG_COMPOUND);
        for (int i = 0; i < legacyItems.size(); i++) {
            ItemStack parsed = ItemStack.parseOptional(registries, legacyItems.getCompound(i).getCompound("it"));
            if (!parsed.isEmpty()) add(parsed.copyWithCount(1), parsed.getCount());
        }

        ListTag savedEscrow = tag.getList("scc_escrow_operations", Tag.TAG_COMPOUND);
        if (savedEscrow.size() > MAX_ESCROW_OPERATIONS) {
            for (int i = 0; i < savedEscrow.size(); i++) unreadableEscrowOperations.add(savedEscrow.getCompound(i).copy());
        } else {
            for (int i = 0; i < savedEscrow.size(); i++) {
                CompoundTag encoded = savedEscrow.getCompound(i);
                EscrowOperation operation = readEscrowOperation(encoded);
                if (operation == null || escrowOperations.containsKey(operation.operationId())) {
                    unreadableEscrowOperations.add(encoded.copy());
                    continue;
                }
                escrowOperations.put(operation.operationId(), operation);
                if (operation.state() == ReservationState.ACTIVE) {
                    for (ReservationLot lot : operation.lots()) stored = saturatedAdd(stored, lot.amount());
                }
            }
        }

        ListTag savedOutputs = tag.getList("scc_output_operations", Tag.TAG_COMPOUND);
        if (savedOutputs.size() > MAX_OUTPUT_OPERATIONS) {
            for (int i = 0; i < savedOutputs.size(); i++) unreadableOutputOperations.add(savedOutputs.getCompound(i).copy());
        } else {
            for (int i = 0; i < savedOutputs.size(); i++) {
                CompoundTag encoded = savedOutputs.getCompound(i);
                OutputOperation operation = readOutputOperation(encoded);
                if (operation == null || outputOperations.containsKey(operation.operationId())) {
                    unreadableOutputOperations.add(encoded.copy());
                    continue;
                }
                outputOperations.put(operation.operationId(), operation);
            }
        }
    }

    private void add(ItemStack prototype, int amount) {
        int safeAmount = Math.max(0, amount);
        if (safeAmount <= 0 || prototype == null || prototype.isEmpty()) return;
        ItemStack target = find(prototype);
        if (target != null) {
            int accepted = (int) Math.min((long) safeAmount, (long) Integer.MAX_VALUE - target.getCount());
            target.grow(accepted);
            stored += accepted;
        } else {
            addEntry(prototype.copyWithCount(safeAmount));
            stored += safeAmount;
        }
    }

    public record Kind(ItemStack item, long count) { }

    private static long saturatedAdd(long left, long right) {
        return right > 0L && left > Long.MAX_VALUE - right ? Long.MAX_VALUE : left + right;
    }

    private static final class KindAccumulator {
        private final ItemStack item;
        private long count;
        private KindAccumulator(ItemStack item, long count) { this.item = item; this.count = count; }
    }
}
