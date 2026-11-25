package de.scc.calculator.client.screen;

import de.scc.calculator.network.SccCraftingTerminalMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/** Fabric screen for the persistent, network-backed 3x3 crafting terminal. */
public final class SccCraftingTerminalScreen
        extends AbstractContainerScreen<SccCraftingTerminalMenu> {

    public SccCraftingTerminalScreen(SccCraftingTerminalMenu menu,
                                     Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 204;
        inventoryLabelX = 8;
        inventoryLabelY = 73;
        titleLabelX = 8;
        titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        addRenderableWidget(Button.builder(
                Component.translatable("screen.scc.crafting_terminal.refill"),
                button -> {
                    if (minecraft != null && minecraft.gameMode != null) {
                        minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                                SccCraftingTerminalMenu.ACTION_REFILL);
                    }
                }).bounds(leftPos + 7, topPos + 164, 70, 16).build());
        addRenderableWidget(Button.builder(
                Component.translatable("screen.scc.crafting_terminal.pick_recipe"),
                button -> {
                    if (minecraft != null && minecraft.gameMode != null) {
                        minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                                SccCraftingTerminalMenu.ACTION_PICK_RECIPE);
                    }
                }).bounds(leftPos + 91, topPos + 164, 78, 16).build());
        addRenderableWidget(Button.builder(
                Component.translatable("screen.scc.crafting_terminal.clear"),
                button -> {
                    if (minecraft != null && minecraft.gameMode != null) {
                        minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                                SccCraftingTerminalMenu.ACTION_CLEAR);
                    }
                }).bounds(leftPos + 7, topPos + 184, 70, 16).build());
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick,
                            int mouseX, int mouseY) {
        int left = leftPos;
        int top = topPos;
        graphics.fill(left, top, left + imageWidth, top + imageHeight, 0xFF20242B);
        graphics.fill(left + 7, top + 17, left + 81, top + 73, 0xFF11151B);
        graphics.fill(left + 7, top + 83, left + 169, top + 159, 0xFF11151B);
        graphics.fill(left + 25, top + 17, left + 81, top + 73, 0xFF343B45);
        graphics.fill(left + 7, top + 83, left + 169, top + 159, 0xFF343B45);
        graphics.fill(left + 115, top + 34, left + 135, top + 54, 0xFF11151B);
        graphics.fill(left + 7, top + 162, left + 169, top + 202, 0xFF11151B);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, 0xFFFFFF, false);
        graphics.drawString(font,
                Component.translatable("screen.scc.crafting_terminal.workspace"),
                26, 6, 0xC7D3E0, false);
        graphics.drawString(font, Component.translatable("container.inventory"),
                inventoryLabelX, inventoryLabelY, 0x404040, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
