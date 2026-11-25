package de.scc.calculator.network;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Platform-independent six-face part layout used by the Fabric cable-bus port.
 *
 * <p>The original SCC bus owns one network part per face and an independent
 * cable flag. This class models those invariants without depending on
 * NeoForge's multipart/model APIs.</p>
 */
public final class SccCableBusParts<T> {
    public enum Face {
        DOWN(0), UP(1), NORTH(2), SOUTH(3), WEST(4), EAST(5);

        private final int dataValue;
        Face(int dataValue) { this.dataValue = dataValue; }
        public int dataValue() { return dataValue; }
        public Face opposite() {
            return switch (this) {
                case DOWN -> UP;
                case UP -> DOWN;
                case NORTH -> SOUTH;
                case SOUTH -> NORTH;
                case WEST -> EAST;
                case EAST -> WEST;
            };
        }
    }

    private final EnumMap<Face, T> parts;
    private boolean cable;

    public SccCableBusParts() {
        this.parts = new EnumMap<>(Face.class);
    }

    private SccCableBusParts(EnumMap<Face, T> parts, boolean cable) {
        this.parts = new EnumMap<>(parts);
        this.cable = cable;
    }

    /** Adds a part only when the requested face is empty. */
    public synchronized boolean add(Face face, T part) {
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(part, "part");
        if (parts.containsKey(face)) return false;
        parts.put(face, part);
        return true;
    }

    /** Replaces the face's part and returns the previous part, if any. */
    public synchronized T replace(Face face, T part) {
        Objects.requireNonNull(face, "face");
        Objects.requireNonNull(part, "part");
        return parts.put(face, part);
    }

    public synchronized T remove(Face face) {
        Objects.requireNonNull(face, "face");
        return parts.remove(face);
    }

    public synchronized T part(Face face) {
        Objects.requireNonNull(face, "face");
        return parts.get(face);
    }

    public synchronized boolean hasPart(Face face) {
        return part(face) != null;
    }

    public synchronized int partCount() {
        return parts.size();
    }

    public synchronized List<T> parts() {
        return Collections.unmodifiableList(new ArrayList<>(parts.values()));
    }

    public synchronized Map<Face, T> snapshot() {
        return Collections.unmodifiableMap(new EnumMap<>(parts));
    }

    public synchronized boolean hasCable() {
        return cable;
    }

    public synchronized void setCable(boolean cable) {
        this.cable = cable;
    }

    /**
     * The original CableBusBlockEntity conducts only while its cable is present
     * (CableBusBlockEntity.conducts() delegates to hasCable()). Hosted parts
     * remain independently addressable; their own conductance does not turn
     * an otherwise cable-less bus into a network conduit.
     */
    public synchronized boolean conducts() {
        return cable;
    }

    /** A part may implement this contract when its own presence is not enough to conduct. */
    public interface ConductivePart {
        boolean conducts();
    }

    /**
     * Returns an independent slot-layout snapshot. Part references are intentionally
     * shallow-copied; callers must not treat mutable hosted parts as cloned objects.
     */
    public synchronized SccCableBusParts<T> copy() {
        return new SccCableBusParts<>(parts, cable);
    }
}
