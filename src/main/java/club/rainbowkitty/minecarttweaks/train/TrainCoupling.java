package club.rainbowkitty.minecarttweaks.train;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.ext.Linkable;
import club.rainbowkitty.minecarttweaks.rail.RailPath;
import club.rainbowkitty.minecarttweaks.rule.MTGameRules;
import club.rainbowkitty.minecarttweaks.util.MinecartHelper;

/**
 * The physics of a train. Its cars are held at one speed as a single body rather than each link
 * being sprung against its neighbours, and the spacing between them is corrected on their positions
 * along the rails.
 */
public final class TrainCoupling {
    // Fraction of the spacing error taken up per tick. Short of all of it because a car is moved
    // straight to its new position, and closing a large error in one tick is a visible jump.
    private static final double SETTLE = 0.5;

    // Ceiling on that correction, in car widths, so a car is never seen to jump.
    private static final double MAX_SHIFT = 0.25;

    // Furthest centre-to-centre distance two carts may be linked at, the spacing the coupling holds
    // them to, and how far past the link distance a stretched link survives before breaking.
    // One geometry: none of the three can be retuned without the others.
    private static final double MAX_LINK_DISTANCE = 2.25;

    private static final double TARGET_SPACING = 2.0;

    private static final double SEVER_SLACK = 1.25;

    // Two batches: one for the batch a client may already be sitting on, one to leave it with
    // nothing, since a client that is never left short goes on replaying at the offset it has.
    private static final int RESYNC_HOLD = 2 * NewMinecartBehavior.POS_ROT_LERP_TICKS;

    // Cars in a train nobody can shift by leaning on it, counted in cars because the length is the
    // part a player can see before they try.
    private static final int PUSHABLE_CARS = 8;

    // Trailers each engine pulls at full output before the rest of the train divides it down.
    private static final int FREE_CARS = 2;

    // Fraction of the train's speed each bend costs, billed to the whole train, not the one car.
    private static final double BEND_LOSS = 0.005;

    // Below this a length is treated as zero.
    private static final double EPSILON = 1.0E-5;

    // Static-only; blocks instantiation.
    private TrainCoupling() {}

    /**
     * Settles the train {@code cart} belongs to and returns the car it follows, or null if it heads
     * its train, is unlinked, sits in a closed loop, or trains are switched off.
     */
    public static @Nullable AbstractMinecart tickTrain(ServerLevel level, AbstractMinecart cart) {
        // Links themselves are left alone while trains are off, so turning them back on picks up
        // where it left off; only the following and the chain stop.
        if (!MTGameRules.trainsEnabled(level)) {
            return null;
        }

        if (!cart.hasLink()) {
            return null;
        }

        // Links are undirected, so which car chases which is derived from them rather than stored.
        Train train = Train.of(cart);

        // One car settles the whole train once a tick, rather than each link fighting the one next
        // to it. Which one is settled between the cars themselves so no cart has to be told.
        if (train.settler() == cart) {
            holdTrain(level, train);
        }

        return train.follows(cart);
    }

    /**
     * Whether {@code other} is a car of the same train as {@code cart}. False whenever trains are
     * off, so nothing built on this applies to a world running without them.
     */
    public static boolean sameTrain(AbstractMinecart cart, Entity other) {
        // Every car of a train is handed the same train instance, so membership is an identity
        // test rather than a scan — this is asked per collision candidate per movement step.
        return other instanceof AbstractMinecart car
                && cart.hasLink()
                && MTGameRules.trainsEnabled(cart.level())
                && Train.of(cart) == Train.of(car);
    }

    /**
     * Asks every car in {@code cart}'s train to hold its position updates until one common tick, so
     * that a client replaying them out of step with each other is made to re-time itself.
     */
    public static void resyncTrain(AbstractMinecart cart) {
        for (AbstractMinecart member : Train.of(cart).cars()) {
            member.minecarttweaks$setResyncAt(member.level().getGameTime() + RESYNC_HOLD);
        }
    }

    /**
     * The acceleration each car in {@code cart}'s train gets. Every engine hauls {@link #FREE_CARS}
     * trailers at its full output before the rest of the train starts dividing that output down, so
     * a furnace cart and two cars climb a slope as readily as one alone and only a longer train
     * falls off. A cart on its own is a train of one and is unaffected.
     */
    public static double sharedDrive(AbstractMinecart cart) {
        List<AbstractMinecart> cars = Train.of(cart).cars();
        double drive = 0.0;
        int engines = 0;

        for (AbstractMinecart member : cars) {
            double output = member.minecarttweaks$driveAcceleration();
            drive += output;

            if (output > 0.0) {
                engines++;
            }
        }

        return engines == 0 ? 0.0 : drive / Math.max(1, cars.size() - FREE_CARS * engines);
    }

    /**
     * Whether anything is driving {@code cart}, its own engine or one it is coupled to. Ordered so
     * an unlinked cart answers off its own field without walking a train.
     */
    public static boolean underPower(AbstractMinecart cart) {
        return cart.minecarttweaks$driveAcceleration() > 0
                || (cart.hasLink() && sharedDrive(cart) > 0);
    }

    /**
     * Whether anyone aboard {@code cart}'s train is steering it. Vanilla's rider nudge is a
     * thousandth of a block per tick, under the floor the dead stop cuts to zero, so it does not
     * survive its own first tick unless asked about here. Bounded by {@link #shovable}: a train too
     * long to shove from outside is too long to shuffle from inside.
     */
    public static boolean riderSteering(AbstractMinecart cart) {
        if (!cart.hasLink()) {
            return steering(cart);
        }

        if (!shovable(cart)) {
            return false;
        }

        // The cart is in its own train, so this covers its own rider too.
        for (AbstractMinecart member : Train.of(cart).cars()) {
            if (steering(member)) {
                return true;
            }
        }

        return false;
    }

    /**
     * The direction the train's powered cars are pushing in, or zero if none of them is. Summed, so
     * two furnace carts aimed against each other cancel. For starting from a standstill only, where
     * a car has no movement of its own to read a heading off.
     */
    public static Vec3 driveHeading(AbstractMinecart cart) {
        Vec3 heading = Vec3.ZERO;

        for (AbstractMinecart member : Train.of(cart).cars()) {
            heading = heading.add(member.minecarttweaks$driveHeading());
        }

        return heading.lengthSqr() > EPSILON ? heading.normalize() : Vec3.ZERO;
    }

    /** The furthest centre-to-centre distance at which two carts may be linked. */
    public static double maxLinkDistance() {
        return MAX_LINK_DISTANCE;
    }

    /**
     * Whether {@code cart}'s train is short enough to be shifted by leaning on it at all, which
     * stops at {@link #PUSHABLE_CARS}. How hard it is below that is {@link #mass}'s to say.
     */
    public static boolean shovable(AbstractMinecart cart) {
        return mass(cart) < PUSHABLE_CARS;
    }

    /**
     * How fast {@code cart}'s train is travelling along {@code line}, averaged over its cars.
     *
     * <p>What a car is about to be doing rather than what it is doing. A shove lands whole on the
     * one car that was touched and is not shared out until the train next settles, so a car read
     * the instant it is pushed reports the same speed whatever it is coupled to.
     */
    public static double sharedSpeed(AbstractMinecart cart, Vec3 line) {
        List<AbstractMinecart> cars = Train.of(cart).cars();
        double total = 0.0;

        for (AbstractMinecart member : cars) {
            total += member.getDeltaMovement().dot(line);
        }

        return total / cars.size();
    }

    /**
     * The mass of {@code cart}'s train, counted in cars. A cart on its own is a train of one, and a
     * loaded car weighs what an empty one does — every threshold measured against this is expressed
     * in these units, so the unit cannot change without retuning all of them together.
     */
    public static double mass(AbstractMinecart cart) {
        // Short-circuited rather than walked: this is asked of every cart every tick, and most
        // carts on a server are not linked to anything.
        return cart.hasLink() ? Train.of(cart).size() : 1;
    }

    /**
     * How hard two trains are about to meet, or zero if they are not closing on each other at all.
     *
     * <p>The rate the gap shrinks rather than either train's own speed, so a heavy train merely
     * catching a light one up barely registers, times the reduced mass {@code m_a·m_b/(m_a+m_b)} —
     * which keeps a loaded train hitting a parked cart on the parked cart's scale, not the train's.
     */
    public static double closingMomentum(AbstractMinecart a, AbstractMinecart b) {
        Vec3 between = b.position().subtract(a.position()).horizontal();

        if (between.lengthSqr() < EPSILON) {
            return 0.0;
        }

        Vec3 line = between.normalize();
        double closing = a.getDeltaMovement().dot(line) - b.getDeltaMovement().dot(line);

        if (closing <= 0.0) {
            return 0.0;
        }

        double massA = mass(a);
        double massB = mass(b);

        return closing * massA * massB / (massA + massB);
    }

    /**
     * Settles every link in {@code train}, breaking any that have been stretched past saving. A car
     * off the track keeps its link and its own physics until it lands or is dragged past the
     * breaking distance, with the runs either side of it settled meanwhile.
     */
    private static void holdTrain(ServerLevel level, Train train) {
        List<AbstractMinecart> members = train.cars();
        List<AbstractMinecart> cars = new ArrayList<>();
        List<RailPath> links = new ArrayList<>();
        double breaking = MAX_LINK_DISTANCE * SEVER_SLACK;
        cars.add(members.getFirst());

        for (int i = 1; i < members.size(); i++) {
            AbstractMinecart next = members.get(i);
            AbstractMinecart current = cars.getLast();
            RailPath path = RailPath.between(current, next, breaking);

            boolean severed = path == null
                    ? current.distanceTo(next) > breaking
                    : path.distance() > breaking;

            // What is left behind the break is a train of its own from the next tick on, and is
            // left to settle itself then rather than being settled as part of this one.
            if (severed) {
                Linkable.unlink(current, next);
                MinecartHelper.dropChain(level, current, next);
                break;
            }

            // A car with no path to the one ahead of it — off the rails, or too far round a corner
            // to trace — ends the run, and starts the next one behind itself.
            if (path == null) {
                settle(cars, links);
                cars = new ArrayList<>();
                links = new ArrayList<>();
            } else {
                links.add(path);
            }

            cars.add(next);
        }

        settle(cars, links);
    }

    // Gives every car in one unbroken run of the train a shared speed along its own rails, and
    // corrects their spacing on their positions.
    private static void settle(List<AbstractMinecart> cars, List<RailPath> links) {
        if (cars.size() < 2) {
            return;
        }

        Vec3[] forward = new Vec3[cars.size()];
        forward[0] = links.getFirst().fromForward();

        for (int i = 1; i < cars.size(); i++) {
            forward[i] = links.get(i - 1).toForward();
        }

        double speed = 0.0;
        int bends = 0;

        for (int i = 0; i < cars.size(); i++) {
            speed += signedSpeed(cars.get(i), forward[i]);
            bends += cars.get(i).minecarttweaks$bendsEntered();
        }

        speed *= Math.max(0.0, 1.0 - BEND_LOSS * bends) / cars.size();

        // Closing a link means moving the car behind it forward by the slack in it, on top of
        // wherever the car ahead is going, so one pass down the train places all of them.
        double[] shift = new double[cars.size()];
        double drift = 0.0;

        for (int i = 1; i < cars.size(); i++) {
            shift[i] = shift[i - 1] + slack(links.get(i - 1));
            drift += shift[i];
        }

        // Measured from the head, closing up a train walks all of it forward. Taking the mean back
        // off leaves the same spacing with the train where it already was.
        drift /= cars.size();

        for (int i = 0; i < cars.size(); i++) {
            AbstractMinecart car = cars.get(i);
            double reach = car.getBbWidth() * MAX_SHIFT;

            car.setDeltaMovement(forward[i].scale(speed));
            car.setPos(car.position().add(RailPath.alongTrack(car, forward[i]).scale(
                    Math.clamp((shift[i] - drift) * SETTLE, -reach, reach))));
        }
    }

    // One car's rider asking to move. Client intent rather than the player's own velocity, which a
    // passenger does not have — it is the same field vanilla's own nudge reads.
    private static boolean steering(AbstractMinecart cart) {
        return cart.getFirstPassenger() instanceof ServerPlayer player
                && player.getLastClientMoveIntent().lengthSqr() > 0.0;
    }

    // Halfway round a corner the exit a car leaves by is square to the way it is currently moving,
    // so its speed is its own movement's length with a sign rather than a projection onto that
    // exit, which would collapse to zero and throw the train's speed away every corner.
    private static double signedSpeed(AbstractMinecart car, Vec3 forward) {
        Vec3 movement = car.getDeltaMovement().horizontal();
        double speed = movement.length();

        if (speed < EPSILON) {
            return 0.0;
        }

        Vec3 taken = RailPath.exitTaken(car);

        // An exact match, since the other exit of a corner scores zero against this one and a car
        // heading down it is going the other way round the bend, not forwards.
        boolean onward = taken == null
                ? movement.dot(forward) >= 0.0
                : taken.dot(forward) > 0.5;

        return onward ? speed : -speed;
    }

    // How much further apart than the target spacing two linked carts are along the rails.
    private static double slack(RailPath link) {
        return link.distance() - TARGET_SPACING;
    }
}
