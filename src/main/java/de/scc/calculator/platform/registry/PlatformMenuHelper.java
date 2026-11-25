package de.scc.calculator.platform.registry;

import net.fabricmc.fabric.api.screenhandler.v1.ExtendedScreenHandlerFactory;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import org.jetbrains.annotations.Nullable;

/**
 * Substitui serverPlayer.openMenu(provider, pos) e serverPlayer.openMenu(provider, buf -> ...) do NeoForge.
 */
public final class PlatformMenuHelper {

    private PlatformMenuHelper() {
    }

    public static void openBlockMenu(ServerPlayer player, MenuProvider delegate, BlockPos pos) {
        player.openMenu(new ExtendedScreenHandlerFactory<BlockPos>() {
            @Override
            public BlockPos getScreenOpeningData(ServerPlayer p) {
                return pos;
            }

            @Override
            public Component getDisplayName() {
                return delegate.getDisplayName();
            }

            @Nullable
            @Override
            public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player p) {
                return delegate.createMenu(syncId, playerInventory, p);
            }
        });
    }

    public static <D> void openExtendedMenu(ServerPlayer player, MenuProvider delegate, D openingData) {
        player.openMenu(new ExtendedScreenHandlerFactory<D>() {
            @Override
            public D getScreenOpeningData(ServerPlayer p) {
                return openingData;
            }

            @Override
            public Component getDisplayName() {
                return delegate.getDisplayName();
            }

            @Nullable
            @Override
            public AbstractContainerMenu createMenu(int syncId, Inventory playerInventory, Player p) {
                return delegate.createMenu(syncId, playerInventory, p);
            }
        });
    }
}
