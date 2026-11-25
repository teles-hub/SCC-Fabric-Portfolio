package de.scc.calculator.network;

import net.minecraft.core.Direction;

/**
 * Contract for a network BlockEntity that can be hosted on one face of an SCC
 * Cable Bus. Hosted parts remain real BlockEntity instances, but their state
 * and lifecycle are owned by the bus while attached.
 */
public interface SccHostedNetworkPart extends NetworkNode {
    SccCableBusBlockEntity sccHost();
    Direction sccHostSide();
    void sccAttachTo(SccCableBusBlockEntity host, Direction side);
}
