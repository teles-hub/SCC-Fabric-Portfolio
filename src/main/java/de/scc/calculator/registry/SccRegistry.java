package de.scc.calculator.registry;

import de.scc.calculator.SuperCoolCalculator;
import de.scc.calculator.platform.PlatformCapabilities;
import de.scc.calculator.network.SccStorageBlockEntity;
import de.scc.calculator.network.SccCableBlockEntity;
import de.scc.calculator.network.SccCableBusBlockEntity;
import de.scc.calculator.network.SccStorageConnectorBlockEntity;
import de.scc.calculator.network.SccTerminalBlockEntity;
import de.scc.calculator.network.SccWirelessReceiverBlockEntity;
import de.scc.calculator.network.SccWirelessTransmitterBlockEntity;
import de.scc.calculator.network.SccNetworkAntennaBlockEntity;
import de.scc.calculator.network.SccNetworkCache;
import de.scc.calculator.network.SccNetworkView;
import de.scc.calculator.network.SccStorageBlock;
import de.scc.calculator.network.FabricSccStorageBlock;
import de.scc.calculator.network.StorageTier;
import de.scc.calculator.platform.registry.PlatformRegistry;
import de.scc.calculator.platform.registry.SccHolder;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.resources.ResourceLocation;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orquestrador dos registros portados do núcleo não-combativo do SCC.
 *
 * <p>Inclui armazenamento, cabos, Cable Bus, conectores, emissores redstone,
 * terminais com fio, downlinks/upinks sem fio, antena de rede, terminais remotos
 * e as APIs de transferência de item, fluido e energia. Não cria IDs fictícios.</p>
 */
public final class SccRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("SCC-Registry");

    public static final SccHolder<CreativeModeTab> MAIN_TAB = PlatformRegistry.registerCreativeTab(
            "main",
            () -> {
                var items = PlatformRegistry.getRegisteredItems();
                return items.isEmpty() ? new ItemStack(Items.COMPARATOR) : new ItemStack(items.getFirst().get());
            },
            (parameters, output) -> {
                for (var itemHolder : PlatformRegistry.getRegisteredItems()) {
                    // Substance is a synthetic recipe-graph token for fluids, not a player-facing item.
                    if (itemHolder.get() != SccItems.SUBSTANCE.get()) output.accept(itemHolder.get());
                }
            });

    private static volatile boolean registered;

    private SccRegistry() {
    }

    public static synchronized void registerAll() {
        if (registered) {
            LOGGER.warn("SCC registry initialization was requested more than once; duplicate registration skipped.");
            return;
        }

        LOGGER.info("Registrando Data Components, itens, blocos de rede e BlockEntities do SCC...");
        SccDataComponents.init();
        SccItems.init();
        SccStorageBlocks.init();
        SccNetworkBlocks.init();
        SccBlockEntities.init();
        SccMenus.init();
        SccSounds.init();
        validateRegisteredState();
        registerBlockEntityCapabilities();
        registered = true;
        LOGGER.info("Registros ativos do SCC: {} blocos e BlockItems de armazenamento, {} itens totais, " +
                        "{} BlockEntities de rede/armazenamento, {} sons, 4 Data Components e Transfer APIs de item/fluidos/energia.",
                SccStorageBlocks.allBlocks().size(), PlatformRegistry.getRegisteredItems().size(),
                summary().blockEntityTypes(), SccSounds.registeredCount());
    }

    public static boolean isRegistered() {
        return registered;
    }

    /** Snapshot público para diagnóstico e relatórios de compatibilidade. */
    public static RegistrationSummary summary() {
        return new RegistrationSummary(
                registered,
                SccStorageBlocks.allBlocks().size(),
                PlatformRegistry.getRegisteredItems().size(),
                (SccBlockEntities.SCC_STORAGE.isBound() ? 1 : 0)
                        + (SccBlockEntities.SCC_CABLE_BUS.isBound() ? 1 : 0)
                        + (SccBlockEntities.SCC_STORAGE_CONNECTOR.isBound() ? 1 : 0)
                        + (SccBlockEntities.SCC_REDSTONE_EMITTER.isBound() ? 1 : 0)
                        + (SccBlockEntities.SCC_CABLE.isBound() ? 1 : 0)
                        + (SccBlockEntities.SCC_TERMINAL.isBound() ? 1 : 0)
                        + (SccBlockEntities.SCC_CRAFTING_TERMINAL.isBound() ? 1 : 0)
                        + (SccBlockEntities.SCC_WIRELESS_TRANSMITTER.isBound() ? 1 : 0)
                        + (SccBlockEntities.SCC_WIRELESS_RECEIVER.isBound() ? 1 : 0)
                        + (SccBlockEntities.SCC_NETWORK_ANTENNA.isBound() ? 1 : 0),
                SccDataComponents.registeredCount(),
                SccSounds.registeredCount());
    }

    public record RegistrationSummary(
            boolean initialized,
            int storageBlocks,
            int registeredItems,
            int blockEntityTypes,
            int dataComponents,
            int soundEvents) { }

    /**
     * Falha cedo se uma alteração futura deixar o registro parcialmente montado.
     * Os números refletem o conjunto implementado, não todo o catálogo do JAR original.
     */
    private static void validateRegisteredState() {
        validateTierMap(SccStorageBlocks.ITEM_STORAGES, SccStorageBlock.Kind.ITEM);
        validateTierMap(SccStorageBlocks.FLUID_STORAGES, SccStorageBlock.Kind.FLUID);
        validateTierMap(SccStorageBlocks.ENERGY_STORAGES, SccStorageBlock.Kind.ENERGY);
        validateTierMap(SccStorageBlocks.CHEMICAL_STORAGES, SccStorageBlock.Kind.CHEMICAL);
        validateTierMap(SccStorageBlocks.SOURCE_STORAGES, SccStorageBlock.Kind.SOURCE);
        validateTierMap(SccStorageBlocks.SOUL_STORAGES, SccStorageBlock.Kind.SOUL);
        if (SccStorageBlocks.allBlocks().size() != 36) {
            throw new IllegalStateException("SCC storage registry invariant failed: expected 36 unique storage blocks");
        }
        var registeredItems = PlatformRegistry.getRegisteredItems();
        if (registeredItems.size() != 55) {
            throw new IllegalStateException("SCC item registry invariant failed: expected 36 storage BlockItems + 12 network BlockItems + Substance + Facade + 3 wireless upgrades + 2 wireless terminals");
        }
        Set<ResourceLocation> itemIds = new HashSet<>();
        for (var item : registeredItems) {
            if (!item.isBound() || !itemIds.add(item.getId())) {
                throw new IllegalStateException("SCC item registry contains an unbound or duplicate item: " + item.getId());
            }
        }
        if (!SccItems.SUBSTANCE.isBound() || !SccItems.FACADE.isBound()
                || !SccItems.WIRELESS_TERMINAL.isBound() || !SccItems.WIRELESS_CRAFTING_TERMINAL.isBound()
                || !SccBlockEntities.SCC_CABLE_BUS.isBound() || !SccBlockEntities.SCC_STORAGE_CONNECTOR.isBound()
                || !SccBlockEntities.SCC_REDSTONE_EMITTER.isBound() || !SccBlockEntities.SCC_STORAGE.isBound()
                || !SccBlockEntities.SCC_CABLE.isBound() || !SccBlockEntities.SCC_TERMINAL.isBound()
                || !SccBlockEntities.SCC_CRAFTING_TERMINAL.isBound()
                || !SccBlockEntities.SCC_WIRELESS_TRANSMITTER.isBound() || !SccBlockEntities.SCC_WIRELESS_RECEIVER.isBound()
                || !SccBlockEntities.SCC_NETWORK_ANTENNA.isBound()
                || !SccNetworkBlocks.CABLE_BUS.isBound() || !SccNetworkBlocks.STORAGE_CONNECTOR.isBound()
                || !SccNetworkBlocks.REDSTONE_EMITTER.isBound() || !SccNetworkBlocks.WALL_REDSTONE_EMITTER.isBound()
                || !SccNetworkBlocks.CABLE.isBound() || !SccNetworkBlocks.TERMINAL.isBound()
                || !SccNetworkBlocks.CRAFTING_TERMINAL.isBound()
                || !SccNetworkBlocks.WALL_TERMINAL.isBound() || !SccNetworkBlocks.WALL_CRAFTING_TERMINAL.isBound()
                || !SccNetworkBlocks.WIRELESS_TRANSMITTER.isBound() || !SccNetworkBlocks.WIRELESS_RECEIVER.isBound()
                || !SccNetworkBlocks.NETWORK_ANTENNA.isBound()
                || !SccItems.RANGE_UPGRADE.isBound() || !SccItems.INFINITE_RANGE_UPGRADE.isBound()
                || !SccItems.DIMENSIONAL_UPGRADE.isBound()
                || !SccMenus.TERMINAL.isBound() || !SccMenus.CRAFTING_TERMINAL.isBound()
                || !SccMenus.REDSTONE_EMITTER.isBound() || !SccMenus.STORAGE_CONNECTOR.isBound()
                || !SccMenus.ANTENNA.isBound() || !MAIN_TAB.isBound()) {
            throw new IllegalStateException("SCC core item, network cable, BlockEntities or creative tab were not registered");
        }
        if (SccDataComponents.registeredCount() != 4 || !SccDataComponents.allBound()
                || !SccDataComponents.FACADE_BLOCK.isBound()
                || !SccDataComponents.WRENCH_MODE.isBound()
                || !SccDataComponents.SUBSTANCE_ID.isBound()
                || !SccDataComponents.BRUSH_DESIGN.isBound()) {
            throw new IllegalStateException("SCC Data Component registry invariant failed");
        }
        if (SccSounds.registeredCount() != 10 || !SccSounds.allBound()) {
            throw new IllegalStateException("SCC non-combat sound registry invariant failed");
        }
    }

    private static void validateTierMap(
            Map<StorageTier, SccHolder<FabricSccStorageBlock>> map,
            SccStorageBlock.Kind kind) {
        if (map.size() != StorageTier.values().length) {
            throw new IllegalStateException("SCC " + kind + " storage map does not contain every tier");
        }
        Set<ResourceLocation> ids = new HashSet<>();
        for (StorageTier tier : StorageTier.values()) {
            SccHolder<FabricSccStorageBlock> holder = map.get(tier);
            ResourceLocation expected = SuperCoolCalculator.id(tier.blockName(kind.family()));
            if (holder == null || !holder.isBound() || !holder.getId().equals(expected) || !ids.add(expected)) {
                throw new IllegalStateException("Invalid SCC storage registration for " + kind + " / " + tier);
            }
        }
    }

    private static void registerBlockEntityCapabilities() {
        var type = SccBlockEntities.SCC_STORAGE.get();
        PlatformCapabilities.registerItemStorage(
                type, (SccStorageBlockEntity blockEntity, net.minecraft.core.Direction side) ->
                        blockEntity.itemTransferStorage());
        PlatformCapabilities.registerFluidStorage(
                type, (SccStorageBlockEntity blockEntity, net.minecraft.core.Direction side) ->
                        blockEntity.fluidTransferStorage());
        PlatformCapabilities.registerEnergyStorage(
                type, (SccStorageBlockEntity blockEntity, net.minecraft.core.Direction side) ->
                        blockEntity.energyTransferStorage());

        // Cable Bus hosts parts at one position and exposes a single aggregate network endpoint.
        var cableBusType = SccBlockEntities.SCC_CABLE_BUS.get();
        PlatformCapabilities.registerItemStorage(cableBusType,
                (SccCableBusBlockEntity bus, net.minecraft.core.Direction side) -> {
                    var level = bus.getLevel();
                    if (level == null || !bus.hasCable()) return null;
                    SccNetworkView view = SccNetworkCache.view(level, bus.getBlockPos());
                    return view.itemStorageCount() == 0 ? null : view.itemTransferStorage();
                });
        PlatformCapabilities.registerFluidStorage(cableBusType,
                (SccCableBusBlockEntity bus, net.minecraft.core.Direction side) -> {
                    var level = bus.getLevel();
                    if (level == null || !bus.hasCable()) return null;
                    SccNetworkView view = SccNetworkCache.view(level, bus.getBlockPos());
                    return view.fluidStorageCount() == 0 ? null : view.fluidTransferStorage();
                });
        PlatformCapabilities.registerEnergyStorage(cableBusType,
                (SccCableBusBlockEntity bus, net.minecraft.core.Direction side) -> {
                    var level = bus.getLevel();
                    if (level == null || !bus.hasCable()) return null;
                    SccNetworkView view = SccNetworkCache.view(level, bus.getBlockPos());
                    return view.energyStorageCount() == 0 ? null : view.energyTransferStorage();
                });

        var connectorType = SccBlockEntities.SCC_STORAGE_CONNECTOR.get();
        PlatformCapabilities.registerItemStorage(connectorType,
                (SccStorageConnectorBlockEntity connector, net.minecraft.core.Direction side) ->
                        connector.hasItemEndpoint() ? connector.itemTransferStorage() : null);
        PlatformCapabilities.registerFluidStorage(connectorType,
                (SccStorageConnectorBlockEntity connector, net.minecraft.core.Direction side) ->
                        connector.hasFluidEndpoint() ? connector.fluidTransferStorage() : null);
        PlatformCapabilities.registerEnergyStorage(connectorType,
                (SccStorageConnectorBlockEntity connector, net.minecraft.core.Direction side) ->
                        connector.hasEnergyEndpoint() ? connector.energyTransferStorage() : null);

        // Cable faces expose one aggregate endpoint, not just the cable block itself.
        var cableType = SccBlockEntities.SCC_CABLE.get();
        PlatformCapabilities.registerItemStorage(cableType,
                (SccCableBlockEntity cable, net.minecraft.core.Direction side) -> {
                    var level = cable.getLevel();
                    if (level == null) return null;
                    SccNetworkView view = SccNetworkCache.view(level, cable.getBlockPos());
                    return view.itemStorageCount() == 0 ? null : view.itemTransferStorage();
                });
        PlatformCapabilities.registerFluidStorage(cableType,
                (SccCableBlockEntity cable, net.minecraft.core.Direction side) -> {
                    var level = cable.getLevel();
                    if (level == null) return null;
                    SccNetworkView view = SccNetworkCache.view(level, cable.getBlockPos());
                    return view.fluidStorageCount() == 0 ? null : view.fluidTransferStorage();
                });
        PlatformCapabilities.registerEnergyStorage(cableType,
                (SccCableBlockEntity cable, net.minecraft.core.Direction side) -> {
                    var level = cable.getLevel();
                    if (level == null) return null;
                    SccNetworkView view = SccNetworkCache.view(level, cable.getBlockPos());
                    return view.energyStorageCount() == 0 ? null : view.energyTransferStorage();
                });

        var terminalType = SccBlockEntities.SCC_TERMINAL.get();
        PlatformCapabilities.registerItemStorage(terminalType,
                (SccTerminalBlockEntity terminal, net.minecraft.core.Direction side) -> {
                    var level = terminal.getLevel();
                    if (level == null) return null;
                    SccNetworkView view = SccNetworkCache.view(level, terminal.getBlockPos());
                    return view.itemStorageCount() == 0 ? null : view.itemTransferStorage();
                });
        PlatformCapabilities.registerFluidStorage(terminalType,
                (SccTerminalBlockEntity terminal, net.minecraft.core.Direction side) -> {
                    var level = terminal.getLevel();
                    if (level == null) return null;
                    SccNetworkView view = SccNetworkCache.view(level, terminal.getBlockPos());
                    return view.fluidStorageCount() == 0 ? null : view.fluidTransferStorage();
                });
        PlatformCapabilities.registerEnergyStorage(terminalType,
                (SccTerminalBlockEntity terminal, net.minecraft.core.Direction side) -> {
                    var level = terminal.getLevel();
                    if (level == null) return null;
                    SccNetworkView view = SccNetworkCache.view(level, terminal.getBlockPos());
                    return view.energyStorageCount() == 0 ? null : view.energyTransferStorage();
                });

        var craftingTerminalType = SccBlockEntities.SCC_CRAFTING_TERMINAL.get();
        PlatformCapabilities.registerItemStorage(craftingTerminalType,
                (de.scc.calculator.network.SccCraftingTerminalBlockEntity terminal, net.minecraft.core.Direction side) -> {
                    var level = terminal.getLevel();
                    if (level == null) return null;
                    SccNetworkView view = SccNetworkCache.view(level, terminal.getBlockPos());
                    return view.itemStorageCount() == 0 ? null : view.itemTransferStorage();
                });
        PlatformCapabilities.registerFluidStorage(craftingTerminalType,
                (de.scc.calculator.network.SccCraftingTerminalBlockEntity terminal, net.minecraft.core.Direction side) -> {
                    var level = terminal.getLevel();
                    if (level == null) return null;
                    SccNetworkView view = SccNetworkCache.view(level, terminal.getBlockPos());
                    return view.fluidStorageCount() == 0 ? null : view.fluidTransferStorage();
                });
        PlatformCapabilities.registerEnergyStorage(craftingTerminalType,
                (de.scc.calculator.network.SccCraftingTerminalBlockEntity terminal, net.minecraft.core.Direction side) -> {
                    var level = terminal.getLevel();
                    if (level == null) return null;
                    SccNetworkView view = SccNetworkCache.view(level, terminal.getBlockPos());
                    return view.energyStorageCount() == 0 ? null : view.energyTransferStorage();
                });

        var antennaType = SccBlockEntities.SCC_NETWORK_ANTENNA.get();
        PlatformCapabilities.registerItemStorage(antennaType,
                (SccNetworkAntennaBlockEntity antenna, net.minecraft.core.Direction side) -> {
                    var level = antenna.getLevel();
                    if (level == null) return null;
                    SccNetworkView view = SccNetworkCache.view(level, antenna.getBlockPos());
                    return view.itemStorageCount() == 0 ? null : view.itemTransferStorage();
                });
        PlatformCapabilities.registerFluidStorage(antennaType,
                (SccNetworkAntennaBlockEntity antenna, net.minecraft.core.Direction side) -> {
                    var level = antenna.getLevel();
                    if (level == null) return null;
                    SccNetworkView view = SccNetworkCache.view(level, antenna.getBlockPos());
                    return view.fluidStorageCount() == 0 ? null : view.fluidTransferStorage();
                });
        PlatformCapabilities.registerEnergyStorage(antennaType,
                (SccNetworkAntennaBlockEntity antenna, net.minecraft.core.Direction side) -> {
                    var level = antenna.getLevel();
                    if (level == null) return null;
                    SccNetworkView view = SccNetworkCache.view(level, antenna.getBlockPos());
                    return view.energyStorageCount() == 0 ? null : view.energyTransferStorage();
                });
    }
}
