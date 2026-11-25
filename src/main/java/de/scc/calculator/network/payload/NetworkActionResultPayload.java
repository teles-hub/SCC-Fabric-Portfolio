package de.scc.calculator.network.payload;

import de.scc.calculator.SuperCoolCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** Bounded server result for a terminal action; never trusts client-provided ItemStacks. */
public record NetworkActionResultPayload(BlockPos pos, String status, long amount) implements CustomPacketPayload {
    public static final Type<NetworkActionResultPayload> TYPE =
            new Type<>(SuperCoolCalculator.id("network_action_result"));
    public static final StreamCodec<RegistryFriendlyByteBuf, NetworkActionResultPayload> STREAM_CODEC =
            StreamCodec.of(NetworkActionResultPayload::encode, NetworkActionResultPayload::decode);

    public NetworkActionResultPayload {
        status = allowed(status) ? status : "unavailable";
        amount = Math.max(0L, amount);
    }

    private static boolean allowed(String status) {
        return "inserted".equals(status) || "partial".equals(status) || "empty_hand".equals(status)
                || "full".equals(status) || "unavailable".equals(status) || "truncated".equals(status)
                || "too_far".equals(status) || "extracted".equals(status)
                || "inventory_full".equals(status) || "not_found".equals(status)
                || "fluid_moved".equals(status) || "fluid_unavailable".equals(status)
                || "energy_moved".equals(status) || "energy_unavailable".equals(status)
                || "order_listed".equals(status) || "order_saved".equals(status)
                || "order_removed".equals(status) || "order_invalid".equals(status)
                || "order_not_found".equals(status)
                || "job_created".equals(status) || "job_cancelled".equals(status)
                || "job_invalid".equals(status) || "job_unavailable".equals(status)
                || "job_network_unavailable".equals(status) || "job_providers_busy".equals(status)
                || "job_recipe_limit".equals(status) || "job_no_recipes".equals(status)
                || "job_calculation_failed".equals(status) || "job_calculation_limited".equals(status)
                || "job_no_plan".equals(status) || "job_queue_full".equals(status)
                || "job_not_found".equals(status) || "job_not_owner".equals(status)
                || "job_ready".equals(status);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, NetworkActionResultPayload payload) {
        buffer.writeBlockPos(payload.pos());
        buffer.writeUtf(payload.status(), 32);
        buffer.writeVarLong(payload.amount());
    }

    private static NetworkActionResultPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        String status = buffer.readUtf(32);
        long amount = buffer.readVarLong();
        if (amount < 0L) throw new IllegalArgumentException("Negative SCC network action amount");
        return new NetworkActionResultPayload(pos, status, amount);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
