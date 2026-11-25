package de.scc.calculator.network;

import java.util.Map;

/** Flow-aware contract for SCC chemical stores exposed to a network. */
public interface SccChemicalAccess {
    void collect(Map<String, Long> target);
    long count(String chemicalId);
    long insert(String chemicalId, long amount, boolean simulate);
    long drain(String chemicalId, long amount, boolean simulate);
}
