package de.scc.calculator.network.payload;

import de.scc.calculator.SuperCoolCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Request to exchange energy between a held Energy API item and the connected SCC network. */
public record NetworkEnergyContainerRequestPayload(BlockPos pos, int pageIndex) implements CustomPacketPayload {
    public static final Type<NetworkEnergyContainerRequestPayload> TYPE =
            new Type<>(SuperCoolCalculator.id("network_energy_container_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NetworkEnergyContainerRequestPayload> STREAM_CODEC =
            StreamCodec.of(NetworkEnergyContainerRequestPayload::encode, NetworkEnergyContainerRequestPayload::decode);

    public NetworkEnergyContainerRequestPayload(BlockPos pos) { this(pos, 0); }

    public NetworkEnergyContainerRequestPayload {
        pageIndex = Math.max(0, Math.min(255, pageIndex));
    }

    private static void encode(RegistryFriendlyByteBuf buffer, NetworkEnergyContainerRequestPayload payload) {
        buffer.writeBlockPos(payload.pos());
        buffer.writeVarInt(payload.pageIndex());
    }

    private static NetworkEnergyContainerRequestPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        int page = buffer.readVarInt();
        if (page < 0 || page > 255) throw new IllegalArgumentException("Invalid network page index");
        return new NetworkEnergyContainerRequestPayload(pos, page);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
