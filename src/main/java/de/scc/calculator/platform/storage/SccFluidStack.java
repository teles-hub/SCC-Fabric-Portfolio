package de.scc.calculator.platform.storage;

import net.fabricmc.fabric.api.transfer.v1.fluid.FluidConstants;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidVariant;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;

/**
 * Representação leve de fluido em milibuckets (mB) compatível com FluidStack do NeoForge
 * e conversível diretamente para FluidVariant + droplets do Fabric.
 */
public class SccFluidStack {

    public static final SccFluidStack EMPTY = new SccFluidStack(Fluids.EMPTY, 0, DataComponentPatch.EMPTY);
    public static final long DROPLETS_PER_MB = FluidConstants.BUCKET / 1000L; // 81 droplets = 1 mB

    private final Fluid fluid;
    private int amountMb;
    private final DataComponentPatch components;

    public SccFluidStack(Fluid fluid, int amountMb) {
        this(fluid, amountMb, DataComponentPatch.EMPTY);
    }

    public SccFluidStack(Fluid fluid, int amountMb, DataComponentPatch components) {
        this.fluid = fluid == null ? Fluids.EMPTY : fluid;
        this.amountMb = Math.max(0, amountMb);
        this.components = components == null ? DataComponentPatch.EMPTY : components;
    }

    public static SccFluidStack fromVariant(FluidVariant variant, long droplets) {
        if (variant.isBlank() || droplets <= 0) {
            return EMPTY;
        }
        int mb = (int) (droplets / DROPLETS_PER_MB);
        return mb <= 0 ? EMPTY : new SccFluidStack(variant.getFluid(), mb, variant.getComponents());
    }

    public FluidVariant toVariant() {
        if (isEmpty()) {
            return FluidVariant.blank();
        }
        return FluidVariant.of(fluid, components);
    }

    public long toDroplets() {
        return (long) amountMb * DROPLETS_PER_MB;
    }

    public Fluid getFluid() {
        return fluid;
    }

    public int getAmount() {
        return amountMb;
    }

    public void setAmount(int amountMb) {
        this.amountMb = Math.max(0, amountMb);
    }

    public DataComponentPatch getComponents() {
        return components;
    }

    public boolean isEmpty() {
        return fluid == Fluids.EMPTY || amountMb <= 0;
    }

    public SccFluidStack copy() {
        return isEmpty() ? EMPTY : new SccFluidStack(fluid, amountMb, components);
    }

    public SccFluidStack copyWithAmount(int newAmountMb) {
        return (fluid == Fluids.EMPTY || newAmountMb <= 0) ? EMPTY : new SccFluidStack(fluid, newAmountMb, components);
    }

    public boolean isFluidEqual(SccFluidStack other) {
        if (this.isEmpty() && other.isEmpty()) return true;
        if (this.isEmpty() || other.isEmpty()) return false;
        return this.fluid == other.fluid && this.components.equals(other.components);
    }
}
