package club.rainbowkitty.minecarttweaks.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.MinecartTweaks;
import club.rainbowkitty.minecarttweaks.ext.AbstractMinecartExt;
import club.rainbowkitty.minecarttweaks.init.MTTicketTypes;
import club.rainbowkitty.minecarttweaks.rail.RailForces;
import club.rainbowkitty.minecarttweaks.rail.RailPath;
import club.rainbowkitty.minecarttweaks.rule.MTGameRules;
import club.rainbowkitty.minecarttweaks.train.Train;
import club.rainbowkitty.minecarttweaks.train.TrainCoupling;
import club.rainbowkitty.minecarttweaks.util.FurnaceFuel;

/** Furnace-cart speed, burning any fuel, the parking brake, and keeping chunks loaded. */
@Mixin(MinecartFurnace.class)
public abstract class MinecartFurnaceMixin extends AbstractMinecart implements AbstractMinecartExt {
    // Chunks held either side of a burning cart, in chunks.
    @Unique
    private static final int CHUNK_TICKET_RADIUS = 3;

    // Below this squared length the impulse is treated as absent, matching vanilla.
    @Unique
    private static final double IMPULSE_EPSILON = 1.0E-7;

    // Below this squared horizontal speed the cart is too slow to read a heading off.
    @Unique
    private static final double MOVING_EPSILON = 1.0E-3;

    // Below this a new heading is a sharper turn than the two exits of one rail can make, so it is
    // something turning the cart round rather than the track bending under it.
    @Unique
    private static final double REVERSAL_DOT = -0.5;

    // Above this an asked-for heading names one end of the rail clearly enough to turn the engine
    // to it. Below, it is square enough across the track to be no answer at all.
    @Unique
    private static final double AIM_DOT = 0.35;

    // One tick of fuel is spent per this many ticks while parked.
    @Unique
    private static final int PARKED_FUEL_INTERVAL = 10;

    // Ticks of no progress under power before the engine turns the cart round. Long enough that a
    // cart just fuelled from rest is not mistaken for one wedged against a buffer — it is moving
    // by its second tick under either model — and short enough to read as a reversal rather than
    // as a cart that got stuck and recovered.
    @Unique
    private static final int STALL_TICKS = 4;

    // Ticks of burn time left.
    @Shadow private int fuel;

    // The heading the cart drives itself along, which this mod keeps normalised.
    @Shadow public Vec3 push;

    // Whether the cart sits on an unpowered powered rail, refreshed at the top of each tick.
    @Unique
    private boolean minecarttweaks$parked;

    // The rail the engine was last aimed on, so a reversal in place can be told from one the track
    // itself makes. Deliberately not saved: a cart reloads holding the heading vanilla wrote.
    @Unique
    private @Nullable BlockPos minecarttweaks$headingRail;

    // Ticks in a row this cart has been given the engine and still covered no ground.
    @Unique
    private int minecarttweaks$stalled;

    // Never called: present only to satisfy the compiler about the superclass this mixin extends.
    protected MinecartFurnaceMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean minecarttweaks$driving() {
        return fuel > 0 && !minecarttweaks$parked;
    }

    // Asked at the speed of the car whose movement is an input, so every engine of a train reads
    // the same figure and their efforts sum — which is how double-heading buys top speed and not
    // just acceleration.
    @Override
    public double minecarttweaks$driveAcceleration() {
        return minecarttweaks$driving()
                ? RailForces.of(level()).engineOutput(TrainCoupling.drivenSpeed(this))
                : 0.0;
    }

    @Override
    public Vec3 minecarttweaks$driveHeading() {
        return minecarttweaks$driving() ? push : Vec3.ZERO;
    }

    @Override
    public void minecarttweaks$aimEngine(Vec3 heading) {
        minecarttweaks$aimAlong(heading);
    }

    @Override
    public void minecarttweaks$turnEngine(Vec3 heading) {
        // Cleared whether or not the heading takes, so that the other engines of a train turning
        // together do not each fire their own turn on the same tick and undo one another.
        minecarttweaks$stalled = 0;

        BlockPos on = getCurrentBlockPosOrRailBelow();
        BlockPos exit = RailPath.ahead(level(), on, heading.horizontal());

        if (exit == null) {
            return;
        }

        push = Vec3.atLowerCornerOf(exit.subtract(on)).horizontal().normalize();
        minecarttweaks$headingRail = on;
    }

    // Vanilla halves a furnace cart's ceiling, which leaves the engine slower than the carts it
    // tows and so makes it the limit on its own train. The physical model undoes that, for a
    // ceiling of max_minecart_speed like every other cart; the vanilla model keeps the halving,
    // which is where its engine's 4 m/s comes from. Raising it past what the world allows anything
    // else is what a rule of its own used to do, and it made that rule a lie.
    @Inject(method = "getMaxSpeed", at = @At("RETURN"), cancellable = true)
    private void minecarttweaks$furnaceSpeed(
            ServerLevel level, CallbackInfoReturnable<Double> cir) {
        // The model first, then the train: a furnace cart's own ceiling is what it lends the train
        // it heads, so the halving has to be settled before the lowest figure is looked for. This
        // is the outermost override for a furnace cart, so the generic hook stands down and both
        // happen here.
        double own = RailForces.of(level).topSpeed(cir.getReturnValueD(), super.getMaxSpeed(level));

        cir.setReturnValue(TrainCoupling.trainCeiling(this, level, own));
    }

    // Vanilla's powered branch decays the cart's own speed and re-adds the impulse each tick, so
    // acceleration is a curve that tails off far short of the speed rule. Rolling friction goes
    // with it — a burning cart is under power, and the caller clamps it to the top speed anyway.
    @Inject(method = "applyNaturalSlowdown", at = @At("HEAD"), cancellable = true)
    private void minecarttweaks$accelerate(
            Vec3 deltaMovement, CallbackInfoReturnable<Vec3> cir) {
        // The brake stops using the impulse rather than clearing it, so the arrival heading
        // survives in vanilla's own PushX/PushZ and is there to leave on. This is vanilla's
        // unpowered branch verbatim; returning early instead would let vanilla re-add the impulse.

        if (minecarttweaks$parked) {
            // Cancelling here means the return hook below never sees this cart, so the retarding
            // law is asked for on the spot. The super call no longer applies one of its own — the
            // generic hook skips a furnace cart precisely so this is the only place it lands.
            cir.setReturnValue(RailForces.of(level()).retarded(this, deltaMovement,
                    super.applyNaturalSlowdown(deltaMovement.multiply(0.98, 0.0, 0.98))));
            return;
        }

        // Without the fuel check a spent cart accelerates forever: vanilla only zeroes the impulse
        // after super.tick() has already moved it, so the heading below would reseed it every tick.
        if (fuel <= 0) {
            return;
        }

        // The direction of travel is the only heading that survives a corner. A coupled cart's is
        // written along its own rail and trustworthy at any speed; a solo cart's needs a floor
        // under it, or the jitter of a near-stopped one decides which way it pushes.
        //
        // Aimed under both models, ahead of the decision about whose force drives the cart.
        // Vanilla aims the impulse by projecting it onto the direction of travel and normalising
        // the result, and the projection onto a perpendicular heading is the zero vector — which
        // normalise() turns into a permanently dead engine, since vanilla then never reaches its
        // own aiming again. A two-block U-turn swings the cart ninety degrees in a single step, so
        // that is ordinary track rather than a corner case. Aiming first makes vanilla's own
        // projection a no-op on a heading already parallel to the movement, which is the whole
        // point: it cannot be degenerate if it has nothing left to do.
        double floor = hasLink() ? IMPULSE_EPSILON : MOVING_EPSILON;

        if (deltaMovement.horizontalDistanceSqr() > floor) {
            minecarttweaks$aimAlong(deltaMovement.horizontal().normalize());
        }

        // Vanilla's engine, when the model keeps it: its own branch supplies the force from here.
        if (!RailForces.of(level()).drivesEngine()) {
            return;
        }

        if (push.lengthSqr() <= IMPULSE_EPSILON) {
            return;
        }

        // Resistance on the movement the cart arrived with, then the engine's impulse on top.
        // Applied here for the reason the parked branch gives: setting the return value at HEAD
        // cancels the method, so neither the super call nor the return hook below is reached, and a
        // lit cart would have no retarding force at all. With flat effort that was invisible — the
        // free-running speed sat above the default ceiling, so the clamp did the limiting — but the
        // curve is meant to limit an engine, and it cannot without something to balance against.
        cir.setReturnValue(
                RailForces.of(level()).retarded(this, deltaMovement, deltaMovement)
                        .add(push.scale(minecarttweaks$sharedAcceleration())));
    }

    // A furnace cart's retarding law, asked for at its own override rather than at the one it calls
    // through to. Vanilla scales the movement before that super call — by 0.98 coasting, or by 0.8
    // plus the impulse under power — so the generic hook is handed a figure a tick of friction has
    // already been taken out of, and the physical model, which works from the movement the cart
    // arrived with, would read a furnace cart as barely different from vanilla. Here the argument
    // is still the real one.
    @Inject(method = "applyNaturalSlowdown", at = @At("RETURN"), cancellable = true)
    private void minecarttweaks$furnaceFriction(
            Vec3 deltaMovement, CallbackInfoReturnable<Vec3> cir) {
        cir.setReturnValue(
                RailForces.of(level()).retarded(this, deltaMovement, cir.getReturnValue()));
    }

    // Aims the engine along a heading read off the cart's movement. A reversal without leaving the
    // rail it was aimed on is refused: vanilla re-derives movement from the impulse, so a shove or
    // a slope would re-aim the engine after the cart and have it drive itself home; a hairpin,
    // which truly reverses a cart, does leave the rail.
    @Unique
    private void minecarttweaks$aimAlong(Vec3 heading) {
        BlockPos rail = getCurrentBlockPosOrRailBelow();

        if (push.lengthSqr() > IMPULSE_EPSILON
                && heading.dot(push) < REVERSAL_DOT
                && rail.equals(minecarttweaks$headingRail)) {
            return;
        }

        push = heading;
        minecarttweaks$headingRail = rail;
    }

    // Force over mass either way: its own output over its own weight when it runs alone, and its
    // train's whole output over the train's weight when it is coupled — the engines behind it
    // counted in, since only the leading car reaches this and it is answering for all of them.
    // Dividing the solo case too is what keeps a lone furnace cart on the same curve as the trains
    // it heads, rather than giving the one cart that carries a firebox the only free ride.
    @Unique
    private double minecarttweaks$sharedAcceleration() {
        return hasLink() && MTGameRules.trainsEnabled(level())
                ? Train.of(this).drive()
                : minecarttweaks$driveAcceleration() / TrainCoupling.mass(this);
    }

    // Reads the brake, holds chunks, and idles the fuel burn while parked.
    @Inject(method = "tick", at = @At("HEAD"))
    private void minecarttweaks$tick(CallbackInfo info) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }

        var rules = serverLevel.getGameRules();

        // Read before super.tick() moves the cart, so the movement path sees this tick's rail, and
        // asked of the whole train: an engine run past the rail while a car behind it is still held
        // drives against a brake that zeroes the train every tick, re-adding exactly one tick of
        // impulse each time — a crawl that never stops and never gets anywhere.
        minecarttweaks$parked = TrainCoupling.held(this);

        minecarttweaks$countStall();

        if (fuel > 0 && rules.get(MTGameRules.FURNACE_MINECARTS_LOAD_CHUNKS)) {
            serverLevel.getChunkSource().addTicketWithRadius(
                    MTTicketTypes.FURNACE_MINECART, chunkPosition(), CHUNK_TICKET_RADIUS);
        }

        // Vanilla burns a tick of fuel every tick; a parked cart should idle instead. Handing back
        // the tick it is about to spend is the only way to slow that without wrapping the loop.
        if (fuel > 0 && tickCount % PARKED_FUEL_INTERVAL != 0 && minecarttweaks$parked) {
            fuel++;
        }
    }

    // Turns a stalled engine round, which is the one thing neither model can work out for itself.
    // Both re-aim the impulse from the cart's own movement — vanilla in calculateNewPushAlong and
    // this mod in aimAlong — and a cart against a buffer has none: stepAlongTrack zeroes a step
    // that covered no ground, so the engine is handed its impulse back into the obstruction every
    // tick and the gate that would turn it round never opens. Left alone a fuelled cart at a dead
    // end wedges there until its fuel runs out, which is what makes a switchback impossible.
    //
    // So the stall is counted instead of derived, and read at the top of the tick where last
    // tick's movement is still what the cart has. Unconditional in both models: the trap is
    // vanilla's, and a cart nothing can move is not behaviour worth mirroring.
    // Unaffected by the power curve, which a reader will want to know here: effort is at its
    // maximum at rest, so a wedged cart is still one the engine is pushing at full force.
    // Whether the train this engine is in is still closing up on what it met. Four ticks is less
    // than a train takes to pack, so without this the engine turns round mid-compression and the
    // train never shuts — and an engine that is still closing gaps is moving something.
    @Unique
    private boolean minecarttweaks$closingUp() {
        return hasLink() && Train.of(this).packing(level().getGameTime());
    }

    @Unique
    private void minecarttweaks$countStall() {
        if (fuel <= 0 || minecarttweaks$parked
                || getDeltaMovement().horizontalDistanceSqr() > IMPULSE_EPSILON
                || minecarttweaks$closingUp()) {
            minecarttweaks$stalled = 0;
            return;
        }

        if (++minecarttweaks$stalled < STALL_TICKS) {
            return;
        }

        // The whole train turns, not this one engine of it, and every engine is handed the same
        // heading rather than each reversing its own. A train's cars stall at different moments —
        // its leading car is driven by vanilla while the rest are placed — so engines left to turn
        // one at a time end up pushing against each other. Two that do sum to nothing, and a train
        // whose engines sum to nothing cannot start itself again at all.
        Vec3 turned = push.scale(-1.0);

        for (AbstractMinecart car : Train.of(this).cars()) {
            car.minecarttweaks$turnEngine(turned);
        }
    }

    // Replaces vanilla's hardcoded burn-time ceiling with the rule's.
    @ModifyConstant(method = "addFuel", constant = @Constant(intValue = 32000))
    private int minecarttweaks$maxBurnTime(int vanillaMax) {
        return level() instanceof ServerLevel serverLevel
                ? serverLevel.getGameRules().get(MTGameRules.FURNACE_MAX_BURN_TIME)
                : vanillaMax;
    }

    // Vanilla only sets push once the fuel is in, so both the normalising and the parking are
    // settled after the fact rather than by wrapping the assignment.
    @Inject(method = "addFuel", at = @At("RETURN"))
    private void minecarttweaks$normaliseAddedImpulse(
            Vec3 interactingPos, ItemStack itemStack, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()) {
            minecarttweaks$setImpulse(push);
        }
    }

    // Vanilla scales the impulse by how far the player stood from the cart and drives off that
    // magnitude; under constant acceleration only the heading is left to carry. Feeding a parked
    // cart is safe — the brake ignores the impulse — and aims it for when the rail powers up.
    @Unique
    private void minecarttweaks$setImpulse(Vec3 direction) {
        Vec3 along = minecarttweaks$alongRail(direction);

        if (along == null) {
            return;
        }

        push = along;

        // Stamped so that aiming a cart back the way it came holds: the movement it still carries
        // reads as a reversal next tick, and only a stamp on this rail has that refused.
        minecarttweaks$headingRail = getCurrentBlockPosOrRailBelow();
    }

    // The rail's own direction nearest {@code want}, or null to keep the heading the cart has.
    // Every impulse but the one read off the cart's own movement starts as a direction to the
    // player, and a track is as often square to that as along it. Left unprojected that drives a
    // standing cart at the rail rather than along it, and vanilla's stepper breaks the tie between
    // two exits it agrees with equally — out of whichever end rounding favours, flipping the cart.
    @Unique
    private @Nullable Vec3 minecarttweaks$alongRail(Vec3 want) {
        Vec3 heading = want.horizontal();

        if (heading.lengthSqr() <= IMPULSE_EPSILON) {
            return null;
        }

        BlockPos on = getCurrentBlockPosOrRailBelow();
        BlockPos exit = RailPath.ahead(level(), on, heading);

        if (exit == null) {
            return null;
        }

        Vec3 along = Vec3.atLowerCornerOf(exit.subtract(on)).horizontal().normalize();

        // Square across the track names neither end, so a cart that has a heading already keeps
        // it rather than being turned round by a top-up offered from the wrong side.
        return heading.normalize().dot(along) > AIM_DOT || push.lengthSqr() <= IMPULSE_EPSILON
                ? along
                : null;
    }

    // Standing still, a cart has no heading of its own and vanilla's away-from-the-player one is
    // near enough arbitrary. A train long enough to resist a shove cannot be aimed by walking into
    // it either, so where the player is looking is the only aim left.
    @Inject(
            method = "interact",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/world/item/ItemStack;consume("
                            + "ILnet/minecraft/world/entity/LivingEntity;)V"))
    private void minecarttweaks$aimVanillaFuel(Player player, InteractionHand hand, Vec3 location,
            CallbackInfoReturnable<InteractionResult> cir) {
        minecarttweaks$aimAlongLook(player);
    }

    // Aims a standing cart where the player is looking.
    @Unique
    private void minecarttweaks$aimAlongLook(Player player) {
        Vec3 look = player.getLookAngle().horizontal();

        if (getDeltaMovement().horizontalDistanceSqr() <= MOVING_EPSILON
                && look.lengthSqr() > IMPULSE_EPSILON) {
            minecarttweaks$setImpulse(look);
        }
    }

    // Handled here rather than in addFuel because a bucket has to be emptied back into the hand
    // that offered it, and addFuel does not know whose hand that was.
    @Inject(method = "interact", at = @At("HEAD"), cancellable = true)
    private void minecarttweaks$addOtherFuels(Player player, InteractionHand hand, Vec3 location,
            CallbackInfoReturnable<InteractionResult> cir) {
        var heldItem = player.getItemInHand(hand);

        if (!(level() instanceof ServerLevel serverLevel)
                || heldItem.is(ItemTags.FURNACE_MINECART_FUEL)
                || !serverLevel.getGameRules().get(MTGameRules.FURNACES_CAN_USE_ALL_FUELS)) {
            return;
        }

        int maxBurnTime = serverLevel.getGameRules().get(MTGameRules.FURNACE_MAX_BURN_TIME);
        int burnTime = FurnaceFuel.burnDuration(serverLevel, heldItem);

        if (burnTime <= 0 || fuel + burnTime > maxBurnTime) {
            return;
        }

        fuel += burnTime;
        minecarttweaks$setImpulse(position().subtract(player.position()));
        minecarttweaks$aimAlongLook(player);
        FurnaceFuel.take(serverLevel, this, player, hand, heldItem);

        cir.setReturnValue(InteractionResult.SUCCESS);
    }

    // Vanilla narrows Fuel to a short, which furnace_max_burn_time overshoots, so the real value
    // rides alongside it and the vanilla field is clamped to stay sane if this mod is removed.
    @Inject(method = "addAdditionalSaveData", at = @At("RETURN"))
    private void minecarttweaks$writeFuel(ValueOutput output, CallbackInfo info) {
        output.child(MinecartTweaks.MOD_ID).putInt("RealFuel", fuel);

        if (fuel > Short.MAX_VALUE) {
            output.putShort("Fuel", Short.MAX_VALUE);
        }
    }

    // Defaulting to what vanilla just read keeps a cart saved without this mod loading fuelled.
    @Inject(method = "readAdditionalSaveData", at = @At("RETURN"))
    private void minecarttweaks$readFuel(ValueInput input, CallbackInfo info) {
        fuel = input.childOrEmpty(MinecartTweaks.MOD_ID).getIntOr("RealFuel", fuel);
    }
}
