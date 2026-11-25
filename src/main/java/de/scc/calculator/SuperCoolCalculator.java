package de.scc.calculator;

import de.scc.calculator.platform.PlatformEvents;
import de.scc.calculator.platform.PlatformNetwork;
import de.scc.calculator.platform.PlatformUtils;
import de.scc.calculator.platform.SccMachineAdapters;
import de.scc.calculator.network.SccStorageLimits;
import de.scc.calculator.network.SccCraftingTerminalNetwork;
import de.scc.calculator.network.StorageTier;
import de.scc.calculator.registry.SccRegistry;
import de.scc.calculator.registry.SccRecipes;
import net.fabricmc.api.ModInitializer;
import net.minecraft.resources.ResourceLocation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Entrypoint principal (substitui @Mod("scc") e o IEventBus do NeoForge). */
public class SuperCoolCalculator implements ModInitializer {

    public static final String MOD_ID = "scc";
    public static final Logger LOGGER = LoggerFactory.getLogger("SCC");

    public static ResourceLocation id(String path) {
        return ResourceLocation.fromNamespaceAndPath(MOD_ID, path);
    }

    @Override
    public void onInitialize() {
        LOGGER.info("Inicializando SCC - Super Cool Calculator (Fabric 1.21.1)...");

        SccConfig.load();
        SccStorageLimits.load();
        StorageTier.reloadLimits();

        // 1. Registros (inclui exposicao de Storage/Energy nas BlockEntities)
        SccRegistry.registerAll();
        SccRecipes.init();

        // 2. Pontes opcionais de inventário (Cobblemon e futuras integrações)
        SccMachineAdapters.registerBuiltIns();

        // 3. Rede: tipos de payload ANTES dos receptores
        PlatformNetwork.registerPayloads();
        PlatformNetwork.registerServerReceivers();
        SccCraftingTerminalNetwork.register();

        // 4. Eventos comuns
        PlatformEvents.registerCommonEvents();

        // 5. Compat condicional (so as integracoes reais que sobrarem da poda)
        if (PlatformUtils.isModLoaded("cobblemon")) {
            LOGGER.info("Cobblemon detectado (CobblemonPot sera o ponto de integracao).");
        }

        LOGGER.info("SCC inicializado (ambiente comum).");
    }
}
