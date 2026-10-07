package club.rainbowkitty.polytrains.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;

import club.rainbowkitty.polytrains.train.TrainCoupling;

/** Holding a train's position updates back so its cars are replayed on one beat. */
@Mixin(ServerEntity.class)
public abstract class ServerEntityMixin {

    // The entity this tracker sends for.
    @Shadow @Final private Entity entity;

    // Ticks this tracker has run for, which decides the ticks it sends on.
    @Shadow private int tickCount;

    // Sends the cart's batch of position and rotation steps.
    @Shadow
    private void handleMinecartPosRot(
            NewMinecartBehavior behavior, byte yRot, byte xRot, boolean sendRotation) {
        throw new AssertionError();
    }

    // A tracker sends on every updateInterval'th tick of its own life, so two carts whose tracking
    // began on different ticks are heard on different ticks for as long as they both exist. Dating
    // the release from the world's clock instead lands the whole train on one of them.
    @Inject(method = "sendChanges", at = @At("HEAD"))
    private void polytrains$releaseResync(CallbackInfo info) {
        if (entity instanceof AbstractMinecart cart
                && cart.polytrains$resyncAt() > 0
                && entity.level().getGameTime() >= cart.polytrains$resyncAt()) {
            cart.polytrains$setResyncAt(0);

            // Zero rather than any multiple of the interval, so a train released across several
            // ticks by a tracker that only just appeared still shares one phase.
            tickCount = 0;
        }
    }

    // Only the cart's own position batch is withheld, rather than the whole of sendChanges: other
    // mods drive per-tick work of their own from this method — the chain visual among them — and
    // cancelling it outright starves them for as long as a train is held.
    @Redirect(
            method = "sendChanges",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/server/level/ServerEntity;handleMinecartPosRot("
                            + "Lnet/minecraft/world/entity/vehicle/minecart/NewMinecartBehavior;"
                            + "BBZ)V"))
    private void polytrains$holdPosition(ServerEntity self, NewMinecartBehavior behavior,
            byte yRot, byte xRot, boolean sendRotation) {
        if (entity instanceof AbstractMinecart cart && cart.polytrains$resyncAt() > 0) {
            return;
        }

        handleMinecartPosRot(behavior, yRot, xRot, sendRotation);
    }

    // A cart tracked from a different tick to the rest of its train is the case this exists for, so
    // the train is put back in step the moment one appears rather than waiting to be noticed.
    @Inject(method = "<init>", at = @At("RETURN"))
    private void polytrains$resyncOnTrack(CallbackInfo info) {
        if (entity instanceof AbstractMinecart cart && cart.hasLink()) {
            TrainCoupling.resyncTrain(cart);
        }
    }
}
