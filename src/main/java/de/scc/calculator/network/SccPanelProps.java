package de.scc.calculator.network;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;

/** Original SCC wall-panel orientation properties, independent of NeoForge. */
public final class SccPanelProps {
    public static final BooleanProperty NECK = BooleanProperty.create("neck");
    public static final BooleanProperty FLIPPED = BooleanProperty.create("flipped");

    private SccPanelProps() { }

    public static void add(StateDefinition.Builder<Block, BlockState> builder, boolean screen) {
        builder.add(NECK);
        if (screen) builder.add(FLIPPED);
    }

    public static BlockState defaults(BlockState state) {
        if (state.hasProperty(NECK)) state = state.setValue(NECK, false);
        if (state.hasProperty(FLIPPED)) state = state.setValue(FLIPPED, false);
        return state;
    }

    public static boolean flipped(BlockState state) {
        return state.hasProperty(FLIPPED) && state.getValue(FLIPPED);
    }

    public static BlockState inHost(BlockState state, boolean hasCable) {
        if (state.hasProperty(NECK)) state = state.setValue(NECK, hasCable && !flipped(state));
        return state;
    }

    public static Direction drawnFacing(Direction facing) { return facing.getOpposite(); }
}
