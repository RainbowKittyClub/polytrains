package club.rainbowkitty.serversidetrains.mixin;

import java.util.List;

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

import club.rainbowkitty.serversidetrains.block.SwitchedRailBlock;
import club.rainbowkitty.serversidetrains.collision.CartImpact;
import club.rainbowkitty.serversidetrains.collision.CartInteraction;
import club.rainbowkitty.serversidetrains.collision.OffRails;
import club.rainbowkitty.serversidetrains.rail.SlopeStep;
import club.rainbowkitty.serversidetrains.train.TrainCoupling;

/**
 * Holds vanilla's pickup to the speed the resolver allows one at, its track forces to the train
 * rather than to whichever car happens to be leading it, a car stepping into another cart to its
 * rail, and a car set back onto its rails or lifted onto a slope short of another train's car.
 */
@Mixin(NewMinecartBehavior.class)
public abstract class NewMinecartBehaviorMixin implements MinecartBehaviorAccessor {

    // Vanilla picks up on every move, whether or not the cart collided with anything, so a cart
    // barrelling into a mob would scoop it aboard rather than run it down no matter what the
    // resolver decided. This is the one place that can stop it.
    @Inject(method = "pickupEntities", at = @At("HEAD"), cancellable = true)
    private void serversidetrains$onlyCollectSlowly(
            AABB hitbox, CallbackInfoReturnable<Boolean> cir) {
        if (!CartInteraction.gathers(serversidetrains$cart())) {
            cir.setReturnValue(false);
        }
    }

    // Vanilla pulls one cart downhill by one cart's worth of gravity, right while every car of a
    // train reached this for itself. Only the leading car does now, so the pull is worked
    // out for the whole train instead — see TrainCoupling.slopePull, which also answers where
    // vanilla found no slope at all because that one car had reached the level.
    @Inject(method = "calculateSlopeSpeed", at = @At("RETURN"), cancellable = true)
    private void serversidetrains$sharedSlope(
            Vec3 movement, RailShape shape, CallbackInfoReturnable<Vec3> cir) {
        AbstractMinecart cart = serversidetrains$cart();
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
    private void serversidetrains$sharedHalt(
            Vec3 movement, BlockState state, CallbackInfoReturnable<Vec3> cir) {
        Vec3 braked = TrainCoupling.braked(serversidetrains$cart(), movement);

        if (braked != null) {
            cir.setReturnValue(braked);
        }
    }

    // The same again for the other half of a powered rail, which drove nothing at all under a
    // trailing car. Answered from the raw movement rather than from vanilla's own return, because
    // the train's share supersedes it whether or not the leading car is over a rail itself.
    @Inject(method = "calculateBoostTrackSpeed", at = @At("RETURN"), cancellable = true)
    private void serversidetrains$sharedBoost(
            Vec3 movement, BlockPos pos, BlockState state, CallbackInfoReturnable<Vec3> cir) {
        Vec3 boosted = TrainCoupling.boostPull(serversidetrains$cart(), movement);

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
    private boolean serversidetrains$squareDownSlope(NewMinecartBehavior behavior, Vec3 movement,
            RailShape shape, Operation<Boolean> isDecending) {
        return isDecending.call(behavior, movement, shape)
                || SlopeStep.squareDownSlope(movement, shape);
    }

    // Vanilla sets a car down onto a rail it has come over wherever the rail is, whatever stands
    // on it. Cut short at another train's car, the car is marked off its rails, so the set-down is
    // tried again on the next pass until the rail is clear; meanwhile vanilla steps it along the
    // rail from where it stands, as it does a car riding a roof.
    @WrapOperation(
            method = {"tick", "moveAlongTrack"},
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/vehicle/minecart/NewMinecartBehavior;"
                            + "adjustToRails(Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;Z)V"),
            require = 2)
    private void serversidetrains$setDownShort(NewMinecartBehavior behavior, BlockPos railPos,
            BlockState state, boolean instant, Operation<Void> adjustToRails) {
        AbstractMinecart cart = serversidetrains$cart();
        Vec3 fromPosition = cart.position();
        AABB fromBox = cart.getBoundingBox();
        int stepCount = behavior.lerpSteps.size();

        adjustToRails.call(behavior, railPos, state, instant);

        Vec3 setDown = cart.position().subtract(fromPosition);

        if (setDown.lengthSqr() == 0.0) {
            return;
        }

        Vec3 allowed = OffRails.shortOfCars(cart, fromBox, setDown);

        if (allowed.equals(setDown)) {
            return;
        }

        cart.setPos(fromPosition.add(allowed));
        cart.setOnRails(false);

        // The lerp step the set-down added leads the client inside; it is drawn the cut instead.
        List<NewMinecartBehavior.MinecartStep> steps = behavior.lerpSteps;
        double cutDistance = allowed.length();

        steps.subList(stepCount, steps.size()).clear();

        if (cutDistance > 0.0) {
            steps.add(new NewMinecartBehavior.MinecartStep(cart.position(),
                    cart.getDeltaMovement(), cart.getYRot(), cart.getXRot(),
                    instant ? 0.0F : (float) cutDistance));
        }
    }

    // A step that meets another cart can leave its car off its line, lifted off a slope or slid
    // off a corner; SlopeStep puts it back where the step first touched. A step deeper into a
    // cart the car is already inside is not taken at all, nor one into a switch closed against it.
    @WrapOperation(
            method = "stepAlongTrack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;"
                            + "move(Lnet/minecraft/world/entity/MoverType;"
                            + "Lnet/minecraft/world/phys/Vec3;)V"))
    private void serversidetrains$moveOnRail(AbstractMinecart cart, MoverType moverType, Vec3 step,
            Operation<Void> move, @Local(argsOnly = true) BlockPos railPos,
            @Local(argsOnly = true) RailShape shape) {
        Vec3 openStep = SwitchedRailBlock.shortOfClosed(cart, railPos, shape, step);

        SlopeStep.moveOnRail(cart, openStep, railPos, shape, () -> {
            if (!CartImpact.deepens(cart, openStep)) {
                move.call(cart, moverType, openStep);
            }
        });
    }

    // After the move vanilla lifts a car it finds below a slope's line straight up onto it, through
    // whatever stands above. Cut at another train's car, it is lifted again on its next step.
    @WrapOperation(
            method = "stepAlongTrack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/vehicle/minecart/NewMinecartBehavior;"
                            + "setPos(DDD)V"))
    private void serversidetrains$liftShort(NewMinecartBehavior behavior, double x, double y,
            double z, Operation<Void> setPos) {
        AbstractMinecart cart = serversidetrains$cart();
        Vec3 from = cart.position();
        Vec3 lift = new Vec3(x, y, z).subtract(from);
        Vec3 allowed = OffRails.shortOfCars(cart, cart.getBoundingBox(), lift);
        Vec3 to = allowed.equals(lift) ? new Vec3(x, y, z) : from.add(allowed);

        setPos.call(behavior, to.x(), to.y(), to.z());
    }
}
