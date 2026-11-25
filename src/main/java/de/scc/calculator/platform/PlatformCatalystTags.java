package de.scc.calculator.platform;

import com.mojang.datafixers.util.Pair;
import de.scc.calculator.SccLog;
import de.scc.calculator.calc.CatalystTags;
import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Bridges the live server item-registry tags into the loader-neutral SCC index.
 * The snapshot is read after server startup and successful datapack reloads.
 */
public final class PlatformCatalystTags {
    private PlatformCatalystTags() { }

    public static void reload(MinecraftServer server) {
        if (server == null) return;

        Registry<Item> itemRegistry = server.registryAccess().registryOrThrow(Registries.ITEM);
        Map<String, List<String>> resolved = new LinkedHashMap<>();
        for (Pair<TagKey<Item>, net.minecraft.core.HolderSet.Named<Item>> entry : itemRegistry.getTags().toList()) {
            TagKey<Item> tag = entry.getFirst();
            ResourceLocation tagId = tag.location();
            String namespace = tagId.getNamespace();
            String path = tagId.getPath();
            if (!"scc".equals(namespace)
                    || !(path.startsWith("catalyst/") || path.startsWith("not_catalyst/"))) {
                continue;
            }

            List<String> itemIds = new ArrayList<>();
            for (Holder<Item> holder : entry.getSecond()) {
                ResourceLocation itemId = itemRegistry.getKey(holder.value());
                if (itemId != null) itemIds.add(itemId.toString());
            }
            resolved.put(tagId.toString(), List.copyOf(itemIds));
        }

        CatalystTags.reload(resolved);
        int tagCount = resolved.size();
        int itemCount = resolved.values().stream().mapToInt(List::size).sum();
        SccLog.info("tags", "loaded " + tagCount + " SCC catalyst tags with " + itemCount + " item entries");
    }
}
