package de.scc.calculator.network;

import de.scc.calculator.network.payload.CraftingGridTransferPayload;
import de.scc.calculator.platform.PlatformNetwork;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Server-side registration and validation for crafting-terminal recipe transfers. */
public final class SccCraftingTerminalNetwork {
    private static final Map<UUID, Long> LAST_TRANSFER = new ConcurrentHashMap<>();

    private SccCraftingTerminalNetwork() { }

    public static synchronized void register() {
        PlatformNetwork.registerC2S(
                CraftingGridTransferPayload.TYPE, CraftingGridTransferPayload.STREAM_CODEC);
        PlatformNetwork.handleC2S(
                CraftingGridTransferPayload.TYPE, SccCraftingTerminalNetwork::handleTransfer);
    }

    private static void handleTransfer(CraftingGridTransferPayload request, ServerPlayer player) {
        if (!(player.containerMenu instanceof SccCraftingTerminalMenu menu)
                || menu.containerId != request.containerId()
                || !menu.pos().equals(request.pos())
                || !menu.stillValid(player)) {
            return;
        }

        long now = System.nanoTime();
        Long previous = LAST_TRANSFER.put(player.getUUID(), now);
        if (previous != null && now - previous < 150_000_000L) return;

        if (!menu.applyRecipeTransfer(request.recipeId(), request.patternKeys())) {
            LAST_TRANSFER.remove(player.getUUID(), now);
        }
    }

    public static void clearPlayer(UUID playerId) {
        if (playerId != null) LAST_TRANSFER.remove(playerId);
    }

    public static void clearAll() {
        LAST_TRANSFER.clear();
    }
}
