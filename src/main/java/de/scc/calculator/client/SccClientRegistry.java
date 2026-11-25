package de.scc.calculator.client;

import com.mojang.blaze3d.platform.InputConstants;
import de.scc.calculator.SuperCoolCalculator;
import de.scc.calculator.registry.SccMenus;
import de.scc.calculator.client.screen.SccTerminalScreen;
import de.scc.calculator.client.screen.SccCraftingTerminalScreen;
import de.scc.calculator.client.screen.SccStorageConnectorScreen;
import de.scc.calculator.client.screen.SccRedstoneEmitterScreen;
import de.scc.calculator.client.screen.SccAntennaScreen;
import de.scc.calculator.client.renderer.SccCableBusRenderer;
import de.scc.calculator.client.renderer.SccFacadeRenderer;
import de.scc.calculator.registry.SccBlockEntities;
import de.scc.calculator.network.SccCableBusBlockEntity;
import de.scc.calculator.registry.SccNetworkBlocks;
import net.fabricmc.fabric.api.event.client.player.ClientPickBlockGatherCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.rendering.v1.ColorProviderRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.EntityModelLayerRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.screens.MenuScreens;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.MenuAccess;
import net.minecraft.client.model.geom.ModelLayerLocation;
import net.minecraft.client.renderer.blockentity.BlockEntityRendererProvider;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderers;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Centraliza todos os registros exclusivos de Cliente do SCC no Fabric 1.21.1.
 * Inclui cabos/fachadas, conectores, emissores e a rede sem fio com antena.
 */
@Environment(EnvType.CLIENT)
public final class SccClientRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("SCC-ClientRegistry");
    private static final List<KeyMapping> REGISTERED_KEYS = new ArrayList<>();

    public static KeyMapping OPEN_STORAGE_PANEL_KEY;
    public static KeyMapping OPEN_ANALYSIS_STATUS_KEY;

    private SccClientRegistry() {
    }

    public static void registerScreens() {
        LOGGER.info("Registrando telas (MenuScreens) do SCC...");

        registerScreen(SccMenus.TERMINAL.get(), SccTerminalScreen::new);
        registerScreen(SccMenus.CRAFTING_TERMINAL.get(), SccCraftingTerminalScreen::new);
        registerScreen(SccMenus.STORAGE_CONNECTOR.get(), SccStorageConnectorScreen::new);
        registerScreen(SccMenus.REDSTONE_EMITTER.get(), SccRedstoneEmitterScreen::new);
        registerScreen(SccMenus.ANTENNA.get(), SccAntennaScreen::new);
        PlatformNetworkClient.registerSecondaryScreenReceivers(
                SccTerminalScreen::accept, SccTerminalScreen::acceptActionResult);
        PlatformNetworkClient.registerSecondaryOrderBookReceiver(SccTerminalScreen::acceptOrderBook);
        PlatformNetworkClient.registerSecondaryCraftJobReceiver(SccTerminalScreen::acceptCraftJobs);
    }

    /** Pick-block de peças do Cable Bus, preservando o estado da fachada. */
    public static void registerPickBlockCallbacks() {
        ClientPickBlockGatherCallback.EVENT.register((player, result) -> {
            if (!(result instanceof BlockHitResult hit)) return ItemStack.EMPTY;
            BlockPos pos = hit.getBlockPos();
            if (!(player.level().getBlockEntity(pos) instanceof SccCableBusBlockEntity bus)) return ItemStack.EMPTY;
            Direction side = hit.getDirection();
            var part = bus.part(side);
            if (part == null) return new ItemStack(SccNetworkBlocks.CABLE.get());
            var partState = bus.partState(side);
            if (partState == null) return ItemStack.EMPTY;
            ItemStack picked = partState.getBlock().getCloneItemStack(player.level(), pos, partState);
            if (!picked.isEmpty()) part.saveToItem(picked, player.level().registryAccess());
            return picked;
        });
    }

    public static void registerRenderers() {
        LOGGER.info("Registrando BlockEntityRenderers e ModelLayers do SCC...");
        registerBlockEntityRenderer(SccBlockEntities.SCC_CABLE_BUS.get(), SccCableBusRenderer::new);
        registerBlockEntityRenderer(SccBlockEntities.SCC_CABLE.get(), SccFacadeRenderer::new);
        registerBlockEntityRenderer(SccBlockEntities.SCC_STORAGE.get(), SccFacadeRenderer::new);
        registerBlockEntityRenderer(SccBlockEntities.SCC_STORAGE_CONNECTOR.get(), SccFacadeRenderer::new);
        registerBlockEntityRenderer(SccBlockEntities.SCC_TERMINAL.get(), SccFacadeRenderer::new);
        registerBlockEntityRenderer(SccBlockEntities.SCC_CRAFTING_TERMINAL.get(), SccFacadeRenderer::new);
        registerBlockEntityRenderer(SccBlockEntities.SCC_WIRELESS_TRANSMITTER.get(), SccFacadeRenderer::new);
        registerBlockEntityRenderer(SccBlockEntities.SCC_WIRELESS_RECEIVER.get(), SccFacadeRenderer::new);
        registerBlockEntityRenderer(SccBlockEntities.SCC_REDSTONE_EMITTER.get(), SccFacadeRenderer::new);
        registerBlockEntityRenderer(SccBlockEntities.SCC_NETWORK_ANTENNA.get(), SccFacadeRenderer::new);
    }

    public static void registerKeybinds() {
        LOGGER.info("Registrando KeyMappings do SCC...");

        String category = "key.categories." + SuperCoolCalculator.MOD_ID;

        OPEN_STORAGE_PANEL_KEY = registerKey(
                "key." + SuperCoolCalculator.MOD_ID + ".open_storage_panel",
                GLFW.GLFW_KEY_C,
                category
        );

        OPEN_ANALYSIS_STATUS_KEY = registerKey(
                "key." + SuperCoolCalculator.MOD_ID + ".open_analysis_status",
                GLFW.GLFW_KEY_V,
                category
        );
    }

    public static void registerColorProviders() {
        // Fachadas usam o Block/Item real; a tinta é resolvida pelo modelo da fachada.
    }

    public static <M extends AbstractContainerMenu, U extends Screen & MenuAccess<M>> void registerScreen(
            MenuType<? extends M> type,
            MenuScreens.ScreenConstructor<M, U> factory
    ) {
        MenuScreens.register(type, factory);
    }

    public static <T extends BlockEntity> void registerBlockEntityRenderer(
            BlockEntityType<? extends T> type,
            BlockEntityRendererProvider<T> provider
    ) {
        BlockEntityRenderers.register(type, provider);
    }

    public static void registerLayer(
            ModelLayerLocation location,
            EntityModelLayerRegistry.TexturedModelDataProvider provider
    ) {
        EntityModelLayerRegistry.registerModelLayer(location, provider);
    }

    public static KeyMapping registerKey(String translationKey, int defaultKeyCode, String category) {
        KeyMapping mapping = new KeyMapping(
                translationKey,
                InputConstants.Type.KEYSYM,
                defaultKeyCode,
                category
        );
        KeyBindingHelper.registerKeyBinding(mapping);
        REGISTERED_KEYS.add(mapping);
        return mapping;
    }

    public static List<KeyMapping> getRegisteredKeys() {
        return Collections.unmodifiableList(REGISTERED_KEYS);
    }
}
