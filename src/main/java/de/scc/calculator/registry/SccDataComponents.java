package de.scc.calculator.registry;

import com.mojang.serialization.Codec;
import de.scc.calculator.platform.registry.PlatformRegistry;
import de.scc.calculator.platform.registry.SccHolder;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/**
 * Data components REAIS do mod (jar 1.21.1): FACADE_BLOCK (ResourceLocation), WRENCH_MODE (ResourceLocation),
 * SUBSTANCE_ID (String), BRUSH_DESIGN (String).
 * CORRECAO: os componentes inventados (energy, network_id, bound_pos, tool_mode, active) foram removidos.
 * Os IDs de registro e codecs foram conferidos no bytecode de `de.scc.calculator.network.SccRegistry`:
 * `facade_block`, `wrench_mode`, `substance` e `brush_design`; os dois primeiros usam
 * `ResourceLocation.CODEC`/`ResourceLocation.STREAM_CODEC`, e os dois últimos usam
 * `Codec.STRING`/`ByteBufCodecs.STRING_UTF8`.
 */
public final class SccDataComponents {

    private SccDataComponents() {
    }

    public static final SccHolder<DataComponentType<ResourceLocation>> FACADE_BLOCK =
            PlatformRegistry.registerDataComponent("facade_block", b ->
                    b.persistent(ResourceLocation.CODEC).networkSynchronized(ResourceLocation.STREAM_CODEC));

    public static final SccHolder<DataComponentType<ResourceLocation>> WRENCH_MODE =
            PlatformRegistry.registerDataComponent("wrench_mode", b ->
                    b.persistent(ResourceLocation.CODEC).networkSynchronized(ResourceLocation.STREAM_CODEC));

    public static final SccHolder<DataComponentType<String>> SUBSTANCE_ID =
            PlatformRegistry.registerDataComponent("substance", b ->
                    b.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));

    public static final SccHolder<DataComponentType<String>> BRUSH_DESIGN =
            PlatformRegistry.registerDataComponent("brush_design", b ->
                    b.persistent(Codec.STRING).networkSynchronized(ByteBufCodecs.STRING_UTF8));

    private static final List<SccHolder<?>> ALL = List.of(FACADE_BLOCK, WRENCH_MODE, SUBSTANCE_ID, BRUSH_DESIGN);

    /** Força o carregamento estático durante onInitialize(). */
    public static void init() { }

    public static int registeredCount() { return ALL.size(); }

    public static boolean allBound() { return ALL.stream().allMatch(SccHolder::isBound); }
}
