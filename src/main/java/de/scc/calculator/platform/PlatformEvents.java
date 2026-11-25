package de.scc.calculator.platform;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import de.scc.calculator.SccConfig;
import de.scc.calculator.network.SccNetworkCache;
import de.scc.calculator.network.SccCraftingTerminalNetwork;
import de.scc.calculator.network.SccCableBusBlock;
import de.scc.calculator.network.SccHostedNetworkPart;
import de.scc.calculator.registry.SccNetworkBlocks;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.world.InteractionResult;
import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerChunkEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;

/**
 * Eventos COMUNS (servidor). Os eventos de cliente ficam em client/PlatformClientEvents.
 * Esqueleto: cada TODO deve ser ligado a uma classe real do mod (SccEvents, tick handlers etc.).
 */
public final class PlatformEvents {

    private PlatformEvents() {
    }

    public static void registerCommonEvents() {
        ServerLifecycleEvents.SERVER_STARTED.register(PlatformCatalystTags::reload);
        ServerLifecycleEvents.END_DATA_PACK_RELOAD.register((server, resourceManager, success) -> {
            if (success) {
                PlatformCatalystTags.reload(server);
            }
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SccConfig.save();
            PlatformNetwork.clearSessionState();
            SccCraftingTerminalNetwork.clearAll();
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            PlatformNetwork.clearPlayerSession(handler.player.getUUID());
            SccCraftingTerminalNetwork.clearPlayer(handler.player.getUUID());
        });
        // Vanilla 1.21.1 has no NeoForge-style BlockEntity.onLoad callback.
        // Invalidate network topology at chunk lifecycle boundaries instead.
        ServerChunkEvents.CHUNK_LOAD.register((world, chunk) -> SccNetworkCache.invalidate(world));
        ServerChunkEvents.CHUNK_UNLOAD.register((world, chunk) -> SccNetworkCache.invalidate(world));
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (!player.getItemInHand(hand).is(SccNetworkBlocks.CABLE.get().asItem())) {
                return InteractionResult.PASS;
            }
            if (!(world.getBlockEntity(hit.getBlockPos()) instanceof SccHostedNetworkPart)) {
                return InteractionResult.PASS;
            }
            if (world.isClientSide) return InteractionResult.SUCCESS;
            if (!SccCableBusBlock.convertPartToBus(world, hit.getBlockPos(), hit.getDirection())) {
                return InteractionResult.FAIL;
            }
            if (!player.getAbilities().instabuild) player.getItemInHand(hand).shrink(1);
            return InteractionResult.CONSUME;
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            // TODO
        });
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            // TODO
        });
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            // TODO: sincronizar estado do jogador
        });
        ServerPlayerEvents.COPY_FROM.register((oldPlayer, newPlayer, alive) -> {
            // TODO: preservar estado ao renascer/trocar de dimensao
        });
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            // false cancela a quebra (equivale a BlockEvent.BreakEvent.setCanceled(true))
            return true;
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            // TODO: registrar comandos
        });
    }
}
