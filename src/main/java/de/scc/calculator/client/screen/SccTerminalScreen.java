package de.scc.calculator.client.screen;

import de.scc.calculator.client.PlatformNetworkClient;
import de.scc.calculator.network.SccTerminalMenu;
import de.scc.calculator.network.payload.NetworkActionResultPayload;
import de.scc.calculator.network.payload.StorageOverviewPayload;
import de.scc.calculator.network.payload.OrderBookSyncPayload;
import de.scc.calculator.network.payload.CraftJobSyncPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;

/**
 * Click-open terminal menu. It combines the regular player inventory with the
 * server-authoritative network overview and the already implemented transfer actions.
 */
public final class SccTerminalScreen extends AbstractContainerScreen<SccTerminalMenu> {
    private StorageOverviewPayload overview;
    private Component notice;
    private int noticeTicks;
    private int refreshTicks;
    private int requestedPage;
    private java.util.List<OrderBookSyncPayload.Summary> orderLists = java.util.List.of();
    private int orderListPage;
    private boolean orderBookAvailable;
    private Component orderNotice;
    private java.util.List<CraftJobSyncPayload.Summary> craftJobs = java.util.List.of();
    private boolean craftJobsAvailable;
    private Component jobNotice;

    public SccTerminalScreen(SccTerminalMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 240;
        imageHeight = 292;
        inventoryLabelX = 39;
        inventoryLabelY = 100;
        titleLabelX = 9;
        titleLabelY = 6;
    }

    @Override
    protected void init() {
        super.init();
        int x = leftPos + 5;
        int y = topPos + 23;
        addRenderableWidget(Button.builder(Component.translatable("screen.scc.terminal.refresh_short"), b -> refresh())
                .bounds(x, y, 31, 18).build());
        x += 33;
        addRenderableWidget(Button.builder(Component.translatable("screen.scc.terminal.deposit_short"), b -> {
            if (overview != null && overview.available()) PlatformNetworkClient.depositHeldItem(menu.pos(), requestedPage);
        }).bounds(x, y, 36, 18).build());
        x += 38;
        addRenderableWidget(Button.builder(Component.translatable("screen.scc.terminal.fluid_short"), b -> {
            if (overview != null && overview.available()) PlatformNetworkClient.interactFluidContainer(menu.pos(), requestedPage);
        }).bounds(x, y, 31, 18).build());
        x += 33;
        addRenderableWidget(Button.builder(Component.translatable("screen.scc.terminal.energy_short"), b -> {
            if (overview != null && overview.available()) PlatformNetworkClient.interactEnergyContainer(menu.pos(), requestedPage);
        }).bounds(x, y, 31, 18).build());
        x += 33;
        addRenderableWidget(Button.builder(Component.translatable("screen.scc.terminal.copy_short"), b -> copyOverview())
                .bounds(x, y, 31, 18).build());
        x += 33;
        addRenderableWidget(Button.builder(Component.literal("<"), b -> changePage(-1))
                .bounds(x, y, 22, 18).build());
        x += 24;
        addRenderableWidget(Button.builder(Component.literal(">"), b -> changePage(1))
                .bounds(x, y, 22, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.scc.terminal.orders_short"), b -> {
            orderBookAvailable = false;
            PlatformNetworkClient.requestOrderBook(menu.pos());
        }).bounds(leftPos + 8, topPos + 190, 48, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.scc.terminal.save_order_short"), b -> {
            if (PlatformNetworkClient.saveHeldItemAsOrderList(menu.pos())) {
                orderNotice = Component.translatable("screen.scc.terminal.order_save_sent");
            } else {
                orderNotice = Component.translatable("screen.scc.terminal.order_save_empty");
            }
        }).bounds(leftPos + 60, topPos + 190, 48, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.scc.terminal.plan_short"), b -> {
            if (PlatformNetworkClient.planHeldItem(menu.pos())) {
                jobNotice = Component.translatable("screen.scc.terminal.job_plan_sent");
            } else {
                jobNotice = Component.translatable("screen.scc.terminal.job_plan_empty");
            }
        }).bounds(leftPos + 112, topPos + 190, 48, 18).build());
        addRenderableWidget(Button.builder(Component.translatable("screen.scc.terminal.jobs_short"), b -> {
            craftJobsAvailable = false;
            PlatformNetworkClient.requestCraftJobs(menu.pos());
        }).bounds(leftPos + 164, topPos + 190, 48, 18).build());
        refresh();
        PlatformNetworkClient.requestOrderBook(menu.pos());
        PlatformNetworkClient.requestCraftJobs(menu.pos());
    }

    public static void accept(StorageOverviewPayload payload) {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof SccTerminalScreen screen
                && screen.menu.pos().equals(payload.pos())) {
            screen.overview = payload;
            screen.requestedPage = payload.pageIndex();
        }
    }

    public static void acceptOrderBook(OrderBookSyncPayload payload) {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof SccTerminalScreen screen
                && screen.menu.pos().equals(payload.pos())) {
            screen.orderBookAvailable = payload.available();
            screen.orderLists = payload.lists();
            int pages = Math.max(1, (screen.orderLists.size() + 1) / 2);
            if (screen.orderListPage >= pages) screen.orderListPage = 0;
            if (!payload.available()) screen.orderNotice = Component.translatable("screen.scc.terminal.order_book_unavailable");
            else if (payload.lists().isEmpty()) screen.orderNotice = Component.translatable("screen.scc.terminal.order_book_empty");
            else screen.orderNotice = null;
        }
    }

    public static void acceptCraftJobs(CraftJobSyncPayload payload) {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof SccTerminalScreen screen
                && screen.menu.pos().equals(payload.pos())) {
            screen.craftJobsAvailable = payload.available();
            screen.craftJobs = payload.jobs();
            if (!payload.available()) screen.jobNotice = Component.translatable("screen.scc.terminal.jobs_unavailable");
            else if (payload.jobs().isEmpty()) screen.jobNotice = Component.translatable("screen.scc.terminal.jobs_empty");
            else screen.jobNotice = null;
        }
    }

    public static void acceptActionResult(NetworkActionResultPayload payload) {
        if (net.minecraft.client.Minecraft.getInstance().screen instanceof SccTerminalScreen screen
                && screen.menu.pos().equals(payload.pos())) {
            String translationPrefix = payload.status().startsWith("order_") || payload.status().startsWith("job_")
                    ? "screen.scc.terminal." : "screen.scc.network.deposit.";
            screen.notice = Component.translatable(translationPrefix + payload.status(), payload.amount());
            screen.noticeTicks = 100;
            if (payload.status().startsWith("order_")) {
                screen.orderNotice = Component.translatable("screen.scc.terminal." + payload.status());
                if ("order_saved".equals(payload.status()) || "order_removed".equals(payload.status())) {
                    PlatformNetworkClient.requestOrderBook(payload.pos());
                }
            } else if (payload.status().startsWith("job_")) {
                screen.jobNotice = Component.translatable("screen.scc.terminal." + payload.status());
                if ("job_created".equals(payload.status()) || "job_cancelled".equals(payload.status())) {
                    PlatformNetworkClient.requestCraftJobs(payload.pos());
                }
            }
        }
    }

    private void refresh() {
        overview = null;
        PlatformNetworkClient.requestStorageOverview(menu.pos(), requestedPage);
    }

    private void changePage(int delta) {
        if (overview == null) return;
        int next = overview.pageIndex() + delta;
        if (next < 0 || next >= overview.pageCount()) return;
        requestedPage = next;
        refresh();
    }

    /** Retira uma unidade do recurso exibido na linha selecionada da visão geral. */
    private void withdraw(StorageOverviewPayload.ResourceSummary resource) {
        if (overview == null || !overview.available() || resource == null) return;
        PlatformNetworkClient.withdrawItem(menu.pos(), resource.variantKey(), requestedPage);
    }

    private void copyOverview() {
        if (overview == null || !overview.available()) return;
        StringBuilder text = new StringBuilder("SCC Network Terminal\n")
                .append("Pos: ").append(menu.pos().getX()).append(',').append(menu.pos().getY()).append(',').append(menu.pos().getZ()).append('\n')
                .append("Nodes: ").append(overview.nodeCount()).append("; cables: ").append(overview.cableCount())
                .append("; stores: ").append(overview.storageCount()).append("; terminals: ").append(overview.terminalCount()).append('\n')
                .append("Page: ").append(overview.pageIndex() + 1).append('/').append(overview.pageCount()).append('\n');
        for (StorageOverviewPayload.ResourceSummary resource : overview.resources()) {
            text.append(resource.id()).append(" x ").append(resource.amount()).append(' ').append(resource.unit()).append('\n');
        }
        net.minecraft.client.Minecraft.getInstance().keyboardHandler.setClipboard(text.toString());
        notice = Component.translatable("screen.scc.storage_overview.copied");
        noticeTicks = 60;
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (noticeTicks > 0 && --noticeTicks == 0) notice = null;
        if (net.minecraft.client.Minecraft.getInstance().player == null) {
            onClose();
            return;
        }
        if (++refreshTicks >= 20) {
            refreshTicks = 0;
            PlatformNetworkClient.requestStorageOverview(menu.pos(), requestedPage);
        }
        if (refreshTicks == 0 && net.minecraft.client.Minecraft.getInstance().level != null
                && net.minecraft.client.Minecraft.getInstance().level.getGameTime() % 100L == 0L) {
            PlatformNetworkClient.requestCraftJobs(menu.pos());
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_O && overview != null
                && overview.available() && !overview.resources().isEmpty()) {
            withdraw(overview.resources().get(0));
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && overview != null && overview.available()
                && !orderBookAvailable && !craftJobsAvailable) {
            int rowStart = topPos + 70;
            int row = (int) ((mouseY - rowStart) / 10.0D);
            if (row >= 0 && row < 3 && mouseX >= leftPos + 9 && mouseX <= leftPos + 218
                    && row < overview.resources().size()) {
                withdraw(overview.resources().get(row));
                return true;
            }
        }
        if (button == 0 && orderBookAvailable) {
            int rowStart = topPos + 222;
            int row = (int) ((mouseY - rowStart) / 9.0D);
            int first = orderListPage * 2;
            int shown = Math.min(2, Math.max(0, orderLists.size() - first));
            if (row == 0 && orderLists.size() > 2 && mouseX >= leftPos + 195
                    && mouseX <= leftPos + 220 && mouseY >= rowStart && mouseY < rowStart + 9) {
                int pages = (orderLists.size() + 1) / 2;
                orderListPage = (orderListPage + 1) % pages;
                return true;
            }
            if (row >= 0 && row < shown && mouseY >= rowStart && mouseY < rowStart + shown * 9
                    && mouseX >= leftPos + 8 && mouseX <= leftPos + 218) {
                OrderBookSyncPayload.Summary order = orderLists.get(first + row);
                PlatformNetworkClient.planOrderList(menu.pos(), order.id());
                orderNotice = Component.translatable("screen.scc.terminal.order_plan_sent", order.name());
                return true;
            }
        }
        if (button == 0 && craftJobsAvailable) {
            int rowStart = topPos + 252;
            int row = (int) ((mouseY - rowStart) / 9.0D);
            if (row >= 0 && row < Math.min(3, craftJobs.size()) && mouseX >= leftPos + 8
                    && mouseX <= leftPos + 218) {
                CraftJobSyncPayload.Summary job = craftJobs.get(row);
                if (job.canCancel()) {
                    PlatformNetworkClient.cancelCraftJob(menu.pos(), job.id());
                    jobNotice = Component.translatable("screen.scc.terminal.job_cancel_sent");
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + imageHeight, 0xE51A1D24);
        graphics.fill(leftPos, topPos, leftPos + imageWidth, topPos + 2, 0xFF4B9AD8);
        graphics.fill(leftPos + 34, topPos + 107, leftPos + 206, topPos + 163, 0xFF101318);
        graphics.fill(leftPos + 34, topPos + 165, leftPos + 206, topPos + 185, 0xFF101318);
        graphics.fill(leftPos + 4, topPos + 45, leftPos + imageWidth - 4, topPos + 105, 0x66333C48);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        graphics.drawString(font, title, titleLabelX, titleLabelY, 0xFFFFFFFF, false);
        if (overview == null) {
            graphics.drawString(font, Component.translatable("screen.scc.storage_overview.waiting"), 9, 48, 0xFFFFD27A, false);
        } else if (!overview.available()) {
            graphics.drawString(font, Component.translatable("screen.scc.storage_overview.not_storage"), 9, 48, 0xFFFF8B80, false);
        } else {
            graphics.drawString(font, Component.translatable("screen.scc.network.topology",
                    overview.nodeCount(), overview.cableCount(), overview.storageCount(), overview.terminalCount()),
                    9, 46, 0xFFFFFFFF, false);
            graphics.drawString(font, Component.translatable("screen.scc.network.page",
                    overview.pageIndex() + 1, overview.pageCount()), 9, 57, 0xFFB9C4D0, false);
            if (overview.truncated()) {
                graphics.drawString(font, Component.translatable("screen.scc.network.truncated"), 110, 57, 0xFFFFD27A, false);
            }
            int line = 0;
            for (StorageOverviewPayload.ResourceSummary resource : overview.resources()) {
                if (line >= 3) break;
                String label = resource.id() + " x " + resource.amount() + " " + resource.unit();
                if (label.length() > 35) label = label.substring(0, 32) + "...";
                graphics.drawString(font, label, 9, 70 + line * 10, 0xFFE2E8EF, false);
                line++;
            }
        }
        if (notice != null) graphics.drawString(font, notice, 9, 92, 0xFF8FC7F2, false);
        if (orderNotice != null) graphics.drawString(font, orderNotice, 9, 212, 0xFF8FC7F2, false);
        if (orderBookAvailable) {
            int first = orderListPage * 2;
            int shown = Math.min(2, Math.max(0, orderLists.size() - first));
            for (int i = 0; i < shown; i++) {
                OrderBookSyncPayload.Summary summary = orderLists.get(first + i);
                String label = "> " + summary.id() + " · " + summary.name() + " (" + summary.lineCount() + ")";
                if (label.length() > 34) label = label.substring(0, 31) + "...";
                graphics.drawString(font, label, 9, 222 + i * 9, 0xFF8FC7F2, false);
            }
            if (orderLists.size() > 2) {
                int remaining = orderLists.size() - (first + shown);
                String pageLabel = remaining > 0 ? "+" + remaining : "↻";
                graphics.drawString(font, Component.literal(pageLabel), 205, 222, 0xFFB9C4D0, false);
            }
        }
        if (jobNotice != null) graphics.drawString(font, jobNotice, 9, 242, 0xFF8FC7F2, false);
        if (craftJobsAvailable) {
            int shown = Math.min(3, craftJobs.size());
            for (int i = 0; i < shown; i++) {
                CraftJobSyncPayload.Summary job = craftJobs.get(i);
                String label = "#" + job.id() + " " + job.display() + " x" + job.amount()
                        + " · " + job.status() + " " + job.completedSteps() + "/" + job.stepCount();
                if (job.canCancel()) label += " [x]";
                if (label.length() > 34) label = label.substring(0, 31) + "...";
                graphics.drawString(font, label, 9, 252 + i * 9, job.canCancel() ? 0xFFFFD27A : 0xFFB9C4D0, false);
            }
            if (craftJobs.size() > shown) {
                graphics.drawString(font, Component.literal("+" + (craftJobs.size() - shown)), 205, 252, 0xFFB9C4D0, false);
            }
        }
        graphics.drawString(font, Component.translatable("container.inventory"), inventoryLabelX, inventoryLabelY, 0xFFE2E8EF, false);
    }
}
