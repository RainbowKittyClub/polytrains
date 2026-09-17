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
import club.rainbowkitty.minecarttweaks.rail.RailBrake;
import club.rainbowkitty.minecarttweaks.rule.MTGameRules;
import club.rainbowkitty.minecarttweaks.train.TrainCoupling;
import club.rainbowkitty.minecarttweaks.util.FurnaceFuel;

/** Furnace-cart speed, burning any fuel, the parking brake, and keeping chunks loaded. */
@Mixin(MinecartFurnace.class)
public abstract class MinecartFurnaceMixin extends AbstractMinecart implements AbstractMinecartExt {
    // Chunks held either side of a burning cart, in chunks.
    @Unique
    private static final int CHUNK_TICKET_RADIUS = 3;

    // Vanilla's minimum per-tick pull-back on an ascending rail
    // (NewMinecartBehavior#calculateSlopeSpeed).
    @Unique
    private static final double SLOPE_PULL = 0.0078125;

    // Engine output of a burning cart in blocks per tick squared. Above SLOPE_PULL with margin, and
    // TrainCoupling hands the whole of it to a cart towing up to two others, so a short furnace
    // train climbs a slope from any speed.
    @Unique
    private static final double ACCELERATION = 2.0 * SLOPE_PULL;

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

    // One tick of fuel is spent per this many ticks while parked.
    @Unique
    private static final int PARKED_FUEL_INTERVAL = 10;

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

    // Never called: present only to satisfy the compiler about the superclass this mixin extends.
    protected MinecartFurnaceMixin(EntityType<?> entityType, Level level) {
        super(entityType, level);
        throw new UnsupportedOperationException();
    }

    @Override
    public double minecarttweaks$driveAcceleration() {
        return fuel > 0 && !minecarttweaks$parked ? ACCELERATION : 0.0;
    }

    @Override
    public Vec3 minecarttweaks$driveHeading() {
        return minecarttweaks$driveAcceleration() > 0 ? push : Vec3.ZERO;
    }

    // Undoes vanilla's halving, which would leave a furnace cart slower than the carts it tows and
    // so make the engine the limit on its own train. The ceiling itself is max_minecart_speed, as
    // it is for every other cart — raising the engine past what the world allows anything else is
    // what a rule of its own used to do, and it made that rule a lie.
    @Inject(method = "getMaxSpeed", at = @At("RETURN"), cancellable = true)
    private void minecarttweaks$furnaceSpeed(
            ServerLevel level, CallbackInfoReturnable<Double> cir) {
        cir.setReturnValue(super.getMaxSpeed(level));
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
            cir.setReturnValue(super.applyNaturalSlowdown(deltaMovement.multiply(0.98, 0.0, 0.98)));
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
        double floor = hasLink() ? IMPULSE_EPSILON : MOVING_EPSILON;

        if (deltaMovement.horizontalDistanceSqr() > floor) {
            minecarttweaks$aimAlong(deltaMovement.horizontal().normalize());
        }

        if (push.lengthSqr() <= IMPULSE_EPSILON) {
            return;
        }

        cir.setReturnValue(deltaMovement.add(push.scale(minecarttweaks$sharedAcceleration())));
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

    // Its own full output when it runs alone, and the train's share of the whole when it is
    // coupled — which is the same figure every carriage behind it is taking.
    @Unique
    private double minecarttweaks$sharedAcceleration() {
        return hasLink() && MTGameRules.trainsEnabled(level())
                ? TrainCoupling.sharedDrive(this)
                : ACCELERATION;
    }

    // Reads the brake, holds chunks, and idles the fuel burn while parked.
    @Inject(method = "tick", at = @At("HEAD"))
    private void minecarttweaks$tick(CallbackInfo info) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }

        var rules = serverLevel.getGameRules();

        // Read before super.tick() moves the cart, so the movement path sees this tick's rail.
        minecarttweaks$parked = RailBrake.brakes(this);

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
        push = direction.horizontal().normalize();

        // Stamped so that aiming a cart back the way it came holds: the movement it still carries
        // reads as a reversal next tick, and only a stamp on this rail has that refused.
        minecarttweaks$headingRail = getCurrentBlockPosOrRailBelow();
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
