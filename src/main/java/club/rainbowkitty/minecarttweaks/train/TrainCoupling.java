package club.rainbowkitty.minecarttweaks.train;

import java.util.List;

import com.mojang.logging.LogUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.ext.Linkable;
import club.rainbowkitty.minecarttweaks.rail.RailPath;
import club.rainbowkitty.minecarttweaks.rail.TrackRibbon;
import club.rainbowkitty.minecarttweaks.rule.MTGameRules;
import club.rainbowkitty.minecarttweaks.util.MinecartHelper;

/**
 * What holds a train together: its trailing cars are placed along the track its leading car
 * recorded, and its links are cut when that track can no longer answer for one of them.
 *
 * <p>Spacing is built rather than corrected. There is no shared speed to agree on and no gap to
 * measure, so the ways a train used to come apart — a link stretching on a bend, across a chunk
 * border, or under a shove — have nothing left to act through. What breaks a train now is an event
 * with a name.
 */
public final class TrainCoupling {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Furthest centre-to-centre distance two carts may be linked at, and how far apart the cars of
    // a linked train then sit. The first is what a player is allowed to couple; the second is only
    // a layout figure, since nothing is measured against it any more.
    private static final double MAX_LINK_DISTANCE = 2.25;

    private static final double TARGET_SPACING = 1.5;

    // How far from a block to look for a car of a train that might be lying across it. A train at
    // the speeds this allows moves several blocks between ticks, so the car nearest a block can be
    // well past it by the time anything asks; the ribbon then says exactly which blocks the train
    // covers, so this only has to be wide enough to find one of its cars.
    private static final double CROSSING_REACH = 8.0;

    // Consecutive ticks the ribbon must fail to place the same car before its link is cut. A car
    // crossing a junction, catching air, or waiting on a ribbon that has not grown to reach it is
    // unplaceable briefly and then fine, and a sever is not.
    private static final int SEVER_TICKS = 10;

    // Two batches: one for the batch a client may already be sitting on, one to leave it with
    // nothing, since a client that is never left short goes on replaying at the offset it has.
    private static final int RESYNC_HOLD = 2 * NewMinecartBehavior.POS_ROT_LERP_TICKS;

    // Cars in a train nobody can shift by leaning on it, counted in cars because the length is the
    // part a player can see before they try.
    private static final int PUSHABLE_CARS = 8;

    // Trailers each engine pulls at full output before the rest of the train divides it down.
    private static final int FREE_CARS = 2;

    // Track kept either side of the train when the ribbon is trimmed, on top of the train's own
    // length. Room for the leading car to run into before the next record extends it.
    private static final double RIBBON_MARGIN = 8.0;

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

        return train.follows(cart);
    }

    /**
     * Lays out {@code cart}'s train if it is the car that drives it, called once {@code cart} has
     * finished moving. The train is placed against where its leading car ended the tick, so this
     * has to run after that car's own movement and before nothing in particular.
     *
     * <p>The leading car drives rather than a fixed end of the train, because it is the one whose
     * position everything else is measured from. A train with no leading end — closed, or never yet
     * moved — falls back to its anchor, so that exactly one car still asks on behalf of a train the
     * placer cannot yet answer for.
     */
    public static void holdAfterMove(ServerLevel level, AbstractMinecart cart) {
        if (!MTGameRules.trainsEnabled(level) || !cart.hasLink()) {
            return;
        }

        Train train = Train.of(cart);
        AbstractMinecart driver = train.leader();

        if ((driver == null ? train.anchor() : driver) == cart) {
            holdTrain(level, train);
        }
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

    /** The distance along the rails the coupling holds consecutive cars at. */
    public static double targetSpacing() {
        return TARGET_SPACING;
    }

    /**
     * Records the rail block {@code cart} is stepping through on its train's ribbon. Only the car
     * leading its train records: a trailing car crosses blocks the ribbon already holds in its
     * middle, which the ribbon cannot tell from a jump and would answer by starting over.
     *
     * <p>Nothing is driven from the ribbon yet. This only builds it.
     */
    public static void recordTrack(AbstractMinecart cart, BlockPos pos) {
        if (!MTGameRules.trainsEnabled(cart.level()) || !cart.hasLink()) {
            return;
        }

        Train train = Train.of(cart);

        if (train.leader() == cart) {
            train.ribbon().record(
                    cart.level(), pos, cart.getDeltaMovement().horizontal());
        }
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
     * one car that carries it and is not shared out at all, so a car read the instant its train is
     * pushed reports the same speed whatever it is coupled to.
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
     * How fast {@code cart}'s train is really travelling, in blocks per tick. Read off the car that
     * leads it, which is the one vanilla still drives along the rails — every other car's movement
     * is written by the placer from this same figure, so asking one of those is reading back what
     * was written rather than measuring anything.
     */
    public static double drivenSpeed(AbstractMinecart cart) {
        if (!cart.hasLink() || !MTGameRules.trainsEnabled(cart.level())) {
            return cart.getDeltaMovement().length();
        }

        AbstractMinecart leader = Train.of(cart).leader();

        return (leader == null ? cart : leader).getDeltaMovement().length();
    }

    /**
     * How much of vanilla's downhill pull on {@code cart} its whole train is owed, as a multiple of
     * the one car's worth vanilla works out.
     *
     * <p>Gravity is the one force the placer took away without replacing. It is added inside
     * {@code moveAlongTrack}, which a seated car never reaches, so a train on a grade gets exactly
     * one car's pull however many cars are on the slope — and carries the mass of all of them.
     *
     * <p>Force over mass, which is what the rest of this class already does with the engine. Each
     * car on a grade pulls downhill and each car anywhere at all resists, so the share is the sum
     * of the cars' grades over their number, signed by the grade vanilla read off the leading car.
     * A train wholly on one slope comes to exactly one, which is right: a long train rolls downhill
     * no faster than a single cart. A train with one car over the brow comes to a fraction of it,
     * and one balanced across a crest comes to nothing.
     */
    public static double slopeShare(AbstractMinecart cart, int under) {
        if (under == 0 || !cart.hasLink() || !MTGameRules.trainsEnabled(cart.level())) {
            return 1.0;
        }

        List<AbstractMinecart> cars = Train.of(cart).cars();
        int grades = 0;

        for (AbstractMinecart car : cars) {
            grades += RailPath.bend(car).grade();
        }

        return (double) (under * grades) / cars.size();
    }

    /**
     * The car of {@code cart}'s train whose movement is an input rather than an output — the one
     * vanilla still drives along the rails, and so the only one a shove can be laid on.
     *
     * <p>The mirror of {@link #drivenSpeed}. Every other car's movement is written by the placer
     * from the leading car's, so a shove landing on one of those is overwritten before it has
     * moved anything: leaning on a train did nothing unless that end happened to be the one
     * leading, and which end that is outlives a reversal.
     */
    public static AbstractMinecart driven(AbstractMinecart cart) {
        if (!cart.hasLink() || !MTGameRules.trainsEnabled(cart.level())) {
            return cart;
        }

        Train train = Train.of(cart);
        AbstractMinecart leader = train.leader();

        if (leader != null) {
            return leader;
        }

        // A train that has never moved has no leading end to read. Its anchor is the one car every
        // car of it agrees on, so a shove at either end still arrives somewhere that can carry it.
        AbstractMinecart anchor = train.anchor();

        return anchor == null ? cart : anchor;
    }

    /**
     * Whether any train is lying across the rail at {@code pos} — not merely whether a cart is
     * inside that block this instant.
     *
     * <p>A switch asks this before laying its resting curve back on. At speed a cart is inside a
     * given block for a fraction of a tick and the gap between two cars passes over one entirely,
     * so a check for a cart standing there reads a long train as a run of brief absences and
     * changes the rails halfway along it. Only the leading car reads rails now, so nothing else
     * re-arms the wait either. The ribbon records the track the train is on, which answers for its
     * whole length at once.
     */
    public static boolean crossedByTrain(Level level, BlockPos pos) {
        if (!MTGameRules.trainsEnabled(level)) {
            return false;
        }

        for (AbstractMinecart cart : level.getEntitiesOfClass(
                AbstractMinecart.class, new AABB(pos).inflate(CROSSING_REACH),
                AbstractMinecart::hasLink)) {
            if (spans(Train.of(cart), pos)) {
                return true;
            }
        }

        return false;
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
     * Holds {@code train} together by seating it on the track its leading car recorded, and cuts it
     * where that cannot be done.
     *
     * <p>There is no second system. A train is placed or it is not, and a train that is not is
     * either waiting — standing still, or on a ribbon not yet grown to reach it — or else coming
     * apart for a reason this can name. Nothing corrects a car towards where it ought to be, so
     * there is no correction to lose a race against, and no gap that has to be measured.
     */
    private static void holdTrain(ServerLevel level, Train train) {
        reachTrain(level, train);

        TrainPlacer.Refusal refusal = TrainPlacer.seat(train, TARGET_SPACING);

        if (refusal != null) {
            cutIfLost(level, train, refusal);
            return;
        }

        // One fault reading a tick, whichever kind it is, so the two cannot clear each other's
        // count. A train cut here is two trains from the next tick, so there is nothing left to
        // trim or measure against: both halves go on from where they now are.
        int diverted = divertedCar(level, train);

        if (train.faultHeld(diverted, SEVER_TICKS) && diverted != TrainPlacer.Refusal.NONE) {
            LOGGER.debug("train of {} severing at car {}: a switch has diverted its track for"
                    + " {} ticks", train.size(), diverted, SEVER_TICKS);
            sever(level, train, diverted);
            return;
        }

        trimRibbon(train);
        TrainMeter.measure(level, train);
    }

    // What to do about a train the ribbon could not seat. Most refusals are a train waiting rather
    // than a train in trouble: one with no leading end is standing still, and one that cannot yet
    // read which way round it lies has not moved far enough to say. Neither names a car, and so
    // neither is a reason to break anything. A refusal that does name one names a car the recorded
    // track no longer reaches, and once that has held it is a sever.
    private static void cutIfLost(ServerLevel level, Train train, TrainPlacer.Refusal refusal) {
        if (refusal.car() == TrainPlacer.Refusal.NONE) {
            train.faultHeld(TrainPlacer.Refusal.NONE, SEVER_TICKS);
            return;
        }

        if (train.faultHeld(refusal.car(), SEVER_TICKS)) {
            LOGGER.debug("train of {} severing at car {}: {} held {} ticks",
                    train.size(), refusal.car(), refusal.why(), SEVER_TICKS);
            sever(level, train, refusal.car());
        }
    }

    // Which car of a train a switch is currently taking off its recorded track, or NONE. Read from
    // each car's own seat rather than worked out again here, so the check and the placement cannot
    // come to different answers about where a car is.
    //
    // The answer alone is not a reason to cut. A switch on a busy junction is re-steered constantly
    // by whatever else is crossing it, and by this train's own leading car when it reverses onto
    // track it has already recorded — so a single tick's disagreement between the rail and the
    // record is the normal state of a shared switch, not a train being taken apart. Only a
    // disagreement that outlasts the traffic causing it means anything, which is why this answers
    // a question and leaves the deciding to the same held count every other fault goes through.
    private static int divertedCar(ServerLevel level, Train train) {
        AbstractMinecart leader = train.leader();
        TrackRibbon ribbon = train.ribbon();
        List<AbstractMinecart> cars = train.cars();

        for (int i = 0; i < cars.size(); i++) {
            AbstractMinecart car = cars.get(i);

            if (car != leader && ribbon.diverted(level, car.minecarttweaks$seatArc())) {
                return i;
            }
        }

        return TrainPlacer.Refusal.NONE;
    }

    // Breaks the link between car and whichever neighbour lies towards the leading car, so that
    // what stays coupled to the leader is the part still on track the ribbon can answer for and
    // what goes free is the part being taken somewhere else. A leading car that is itself the fault
    // is cut from the one neighbour it has.
    private static void sever(ServerLevel level, Train train, int car) {
        List<AbstractMinecart> cars = train.cars();
        AbstractMinecart leader = train.leader();
        int lead = leader == null ? 0 : cars.indexOf(leader);
        int towards = car == lead ? (lead == 0 ? 1 : -1) : (lead == 0 ? -1 : 1);
        int keep = car + towards;

        if (keep < 0 || keep >= cars.size()) {
            return;
        }

        AbstractMinecart from = cars.get(car);
        AbstractMinecart to = cars.get(keep);

        Linkable.unlink(from, to);
        MinecartHelper.dropChain(level, from, to);
    }

    // Whether the run of ribbon between a train's two end cars takes in the block at pos. Read off
    // where its cars are rather than off its length, so a train that is not where the ribbon thinks
    // it is answers no instead of claiming track it has left.
    private static boolean spans(Train train, BlockPos pos) {
        TrackRibbon ribbon = train.ribbon();
        double low = Double.POSITIVE_INFINITY;
        double high = Double.NEGATIVE_INFINITY;

        for (AbstractMinecart car : train.cars()) {
            Double arc = ribbon.arcOf(car);

            if (arc != null) {
                low = Math.min(low, arc);
                high = Math.max(high, arc);
            }
        }

        return low <= high && ribbon.covers(low, high, pos);
    }

    // Makes sure the ribbon reaches the whole train before anything is measured against it. A
    // ribbon is thrown away and restarted whenever the link graph moves, so a train that gains a
    // car is a train whose track begins again at one block — with cars already spread over it.
    private static void reachTrain(ServerLevel level, Train train) {
        TrackRibbon ribbon = train.ribbon();
        AbstractMinecart leader = train.leader();

        if (leader == null || ribbon.isEmpty()) {
            return;
        }

        Double arc = ribbon.arcOfLeader(leader);

        if (arc == null) {
            return;
        }

        // Both ways, not just behind: a train reverses by swapping which end leads, and the cars
        // that were behind the old one are ahead of the new.
        double span = (train.size() - 1) * TARGET_SPACING + 1.0;

        ribbon.extendTo(level, arc - span, arc + span);
    }

    // Keeps the ribbon to the train's own span plus a margin, measured out from the leading car
    // symmetrically so it does not matter which end that car is. Trimming any tighter walks the
    // last car off the end on a circuit shorter than the train, where the leader re-enters blocks
    // the tail has not left yet. Without it a recording train grows nodes for as long as it runs.
    private static void trimRibbon(Train train) {
        TrackRibbon ribbon = train.ribbon();
        AbstractMinecart leader = train.leader();

        if (leader == null || ribbon.isEmpty()) {
            return;
        }

        Double arc = ribbon.arcOfLeader(leader);

        if (arc == null) {
            return;
        }

        double reach = train.size() * TARGET_SPACING + RIBBON_MARGIN;
        ribbon.trim(arc - reach, arc + reach);
    }

    // One car's rider asking to move. Client intent rather than the player's own velocity, which a
    // passenger does not have — it is the same field vanilla's own nudge reads.
    private static boolean steering(AbstractMinecart cart) {
        return cart.getFirstPassenger() instanceof ServerPlayer player
                && player.getLastClientMoveIntent().lengthSqr() > 0.0;
    }
}
