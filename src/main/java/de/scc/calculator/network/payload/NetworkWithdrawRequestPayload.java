package de.scc.calculator.network.payload;

import de.scc.calculator.SuperCoolCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Requests one server-authoritative item extraction using a component-aware StackKey. */
public record NetworkWithdrawRequestPayload(BlockPos pos, String variantKey, int pageIndex) implements CustomPacketPayload {
    private static final int MAX_VARIANT_KEY_LENGTH = 4096;
    public static final Type<NetworkWithdrawRequestPayload> TYPE =
            new Type<>(SuperCoolCalculator.id("network_withdraw_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NetworkWithdrawRequestPayload> STREAM_CODEC =
            StreamCodec.of(NetworkWithdrawRequestPayload::encode, NetworkWithdrawRequestPayload::decode);

    public NetworkWithdrawRequestPayload(BlockPos pos, String variantKey) { this(pos, variantKey, 0); }

    public NetworkWithdrawRequestPayload {
        variantKey = variantKey == null || variantKey.length() > MAX_VARIANT_KEY_LENGTH ? "" : variantKey;
        pageIndex = Math.max(0, Math.min(255, pageIndex));
    }

    private static void encode(RegistryFriendlyByteBuf buffer, NetworkWithdrawRequestPayload payload) {
        buffer.writeBlockPos(payload.pos());
        buffer.writeUtf(payload.variantKey(), MAX_VARIANT_KEY_LENGTH);
        buffer.writeVarInt(payload.pageIndex());
    }

    private static NetworkWithdrawRequestPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        String variantKey = buffer.readUtf(MAX_VARIANT_KEY_LENGTH);
        int page = buffer.readVarInt();
        if (page < 0 || page > 255) throw new IllegalArgumentException("Invalid network page index");
        return new NetworkWithdrawRequestPayload(pos, variantKey, page);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
