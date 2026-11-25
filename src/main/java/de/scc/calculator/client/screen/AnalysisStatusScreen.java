package de.scc.calculator.client.screen;

import de.scc.calculator.client.AnalysisManager;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Live client interface for recipe discovery and graph analysis. */
public final class AnalysisStatusScreen extends Screen {
    private static final int PANEL_WIDTH = 280;
    private static final int PANEL_HEIGHT = 154;

    public AnalysisStatusScreen() {
        super(Component.translatable("screen.scc.analysis.title"));
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        AnalysisManager manager = AnalysisManager.get();
        if (keyCode == GLFW.GLFW_KEY_R) {
            manager.reanalyse();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_X) {
            manager.cancel();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics, mouseX, mouseY, partialTick);
        int left = (width - PANEL_WIDTH) / 2;
        int top = (height - PANEL_HEIGHT) / 2;
        graphics.fill(left, top, left + PANEL_WIDTH, top + PANEL_HEIGHT, 0xE51A1D24);
        graphics.fill(left, top, left + PANEL_WIDTH, top + 2, 0xFF4B9AD8);
        graphics.drawCenteredString(font, title, width / 2, top + 12, 0xFFFFFFFF);

        AnalysisManager manager = AnalysisManager.get();
        int percent = (int) Math.round(Math.max(0.0D, Math.min(1.0D, manager.progress())) * 100.0D);
        graphics.drawString(font, Component.translatable("screen.scc.analysis.state", manager.state().name()),
                left + 12, top + 34, 0xFFFFFFFF);
        graphics.drawString(font, Component.translatable("screen.scc.analysis.recipes", manager.recipesFound()),
                left + 12, top + 50, 0xFFFFFFFF);
        graphics.drawString(font, Component.translatable("screen.scc.analysis.items",
                manager.itemsResolved(), manager.totalItems()), left + 12, top + 66, 0xFFFFFFFF);

        int barLeft = left + 12;
        int barTop = top + 88;
        int barWidth = PANEL_WIDTH - 24;
        graphics.fill(barLeft, barTop, barLeft + barWidth, barTop + 8, 0xFF354353);
        graphics.fill(barLeft, barTop, barLeft + (barWidth * percent / 100), barTop + 8, 0xFF4B9AD8);
        graphics.drawString(font, Component.translatable("screen.scc.analysis.progress", percent),
                left + 12, top + 102, 0xFFB9C4D0);

        String detail = manager.message();
        if (detail == null || detail.isBlank()) detail = manager.detail();
        if (detail != null && !detail.isBlank()) {
            String safe = detail.length() > 72 ? detail.substring(0, 69) + "..." : detail;
            graphics.drawString(font, Component.literal(safe), left + 12, top + 120, 0xFFB9C4D0);
        }
        graphics.drawCenteredString(font, Component.translatable("screen.scc.analysis.controls"),
                width / 2, top + PANEL_HEIGHT - 16, 0xFF8FA2B4);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
