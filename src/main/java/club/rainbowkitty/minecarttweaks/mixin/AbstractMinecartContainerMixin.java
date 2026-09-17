package club.rainbowkitty.minecarttweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.rail.RailDrag;

/**
 * A chest or hopper cart's share of this mod's rolling friction.
 *
 * <p>Vanilla gives a container cart its own slowdown and returns from it without calling the one
 * on {@code AbstractMinecart}, where this mod hangs its own. So a container cart got none of it:
 * no low-speed drag and, worse, no floor to stop it, leaving it to coast down through a fixed
 * fraction a tick for a couple of thousand ticks before vanilla's own cutoff caught it.
 */
@Mixin(AbstractMinecartContainer.class)
public abstract class AbstractMinecartContainerMixin {

    // The same friction the other carts take, asked for here because the override above does not
    // reach it. Vanilla's own figure is left as it is: an emptier container cart rolls better by
    // design, and this only decides how the last of that roll is spent.
    @Inject(method = "applyNaturalSlowdown", at = @At("RETURN"), cancellable = true)
    private void minecarttweaks$railFriction(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        cir.setReturnValue(
                RailDrag.slowed((AbstractMinecart) (Object) this, cir.getReturnValue()));
    }
}
