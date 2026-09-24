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
import club.rainbowkitty.minecarttweaks.init.FurnaceTicketTypes;
import club.rainbowkitty.minecarttweaks.rail.RailForces;
import club.rainbowkitty.minecarttweaks.rail.RailPath;
import club.rainbowkitty.minecarttweaks.rule.ModGameRules;
import club.rainbowkitty.minecarttweaks.train.Train;
import club.rainbowkitty.minecarttweaks.train.TrainCoupling;
import club.rainbowkitty.minecarttweaks.train.TrainMeter;
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

    // A car covering less ground in a tick than this, and than this share of what its movement
    // asks for, is going nowhere: two trains shoving each other creep a thousandth of a block.
    @Unique
    private static final double STALL_CREEP = 0.005;
    @Unique
    private static final double STALL_SHARE = 0.5;

    // The most ticks a stalled engine holds its turn for another train that turns first, before
    // turning anyway: time for that one to turn and get going, so this one can follow.
    @Unique
    private static final int YIELD_TICKS = 5 * STALL_TICKS;

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

    // Ticks in a row this cart has been given the engine and its train still covered no ground,
    // and the tick they were last counted on.
    @Unique
    private int minecarttweaks$stalled;
    @Unique
    private long minecarttweaks$stallCountedTick;

    // The entity id of the car the ground covered is read off — the one leading this cart's train,
    // whose own movement is the train's — and its position and tick count at this cart's last tick.
    @Unique
    private int minecarttweaks$gaugeId;
    @Unique
    private @Nullable Vec3 minecarttweaks$gaugePosition;
    @Unique
    private int minecarttweaks$gaugeTicks;

    // Never called: present only to satisfy the compiler about the superclass this mixin extends.
    protected MinecartFurnaceMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean minecarttweaks$driving() {
        return fuel > 0 && !minecarttweaks$parked;
    }

    @Override
    public int minecarttweaks$fuel() {
        return fuel;
    }

    // A count left standing by a cart that has stopped ticking says nothing about now.
    @Override
    public boolean minecarttweaks$stalling() {
        return minecarttweaks$stalled > 0
                && minecarttweaks$stallCountedTick + 1 >= level().getGameTime();
    }

    // Asked at the speed of the car whose movement is an input, so every engine of a train reads
    // the same figure and their efforts sum — which is how double-heading buys top speed and not
    // just acceleration.
    @Override
    public double minecarttweaks$driveAcceleration() {
        return minecarttweaks$driving()
                ? RailForces.forLevel(level()).engineOutput(TrainCoupling.drivenSpeed(this))
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

        BlockPos railPos = getCurrentBlockPosOrRailBelow();
        BlockPos exitRailPos = RailPath.ahead(level(), railPos, heading.horizontal());

        if (exitRailPos == null) {
            return;
        }

        push = minecarttweaks$railHeading(railPos, exitRailPos);
        minecarttweaks$headingRail = railPos;
    }

    // Vanilla halves a furnace cart's ceiling, leaving the engine slower than the carts it tows
    // and making it the limit on its own train. The physical model undoes that, for a
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
        double ownTopSpeed = RailForces.forLevel(level)
                .topSpeed(cir.getReturnValueD(), super.getMaxSpeed(level));

        cir.setReturnValue(TrainCoupling.trainCeiling(this, level, ownTopSpeed));
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
            cir.setReturnValue(RailForces.forLevel(level()).retarded(this, deltaMovement,
                    super.applyNaturalSlowdown(deltaMovement.multiply(0.98, 0.0, 0.98))));
            return;
        }

        // Without the fuel check a spent cart accelerates forever: vanilla only zeroes the impulse
        // after super.tick() has already moved it, so the heading below would reseed it every tick.
        if (fuel <= 0) {
            return;
        }

        // The direction of travel is the only heading that survives a corner. A coupled cart's
        // comes from its own rail record and is trustworthy at any speed; a solo cart needs a
        // floor under it, or the jitter of a near-stopped one decides which way it pushes.
        //
        // Aimed under both models, before deciding whose force drives the cart. Vanilla aims the
        // impulse by projecting it onto the direction of travel and normalising the result — but
        // projecting onto a perpendicular heading gives the zero vector, and normalise() turns
        // that into a permanently dead engine, since vanilla never re-aims after. A two-block
        // U-turn swings the cart ninety degrees in one step, so that is ordinary track, not an
        // edge case. Aiming first sidesteps this: the heading is already parallel to the
        // movement, so vanilla's own projection becomes a no-op.
        double movementFloorSqr = hasLink() ? IMPULSE_EPSILON : MOVING_EPSILON;

        if (deltaMovement.horizontalDistanceSqr() > movementFloorSqr) {
            minecarttweaks$aimAlong(deltaMovement.horizontal().normalize());
        }

        // Vanilla's engine, when the model keeps it: its own branch supplies the force from here.
        if (!RailForces.forLevel(level()).drivesEngine()) {
            return;
        }

        if (push.lengthSqr() <= IMPULSE_EPSILON) {
            return;
        }

        // Resistance on the movement the cart arrived with, then the engine's impulse on top.
        // Applied here for the reason the parked branch gives: cancelling at HEAD skips both the
        // super call and the return hook below, so a lit cart would otherwise get no retarding
        // force at all. Flat effort hid this — free-running speed sat above the default ceiling,
        // so the clamp did the limiting — but the curve is meant to limit an engine, and needs
        // something to balance against.
        cir.setReturnValue(
                RailForces.forLevel(level()).retarded(this, deltaMovement, deltaMovement)
                        .add(push.scale(minecarttweaks$sharedAcceleration())));
    }

    // A furnace cart's retarding law, asked for at its own override rather than the one it calls
    // through to. Vanilla scales the movement before that super call — by 0.98 coasting, or by 0.8
    // plus the impulse under power — so the generic hook gets a figure a tick of friction has
    // already left. The physical model works from the movement the cart arrived with, so it would
    // read a furnace cart as barely different from vanilla there; here the argument is still real.
    @Inject(method = "applyNaturalSlowdown", at = @At("RETURN"), cancellable = true)
    private void minecarttweaks$furnaceFriction(
            Vec3 deltaMovement, CallbackInfoReturnable<Vec3> cir) {
        cir.setReturnValue(
                RailForces.forLevel(level()).retarded(this, deltaMovement, cir.getReturnValue()));
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

    @Unique
    private Vec3 minecarttweaks$railHeading(BlockPos from, BlockPos to) {
        return Vec3.atLowerCornerOf(to.subtract(from)).horizontal().normalize();
    }

    // Force over mass either way: its own output over its own weight alone, or its train's whole
    // output over the train's weight coupled — engines behind it counted in, since only the
    // leading car reaches this and answers for all of them. Dividing the solo case too keeps a
    // lone furnace cart on the same curve as the trains it heads, instead of giving the one cart
    // with a firebox the only free ride.
    @Unique
    private double minecarttweaks$sharedAcceleration() {
        if (!hasLink() || !ModGameRules.trainsEnabled(level())) {
            return minecarttweaks$driveAcceleration() / TrainCoupling.mass(this);
        }

        Train train = Train.of(this);

        // The same one car spends it here as in TrainCoupling.hauled, and for the same reason: the
        // train's figure already counts every firebox in it, so a second engine spending it again
        // on its own account spends it twice. Nothing while the placer holds the train, where only
        // this car's movement survives anyway; everything while it does not.
        return train.leader() == this ? train.driveAcceleration() : 0.0;
    }

    // Reads the brake, holds chunks, and idles the fuel burn while parked.
    @Inject(method = "tick", at = @At("HEAD"))
    private void minecarttweaks$tick(CallbackInfo info) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }

        var rules = serverLevel.getGameRules();

        // Read before super.tick() moves the cart, so the movement path sees this tick's rail —
        // and asked of the whole train: an engine run past the rail while a car behind it is
        // still held drives against a brake that zeroes the train every tick, re-adding one tick
        // of impulse each time, a crawl that never stops and never gets anywhere.
        minecarttweaks$parked = TrainCoupling.held(this);

        minecarttweaks$countStall();

        if (fuel > 0 && rules.get(ModGameRules.FURNACE_MINECARTS_LOAD_CHUNKS)) {
            serverLevel.getChunkSource().addTicketWithRadius(
                    FurnaceTicketTypes.FURNACE_MINECART, chunkPosition(), CHUNK_TICKET_RADIUS);
        }

        // Vanilla burns a tick of fuel every tick; a parked cart should idle instead. Handing back
        // the tick it is about to spend is the only way to slow that without wrapping the loop.
        if (fuel > 0 && tickCount % PARKED_FUEL_INTERVAL != 0 && minecarttweaks$parked) {
            fuel++;
        }
    }

    // The car leading this engine's train: one behind it can stand while the train stretches out.
    @Unique
    private AbstractMinecart minecarttweaks$gauge() {
        if (!hasLink() || !ModGameRules.trainsEnabled(level())) {
            return this;
        }

        AbstractMinecart leader = Train.of(this).lastLeader();

        return leader == null ? this : leader;
    }

    // Whether gaugeCar, moved by step in its last tick, holds no movement or covered next to none.
    @Unique
    private static boolean minecarttweaks$standing(AbstractMinecart gaugeCar, Vec3 step) {
        double covered = step.horizontalDistance();
        double asked = gaugeCar.getDeltaMovement().horizontalDistance();

        return asked * asked <= IMPULSE_EPSILON
                || (covered < STALL_CREEP && covered < asked * STALL_SHARE);
    }

    // Whether the train this engine is in is still closing up on what it met. Four ticks is less
    // than a train takes to pack, so without this the engine would turn round mid-compression and
    // the train never shuts — an engine still closing gaps is moving something.
    @Unique
    private boolean minecarttweaks$closingUp() {
        return hasLink() && Train.of(this).packing(level().getGameTime());
    }

    // Turns a stalled engine round, which neither model can work out for itself. Both re-aim the
    // impulse from the cart's own movement — vanilla in calculateNewPushAlong, this mod in
    // aimAlong — and a cart against a buffer has none: stepAlongTrack zeroes a step that covered
    // no ground, so the impulse drives back into the obstruction every tick and the turning gate
    // never opens. Left alone, a fuelled cart at a dead end wedges until its fuel runs out — the
    // switchback that becomes impossible.
    //
    // Counted rather than derived, off the ground the train covers between the tops of two ticks
    // rather than the movement it holds: a cart wedged among others is handed movement by every
    // strike on it and still goes nowhere. Unconditional in both models — the trap is vanilla's,
    // not behaviour worth mirroring — and unaffected by the power curve: effort is maximum at
    // rest, so a wedged cart is still being pushed at full force.
    @Unique
    private void minecarttweaks$countStall() {
        AbstractMinecart gaugeCar = minecarttweaks$gauge();
        Vec3 standingPosition = gaugeCar.position();
        // A leading car outside the ticking chunks stands still without its train being wedged.
        Vec3 lastPosition = gaugeCar.getId() == minecarttweaks$gaugeId
                && gaugeCar.tickCount != minecarttweaks$gaugeTicks
                ? minecarttweaks$gaugePosition : null;

        minecarttweaks$gaugeId = gaugeCar.getId();
        minecarttweaks$gaugePosition = standingPosition;
        minecarttweaks$gaugeTicks = gaugeCar.tickCount;
        minecarttweaks$stallCountedTick = level().getGameTime();

        if (fuel <= 0 || minecarttweaks$parked || lastPosition == null
                || !minecarttweaks$standing(gaugeCar, standingPosition.subtract(lastPosition))
                || minecarttweaks$closingUp()) {
            minecarttweaks$stalled = 0;
            return;
        }

        if (++minecarttweaks$stalled < STALL_TICKS) {
            return;
        }

        // Of two trains stalled against each other only one turns straight away, or both run
        // back into whatever lies behind them together: on a loop they fill, for ever.
        if (minecarttweaks$stalled < YIELD_TICKS && TrainCoupling.yieldsTurn(this)) {
            return;
        }

        // The whole train turns, not this one engine of it, and every engine is handed the one
        // heading, carried along the rails to it, rather than each reversing its own. A train's
        // cars stall at different moments — its leading car is driven by vanilla while the rest
        // are placed — so engines left to turn one at a time end up pushing against each other.
        // Two that do sum to nothing, and a train whose engines sum to nothing cannot start itself
        // again at all.
        Vec3 reversedHeading = push.scale(-1.0);

        TrainMeter.stallReversed(this);
        TrainCoupling.turnRound(this, reversedHeading);
    }

    // Replaces vanilla's hardcoded burn-time ceiling with the rule's.
    @ModifyConstant(method = "addFuel", constant = @Constant(intValue = 32000))
    private int minecarttweaks$maxBurnTime(int vanillaMax) {
        return level() instanceof ServerLevel serverLevel
                ? serverLevel.getGameRules().get(ModGameRules.FURNACE_MAX_BURN_TIME)
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
        Vec3 railHeading = minecarttweaks$alongRail(direction);

        if (railHeading == null) {
            return;
        }

        push = railHeading;

        // Stamped so that aiming a cart back the way it came holds: the movement it still carries
        // reads as a reversal next tick, and only a stamp on this rail has that refused.
        minecarttweaks$headingRail = getCurrentBlockPosOrRailBelow();
    }

    // The rail's own direction nearest {@code wantedHeading}, or null to keep the heading the
    // cart has. Every impulse but the one read off the cart's own movement starts as a direction
    // to the player, and a track is as often square to that as along it. Left unprojected that
    // drives a standing cart at the rail rather than along it, and vanilla's stepper breaks the
    // tie between two exits it agrees with equally — out of whichever end rounding favours,
    // flipping the cart.
    @Unique
    private @Nullable Vec3 minecarttweaks$alongRail(Vec3 wantedHeading) {
        Vec3 heading = wantedHeading.horizontal();

        if (heading.lengthSqr() <= IMPULSE_EPSILON) {
            return null;
        }

        BlockPos railPos = getCurrentBlockPosOrRailBelow();
        BlockPos exitRailPos = RailPath.ahead(level(), railPos, heading);

        if (exitRailPos == null) {
            return null;
        }

        Vec3 railHeading = minecarttweaks$railHeading(railPos, exitRailPos);

        // Square across the track names neither end, so a cart that has a heading already keeps
        // it rather than being turned round by a top-up offered from the wrong side.
        return heading.normalize().dot(railHeading) > AIM_DOT || push.lengthSqr() <= IMPULSE_EPSILON
                ? railHeading
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
        Vec3 lookDirection = player.getLookAngle().horizontal();

        if (getDeltaMovement().horizontalDistanceSqr() <= MOVING_EPSILON
                && lookDirection.lengthSqr() > IMPULSE_EPSILON) {
            minecarttweaks$setImpulse(lookDirection);
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
                || !serverLevel.getGameRules().get(ModGameRules.FURNACES_CAN_USE_ALL_FUELS)) {
            return;
        }

        int maxBurnTime = serverLevel.getGameRules().get(ModGameRules.FURNACE_MAX_BURN_TIME);
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
