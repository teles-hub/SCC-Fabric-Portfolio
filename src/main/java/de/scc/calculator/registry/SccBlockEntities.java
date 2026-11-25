package de.scc.calculator.registry;

import de.scc.calculator.network.SccCableBlockEntity;
import de.scc.calculator.network.SccCableBusBlockEntity;
import de.scc.calculator.network.SccStorageConnectorBlockEntity;
import de.scc.calculator.network.SccRedstoneEmitterBlockEntity;
import de.scc.calculator.network.SccTerminalBlockEntity;
import de.scc.calculator.network.SccCraftingTerminalBlockEntity;
import de.scc.calculator.network.SccStorageBlockEntity;
import de.scc.calculator.network.SccWirelessReceiverBlockEntity;
import de.scc.calculator.network.SccWirelessTransmitterBlockEntity;
import de.scc.calculator.network.SccNetworkAntennaBlockEntity;
import de.scc.calculator.platform.registry.PlatformRegistry;
import de.scc.calculator.platform.registry.SccHolder;
import net.minecraft.world.level.block.entity.BlockEntityType;

/** Fabric BlockEntity types for local stores and network conduits. */
public final class SccBlockEntities {
    public static final SccHolder<BlockEntityType<SccStorageBlockEntity>> SCC_STORAGE =
            PlatformRegistry.registerBlockEntity(
                    "scc_storage",
                    SccStorageBlockEntity::new,
                    SccStorageBlocks.allBlocks().toArray(net.minecraft.world.level.block.Block[]::new));

    public static final SccHolder<BlockEntityType<SccCableBusBlockEntity>> SCC_CABLE_BUS =
            PlatformRegistry.registerBlockEntity("scc_cable_bus", SccCableBusBlockEntity::new,
                    SccNetworkBlocks.CABLE_BUS.get());

    public static final SccHolder<BlockEntityType<SccStorageConnectorBlockEntity>> SCC_STORAGE_CONNECTOR =
            PlatformRegistry.registerBlockEntity("scc_storage_connector", SccStorageConnectorBlockEntity::new,
                    SccNetworkBlocks.STORAGE_CONNECTOR.get());

    public static final SccHolder<BlockEntityType<SccRedstoneEmitterBlockEntity>> SCC_REDSTONE_EMITTER =
            PlatformRegistry.registerBlockEntity("scc_redstone_emitter", SccRedstoneEmitterBlockEntity::new,
                    SccNetworkBlocks.REDSTONE_EMITTER.get(), SccNetworkBlocks.WALL_REDSTONE_EMITTER.get());

    public static final SccHolder<BlockEntityType<SccCableBlockEntity>> SCC_CABLE =
            PlatformRegistry.registerBlockEntity(
                    "scc_cable",
                    SccCableBlockEntity::new,
                    SccNetworkBlocks.CABLE.get());

    public static final SccHolder<BlockEntityType<SccTerminalBlockEntity>> SCC_TERMINAL =
            PlatformRegistry.registerBlockEntity(
                    "scc_terminal",
                    SccTerminalBlockEntity::new,
                    SccNetworkBlocks.TERMINAL.get(), SccNetworkBlocks.WALL_TERMINAL.get());

    public static final SccHolder<BlockEntityType<SccCraftingTerminalBlockEntity>> SCC_CRAFTING_TERMINAL =
            PlatformRegistry.registerBlockEntity(
                    "scc_crafting_terminal",
                    SccCraftingTerminalBlockEntity::new,
                    SccNetworkBlocks.CRAFTING_TERMINAL.get(), SccNetworkBlocks.WALL_CRAFTING_TERMINAL.get());

    public static final SccHolder<BlockEntityType<SccWirelessTransmitterBlockEntity>> SCC_WIRELESS_TRANSMITTER =
            PlatformRegistry.registerBlockEntity(
                    "scc_wireless_transmitter",
                    SccWirelessTransmitterBlockEntity::new,
                    SccNetworkBlocks.WIRELESS_TRANSMITTER.get());

    public static final SccHolder<BlockEntityType<SccWirelessReceiverBlockEntity>> SCC_WIRELESS_RECEIVER =
            PlatformRegistry.registerBlockEntity(
                    "scc_wireless_receiver",
                    SccWirelessReceiverBlockEntity::new,
                    SccNetworkBlocks.WIRELESS_RECEIVER.get());

    public static final SccHolder<BlockEntityType<SccNetworkAntennaBlockEntity>> SCC_NETWORK_ANTENNA =
            PlatformRegistry.registerBlockEntity(
                    "scc_network_antenna",
                    SccNetworkAntennaBlockEntity::new,
                    SccNetworkBlocks.NETWORK_ANTENNA.get());

    private SccBlockEntities() { }

    public static void init() { /* triggers static registration */ }
}
