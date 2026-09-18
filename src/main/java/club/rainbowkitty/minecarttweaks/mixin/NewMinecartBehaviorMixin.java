package club.rainbowkitty.minecarttweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.collision.CartInteraction;
import club.rainbowkitty.minecarttweaks.train.TrainCoupling;

/** Holds vanilla's pickup to the speed the resolver allows one at, and its gravity to the train. */
@Mixin(NewMinecartBehavior.class)
public abstract class NewMinecartBehaviorMixin implements MinecartBehaviorAccessor {

    // Vanilla picks up on every move, whether or not the cart collided with anything, so a cart
    // barrelling into a mob would scoop it aboard rather than run it down no matter what the
    // resolver decided. This is the one place that can stop it.
    @Inject(method = "pickupEntities", at = @At("HEAD"), cancellable = true)
    private void minecarttweaks$onlyCollectSlowly(
            AABB hitbox, CallbackInfoReturnable<Boolean> cir) {
        if (!CartInteraction.gathers(minecarttweaks$cart())) {
            cir.setReturnValue(false);
        }
    }

    // Vanilla pulls one cart downhill by one cart's worth of gravity, which was right while every
    // car of a train reached this for itself. Only the leading car does now, so the pull is worked
    // out for the whole train instead — see TrainCoupling.slopePull, which also answers where
    // vanilla found no slope at all because that one car had reached the level.
    @Inject(method = "calculateSlopeSpeed", at = @At("RETURN"), cancellable = true)
    private void minecarttweaks$sharedSlope(
            Vec3 movement, RailShape shape, CallbackInfoReturnable<Vec3> cir) {
        AbstractMinecart cart = minecarttweaks$cart();
        Vec3 pull = TrainCoupling.slopePull(
                cart, movement, cir.getReturnValue().subtract(movement));

        if (pull != null) {
            cir.setReturnValue(movement.add(pull));
        }
    }
}
