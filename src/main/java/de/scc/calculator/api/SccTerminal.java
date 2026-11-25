package de.scc.calculator.api;

import de.scc.calculator.calc.StackKey;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Server-safe extension points for entries displayed by an SCC terminal.
 *
 * <p>Unlike the original NeoForge API, this common-side contract does not
 * reference Minecraft client Screen classes. Client UI adapters can provide
 * their own screen bridge without making dedicated servers load client types.</p>
 */
public final class SccTerminal {
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();

    private SccTerminal() { }

    /** Register an entry once by namespaced ID; duplicate IDs replace nothing. */
    public static synchronized void addEntry(Entry entry) {
        Objects.requireNonNull(entry, "entry");
        String id = normalizeId(entry.id());
        if (!id.equals(entry.id())) throw new IllegalArgumentException("Terminal entry ID must be normalized: " + entry.id());
        if (entry.glyph() == null || entry.glyph().isBlank() || entry.glyph().length() > 8) {
            throw new IllegalArgumentException("Terminal glyph must contain 1..8 characters");
        }
        Objects.requireNonNull(entry.title(), "title supplier");
        Objects.requireNonNull(entry.help(), "help supplier");
        Objects.requireNonNull(entry.open(), "open callback");
        ENTRIES.putIfAbsent(id, entry);
    }

    /** Stable immutable snapshot safe to enumerate while other addons register entries. */
    public static synchronized List<Entry> entries() {
        return List.copyOf(ENTRIES.values());
    }

    /** Count one component-sensitive item variant in a terminal handle's stock snapshot. */
    public static long count(Handle handle, ItemStack stack) {
        if (handle == null || stack == null || stack.isEmpty()) return 0L;
        StackKey key = StackKey.of(stack);
        if (key == null) return 0L;
        Long amount = handle.stock().get(key);
        return amount == null ? 0L : Math.max(0L, amount);
    }

    private static String normalizeId(String value) {
        if (value == null || value.length() > 192) throw new IllegalArgumentException("Invalid terminal entry ID");
        String id = value.trim();
        int separator = id.indexOf(':');
        if (separator <= 0 || separator == id.length() - 1 || id.indexOf(':', separator + 1) >= 0) {
            throw new IllegalArgumentException("Terminal entry ID must be namespaced: " + value);
        }
        String namespace = id.substring(0, separator);
        String path = id.substring(separator + 1);
        if (!namespace.matches("[a-z0-9_.-]+") || !path.matches("[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("Invalid terminal entry ID: " + value);
        }
        return id;
    }

    public record Entry(String id, String glyph, Supplier<Component> title,
                        Supplier<Component> help, Consumer<Handle> open) { }

    /** Narrow server-safe contract for a terminal session. */
    public interface Handle {
        BlockPos terminalPos();
        Map<StackKey, Long> stock();
        boolean order(StackKey item, long amount, boolean simulate);
        boolean craftable(StackKey item);
        void back();
    }
}
