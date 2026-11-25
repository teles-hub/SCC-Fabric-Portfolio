package de.scc.calculator.platform.storage;

/**
 * Equivalente direto ao IFluidHandler do NeoForge operando em milibuckets (mB).
 */
public interface SccFluidHandler {

    int getTanks();

    SccFluidStack getFluidInTank(int tank);

    int getTankCapacity(int tank);

    boolean isFluidValid(int tank, SccFluidStack stack);

    /**
     * @return Quantidade de fluido (em mB) efetivamente inserida.
     */
    int fill(SccFluidStack resource, boolean simulate);

    /**
     * @return Fluido e quantidade (em mB) efetivamente extraídos.
     */
    SccFluidStack drain(SccFluidStack resource, boolean simulate);

    /**
     * @return Fluido e quantidade (em mB) efetivamente extraídos de qualquer tipo disponível.
     */
    SccFluidStack drain(int maxDrainMb, boolean simulate);
}
