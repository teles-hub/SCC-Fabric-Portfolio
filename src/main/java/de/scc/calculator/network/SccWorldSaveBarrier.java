package de.scc.calculator.network;

import net.minecraft.server.MinecraftServer;

/**
 * Explicit synchronous Minecraft save boundary for SCC transaction phases.
 *
 * <p>This asks Minecraft to save all dimensions, force saving even when a level
 * has saving disabled, and flush pending chunk writes before returning. It is
 * a game-level save barrier, not a hardware fsync guarantee and not an atomic
 * transaction across filesystems, chunks, and third-party machine inventories.</p>
 */
public final class SccWorldSaveBarrier {
    private SccWorldSaveBarrier() {
    }

    /**
     * Saves the complete server world with flush=true and force=true.
     * Must be called on the server thread; false means the caller must stop the
     * transaction and must not dispatch an external machine operation.
     */
    public static boolean saveAllNow(MinecraftServer server) {
        if (server == null || !server.isSameThread()) return false;
        return server.saveAllChunks(true, true, true);
    }
}
