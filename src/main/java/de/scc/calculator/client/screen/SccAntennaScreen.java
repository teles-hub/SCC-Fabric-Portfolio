package de.scc.calculator.client.screen;

import de.scc.calculator.network.SccAntennaMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Compact antenna link configuration screen. */
public final class SccAntennaScreen extends AbstractContainerScreen<SccAntennaMenu> {
    public SccAntennaScreen(SccAntennaMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 190;
        inventoryLabelX = 8;
        inventoryLabelY = 94;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.translatable("scc.gui.antenna.link"), button ->
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, SccAntennaMenu.ACTION_LINK))
                .bounds(leftPos + 103, topPos + 25, 32, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("scc.gui.antenna.unlink"), button ->
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, SccAntennaMenu.ACTION_UNLINK))
                .bounds(leftPos + 103, topPos + 49, 48, 20).build());
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xE51A1D24);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 2, 0xFF4B9AD8);
        graphics.fill(leftPos + 8, topPos + 25, leftPos + 98, topPos + 45, 0xFF101318);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, 8, 6, 0xFFFFFFFF, false);
        graphics.drawString(font, Component.translatable("scc.gui.antenna.hint"), 8, 31, 0xFFE2E8EF, false);
        graphics.drawString(font, Component.translatable("scc.gui.antenna.link.tip"), 8, 57, 0xFFB9C4D0, false);
        graphics.drawString(font, Component.translatable("container.inventory"), inventoryLabelX, inventoryLabelY, 0xFFE2E8EF, false);
    }
}
