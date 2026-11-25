package de.scc.calculator.calc;

import java.util.Set;

/** Incremental recipe-source contract reconstructed from SCC 1.6.5 bytecode. */
public interface RecipeSource {
    String name();

    void begin();

    int total();

    int done();

    boolean scanChunk(RecipeBook book, int budget);

    default boolean restrict(AnalysisDelta delta) {
        return false;
    }

    default Set<String> scannedTypes() {
        return null;
    }
}
