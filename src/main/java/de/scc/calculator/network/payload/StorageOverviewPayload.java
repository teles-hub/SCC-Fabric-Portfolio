package de.scc.calculator.network.payload;

import de.scc.calculator.SuperCoolCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/** Server-authoritative, read-only storage status sent to the requesting client. */
public record StorageOverviewPayload(
        BlockPos pos,
        boolean available,
        String family,
        String tier,
        long used,
        long capacity,
        boolean unlimited,
        boolean empty,
        List<ResourceSummary> resources,
        int nodeCount,
        int cableCount,
        int storageCount,
        int terminalCount,
        boolean truncated,
        int pageIndex,
        int pageCount
) implements CustomPacketPayload {
    private static final int MAX_SUMMARY_ENTRIES = 8;
    public static final Type<StorageOverviewPayload> TYPE =
            new Type<>(SuperCoolCalculator.id("storage_overview"));

    public static final StreamCodec<RegistryFriendlyByteBuf, StorageOverviewPayload> STREAM_CODEC =
            StreamCodec.of(StorageOverviewPayload::encode, StorageOverviewPayload::decode);

    public StorageOverviewPayload {
        family = safeLabel(family, "unknown");
        tier = safeLabel(tier, "unknown");
        used = Math.max(0L, used);
        capacity = Math.max(0L, capacity);
        resources = resources == null ? List.of() : resources.stream().filter(java.util.Objects::nonNull).limit(MAX_SUMMARY_ENTRIES).toList();
        nodeCount = Math.max(0, Math.min(4096, nodeCount));
        cableCount = Math.max(0, Math.min(nodeCount, cableCount));
        storageCount = Math.max(0, Math.min(nodeCount - cableCount, storageCount));
        terminalCount = Math.max(0, Math.min(nodeCount - cableCount - storageCount, terminalCount));
        pageCount = Math.max(1, Math.min(256, pageCount));
        pageIndex = Math.max(0, Math.min(pageCount - 1, pageIndex));
    }

    /** Compatibility constructor for topology payloads without pagination fields. */
    public StorageOverviewPayload(BlockPos pos, boolean available, String family, String tier, long used,
                                  long capacity, boolean unlimited, boolean empty, List<ResourceSummary> resources,
                                  int nodeCount, int cableCount, int storageCount, int terminalCount,
                                  boolean truncated) {
        this(pos, available, family, tier, used, capacity, unlimited, empty, resources,
                nodeCount, cableCount, storageCount, terminalCount, truncated, 0, 1);
    }

    /** Compatibility constructor for earlier topology payloads without terminal counters. */
    public StorageOverviewPayload(BlockPos pos, boolean available, String family, String tier, long used,
                                  long capacity, boolean unlimited, boolean empty, List<ResourceSummary> resources,
                                  int nodeCount, int cableCount, int storageCount, boolean truncated) {
        this(pos, available, family, tier, used, capacity, unlimited, empty, resources,
                nodeCount, cableCount, storageCount, 0, truncated, 0, 1);
    }

    /** Compatibility constructor for callers that still describe one local store. */
    public StorageOverviewPayload(BlockPos pos, boolean available, String family, String tier, long used,
                                  long capacity, boolean unlimited, boolean empty, List<ResourceSummary> resources) {
        this(pos, available, family, tier, used, capacity, unlimited, empty, resources, 0, 0, 0, 0, false, 0, 1);
    }

    private static String safeLabel(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.toLowerCase(java.util.Locale.ROOT);
        return normalized.matches("[a-z0-9_]{1,32}") ? normalized : fallback;
    }

    public static StorageOverviewPayload unavailable(BlockPos pos) {
        return new StorageOverviewPayload(pos, false, "unavailable", "unknown", 0L, 0L, false, true, List.of());
    }

    private static void encode(RegistryFriendlyByteBuf buffer, StorageOverviewPayload payload) {
        buffer.writeBlockPos(payload.pos());
        buffer.writeBoolean(payload.available());
        buffer.writeUtf(payload.family(), 32);
        buffer.writeUtf(payload.tier(), 32);
        buffer.writeVarLong(payload.used());
        buffer.writeVarLong(payload.capacity());
        buffer.writeBoolean(payload.unlimited());
        buffer.writeBoolean(payload.empty());
        buffer.writeVarInt(payload.nodeCount());
        buffer.writeVarInt(payload.cableCount());
        buffer.writeVarInt(payload.storageCount());
        buffer.writeVarInt(payload.terminalCount());
        buffer.writeBoolean(payload.truncated());
        buffer.writeVarInt(payload.pageIndex());
        buffer.writeVarInt(payload.pageCount());
        buffer.writeVarInt(payload.resources().size());
        for (ResourceSummary resource : payload.resources()) {
            buffer.writeUtf(resource.id(), 128);
            buffer.writeVarLong(resource.amount());
            buffer.writeUtf(resource.unit(), 16);
            buffer.writeUtf(resource.variantKey(), 4096);
        }
    }

    private static StorageOverviewPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        boolean available = buffer.readBoolean();
        String family = buffer.readUtf(32);
        String tier = buffer.readUtf(32);
        long used = buffer.readVarLong();
        long capacity = buffer.readVarLong();
        boolean unlimited = buffer.readBoolean();
        boolean empty = buffer.readBoolean();
        int nodeCount = buffer.readVarInt();
        int cableCount = buffer.readVarInt();
        int storageCount = buffer.readVarInt();
        int terminalCount = buffer.readVarInt();
        boolean truncated = buffer.readBoolean();
        int pageIndex = buffer.readVarInt();
        int pageCount = buffer.readVarInt();
        if (pageIndex < 0 || pageIndex > 255 || pageCount < 1 || pageCount > 256 || pageIndex >= pageCount) {
            throw new IllegalArgumentException("Invalid SCC storage page metadata");
        }
        if (nodeCount < 0 || nodeCount > 4096 || cableCount < 0 || cableCount > nodeCount
                || storageCount < 0 || storageCount > nodeCount - cableCount
                || terminalCount < 0 || terminalCount > nodeCount - cableCount - storageCount) {
            throw new IllegalArgumentException("Invalid SCC network topology counters");
        }
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_SUMMARY_ENTRIES) {
            throw new IllegalArgumentException("Invalid SCC storage summary size: " + count);
        }
        java.util.ArrayList<ResourceSummary> resources = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            resources.add(new ResourceSummary(buffer.readUtf(128), buffer.readVarLong(), buffer.readUtf(16), buffer.readUtf(4096)));
        }
        return new StorageOverviewPayload(pos, available, family, tier, used, capacity, unlimited, empty,
                resources, nodeCount, cableCount, storageCount, terminalCount, truncated, pageIndex, pageCount);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    public record ResourceSummary(String id, long amount, String unit, String variantKey) {
        public ResourceSummary(String id, long amount, String unit) { this(id, amount, unit, ""); }
        public ResourceSummary {
            if (id == null || !id.matches("[a-z0-9_.-]{1,63}:[a-z0-9_./-]{1,64}")) {
                id = "minecraft:unknown";
            }
            amount = Math.max(0L, amount);
            if (variantKey == null || variantKey.length() > 4096) variantKey = "";
            if (unit == null || !unit.matches("items|mB|FE|units")) unit = "units";
        }
    }
}
