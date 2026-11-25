package de.scc.calculator.platform.registry;

import de.scc.calculator.SuperCoolCalculator;
import net.fabricmc.fabric.api.itemgroup.v1.FabricItemGroup;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Fabrica de registros do Fabric 1.21.1. Todos os registros passam por SccHolder.register(...) (que devolve um
 * Holder real). Mudancas: BlockEntityType.Builder.of e publico (a nota sobre AccessWidener estava errada);
 * RecipeType usa RecipeType.register(id.toString()).
 */
public final class PlatformRegistry {

    private static final List<SccHolder<? extends Item>> ALL_ITEMS = new ArrayList<>();

    private PlatformRegistry() {
    }

    public static List<SccHolder<? extends Item>> getRegisteredItems() {
        return Collections.unmodifiableList(ALL_ITEMS);
    }

    public static <T extends Block> SccHolder<T> registerBlock(String name, Supplier<T> factory) {
        return SccHolder.register(BuiltInRegistries.BLOCK, SuperCoolCalculator.id(name), factory.get());
    }

    public static <T extends Block> SccHolder<T> registerBlockWithItem(
            String name, Supplier<T> blockFactory, Item.Properties itemProperties) {
        SccHolder<T> blockHolder = registerBlock(name, blockFactory);
        registerItem(name, () -> new BlockItem(blockHolder.get(), itemProperties));
        return blockHolder;
    }

    public static <T extends Item> SccHolder<T> registerItem(String name, Supplier<T> factory) {
        SccHolder<T> holder = SccHolder.register(BuiltInRegistries.ITEM, SuperCoolCalculator.id(name), factory.get());
        ALL_ITEMS.add(holder);
        return holder;
    }

    public static <T> SccHolder<DataComponentType<T>> registerDataComponent(
            String name, UnaryOperator<DataComponentType.Builder<T>> builderOp) {
        DataComponentType<T> component = builderOp.apply(DataComponentType.builder()).build();
        return SccHolder.register(BuiltInRegistries.DATA_COMPONENT_TYPE, SuperCoolCalculator.id(name), component);
    }

    public static <T extends BlockEntity> SccHolder<BlockEntityType<T>> registerBlockEntity(
            String name, FabricBlockEntityTypeBuilder.Factory<? extends T> factory, Block... validBlocks) {
        BlockEntityType<T> type = FabricBlockEntityTypeBuilder.<T>create(factory, validBlocks).build();
        return SccHolder.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, SuperCoolCalculator.id(name), type);
    }

    public static <T extends AbstractContainerMenu> SccHolder<MenuType<T>> registerSimpleMenu(
            String name, MenuType.MenuSupplier<T> factory) {
        MenuType<T> type = new MenuType<>(factory, FeatureFlags.VANILLA_SET);
        return SccHolder.register(BuiltInRegistries.MENU, SuperCoolCalculator.id(name), type);
    }

    /** Substitui IMenuTypeExtension.create((id, inv, buf) -> new Menu(id, inv, buf.readBlockPos())). */
    public static <T extends AbstractContainerMenu> SccHolder<ExtendedScreenHandlerType<T, BlockPos>> registerBlockMenu(
            String name, BlockMenuFactory<T> factory) {
        ExtendedScreenHandlerType<T, BlockPos> type = new ExtendedScreenHandlerType<>(factory::create, BlockPos.STREAM_CODEC);
        return SccHolder.register(BuiltInRegistries.MENU, SuperCoolCalculator.id(name), type);
    }

    public static <T extends AbstractContainerMenu, D> SccHolder<ExtendedScreenHandlerType<T, D>> registerExtendedMenu(
            String name, ExtendedScreenHandlerType.ExtendedFactory<T, D> factory,
            StreamCodec<? super RegistryFriendlyByteBuf, D> packetCodec) {
        ExtendedScreenHandlerType<T, D> type = new ExtendedScreenHandlerType<>(factory, packetCodec);
        return SccHolder.register(BuiltInRegistries.MENU, SuperCoolCalculator.id(name), type);
    }

    @FunctionalInterface
    public interface BlockMenuFactory<T extends AbstractContainerMenu> {
        T create(int syncId, Inventory playerInventory, BlockPos pos);
    }

    public static <T extends Recipe<?>> SccHolder<RecipeType<T>> registerRecipeType(String name) {
        ResourceLocation id = SuperCoolCalculator.id(name);
        return SccHolder.register(BuiltInRegistries.RECIPE_TYPE, id, RecipeType.<T>register(id.toString()));
    }

    public static <T extends Recipe<?>> SccHolder<RecipeSerializer<T>> registerRecipeSerializer(
            String name, RecipeSerializer<T> serializer) {
        return SccHolder.register(BuiltInRegistries.RECIPE_SERIALIZER, SuperCoolCalculator.id(name), serializer);
    }

    public static SccHolder<SoundEvent> registerSound(String name) {
        ResourceLocation id = SuperCoolCalculator.id(name);
        return SccHolder.register(BuiltInRegistries.SOUND_EVENT, id, SoundEvent.createVariableRangeEvent(id));
    }

    public static SccHolder<CreativeModeTab> registerCreativeTab(
            String name, Supplier<ItemStack> iconSupplier, CreativeModeTab.DisplayItemsGenerator itemsGenerator) {
        CreativeModeTab tab = FabricItemGroup.builder()
                .title(Component.translatable("itemGroup." + SuperCoolCalculator.MOD_ID + "." + name))
                .icon(iconSupplier)
                .displayItems(itemsGenerator)
                .build();
        return SccHolder.register(BuiltInRegistries.CREATIVE_MODE_TAB, SuperCoolCalculator.id(name), tab);
    }
}
