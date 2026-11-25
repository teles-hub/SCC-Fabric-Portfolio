package de.scc.calculator.network.payload;

import de.scc.calculator.SuperCoolCalculator;
import de.scc.calculator.network.OrderBook;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/** Bounded client request to list, upsert or remove a terminal's persisted order list. */
public record OrderBookRequestPayload(BlockPos pos, String action, String id, String name,
                                      boolean topUp, List<Line> lines) implements CustomPacketPayload {
    public static final int MAX_LINES_PER_REQUEST = OrderBook.MAX_BATCH;
    public static final int MAX_KEY_LENGTH = 4_000;
    public static final Type<OrderBookRequestPayload> TYPE =
            new Type<>(SuperCoolCalculator.id("order_book_request"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OrderBookRequestPayload> STREAM_CODEC =
            StreamCodec.of(OrderBookRequestPayload::encode, OrderBookRequestPayload::decode);

    public OrderBookRequestPayload {
        if (pos == null) throw new IllegalArgumentException("Missing terminal position");
        if (!"sync".equals(action) && !"put".equals(action) && !"remove".equals(action)
                && !"plan".equals(action)) {
            throw new IllegalArgumentException("Unsupported order-book action");
        }
        id = id == null ? "" : id;
        name = name == null ? "" : name.strip();
        if (id.length() > 16 || name.length() > OrderBook.MAX_NAME) throw new IllegalArgumentException("Order-book label too long");
        lines = lines == null ? List.of() : List.copyOf(lines);
        if (lines.size() > MAX_LINES_PER_REQUEST) throw new IllegalArgumentException("Order batch exceeds limit");
        for (Line line : lines) {
            if (line == null || line.key().isBlank() || line.key().length() > MAX_KEY_LENGTH
                    || line.amount() < 1L || line.amount() > OrderBook.MAX_AMOUNT) {
                throw new IllegalArgumentException("Invalid order line");
            }
        }
        if ("put".equals(action) && lines.isEmpty()) throw new IllegalArgumentException("Empty order list");
        if (("remove".equals(action) || "plan".equals(action)) && id.isBlank()) {
            throw new IllegalArgumentException("Missing order-list id");
        }
        if ("plan".equals(action) && !lines.isEmpty()) {
            throw new IllegalArgumentException("Plan requests must reference a saved list only");
        }
    }

    public static OrderBookRequestPayload sync(BlockPos pos) {
        return new OrderBookRequestPayload(pos, "sync", "", "", false, List.of());
    }

    public static OrderBookRequestPayload plan(BlockPos pos, String id) {
        return new OrderBookRequestPayload(pos, "plan", id, "", false, List.of());
    }

    private static void encode(RegistryFriendlyByteBuf buffer, OrderBookRequestPayload payload) {
        buffer.writeBlockPos(payload.pos());
        buffer.writeUtf(payload.action(), 8);
        buffer.writeUtf(payload.id(), 16);
        buffer.writeUtf(payload.name(), OrderBook.MAX_NAME);
        buffer.writeBoolean(payload.topUp());
        buffer.writeVarInt(payload.lines().size());
        for (Line line : payload.lines()) {
            buffer.writeUtf(line.key(), MAX_KEY_LENGTH);
            buffer.writeVarLong(line.amount());
        }
    }

    private static OrderBookRequestPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        String action = buffer.readUtf(8);
        String id = buffer.readUtf(16);
        String name = buffer.readUtf(OrderBook.MAX_NAME);
        boolean topUp = buffer.readBoolean();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_LINES_PER_REQUEST) throw new IllegalArgumentException("Invalid order line count");
        java.util.ArrayList<Line> lines = new java.util.ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            String key = buffer.readUtf(MAX_KEY_LENGTH);
            long amount = buffer.readVarLong();
            if (key.isBlank() || amount < 1L || amount > OrderBook.MAX_AMOUNT) {
                throw new IllegalArgumentException("Invalid decoded order line");
            }
            lines.add(new Line(key, amount));
        }
        return new OrderBookRequestPayload(pos, action, id, name, topUp, lines);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }

    public record Line(String key, long amount) { }
}
