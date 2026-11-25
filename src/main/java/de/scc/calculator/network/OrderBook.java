package de.scc.calculator.network;

import de.scc.calculator.calc.StackKey;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Persistent, bounded named order lists for an SCC terminal.
 *
 * <p>The limits and NBT field names follow the SCC 1.6.5 OrderBook contract.
 * Input is normalized at the boundary: invalid StackKeys are discarded,
 * duplicates are merged, amounts are clamped, and callers only receive
 * immutable snapshots.</p>
 */
public final class OrderBook {
    public static final int MAX_LISTS = 24;
    public static final int MAX_LINES = 256;
    public static final int MAX_BATCH = 44;
    public static final int MAX_NAME = 32;
    public static final long MAX_AMOUNT = 1_000_000L;
    private static final int MAX_KEY = 4_000;

    private final List<OrderList> lists = new ArrayList<>();
    private int nextId = 1;

    public synchronized List<OrderList> lists() {
        return List.copyOf(lists);
    }

    public synchronized OrderList byId(String id) {
        if (id == null) return null;
        for (OrderList list : lists) {
            if (list.id().equals(id)) return list;
        }
        return null;
    }

    /**
     * Inserts or replaces an order list from the original SCC-compatible NBT
     * schema. Returns the persisted id, or null if the list capacity is full.
     */
    public synchronized String put(CompoundTag input) {
        if (input == null) return null;
        OrderList parsed = OrderList.read(input);
        if (parsed.lines().isEmpty()) return null;
        int existing = indexOf(parsed.id());
        if (existing < 0 && lists.size() >= MAX_LISTS) return null;

        String id = existing >= 0 ? parsed.id() : nextListId();
        String name = parsed.name();
        if (name.isEmpty()) {
            name = existing >= 0 ? lists.get(existing).name() : "Lista " + (lists.size() + 1);
        }
        OrderList stored = new OrderList(id, name, parsed.topUp(), parsed.lines());
        if (existing >= 0) lists.set(existing, stored);
        else lists.add(stored);
        return id;
    }

    public synchronized String put(String id, String name, boolean topUp, List<Line> lines) {
        CompoundTag input = new CompoundTag();
        if (id != null) input.putString("id", id);
        input.putString("name", name == null ? "" : name);
        input.putBoolean("topup", topUp);
        ListTag encoded = new ListTag();
        if (lines != null) {
            for (Line line : lines.stream().filter(Objects::nonNull).limit(MAX_LINES).toList()) {
                CompoundTag entry = new CompoundTag();
                entry.putString("k", line.key());
                entry.putLong("n", line.amount());
                encoded.add(entry);
            }
        }
        input.put("lines", encoded);
        return put(input);
    }

    public synchronized boolean remove(String id) {
        if (id == null || id.isBlank()) return false;
        return lists.removeIf(list -> list.id().equals(id));
    }

    public synchronized ListTag write() {
        ListTag result = new ListTag();
        for (OrderList list : lists) result.add(list.write());
        return result;
    }

    public synchronized void save(CompoundTag target) {
        if (target == null || lists.isEmpty()) return;
        target.put("orderlists", write());
        target.putInt("orderlistnext", nextId);
    }

    public synchronized void load(CompoundTag source) {
        if (source == null || !source.contains("orderlists")) return;
        lists.clear();
        ListTag encoded = source.getList("orderlists", 10);
        for (int i = 0; i < encoded.size() && lists.size() < MAX_LISTS; i++) {
            OrderList list = OrderList.read(encoded.getCompound(i));
            if (list.id().isBlank()) continue;
            if (indexOf(list.id()) >= 0) continue;
            lists.add(list);
        }

        int maxId = lists.size() + 1;
        for (OrderList list : lists) {
            String id = list.id();
            if (id.length() > 1 && id.charAt(0) == 'l') {
                try { maxId = Math.max(maxId, Integer.parseInt(id.substring(1)) + 1); }
                catch (NumberFormatException ignored) { /* preserve unknown legacy IDs */ }
            }
        }
        nextId = Math.max(maxId, source.getInt("orderlistnext"));
        if (nextId < 1) nextId = maxId;
    }

    private int indexOf(String id) {
        if (id == null || id.isBlank()) return -1;
        for (int i = 0; i < lists.size(); i++) {
            if (lists.get(i).id().equals(id)) return i;
        }
        return -1;
    }

    private String nextListId() {
        String id;
        do { id = "l" + nextId++; } while (indexOf(id) >= 0);
        return id;
    }

    public record Line(String key, long amount) {
        public Line {
            key = key == null ? "" : key;
            amount = Math.max(1L, Math.min(MAX_AMOUNT, amount));
        }
    }

    public record OrderList(String id, String name, boolean topUp, List<Line> lines) {
        public OrderList {
            id = id == null ? "" : id;
            name = cleanName(name);
            lines = lines == null ? List.of() : List.copyOf(lines.stream()
                    .filter(Objects::nonNull).limit(MAX_LINES).toList());
        }

        public CompoundTag write() {
            CompoundTag result = new CompoundTag();
            result.putString("id", id);
            result.putString("name", name);
            result.putBoolean("topup", topUp);
            ListTag encoded = new ListTag();
            for (Line line : lines) {
                CompoundTag entry = new CompoundTag();
                entry.putString("k", line.key());
                entry.putLong("n", line.amount());
                encoded.add(entry);
            }
            result.put("lines", encoded);
            return result;
        }

        public static OrderList read(CompoundTag input) {
            if (input == null) return new OrderList("", "", false, List.of());
            String id = input.getString("id");
            String name = cleanName(input.getString("name"));
            List<Line> lines = new ArrayList<>();
            Map<String, Long> merged = new LinkedHashMap<>();
            ListTag entries = input.getList("lines", 10);
            for (int i = 0; i < entries.size() && merged.size() < MAX_LINES; i++) {
                CompoundTag encoded = entries.getCompound(i);
                String key = encoded.getString("k");
                if (key.isBlank() || key.length() > MAX_KEY || StackKey.decode(key) == null) continue;
                long amount = Math.max(1L, Math.min(MAX_AMOUNT, encoded.getLong("n")));
                merged.merge(key, amount, (a, b) -> Math.min(MAX_AMOUNT, a + b));
            }
            merged.forEach((key, amount) -> lines.add(new Line(key, amount)));
            return new OrderList(id, name, input.getBoolean("topup"), lines);
        }

        private static String cleanName(String value) {
            if (value == null) return "";
            String normalized = value.strip().replaceAll("[\\p{Cntrl}]", "");
            return normalized.length() <= MAX_NAME ? normalized : normalized.substring(0, MAX_NAME);
        }
    }
}
