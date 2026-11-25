package de.scc.calculator.registry;

import de.scc.calculator.network.FabricSccStorageBlock;
import de.scc.calculator.network.SccStorageBlock;
import de.scc.calculator.network.StorageTier;
import de.scc.calculator.platform.registry.PlatformRegistry;
import de.scc.calculator.platform.registry.SccHolder;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Registers the six original storage families across the six original tiers. */
public final class SccStorageBlocks {
    public static final Map<StorageTier, SccHolder<FabricSccStorageBlock>> ITEM_STORAGES = new EnumMap<>(StorageTier.class);
    public static final Map<StorageTier, SccHolder<FabricSccStorageBlock>> FLUID_STORAGES = new EnumMap<>(StorageTier.class);
    public static final Map<StorageTier, SccHolder<FabricSccStorageBlock>> ENERGY_STORAGES = new EnumMap<>(StorageTier.class);
    public static final Map<StorageTier, SccHolder<FabricSccStorageBlock>> CHEMICAL_STORAGES = new EnumMap<>(StorageTier.class);
    public static final Map<StorageTier, SccHolder<FabricSccStorageBlock>> SOURCE_STORAGES = new EnumMap<>(StorageTier.class);
    public static final Map<StorageTier, SccHolder<FabricSccStorageBlock>> SOUL_STORAGES = new EnumMap<>(StorageTier.class);

    private static final List<SccHolder<FabricSccStorageBlock>> ALL = new ArrayList<>(36);

    static {
        for (StorageTier tier : StorageTier.values()) {
            register(ITEM_STORAGES, tier, SccStorageBlock.Kind.ITEM);
            register(FLUID_STORAGES, tier, SccStorageBlock.Kind.FLUID);
            register(ENERGY_STORAGES, tier, SccStorageBlock.Kind.ENERGY);
            register(CHEMICAL_STORAGES, tier, SccStorageBlock.Kind.CHEMICAL);
            register(SOURCE_STORAGES, tier, SccStorageBlock.Kind.SOURCE);
            register(SOUL_STORAGES, tier, SccStorageBlock.Kind.SOUL);
        }
    }

    private SccStorageBlocks() { }

    private static void register(Map<StorageTier, SccHolder<FabricSccStorageBlock>> map,
                                  StorageTier tier, SccStorageBlock.Kind kind) {
        String id = tier.blockName(kind.family());
        SccHolder<FabricSccStorageBlock> block = PlatformRegistry.registerBlockWithItem(
                id,
                () -> new FabricSccStorageBlock(FabricSccStorageBlock.deviceProperties(), tier, kind),
                new Item.Properties());
        map.put(tier, block);
        ALL.add(block);
    }

    public static void init() { /* triggers static registration */ }

    public static SccHolder<FabricSccStorageBlock> get(SccStorageBlock.Kind kind, StorageTier tier) {
        if (kind == null || tier == null) return null;
        return switch (kind) {
            case ITEM -> ITEM_STORAGES.get(tier);
            case FLUID -> FLUID_STORAGES.get(tier);
            case ENERGY -> ENERGY_STORAGES.get(tier);
            case CHEMICAL -> CHEMICAL_STORAGES.get(tier);
            case SOURCE -> SOURCE_STORAGES.get(tier);
            case SOUL -> SOUL_STORAGES.get(tier);
        };
    }

    public static List<Block> allBlocks() {
        return ALL.stream().map(SccHolder::get).map(Block.class::cast).toList();
    }

    public static List<FabricSccStorageBlock> allStorageBlocks() {
        return ALL.stream().map(SccHolder::get).toList();
    }
}
