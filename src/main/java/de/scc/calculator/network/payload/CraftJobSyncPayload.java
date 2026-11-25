package de.scc.calculator.network.payload;

import de.scc.calculator.SuperCoolCalculator;
import de.scc.calculator.network.SccCraftJobQueue;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;
import java.util.UUID;

/** Bounded S2C summary of the latest craft jobs; recipe graphs stay server-side. */
public record CraftJobSyncPayload(BlockPos pos, boolean available, List<Summary> jobs)
        implements CustomPacketPayload {
    public static final Type<CraftJobSyncPayload> TYPE =
            new Type<>(SuperCoolCalculator.id("craft_job_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CraftJobSyncPayload> STREAM_CODEC =
            StreamCodec.of(CraftJobSyncPayload::encode, CraftJobSyncPayload::decode);

    public CraftJobSyncPayload {
        if (pos == null) throw new IllegalArgumentException("Missing terminal position");
        jobs = jobs == null ? List.of() : List.copyOf(jobs.stream().filter(java.util.Objects::nonNull)
                .limit(SccCraftJobQueue.MAX_SYNC_JOBS).toList());
    }

    public static CraftJobSyncPayload from(BlockPos pos, SccCraftJobQueue queue, UUID viewer) {
        if (queue == null) return new CraftJobSyncPayload(pos, false, List.of());
        List<Summary> result = queue.latest(SccCraftJobQueue.MAX_SYNC_JOBS).stream().map(job -> {
            String display = job.targetKey();
            return new Summary(job.id(), display, job.amount(), job.status(), job.stepCount(),
                    job.completedSteps(), job.crafts(), job.missing().size(),
                    viewer != null && viewer.toString().equals(job.owner()), job.reason());
        }).toList();
        return new CraftJobSyncPayload(pos, true, result);
    }

    private static void encode(RegistryFriendlyByteBuf buffer, CraftJobSyncPayload payload) {
        buffer.writeBlockPos(payload.pos());
        buffer.writeBoolean(payload.available());
        buffer.writeVarInt(payload.jobs().size());
        for (Summary summary : payload.jobs()) {
            buffer.writeVarLong(summary.id());
            buffer.writeUtf(summary.display(), 128);
            buffer.writeVarLong(summary.amount());
            buffer.writeUtf(summary.status(), 24);
            buffer.writeVarInt(summary.stepCount());
            buffer.writeVarInt(summary.completedSteps());
            buffer.writeVarLong(summary.crafts());
            buffer.writeVarInt(summary.missingCount());
            buffer.writeBoolean(summary.canCancel());
            buffer.writeUtf(summary.reason(), SccCraftJobQueue.MAX_REASON_LENGTH);
        }
    }

    private static CraftJobSyncPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        boolean available = buffer.readBoolean();
        int count = buffer.readVarInt();
        if (count < 0 || count > SccCraftJobQueue.MAX_SYNC_JOBS) {
            throw new IllegalArgumentException("Invalid craft-job count");
        }
        java.util.ArrayList<Summary> jobs = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long id = buffer.readVarLong();
            String display = buffer.readUtf(128);
            long amount = buffer.readVarLong();
            String status = buffer.readUtf(24);
            int steps = buffer.readVarInt();
            int completed = buffer.readVarInt();
            long crafts = buffer.readVarLong();
            int missing = buffer.readVarInt();
            boolean canCancel = buffer.readBoolean();
            String reason = buffer.readUtf(SccCraftJobQueue.MAX_REASON_LENGTH);
            if (id <= 0L || amount < 1L || amount > SccCraftJobQueue.MAX_AMOUNT
                    || SccCraftJobQueue.Status.from(status) == null
                    || steps < 0 || steps > SccCraftJobQueue.MAX_STEPS
                    || completed < 0 || completed > steps
                    || crafts < 0L || crafts > SccCraftJobQueue.MAX_CRAFTS
                    || missing < 0 || missing > SccCraftJobQueue.MAX_REQUIREMENTS) {
                throw new IllegalArgumentException("Invalid craft-job summary");
            }
            jobs.add(new Summary(id, display, amount, status, steps, completed, crafts, missing, canCancel, reason));
        }
        return new CraftJobSyncPayload(pos, available, jobs);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public record Summary(long id, String display, long amount, String status, int stepCount,
                          int completedSteps, long crafts, int missingCount, boolean canCancel, String reason) {
        public Summary {
            display = display == null ? "" : display;
            status = SccCraftJobQueue.Status.from(status) == null ? "failed" : status;
            reason = reason == null ? "" : reason;
            if (display.length() > 128) display = display.substring(0, 128);
            if (reason.length() > SccCraftJobQueue.MAX_REASON_LENGTH) reason = reason.substring(0, SccCraftJobQueue.MAX_REASON_LENGTH);
        }
    }
}
