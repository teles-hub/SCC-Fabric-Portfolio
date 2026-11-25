package de.scc.calculator.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import de.scc.calculator.platform.SccMachineAdapters;
import net.minecraft.resources.ResourceLocation;

/** Minecraft world adapter for the bounded, dimension-local SCC topology scan. */
public final class SccNetworkScanner {
    public static final int MAX_NODES = SccNetworkTopology.DEFAULT_MAX_NODES;
    private SccNetworkScanner() { }

    public static SccNetworkView scan(Level level, BlockPos origin) {
        if (level == null || origin == null || !level.hasChunkAt(origin)) return SccNetworkView.empty(level, origin);
        SccNetworkTopology.Cell start = cell(origin);
        boolean[] unresolvedBoundary = {false};
        SccNetworkTopology.Result topology = SccNetworkTopology.scan(start, cell -> {
            BlockPos pos = new BlockPos(cell.x(), cell.y(), cell.z());
            if (!level.hasChunkAt(pos)) { unresolvedBoundary[0] = true; return null; }
            BlockEntity blockEntity = level.getBlockEntity(pos);
            if (blockEntity instanceof SccCableBusBlockEntity bus) {
                return bus.hasCable() ? node(cell, SccNetworkTopology.Kind.CABLE, bus, "") : null;
            }
            if (blockEntity instanceof SccCableBlockEntity) {
                return node(cell, SccNetworkTopology.Kind.CABLE, blockEntity, "");
            }
            if (blockEntity instanceof SccRedstoneEmitterBlockEntity) {
                return node(cell, SccNetworkTopology.Kind.DEVICE, blockEntity, "");
            }
            if (blockEntity instanceof SccStorageConnectorBlockEntity) {
                return node(cell, SccNetworkTopology.Kind.STORAGE, blockEntity, "");
            }
            if (blockEntity instanceof SccStorageBlockEntity) {
                return node(cell, SccNetworkTopology.Kind.STORAGE, blockEntity, "");
            }
            if (blockEntity instanceof SccTerminalBlockEntity
                    || blockEntity instanceof SccCraftingTerminalBlockEntity
                    || blockEntity instanceof SccWirelessTransmitterBlockEntity
                    || blockEntity instanceof SccWirelessReceiverBlockEntity) {
                return node(cell, SccNetworkTopology.Kind.TERMINAL, blockEntity, "");
            }
            if (blockEntity != null) {
                ResourceLocation alias = SccMachineAdapters.machineAlias(level, pos);
                if (alias != null) return new SccNetworkTopology.Node(cell,
                        SccNetworkTopology.Kind.MACHINE, alias.toString(), SccNetworkTopology.ALL_PORTS);
            }
            return null;
        }, MAX_NODES);
        if (unresolvedBoundary[0] && !topology.truncated()) {
            topology = new SccNetworkTopology.Result(topology.nodes(), true);
        }
        return SccNetworkView.from(level, origin, topology);
    }

    private static SccNetworkTopology.Node node(SccNetworkTopology.Cell cell,
            SccNetworkTopology.Kind kind, BlockEntity entity, String alias) {
        int mask = entity instanceof NetworkNode networkNode
                ? networkNode.connectionMask() : SccNetworkTopology.ALL_PORTS;
        return new SccNetworkTopology.Node(cell, kind, alias, mask);
    }

    public static boolean isNode(Level level, BlockPos pos) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return false;
        BlockEntity entity = level.getBlockEntity(pos);
        return (entity instanceof NetworkNode node && node.connectionMask() != 0)
                || (entity != null && SccMachineAdapters.machineAlias(level, pos) != null);
    }

    public static boolean adjacentNode(Level level, BlockPos pos, Direction direction) {
        if (level == null || pos == null || direction == null) return false;
        BlockPos adjacent = pos.relative(direction);
        if (!level.hasChunkAt(pos) || !level.hasChunkAt(adjacent) || !isNode(level, adjacent)) return false;
        BlockEntity from = level.getBlockEntity(pos);
        BlockEntity to = level.getBlockEntity(adjacent);
        boolean fromPort = !(from instanceof NetworkNode node) || node.canConnect(direction);
        boolean toPort = !(to instanceof NetworkNode node) || node.canConnect(direction.getOpposite());
        return fromPort && toPort;
    }

    private static SccNetworkTopology.Cell cell(BlockPos pos) {
        return new SccNetworkTopology.Cell(pos.getX(), pos.getY(), pos.getZ());
    }
}
