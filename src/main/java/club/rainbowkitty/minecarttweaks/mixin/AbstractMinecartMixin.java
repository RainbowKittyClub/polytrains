package club.rainbowkitty.minecarttweaks.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import it.unimi.dsi.fastutil.longs.LongSet;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.block.JunctionRailBlock;
import club.rainbowkitty.minecarttweaks.block.SwitchedRailBlock;
import club.rainbowkitty.minecarttweaks.collision.CartImpact;
import club.rainbowkitty.minecarttweaks.collision.CartInteraction;
import club.rainbowkitty.minecarttweaks.display.LinkChain;
import club.rainbowkitty.minecarttweaks.ext.AbstractMinecartExt;
import club.rainbowkitty.minecarttweaks.init.EntityAttachments;
import club.rainbowkitty.minecarttweaks.rail.RailBrake;
import club.rainbowkitty.minecarttweaks.rail.RailContext;
import club.rainbowkitty.minecarttweaks.rail.RailForces;
import club.rainbowkitty.minecarttweaks.train.Train;
import club.rainbowkitty.minecarttweaks.train.TrainCoupling;
import club.rainbowkitty.minecarttweaks.train.TrainPlacer;
import club.rainbowkitty.minecarttweaks.util.MinecartHelper;

/**
 * A cart's own half of this mod: the link slots it stores, the per-tick memos the coupling reads,
 * and the injections that give a train one speed, one drive and one dead stop, and that let a
 * moving cart through what it is about to run over.
 *
 * <p>No {@code @Unique} instance field here may carry an initializer. Mixin never merges them into
 * the target's constructor — verified against the exported class, which writes none — so an
 * initializer silently never runs and the field starts at Java's default instead. Every field
 * below is written to mean the right thing at zero.
 */
@Mixin(AbstractMinecart.class)
public abstract class AbstractMinecartMixin extends Entity implements AbstractMinecartExt {

    // Vanilla's shove between a cart and something that is not one, read out of
    // AbstractMinecart.push(Entity): the impulse it is worth at touching range, and the separation
    // below which the two count as one point.
    @Unique
    private static final double SHOVE_IMPULSE = 0.1 * 0.5;
    @Unique
    private static final double SHOVE_EPSILON = 1.0E-4;

    // The chain drawn back to the cart this one follows, or null when it follows nothing.
    @Unique
    private @Nullable LinkChain minecarttweaks$chain;

    // Game time this cart's tracker withholds position updates until, or zero for none. Zero rather
    // than a negative sentinel because the field starts at Java's default; a real hold is always a
    // live game time plus an offset, so it can never be zero itself.
    @Unique
    private long minecarttweaks$resyncUntil;

    // The train this cart was last placed in, which knows for itself whether it is still good.
    @Unique
    private @Nullable Train minecarttweaks$ownTrain;

    // Where along its train's ribbon the placer last put this cart, and the tick it did. Zero
    // rather than a sentinel because the field starts at Java's default, so a cart's very first
    // tick reads as never seated, which costs that one tick of steering and nothing after.
    @Unique
    private double minecarttweaks$seatedArc;
    @Unique
    private long minecarttweaks$seatedTick;

    // Which arc numbering the seat arc above was measured in. Zero works as "never stamped" on its
    // own: epochs are handed out from a counter that starts at one, so no ribbon ever answers zero.
    @Unique
    private int minecarttweaks$seatedArcEpoch;

    // Which way this cart's nose points, in the world. Null until it has been read once. A
    // heading rather than a sign along the ribbon, so that re-recording the track does not take
    // this with it — see AbstractMinecartExt.
    @Unique
    private Vec3 minecarttweaks$ownNoseVector;

    // This cart's stretch of track, and the tick it was read on. The tick only matters once the
    // context is non-null, so its own zero never reads as fresh.
    @Unique
    private @Nullable RailContext minecarttweaks$rail;
    @Unique
    private long minecarttweaks$railTick;

    // The rail blocks this cart covers, and the tick, position and movement they were walked from.
    // The position and movement are null until the first walk, so that walk is never skipped.
    @Unique
    private @Nullable LongSet minecarttweaks$covered;
    @Unique
    private long minecarttweaks$coveredTick;
    @Unique
    private @Nullable Vec3 minecarttweaks$coveredFrom;
    @Unique
    private @Nullable Vec3 minecarttweaks$coveredMovement;

    // Never called: present only to satisfy the compiler about the superclass this mixin extends.
    public AbstractMinecartMixin(EntityType<?> type, Level level) {
        super(type, level);
        throw new UnsupportedOperationException();
    }

    // Vanilla's own speed ceiling for this cart, which is max_minecart_speed for every kind.
    @Shadow
    protected abstract double getMaxSpeed(ServerLevel level);

    // ---- link state and per-tick memos ----

    @Override
    public List<AbstractMinecart> getNeighbours() {
        var link = EntityAttachments.getLink(minecarttweaks$self());

        // Walked once per car per tick, and most carts hold no links at all.
        if (!link.hasLink()) {
            return List.of();
        }

        var neighbours = new ArrayList<AbstractMinecart>(2);
        minecarttweaks$collect(link.slotA(), neighbours);
        minecarttweaks$collect(link.slotB(), neighbours);
        return neighbours;
    }

    @Override
    public boolean hasLink() {
        return EntityAttachments.getLink(minecarttweaks$self()).hasLink();
    }

    @Override
    public boolean hasFreeSlot() {
        return EntityAttachments.getLink(minecarttweaks$self()).hasFreeSlot();
    }

    @Override
    public void addNeighbour(AbstractMinecart neighbour) {
        AbstractMinecart self = minecarttweaks$self();

        if (neighbour != self) {
            EntityAttachments.setLink(self,
                    EntityAttachments.getLink(self).withNeighbour(neighbour.getUUID()));
            // Both ends: a train is marked on the one object its own cars share, so the two
            // trains being joined are separate objects and neither end's mark reaches the other.
            Train.invalidate(self);
            Train.invalidate(neighbour);
        }
    }

    @Override
    public void removeNeighbour(AbstractMinecart neighbour) {
        AbstractMinecart self = minecarttweaks$self();
        EntityAttachments.setLink(self,
                EntityAttachments.getLink(self).withoutNeighbour(neighbour.getUUID()));
        // Both ends, as in addNeighbour: the graph change marks itself rather than relying on
        // Linkable.unlink coming back round to mark the other side.
        Train.invalidate(self);
        Train.invalidate(neighbour);
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
    public LongSet minecarttweaks$coveredTrack() {
        long now = level().getGameTime();
        Vec3 standingPosition = position();
        Vec3 movement = getDeltaMovement();

        if (minecarttweaks$covered == null || minecarttweaks$coveredTick != now
                || !standingPosition.equals(minecarttweaks$coveredFrom)
                || !movement.equals(minecarttweaks$coveredMovement)) {
            minecarttweaks$covered = RailContext.covering(minecarttweaks$self());
            minecarttweaks$coveredTick = now;
            minecarttweaks$coveredFrom = standingPosition;
            minecarttweaks$coveredMovement = movement;
        }

        return minecarttweaks$covered;
    }

    @Override
    public @Nullable Train minecarttweaks$train() {
        return minecarttweaks$ownTrain;
    }

    @Override
    public void minecarttweaks$setTrain(Train train) {
        minecarttweaks$ownTrain = train;
    }

    @Override
    public long minecarttweaks$seatedAt() {
        return minecarttweaks$seatedTick;
    }

    @Override
    public void minecarttweaks$setSeatedAt(long gameTime) {
        minecarttweaks$seatedTick = gameTime;
    }

    @Override
    public @Nullable Double minecarttweaks$seatArc(int arcEpoch) {
        return minecarttweaks$seatedArcEpoch == arcEpoch ? minecarttweaks$seatedArc : null;
    }

    @Override
    public @Nullable Vec3 minecarttweaks$noseVector() {
        return minecarttweaks$ownNoseVector;
    }

    @Override
    public void minecarttweaks$setNoseVector(Vec3 heading) {
        minecarttweaks$ownNoseVector = heading;
    }

    @Override
    public void minecarttweaks$setSeatArc(double arc, int arcEpoch) {
        minecarttweaks$seatedArc = arc;
        minecarttweaks$seatedArcEpoch = arcEpoch;
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

    // A seated cart is placed along its train's track, so vanilla moving it too would leave it
    // wherever the two land — decided by entity tick order, which nothing here controls. Only the
    // movement is refused; everything else the stepper does belongs to the placer.
    @Inject(method = "moveAlongTrack", at = @At("HEAD"), cancellable = true)
    private void minecarttweaks$placedInstead(ServerLevel level, CallbackInfo info) {
        if (TrainPlacer.seated(minecarttweaks$self())) {
            info.cancel();
        }
    }

    // After the leading cart has moved, not before: the whole train is laid out against where that
    // cart ended this tick, so reading it at the start of some other cart's tick reads it stale by
    // however far the leader is about to travel — four blocks a tick at the speeds this allows.
    @Inject(method = "tick", at = @At("TAIL"))
    private void minecarttweaks$holdTrain(CallbackInfo info) {
        if (level() instanceof ServerLevel serverLevel) {
            TrainCoupling.holdAfterMove(serverLevel, minecarttweaks$self());
            TrainPlacer.holdNose(minecarttweaks$self());
        }
    }

    // Vanilla spawns a cart facing nowhere in particular and lets its first movement decide, so
    // which end of it is the front was settled by whichever way it happened to roll. Every kind of
    // cart is built here, so every kind is faced here.
    //
    // Ahead of the rail adjustment rather than after it: that stamps the rotation it picked into
    // the cart's first interpolation step, and the client draws the step rather than the entity.
    // Setting the rotation afterwards left the server right and the cart visibly wrong until it
    // moved for real.
    @Redirect(method = "createMinecart",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/vehicle/minecart/NewMinecartBehavior;"
                            + "adjustToRails(Lnet/minecraft/core/BlockPos;"
                            + "Lnet/minecraft/world/level/block/state/BlockState;Z)V"))
    private static void minecarttweaks$faceOnPlacement(NewMinecartBehavior behavior, BlockPos pos,
            BlockState state, boolean instant, Level level, double x, double y, double z,
            EntityType<?> type, EntitySpawnReason reason, ItemStack stack,
            @Nullable Player player) {
        if (player != null) {
            MinecartHelper.faceAlongRail(level, pos, behavior, player.getLookAngle());
        }

        behavior.adjustToRails(pos, state, instant);
    }

    // Vanilla's stepper is the only thing that knows which rail blocks a cart really crossed, and
    // it is called once for each of them — so the ribbon is recorded here rather than traced
    // separately, which would be a second opinion about the track to disagree with the first.
    @Inject(method = "makeStepAlongTrack", at = @At("HEAD"))
    private void minecarttweaks$recordTrack(
            BlockPos pos, RailShape shape, double budget, CallbackInfoReturnable<Double> cir) {
        TrainCoupling.recordTrack(minecarttweaks$self(), pos);
    }

    // Vanilla crosses several rail blocks a tick and reads each one's shape off the world as it
    // reaches it, so a rail laid only under the cart at the start of the tick is laid for the
    // first of them and none of the rest. Laid one block ahead of the cart instead, on every step
    // it takes, so that each junction and switch is set the cart's way before it is read.
    @Inject(method = "makeStepAlongTrack", at = @At("HEAD"))
    private void minecarttweaks$steerAhead(
            BlockPos pos, RailShape shape, double budget, CallbackInfoReturnable<Double> cir) {
        if (level() instanceof ServerLevel serverLevel) {
            JunctionRailBlock.steerAhead(serverLevel, minecarttweaks$self(), pos);
            SwitchedRailBlock.steerAhead(serverLevel, minecarttweaks$self(), pos);
        }
    }

    @Override
    public double minecarttweaks$ownMaxSpeed(ServerLevel level) {
        return getMaxSpeed(level);
    }

    // A train runs no faster than its slowest car will allow, whichever end of it happens to be
    // leading. Same shape as the friction hook below and for the same reason: asked at whichever
    // getMaxSpeed override is the outermost for this kind of cart, since a furnace cart halves what
    // this one returns on the way back out and the ceiling has to be the halved figure.
    @Inject(method = "getMaxSpeed", at = @At("RETURN"), cancellable = true)
    private void minecarttweaks$trainSpeed(
            ServerLevel level, CallbackInfoReturnable<Double> cir) {
        AbstractMinecart self = minecarttweaks$self();

        if (self instanceof MinecartFurnace) {
            return;
        }

        cir.setReturnValue(TrainCoupling.trainCeiling(self, level, cir.getReturnValueD()));
    }

    // Which retarding law applies is the model's to say, given both candidates: the movement the
    // cart arrived with, and what vanilla's own body left of it.
    //
    // Asked at whichever override is outermost for this kind of cart — the only one handed the
    // movement the cart really arrived with, which the physical model needs instead of vanilla's
    // figure. A container cart overrides this and returns without calling it, so it asks for
    // itself and never reaches here. A furnace cart overrides it, calls it, but has already
    // scaled the movement on the way past — so it is skipped here and asks at its own return
    // instead.
    @Inject(method = "applyNaturalSlowdown", at = @At("RETURN"), cancellable = true)
    private void minecarttweaks$railFriction(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        AbstractMinecart self = minecarttweaks$self();

        if (self instanceof MinecartFurnace) {
            return;
        }

        // The train's drive rides along, taken by the leading car when it is not itself an engine
        // — which is most trains, since the engine is as often behind. Every other car is placed,
        // so this is the only car that can carry it, and a container cart asks for itself.
        // Resistance first and the drive on top.
        cir.setReturnValue(TrainCoupling.hauled(
                self, RailForces.forLevel(level()).retarded(self, movement, cir.getReturnValue())));
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

        double deltaX = entity.getX() - getX();
        double deltaZ = entity.getZ() - getZ();
        double distanceSquared = deltaX * deltaX + deltaZ * deltaZ;

        if (distanceSquared < SHOVE_EPSILON) {
            return;
        }

        info.cancel();

        double distance = Math.sqrt(distanceSquared);
        Vec3 separationDirection = new Vec3(deltaX / distance, 0.0, deltaZ / distance);

        // The cart's half, vanilla's own: an impulse falling off with distance, capped at touching
        // range. Sent the ordinary way, so the Entity.push mixin divides it by the train's length.
        Vec3 shove = separationDirection.scale(SHOVE_IMPULSE * Math.min(1.0, 1.0 / distance));
        push(-shove.x(), 0.0, -shove.z());

        // The shover is then held to the car it touched, by taking back all the speed it is still
        // closing at — which makes contact a wall rather than a bounce. That car's own movement,
        // which the placer writes along its own rail: round a bend the driven car is heading
        // somewhere else, so reading it would walk the train through whoever stood there.
        double closingSpeed = self.getDeltaMovement().dot(separationDirection)
                - entity.getKnownMovement().dot(separationDirection);

        if (closingSpeed <= 0.0) {
            return;
        }

        Vec3 pushbackVelocity = separationDirection.scale(closingSpeed);
        entity.push(pushbackVelocity.x(), 0.0, pushbackVelocity.z());

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

        // A seated cart is put on its track rather than driven along it, so it reads no rail shape
        // and has none to lay. Steering from one would write the world out of a position the placer
        // chose and a heading it copied off the leader, for a block the cart may not even be on.
        if (!TrainPlacer.seated(self)) {
            JunctionRailBlock.steer(serverLevel, self);
            SwitchedRailBlock.steer(serverLevel, self);
        }

        RailBrake.tick(serverLevel, self);

        // Each cart draws exactly the one chain, to whichever cart it follows.
        AbstractMinecart parent = TrainCoupling.tickTrain(serverLevel, self);
        minecarttweaks$chain = LinkChain.sync(minecarttweaks$chain, self, parent);

        CartImpact.strikeNearby(serverLevel, self);
    }

    // ---- helpers ----

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
