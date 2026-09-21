package club.rainbowkitty.minecarttweaks.ext;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.rail.RailContext;
import club.rainbowkitty.minecarttweaks.train.Train;

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
    public default boolean minecarttweaks$driving() {
        return false;
    }

    /**
     * How hard this cart drives itself along the track in blocks per tick squared, or zero if it is
     * not under its own power. Only a burning, unparked furnace cart returns anything.
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
     * train that has given up trying to move the way it was pointed. Unconditional, unlike
     * {@link #minecarttweaks$aimEngine(Vec3)}.
     */
    public default void minecarttweaks$turnEngine(Vec3 heading) {}

    /**
     * Lays this cart's own power along {@code heading}, for a cart the placer is seating.
     */
    public default void minecarttweaks$aimEngine(Vec3 heading) {}

    /**
     * This cart's own speed ceiling, as its own kind of cart works it out. Exists so that the car
     * driving a train can ask the cars behind it without reaching for a {@code protected} method on
     * somebody else's instance. Only ever asked of a car that is not driving its train, so it
     * answers with that car's own figure, not its train's.
     */
    public double minecarttweaks$ownMaxSpeed(ServerLevel level);

    /**
     * The stretch of track this cart stands on, read once a tick and held. Collision asks this of
     * every candidate pair, so it must not walk the rails again on each call.
     */
    public RailContext minecarttweaks$railContext();

    /**
     * This cart's box joined to where its own movement would carry it this tick, read once a tick
     * and held, for the same reason {@link #minecarttweaks$railContext()} is.
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
     */
    public @Nullable Vec3 minecarttweaks$noseVector();

    /** Records which way this cart's nose points. */
    public void minecarttweaks$setNoseVector(Vec3 heading);

    /** The game time this cart's tracker withholds position updates until, or zero for none. */
    public long minecarttweaks$resyncAt();

    /** Withholds this cart's position updates until {@code gameTime}, or zero to stop. */
    public void minecarttweaks$setResyncAt(long gameTime);
}
