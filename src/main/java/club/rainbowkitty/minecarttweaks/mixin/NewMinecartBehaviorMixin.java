package club.rainbowkitty.minecarttweaks.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.collision.CartInteraction;
import club.rainbowkitty.minecarttweaks.rail.SlopeStep;
import club.rainbowkitty.minecarttweaks.train.TrainCoupling;

/**
 * Holds vanilla's pickup to the speed the resolver allows one at, its track forces to the train
 * rather than to whichever car happens to be leading it, and a car stepping along a slope to its
 * rail.
 */
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

    // Vanilla pulls one cart downhill by one cart's worth of gravity, right while every car of a
    // train reached this for itself. Only the leading car does now, so the pull is worked
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

    // The brake reaches one car of a train for the same reason gravity did, so a rail under a
    // trailing car held nothing and a train could be towed off one by its far end. Read over the
    // whole train instead — TrainCoupling.braked shares the retardation and holds outright.
    @Inject(method = "calculateHaltTrackSpeed", at = @At("RETURN"), cancellable = true)
    private void minecarttweaks$sharedHalt(
            Vec3 movement, BlockState state, CallbackInfoReturnable<Vec3> cir) {
        Vec3 braked = TrainCoupling.braked(minecarttweaks$cart(), movement);

        if (braked != null) {
            cir.setReturnValue(braked);
        }
    }

    // The same again for the other half of a powered rail, which drove nothing at all under a
    // trailing car. Answered from the raw movement rather than from vanilla's own return, because
    // the train's share supersedes it whether or not the leading car is over a rail itself.
    @Inject(method = "calculateBoostTrackSpeed", at = @At("RETURN"), cancellable = true)
    private void minecarttweaks$sharedBoost(
            Vec3 movement, BlockPos pos, BlockState state, CallbackInfoReturnable<Vec3> cir) {
        Vec3 boosted = TrainCoupling.boostPull(minecarttweaks$cart(), movement);

        if (boosted != null) {
            cir.setReturnValue(boosted);
        }
    }

    // The out position is raised a block for a car read as climbing, so a movement square to the
    // slope that is sent down it aims a block above the low end; see SlopeStep.squareDownSlope.
    @WrapOperation(
            method = "stepAlongTrack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/vehicle/minecart/NewMinecartBehavior;"
                            + "isDecending(Lnet/minecraft/world/phys/Vec3;"
                            + "Lnet/minecraft/world/level/block/state/properties/RailShape;)Z",
                    ordinal = 0))
    private boolean minecarttweaks$squareDownSlope(NewMinecartBehavior behavior, Vec3 movement,
            RailShape shape, Operation<Boolean> isDecending) {
        return isDecending.call(behavior, movement, shape)
                || SlopeStep.squareDownSlope(movement, shape);
    }

    // A slope step that meets another cart can leave its car above the rail, and the stepper only
    // corrects a car below it; SlopeStep puts it back where the step first touched.
    @WrapOperation(
            method = "stepAlongTrack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;"
                            + "move(Lnet/minecraft/world/entity/MoverType;"
                            + "Lnet/minecraft/world/phys/Vec3;)V"))
    private void minecarttweaks$moveOnRail(AbstractMinecart cart, MoverType moverType, Vec3 step,
            Operation<Void> move, @Local(argsOnly = true) BlockPos railPos,
            @Local(argsOnly = true) RailShape shape) {
        SlopeStep.moveOnRail(cart, step, railPos, shape, () -> move.call(cart, moverType, step));
    }
}
