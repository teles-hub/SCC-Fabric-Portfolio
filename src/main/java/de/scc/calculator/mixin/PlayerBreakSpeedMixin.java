package de.scc.calculator.mixin;

import de.scc.calculator.hooks.SccEventHooks;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Substitui PlayerEvent.BreakSpeed (sem BlockPos). */
@Mixin(Player.class)
public abstract class PlayerBreakSpeedMixin {

    @Inject(method = "getDestroySpeed(Lnet/minecraft/world/level/block/state/BlockState;)F",
            at = @At("RETURN"), cancellable = true, require = 1)
    private void scc$onBreakSpeed(BlockState state, CallbackInfoReturnable<Float> cir) {
        float original = cir.getReturnValue();
        float modified = SccEventHooks.onBreakSpeed((Player) (Object) this, state, original);
        if (modified != original) {
            cir.setReturnValue(modified);
        }
    }
}
