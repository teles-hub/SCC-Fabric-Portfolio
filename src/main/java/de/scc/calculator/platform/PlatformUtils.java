package de.scc.calculator.platform;

import net.fabricmc.api.EnvType;
import net.fabricmc.fabric.api.entity.FakePlayer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

import java.nio.file.Path;

/**
 * Utilitários de ambiente para substituir chamadas diretas a ModList, FMLEnvironment e FakePlayerFactory.
 */
public final class PlatformUtils {

    private PlatformUtils() {
    }

    public static boolean isModLoaded(String modId) {
        return FabricLoader.getInstance().isModLoaded(modId);
    }

    public static boolean isClient() {
        return FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT;
    }

    public static Path getConfigDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    /**
     * Substitui FakePlayerFactory.getMinecraft(serverLevel) usado em blocos de automação/quebra/uso.
     */
    public static ServerPlayer getFakePlayer(ServerLevel level) {
        return FakePlayer.get(level);
    }
}
