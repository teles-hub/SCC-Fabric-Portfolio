package de.scc.calculator.compat.cobblemon;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable, dimension-scoped receipt ledger for Campfire Pot machine operations.
 *
 * <p>Each entry records the exact operation payload digest before any ingredient
 * is moved. Repeating a dispatch with the same ID either resumes the recorded
 * placement set or returns the original outcome; a different digest is always
 * rejected. Phases are written through {@link #setDirty()} so a world save
 * preserves the machine-side idempotency token independently of SCC's journal.</p>
 */
public final class SccCobblemonPotLedger extends SavedData {
    public static final String DATA_ID = "scc_cobblemon_pot_operations";
    public static final Factory<SccCobblemonPotLedger> FACTORY = new Factory<>(
            SccCobblemonPotLedger::new,
            SccCobblemonPotLedger::load,
            DataFixTypes.LEVEL);

    public static final int MAX_ENTRIES = 256;
    public static final int MAX_TEXT = 4_000;
    public static final long MAX_AMOUNT = 1_000_000_000_000L;

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public SccCobblemonPotLedger() { }

    /** Returns the ledger for the dimension that owns the pot, or null off-server. */
    public static SccCobblemonPotLedger get(Level level) {
        if (!(level instanceof ServerLevel server) || server.getDataStorage() == null) return null;
        return server.getDataStorage().computeIfAbsent(FACTORY, DATA_ID);
    }

    /**
     * Read-only lookup for paths polled every five ticks. Unlike {@link #get(Level)}
     * this never creates or registers a SavedData, so {@code state()} and
     * {@code outputs()} have no side effects at all.
     */
    public static SccCobblemonPotLedger peek(Level level) {
        if (!(level instanceof ServerLevel server) || server.getDataStorage() == null) return null;
        return server.getDataStorage().get(FACTORY, DATA_ID);
    }

    /** Test/inspection helper that decodes a previously saved ledger tag. */
    public static SccCobblemonPotLedger fromNbt(CompoundTag tag) {
        SccCobblemonPotLedger ledger = new SccCobblemonPotLedger();
        ledger.loadInternal(tag);
        return ledger;
    }

    public synchronized Entry byOperationId(String operationId) {
        return operationId == null ? null : entries.get(operationId);
    }

    /** Creates a write-ahead intent; an existing ID is accepted only with an identical digest. */
    public synchronized boolean begin(String operationId, String digest, String machineKey) {
        if (!validToken(operationId) || !validToken(digest) || !validText(machineKey)) return false;
        Entry existing = entries.get(operationId);
        if (existing != null) return existing.digest().equals(digest) && existing.machineKey().equals(machineKey);
        if (entries.size() >= MAX_ENTRIES) pruneTerminal();
        if (entries.size() >= MAX_ENTRIES) return false;
        entries.put(operationId, new Entry(operationId, digest, machineKey, Phase.PREPARING,
                List.of(), List.of(), false, ""));
        setDirty();
        return true;
    }

    /** Records one slot placement so a retry can recognise items already in the pot. */
    public synchronized boolean place(String operationId, Placement placement) {
        Entry current = entries.get(operationId);
        if (current == null || current.phase() != Phase.PREPARING || placement == null) return false;
        if (current.placements().contains(placement)) return true;
        if (current.placements().size() >= 16) return false;
        List<Placement> placements = new ArrayList<>(current.placements());
        placements.add(placement);
        entries.put(operationId, current.with(Phase.PREPARING, placements, current.products(),
                current.escrowConsumed(), current.reason()));
        setDirty();
        return true;
    }

    public synchronized boolean markEscrowConsumed(String operationId) {
        Entry current = entries.get(operationId);
        if (current == null || current.phase() != Phase.PREPARING || current.escrowConsumed()) return false;
        entries.put(operationId, current.with(Phase.PREPARING, current.placements(), current.products(),
                true, current.reason()));
        setDirty();
        return true;
    }

    public synchronized boolean markReady(String operationId, List<Product> products) {
        Entry current = entries.get(operationId);
        List<Product> normalized = normalizeProducts(products);
        if (current == null || current.phase() != Phase.PREPARING || normalized == null) return false;
        entries.put(operationId, current.with(Phase.OUTPUT_READY, current.placements(),
                normalized, current.escrowConsumed(), ""));
        setDirty();
        return true;
    }

    public synchronized boolean markFailed(String operationId, String reason) {
        Entry current = entries.get(operationId);
        if (current == null || current.phase() == Phase.ACKNOWLEDGED || current.phase() == Phase.FAILED) return false;
        entries.put(operationId, current.with(Phase.FAILED, current.placements(), List.of(),
                current.escrowConsumed(), safeText(reason)));
        setDirty();
        return true;
    }

    public synchronized boolean markAcknowledged(String operationId) {
        Entry current = entries.get(operationId);
        if (current == null) return false;
        if (current.phase() == Phase.ACKNOWLEDGED) return true;
        if (current.phase() != Phase.OUTPUT_READY) return false;
        entries.put(operationId, current.with(Phase.ACKNOWLEDGED, List.of(), List.of(),
                current.escrowConsumed(), ""));
        setDirty();
        return true;
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag list = new ListTag();
        synchronized (this) {
            for (Entry entry : entries.values()) list.add(entry.write());
        }
        tag.put(DATA_ID, list);
        return tag;
    }

    private static SccCobblemonPotLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        SccCobblemonPotLedger ledger = new SccCobblemonPotLedger();
        ledger.loadInternal(tag);
        return ledger;
    }

    private void loadInternal(CompoundTag tag) {
        entries.clear();
        if (tag == null || !tag.contains(DATA_ID)) return;
        ListTag list = tag.getList(DATA_ID, 10);
        if (list.size() > MAX_ENTRIES) return;
        boolean repaired = false;
        for (int i = 0; i < list.size(); i++) {
            Entry decoded = Entry.read(list.getCompound(i));
            if (decoded == null || entries.containsKey(decoded.operationId())) {
                entries.clear();
                return;
            }
            // A crash between placing ingredients and observing the result is an
            // ambiguous machine state. Keep the receipt for idempotency, but
            // never promote it to an authoritative output.
            if (decoded.phase() == Phase.PREPARING) {
                decoded = decoded.with(Phase.FAILED, decoded.placements(), List.of(),
                        decoded.escrowConsumed(), "interrupted_preparing");
                repaired = true;
            }
            entries.put(decoded.operationId(), decoded);
        }
        if (repaired) setDirty();
    }

    private void pruneTerminal() {
        entries.entrySet().removeIf(entry -> entry.getValue().phase() == Phase.ACKNOWLEDGED
                || entry.getValue().phase() == Phase.FAILED);
    }

    private static List<Product> normalizeProducts(List<Product> products) {
        if (products == null || products.isEmpty() || products.size() > 64) return null;
        Map<String, Long> merged = new LinkedHashMap<>();
        for (Product product : products) {
            if (product == null || !validText(product.itemKey()) || product.amount() < 1L
                    || product.amount() > MAX_AMOUNT) return null;
            long total;
            try { total = Math.addExact(merged.getOrDefault(product.itemKey(), 0L), product.amount()); }
            catch (ArithmeticException overflow) { return null; }
            if (total > MAX_AMOUNT) return null;
            merged.put(product.itemKey(), total);
        }
        List<Product> result = new ArrayList<>();
        merged.forEach((key, amount) -> result.add(new Product(key, amount)));
        return List.copyOf(result);
    }

    private static String safeText(String value) {
        if (value == null) return "";
        return value.length() <= 128 ? value : value.substring(0, 128);
    }

    private static boolean validToken(String value) {
        return value != null && !value.isBlank() && value.length() <= 256
                && value.matches("[A-Za-z0-9_.:-]+");
    }

    private static boolean validText(String value) {
        return value != null && !value.isBlank() && value.length() <= MAX_TEXT;
    }

    public enum Phase {
        PREPARING("preparing"), OUTPUT_READY("output_ready"), ACKNOWLEDGED("acknowledged"),
        FAILED("failed");
        private final String key;
        Phase(String key) { this.key = key; }
        public String key() { return key; }
        public static Phase from(String key) {
            for (Phase phase : values()) if (phase.key.equals(key)) return phase;
            return null;
        }
    }

    /** A consumed ingredient always occupies one of the nine grid slots (1-9). */
    public record Placement(int slot, String itemKey, long amount, boolean consumed) {
        public Placement {
            if (slot < 1 || slot > 9 || !validText(itemKey) || amount < 1L || amount > MAX_AMOUNT) {
                throw new IllegalArgumentException("Invalid pot placement");
            }
        }
        private CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putInt("slot", slot);
            tag.putString("item", itemKey);
            tag.putLong("amount", amount);
            tag.putBoolean("consumed", consumed);
            return tag;
        }
        private static Placement read(CompoundTag tag) {
            try {
                return new Placement(tag.getInt("slot"), tag.getString("item"),
                        tag.getLong("amount"), tag.getBoolean("consumed"));
            } catch (RuntimeException invalid) {
                return null;
            }
        }
    }

    public record Product(String itemKey, long amount) {
        public Product {
            if (!validText(itemKey) || amount < 1L || amount > MAX_AMOUNT) {
                throw new IllegalArgumentException("Invalid pot product");
            }
        }
        private CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putString("item", itemKey);
            tag.putLong("amount", amount);
            return tag;
        }
        private static Product read(CompoundTag tag) {
            try { return new Product(tag.getString("item"), tag.getLong("amount")); }
            catch (RuntimeException invalid) { return null; }
        }
    }

    public record Entry(String operationId, String digest, String machineKey, Phase phase,
                        List<Placement> placements, List<Product> products,
                        boolean escrowConsumed, String reason) {
        private Entry with(Phase next, List<Placement> placements, List<Product> products,
                           boolean escrowConsumed, String reason) {
            return new Entry(operationId, digest, machineKey, next, placements, products,
                    escrowConsumed, reason);
        }
        private CompoundTag write() {
            CompoundTag tag = new CompoundTag();
            tag.putString("operation", operationId);
            tag.putString("digest", digest);
            tag.putString("machine", machineKey);
            tag.putString("phase", phase.key());
            tag.putBoolean("escrow_consumed", escrowConsumed);
            tag.putString("reason", reason);
            ListTag placed = new ListTag();
            for (Placement placement : placements) placed.add(placement.write());
            ListTag produced = new ListTag();
            for (Product product : products) produced.add(product.write());
            tag.put("placements", placed);
            tag.put("products", produced);
            return tag;
        }
        private static Entry read(CompoundTag tag) {
            try {
                Phase phase = Phase.from(tag.getString("phase"));
                String operationId = tag.getString("operation");
                String digest = tag.getString("digest");
                String machineKey = tag.getString("machine");
                if (phase == null || !validToken(operationId) || !validToken(digest)
                        || !validText(machineKey)) return null;
                ListTag placed = tag.getList("placements", 10);
                ListTag produced = tag.getList("products", 10);
                if (placed.size() > 16 || produced.size() > 64) return null;
                List<Placement> placements = new ArrayList<>();
                for (int i = 0; i < placed.size(); i++) {
                    Placement placement = Placement.read(placed.getCompound(i));
                    if (placement == null) return null;
                    placements.add(placement);
                }
                List<Product> products = new ArrayList<>();
                for (int i = 0; i < produced.size(); i++) {
                    Product product = Product.read(produced.getCompound(i));
                    if (product == null) return null;
                    products.add(product);
                }
                return new Entry(operationId, digest, machineKey, phase, List.copyOf(placements),
                        List.copyOf(products), tag.getBoolean("escrow_consumed"),
                        safeText(tag.getString("reason")));
            } catch (RuntimeException invalid) {
                return null;
            }
        }
    }
}
