package de.scc.calculator.client.screen;

import de.scc.calculator.network.SccRedstoneEmitterBlockEntity;
import de.scc.calculator.network.SccRedstoneEmitterMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

public final class SccRedstoneEmitterScreen extends AbstractContainerScreen<SccRedstoneEmitterMenu> {
    public SccRedstoneEmitterScreen(SccRedstoneEmitterMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 202;
        inventoryLabelY = 92;
    }

    @Override protected void init() {
        super.init();
        addRenderableWidget(Button.builder(Component.literal("Use held item"), button -> {
            ItemStack held = minecraft.player.getMainHandItem();
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                    SccRedstoneEmitterMenu.itemButton(held.isEmpty() ? null : held.getItem()));
        }).bounds(leftPos + 8, topPos + 22, 88, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Clear item"), button ->
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                        SccRedstoneEmitterMenu.itemButton(null)))
                .bounds(leftPos + 100, topPos + 22, 68, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Threshold -"), button -> {
            SccRedstoneEmitterBlockEntity emitter = menu.emitter();
            if (emitter != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                    SccRedstoneEmitterMenu.amountButton(Math.max(0L, emitter.amount() - 1L)));
        }).bounds(leftPos + 8, topPos + 46, 78, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Threshold +"), button -> {
            SccRedstoneEmitterBlockEntity emitter = menu.emitter();
            if (emitter != null) minecraft.gameMode.handleInventoryButtonClick(menu.containerId,
                    SccRedstoneEmitterMenu.amountButton(Math.min(999_999L, emitter.amount() + 1L)));
        }).bounds(leftPos + 90, topPos + 46, 78, 20).build());
        addRenderableWidget(Button.builder(Component.literal("Mode"), button ->
                minecraft.gameMode.handleInventoryButtonClick(menu.containerId, SccRedstoneEmitterMenu.ACTION_MODE))
                .bounds(leftPos + 8, topPos + 68, 160, 20).build());
    }

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xC0101010);
        graphics.fill(leftPos + 4, topPos + 20, leftPos + imageWidth - 4, topPos + 88, 0xFF282828);
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        SccRedstoneEmitterBlockEntity emitter = menu.emitter();
        if (emitter != null) {
            String item = emitter.item() == null ? "none" : emitter.item().toString();
            graphics.drawString(font, Component.literal("Item: " + item), 8, 8, 0xFFFFFF, false);
            graphics.drawString(font, Component.literal("Threshold: " + emitter.amount()), 8, 91, 0xFFFFFF, false);
            graphics.drawString(font, Component.literal("Mode: " + (emitter.mode() == 0 ? "at least" : "at most")
                    + " | Stock: " + emitter.have() + " | Output: " + (emitter.lit() ? "ON" : "OFF")),
                    8, 104, 0xFFFFFF, false);
        }
        super.renderLabels(graphics, mouseX, mouseY);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
