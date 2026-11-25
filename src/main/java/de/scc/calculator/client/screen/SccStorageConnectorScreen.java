package de.scc.calculator.client.screen;

import de.scc.calculator.network.SccStorageConnectorBlockEntity;
import de.scc.calculator.network.SccStorageConnectorMenu;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;

/** Compact connector configuration screen; all mutations are server menu actions. */
public final class SccStorageConnectorScreen extends AbstractContainerScreen<SccStorageConnectorMenu> {
    private Button flow;
    private Button voidMode;
    private Button filterReads;
    private Button blacklist;
    private Button carried;
    private Button drain;

    public SccStorageConnectorScreen(SccStorageConnectorMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 220;
        imageHeight = 242;
        inventoryLabelX = 8;
        inventoryLabelY = 111;
        titleLabelX = 8;
        titleLabelY = 7;
    }

    @Override protected void init() {
        super.init();
        flow = addRenderableWidget(Button.builder(Component.literal("Flow"), b -> action(3))
                .bounds(leftPos + 8, topPos + 84, 50, 18).build());
        addRenderableWidget(Button.builder(Component.literal("P-"), b -> action(590))
                .bounds(leftPos + 60, topPos + 84, 20, 18).build());
        addRenderableWidget(Button.builder(Component.literal("P+"), b -> action(610))
                .bounds(leftPos + 82, topPos + 84, 20, 18).build());
        addRenderableWidget(Button.builder(Component.literal("E-"), b -> action(2490))
                .bounds(leftPos + 104, topPos + 84, 20, 18).build());
        addRenderableWidget(Button.builder(Component.literal("E+"), b -> action(2510))
                .bounds(leftPos + 126, topPos + 84, 20, 18).build());
        addRenderableWidget(Button.builder(Component.literal("E=P"), b -> action(5))
                .bounds(leftPos + 148, topPos + 84, 30, 18).build());
        voidMode = addRenderableWidget(Button.builder(Component.literal("Void"), b -> action(4))
                .bounds(leftPos + 180, topPos + 84, 32, 18).build());

        addRenderableWidget(Button.builder(Component.literal("Lock"), b -> action(0))
                .bounds(leftPos + 8, topPos + 104, 32, 18).build());
        addRenderableWidget(Button.builder(Component.literal("Clear"), b -> action(1))
                .bounds(leftPos + 42, topPos + 104, 32, 18).build());
        carried = addRenderableWidget(Button.builder(Component.literal("Carried"), b -> action(2))
                .bounds(leftPos + 76, topPos + 104, 42, 18).build());
        drain = addRenderableWidget(Button.builder(Component.literal("Drain"), b -> action(6))
                .bounds(leftPos + 76, topPos + 104, 42, 18).build());
        filterReads = addRenderableWidget(Button.builder(Component.literal("Read"), b -> action(7))
                .bounds(leftPos + 120, topPos + 104, 44, 18).build());
        blacklist = addRenderableWidget(Button.builder(Component.literal("Blacklist"), b -> action(8))
                .bounds(leftPos + 166, topPos + 104, 46, 18).build());
        drain.visible = false;
        carried.visible = true;
        updateLabels();
    }

    private void action(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    private void updateLabels() {
        SccStorageConnectorBlockEntity connector = menu.connector();
        if (connector == null) return;
        flow.setMessage(Component.literal("Flow: " + connector.flow().name()));
        voidMode.setMessage(Component.literal(connector.voidPerKind() ? "Void KIND"
                : connector.voidOverflow() ? "Void ON" : "Void OFF"));
        filterReads.setMessage(Component.literal("Read " + (connector.filterReads() ? "ON" : "OFF")));
        blacklist.setMessage(Component.literal(connector.blacklist() ? "Black ON" : "White"));
        boolean localStorage = connector instanceof de.scc.calculator.network.SccStorageBlockEntity;
        carried.visible = !localStorage;
        drain.visible = localStorage;
    }

    @Override protected void containerTick() {
        super.containerTick();
        updateLabels();
    }

    @Override protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xC0101010);
        graphics.fill(leftPos + 4, topPos + 22, leftPos + imageWidth - 4, topPos + 80, 0xFF282828);
    }

    @Override protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        SccStorageConnectorBlockEntity connector = menu.connector();
        graphics.drawString(font, Component.literal("Filter"), 8, 17, 0xFFFFFF, false);
        if (connector != null) {
            graphics.drawString(font, Component.literal("Priority: " + connector.priority()
                    + "  Extract: " + connector.extractPriority()), 8, 75, 0xFFFFFF, false);
            graphics.drawString(font, Component.literal("Fluid lock: "
                    + (connector.lockedFluid() == null ? "none" : BuiltInRegistries.FLUID.getKey(connector.lockedFluid()).toString())
                    + "  voided: " + connector.voidedItems() + " items / " + connector.voidedFluid() + " droplets"),
                    8, 224, 0xFFFFFF, false);
        }
        super.renderLabels(graphics, mouseX, mouseY);
    }

    @Override public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }
}
