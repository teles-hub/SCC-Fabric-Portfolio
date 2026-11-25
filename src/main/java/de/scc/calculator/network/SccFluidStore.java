package de.scc.calculator.network;

import com.mojang.serialization.DataResult;
import de.scc.calculator.platform.storage.SccFluidHandler;
import de.scc.calculator.platform.storage.SccFluidStack;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Multi-tank fluid store reconstructed from SCC 1.6.5 bytecode.
 *
 * <p>The public API remains in mB to preserve SCC's machine logic. Fabric's
 * Transfer API adapter converts to droplets at the boundary (81 droplets/mB).
 * Variant persistence uses FluidVariant.CODEC so custom fluid data components
 * are retained; the root NBT key remains "tanks" as in the original store.</p>
 */
public final class SccFluidStore implements SccFluidHandler {
    private int tanks;
    private int perTank;
    private final Runnable changed;
    private LongSupplier perKindLimit = () -> 0L;
    private final List<SccFluidStack> content = new ArrayList<>();

    public SccFluidStore(int tanks, int perTank, Runnable changed) {
        this.tanks = tanks;
        this.perTank = perTank;
        this.changed = changed == null ? () -> { } : changed;
    }

    public boolean unlimited() {
        return tanks < 0 || perTank < 0;
    }

    public void setLimits(int tanks, int perTank) {
        this.tanks = tanks;
        this.perTank = perTank;
    }

    public void limitPerKind(LongSupplier supplier) {
        perKindLimit = supplier == null ? () -> 0L : supplier;
    }

    public long totalAmount() {
        long total = 0L;
        for (SccFluidStack stack : content) {
            total += stack.getAmount(); // each stack is int-sized; total is long as in SCC
        }
        return total;
    }

    public int usedTanks() {
        return content.size();
    }

    public List<SccFluidStack> entries() {
        return content.stream().map(SccFluidStack::copy).toList();
    }

    public long capacityMb() {
        if (unlimited()) return Long.MAX_VALUE;
        return (long) Math.max(0, tanks) * (long) Math.max(0, perTank);
    }

    public long roomMb() {
        long capacity = capacityMb();
        return capacity == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(0L, capacity - totalAmount());
    }

    public State snapshotState() {
        return new State(entries());
    }

    public void restoreState(State state) {
        content.clear();
        if (state != null) {
            for (SccFluidStack stack : state.entries()) {
                if (stack != null && !stack.isEmpty()) content.add(stack.copy());
            }
        }
        changed.run();
    }

    public record State(List<SccFluidStack> entries) {
        public State {
            entries = entries == null ? List.of() : entries.stream()
                    .filter(stack -> stack != null && !stack.isEmpty())
                    .map(SccFluidStack::copy).toList();
        }
    }

    private int capacity() {
        return perTank < 0 ? Integer.MAX_VALUE : perTank;
    }

    public int perTankCapacity() {
        return capacity();
    }

    public long amountOf(SccFluidStack resource) {
        if (resource == null || resource.isEmpty()) return 0L;
        long total = 0L;
        for (SccFluidStack stack : content) {
            if (stack.isFluidEqual(resource)) total += stack.getAmount();
        }
        return total;
    }

    private int maxTanks() {
        return tanks < 0 ? Integer.MAX_VALUE : tanks;
    }

    @Override
    public int getTanks() {
        int used = content.size();
        return Math.max(used, Math.min(maxTanks(), used + (used < maxTanks() ? 1 : 0)));
    }

    @Override
    public SccFluidStack getFluidInTank(int tank) {
        return tank >= 0 && tank < content.size() ? content.get(tank) : SccFluidStack.EMPTY;
    }

    @Override
    public int getTankCapacity(int tank) {
        return capacity();
    }

    @Override
    public boolean isFluidValid(int tank, SccFluidStack stack) {
        return true;
    }

    @Override
    public int fill(SccFluidStack resource, boolean simulate) {
        if (resource == null || resource.isEmpty()) return 0;
        int remaining = resource.getAmount();
        long kindLimit = perKindLimit.getAsLong();
        if (kindLimit > 0L) {
            remaining = (int) Math.min(remaining, Math.max(0L, kindLimit - amountOf(resource)));
        }
        int accepted = 0;

        for (SccFluidStack existing : content) {
            if (remaining <= 0) break;
            if (!existing.isFluidEqual(resource)) continue;
            int free = capacity() - existing.getAmount();
            if (free <= 0) continue;
            int moved = Math.min(free, remaining);
            if (!simulate) existing.setAmount(existing.getAmount() + moved);
            accepted += moved;
            remaining -= moved;
        }

        int freeTanks = maxTanks() - content.size();
        while (remaining > 0 && freeTanks > 0) {
            int moved = Math.min(capacity(), remaining);
            if (moved <= 0) break;
            if (!simulate) content.add(resource.copyWithAmount(moved));
            accepted += moved;
            remaining -= moved;
            freeTanks--;
        }

        if (!simulate && accepted > 0) changed.run();
        return accepted;
    }

    @Override
    public SccFluidStack drain(SccFluidStack resource, boolean simulate) {
        if (resource == null || resource.isEmpty()) return SccFluidStack.EMPTY;
        return drainSame(resource, resource.getAmount(), simulate);
    }

    @Override
    public SccFluidStack drain(int maxDrainMb, boolean simulate) {
        if (maxDrainMb <= 0 || content.isEmpty()) return SccFluidStack.EMPTY;
        return drainSame(content.get(0), maxDrainMb, simulate);
    }

    private SccFluidStack drainSame(SccFluidStack resource, int amount, boolean simulate) {
        if (amount <= 0) return SccFluidStack.EMPTY;
        int remaining = amount;
        int extracted = 0;

        // The original SCC store drains matching entries from the end of the list.
        for (int i = content.size() - 1; i >= 0 && remaining > 0; i--) {
            SccFluidStack existing = content.get(i);
            if (!existing.isFluidEqual(resource)) continue;
            int moved = Math.min(remaining, existing.getAmount());
            if (moved <= 0) continue;
            extracted += moved;
            remaining -= moved;
            if (!simulate) {
                if (moved >= existing.getAmount()) content.remove(i);
                else existing.setAmount(existing.getAmount() - moved);
                changed.run(); // matches original take(): one notification per affected tank
            }
        }

        return extracted <= 0 ? SccFluidStack.EMPTY : resource.copyWithAmount(extracted);
    }

    public CompoundTag save(HolderLookup.Provider registries) {
        CompoundTag root = new CompoundTag();
        ListTag savedTanks = new ListTag();
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        for (SccFluidStack stack : content) {
            if (stack.isEmpty()) continue;
            DataResult<Tag> encoded = FluidVariant.CODEC.encodeStart(ops, stack.toVariant());
            Tag variantTag = encoded.result().orElse(null);
            if (variantTag == null) continue;
            CompoundTag entry = new CompoundTag();
            entry.put("variant", variantTag);
            entry.putInt("amount", stack.getAmount());
            savedTanks.add(entry);
        }
        root.put("tanks", savedTanks);
        return root;
    }

    public void load(CompoundTag root, HolderLookup.Provider registries) {
        content.clear();
        if (root == null) return;
        ListTag savedTanks = root.getList("tanks", Tag.TAG_COMPOUND);
        var ops = registries.createSerializationContext(NbtOps.INSTANCE);
        for (int i = 0; i < savedTanks.size(); i++) {
            CompoundTag entry = savedTanks.getCompound(i);
            Tag encodedVariant = entry.get("variant");
            if (encodedVariant == null) continue;
            FluidVariant variant = FluidVariant.CODEC.parse(ops, encodedVariant).result().orElse(FluidVariant.blank());
            int amount = entry.getInt("amount");
            if (variant.isBlank() || amount <= 0) continue;
            content.add(SccFluidStack.fromVariant(variant, (long) amount * SccFluidStack.DROPLETS_PER_MB));
        }
    }
}
