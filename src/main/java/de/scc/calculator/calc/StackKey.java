package de.scc.calculator.calc;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import de.scc.calculator.SccConfig;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.TagParser;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Immutable inventory key for an item plus its variant data components.
 *
 * <p>Component relevance is defined by {@link SccConfig}: ignored components
 * never affect identity, while every other component is part of the key. The
 * encoded form starts with the item identifier so existing ordering and
 * vanilla-first comparisons remain stable.</p>
 */
public final class StackKey {
    private static final HolderLookup.Provider REGISTRIES =
            RegistryAccess.fromRegistryOfRegistries(BuiltInRegistries.REGISTRY);
    private static final Object LOCK = new Object();
    private static volatile SccConfig policy = SccConfig.get();
    private static volatile Object generation = new Object();

    private final Item item;
    private final DataComponentPatch components;
    private final String encoded;
    private final Set<String> componentIds;

    private StackKey(Item item, DataComponentPatch components, String encoded, Set<String> componentIds) {
        this.item = item;
        this.components = components;
        this.encoded = encoded;
        this.componentIds = componentIds;
    }

    public static StackKey of(Item item) {
        return item == null ? null : of(new ItemStack(item));
    }

    public static StackKey of(ItemStack stack) {
        return stack == null || stack.isEmpty() ? null : fromStack(stack);
    }

    public static StackKey decode(String encoded) {
        if (encoded == null) {
            return null;
        }
        String value = encoded.trim();
        if (value.isEmpty() || value.length() > 4_000) {
            return null;
        }
        refreshPolicy();
        try {
            if (value.charAt(0) == '{') {
                return parseFullTag(value);
            }
            int brace = value.indexOf('{');
            if (brace < 0) {
                return plainItem(value);
            }
            if (!value.endsWith("}")) {
                return null;
            }
            String itemPart = value.substring(0, brace);
            StackKey plain = plainItem(itemPart);
            if (plain == null) {
                return null;
            }
            CompoundTag parsed = TagParser.parseTag(value.substring(brace));
            CompoundTag full = new CompoundTag();
            full.putString("id", itemPart);
            full.putByte("count", (byte) 1);
            full.put("components", parsed);
            Optional<ItemStack> stack = ItemStack.parse(REGISTRIES, full);
            return stack.map(StackKey::fromStack).orElse(null);
        } catch (CommandSyntaxException | RuntimeException ignored) {
            return null;
        }
    }

    private static StackKey parseFullTag(String value) throws CommandSyntaxException {
        CompoundTag tag = TagParser.parseTag(value);
        Optional<ItemStack> parsed = ItemStack.parse(REGISTRIES, tag);
        return parsed.map(StackKey::fromStack).orElse(null);
    }

    private static StackKey plainItem(String itemId) {
        ResourceLocation id = ResourceLocation.tryParse(itemId.trim());
        if (id == null) {
            return null;
        }
        Optional<Item> registered = BuiltInRegistries.ITEM.getOptional(id);
        Item item = registered.orElse(null);
        return item == null ? null : fromStack(new ItemStack(item));
    }

    private static StackKey fromStack(ItemStack stack) {
        refreshPolicy();
        ResourceLocation id = BuiltInRegistries.ITEM.getKey(stack.getItem());
        if (id == null) {
            return null;
        }
        DataComponentPatch filtered = filter(stack.getComponentsPatch());
        TreeMap<String, Tag> tags = componentTags(filtered);
        StringBuilder encoded = new StringBuilder(id.toString());
        if (!tags.isEmpty()) {
            encoded.append(canonical(compound(tags)).toString());
        }
        return new StackKey(stack.getItem(), filtered, encoded.toString(), Set.copyOf(tags.keySet()));
    }

    private static DataComponentPatch filter(DataComponentPatch source) {
        DataComponentPatch.Builder builder = DataComponentPatch.builder();
        boolean changed = false;
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : source.entrySet()) {
            DataComponentType<?> type = entry.getKey();
            ResourceLocation componentId = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(type);
            if (componentId == null || policy.isIgnoredComponent(componentId.toString())) {
                changed = true;
                continue;
            }
            Optional<?> value = entry.getValue();
            if (value == null || value.isEmpty()) {
                setRemoval(builder, type);
            } else if (encodeComponent(type, value.get()) != null) {
                setValue(builder, type, value.get());
            } else {
                changed = true;
            }
        }
        return changed || !source.entrySet().isEmpty()
                ? builder.build()
                : DataComponentPatch.EMPTY;
    }

    private static TreeMap<String, Tag> componentTags(DataComponentPatch patch) {
        TreeMap<String, Tag> tags = new TreeMap<>();
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : patch.entrySet()) {
            Optional<?> value = entry.getValue();
            if (value == null || value.isEmpty()) {
                continue;
            }
            ResourceLocation componentId = BuiltInRegistries.DATA_COMPONENT_TYPE.getKey(entry.getKey());
            if (componentId == null || policy.isIgnoredComponent(componentId.toString())) {
                continue;
            }
            Tag tag = encodeComponent(entry.getKey(), value.get());
            if (tag != null) {
                tags.put(componentId.toString(), tag);
            }
        }
        return tags;
    }

    private static CompoundTag compound(Map<String, Tag> tags) {
        CompoundTag result = new CompoundTag();
        tags.forEach(result::put);
        return result;
    }

    private static CompoundTag canonical(CompoundTag source) {
        CompoundTag result = new CompoundTag();
        for (String key : new TreeSet<>(source.getAllKeys())) {
            result.put(key, canonicalValue(source.get(key)));
        }
        return result;
    }

    private static Tag canonicalValue(Tag value) {
        return value instanceof CompoundTag compound ? canonical(compound) : value.copy();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Tag encodeComponent(DataComponentType<?> type, Object value) {
        try {
            Codec codec = ((DataComponentType) type).codec();
            DataResult<Tag> result = codec.encodeStart(NbtOps.INSTANCE, value);
            return result.result().orElse(null);
        } catch (Throwable ignored) {
            return null;
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setValue(DataComponentPatch.Builder builder, DataComponentType<?> type, Object value) {
        builder.set((DataComponentType) type, value);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void setRemoval(DataComponentPatch.Builder builder, DataComponentType<?> type) {
        builder.remove((DataComponentType) type);
    }

    public Item item() {
        return item;
    }

    public String encode() {
        return encoded;
    }

    public boolean plain() {
        return componentIds.isEmpty();
    }

    public boolean onlyModData() {
        if (componentIds.isEmpty()) {
            return false;
        }
        for (String componentId : componentIds) {
            if (componentId.startsWith("minecraft:")) {
                return false;
            }
        }
        return true;
    }

    public ItemStack stack(int count) {
        ItemStack result = new ItemStack(item, Math.max(1, Math.min(count, Integer.MAX_VALUE)));
        if (!components.entrySet().isEmpty()) {
            result.applyComponents(components);
        }
        return result;
    }

    public ItemStack display() {
        return stack(1);
    }

    public boolean matches(ItemStack other) {
        if (other == null || other.isEmpty()) {
            return false;
        }
        StackKey key = of(other);
        return key != null && key.equals(this);
    }

    /**
     * Returns a patch that removes components whose codec cannot round-trip in
     * the supplied registry context, or {@code null} when no repair is needed.
     */
    public static DataComponentPatch patchInSet(ItemStack stack, HolderLookup.Provider registries) {
        if (stack == null || stack.isEmpty() || registries == null) {
            return null;
        }
        DataComponentPatch patch = stack.getComponentsPatch();
        if (patch.entrySet().isEmpty()) {
            return null;
        }
        DataComponentPatch.Builder repair = null;
        for (Map.Entry<DataComponentType<?>, Optional<?>> entry : patch.entrySet()) {
            Optional<?> value = entry.getValue();
            if (value == null || value.isEmpty()) {
                continue;
            }
            if (!roundTrips(entry.getKey(), value.get())) {
                if (repair == null) {
                    repair = DataComponentPatch.builder();
                }
                setRemoval(repair, entry.getKey());
            }
        }
        return repair == null ? null : repair.build();
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static boolean roundTrips(DataComponentType<?> type, Object value) {
        try {
            Codec codec = ((DataComponentType) type).codec();
            DataResult<Tag> encoded = codec.encodeStart(NbtOps.INSTANCE, value);
            Optional<Tag> tag = encoded.result();
            if (tag.isEmpty()) {
                return false;
            }
            return codec.parse(NbtOps.INSTANCE, tag.get()).result().isPresent();
        } catch (Throwable ignored) {
            return false;
        }
    }

    /** Stable identity that changes whenever the component policy is replaced. */
    public static Object generation() {
        refreshPolicy();
        return generation;
    }

    private static void refreshPolicy() {
        SccConfig current = SccConfig.get();
        if (current == policy) {
            return;
        }
        synchronized (LOCK) {
            if (current != policy) {
                policy = current;
                generation = new Object();
            }
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof StackKey key)) {
            return false;
        }
        return item == key.item && encoded.equals(key.encoded);
    }

    @Override
    public int hashCode() {
        return 31 * Objects.hashCode(item) + encoded.hashCode();
    }

    @Override
    public String toString() {
        return encoded;
    }
}
