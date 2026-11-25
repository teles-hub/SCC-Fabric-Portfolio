package de.scc.calculator.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import de.scc.calculator.client.screen.StorageOverviewScreen;
import de.scc.calculator.network.payload.StorageOverviewPayload;
import de.scc.calculator.network.payload.StorageOverviewRequestPayload;
import de.scc.calculator.network.payload.NetworkDepositRequestPayload;
import de.scc.calculator.network.payload.NetworkActionResultPayload;
import de.scc.calculator.network.payload.NetworkWithdrawRequestPayload;
import de.scc.calculator.network.payload.NetworkFluidContainerRequestPayload;
import de.scc.calculator.network.payload.NetworkEnergyContainerRequestPayload;
import de.scc.calculator.network.payload.OrderBookRequestPayload;
import de.scc.calculator.network.payload.OrderBookSyncPayload;
import de.scc.calculator.network.payload.CraftJobRequestPayload;
import de.scc.calculator.network.payload.CraftJobSyncPayload;
import de.scc.calculator.network.payload.CraftingGridTransferPayload;
import de.scc.calculator.calc.StackKey;
import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.core.BlockPos;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Lado CLIENTE da rede. Substitui PacketDistributor.sendToServer e os handlers S2C de IPayloadContext. */
@Environment(EnvType.CLIENT)
public final class PlatformNetworkClient {

    private PlatformNetworkClient() {
    }

    private static boolean clientReceiversRegistered;
    private static Consumer<StorageOverviewPayload> secondaryOverviewReceiver;
    private static Consumer<NetworkActionResultPayload> secondaryActionReceiver;
    private static Consumer<OrderBookSyncPayload> secondaryOrderBookReceiver;
    private static Consumer<CraftJobSyncPayload> secondaryCraftJobReceiver;

    /** Allows container screens to receive the same server snapshot without coupling this transport to a screen class. */
    public static void registerSecondaryScreenReceivers(
            Consumer<StorageOverviewPayload> overviewReceiver,
            Consumer<NetworkActionResultPayload> actionReceiver) {
        secondaryOverviewReceiver = overviewReceiver;
        secondaryActionReceiver = actionReceiver;
    }

    public static void registerSecondaryOrderBookReceiver(Consumer<OrderBookSyncPayload> receiver) {
        secondaryOrderBookReceiver = receiver;
    }

    public static void registerSecondaryCraftJobReceiver(Consumer<CraftJobSyncPayload> receiver) {
        secondaryCraftJobReceiver = receiver;
    }

    public static synchronized void registerClientReceivers() {
        if (clientReceiversRegistered) return;
        handleS2C(StorageOverviewPayload.TYPE, (payload, context) -> {
            StorageOverviewScreen.accept(payload);
            if (secondaryOverviewReceiver != null) secondaryOverviewReceiver.accept(payload);
        });
        handleS2C(NetworkActionResultPayload.TYPE, (payload, context) -> {
            StorageOverviewScreen.acceptActionResult(payload);
            if (secondaryActionReceiver != null) secondaryActionReceiver.accept(payload);
        });
        handleS2C(OrderBookSyncPayload.TYPE, (payload, context) -> {
            if (secondaryOrderBookReceiver != null) secondaryOrderBookReceiver.accept(payload);
        });
        handleS2C(CraftJobSyncPayload.TYPE, (payload, context) -> {
            if (secondaryCraftJobReceiver != null) secondaryCraftJobReceiver.accept(payload);
        });
        clientReceiversRegistered = true;
    }

    public static void requestStorageOverview(BlockPos pos) { requestStorageOverview(pos, 0); }

    public static void requestStorageOverview(BlockPos pos, int pageIndex) {
        sendToServer(new StorageOverviewRequestPayload(pos, pageIndex));
    }

    public static void depositHeldItem(BlockPos pos, int pageIndex) {
        sendToServer(new NetworkDepositRequestPayload(pos, pageIndex));
    }

    public static void withdrawItem(BlockPos pos, String variantKey, int pageIndex) {
        sendToServer(new NetworkWithdrawRequestPayload(pos, variantKey, pageIndex));
    }

    public static void interactFluidContainer(BlockPos pos, int pageIndex) {
        sendToServer(new NetworkFluidContainerRequestPayload(pos, pageIndex));
    }

    public static void interactEnergyContainer(BlockPos pos, int pageIndex) {
        sendToServer(new NetworkEnergyContainerRequestPayload(pos, pageIndex));
    }

    public static void requestOrderBook(BlockPos pos) {
        sendToServer(OrderBookRequestPayload.sync(pos));
    }

    /** Saves the held stack as a new persisted terminal order list. */
    public static boolean saveHeldItemAsOrderList(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return false;
        ItemStack held = client.player.getMainHandItem();
        if (held == null || held.isEmpty()) return false;
        StackKey key = StackKey.of(held);
        if (key == null) return false;
        String name = held.getHoverName().getString();
        if (name.length() > 32) name = name.substring(0, 32);
        sendToServer(new OrderBookRequestPayload(pos, "put", "", name, false,
                java.util.List.of(new OrderBookRequestPayload.Line(key.encode(), held.getCount()))));
        return true;
    }

    public static void removeOrderList(BlockPos pos, String id) {
        sendToServer(new OrderBookRequestPayload(pos, "remove", id, "", false, java.util.List.of()));
    }

    /** Creates craft jobs for the bounded recipe targets in a saved order list. */
    public static void planOrderList(BlockPos pos, String id) {
        if (id != null && !id.isBlank()) sendToServer(OrderBookRequestPayload.plan(pos, id));
    }

    public static void transferCraftingRecipe(BlockPos pos, int containerId, String recipeId, java.util.List<String> patternKeys) {
        sendToServer(new CraftingGridTransferPayload(pos, containerId, recipeId, patternKeys));
    }

    public static void requestCraftJobs(BlockPos pos) {
        sendToServer(CraftJobRequestPayload.sync(pos));
    }

    /** Creates a server-side material plan for the held output variant and count. */
    public static boolean planHeldItem(BlockPos pos) {
        Minecraft client = Minecraft.getInstance();
        if (client.player == null) return false;
        ItemStack held = client.player.getMainHandItem();
        if (held == null || held.isEmpty()) return false;
        StackKey key = StackKey.of(held);
        if (key == null) return false;
        sendToServer(new CraftJobRequestPayload(pos, "plan", key.encode(), held.getCount(), 0L));
        return true;
    }

    public static void cancelCraftJob(BlockPos pos, long jobId) {
        sendToServer(new CraftJobRequestPayload(pos, "cancel", "", 0L, jobId));
    }

    /** O handler ja roda na thread do cliente (Fabric API 1.21). */
    public static <T extends CustomPacketPayload> void handleS2C(
            CustomPacketPayload.Type<T> type, BiConsumer<T, ClientPlayNetworking.Context> handler) {
        ClientPlayNetworking.registerGlobalReceiver(type, handler::accept);
    }

    public static void sendToServer(CustomPacketPayload payload) {
        if (ClientPlayNetworking.canSend(payload.type())) {
            ClientPlayNetworking.send(payload);
        }
    }
}
