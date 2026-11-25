package de.scc.calculator.network.payload;

import de.scc.calculator.SuperCoolCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Request to interact with the main-hand fluid container and the connected SCC fluid network. */
public record NetworkFluidContainerRequestPayload(BlockPos pos, int pageIndex) implements CustomPacketPayload {
    public static final Type<NetworkFluidContainerRequestPayload> TYPE =
            new Type<>(SuperCoolCalculator.id("network_fluid_container_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NetworkFluidContainerRequestPayload> STREAM_CODEC =
            StreamCodec.of(NetworkFluidContainerRequestPayload::encode, NetworkFluidContainerRequestPayload::decode);

    public NetworkFluidContainerRequestPayload(BlockPos pos) { this(pos, 0); }

    public NetworkFluidContainerRequestPayload {
        pageIndex = Math.max(0, Math.min(255, pageIndex));
    }

    private static void encode(RegistryFriendlyByteBuf buffer, NetworkFluidContainerRequestPayload payload) {
        buffer.writeBlockPos(payload.pos());
        buffer.writeVarInt(payload.pageIndex());
    }

    private static NetworkFluidContainerRequestPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        int page = buffer.readVarInt();
        if (page < 0 || page > 255) throw new IllegalArgumentException("Invalid network page index");
        return new NetworkFluidContainerRequestPayload(pos, page);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
