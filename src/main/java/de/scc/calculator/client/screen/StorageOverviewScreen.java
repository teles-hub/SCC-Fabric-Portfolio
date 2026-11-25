package de.scc.calculator.client.screen;

import de.scc.calculator.network.payload.StorageOverviewPayload;
import de.scc.calculator.network.payload.NetworkActionResultPayload;
import de.scc.calculator.client.PlatformNetworkClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Lightweight read-only interface for the currently implemented storage blocks. */
public final class StorageOverviewScreen extends Screen {
    private static final int PANEL_WIDTH = 264;
    private static final int PANEL_HEIGHT = 236;

    private final BlockPos pos;
    private StorageOverviewPayload data;
    private String error;
    private int refreshTicks;
    private int noticeTicks;
    private int selectedItemIndex;
    private int requestedPage;
    private Component notice;

    public StorageOverviewScreen(BlockPos pos) {
        super(Component.translatable("screen.scc.storage_overview"));
        this.pos = pos.immutable();
    }

    public static void open(BlockPos pos) {
        Minecraft.getInstance().setScreen(new StorageOverviewScreen(pos));
    }

    public static void accept(StorageOverviewPayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof StorageOverviewScreen screen && screen.pos.equals(payload.pos())) {
            screen.data = payload;
            screen.requestedPage = payload.pageIndex();
            screen.error = null;
        }
    }

    public static void acceptActionResult(NetworkActionResultPayload payload) {
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof StorageOverviewScreen screen && screen.pos.equals(payload.pos())) {
            screen.notice = Component.translatable("screen.scc.network.deposit." + payload.status(), payload.amount());
            screen.noticeTicks = 100;
        }
    }

    public static void reject(BlockPos pos, String reason) {
        Minecraft client = Minecraft.getInstance();
        if (client.screen instanceof StorageOverviewScreen screen && screen.pos.equals(pos)) {
            screen.error = reason;
        }
    }

    @Override
    protected void init() {
        super.init();
    }

    @Override
    public void tick() {
        super.tick();
        if (noticeTicks > 0 && --noticeTicks == 0) notice = null;
        if (Minecraft.getInstance().player == null) {
            onClose();
            return;
        }
        if (++refreshTicks >= 20) {
            refreshTicks = 0;
            PlatformNetworkClient.requestStorageOverview(pos, requestedPage);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_R) {
            error = null;
            data = null;
            refreshTicks = 0;
            PlatformNetworkClient.requestStorageOverview(pos, requestedPage);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_I && data != null && data.available()
                && "network".equals(data.family())) {
            PlatformNetworkClient.depositHeldItem(pos, requestedPage);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_U && data != null && data.available()
                && "network".equals(data.family())) {
            PlatformNetworkClient.interactFluidContainer(pos, requestedPage);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_G && data != null && data.available()
                && "network".equals(data.family())) {
            PlatformNetworkClient.interactEnergyContainer(pos, requestedPage);
            return true;
        }
        if (keyCode >= GLFW.GLFW_KEY_1 && keyCode <= GLFW.GLFW_KEY_8 && data != null) {
            int requested = keyCode - GLFW.GLFW_KEY_1;
            long availableItems = data.resources().stream().filter(resource -> "items".equals(resource.unit())
                    && !resource.variantKey().isBlank()).count();
            if (requested < availableItems) {
                selectedItemIndex = requested;
                notice = Component.translatable("screen.scc.network.selected_item", requested + 1);
                noticeTicks = 60;
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_O && data != null && data.available()
                && "network".equals(data.family())) {
            var selected = data.resources().stream().filter(resource -> "items".equals(resource.unit())
                    && !resource.variantKey().isBlank()).skip(selectedItemIndex).findFirst().orElse(null);
            if (selected != null) PlatformNetworkClient.withdrawItem(pos, selected.variantKey(), requestedPage);
            else {
                notice = Component.translatable("screen.scc.network.no_item_selected");
                noticeTicks = 60;
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_DOWN && data != null && data.pageIndex() + 1 < data.pageCount()) {
            requestedPage = data.pageIndex() + 1;
            selectedItemIndex = 0;
            data = null;
            PlatformNetworkClient.requestStorageOverview(pos, requestedPage);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_PAGE_UP && data != null && data.pageIndex() > 0) {
            requestedPage = data.pageIndex() - 1;
            selectedItemIndex = 0;
            data = null;
            PlatformNetworkClient.requestStorageOverview(pos, requestedPage);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_P && data != null && data.available()) {
            StringBuilder clipboard = new StringBuilder("SCC Network\n")
                    .append("Position: ").append(pos.getX()).append(", ").append(pos.getY()).append(", ").append(pos.getZ()).append('\n')
                    .append("Nodes: ").append(data.nodeCount()).append("; cables: ").append(data.cableCount())
                    .append("; storages: ").append(data.storageCount())
                    .append("; terminals: ").append(data.terminalCount()).append('\n')
                    .append("Truncated: ").append(data.truncated()).append('\n');
            for (var resource : data.resources()) {
                clipboard.append(resource.id()).append(" x ").append(resource.amount()).append(' ').append(resource.unit()).append('\n');
            }
            Minecraft.getInstance().keyboardHandler.setClipboard(clipboard.toString());
            notice = Component.translatable("screen.scc.storage_overview.copied");
            noticeTicks = 60;
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
        graphics.drawString(font, Component.translatable("screen.scc.storage_overview.position",
                pos.getX(), pos.getY(), pos.getZ()), left + 12, top + 32, 0xFFB9C4D0);

        if (data == null) {
            Component status = error == null
                    ? Component.translatable("screen.scc.storage_overview.waiting")
                    : Component.translatable("screen.scc.storage_overview.unavailable", error);
            graphics.drawCenteredString(font, status, width / 2, top + 66, 0xFFFFD27A);
        } else if (!data.available()) {
            graphics.drawCenteredString(font, Component.translatable("screen.scc.storage_overview.not_storage"),
                    width / 2, top + 66, 0xFFFF8B80);
        } else {
            if ("network".equals(data.family())) {
                graphics.drawString(font, Component.translatable("screen.scc.network.topology",
                                data.nodeCount(), data.cableCount(), data.storageCount(), data.terminalCount()),
                        left + 12, top + 50, 0xFFFFFFFF);
                graphics.drawString(font, data.truncated()
                                ? Component.translatable("screen.scc.network.truncated")
                                : Component.translatable("screen.scc.network.complete"),
                        left + 12, top + 64, data.truncated() ? 0xFFFFD27A : 0xFFB9C4D0);
                graphics.drawString(font, Component.translatable("screen.scc.network.page", data.pageIndex() + 1, data.pageCount()),
                        left + 12, top + 78, 0xFFB9C4D0);
            } else {
                graphics.drawString(font, Component.translatable("screen.scc.storage_overview.family", data.family()),
                        left + 12, top + 50, 0xFFFFFFFF);
                graphics.drawString(font, Component.translatable("screen.scc.storage_overview.tier", data.tier()),
                        left + 12, top + 64, 0xFFFFFFFF);
            }
            graphics.drawString(font, data.empty()
                    ? Component.translatable("screen.scc.storage_overview.empty")
                    : Component.translatable("screen.scc.storage_overview.non_empty"),
                    left + 12, top + 92, 0xFFB9C4D0);
            if (!data.resources().isEmpty()) {
                graphics.drawString(font, Component.translatable("screen.scc.storage_overview.resources"),
                        left + 12, top + 108, 0xFF8FC7F2);
                int line = 0;
                int itemIndex = 0;
                for (var resource : data.resources()) {
                    if (line >= 8) break;
                    boolean item = "items".equals(resource.unit());
                    String prefix = item ? ((itemIndex == selectedItemIndex ? ">" : " ") + (itemIndex + 1) + ". ") : "   ";
                    graphics.drawString(font, Component.literal(prefix + resource.id() + "  × " + resource.amount() + " " + resource.unit()),
                            left + 16, top + 122 + line * 10, item && itemIndex == selectedItemIndex ? 0xFF8FC7F2 : 0xFFE2E8EF);
                    if (item) itemIndex++;
                    line++;
                }
                if (data.resources().size() > 8) {
                    graphics.drawString(font, Component.translatable("screen.scc.storage_overview.more_items",
                            data.resources().size() - 8), left + 16, top + 190, 0xFF8FA2B4);
                }
            }
        }
        if (notice != null) {
            graphics.drawCenteredString(font, notice, width / 2, top + PANEL_HEIGHT - 30, 0xFF8FC7F2);
        }
        graphics.drawCenteredString(font, Component.translatable("screen.scc.storage_overview.controls"),
                width / 2, top + PANEL_HEIGHT - 14, 0xFF8FA2B4);
        super.render(graphics, mouseX, mouseY, partialTick);
    }
}
