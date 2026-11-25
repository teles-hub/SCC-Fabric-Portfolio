package de.scc.calculator.registry;

import de.scc.calculator.network.SccCableBlock;
import de.scc.calculator.network.SccCableBusBlock;
import de.scc.calculator.network.SccStorageConnectorBlock;
import de.scc.calculator.network.SccRedstoneEmitterBlock;
import de.scc.calculator.network.SccWallRedstoneEmitterBlock;
import de.scc.calculator.network.SccTerminalBlock;
import de.scc.calculator.network.SccCraftingTerminalBlock;
import de.scc.calculator.network.SccWallTerminalBlock;
import de.scc.calculator.network.SccWallCraftingTerminalBlock;
import de.scc.calculator.network.SccWirelessReceiverBlock;
import de.scc.calculator.network.SccWirelessTransmitterBlock;
import de.scc.calculator.network.SccNetworkAntennaBlock;
import de.scc.calculator.platform.registry.PlatformRegistry;
import de.scc.calculator.platform.registry.SccHolder;
import net.minecraft.world.item.Item;

/** Registry for the non-combat network conduit block. */
public final class SccNetworkBlocks {
    public static final SccHolder<SccCableBusBlock> CABLE_BUS = PlatformRegistry.registerBlockWithItem(
            "cable_bus", () -> new SccCableBusBlock(SccCableBusBlock.deviceProperties()), new Item.Properties());
    public static final SccHolder<SccStorageConnectorBlock> STORAGE_CONNECTOR = PlatformRegistry.registerBlockWithItem(
            "storage_connector", () -> new SccStorageConnectorBlock(SccStorageConnectorBlock.deviceProperties()), new Item.Properties());
    public static final SccHolder<SccRedstoneEmitterBlock> REDSTONE_EMITTER = PlatformRegistry.registerBlockWithItem(
            "redstone_emitter", () -> new SccRedstoneEmitterBlock(SccRedstoneEmitterBlock.deviceProperties()), new Item.Properties());
    public static final SccHolder<SccWallRedstoneEmitterBlock> WALL_REDSTONE_EMITTER = PlatformRegistry.registerBlockWithItem(
            "wall_redstone_emitter", () -> new SccWallRedstoneEmitterBlock(SccRedstoneEmitterBlock.deviceProperties()), new Item.Properties());

    public static final SccHolder<SccCableBlock> CABLE = PlatformRegistry.registerBlockWithItem(
            "network_cable", () -> new SccCableBlock(SccCableBlock.deviceProperties()), new Item.Properties());
    public static final SccHolder<SccTerminalBlock> TERMINAL = PlatformRegistry.registerBlockWithItem(
            "terminal", () -> new SccTerminalBlock(SccTerminalBlock.deviceProperties()), new Item.Properties());
    public static final SccHolder<SccCraftingTerminalBlock> CRAFTING_TERMINAL = PlatformRegistry.registerBlockWithItem(
            "crafting_terminal", () -> new SccCraftingTerminalBlock(SccCraftingTerminalBlock.deviceProperties()), new Item.Properties());
    public static final SccHolder<SccWallTerminalBlock> WALL_TERMINAL = PlatformRegistry.registerBlockWithItem(
            "wall_terminal", () -> new SccWallTerminalBlock(SccWallTerminalBlock.deviceProperties()), new Item.Properties());
    public static final SccHolder<SccWallCraftingTerminalBlock> WALL_CRAFTING_TERMINAL = PlatformRegistry.registerBlockWithItem(
            "wall_crafting_terminal", () -> new SccWallCraftingTerminalBlock(SccWallCraftingTerminalBlock.deviceProperties()), new Item.Properties());

    public static final SccHolder<SccWirelessTransmitterBlock> WIRELESS_TRANSMITTER = PlatformRegistry.registerBlockWithItem(
            "wireless_transmitter", () -> new SccWirelessTransmitterBlock(SccWirelessTransmitterBlock.deviceProperties()), new Item.Properties());
    public static final SccHolder<SccWirelessReceiverBlock> WIRELESS_RECEIVER = PlatformRegistry.registerBlockWithItem(
            "wireless_receiver", () -> new SccWirelessReceiverBlock(SccWirelessReceiverBlock.deviceProperties()), new Item.Properties());

    public static final SccHolder<SccNetworkAntennaBlock> NETWORK_ANTENNA = PlatformRegistry.registerBlockWithItem(
            "network_antenna", () -> new SccNetworkAntennaBlock(SccNetworkAntennaBlock.deviceProperties()), new Item.Properties());

    private SccNetworkBlocks() { }

    public static void init() { /* triggers static registration */ }
}
