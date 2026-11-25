package de.scc.calculator.network;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;

/** Deterministic, bounded six-direction graph traversal for the SCC network. */
public final class SccNetworkTopology {
    public static final int DEFAULT_MAX_NODES = 4096;
    public static final int ALL_PORTS = 0x3F;

    private SccNetworkTopology() { }

    public enum Kind { CABLE, STORAGE, TERMINAL, MACHINE, DEVICE }

    public record Cell(int x, int y, int z) {
        public Cell offset(int dx, int dy, int dz) { return new Cell(x + dx, y + dy, z + dz); }
    }

    /**
     * connectionMask bits follow Minecraft Direction.get3DDataValue():
     * DOWN=0, UP=1, NORTH=2, SOUTH=3, WEST=4, EAST=5.
     */
    public record Node(Cell position, Kind kind, String machineAlias, int connectionMask) {
        public Node(Cell position, Kind kind) { this(position, kind, "", ALL_PORTS); }
        public Node(Cell position, Kind kind, String machineAlias) {
            this(position, kind, machineAlias, ALL_PORTS);
        }
        public Node {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(kind, "kind");
            machineAlias = machineAlias == null ? "" : machineAlias.strip();
            connectionMask &= ALL_PORTS;
            if (kind != Kind.MACHINE) machineAlias = "";
        }
        public boolean connects(int directionBit) { return (connectionMask & (1 << directionBit)) != 0; }
    }

    public record Result(List<Node> nodes, boolean truncated) {
        public Result { nodes = nodes == null ? List.of() : List.copyOf(nodes); }
        public int nodeCount() { return nodes.size(); }
        public int cableCount() { return count(Kind.CABLE); }
        public int storageCount() { return count(Kind.STORAGE); }
        public int terminalCount() { return count(Kind.TERMINAL); }
        public int machineCount() { return count(Kind.MACHINE); }
        public int deviceCount() { return count(Kind.DEVICE); }
        private int count(Kind kind) {
            int count = 0;
            for (Node node : nodes) if (node.kind() == kind) count++;
            return count;
        }
        public List<Cell> storagePositions() {
            List<Cell> result = new ArrayList<>();
            for (Node node : nodes) if (node.kind() == Kind.STORAGE) result.add(node.position());
            return Collections.unmodifiableList(result);
        }
    }

    /**
     * Scans mutually connected ports only. A connection exists only when both
     * adjacent endpoints expose reciprocal faces; unloaded cells are rejected
     * by the caller's lookup and are never force-loaded here.
     */
    public static Result scan(Cell start, Function<Cell, Node> lookup, int maxNodes) {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(lookup, "lookup");
        int limit = Math.max(1, Math.min(DEFAULT_MAX_NODES, maxNodes));
        Node first = lookup.apply(start);
        if (first == null) return new Result(List.of(), false);
        if (!start.equals(first.position())) {
            throw new IllegalArgumentException("Node lookup returned a different position for the requested cell");
        }

        ArrayDeque<Node> pending = new ArrayDeque<>();
        Set<Cell> visited = new HashSet<>();
        List<Node> result = new ArrayList<>();
        pending.add(first);
        visited.add(start);
        result.add(first);
        boolean truncated = false;

        // Each tuple: dx, dy, dz, outgoing face bit, reciprocal incoming face bit.
        final int[][] directions = {
                {0, -1, 0, 0, 1}, {0, 1, 0, 1, 0},
                {0, 0, -1, 2, 3}, {0, 0, 1, 3, 2},
                {-1, 0, 0, 4, 5}, {1, 0, 0, 5, 4}
        };

        while (!pending.isEmpty() && !truncated) {
            Node current = pending.removeFirst();
            for (int[] direction : directions) {
                if (!current.connects(direction[3])) continue;
                Cell next = current.position().offset(direction[0], direction[1], direction[2]);
                if (visited.contains(next)) continue;
                Node candidate = lookup.apply(next);
                if (candidate == null || !candidate.connects(direction[4])) continue;
                if (!next.equals(candidate.position())) {
                    throw new IllegalArgumentException("Node lookup returned a different position for " + next);
                }
                if (result.size() >= limit) {
                    truncated = true;
                    break;
                }
                visited.add(next);
                result.add(candidate);
                pending.addLast(candidate);
            }
        }
        return new Result(result, truncated);
    }
}
