package de.scc.calculator.client;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entrypoint de cliente (substitui @Mod(dist = CLIENT)). */
@Environment(EnvType.CLIENT)
public class SuperCoolCalculatorClient implements ClientModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("SCC-Client");

    @Override
    public void onInitializeClient() {
        LOGGER.info("Inicializando cliente do SCC (Fabric 1.21.1)...");

        PlatformNetworkClient.registerClientReceivers();

        SccClientRegistry.registerScreens();
        SccClientRegistry.registerRenderers();
        SccClientRegistry.registerKeybinds();
        SccClientRegistry.registerColorProviders();
        SccClientRegistry.registerPickBlockCallbacks();
        // TODO (secao 5 do guia): renderizadores de entidade, shaders, ModelLoadingPlugin, camadas de jogador,
        // HUD, reload listeners, ceu, IClientItemExtensions, ArmorRenderer.

        PlatformClientEvents.registerClientEvents();

        LOGGER.info("Cliente do SCC inicializado.");
    }
}
