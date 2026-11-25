package de.scc.calculator.network;

/** Shared long-capacity contract for SCC's non-item/non-fluid resource stores. */
public interface LongStoreHandler {
    long stored();
    long capacity();
    long receive(long amount, boolean simulate);
    long extract(long amount, boolean simulate);
}
