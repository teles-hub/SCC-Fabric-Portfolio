package de.scc.calculator.network;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Registry-independent long-capacity chemical tank store reconstructed from
 * SCC 1.6.5. The optional Mekanism-facing wrapper can delegate to this class.
 */
public final class SccChemicalStore implements SccChemicalAccess {
    private int tanks;
    private long perTank;
    private final Runnable changed;
    private final List<String> ids = new ArrayList<>();
    private final List<Long> amounts = new ArrayList<>();

    public SccChemicalStore(int tanks, long perTank, Runnable changed) {
        this.tanks = tanks;
        this.perTank = perTank;
        this.changed = changed == null ? () -> { } : changed;
    }

    public boolean unlimited() {
        return tanks < 0 || perTank < 0L;
    }

    public void setLimits(int tanks, long perTank) {
        this.tanks = tanks;
        this.perTank = perTank;
    }

    public long totalAmount() {
        long total = 0L;
        for (long amount : amounts) total = saturatedAdd(total, amount);
        return total;
    }

    public int usedTanks() { return ids.size(); }
    public long perTankCapacity() { return capacity(); }
    private long capacity() { return perTank < 0L ? Long.MAX_VALUE : perTank; }
    private int maxTanks() { return tanks < 0 ? Integer.MAX_VALUE : tanks; }

    /** Number of visible tanks, including one virtual empty tank when capacity allows. */
    public int tanks() {
        int used = ids.size();
        return Math.max(used, Math.min(maxTanks(), used + 1));
    }

    public String chemicalAt(int tank) {
        return tank >= 0 && tank < ids.size() ? ids.get(tank) : null;
    }

    public long amountAt(int tank) {
        return tank >= 0 && tank < amounts.size() ? amounts.get(tank) : 0L;
    }

    public long capacityAt(int tank) { return capacity(); }

    public long insertInto(int tank, String chemical, long amount, boolean simulate) {
        if (chemical == null || chemical.isEmpty() || amount <= 0L) return 0L;
        if (tank < 0 || tank >= tanks()) return 0L;

        if (tank < ids.size()) {
            if (!ids.get(tank).equals(chemical)) return 0L;
            long free = Math.max(0L, capacity() - amounts.get(tank));
            long accepted = Math.min(free, amount);
            if (accepted <= 0L) return 0L;
            if (!simulate) {
                amounts.set(tank, saturatedAdd(amounts.get(tank), accepted));
                changed.run();
            }
            return accepted;
        }

        if (indexOf(chemical) >= 0 || ids.size() >= maxTanks()) return 0L;
        long accepted = Math.min(capacity(), amount);
        if (accepted <= 0L) return 0L;
        if (!simulate) {
            ids.add(chemical);
            amounts.add(accepted);
            changed.run();
        }
        return accepted;
    }

    public long insert(String chemical, long amount, boolean simulate) {
        if (chemical == null || chemical.isEmpty() || amount <= 0L) return 0L;
        int index = indexOf(chemical);
        return insertInto(index < 0 ? ids.size() : index, chemical, amount, simulate);
    }

    public long extract(int tank, long amount, boolean simulate) {
        if (tank < 0 || tank >= ids.size() || amount <= 0L) return 0L;
        long current = amounts.get(tank);
        long extracted = Math.min(current, amount);
        if (extracted <= 0L) return 0L;
        if (!simulate) {
            if (extracted >= current) {
                ids.remove(tank);
                amounts.remove(tank);
            } else {
                amounts.set(tank, current - extracted);
            }
            changed.run();
        }
        return extracted;
    }

    public void set(int tank, String chemical, long amount) {
        if (tank < 0 || tank >= tanks()) return;
        if (chemical == null || chemical.isEmpty() || amount <= 0L) {
            if (tank < ids.size()) {
                ids.remove(tank);
                amounts.remove(tank);
                changed.run();
            }
            return;
        }

        long safeAmount = Math.min(capacity(), amount);
        if (safeAmount <= 0L) return;
        if (tank < ids.size()) {
            ids.set(tank, chemical);
            amounts.set(tank, safeAmount);
        } else {
            if (ids.size() >= maxTanks()) return;
            ids.add(chemical);
            amounts.add(safeAmount);
        }
        changed.run();
    }

    private int indexOf(String chemical) {
        for (int i = 0; i < ids.size(); i++) if (ids.get(i).equals(chemical)) return i;
        return -1;
    }

    public void collect(Map<String, Long> target) {
        if (target == null) return;
        for (int i = 0; i < ids.size(); i++) {
            target.merge(ids.get(i), amounts.get(i), SccChemicalStore::saturatedAdd);
        }
    }

    public long count(String chemical) {
        int index = chemical == null ? -1 : indexOf(chemical);
        return index < 0 ? 0L : amounts.get(index);
    }

    public long drain(String chemical, long amount, boolean simulate) {
        int index = chemical == null ? -1 : indexOf(chemical);
        return index < 0 ? 0L : extract(index, amount, simulate);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag savedTanks = new ListTag();
        for (int i = 0; i < ids.size(); i++) {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", ids.get(i));
            entry.putLong("n", amounts.get(i));
            savedTanks.add(entry);
        }
        tag.put("tanks", savedTanks);
        return tag;
    }

    public void load(CompoundTag tag) {
        ids.clear();
        amounts.clear();
        if (tag == null) return;
        ListTag savedTanks = tag.getList("tanks", Tag.TAG_COMPOUND);
        for (int i = 0; i < savedTanks.size(); i++) {
            CompoundTag entry = savedTanks.getCompound(i);
            String id = entry.getString("id");
            long amount = entry.getLong("n");
            if (id.isEmpty() || amount <= 0L) continue;
            int existing = indexOf(id);
            if (existing >= 0) amounts.set(existing, saturatedAdd(amounts.get(existing), amount));
            else {
                ids.add(id);
                amounts.add(amount);
            }
        }
    }

    private static long saturatedAdd(long left, long right) {
        if (right > 0L && left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        if (right < 0L && left < Long.MIN_VALUE - right) return Long.MIN_VALUE;
        return left + right;
    }
}
