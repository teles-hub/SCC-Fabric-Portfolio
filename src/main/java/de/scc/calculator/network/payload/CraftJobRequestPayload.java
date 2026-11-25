package de.scc.calculator.network.payload;

import de.scc.calculator.SuperCoolCalculator;
import de.scc.calculator.network.SccCraftJobQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Bounded C2S request to plan, synchronize or cancel a craft job at a terminal. */
public record CraftJobRequestPayload(BlockPos pos, String action, String targetKey,
                                     long amount, long jobId) implements CustomPacketPayload {
    public static final int MAX_KEY_LENGTH = SccCraftJobQueue.MAX_KEY_LENGTH;
    public static final Type<CraftJobRequestPayload> TYPE =
            new Type<>(SuperCoolCalculator.id("craft_job_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CraftJobRequestPayload> STREAM_CODEC =
            StreamCodec.of(CraftJobRequestPayload::encode, CraftJobRequestPayload::decode);

    public CraftJobRequestPayload {
        if (pos == null) throw new IllegalArgumentException("Missing terminal position");
        if (!"sync".equals(action) && !"plan".equals(action) && !"cancel".equals(action)) {
            throw new IllegalArgumentException("Unsupported craft-job action");
        }
        targetKey = targetKey == null ? "" : targetKey;
        if (targetKey.length() > MAX_KEY_LENGTH) throw new IllegalArgumentException("Target key too long");
        if ("plan".equals(action) && (targetKey.isBlank() || amount < 1L || amount > SccCraftJobQueue.MAX_AMOUNT)) {
            throw new IllegalArgumentException("Invalid craft-job plan request");
        }
        if ("cancel".equals(action) && jobId <= 0L) throw new IllegalArgumentException("Invalid craft-job id");
    }

    public static CraftJobRequestPayload sync(BlockPos pos) {
        return new CraftJobRequestPayload(pos, "sync", "", 0L, 0L);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, CraftJobRequestPayload payload) {
        buffer.writeBlockPos(payload.pos());
        buffer.writeUtf(payload.action(), 8);
        buffer.writeUtf(payload.targetKey(), MAX_KEY_LENGTH);
        buffer.writeVarLong(payload.amount());
        buffer.writeVarLong(payload.jobId());
    }

    private static CraftJobRequestPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        String action = buffer.readUtf(8);
        String key = buffer.readUtf(MAX_KEY_LENGTH);
        long amount = buffer.readVarLong();
        long jobId = buffer.readVarLong();
        if (amount < 0L || jobId < 0L) throw new IllegalArgumentException("Negative craft-job values");
        return new CraftJobRequestPayload(pos, action, key, amount, jobId);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
