package de.scc.calculator.network;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** Fabric geometry helper for the original six-way cable-mounted panel. */
public final class SccWallPanel {
    private static final VoxelShape CORE = Block.box(6, 6, 6, 10, 10, 10);
    private static final VoxelShape NORTH_ARM = Block.box(6, 6, 0, 10, 10, 6);
    private static final VoxelShape SOUTH_ARM = Block.box(6, 6, 10, 10, 10, 16);
    private static final VoxelShape WEST_ARM = Block.box(0, 6, 6, 6, 10, 10);
    private static final VoxelShape EAST_ARM = Block.box(10, 6, 6, 16, 10, 10);
    private static final VoxelShape UP_ARM = Block.box(6, 10, 6, 10, 16, 10);
    private static final VoxelShape DOWN_ARM = Block.box(6, 0, 6, 10, 6, 10);
    private static final VoxelShape NORTH = Block.box(2, 2, 0, 14, 14, 2);
    private static final VoxelShape SOUTH = Block.box(2, 2, 14, 14, 14, 16);
    private static final VoxelShape WEST = Block.box(0, 2, 2, 2, 14, 14);
    private static final VoxelShape EAST = Block.box(14, 2, 2, 16, 14, 14);
    private static final VoxelShape UP = Block.box(2, 14, 2, 14, 16, 14);
    private static final VoxelShape DOWN = Block.box(2, 0, 2, 14, 2, 14);
    private static final VoxelShape NECK_NORTH = Block.box(6, 6, 2, 10, 10, 6);
    private static final VoxelShape NECK_SOUTH = Block.box(6, 6, 10, 10, 10, 14);
    private static final VoxelShape NECK_WEST = Block.box(2, 6, 6, 6, 10, 10);
    private static final VoxelShape NECK_EAST = Block.box(10, 6, 6, 14, 10, 10);
    private static final VoxelShape NECK_UP = Block.box(6, 10, 6, 10, 14, 10);
    private static final VoxelShape NECK_DOWN = Block.box(6, 2, 6, 10, 6, 10);

    private SccWallPanel() { }

    public static VoxelShape shape(BlockState state) {
        if (SccFacadeShell.covered(state)) return Shapes.block();
        Direction facing = state.getValue(BlockStateProperties.FACING);
        VoxelShape shape = Shapes.or(CORE, plate(facing));
        if (state.getValue(SccPanelProps.NECK)) shape = Shapes.or(shape, neck(facing));
        for (Direction direction : Direction.values()) {
            if (state.hasProperty(SccCableBlock.propertyFor(direction))
                    && state.getValue(SccCableBlock.propertyFor(direction))) {
                shape = Shapes.or(shape, arm(direction));
            }
        }
        return shape.optimize();
    }

    private static VoxelShape arm(Direction direction) {
        return switch (direction) {
            case NORTH -> NORTH_ARM; case SOUTH -> SOUTH_ARM; case WEST -> WEST_ARM;
            case EAST -> EAST_ARM; case UP -> UP_ARM; case DOWN -> DOWN_ARM;
        };
    }

    private static VoxelShape plate(Direction direction) {
        return switch (direction) {
            case SOUTH -> SOUTH; case WEST -> WEST; case EAST -> EAST;
            case UP -> UP; case DOWN -> DOWN; default -> NORTH;
        };
    }

    private static VoxelShape neck(Direction direction) {
        return switch (direction) {
            case SOUTH -> NECK_SOUTH; case WEST -> NECK_WEST; case EAST -> NECK_EAST;
            case UP -> NECK_UP; case DOWN -> NECK_DOWN; default -> NECK_NORTH;
        };
    }
}
