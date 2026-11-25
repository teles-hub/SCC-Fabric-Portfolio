package de.scc.calculator.network;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Persistent antenna address stored on wireless receiver/link items.
 *
 * <p>This is a direct Fabric-safe reconstruction of the SCC 1.6.5 item-link
 * contract. It uses vanilla 1.21.1 CUSTOM_DATA rather than loader-specific item
 * capabilities or legacy stack NBT.</p>
 */
public final class WirelessLink {
    public static final String TAG_POS = "scc_link";
    public static final String TAG_DIM = "scc_link_dim";

    private WirelessLink() { }

    public static void write(ItemStack stack, BlockPos position, ResourceKey<Level> dimension) {
        if (stack == null || stack.isEmpty() || position == null || dimension == null) return;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putLong(TAG_POS, position.asLong());
        tag.putString(TAG_DIM, dimension.location().toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static void clear(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.remove(TAG_POS);
        tag.remove(TAG_DIM);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Nullable
    public static BlockPos posOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.contains(TAG_POS) ? BlockPos.of(tag.getLong(TAG_POS)) : null;
    }

    @Nullable
    public static ResourceKey<Level> dimOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return dimFromName(tag.contains(TAG_DIM) ? tag.getString(TAG_DIM) : null);
    }

    @Nullable
    public static String dimNameOf(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.contains(TAG_DIM) ? tag.getString(TAG_DIM) : null;
    }

    @Nullable
    public static ResourceKey<Level> dimFromName(@Nullable String name) {
        if (name == null || name.isBlank()) return null;
        ResourceLocation id = ResourceLocation.tryParse(name);
        return id == null ? null : ResourceKey.create(Registries.DIMENSION, id);
    }

    public static String shortName(ResourceKey<Level> dimension) {
        ResourceLocation id = dimension.location();
        return "minecraft".equals(id.getNamespace()) ? id.getPath() : id.toString();
    }
}
