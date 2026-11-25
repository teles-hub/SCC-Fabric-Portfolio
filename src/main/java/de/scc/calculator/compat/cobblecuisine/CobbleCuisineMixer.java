package de.scc.calculator.compat.cobblecuisine;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * Optional CobbleCuisine Mixer discovery without a hard dependency on the mod.
 *
 * <p>The upstream Mixer has a continuously running tick processor and no
 * durable SCC operation token/output acknowledgement contract. This bridge
 * intentionally exposes identity only: it must not start the machine or claim
 * that it can execute an SCC craft job.</p>
 */
public final class CobbleCuisineMixer {
    private static final String MIXER_BLOCK_ENTITY =
            "com.fyre.cobblecuisine.block.entity.MixerBlockEntity";
    private static final ResourceLocation MACHINE_ALIAS =
            ResourceLocation.parse("scc:cobblecuisine_mixer");

    private CobbleCuisineMixer() { }

    public static ResourceLocation machineAlias(Level level, BlockPos pos) {
        if (level == null || pos == null || !level.hasChunkAt(pos)) return null;
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity == null) return null;

        // Exact class-name comparison avoids loading CobbleCuisine classes when
        // the optional mod is absent and remains safe across classloader boundaries.
        for (Class<?> type = entity.getClass(); type != null; type = type.getSuperclass()) {
            if (MIXER_BLOCK_ENTITY.equals(type.getName())) return MACHINE_ALIAS;
        }
        return null;
    }
}
