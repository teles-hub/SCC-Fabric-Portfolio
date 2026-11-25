package de.scc.calculator.network.payload;

import de.scc.calculator.SuperCoolCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Client request for a bounded page of the connected SCC network inventory. */
public record StorageOverviewRequestPayload(BlockPos pos, int pageIndex) implements CustomPacketPayload {
    public static final int MAX_PAGE_INDEX = 255;
    public static final Type<StorageOverviewRequestPayload> TYPE =
            new Type<>(SuperCoolCalculator.id("storage_overview_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, StorageOverviewRequestPayload> STREAM_CODEC =
            StreamCodec.of(StorageOverviewRequestPayload::encode, StorageOverviewRequestPayload::decode);

    public StorageOverviewRequestPayload(BlockPos pos) { this(pos, 0); }

    public StorageOverviewRequestPayload {
        pageIndex = Math.max(0, Math.min(MAX_PAGE_INDEX, pageIndex));
    }

    private static void encode(RegistryFriendlyByteBuf buffer, StorageOverviewRequestPayload payload) {
        buffer.writeBlockPos(payload.pos());
        buffer.writeVarInt(payload.pageIndex());
    }

    private static StorageOverviewRequestPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        int page = buffer.readVarInt();
        if (page < 0 || page > MAX_PAGE_INDEX) throw new IllegalArgumentException("Invalid SCC storage page index");
        return new StorageOverviewRequestPayload(pos, page);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
