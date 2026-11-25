package de.scc.calculator.platform;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.PlayerLookup;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import de.scc.calculator.network.SccNetworkCache;
import de.scc.calculator.network.SccNetworkView;
import de.scc.calculator.network.SccTerminalBlockEntity;
import de.scc.calculator.network.OrderBook;
import de.scc.calculator.calc.StackKey;
import net.minecraft.core.registries.BuiltInRegistries;
import de.scc.calculator.network.payload.StorageOverviewPayload;
import de.scc.calculator.network.payload.NetworkDepositRequestPayload;
import de.scc.calculator.network.payload.NetworkActionResultPayload;
import de.scc.calculator.network.payload.NetworkWithdrawRequestPayload;
import de.scc.calculator.network.payload.NetworkFluidContainerRequestPayload;
import de.scc.calculator.network.payload.NetworkEnergyContainerRequestPayload;
import net.fabricmc.fabric.api.transfer.v1.item.ItemVariant;
import net.fabricmc.fabric.api.transfer.v1.transaction.Transaction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.fabricmc.fabric.api.transfer.v1.fluid.FluidStorageUtil;
import net.fabricmc.fabric.api.transfer.v1.context.ContainerItemContext;
import team.reborn.energy.api.EnergyStorage;
import team.reborn.energy.api.EnergyStorageUtil;
import de.scc.calculator.network.payload.StorageOverviewRequestPayload;
import de.scc.calculator.network.payload.OrderBookRequestPayload;
import de.scc.calculator.network.payload.OrderBookSyncPayload;
import de.scc.calculator.network.payload.CraftJobRequestPayload;
import de.scc.calculator.network.payload.CraftJobSyncPayload;
import de.scc.calculator.network.SccCraftJobQueue;
import de.scc.calculator.network.SccServerRecipePlanner;

import java.util.function.BiConsumer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Lado COMUM da rede (servidor + registro de tipos). Substitui RegisterPayloadHandlersEvent e PacketDistributor.
 * CORRECAO: o lado cliente (ClientPlayNetworking) foi movido para client/PlatformNetworkClient. Importar classes
 * de cliente numa classe comum pode derrubar o servidor dedicado.
 * Os ~89 payloads devem ser registrados por script (registerC2S/registerS2C) em registerPayloads().
 */
public final class PlatformNetwork {

    private PlatformNetwork() {
    }

    private static boolean payloadsRegistered;
    private static boolean serverReceiversRegistered;
    private static final ConcurrentMap<UUID, Long> LAST_STORAGE_OVERVIEW_REQUEST = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Long> LAST_NETWORK_DEPOSIT_REQUEST = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Long> LAST_NETWORK_WITHDRAW_REQUEST = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Long> LAST_NETWORK_FLUID_REQUEST = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Long> LAST_NETWORK_ENERGY_REQUEST = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Long> LAST_ORDER_BOOK_REQUEST = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Long> LAST_ORDER_PLAN_REQUEST = new ConcurrentHashMap<>();
    private static final ConcurrentMap<UUID, Long> LAST_CRAFT_JOB_REQUEST = new ConcurrentHashMap<>();

    public static synchronized void registerPayloads() {
        if (payloadsRegistered) return;
        registerC2S(StorageOverviewRequestPayload.TYPE, StorageOverviewRequestPayload.STREAM_CODEC);
        registerS2C(StorageOverviewPayload.TYPE, StorageOverviewPayload.STREAM_CODEC);
        registerC2S(NetworkDepositRequestPayload.TYPE, NetworkDepositRequestPayload.STREAM_CODEC);
        registerC2S(NetworkWithdrawRequestPayload.TYPE, NetworkWithdrawRequestPayload.STREAM_CODEC);
        registerC2S(NetworkFluidContainerRequestPayload.TYPE, NetworkFluidContainerRequestPayload.STREAM_CODEC);
        registerC2S(NetworkEnergyContainerRequestPayload.TYPE, NetworkEnergyContainerRequestPayload.STREAM_CODEC);
        registerS2C(NetworkActionResultPayload.TYPE, NetworkActionResultPayload.STREAM_CODEC);
        registerC2S(OrderBookRequestPayload.TYPE, OrderBookRequestPayload.STREAM_CODEC);
        registerS2C(OrderBookSyncPayload.TYPE, OrderBookSyncPayload.STREAM_CODEC);
        registerC2S(CraftJobRequestPayload.TYPE, CraftJobRequestPayload.STREAM_CODEC);
        registerS2C(CraftJobSyncPayload.TYPE, CraftJobSyncPayload.STREAM_CODEC);
        payloadsRegistered = true;
    }

    public static synchronized void registerServerReceivers() {
        if (serverReceiversRegistered) return;
        handleC2S(StorageOverviewRequestPayload.TYPE, PlatformNetwork::handleStorageOverviewRequest);
        handleC2S(NetworkDepositRequestPayload.TYPE, PlatformNetwork::handleNetworkDepositRequest);
        handleC2S(NetworkWithdrawRequestPayload.TYPE, PlatformNetwork::handleNetworkWithdrawRequest);
        handleC2S(NetworkFluidContainerRequestPayload.TYPE, PlatformNetwork::handleNetworkFluidContainerRequest);
        handleC2S(NetworkEnergyContainerRequestPayload.TYPE, PlatformNetwork::handleNetworkEnergyContainerRequest);
        handleC2S(OrderBookRequestPayload.TYPE, PlatformNetwork::handleOrderBookRequest);
        handleC2S(CraftJobRequestPayload.TYPE, PlatformNetwork::handleCraftJobRequest);
        serverReceiversRegistered = true;
    }

    private static void handleStorageOverviewRequest(StorageOverviewRequestPayload request, ServerPlayer player) {
        BlockPos pos = request.pos();
        // The request is read-only and bounded to a nearby loaded block. Never force-load a chunk.
        if (!withinTerminalRange(player, pos)) return;
        long now = System.nanoTime();
        Long previous = LAST_STORAGE_OVERVIEW_REQUEST.put(player.getUUID(), now);
        if (previous != null && now - previous < 150_000_000L) return;
        sendStorageOverview(player, pos, request.pageIndex());
    }

    private static void sendStorageOverview(ServerPlayer player, BlockPos pos, int requestedPage) {
        ServerLevel level = networkLevel(player, pos);
        if (!level.hasChunkAt(pos)) {
            sendToPlayer(player, StorageOverviewPayload.unavailable(pos));
            return;
        }
        SccNetworkView network = SccNetworkCache.view(level, pos);
        if (network.nodeCount() == 0 || network.storageCount() == 0) {
            sendToPlayer(player, StorageOverviewPayload.unavailable(pos));
            return;
        }

        List<StorageOverviewPayload.ResourceSummary> allResources = new ArrayList<>();
        network.itemContents().entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .limit(2048)
                .forEach(entry -> {
                    StackKey key = entry.getKey();
                    String encoded = key.encode();
                    allResources.add(new StorageOverviewPayload.ResourceSummary(
                            BuiltInRegistries.ITEM.getKey(key.item()).toString(), entry.getValue(), "items",
                            encoded.length() <= 4096 ? encoded : ""));
                });
        network.fluidContents().entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .limit(Math.max(0, 2048 - allResources.size()))
                .forEach(entry -> allResources.add(new StorageOverviewPayload.ResourceSummary(
                        BuiltInRegistries.FLUID.getKey(entry.getKey()).toString(), entry.getValue(), "mB")));
        network.chemicalContents().entrySet().stream()
                .sorted((left, right) -> Long.compare(right.getValue(), left.getValue()))
                .limit(Math.max(0, 2048 - allResources.size()))
                .forEach(entry -> allResources.add(new StorageOverviewPayload.ResourceSummary(
                        entry.getKey(), entry.getValue(), "units")));
        addResourceIfPresent(allResources, "scc:energy", network.energyStored(), "FE");
        addResourceIfPresent(allResources, "scc:source", network.sourceStored(), "units");
        addResourceIfPresent(allResources, "scc:soul", network.soulStored(), "units");

        int totalPages = Math.max(1, Math.min(256, (allResources.size() + 7) / 8));
        int pageIndex = Math.max(0, Math.min(totalPages - 1, requestedPage));
        int from = Math.min(allResources.size(), pageIndex * 8);
        int to = Math.min(allResources.size(), from + 8);
        List<StorageOverviewPayload.ResourceSummary> page = List.copyOf(allResources.subList(from, to));

        StorageOverviewPayload response = new StorageOverviewPayload(
                pos,
                true,
                "network",
                "mixed",
                0L,
                0L,
                false,
                network.empty(),
                page,
                network.nodeCount(),
                network.cableCount(),
                network.storageCount(),
                network.terminalCount(),
                network.truncated(),
                pageIndex,
                totalPages
        );
        sendToPlayer(player, response);
    }

    private static void handleNetworkDepositRequest(NetworkDepositRequestPayload request, ServerPlayer player) {
        BlockPos pos = request.pos();
        if (!withinTerminalRange(player, pos)) {
            sendActionResult(player, pos, "too_far", 0L);
            return;
        }
        long now = System.nanoTime();
        Long previous = LAST_NETWORK_DEPOSIT_REQUEST.put(player.getUUID(), now);
        if (previous != null && now - previous < 250_000_000L) return;

        ServerLevel level = networkLevel(player, pos);
        if (!level.hasChunkAt(pos)) {
            sendActionResult(player, pos, "unavailable", 0L);
            return;
        }
        SccNetworkView network = SccNetworkCache.view(level, pos);
        if (network.nodeCount() == 0 || network.storageCount() == 0 || network.itemStorageCount() == 0) {
            sendActionResult(player, pos, "unavailable", 0L);
            return;
        }
        // A truncated graph is a partial view; writes are refused to avoid silently
        // choosing an arbitrary subset of a larger storage network.
        if (network.truncated()) {
            sendActionResult(player, pos, "truncated", 0L);
            return;
        }

        ItemStack held = player.getMainHandItem();
        if (held == null || held.isEmpty()) {
            sendActionResult(player, pos, "empty_hand", 0L);
            return;
        }

        long requested = held.getCount();
        long inserted = 0L;
        try (Transaction transaction = Transaction.openOuter()) {
            inserted = network.itemTransferStorage().insert(ItemVariant.of(held), requested, transaction);
            if (inserted > 0L) {
                transaction.commit();
                held.shrink((int) inserted);
                player.getInventory().setChanged();
            }
        }
        String status = inserted >= requested ? "inserted" : inserted > 0L ? "partial" : "full";
        sendActionResult(player, pos, status, inserted);
        sendStorageOverview(player, pos, request.pageIndex());
    }


    private static void handleNetworkWithdrawRequest(NetworkWithdrawRequestPayload request, ServerPlayer player) {
        BlockPos pos = request.pos();
        if (!withinTerminalRange(player, pos)) {
            sendActionResult(player, pos, "too_far", 0L);
            return;
        }
        long now = System.nanoTime();
        Long previous = LAST_NETWORK_WITHDRAW_REQUEST.put(player.getUUID(), now);
        if (previous != null && now - previous < 250_000_000L) return;

        ServerLevel level = networkLevel(player, pos);
        if (!level.hasChunkAt(pos)) {
            sendActionResult(player, pos, "unavailable", 0L);
            return;
        }
        SccNetworkView network = SccNetworkCache.view(level, pos);
        if (network.nodeCount() == 0 || network.storageCount() == 0 || network.itemStorageCount() == 0) {
            sendActionResult(player, pos, "unavailable", 0L);
            return;
        }
        if (network.truncated()) {
            sendActionResult(player, pos, "truncated", 0L);
            return;
        }

        StackKey key;
        ItemStack single;
        try {
            key = StackKey.decode(request.variantKey());
            if (key == null || key.item() == null) throw new IllegalArgumentException("Invalid item key");
            single = key.stack(1);
            if (single == null || single.isEmpty()) throw new IllegalArgumentException("Invalid item variant");
        } catch (RuntimeException invalidKey) {
            sendActionResult(player, pos, "not_found", 0L);
            return;
        }
        String status = "not_found";
        long extracted = 0L;
        try (Transaction transaction = Transaction.openOuter()) {
            extracted = network.itemTransferStorage().extract(ItemVariant.of(single), 1L, transaction);
            if (extracted > 0L) {
                // One item per command makes Inventory.add all-or-nothing; a full inventory
                // leaves the outer storage transaction uncommitted and therefore rolled back.
                if (player.getInventory().add(key.stack((int) extracted))) {
                    transaction.commit();
                    player.getInventory().setChanged();
                    status = "extracted";
                } else {
                    extracted = 0L;
                    status = "inventory_full";
                }
            }
        }
        sendActionResult(player, pos, status, extracted);
        if ("extracted".equals(status)) sendStorageOverview(player, pos, request.pageIndex());
    }

    private static void handleNetworkFluidContainerRequest(NetworkFluidContainerRequestPayload request, ServerPlayer player) {
        BlockPos pos = request.pos();
        if (!withinTerminalRange(player, pos)) {
            sendActionResult(player, pos, "too_far", 0L);
            return;
        }
        long now = System.nanoTime();
        Long previous = LAST_NETWORK_FLUID_REQUEST.put(player.getUUID(), now);
        if (previous != null && now - previous < 350_000_000L) return;

        ServerLevel level = networkLevel(player, pos);
        if (!level.hasChunkAt(pos)) {
            sendActionResult(player, pos, "unavailable", 0L);
            return;
        }
        SccNetworkView network = SccNetworkCache.view(level, pos);
        if (network.nodeCount() == 0 || network.storageCount() == 0 || network.fluidStorageCount() == 0) {
            sendActionResult(player, pos, "unavailable", 0L);
            return;
        }
        if (network.truncated()) {
            sendActionResult(player, pos, "truncated", 0L);
            return;
        }

        // Fabric's helper checks the main-hand item's FluidStorage API, tries to fill
        // the container from the network first, then empties it into the network.
        // It owns the inventory exchange and transaction semantics (including buckets).
        boolean moved = FluidStorageUtil.interactWithFluidStorage(
                network.fluidTransferStorage(), player, InteractionHand.MAIN_HAND);
        sendActionResult(player, pos, moved ? "fluid_moved" : "fluid_unavailable", moved ? 1L : 0L);
        if (moved) sendStorageOverview(player, pos, request.pageIndex());
    }

    private static void handleNetworkEnergyContainerRequest(NetworkEnergyContainerRequestPayload request, ServerPlayer player) {
        BlockPos pos = request.pos();
        if (!withinTerminalRange(player, pos)) {
            sendActionResult(player, pos, "too_far", 0L);
            return;
        }
        long now = System.nanoTime();
        Long previous = LAST_NETWORK_ENERGY_REQUEST.put(player.getUUID(), now);
        if (previous != null && now - previous < 350_000_000L) return;

        ServerLevel level = networkLevel(player, pos);
        if (!level.hasChunkAt(pos)) {
            sendActionResult(player, pos, "unavailable", 0L);
            return;
        }
        SccNetworkView network = SccNetworkCache.view(level, pos);
        if (network.nodeCount() == 0 || network.storageCount() == 0 || network.energyStorageCount() == 0) {
            sendActionResult(player, pos, "unavailable", 0L);
            return;
        }
        if (network.truncated()) {
            sendActionResult(player, pos, "truncated", 0L);
            return;
        }

        EnergyStorage heldStorage = ContainerItemContext.ofPlayerHand(player, InteractionHand.MAIN_HAND)
                .find(EnergyStorage.ITEM);
        if (heldStorage == null) {
            sendActionResult(player, pos, "energy_unavailable", 0L);
            return;
        }

        // Prefer charging the held item from the network. If it cannot accept energy,
        // reverse the direction and deposit the held item's energy into the network.
        long moved = EnergyStorageUtil.move(network.energyTransferStorage(), heldStorage, Long.MAX_VALUE, null);
        if (moved <= 0L) {
            moved = EnergyStorageUtil.move(heldStorage, network.energyTransferStorage(), Long.MAX_VALUE, null);
        }
        sendActionResult(player, pos, moved > 0L ? "energy_moved" : "energy_unavailable", moved);
        if (moved > 0L) sendStorageOverview(player, pos, request.pageIndex());
    }

    private static void handleOrderBookRequest(OrderBookRequestPayload request, ServerPlayer player) {
        BlockPos pos = request.pos();
        if (!withinTerminalRange(player, pos)) {
            sendActionResult(player, pos, "too_far", 0L);
            return;
        }
        long now = System.nanoTime();
        if ("plan".equals(request.action())) {
            Long previous = LAST_ORDER_PLAN_REQUEST.put(player.getUUID(), now);
            if (previous != null && now - previous < 750_000_000L) return;
        } else {
            Long previous = LAST_ORDER_BOOK_REQUEST.put(player.getUUID(), now);
            if (previous != null && now - previous < 200_000_000L) return;
        }

        ServerLevel level = networkLevel(player, pos);
        if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof SccTerminalBlockEntity terminal)) {
            sendToPlayer(player, new OrderBookSyncPayload(pos, false, List.of()));
            return;
        }
        SccNetworkView network = SccNetworkCache.view(level, pos);
        if (network.truncated() || !network.terminals().contains(pos)) {
            sendToPlayer(player, new OrderBookSyncPayload(pos, false, List.of()));
            return;
        }

        String status = "order_listed";
        long resultAmount = 0L;
        if ("plan".equals(request.action())) {
            SccServerRecipePlanner.BatchOutcome outcome = SccServerRecipePlanner.planOrderList(
                    level, pos, request.id(), player.getUUID());
            status = outcome.status();
            resultAmount = outcome.created();
            if (outcome.created() > 0) {
                sendToPlayer(player, CraftJobSyncPayload.from(pos, terminal.craftJobs(), player.getUUID()));
            }
        } else if ("put".equals(request.action())) {
            List<OrderBook.Line> lines = request.lines().stream()
                    .map(line -> new OrderBook.Line(line.key(), line.amount())).toList();
            String id = terminal.orderBook().put(request.id(), request.name(), request.topUp(), lines);
            if (id == null) {
                status = "order_invalid";
            } else if (terminal.orderBook().byId(id) == null
                    || terminal.orderBook().byId(id).lines().isEmpty()) {
                status = "order_invalid";
                terminal.orderBook().remove(id);
            } else {
                terminal.setChanged();
                status = "order_saved";
            }
        } else if ("remove".equals(request.action())) {
            boolean removed = terminal.orderBook().remove(request.id());
            if (removed) terminal.setChanged();
            status = removed ? "order_removed" : "order_not_found";
        }

        sendActionResult(player, pos, status, resultAmount);
        sendToPlayer(player, OrderBookSyncPayload.from(pos, terminal.orderBook()));
    }

    private static void handleCraftJobRequest(CraftJobRequestPayload request, ServerPlayer player) {
        BlockPos pos = request.pos();
        if (!withinTerminalRange(player, pos)) {
            sendActionResult(player, pos, "too_far", 0L);
            return;
        }
        long now = System.nanoTime();
        Long previous = LAST_CRAFT_JOB_REQUEST.put(player.getUUID(), now);
        if (previous != null && now - previous < 500_000_000L) return;

        ServerLevel level = networkLevel(player, pos);
        if (!level.hasChunkAt(pos) || !(level.getBlockEntity(pos) instanceof SccTerminalBlockEntity terminal)) {
            sendToPlayer(player, new CraftJobSyncPayload(pos, false, List.of()));
            sendActionResult(player, pos, "job_unavailable", 0L);
            return;
        }
        SccNetworkView network = SccNetworkCache.view(level, pos);
        if (network.truncated() || !network.terminals().contains(pos)) {
            sendToPlayer(player, new CraftJobSyncPayload(pos, false, List.of()));
            sendActionResult(player, pos, "job_network_unavailable", 0L);
            return;
        }

        String status = "job_unavailable";
        if ("plan".equals(request.action())) {
            SccServerRecipePlanner.Outcome outcome = SccServerRecipePlanner.plan(
                    level, pos, request.targetKey(), request.amount(), player.getUUID());
            status = outcome.status();
        } else if ("cancel".equals(request.action())) {
            SccCraftJobQueue.Job job = terminal.craftJobs().byId(request.jobId());
            if (job == null) status = "job_not_found";
            else if (!player.getUUID().toString().equals(job.owner())) status = "job_not_owner";
            else if (terminal.craftJobs().cancel(request.jobId(), player.getUUID(), level.getGameTime())) {
                terminal.setChanged();
                status = "job_cancelled";
            } else status = "job_not_found";
        } else if ("sync".equals(request.action())) {
            status = "job_ready";
        }
        sendActionResult(player, pos, status, 0L);
        sendToPlayer(player, CraftJobSyncPayload.from(pos, terminal.craftJobs(), player.getUUID()));
    }

    private static ServerLevel networkLevel(ServerPlayer player, BlockPos pos) {
        return de.scc.calculator.network.WirelessAccess.networkLevel(player, pos);
    }

    private static boolean withinTerminalRange(ServerPlayer player, BlockPos pos) {
        if (pos == null) return false;
        ServerLevel level = networkLevel(player, pos);
        if (level == null) return false;
        if (!level.hasChunkAt(pos)) {
            return level == player.serverLevel() && player.distanceToSqr(
                    pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D;
        }
        if (level.getBlockEntity(pos) instanceof de.scc.calculator.network.SccNetworkAntennaBlockEntity) {
            if (!de.scc.calculator.network.WirelessAccess.activeSession(player, pos, level)) return false;
            return de.scc.calculator.network.WirelessAccess.allowsAccess(player, pos, level);
        }
        return level == player.serverLevel() && player.distanceToSqr(
                pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= 64.0D;
    }

    private static void sendActionResult(ServerPlayer player, BlockPos pos, String status, long amount) {
        sendToPlayer(player, new NetworkActionResultPayload(pos, status, amount));
    }

    private static void addResourceIfPresent(
            List<StorageOverviewPayload.ResourceSummary> target, String id, long amount, String unit) {
        if (amount > 0L && target.size() < 2048) {
            target.add(new StorageOverviewPayload.ResourceSummary(id, amount, unit));
        }
    }

    public static void clearPlayerSession(UUID playerId) {
        if (playerId != null) {
            LAST_STORAGE_OVERVIEW_REQUEST.remove(playerId);
            LAST_NETWORK_DEPOSIT_REQUEST.remove(playerId);
            LAST_NETWORK_WITHDRAW_REQUEST.remove(playerId);
            LAST_NETWORK_FLUID_REQUEST.remove(playerId);
            LAST_NETWORK_ENERGY_REQUEST.remove(playerId);
            LAST_ORDER_BOOK_REQUEST.remove(playerId);
            LAST_ORDER_PLAN_REQUEST.remove(playerId);
            LAST_CRAFT_JOB_REQUEST.remove(playerId);
        }
    }

    public static void clearSessionState() {
        LAST_STORAGE_OVERVIEW_REQUEST.clear();
        LAST_NETWORK_DEPOSIT_REQUEST.clear();
        LAST_NETWORK_WITHDRAW_REQUEST.clear();
        LAST_NETWORK_FLUID_REQUEST.clear();
        LAST_NETWORK_ENERGY_REQUEST.clear();
        LAST_ORDER_BOOK_REQUEST.clear();
        LAST_ORDER_PLAN_REQUEST.clear();
        LAST_CRAFT_JOB_REQUEST.clear();
    }

    public static <T extends CustomPacketPayload> void registerC2S(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        PayloadTypeRegistry.playC2S().register(type, codec);
    }

    public static <T extends CustomPacketPayload> void registerS2C(
            CustomPacketPayload.Type<T> type, StreamCodec<? super RegistryFriendlyByteBuf, T> codec) {
        PayloadTypeRegistry.playS2C().register(type, codec);
    }

    /** O handler ja roda na thread do servidor (Fabric API 1.21): nao precisa de server.execute(). */
    public static <T extends CustomPacketPayload> void handleC2S(
            CustomPacketPayload.Type<T> type, BiConsumer<T, ServerPlayer> handler) {
        ServerPlayNetworking.registerGlobalReceiver(type, (payload, context) -> handler.accept(payload, context.player()));
    }

    public static void sendToPlayer(ServerPlayer player, CustomPacketPayload payload) {
        if (ServerPlayNetworking.canSend(player, payload.type())) {
            ServerPlayNetworking.send(player, payload);
        }
    }

    public static void sendToAllPlayers(MinecraftServer server, CustomPacketPayload payload) {
        for (ServerPlayer player : PlayerLookup.all(server)) {
            sendToPlayer(player, payload);
        }
    }

    public static void sendToPlayersTrackingBlock(ServerLevel level, BlockPos pos, CustomPacketPayload payload) {
        for (ServerPlayer player : PlayerLookup.tracking(level, pos)) {
            sendToPlayer(player, payload);
        }
    }

    public static void sendToPlayersTrackingChunk(ServerLevel level, ChunkPos pos, CustomPacketPayload payload) {
        for (ServerPlayer player : PlayerLookup.tracking(level, pos)) {
            sendToPlayer(player, payload);
        }
    }
}
