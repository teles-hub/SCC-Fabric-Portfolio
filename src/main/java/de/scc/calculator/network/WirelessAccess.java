package de.scc.calculator.network;

import de.scc.calculator.compat.accessories.SccAccessoriesCompat;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.level.Level;
import de.scc.calculator.platform.registry.PlatformMenuHelper;
import org.jetbrains.annotations.Nullable;

import java.util.function.Predicate;

/**
 * Wireless terminal address handling and guarded remote access.
 * Remote endpoint lookup is deliberately limited to already-loaded chunks.
 */
public final class WirelessAccess {
    private static final String TAG_POS = "scc_antenna";
    private static final String TAG_DIM = "scc_dim";

    private WirelessAccess() { }

    public static void link(ItemStack stack, BlockPos pos, ResourceKey<Level> dimension) {
        if (stack == null || stack.isEmpty() || pos == null || dimension == null) return;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.putLong(TAG_POS, pos.asLong());
        tag.putString(TAG_DIM, dimension.location().toString());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    public static void unlink(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        tag.remove(TAG_POS);
        tag.remove(TAG_DIM);
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));
    }

    @Nullable public static BlockPos linkedPos(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        return tag.contains(TAG_POS) ? BlockPos.of(tag.getLong(TAG_POS)) : null;
    }

    @Nullable public static ResourceKey<Level> linkedDim(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CompoundTag tag = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        if (!tag.contains(TAG_DIM)) return null;
        ResourceLocation id = ResourceLocation.tryParse(tag.getString(TAG_DIM));
        return id == null ? null : WirelessLink.dimFromName(id.toString());
    }

    @Nullable public static String linkedDimName(ItemStack stack) {
        ResourceKey<Level> dimension = linkedDim(stack);
        return dimension == null ? null : dimension.location().toString();
    }

    public static ItemStack findTerminal(Player player) {
        ItemStack crafting = findTerminal(player, true);
        return crafting.isEmpty() ? findTerminal(player, false) : crafting;
    }

    public static ItemStack findTerminal(Player player, boolean crafting) {
        return find(player, stack -> stack.getItem() instanceof WirelessTerminalItem terminal
                && terminal.crafting() == crafting && linkedPos(stack) != null);
    }

    public static ItemStack find(Player player, Predicate<ItemStack> predicate) {
        if (player == null || predicate == null) return ItemStack.EMPTY;
        ItemStack main = player.getMainHandItem();
        if (predicate.test(main)) return main;
        ItemStack off = player.getOffhandItem();
        if (predicate.test(off)) return off;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (predicate.test(stack)) return stack;
        }
        // Accessories is optional at the mod boundary but is shipped by Cobbleverse 1.8.0.
        // Isolate API linkage in SccAccessoriesCompat so SCC still loads without Accessories.
        if (FabricLoader.getInstance().isModLoaded("accessories")) {
            return SccAccessoriesCompat.findEquipped(player, predicate);
        }
        return ItemStack.EMPTY;
    }

    public static ItemStack findAny(Player player) {
        return findTerminal(player);
    }

    public static boolean hasLinkedTerminal(Player player, BlockPos pos, ResourceKey<Level> dimension) {
        if (player == null || pos == null || dimension == null) return false;
        return !find(player, stack -> stack.getItem() instanceof WirelessTerminalItem
                && pos.equals(linkedPos(stack)) && dimension.equals(linkedDim(stack))).isEmpty();
    }

    public static boolean keepsWindowOpen(Player player, ItemStack stack,
                                           @Nullable BlockPos pos, @Nullable ResourceKey<Level> dimension) {
        if (pos == null || dimension == null || stack == null || stack.isEmpty()
                || !(stack.getItem() instanceof WirelessTerminalItem)
                || !pos.equals(linkedPos(stack)) || !dimension.equals(linkedDim(stack))) return false;
        return find(player, candidate -> candidate != stack
                && candidate.getItem() instanceof WirelessTerminalItem
                && pos.equals(linkedPos(candidate)) && dimension.equals(linkedDim(candidate))).isEmpty();
    }

    public static boolean refuseOwnTerminal(Player player, ItemStack stack,
                                            @Nullable BlockPos pos, @Nullable ResourceKey<Level> dimension) {
        if (!keepsWindowOpen(player, stack, pos, dimension)) return false;
        player.displayClientMessage(Component.translatable("scc.msg.wireless.inuse"), true);
        return true;
    }

    @Nullable public static SccNetworkAntennaBlockEntity antenna(Player player, ItemStack stack) {
        BlockPos pos = linkedPos(stack);
        ResourceKey<Level> dimension = linkedDim(stack);
        if (pos == null || dimension == null || player.getServer() == null) return null;
        ServerLevel target = player.getServer().getLevel(dimension);
        if (target == null || !target.hasChunkAt(pos)) return null;
        return target.getBlockEntity(pos) instanceof SccNetworkAntennaBlockEntity antenna ? antenna : null;
    }

    public static boolean inRange(Player player, SccNetworkAntennaBlockEntity antenna,
                                  ResourceKey<Level> antennaDimension) {
        if (antenna == null || antenna.getLevel() == null
                || !antenna.getLevel().dimension().equals(antennaDimension)) return false;
        if (antenna.dimensional()) return true;
        if (!player.level().dimension().equals(antennaDimension)) return false;
        if (antenna.infinite()) return true;
        BlockPos antennaPos = antenna.getBlockPos();
        double range = antenna.range();
        return player.distanceToSqr(antennaPos.getX() + 0.5D, antennaPos.getY() + 0.5D,
                antennaPos.getZ() + 0.5D) <= range * range;
    }

    /** Owner-only configuration fallback until the original TAKE/BUILD ACL is ported. */
    public static boolean canConfigure(Player player, SccNetworkAntennaBlockEntity antenna) {
        return player != null && antenna != null && antenna.placedBy() != null
                && player.getUUID().equals(antenna.placedBy());
    }

    /** Owner-only transmitter configuration fallback until the original ACL is ported. */
    public static boolean canConfigure(Player player, SccWirelessTransmitterBlockEntity transmitter) {
        return player != null && transmitter != null && transmitter.owner() != null
                && player.getUUID().equals(transmitter.owner());
    }

    /** Owner-only receiver configuration fallback until the original ACL is ported. */
    public static boolean canConfigure(Player player, SccWirelessReceiverBlockEntity receiver) {
        return player != null && receiver != null && receiver.owner() != null
                && player.getUUID().equals(receiver.owner());
    }

    public static boolean ownerCanAccess(ServerPlayer player, BlockPos pos, ResourceKey<Level> dimension) {
        if (player == null || pos == null || dimension == null || player.getServer() == null) return false;
        ServerLevel target = player.getServer().getLevel(dimension);
        if (target == null || !target.hasChunkAt(pos)
                || !(target.getBlockEntity(pos) instanceof SccNetworkAntennaBlockEntity antenna)) return false;
        return player.getUUID().equals(antenna.placedBy());
    }

    /**
     * Current Fabric fallback is owner-only. The original SCC TAKE ACL cannot be
     * reproduced until its network controller/security subsystem is ported.
     */
    public static boolean allowsAccess(ServerPlayer player, BlockPos pos, ResourceKey<Level> dimension) {
        if (!hasLinkedTerminal(player, pos, dimension) || player.getServer() == null) return false;
        ServerLevel target = player.getServer().getLevel(dimension);
        if (target == null || !target.hasChunkAt(pos)
                || !(target.getBlockEntity(pos) instanceof SccNetworkAntennaBlockEntity antenna)) return false;
        return player.getUUID().equals(antenna.placedBy()) && inRange(player, antenna, dimension);
    }

    public static ServerLevel networkLevel(ServerPlayer player, BlockPos pos) {
        if (player.containerMenu instanceof SccTerminalMenu menu
                && menu.wireless() && !pos.equals(menu.pos())) {
            return player.serverLevel();
        }
        if (player.containerMenu instanceof SccTerminalMenu menu
                && menu.wireless() && menu.pos().equals(pos) && menu.remoteDimension() != null) {
            return player.getServer().getLevel(menu.remoteDimension());
        }
        return player.serverLevel();
    }

    public static boolean activeSession(ServerPlayer player, BlockPos pos, ServerLevel level) {
        return player.containerMenu instanceof SccTerminalMenu menu
                && menu.wireless() && menu.pos().equals(pos)
                && menu.remoteDimension() != null && menu.remoteDimension().equals(level.dimension());
    }

    public static boolean allowsAccess(ServerPlayer player, BlockPos pos, ServerLevel level) {
        return allowsAccess(player, pos, level.dimension());
    }

    public static boolean open(ServerPlayer player, ItemStack stack) {
        boolean crafting = stack != null && stack.getItem() instanceof WirelessTerminalItem terminal && terminal.crafting();
        return open(player, stack, crafting);
    }

    /** Fecha uma sessão remota de tipo diferente antes de abrir o terminal solicitado. */
    public static void closeMismatchedWirelessSession(ServerPlayer player, boolean crafting) {
        if (player.containerMenu instanceof SccTerminalMenu menu && menu.wireless()
                && menu.wirelessCrafting() != crafting) {
            player.doCloseContainer();
        }
        if (player.containerMenu instanceof SccCraftingTerminalMenu menu && menu.wireless()
                && menu.wirelessCrafting() != crafting) {
            player.doCloseContainer();
        }
    }

    public static boolean open(ServerPlayer player, ItemStack stack, boolean crafting) {
        closeMismatchedWirelessSession(player, crafting);
        BlockPos pos = linkedPos(stack);
        ResourceKey<Level> dimension = linkedDim(stack);
        if (pos == null || dimension == null) {
            player.displayClientMessage(Component.translatable("scc.msg.wireless.unlinked"), true);
            return false;
        }
        SccNetworkAntennaBlockEntity antenna = antenna(player, stack);
        if (antenna == null) {
            player.displayClientMessage(Component.translatable("scc.msg.wireless.noantenna"), true);
            return false;
        }
        if (!inRange(player, antenna, dimension)) {
            player.displayClientMessage(Component.translatable("scc.msg.wireless.outofrange", antenna.range()), true);
            return false;
        }
        if (!allowsAccess(player, pos, dimension)) {
            player.displayClientMessage(Component.translatable("scc.msg.wireless.permission"), true);
            return false;
        }
        if (player.containerMenu instanceof SccTerminalMenu current && current.wireless()
                && current.wirelessCrafting() == crafting
                && pos.equals(current.pos()) && dimension.equals(current.remoteDimension())) return true;

        antenna.refresh();
        TerminalMenuOpenData data = new TerminalMenuOpenData(pos, dimension, true, crafting);
        if (crafting) {
            PlatformMenuHelper.openExtendedMenu(player,
                    new SimpleMenuProvider((id, inventory, user) ->
                            new SccCraftingTerminalMenu(id, inventory, data),
                            Component.translatable("scc.gui.crafting_terminal.title")), data);
        } else {
            PlatformMenuHelper.openExtendedMenu(player,
                    new SimpleMenuProvider((id, inventory, user) ->
                            new SccTerminalMenu(id, inventory, data),
                            Component.translatable("scc.gui.terminal.title")), data);
        }
        return true;
    }

    public static boolean openFromInventory(ServerPlayer player) {
        ItemStack terminal = findTerminal(player);
        if (terminal.isEmpty()) {
            player.displayClientMessage(Component.translatable("scc.msg.wireless.none"), true);
            return false;
        }
        return open(player, terminal);
    }

    public static boolean stillValid(Player player, BlockPos pos,
                                      @Nullable ResourceKey<Level> dimension, boolean crafting) {
        if (!(player instanceof ServerPlayer serverPlayer) || dimension == null) return false;
        boolean sessionMatches;
        if (crafting && serverPlayer.containerMenu instanceof SccCraftingTerminalMenu menu) {
            sessionMatches = menu.wireless() && menu.wirelessCrafting()
                    && pos.equals(menu.pos()) && dimension.equals(menu.remoteDimension());
        } else if (!crafting && serverPlayer.containerMenu instanceof SccTerminalMenu menu) {
            sessionMatches = menu.wireless() && !menu.wirelessCrafting()
                    && pos.equals(menu.pos()) && dimension.equals(menu.remoteDimension());
        } else {
            return false;
        }
        if (!sessionMatches) return false;
        ItemStack matching = findTerminal(serverPlayer, crafting);
        if (matching.isEmpty() || !pos.equals(linkedPos(matching))
                || !dimension.equals(linkedDim(matching))) return false;
        return allowsAccess(serverPlayer, pos, dimension);
    }
}
