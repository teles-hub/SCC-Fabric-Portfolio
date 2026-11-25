package de.scc.calculator.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Position, dimension and session kind for a terminal menu on the client. */
public record TerminalMenuOpenData(BlockPos pos, ResourceKey<Level> dimension, boolean wireless, boolean wirelessCrafting) {
    public TerminalMenuOpenData(BlockPos pos, ResourceKey<Level> dimension, boolean wireless) {
        this(pos, dimension, wireless, false);
    }
    public static final StreamCodec<RegistryFriendlyByteBuf, TerminalMenuOpenData> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, value) -> {
                        buffer.writeBlockPos(value.pos());
                        buffer.writeResourceLocation(value.dimension().location());
                        buffer.writeBoolean(value.wireless());
                        buffer.writeBoolean(value.wirelessCrafting());
                    },
                    buffer -> new TerminalMenuOpenData(
                            buffer.readBlockPos(),
                            ResourceKey.create(Registries.DIMENSION, buffer.readResourceLocation()),
                            buffer.readBoolean(),
                            buffer.readBoolean()));

    public TerminalMenuOpenData {
        if (pos == null || dimension == null) {
            throw new IllegalArgumentException("Terminal menu opening data requires position and dimension");
        }
        pos = pos.immutable();
    }
}
