package de.scc.calculator.registry;

import de.scc.calculator.platform.registry.PlatformRegistry;
import de.scc.calculator.platform.registry.SccHolder;
import net.minecraft.sounds.SoundEvent;

import java.util.List;

/**
 * Sons pertencentes ao núcleo não-combativo de logística e interface.
 *
 * <p>Não registra efeitos de armas, projéteis, combate, gravidade ou máquinas
 * estelares que não fazem parte deste port. Cada ID abaixo possui uma entrada
 * correspondente em assets/scc/sounds.json e um arquivo OGG.</p>
 */
public final class SccSounds {
    public static final SccHolder<SoundEvent> NETWORK_HUM = register("network_hum");
    public static final SccHolder<SoundEvent> ORDER_PLACED = register("order_placed");
    public static final SccHolder<SoundEvent> JOB_DONE = register("job_done");
    public static final SccHolder<SoundEvent> JOB_STUCK = register("job_stuck");
    public static final SccHolder<SoundEvent> TERMINAL_OPEN = register("terminal_open");
    public static final SccHolder<SoundEvent> TERMINAL_CLOSE = register("terminal_close");
    public static final SccHolder<SoundEvent> UI_CLICK = register("ui_click");
    public static final SccHolder<SoundEvent> ITEM_STORED = register("item_stored");
    public static final SccHolder<SoundEvent> ITEM_TAKEN = register("item_taken");
    public static final SccHolder<SoundEvent> WIRELESS_LINK = register("wireless_link");

    private static final List<SccHolder<SoundEvent>> ALL = List.of(
            NETWORK_HUM, ORDER_PLACED, JOB_DONE, JOB_STUCK, TERMINAL_OPEN,
            TERMINAL_CLOSE, UI_CLICK, ITEM_STORED, ITEM_TAKEN, WIRELESS_LINK
    );

    private SccSounds() { }

    private static SccHolder<SoundEvent> register(String id) {
        return PlatformRegistry.registerSound(id);
    }

    /** Força a inicialização estática dos SoundEvents. */
    public static void init() { }

    public static int registeredCount() {
        return ALL.size();
    }

    public static boolean allBound() {
        return ALL.stream().allMatch(SccHolder::isBound);
    }

    public static List<SccHolder<SoundEvent>> all() {
        return ALL;
    }
}
