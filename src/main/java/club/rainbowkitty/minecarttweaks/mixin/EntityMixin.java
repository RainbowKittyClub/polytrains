package club.rainbowkitty.minecarttweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

import club.rainbowkitty.minecarttweaks.collision.CartInteraction;
import club.rainbowkitty.minecarttweaks.rule.MTGameRules;
import club.rainbowkitty.minecarttweaks.train.Train;
import club.rainbowkitty.minecarttweaks.train.TrainCoupling;
import club.rainbowkitty.minecarttweaks.util.MinecartHelper;

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
            MinecartHelper.severLinks(
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
    // than at each of them. Length is divided out twice and both are meant — here, and again when
    // the train settles to the mean of its cars' speeds; either alone loses out to rail friction.
    @Inject(method = "push(DDD)V", at = @At("HEAD"), cancellable = true)
    private void minecarttweaks$shoveTrain(
            double xa, double ya, double za, CallbackInfo info) {
        if (!((Object) this instanceof AbstractMinecart cart)
                || !MTGameRules.trainsEnabled(cart.level())
                || !cart.hasLink()) {
            return;
        }

        info.cancel();

        if (!TrainCoupling.shovable(cart)) {
            return;
        }

        double share = 1.0 / TrainCoupling.mass(cart);
        cart.setDeltaMovement(
                cart.getDeltaMovement().add(xa * share, ya * share, za * share));
        needsSync = true;
    }
}
