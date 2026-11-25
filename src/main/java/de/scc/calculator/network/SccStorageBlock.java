package de.scc.calculator.network;

/**
 * Contract implemented by SCC storage blocks. The eventual Fabric block base
 * can extend Minecraft's Block and implement this interface without coupling
 * capacity policy to a concrete block implementation.
 */
public interface SccStorageBlock {
    StorageTier tier();

    /** Identifies which of the six persistent stores this block owns. */
    Kind kind();

    enum Kind {
        ITEM("item_storage"),
        FLUID("fluid_storage"),
        ENERGY("energy_storage"),
        CHEMICAL("chemical_storage"),
        SOURCE("source_storage"),
        SOUL("soul_storage");

        private final String family;

        Kind(String family) {
            this.family = family;
        }

        public String family() {
            return family;
        }
    }
}
