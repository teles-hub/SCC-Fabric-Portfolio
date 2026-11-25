package de.scc.calculator.network.payload;

import de.scc.calculator.SuperCoolCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Request to insert the server-validated main-hand stack into the connected item network. */
public record NetworkDepositRequestPayload(BlockPos pos, int pageIndex) implements CustomPacketPayload {
    public static final Type<NetworkDepositRequestPayload> TYPE =
            new Type<>(SuperCoolCalculator.id("network_deposit_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NetworkDepositRequestPayload> STREAM_CODEC =
            StreamCodec.of(NetworkDepositRequestPayload::encode, NetworkDepositRequestPayload::decode);

    public NetworkDepositRequestPayload(BlockPos pos) { this(pos, 0); }
    public NetworkDepositRequestPayload { pageIndex = Math.max(0, Math.min(255, pageIndex)); }

    private static void encode(RegistryFriendlyByteBuf buffer, NetworkDepositRequestPayload payload) {
        buffer.writeBlockPos(payload.pos());
        buffer.writeVarInt(payload.pageIndex());
    }

    private static NetworkDepositRequestPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        int page = buffer.readVarInt();
        if (page < 0 || page > 255) throw new IllegalArgumentException("Invalid network page index");
        return new NetworkDepositRequestPayload(pos, page);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
