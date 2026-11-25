package de.scc.calculator.registry;

import de.scc.calculator.network.SubstanceItem;
import de.scc.calculator.network.SccFacadeItem;
import de.scc.calculator.network.WirelessTerminalItem;
import de.scc.calculator.platform.registry.PlatformRegistry;
import de.scc.calculator.platform.registry.SccHolder;
import net.minecraft.world.item.Item;

/** Itens registrados do núcleo SCC: storage, rede, fachada e terminais remotos. */
public final class SccItems {
    public static final SccHolder<SccFacadeItem> FACADE = PlatformRegistry.registerItem(
            "facade", () -> new SccFacadeItem(new Item.Properties().stacksTo(64)));

    public static final SccHolder<SubstanceItem> SUBSTANCE = PlatformRegistry.registerItem(
            "substance", () -> new SubstanceItem(new Item.Properties().stacksTo(1)));

    public static final SccHolder<Item> RANGE_UPGRADE = PlatformRegistry.registerItem(
            "range_upgrade", () -> new Item(new Item.Properties()));

    public static final SccHolder<Item> INFINITE_RANGE_UPGRADE = PlatformRegistry.registerItem(
            "infinite_range_upgrade", () -> new Item(new Item.Properties()));

    public static final SccHolder<Item> DIMENSIONAL_UPGRADE = PlatformRegistry.registerItem(
            "dimensional_upgrade", () -> new Item(new Item.Properties()));

    public static final SccHolder<WirelessTerminalItem> WIRELESS_TERMINAL = PlatformRegistry.registerItem(
            "wireless_terminal", () -> new WirelessTerminalItem(new Item.Properties(), false));

    public static final SccHolder<WirelessTerminalItem> WIRELESS_CRAFTING_TERMINAL = PlatformRegistry.registerItem(
            "wireless_crafting_terminal", () -> new WirelessTerminalItem(new Item.Properties(), true));

    private SccItems() { }

    public static void init() { }
}
