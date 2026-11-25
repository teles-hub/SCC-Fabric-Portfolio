package de.scc.calculator.hooks;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** Hook nao-combativo para ajuste da velocidade de quebra de blocos. */
public final class SccEventHooks {

    @FunctionalInterface
    public interface BreakSpeed {
        float modify(Player player, BlockState state, float speed);
    }

    private static final List<BreakSpeed> BREAK = new CopyOnWriteArrayList<>();

    private SccEventHooks() {
    }

    public static void registerBreakSpeed(BreakSpeed hook) {
        if (hook != null) BREAK.add(hook);
    }

    public static float onBreakSpeed(Player player, BlockState state, float speed) {
        float result = speed;
        for (BreakSpeed hook : BREAK) result = hook.modify(player, state, result);
        return result;
    }
}
