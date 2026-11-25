package de.scc.calculator.network.payload;

import de.scc.calculator.SuperCoolCalculator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

import java.util.List;

/** Bounded client request to transfer a JEI crafting recipe into an SCC terminal. */
public record CraftingGridTransferPayload(BlockPos pos, int containerId, String recipeId,
                                          List<String> patternKeys) implements CustomPacketPayload {
    public static final int MAX_RECIPE_ID_LENGTH = 256;
    public static final int MAX_STACK_KEY_LENGTH = 4_000;
    public static final int GRID_SIZE = 9;

    public static final Type<CraftingGridTransferPayload> TYPE =
            new Type<>(SuperCoolCalculator.id("crafting_grid_transfer"));
    public static final StreamCodec<RegistryFriendlyByteBuf, CraftingGridTransferPayload> STREAM_CODEC =
            StreamCodec.of(CraftingGridTransferPayload::encode, CraftingGridTransferPayload::decode);

    public CraftingGridTransferPayload {
        if (pos == null) throw new IllegalArgumentException("Missing crafting terminal position");
        if (containerId < 0) throw new IllegalArgumentException("Invalid menu id");
        if (recipeId == null || recipeId.isBlank() || recipeId.length() > MAX_RECIPE_ID_LENGTH) {
            throw new IllegalArgumentException("Invalid recipe id");
        }
        if (patternKeys == null || patternKeys.size() != GRID_SIZE) {
            throw new IllegalArgumentException("Crafting transfer must contain exactly nine grid entries");
        }
        patternKeys = List.copyOf(patternKeys);
        for (String key : patternKeys) {
            if (key == null || key.length() > MAX_STACK_KEY_LENGTH) {
                throw new IllegalArgumentException("Invalid crafting grid entry");
            }
        }
    }

    private static void encode(RegistryFriendlyByteBuf buffer, CraftingGridTransferPayload payload) {
        buffer.writeBlockPos(payload.pos());
        buffer.writeVarInt(payload.containerId());
        buffer.writeUtf(payload.recipeId(), MAX_RECIPE_ID_LENGTH);
        buffer.writeVarInt(payload.patternKeys().size());
        for (String key : payload.patternKeys()) {
            buffer.writeUtf(key, MAX_STACK_KEY_LENGTH);
        }
    }

    private static CraftingGridTransferPayload decode(RegistryFriendlyByteBuf buffer) {
        BlockPos pos = buffer.readBlockPos();
        int containerId = buffer.readVarInt();
        String recipeId = buffer.readUtf(MAX_RECIPE_ID_LENGTH);
        int count = buffer.readVarInt();
        if (count != GRID_SIZE) throw new IllegalArgumentException("Invalid crafting grid entry count");
        java.util.ArrayList<String> keys = new java.util.ArrayList<>(GRID_SIZE);
        for (int slot = 0; slot < GRID_SIZE; slot++) keys.add(buffer.readUtf(MAX_STACK_KEY_LENGTH));
        return new CraftingGridTransferPayload(pos, containerId, recipeId, keys);
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
