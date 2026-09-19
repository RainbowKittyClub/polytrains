package club.rainbowkitty.minecarttweaks.ext;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.rail.RailContext;
import club.rainbowkitty.minecarttweaks.train.Train;

/**
 * Access interface injected onto {@link AbstractMinecart} to expose link state.
 */
public interface AbstractMinecartExt extends Linkable {

    /**
     * Whether this cart is driving itself at all, whatever it is currently putting out. Only a
     * burning, unparked furnace cart is.
     *
     * <p>Asked separately from {@link #minecarttweaks$driveAcceleration()} because the output falls
     * with speed: an engine near its free-running speed puts out almost nothing, so reading the
     * output as a yes-or-no would answer "not powered" for a cart that plainly is. Everything that
     * wants the fact rather than the figure asks this.
     */
    public default boolean minecarttweaks$driving() {
        return false;
    }

    /**
     * How hard this cart drives itself along the track in blocks per tick squared, or zero if it is
     * not under its own power. Only a burning, unparked furnace cart returns anything.
     *
     * <p>Falls with the cart's own speed under the physical model, so this is a reading taken now
     * and not a property of the cart. Use {@link #minecarttweaks$driving()} to ask whether the
     * engine is lit.
     */
    public default double minecarttweaks$driveAcceleration() {
        return 0.0;
    }

    /** The direction a cart under its own power is pushing in, or zero if it is not under any. */
    public default Vec3 minecarttweaks$driveHeading() {
        return Vec3.ZERO;
    }

    /**
     * Turns this cart's own power to {@code heading}, laid along the rail it is standing on, for a
     * train that has given up trying to move the way it was pointed.
     *
     * <p>Unconditional, unlike {@link #minecarttweaks$aimEngine(Vec3)}: the whole point is to turn
     * an engine round, so the refusals that keep a heading from being knocked about do not apply.
     */
    public default void minecarttweaks$turnEngine(Vec3 heading) {}

    /**
     * Lays this cart's own power along {@code heading}, for a cart the placer is seating.
     *
     * <p>A seated cart never runs vanilla's stepper, and that stepper is the only thing that
     * re-aims an engine off the ground it covered — so an engine in a placed train would otherwise
     * keep the heading it had before it was first seated, through every corner after it. Refusing
     * a reversal is still the engine's own business, so this only offers the heading.
     */
    public default void minecarttweaks$aimEngine(Vec3 heading) {}

    /**
     * This cart's own speed ceiling, as its own kind of cart works it out. Exists so that the car
     * driving a train can ask the cars behind it without reaching for a {@code protected} method on
     * somebody else's instance.
     *
     * <p>Only ever asked of a car that is not driving its train, which is why it answers with that
     * car's own figure and not its train's: the ceiling-sharing hook stands down for any car that
     * is not the one being driven, so asking one is not asking the whole train again.
     */
    public double minecarttweaks$ownMaxSpeed(ServerLevel level);

    /**
     * The stretch of track this cart stands on, read once a tick and held. Collision asks this of
     * every candidate pair, so it must not walk the rails again on each call.
     */
    public RailContext minecarttweaks$railContext();

    /**
     * This cart's box joined to where its own movement would carry it this tick, read once a tick
     * and held.
     *
     * <p>Held for the same reason the rail context is: collision asks each of a pair about the
     * other separately, and a box read live would be read on either side of the cart having moved,
     * giving one of them a wall the other did not have.
     */
    public AABB minecarttweaks$sweptBox();

    /** The train this cart was last placed in, whether or not it is still good to use. */
    public @Nullable Train minecarttweaks$train();

    /** Hands this cart the train it belongs to, sparing its siblings walking their own. */
    public void minecarttweaks$setTrain(Train train);

    /** The game time the placer last seated this cart on its train's track, or zero for never. */
    public long minecarttweaks$seatedAt();

    /** Records that the placer seated this cart at {@code gameTime}. */
    public void minecarttweaks$setSeatedAt(long gameTime);

    /** The arc along its train's ribbon this cart was last seated at. */
    public double minecarttweaks$seatArc();

    /** Records the arc this cart has just been seated at, for the next sweep to start from. */
    public void minecarttweaks$setSeatArc(double arc);

    /**
     * Which way this cart's nose points, as a heading in the world, or null if it has never been
     * recorded.
     *
     * <p>Which end a cart leads with is a fact about the cart: it cannot turn round on a rail, and
     * two cars of one train may honestly face opposite ways. So it is remembered rather than worked
     * out again from which way the train happens to be going.
     *
     * <p>Held as a direction and not as a sign along some track, because a sign is only worth
     * anything while that track keeps the numbering it was taken against — and a ribbon is thrown
     * away and re-recorded often, which would discard this with it every time.
     */
    public @Nullable Vec3 minecarttweaks$nose();

    /** Records which way this cart's nose points. */
    public void minecarttweaks$setNose(Vec3 heading);

    /** The game time this cart's tracker withholds position updates until, or zero for none. */
    public long minecarttweaks$resyncAt();

    /** Withholds this cart's position updates until {@code gameTime}, or zero to stop. */
    public void minecarttweaks$setResyncAt(long gameTime);
}
