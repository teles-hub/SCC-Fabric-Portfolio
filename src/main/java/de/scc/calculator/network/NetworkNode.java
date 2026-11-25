package de.scc.calculator.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** Marker and port contract for a block entity participating in the SCC network. */
public interface NetworkNode {
    BlockPos getBlockPos();

    /**
     * Whether this endpoint exposes a network port on the supplied face.
     * The default preserves the legacy six-way behavior until a device
     * explicitly defines directional ports.
     */
    default boolean canConnect(Direction face) {
        return face != null;
    }

    /** Bit mask using Direction.get3DDataValue() for the six Minecraft faces. */
    default int connectionMask() {
        int mask = 0;
        for (Direction direction : Direction.values()) {
            if (canConnect(direction)) mask |= 1 << direction.get3DDataValue();
        }
        return mask;
    }

    default SccTier tier() {
        return SccTier.BASIC;
    }
}
