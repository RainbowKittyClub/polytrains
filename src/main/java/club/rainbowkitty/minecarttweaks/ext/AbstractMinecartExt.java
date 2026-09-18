package club.rainbowkitty.minecarttweaks.ext;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.rail.RailContext;
import club.rainbowkitty.minecarttweaks.train.Train;

/**
 * Access interface injected onto {@link AbstractMinecart} to expose link state.
 */
public interface AbstractMinecartExt extends Linkable {

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

    /** The game time this cart's tracker withholds position updates until, or zero for none. */
    public long minecarttweaks$resyncAt();

    /** Withholds this cart's position updates until {@code gameTime}, or zero to stop. */
    public void minecarttweaks$setResyncAt(long gameTime);
}
