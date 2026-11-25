package de.scc.calculator.platform.storage;

/**
 * Equivalente direto ao IEnergyStorage do NeoForge (Forge Energy / FE).
 */
public interface SccEnergyStorage {

    int receiveEnergy(int maxReceive, boolean simulate);

    int extractEnergy(int maxExtract, boolean simulate);

    int getEnergyStored();

    int getMaxEnergyStored();

    boolean canExtract();

    boolean canReceive();
}
