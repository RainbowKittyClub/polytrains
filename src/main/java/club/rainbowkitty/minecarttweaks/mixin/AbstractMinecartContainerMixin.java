package club.rainbowkitty.minecarttweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.rail.RailForces;
import club.rainbowkitty.minecarttweaks.train.TrainCoupling;

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

    // The same retarding law the other carts take, asked for here because the override above does
    // not reach it. Which law that is belongs to the model: the vanilla one leaves vanilla's own
    // figure alone, so an emptier container cart goes on rolling better, and the physical one
    // replaces it with a law that answers to what the cart is carrying instead.
    @Inject(method = "applyNaturalSlowdown", at = @At("RETURN"), cancellable = true)
    private void minecarttweaks$railFriction(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        AbstractMinecart self = (AbstractMinecart) (Object) this;

        // The train's drive rides along here too, for the same reason: a chest cart at the head of
        // a furnace train is the only car that can carry it, and this override is the only
        // slowdown it ever reaches. Resistance first, then the drive.
        cir.setReturnValue(TrainCoupling.hauled(self,
                RailForces.of(self.level()).retarded(self, movement, cir.getReturnValue())));
    }
}
