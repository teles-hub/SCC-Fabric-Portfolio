package de.scc.calculator.client;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import de.scc.calculator.SccConfig;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import de.scc.calculator.network.SccStorageBlockEntity;
import de.scc.calculator.network.SccCableBlockEntity;
import de.scc.calculator.network.SccTerminalBlockEntity;
import de.scc.calculator.client.screen.StorageOverviewScreen;
import de.scc.calculator.client.screen.AnalysisStatusScreen;

/** Eventos de CLIENTE. */
@Environment(EnvType.CLIENT)
public final class PlatformClientEvents {

    private PlatformClientEvents() {
    }

    @FunctionalInterface
    public interface ScreenRenderPost {
        void render(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick);
    }

    public static void registerClientEvents() {
        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> SccConfig.save());
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            AnalysisManager.get().tick(SccConfig.get().analysisMillisPerTick);
            if (client.player == null || client.level == null) return;
            if (SccClientRegistry.OPEN_STORAGE_PANEL_KEY != null
                    && SccClientRegistry.OPEN_STORAGE_PANEL_KEY.consumeClick()
                    && client.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
                    && (client.level.getBlockEntity(hit.getBlockPos()) instanceof SccStorageBlockEntity
                    || client.level.getBlockEntity(hit.getBlockPos()) instanceof SccCableBlockEntity
                    || client.level.getBlockEntity(hit.getBlockPos()) instanceof SccTerminalBlockEntity)) {
                StorageOverviewScreen.open(hit.getBlockPos());
                PlatformNetworkClient.requestStorageOverview(hit.getBlockPos());
            }
            if (SccClientRegistry.OPEN_ANALYSIS_STATUS_KEY != null
                    && SccClientRegistry.OPEN_ANALYSIS_STATUS_KEY.consumeClick()) {
                client.setScreen(new AnalysisStatusScreen());
            }
        });
        WorldRenderEvents.AFTER_TRANSLUCENT.register(context -> {
            // TODO: RenderLevelStageEvent
        });
        HudRenderCallback.EVENT.register((graphics, tickCounter) -> {
            // TODO: RegisterGuiLayersEvent / RenderGuiLayerEvent (4 classes)
        });
        ItemTooltipCallback.EVENT.register((stack, tooltipContext, tooltipType, lines) -> {
            // TODO: ItemTooltipEvent
        });
    }

    /**
     * CORRECAO: ScreenEvent.Render.Post do NeoForge equivale a ScreenEvents.afterRender(screen), NAO a HudRenderCallback.
     * O registro e feito por tela, no AFTER_INIT.
     */
    public static void onScreenRenderPost(ScreenRenderPost callback) {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) ->
                ScreenEvents.afterRender(screen).register(callback::render));
    }
}
