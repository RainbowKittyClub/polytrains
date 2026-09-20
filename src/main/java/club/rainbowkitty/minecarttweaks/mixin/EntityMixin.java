package club.rainbowkitty.minecarttweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.collision.CartInteraction;
import club.rainbowkitty.minecarttweaks.link.LinkAction;
import club.rainbowkitty.minecarttweaks.train.Train;
import club.rainbowkitty.minecarttweaks.train.TrainCoupling;

/** Severs a destroyed cart's links, and thins out shoves aimed at a train. */
@Mixin(Entity.class)
public abstract class EntityMixin {

    // Set to have the entity's movement sent to clients this tick.
    @Shadow public boolean needsSync;

    // AbstractMinecart does not override remove, so this has to sit on Entity and filter.
    @Inject(method = "remove", at = @At("HEAD"))
    private void minecarttweaks$remove(Entity.RemovalReason reason, CallbackInfo info) {
        if (!((Object) this instanceof AbstractMinecart cart)) {
            return;
        }

        // Unloading does not sever, so a train held across ticks outlives a cart on its way out of
        // the world. Only a linked cart appears in anyone else's train, so an unlinked one has
        // nothing to tell.
        if (cart.hasLink()) {
            Train.invalidate(cart);
        }

        if (reason.shouldDestroy() && cart.level() instanceof ServerLevel serverLevel) {
            // Only a killed cart drops its chains, which is the path a broken one takes. A cart
            // discarded outright — creative, or the old cart of an upgrade — is severed silently.
            LinkAction.severLinks(
                    serverLevel, cart, reason == Entity.RemovalReason.KILLED);
        }
    }

    // The other way in: NewMinecartBehavior's sweep calls entity.push(cart), but only on a tick the
    // cart collided, so a standing train never reaches here. Gated all the same, so the resolver
    // governs both directions; the reaction itself lives on the one that actually fires.
    @Inject(method = "push(Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void minecarttweaks$shovedByCart(Entity other, CallbackInfo info) {
        if (other instanceof AbstractMinecart cart
                && !CartInteraction.pushes(cart, (Entity) (Object) this)) {
            info.cancel();
        }
    }

    // Every shove in the game funnels through this one method, so a train is made heavy here rather
    // than at each of them: one over the mass is the share of a push the train is owed.
    //
    // Only a car the placer is holding. One it is not is an ordinary cart, and a shove on it is
    // that cart's own — vanilla's shunt between two of them arrives here as well, and routing it
    // to the leading car would leave the car it was meant for standing.
    @Inject(method = "push(DDD)V", at = @At("HEAD"), cancellable = true)
    private void minecarttweaks$shoveTrain(
            double xa, double ya, double za, CallbackInfo info) {
        if (!((Object) this instanceof AbstractMinecart cart)
                || !TrainCoupling.carried(cart)) {
            return;
        }

        info.cancel();

        if (!TrainCoupling.shovable(cart)) {
            return;
        }

        double share = 1.0 / TrainCoupling.mass(cart);

        // Onto the car vanilla drives rather than the one leaned on: a placed car's movement is
        // written by the placer, so a shove left on it is overwritten before it moves anything. The
        // two cars only point the same way while the track between them is straight, so what
        // carries over is the push along the rails rather than the push through the world.
        AbstractMinecart driven = TrainCoupling.driven(cart);
        Vec3 push = TrainCoupling.alongTrack(cart, driven, new Vec3(xa, ya, za));

        driven.setDeltaMovement(driven.getDeltaMovement()
                .add(push.x() * share, push.y() * share, push.z() * share));

        if (driven == (Object) this) {
            needsSync = true;
        }
    }
}
