package club.rainbowkitty.polytrains.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
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
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartCommandBlock;
import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.polytrains.block.JunctionRailBlock;
import club.rainbowkitty.polytrains.block.SwitchedRailBlock;
import club.rainbowkitty.polytrains.collision.CartImpact;
import club.rainbowkitty.polytrains.collision.CartInteraction;
import club.rainbowkitty.polytrains.collision.OffRails;
import club.rainbowkitty.polytrains.display.CommandCartBox;
import club.rainbowkitty.polytrains.display.LinkChain;
import club.rainbowkitty.polytrains.ext.AbstractMinecartExt;
import club.rainbowkitty.polytrains.init.EntityAttachments;
import club.rainbowkitty.polytrains.rail.RailBrake;
import club.rainbowkitty.polytrains.rail.RailContext;
import club.rainbowkitty.polytrains.rail.RailForces;
import club.rainbowkitty.polytrains.rail.RailPath;
import club.rainbowkitty.polytrains.train.Train;
import club.rainbowkitty.polytrains.train.TrainCoupling;
import club.rainbowkitty.polytrains.train.TrainPlacer;
import club.rainbowkitty.polytrains.util.MinecartHelper;

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
    private @Nullable LinkChain polytrains$chain;

    // A command block cart's box for the wrench, built on its first tick on a server and gone with
    // the cart, as a furnace cart's parts are; null on every other kind of cart.
    @Unique
    private @Nullable CommandCartBox polytrains$commandBox;

    // Game time this cart's tracker withholds position updates until, or zero for none. Zero rather
    // than a negative sentinel because the field starts at Java's default; a real hold is always a
    // live game time plus an offset, so it can never be zero itself.
    @Unique
    private long polytrains$resyncUntil;

    // Whether this cart's tracker appeared since its last tick, so its train is due a resync.
    @Unique
    private boolean polytrains$resyncPendingFlag;

    // The train this cart was last placed in, which knows for itself whether it is still good.
    @Unique
    private @Nullable Train polytrains$ownTrain;
    // This cart's place in that train's car list.
    @Unique
    private int polytrains$ownTrainIndex;

    // Where along its train's ribbon the placer last put this cart, and the tick it did. Zero
    // rather than a sentinel because the field starts at Java's default, so a cart's very first
    // tick reads as never seated, which costs that one tick of steering and nothing after.
    @Unique
    private double polytrains$seatedArc;
    @Unique
    private long polytrains$seatedTick;

    // The tick the placer last flew this cart on through the air, zero for never, as above.
    @Unique
    private long polytrains$flownTick;

    // Which arc numbering the seat arc above was measured in. Zero works as "never stamped" on its
    // own: epochs are handed out from a counter that starts at one, so no ribbon ever answers zero.
    @Unique
    private int polytrains$seatedArcEpoch;

    // Which way this cart's nose points, in the world. Null until it has been read once. A
    // heading rather than a sign along the ribbon, so that re-recording the track does not take
    // this with it — see AbstractMinecartExt.
    @Unique
    private Vec3 polytrains$ownNoseVector;

    // This cart's stretch of track, and the tick it was read on. The tick only matters once the
    // context is non-null, so its own zero never reads as fresh.
    @Unique
    private @Nullable RailContext polytrains$rail;
    @Unique
    private long polytrains$railTick;

    // The track this cart covers, and the tick, position and movement it was walked from. The
    // position and movement are null until the first walk, so that walk is never skipped.
    @Unique
    private RailPath.@Nullable Reach polytrains$covered;
    @Unique
    private long polytrains$coveredTick;
    @Unique
    private @Nullable Vec3 polytrains$coveredFrom;
    @Unique
    private @Nullable Vec3 polytrains$coveredMovement;

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
        var link = EntityAttachments.getLink(polytrains$self());

        // Walked once per car per tick, and most carts hold no links at all.
        if (!link.hasLink()) {
            return List.of();
        }

        var neighbours = new ArrayList<AbstractMinecart>(2);
        polytrains$collect(link.slotA(), neighbours);
        polytrains$collect(link.slotB(), neighbours);
        return neighbours;
    }

    @Override
    public boolean hasLink() {
        return EntityAttachments.getLink(polytrains$self()).hasLink();
    }

    @Override
    public boolean hasFreeSlot() {
        return EntityAttachments.getLink(polytrains$self()).hasFreeSlot();
    }

    @Override
    public void addNeighbour(AbstractMinecart neighbour) {
        AbstractMinecart self = polytrains$self();

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
        AbstractMinecart self = polytrains$self();
        EntityAttachments.setLink(self,
                EntityAttachments.getLink(self).withoutNeighbour(neighbour.getUUID()));
        // Both ends, as in addNeighbour: the graph change marks itself rather than relying on
        // Linkable.unlink coming back round to mark the other side.
        Train.invalidate(self);
        Train.invalidate(neighbour);
    }

    @Override
    public RailContext polytrains$railContext() {
        long now = level().getGameTime();

        if (polytrains$rail == null || polytrains$railTick != now) {
            polytrains$rail = RailContext.of(polytrains$self());
            polytrains$railTick = now;
        }

        return polytrains$rail;
    }

    @Override
    public RailPath.Reach polytrains$coveredTrack() {
        long now = level().getGameTime();
        Vec3 standingPosition = position();
        Vec3 movement = getDeltaMovement();

        if (polytrains$covered == null || polytrains$coveredTick != now
                || !standingPosition.equals(polytrains$coveredFrom)
                || !movement.equals(polytrains$coveredMovement)) {
            polytrains$covered = RailContext.covering(polytrains$self());
            polytrains$coveredTick = now;
            polytrains$coveredFrom = standingPosition;
            polytrains$coveredMovement = movement;
        }

        return polytrains$covered;
    }

    @Override
    public @Nullable Train polytrains$train() {
        return polytrains$ownTrain;
    }

    @Override
    public int polytrains$trainIndex() {
        return polytrains$ownTrainIndex;
    }

    @Override
    public void polytrains$setTrain(Train train, int index) {
        polytrains$ownTrain = train;
        polytrains$ownTrainIndex = index;
    }

    @Override
    public long polytrains$seatedAt() {
        return polytrains$seatedTick;
    }

    @Override
    public void polytrains$setSeatedAt(long gameTime) {
        polytrains$seatedTick = gameTime;
    }

    @Override
    public long polytrains$flownAt() {
        return polytrains$flownTick;
    }

    @Override
    public void polytrains$setFlownAt(long gameTime) {
        polytrains$flownTick = gameTime;
    }

    @Override
    public @Nullable Double polytrains$seatArc(int arcEpoch) {
        return polytrains$seatedArcEpoch == arcEpoch ? polytrains$seatedArc : null;
    }

    @Override
    public @Nullable Vec3 polytrains$noseVector() {
        return polytrains$ownNoseVector;
    }

    @Override
    public void polytrains$setNoseVector(Vec3 heading) {
        polytrains$ownNoseVector = heading;
    }

    @Override
    public void polytrains$setSeatArc(double arc, int arcEpoch) {
        polytrains$seatedArc = arc;
        polytrains$seatedArcEpoch = arcEpoch;
    }

    @Override
    public long polytrains$resyncAt() {
        return polytrains$resyncUntil;
    }

    @Override
    public void polytrains$setResyncAt(long gameTime) {
        polytrains$resyncUntil = gameTime;
    }

    @Override
    public void polytrains$setResyncPending(boolean pending) {
        polytrains$resyncPendingFlag = pending;
    }

    // ---- injections ----

    // A seated cart is placed along its train's track, so vanilla moving it too would leave it
    // wherever the two land — decided by entity tick order, which nothing here controls. Only the
    // movement is refused; everything else the stepper does belongs to the placer. A car the
    // placer flies through the air behind its train's leading car is moved once, by it, too.
    @Inject(method = "moveAlongTrack", at = @At("HEAD"), cancellable = true)
    private void polytrains$placedInstead(ServerLevel level, CallbackInfo info) {
        AbstractMinecart self = polytrains$self();

        if (TrainPlacer.seated(self) || TrainPlacer.flown(self)) {
            info.cancel();
        }
    }

    // After the leading cart has moved, not before: the whole train is laid out against where that
    // cart ended this tick, so reading it at the start of some other cart's tick reads it stale by
    // however far the leader is about to travel — four blocks a tick at the speeds this allows.
    @Inject(method = "tick", at = @At("TAIL"))
    private void polytrains$holdTrain(CallbackInfo info) {
        if (level() instanceof ServerLevel serverLevel) {
            TrainCoupling.holdAfterMove(serverLevel, polytrains$self());
            TrainPlacer.holdNose(polytrains$self());
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
    private static void polytrains$faceOnPlacement(NewMinecartBehavior behavior, BlockPos pos,
            BlockState state, boolean instant, Level level, double x, double y, double z,
            EntityType<?> type, EntitySpawnReason reason, ItemStack stack,
            @Nullable Player player) {
        if (player != null) {
            MinecartHelper.faceAlongRail(
                    level, pos, behavior, MinecartHelper.placementLook(type, player));
        }

        behavior.adjustToRails(pos, state, instant);
    }

    // Vanilla's stepper is the only thing that knows which rail blocks a cart really crossed, and
    // it is called once for each of them — so the ribbon is recorded here rather than traced
    // separately, which would be a second opinion about the track to disagree with the first.
    @Inject(method = "makeStepAlongTrack", at = @At("HEAD"))
    private void polytrains$recordTrack(
            BlockPos pos, RailShape shape, double budget, CallbackInfoReturnable<Double> cir) {
        TrainCoupling.recordTrack(polytrains$self(), pos);
    }

    // Vanilla crosses several rail blocks a tick and reads each one's shape off the world as it
    // reaches it, so a rail laid only under the cart at the start of the tick is laid for the
    // first of them and none of the rest. Laid one block ahead of the cart instead, on every step
    // it takes, so that each junction and switch is set the cart's way before it is read.
    @Inject(method = "makeStepAlongTrack", at = @At("HEAD"))
    private void polytrains$steerAhead(
            BlockPos pos, RailShape shape, double budget, CallbackInfoReturnable<Double> cir) {
        if (level() instanceof ServerLevel serverLevel) {
            BlockPos nextPos = RailPath.ahead(serverLevel, pos, getDeltaMovement());

            if (nextPos != null) {
                JunctionRailBlock.layAhead(serverLevel, pos, nextPos);
                SwitchedRailBlock.orientAhead(serverLevel, polytrains$self(), pos, nextPos);
            }
        }
    }

    @Override
    public double polytrains$ownMaxSpeed(ServerLevel level) {
        return getMaxSpeed(level);
    }

    // A train runs no faster than its slowest car will allow, whichever end of it happens to be
    // leading. Same shape as the friction hook below and for the same reason: asked at whichever
    // getMaxSpeed override is the outermost for this kind of cart, since a furnace cart halves what
    // this one returns on the way back out and the ceiling has to be the halved figure.
    @Inject(method = "getMaxSpeed", at = @At("RETURN"), cancellable = true)
    private void polytrains$trainSpeed(
            ServerLevel level, CallbackInfoReturnable<Double> cir) {
        AbstractMinecart self = polytrains$self();

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
    private void polytrains$railFriction(Vec3 movement, CallbackInfoReturnable<Vec3> cir) {
        AbstractMinecart self = polytrains$self();

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
    private void polytrains$driveThrough(
            Entity entity, CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ()
                && !CartInteraction.of(polytrains$self(), entity).blocks()) {
            cir.setReturnValue(false);
        }
    }

    // Vanilla's move stops a box only at a face it has yet to cross, so a car off its rails that
    // is already inside another train's car would be carried on into it. An axis held back loses
    // its speed, as one a wall stops does. A car flown off a jump passes its own train's cars in
    // vanilla's move, so those stop it first, its speed matched: lost, it would drop short.
    @WrapOperation(method = "comeOffTrack",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/world/entity/vehicle/minecart/AbstractMinecart;"
                            + "move(Lnet/minecraft/world/entity/MoverType;"
                            + "Lnet/minecraft/world/phys/Vec3;)V"))
    private void polytrains$moveOffRails(AbstractMinecart cart, MoverType moverType,
            Vec3 movement, Operation<Void> move) {
        Vec3 held = OffRails.behindOwnTrain(cart, movement);
        Vec3 allowed = OffRails.shallowerPart(cart, held);

        move.call(cart, moverType, allowed);

        if (!allowed.equals(held)) {
            Vec3 speed = cart.getDeltaMovement();

            cart.setDeltaMovement(allowed.x() == held.x() ? speed.x() : 0.0,
                    allowed.y() == held.y() ? speed.y() : 0.0,
                    allowed.z() == held.z() ? speed.z() : 0.0);
        }
    }

    // Which pairs shove each other at all is CartInteraction's to say. Vanilla resolves a cart's
    // own shove the other way round, through Entity.push(Entity), but skips that on a tick the
    // cart collided with nothing — so a standing train is only ever met here.
    @Inject(
            method = "push(Lnet/minecraft/world/entity/Entity;)V",
            at = @At("HEAD"),
            cancellable = true)
    private void polytrains$shoveReaction(Entity entity, CallbackInfo info) {
        AbstractMinecart self = polytrains$self();

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
    private void polytrains$tick(CallbackInfo info) {
        if (!(level() instanceof ServerLevel serverLevel)) {
            return;
        }

        AbstractMinecart self = polytrains$self();

        if (polytrains$commandBox == null && self instanceof MinecartCommandBlock) {
            polytrains$commandBox = CommandCartBox.attach(self);
        }

        // A seated cart is put on its track rather than driven along it, so it reads no rail shape
        // and has none to lay. Steering from one would write the world out of a position the placer
        // chose and a heading it copied off the leader, for a block the cart may not even be on.
        boolean seated = TrainPlacer.seated(self);
        boolean watchesBrake = !(self instanceof MinecartFurnace);

        // One read for both; a seated furnace cart needs neither, so it reads nothing.
        if (!seated || watchesBrake) {
            BlockPos railPos = self.getCurrentBlockPosOrRailBelow();
            BlockState rail = serverLevel.getBlockState(railPos);

            if (!seated) {
                JunctionRailBlock.steer(serverLevel, self, railPos, rail);
                SwitchedRailBlock.steer(serverLevel, self);
            }

            if (watchesBrake) {
                RailBrake.tick(serverLevel, self, rail);
            }
        }

        if (polytrains$resyncPendingFlag) {
            polytrains$resyncPendingFlag = false;

            if (self.hasLink()) {
                TrainCoupling.resyncTrainOnce(self);
            }
        }

        // Each cart draws exactly the one chain, to whichever cart it follows.
        AbstractMinecart parent = TrainCoupling.tickTrain(serverLevel, self);
        polytrains$chain = LinkChain.sync(polytrains$chain, self, parent);

        CartImpact.strikeNearby(serverLevel, self);
    }

    // ---- helpers ----

    // This mixin as the cart it is applied to.
    @Unique
    private AbstractMinecart polytrains$self() {
        return (AbstractMinecart) (Object) this;
    }

    // Adds the cart a stored neighbour slot names, if it holds one that still exists.
    @Unique
    private void polytrains$collect(Optional<UUID> ref, List<AbstractMinecart> into) {
        if (ref.isEmpty() || !(level() instanceof ServerLevel serverLevel)) {
            return;
        }

        if (serverLevel.getEntity(ref.get()) instanceof AbstractMinecart cart) {
            into.add(cart);
        }
    }
}
