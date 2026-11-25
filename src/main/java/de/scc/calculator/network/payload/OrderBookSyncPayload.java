package de.scc.calculator.network.payload;

import de.scc.calculator.SuperCoolCalculator;
import de.scc.calculator.network.OrderBook;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/** Small server-to-client order-list summary; item keys remain server-side until a list is selected. */
public record OrderBookSyncPayload(BlockPos pos, boolean available, List<Summary> lists) implements CustomPacketPayload {
    public static final Type<OrderBookSyncPayload> TYPE = new Type<>(SuperCoolCalculator.id("order_book_sync"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OrderBookSyncPayload> STREAM_CODEC =
            StreamCodec.of(OrderBookSyncPayload::encode, OrderBookSyncPayload::decode);

    public OrderBookSyncPayload {
        if (pos == null) throw new IllegalArgumentException("Missing terminal position");
        lists = lists == null ? List.of() : List.copyOf(lists.stream().filter(java.util.Objects::nonNull)
                .limit(OrderBook.MAX_LISTS).toList());
    }

    public static OrderBookSyncPayload from(BlockPos pos, OrderBook book) {
        if (book == null) return new OrderBookSyncPayload(pos, false, List.of());
        return new OrderBookSyncPayload(pos, true, book.lists().stream()
                .map(list -> new Summary(list.id(), list.name(), list.topUp(), list.lines().size())).toList());
    }

    private static void encode(RegistryFriendlyByteBuf buffer, OrderBookSyncPayload payload) {
        buffer.writeBlockPos(payload.pos());
        buffer.writeBoolean(payload.available());
        buffer.writeVarInt(payload.lists().size());
        for (Summary summary : payload.lists()) {
            buffer.writeUtf(summary.id(), 16);
            buffer.writeUtf(summary.name(), OrderBook.MAX_NAME);
            buffer.writeBoolean(summary.topUp());
            buffer.writeVarInt(summary.lineCount());
        }
    }

    private static OrderBookSyncPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        boolean available = buffer.readBoolean();
        int count = buffer.readVarInt();
        if (count < 0 || count > OrderBook.MAX_LISTS) throw new IllegalArgumentException("Invalid order list count");
        java.util.ArrayList<Summary> summaries = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String id = buffer.readUtf(16);
            String name = buffer.readUtf(OrderBook.MAX_NAME);
            boolean topUp = buffer.readBoolean();
            int lineCount = buffer.readVarInt();
            if (id.isBlank() || lineCount < 0 || lineCount > OrderBook.MAX_LINES) {
                throw new IllegalArgumentException("Invalid order-list summary");
            }
            summaries.add(new Summary(id, name, topUp, lineCount));
        }
        return new OrderBookSyncPayload(pos, available, summaries);
    }

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public record Summary(String id, String name, boolean topUp, int lineCount) {
        public Summary {
            id = id == null ? "" : id;
            name = name == null ? "" : name;
            lineCount = Math.max(0, Math.min(OrderBook.MAX_LINES, lineCount));
        }
    }
}
