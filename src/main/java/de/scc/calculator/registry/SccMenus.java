package de.scc.calculator.registry;

import de.scc.calculator.network.SccTerminalMenu;
import de.scc.calculator.network.SccCraftingTerminalMenu;
import de.scc.calculator.network.SccStorageConnectorMenu;
import de.scc.calculator.network.SccRedstoneEmitterMenu;
import de.scc.calculator.network.SccAntennaMenu;
import de.scc.calculator.network.TerminalMenuOpenData;
import de.scc.calculator.platform.registry.PlatformRegistry;
import de.scc.calculator.platform.registry.SccHolder;
import net.minecraft.core.BlockPos;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;

/** Mensagens e menus reais do SCC, inclusive terminais remotos e antena. */
public final class SccMenus {
    public static final SccHolder<ExtendedScreenHandlerType<SccTerminalMenu, TerminalMenuOpenData>> TERMINAL =
            PlatformRegistry.registerExtendedMenu("terminal", SccTerminalMenu::new, TerminalMenuOpenData.STREAM_CODEC);
    public static final SccHolder<ExtendedScreenHandlerType<SccCraftingTerminalMenu, TerminalMenuOpenData>> CRAFTING_TERMINAL =
            PlatformRegistry.registerExtendedMenu("crafting_terminal", SccCraftingTerminalMenu::new, TerminalMenuOpenData.STREAM_CODEC);

    public static final SccHolder<ExtendedScreenHandlerType<SccRedstoneEmitterMenu, BlockPos>> REDSTONE_EMITTER =
            PlatformRegistry.registerBlockMenu("redstone_emitter", SccRedstoneEmitterMenu::new);
    public static final SccHolder<ExtendedScreenHandlerType<SccStorageConnectorMenu, BlockPos>> STORAGE_CONNECTOR =
            PlatformRegistry.registerBlockMenu("storage_connector", SccStorageConnectorMenu::new);
    public static final SccHolder<ExtendedScreenHandlerType<SccAntennaMenu, BlockPos>> ANTENNA =
            PlatformRegistry.registerBlockMenu("network_antenna", SccAntennaMenu::new);

    private SccMenus() { }

    public static void init() { }
}
