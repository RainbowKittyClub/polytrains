package club.rainbowkitty.serversidetrains.ext;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.serversidetrains.rail.RailContext;
import club.rainbowkitty.serversidetrains.rail.RailPath;
import club.rainbowkitty.serversidetrains.train.Train;

/**
 * Access interface injected onto {@link AbstractMinecart} for this mod's own per-cart state:
 * driving and power, cached rail geometry, train membership and seating, nose heading, and
 * position-sync timing. Link-graph access lives on {@link Linkable}, which this interface extends.
 *
 * <p>See {@code docs/abstract-minecart-ext.md} for the rationale behind each accessor.
 */
public interface AbstractMinecartExt extends Linkable {

    /**
     * Whether this cart is driving itself at all, whatever it is currently putting out. Only a
     * burning, unparked furnace cart is.
     */
    public default boolean serversidetrains$driving() {
        return false;
    }

    /**
     * How hard this cart drives itself along the track in blocks per tick squared, or zero if it is
     * not under its own power. Only a burning, unparked furnace cart returns anything.
     */
    public default double serversidetrains$driveAcceleration() {
        return 0.0;
    }

    /** The direction a cart under its own power is pushing in, or zero if it is not under any. */
    public default Vec3 serversidetrains$driveHeading() {
        return Vec3.ZERO;
    }

    /**
     * Turns this cart's own power to {@code heading}, laid along the rail it is standing on, for a
     * train that has given up trying to move the way it was pointed. Unconditional, unlike
     * {@link #serversidetrains$aimEngine(Vec3)}.
     */
    public default void serversidetrains$turnEngine(Vec3 heading) {}

    /**
     * Lays this cart's own power along {@code heading}, for a cart the placer is seating.
     */
    public default void serversidetrains$aimEngine(Vec3 heading) {}

    /** Ticks of burn time this cart has left, or zero for a cart that burns nothing. */
    public default int serversidetrains$fuel() {
        return 0;
    }

    /**
     * Whether this cart is under its own power and has begun counting ticks its train covered no
     * ground on, towards turning it round. Only a burning, unparked furnace cart ever is.
     */
    public default boolean serversidetrains$stalling() {
        return false;
    }

    /**
     * This cart's own speed ceiling, as its own kind of cart works it out. Exists so that the car
     * driving a train can ask the cars behind it without reaching for a {@code protected} method on
     * somebody else's instance. Asked of any other car it answers with that car's own figure; asked
     * of the car driving a train, with the train's, which is the one that car is clamped to.
     */
    public double serversidetrains$ownMaxSpeed(ServerLevel level);

    /**
     * The stretch of track this cart stands on, read once a tick and held. Collision asks this of
     * every candidate pair, so it must not walk the rails again on each call.
     */
    public RailContext serversidetrains$railContext();

    /**
     * The track this cart can be over before its next move ends: see {@link RailContext#covering}.
     * Held until the cart moves, its movement changes or the tick does, since collision asks it of
     * every candidate pair.
     */
    public RailPath.Reach serversidetrains$coveredTrack();

    /** The train this cart was last placed in, whether or not it is still good to use. */
    public @Nullable Train serversidetrains$train();

    /** Hands this cart the train it belongs to, sparing its siblings walking their own. */
    public void serversidetrains$setTrain(Train train);

    /** The game time the placer last seated this cart on its train's track, or zero for never. */
    public long serversidetrains$seatedAt();

    /** Records that the placer seated this cart at {@code gameTime}. */
    public void serversidetrains$setSeatedAt(long gameTime);

    /** The game time the placer last flew this cart on through the air, or zero for never. */
    public long serversidetrains$flownAt();

    /** Records that the placer flew this cart at {@code gameTime}. */
    public void serversidetrains$setFlownAt(long gameTime);

    /**
     * The arc along its train's ribbon this cart was last seated at, or null if that arc was
     * recorded against a different numbering than {@code arcEpoch} — a reset since renumbered it,
     * and an arc from the old frame describes nothing in the new one.
     */
    public @Nullable Double serversidetrains$seatArc(int arcEpoch);

    /**
     * Records the arc this cart has just been seated at, against the arc numbering it was measured
     * in, so that what put the cart there and what later asks where it sits cannot disagree.
     */
    public void serversidetrains$setSeatArc(double arc, int arcEpoch);

    /**
     * Which way this cart's nose points, as a heading in the world, or null if it has never been
     * recorded.
     */
    public @Nullable Vec3 serversidetrains$noseVector();

    /** Records which way this cart's nose points. */
    public void serversidetrains$setNoseVector(Vec3 heading);

    /** The game time this cart's tracker withholds position updates until, or zero for none. */
    public long serversidetrains$resyncAt();

    /** Withholds this cart's position updates until {@code gameTime}, or zero to stop. */
    public void serversidetrains$setResyncAt(long gameTime);
}
