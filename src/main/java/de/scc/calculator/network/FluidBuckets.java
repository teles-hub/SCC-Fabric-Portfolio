package de.scc.calculator.network;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.material.Fluid;

/** Registry-backed fluid identifier bridge used by the calculator's synthetic fluid keys. */
public final class FluidBuckets {
    public static final int BUCKET = 1000;

    private FluidBuckets() { }

    public static Fluid parse(String rawId) {
        if (rawId == null || rawId.isBlank()) return null;
        try {
            ResourceLocation id = ResourceLocation.parse(rawId);
            return BuiltInRegistries.FLUID.getOptional(id).orElse(null);
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
