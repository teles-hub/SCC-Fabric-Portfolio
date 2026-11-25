package de.scc.calculator.network;

import de.scc.calculator.SuperCoolCalculator;
import de.scc.calculator.registry.SccBlockEntities;
import de.scc.calculator.platform.registry.PlatformMenuHelper;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Fabric block implementation for the six SCC storage families.
 *
 * <p>The SCC 1.6.5 block used the same FILL state, fill-level calculation,
 * device properties and block-entity loot payload. Its GUI interaction is
 * intentionally not wired yet because StorageConnectorMenu is still pending.</p>
 */
public final class FabricSccStorageBlock extends Block implements EntityBlock, SccStorageBlock {
    public static final IntegerProperty FILL = IntegerProperty.create("fill", 0, 4);
    public static final MapCodec<FabricSccStorageBlock> CODEC = simpleCodec(
            properties -> new FabricSccStorageBlock(properties, StorageTier.BASIC, Kind.ITEM));

    private final StorageTier tier;
    private final Kind kind;

    public FabricSccStorageBlock(BlockBehaviour.Properties properties, StorageTier tier, Kind kind) {
        super(properties);
        this.tier = tier == null ? StorageTier.BASIC : tier;
        this.kind = kind == null ? Kind.ITEM : kind;
        registerDefaultState(defaultBlockState().setValue(FILL, 0));
    }

    public static BlockBehaviour.Properties deviceProperties() {
        // Decompiled from SccRegistry.deviceProps(): strength(2.0F, 6.0F), SoundType.METAL.
        return BlockBehaviour.Properties.of().strength(2.0F, 6.0F).sound(SoundType.METAL);
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FILL);
    }

    @Override
    public StorageTier tier() {
        return tier;
    }

    @Override
    public Kind kind() {
        return kind;
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SccStorageBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        if (level.isClientSide || type != SccBlockEntities.SCC_STORAGE.get()) return null;
        return (tickLevel, tickPos, tickState, blockEntity) -> {
            if (blockEntity instanceof SccStorageBlockEntity storage) {
                SccStorageBlockEntity.serverTick(tickLevel, tickPos, tickState, storage);
            }
        };
    }

    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos,
                                               Player player, InteractionHand hand, BlockHitResult hit) {
        // Preserve cable-to-bus conversion while keeping direct storage upgrades available.
        if (stack.is(de.scc.calculator.registry.SccNetworkBlocks.CABLE.get().asItem())) {
            if (level.isClientSide) return ItemInteractionResult.SUCCESS;
            if (!SccCableBusBlock.convertPartToBus(level, pos, hit.getDirection())) {
                return ItemInteractionResult.FAIL;
            }
            if (!player.getAbilities().instabuild) stack.shrink(1);
            return ItemInteractionResult.SUCCESS;
        }
        return attemptUpgrade(state, level, pos, player);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos,
                                                Player player, BlockHitResult hit) {
        if (level.isClientSide) return InteractionResult.SUCCESS;

        // Upgrade takes precedence only when it can actually run. Otherwise preserve
        // the Storage Connector menu interaction introduced by the cabling integration.
        ItemInteractionResult upgradeResult = attemptUpgrade(state, level, pos, player);
        if (upgradeResult != ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION) {
            return upgradeResult.result();
        }
        if (level.getBlockEntity(pos) instanceof SccStorageBlockEntity
                && player instanceof ServerPlayer serverPlayer) {
            SimpleMenuProvider provider = new SimpleMenuProvider(
                    (containerId, inventory, user) -> new SccStorageConnectorMenu(containerId, inventory, pos),
                    net.minecraft.network.chat.Component.translatable("block.scc.storage"));
            PlatformMenuHelper.openBlockMenu(serverPlayer, provider, pos);
            return InteractionResult.CONSUME;
        }
        return InteractionResult.PASS;
    }

    private ItemInteractionResult attemptUpgrade(BlockState state, Level level, BlockPos pos, Player player) {
        StorageTier nextTier = tier.next();
        if (nextTier == null || !SccStorageUpgrade.isUpgrade(tier, nextTier)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        Map<net.minecraft.world.item.Item, Integer> ingredients =
                SccStorageUpgrade.ingredientsFor(nextTier);
        if (ingredients.isEmpty() || (!player.getAbilities().instabuild
                && !hasIngredients(player, ingredients))) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (level.isClientSide) {
            return ItemInteractionResult.sidedSuccess(true);
        }

        BlockEntity currentEntity = level.getBlockEntity(pos);
        if (!(currentEntity instanceof SccStorageBlockEntity currentStorage)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        CompoundTag storageSnapshot = currentStorage.saveWithoutMetadata(level.registryAccess());
        var targetHolder = de.scc.calculator.registry.SccStorageBlocks.get(kind, nextTier);
        if (targetHolder == null || !targetHolder.isBound()) {
            return ItemInteractionResult.FAIL;
        }

        BlockState upgradedState = targetHolder.get().defaultBlockState().setValue(FILL, state.getValue(FILL));
        if (!level.setBlock(pos, upgradedState, Block.UPDATE_ALL)) {
            return ItemInteractionResult.FAIL;
        }

        BlockEntity upgradedEntity = level.getBlockEntity(pos);
        if (!(upgradedEntity instanceof SccStorageBlockEntity upgradedStorage)) {
            restoreOriginalStorage(level, pos, state, storageSnapshot);
            return ItemInteractionResult.FAIL;
        }

        try {
            upgradedStorage.loadWithComponents(storageSnapshot, level.registryAccess());
            upgradedStorage.storage().setTier(nextTier);
            upgradedStorage.setChanged();

            // Recalculate the displayed fill after the new tier changes total capacity.
            BlockState filledState = upgradedState.setValue(
                    FILL, fillLevel(upgradedStorage.fillUsed(), upgradedStorage.fillTotal()));
            if (!filledState.equals(upgradedState)
                    && !level.setBlock(pos, filledState, Block.UPDATE_ALL)) {
                restoreOriginalStorage(level, pos, state, storageSnapshot);
                return ItemInteractionResult.FAIL;
            }
            level.sendBlockUpdated(pos, state, filledState, Block.UPDATE_ALL);
            SccNetworkCache.invalidate(level);
        } catch (RuntimeException upgradeFailure) {
            boolean restored = restoreOriginalStorage(level, pos, state, storageSnapshot);
            SuperCoolCalculator.LOGGER.error(
                    "SCC storage upgrade failed at {}; original storage restored: {}", pos, restored, upgradeFailure);
            return ItemInteractionResult.FAIL;
        }

        if (!player.getAbilities().instabuild) {
            consumeIngredients(player, ingredients);
        }
        return ItemInteractionResult.sidedSuccess(false);
    }

    /**
     * Best-effort transactional rollback. Ingredients are never consumed when this path runs.
     */
    private static boolean restoreOriginalStorage(
            Level level, BlockPos pos, BlockState originalState, CompoundTag storageSnapshot) {
        boolean blockRestored = level.setBlock(pos, originalState, Block.UPDATE_ALL);
        SccNetworkCache.invalidate(level);
        if (!blockRestored) {
            SuperCoolCalculator.LOGGER.error("Could not restore original SCC storage block at {}", pos);
            return false;
        }
        BlockEntity restored = level.getBlockEntity(pos);
        if (!(restored instanceof SccStorageBlockEntity restoredStorage)) {
            SuperCoolCalculator.LOGGER.error("Restored SCC storage block has no storage BlockEntity at {}", pos);
            return false;
        }
        try {
            restoredStorage.loadWithComponents(storageSnapshot, level.registryAccess());
            restoredStorage.setChanged();
            return true;
        } catch (RuntimeException rollbackFailure) {
            SuperCoolCalculator.LOGGER.error("Could not restore SCC storage payload at {}", pos, rollbackFailure);
            return false;
        }
    }

    private static boolean hasIngredients(Player player, Map<net.minecraft.world.item.Item, Integer> ingredients) {
        for (Map.Entry<net.minecraft.world.item.Item, Integer> ingredient : ingredients.entrySet()) {
            int remaining = ingredient.getValue();
            for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (stack.is(ingredient.getKey())) remaining -= stack.getCount();
            }
            if (remaining > 0) return false;
        }
        return true;
    }

    /** Called only after a full inventory preflight and successful block replacement. */
    private static void consumeIngredients(Player player, Map<net.minecraft.world.item.Item, Integer> ingredients) {
        for (Map.Entry<net.minecraft.world.item.Item, Integer> ingredient : ingredients.entrySet()) {
            int remaining = ingredient.getValue();
            for (int slot = 0; slot < player.getInventory().getContainerSize() && remaining > 0; slot++) {
                ItemStack stack = player.getInventory().getItem(slot);
                if (!stack.is(ingredient.getKey())) continue;
                int taken = Math.min(stack.getCount(), remaining);
                stack.shrink(taken);
                remaining -= taken;
            }
        }
        player.getInventory().setChanged();
    }

    public static int fillLevel(long used, long total) {
        return SccStorageMath.fillLevel(used, total);
    }

    @Override
    protected boolean hasAnalogOutputSignal(BlockState state) {
        return true;
    }

    @Override
    protected int getAnalogOutputSignal(BlockState state, Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof SccStorageBlockEntity storage)) return 0;
        long used = storage.fillUsed();
        long total = storage.fillTotal();
        return SccStorageMath.analogSignal(used, total);
    }

    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        List<ItemStack> drops = super.getDrops(state, params);
        BlockEntity blockEntity = params.getOptionalParameter(LootContextParams.BLOCK_ENTITY);
        if (!(blockEntity instanceof SccStorageBlockEntity storage) || storage.emptyStore()) {
            return drops;
        }
        Level level = storage.getLevel();
        if (level == null) return drops;

        for (ItemStack stack : drops) {
            if (stack.isEmpty() || !stack.is(asItem())) continue;
            var blockEntityData = new net.minecraft.nbt.CompoundTag();
            blockEntityData = storage.saveItemData(level.registryAccess());
            BlockItem.setBlockEntityData(stack, SccBlockEntities.SCC_STORAGE.get(), blockEntityData);
        }
        return drops;
    }
}
