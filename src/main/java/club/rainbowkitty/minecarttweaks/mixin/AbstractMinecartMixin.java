package club.rainbowkitty.minecarttweaks.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.block.JunctionRailBlock;
import club.rainbowkitty.minecarttweaks.block.SwitchedRailBlock;
import club.rainbowkitty.minecarttweaks.collision.CartImpact;
import club.rainbowkitty.minecarttweaks.collision.CartInteraction;
import club.rainbowkitty.minecarttweaks.ext.AbstractMinecartExt;
import club.rainbowkitty.minecarttweaks.init.MTAttachments;
import club.rainbowkitty.minecarttweaks.rail.RailBrake;
import club.rainbowkitty.minecarttweaks.rail.RailContext;
import club.rainbowkitty.minecarttweaks.rail.RailPath;
import club.rainbowkitty.minecarttweaks.rail.RailPath.Bend;
import club.rainbowkitty.minecarttweaks.rule.MTGameRules;
import club.rainbowkitty.minecarttweaks.train.TrainCoupling;
import club.rainbowkitty.minecarttweaks.train.TrainSnapshot;
import club.rainbowkitty.minecarttweaks.visual.LinkChain;

/**
 * A cart's own half of this mod: the link slots it stores, the per-tick memos the coupling reads,
 * and the injections that give a train one speed, one drive and one dead stop, and that let a
 * moving cart through what it is about to run over.
 *
 * <p>No {@code @Unique} instance field here may carry an initializer. Mixin does not merge them
 * into the target's constructor — verified against the exported class, where no constructor writes
 * one — so a field declared with a value silently starts at Java's default instead, and the
 * declaration reads as a guarantee the class does not have. Every field below is written to mean
 * the right thing at its own zero.
 */
@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartMixin extends Entity implements AbstractMinecartExt {

    // Only has to exclude a dead stop, since the coupling writes a towed cart's movement along its
    // own rail and any of it at all names the direction the train is going.
    @Unique
    private static final double ANY_MOVEMENT = 1.0E-10;

    // Speed at which a cart is stopped outright, as a fraction of its own width per tick — slow
    // enough that it would take ten seconds to cover its own length.
    @Unique
    private static final double CRAWL = 1.0 / (20.0 * 10.0);

    // Of its speed a cart keeps per tick at a dead standstill, which the extra low-speed drag eases
    // back to one at rail_drag_speed. Vanilla's own figure is 0.975 empty and 0.997 ridden, so this
    // shortens a laden cart's last few blocks from most of a minute to about a second.
    @Unique
    private static final double SLOW_DRAG = 0.8;

    // Vanilla's shove between a cart and something that is not one, read out of
    // AbstractMinecart.push(Entity): the impulse it is worth at touching range, and the separation
    // below which the two count as one point.
    @Unique
    private static final double SHOVE_IMPULSE = 0.1 * 0.5;
    @Unique
    private static final double SHOVE_EPSILON = 1.0E-4;

    // Set for the length of a walk over a train, so the members asked for their ceiling answer with
    // their own rather than walking back over the same train to find it again.
    @Unique
    private static boolean minecarttweaks$ownSpeedOnly;

    // The chain drawn back to the cart this one follows, or null when it follows nothing.
    @Unique
    private @Nullable LinkChain minecarttweaks$chain;

    // Game time this cart's tracker withholds position updates until, or zero for none. Zero rather
    // than a negative sentinel because the field starts at Java's default; a real hold is always a
    // live game time plus an offset, so it can never be zero itself.
    @Unique
    private long minecarttweaks$resyncUntil;

    // The last snapshot this cart appeared in, which knows for itself whether it is still good.
    @Unique
    private @Nullable TrainSnapshot minecarttweaks$train;

    // This cart's stretch of track, and the tick it was read on. The tick only matters once the
    // context is non-null, so its own zero never reads as fresh.
    @Unique
    private @Nullable RailContext minecarttweaks$rail;
    @Unique
    private long minecarttweaks$railTick;

    // The rail last counted for bends, so a cart standing on one is only counted once, and how that
    // rail ran — which the next one is compared against. Null before the first rail is counted.
    @Unique
    private @Nullable BlockPos minecarttweaks$lastRail;
    @Unique
    private @Nullable Bend minecarttweaks$lastBend;

    // Vanilla's own speed ceiling for this cart, which minecarttweaks$trainSpeed raises.
    @Shadow
    protected abstract double getMaxSpeed(ServerLevel level);

    // Never called: present only to satisfy the compiler about the superclass this mixin extends.
    public AbstractMinecartMixin(EntityType<?> type, Level level) {
        super(type, level);
        throw new UnsupportedOperationException();
    }

    // ---- link state and per-tick memos ----

    @Override
    public List<AbstractMinecart> getNeighbours() {
        var data = MTAttachments.getLink(minecarttweaks$self());

        // Walked once per car per tick, and most carts hold no links at all.
        if (!data.hasLink()) {
            return List.of();
        }

        var neighbours = new ArrayList<AbstractMinecart>(2);
        minecarttweaks$collect(data.first(), neighbours);
        minecarttweaks$collect(data.second(), neighbours);
        return neighbours;
    }

    @Override
    public boolean hasLink() {
        return MTAttachments.getLink(minecarttweaks$self()).hasLink();
    }

    @Override
    public boolean hasFreeSlot() {
        return MTAttachments.getLink(minecarttweaks$self()).hasFreeSlot();
    }

    @Override
    public void addNeighbour(AbstractMinecart neighbour) {
        AbstractMinecart self = minecarttweaks$self();

        if (neighbour != self) {
            MTAttachments.setLink(self,
                    MTAttachments.getLink(self).withNeighbour(neighbour.getUUID()));
            TrainSnapshot.invalidate();
        }
    }

    @Override
    public void removeNeighbour(AbstractMinecart neighbour) {
        AbstractMinecart self = minecarttweaks$self();
        MTAttachments.setLink(self,
                MTAttachments.getLink(self).withoutNeighbour(neighbour.getUUID()));
        TrainSnapshot.invalidate();
    }

    @Override
    public double minecarttweaks$maxSpeedAlone(ServerLevel level) {
        return getMaxSpeed(level);
    }

    @Override
    public int minecarttweaks$bendsEntered() {
        AbstractMinecart self = minecarttweaks$self();
        BlockPos rail = self.getCurrentBlockPosOrRailBelow();

        if (rail.equals(minecarttweaks$lastRail)) {
            return 0;
        }

        Bend bend = RailPath.bend(self);
        Bend last = Objects.requireNonNullElse(minecarttweaks$lastBend, Bend.NONE);
        int bends = (bend.turn() != 0 && bend.turn() != -last.turn() ? 1 : 0)
                + Math.abs(bend.grade() - last.grade());

        minecarttweaks$lastRail = rail;
        minecarttweaks$lastBend = bend;
        return bends;
    }

    @Override
    public RailContext minecarttweaks$railContext() {
        long now = level().getGameTime();

        if (minecarttweaks$rail == null || minecarttweaks$railTick != now) {
            minecarttweaks$rail = RailContext.of(minecarttweaks$self());
            minecarttweaks$railTick = now;
        }

        return minecarttweaks$rail;
    }

    @Override
    public @Nullable TrainSnapshot minecarttweaks$train() {
        return minecarttweaks$train;
    }

    @Override
    public void minecarttweaks$setTrain(TrainSnapshot train) {
        minecarttweaks$train = train;
    }

    @Override
    public long minecarttweaks$resyncAt() {
        return minecarttweaks$resyncUntil;
    }

    @Override
    public void minecarttweaks$setResyncAt(long gameTime) {
        minecarttweaks$resyncUntil = gameTime;
    }

    // ---- injections ----

    // Raises this cart's ceiling to the highest in its train, so no member can outrun another until
    // the coupling stretches far enough to break.
    @Inject(method = "getMaxSpeed", at = @At("RETURN"), cancellable = true)
    private void minecarttweaks$trainSpeed(
            ServerLevel level, CallbackInfoReturnable<Double> cir) {
        if (minecarttweaks$ownSpeedOnly || !hasLink() || !MTGameRules.trainsEnabled(level)) {
            return;
        }

        AbstractMinecart self = minecarttweaks$self();
        double fastest = cir.getReturnValue();
        minecarttweaks$ownSpeedOnly = true;

        try {
            for (AbstractMinecart member : TrainSnapshot.of(self).cars()) {
                if (member != self) {
                    fastest = Math.max(fastest, member.minecarttweaks$maxSpeedAlone(level));
                }
            }
        } finally {
            minecarttweaks$ownSpeedOnly = false;
        }

        cir.setReturnValue(fastest);
    }

    // A towed cart is under power as much as the cart towing it, so it takes the train's
    // acceleration and drops the same rolling friction.
    @Inject(method = "applyNaturalSlowdown", at = @At("HEAD"), cancellable = true)
    private void minecarttweaks$towedDrive(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        AbstractMinecart self = minecarttweaks$self();

        if (!hasLink()
                || !MTGameRules.trainsEnabled(level())
                || minecarttweaks$driveAcceleration() > 0
                || RailBrake.brakes(self)) {
            return;
        }

        double drive = TrainCoupling.sharedDrive(self);

        if (drive <= 0) {
            return;
        }

        // A standing train has no movement to read a heading off, so it leaves on the aim of its
        // powered carts. Without that only those carts move and the train never gets going.
        Vec3 heading = movement.horizontal();
        if (heading.lengthSqr() <= ANY_MOVEMENT) {
            heading = TrainCoupling.driveHeading(self);
        }

        if (heading.lengthSqr() > ANY_MOVEMENT) {
            cir.setReturnValue(movement.add(heading.normalize().scale(drive)));
        }
    }

    // Vanilla's rolling friction takes a fixed fraction of the speed, so the last of it is never
    // quite spent and a cart crawls on long after it looks stopped — and worst with someone aboard,
    // where that fraction is a three-hundredth a tick. Below rail_drag_speed the fraction is raised
    // as the speed falls, and below a crawl what is left is dropped outright. Neither applies while
    // something is still driving the cart — an engine, or a rider holding a direction, whose nudge
    // is itself well under the crawl and would be swallowed here.
    @Inject(method = "applyNaturalSlowdown", at = @At("RETURN"), cancellable = true)
    private void minecarttweaks$railFriction(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        AbstractMinecart self = minecarttweaks$self();
        Vec3 slowed = cir.getReturnValue();

        if (TrainCoupling.underPower(self) || TrainCoupling.riderSteering(self)) {
            return;
        }

        // Scaled by length: a train moves slower under the same push, and one fixed floor swallows
        // its opening ticks before they accumulate into anything it could start on.
        double crawl = getBbWidth() * CRAWL / TrainCoupling.mass(self);
        double speed = slowed.horizontalDistance();

        if (speed < crawl) {
            cir.setReturnValue(new Vec3(0.0, slowed.y(), 0.0));
            return;
        }

        double drag = minecarttweaks$slowSpeedDrag(speed);

        if (drag < 1.0) {
            cir.setReturnValue(new Vec3(slowed.x() * drag, slowed.y(), slowed.z() * drag));
        }
    }

    // This is the hook the whole resolver hangs off. Vanilla hands every pushable entity to
    // getEntityCollisions as a solid box, and stepAlongTrack zeroes the speed of a step that
    // covered no ground, so one mob on the rail stops a cart at full tilt dead. CartInteraction
    // decides what a cart is allowed through; CartImpact hurts and throws what it went through.
    @Inject(
            method = "canCollideWith(Lnet/minecraft/world/entity/Entity;)Z",
            at = @At("RETURN"),
            cancellable = true)
    private void minecarttweaks$driveThrough(
            Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()
                && !CartInteraction.of(minecarttweaks$self(), entity).blocks()) {
            cir.setReturnValue(false);
        }
    }

    // Which pairs shove each other at all is CartInteraction's to say. Vanilla resolves a cart's
    // own shove the other way round, through Entity.push(Entity), but skips that on a tick the
    // cart collided with nothing — so a standing train is only ever met here.
    @Inject(
            method = "push(Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void minecarttweaks$shoveReaction(Entity entity, CallbackInfo info) {
        AbstractMinecart self = minecarttweaks$self();

        if (!CartInteraction.pushes(self, entity)) {
            info.cancel();
            return;
        }

        // Cart against cart is vanilla's own pushOtherMinecart, which reads headings rather than
        // splitting an impulse and has nothing to hold anything against.
        if (level().isClientSide()
                || entity instanceof AbstractMinecart
                || entity.noPhysics
                || noPhysics
                || hasPassenger(entity)) {
            return;
        }

        double xa = entity.getX() - getX();
        double za = entity.getZ() - getZ();
        double spread = xa * xa + za * za;

        if (spread < SHOVE_EPSILON) {
            return;
        }

        info.cancel();

        double gap = Math.sqrt(spread);
        Vec3 line = new Vec3(xa / gap, 0.0, za / gap);

        // The cart's half, vanilla's own: an impulse falling off with distance, capped at touching
        // range. Sent the ordinary way, so the Entity.push mixin divides it by the train's length.
        Vec3 shove = line.scale(SHOVE_IMPULSE * Math.min(1.0, 1.0 / gap));
        push(-shove.x(), 0.0, -shove.z());

        // The shover is then held to whatever the train has just become, by taking back all of the
        // speed it is still closing at — which is what makes contact a wall rather than a bounce,
        // since it can never exceed the shover's own approach. Read off the train and not this car,
        // which holds the whole of the push above until the train next settles.
        double closing = TrainCoupling.sharedSpeed(self, line)
                - entity.getKnownMovement().dot(line);

        if (closing <= 0.0) {
            return;
        }

        Vec3 held = line.scale(closing);
        entity.push(held.x(), 0.0, held.z());

        // Player movement is client-authoritative: Entity.push only sets needsSync, which reaches
        // the entity's *trackers*, so without this their own client never hears the shove and the
        // next move packet snaps the server's copy away.
        entity.hurtMarked = true;
    }

    // Orients the rails under the cart, settles the train and its chain, and applies impact damage.
    @Inject(method = "tick", at = @At("HEAD"))
    private void minecarttweaks$tick(CallbackInfo info) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }

        AbstractMinecart self = minecarttweaks$self();

        JunctionRailBlock.steer(serverLevel, self);
        SwitchedRailBlock.steer(serverLevel, self);
        RailBrake.tick(serverLevel, self);

        // Each cart draws exactly the one chain, to whichever cart it follows.
        AbstractMinecart parent = TrainCoupling.tickTrain(serverLevel, self);
        minecarttweaks$chain = LinkChain.sync(minecarttweaks$chain, this, parent);

        CartImpact.strikeNearby(serverLevel, self);
    }

    // ---- helpers ----

    // How much of its speed a cart under rail_drag_speed keeps: all of it at the rule's own speed,
    // falling to SLOW_DRAG at a standstill. One wherever the rule does not reach, client included.
    @Unique
    private double minecarttweaks$slowSpeedDrag(double speed) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return 1.0;
        }

        double until = serverLevel.getGameRules().get(MTGameRules.RAIL_DRAG_SPEED) / 20.0;

        if (until <= 0.0 || speed >= until) {
            return 1.0;
        }

        return SLOW_DRAG + (1.0 - SLOW_DRAG) * (speed / until);
    }

    // This mixin as the cart it is applied to.
    @Unique
    private AbstractMinecart minecarttweaks$self() {
        return (AbstractMinecart) (Object) this;
    }

    // Adds the cart a stored neighbour slot names, if it holds one that still exists.
    @Unique
    private void minecarttweaks$collect(Optional<UUID> ref, List<AbstractMinecart> into) {
        if (ref.isEmpty() || !(level() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (serverLevel.getEntity(ref.get()) instanceof AbstractMinecart cart) {
            into.add(cart);
        }
    }
}
