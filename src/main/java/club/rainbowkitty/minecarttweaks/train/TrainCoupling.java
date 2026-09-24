package club.rainbowkitty.minecarttweaks.train;

import java.util.List;
import java.util.function.Function;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.ext.Linkable;
import club.rainbowkitty.minecarttweaks.link.LinkAction;
import club.rainbowkitty.minecarttweaks.rail.RailBrake;
import club.rainbowkitty.minecarttweaks.rail.RailDrag;
import club.rainbowkitty.minecarttweaks.rail.RailForces;
import club.rainbowkitty.minecarttweaks.rail.RailPath;
import club.rainbowkitty.minecarttweaks.rail.TrackRibbon;
import club.rainbowkitty.minecarttweaks.rule.ModGameRules;

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

    // Centre-to-centre distance at which two carts are too far apart to link, and how far apart
    // the cars of a linked train settle. The first is the straight line between them rather than
    // the track that runs between, so a curve costs a link no reach.
    //
    // Independent of each other: a train holds one interval per gap and keeps whatever it was
    // coupled at, so the pair closes up over the ticks after rather than being moved the whole
    // difference on the one it was coupled.
    private static final double MAX_LINK_DISTANCE = 3.0;

    private static final double TARGET_SPACING = 1.5;

    // How much track a chord of the link distance can hide behind it. A curve makes the run
    // between two carts longer than the line they are apart by, and the reach is spent on the
    // line — so a walk looking for the far end has to be allowed the arc as well.
    private static final double LINK_ARC_ALLOWANCE = 2.0;

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

    // Mass a train has to reach before nobody can shift it by leaning on it, in empty minecarts —
    // the unit every mass in this mod is in. Eight empty cars, or three loaded chest carts: what a
    // player can see is the length, but what resists them is the load.
    private static final double PUSHABLE_MASS = 8.0;

    // Below this squared length a movement has no direction to read. Only has to exclude a dead
    // stop, since the placer writes a car's movement along its own rail and any of it at all names
    // the way the train is going.
    private static final double ANY_MOVEMENT = 1.0E-10;

    // Track kept either side of the train when the ribbon is trimmed, on top of the train's own
    // length. Room for the leading car to run into before the next record extends it.
    private static final double RIBBON_MARGIN = 8.0;

    // Below this a length is treated as zero.
    private static final double EPSILON = 1.0E-5;

    // Vanilla's own two factors on the downhill pull it works out per tick: how much of the cart's
    // own horizontal speed adds to it, and what being in water leaves of it
    // (NewMinecartBehavior#calculateSlopeSpeed).
    public static final double SLOPE_SPEED_GAIN = 0.02;

    private static final double WATER_SLIDE = 0.2;

    // Static-only; blocks instantiation.
    private TrainCoupling() {}

    /**
     * Settles the train {@code cart} belongs to and returns the car it follows, or null if it heads
     * its train, is unlinked, sits in a closed loop, or trains are switched off.
     */
    public static @Nullable AbstractMinecart tickTrain(ServerLevel level, AbstractMinecart cart) {
        // Links themselves are left alone while trains are off, so turning them back on picks up
        // where it left off; only the following and the chain stop.
        if (!ModGameRules.trainsEnabled(level)) {
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
     * position everything else is measured from. Every train of two or more has one, so exactly one
     * car asks per tick — a train that cannot be read names its head rather than nothing.
     *
     * <p>Unless that car does not tick, which is not the same as it being gone: a cart in a chunk
     * outside the ticking radius stays loaded, stays linked and stays the car this train is
     * measured from, while the cars behind it go on ticking without anything to hold them. So any
     * car may lay the train out once a whole tick has passed without one — see {@link
     * Train#unheld}.
     */
    public static void holdAfterMove(ServerLevel level, AbstractMinecart cart) {
        if (!ModGameRules.trainsEnabled(level) || !cart.hasLink()) {
            return;
        }

        Train train = Train.of(cart);

        if (train.leader() == cart || train.unheld(level.getGameTime())) {
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
                && ModGameRules.trainsEnabled(cart.level())
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
     * The train's drive laid on {@code resisted}, or {@code resisted} unchanged where this car is
     * owed none of it.
     *
     * <p>Only the leading car reaches vanilla's slowdown at all, and the leading car is not
     * necessarily an engine: a plain cart at the front of a train with a furnace cart behind it has
     * no impulse of its own to be driven by. So the train's whole output is applied here, to the
     * one car carrying it, and a car that is its own engine is left to the furnace's own path.
     *
     * <p>Takes the movement the retarding law has already been applied to, and adds to it.
     * Resistance then drive, in that order, is what gives a powered train a top speed of its own;
     * a drive that cancelled the slowdown would run to the ceiling whatever the train's length.
     */
    public static Vec3 hauled(AbstractMinecart cart, Vec3 resisted) {
        if (!cart.hasLink()
                || !ModGameRules.trainsEnabled(cart.level())
                || cart.minecarttweaks$driving()
                || held(cart)) {
            return resisted;
        }

        Train train = Train.of(cart);

        // Only the car at the head of a train carries its drive. That was already true of a train
        // the placer is holding — every other car is placed, and a placed car never reaches this —
        // but it was true by arrangement rather than by asking, and the arrangement is exactly what
        // fails when the placer cannot hold the train. Every car then drew the whole train's force
        // on its own account, with nothing coupling them but a chain that is a record and not a
        // rope, and a train already in trouble tore itself apart under its own power.
        if (train.leader() != cart) {
            return resisted;
        }

        double drive = train.driveAcceleration();

        if (drive <= 0.0) {
            return resisted;
        }

        // A standing train has no movement to read a heading off, so it leaves on the aim of its
        // powered carts. Without that only those carts move and the train never gets going — and
        // they are the carts the placer is not moving, so it never gets going at all.
        Vec3 headingDirection = resisted.horizontal();

        if (headingDirection.lengthSqr() <= ANY_MOVEMENT) {
            headingDirection = driveHeading(cart);
        }

        return headingDirection.lengthSqr() > ANY_MOVEMENT
                ? resisted.add(headingDirection.normalize().scale(drive))
                : resisted;
    }

    /**
     * Whether anything is driving {@code cart}, its own engine or one it is coupled to. Ordered so
     * an unlinked cart answers off its own field without walking a train.
     *
     * <p>Asks whether an engine is lit, not what it is putting out. Under the power curve a lit
     * engine near its free-running speed outputs almost nothing, and this decides whether the flat
     * rolling term applies — so reading the output here would reapply resistance to a train at
     * speed, slow it, restore its effort, and oscillate.
     */
    public static boolean underPower(AbstractMinecart cart) {
        return cart.minecarttweaks$driving()
                || (cart.hasLink() && Train.of(cart).driving());
    }

    /**
     * Whether anyone aboard {@code cart}'s train is steering it. Vanilla's rider nudge is a
     * thousandth of a block per tick, under the floor the dead stop cuts to zero, so it does not
     * survive its own first tick unless asked about here. Bounded by {@link #shovable}: a train too
     * heavy to shove from outside is too heavy to shuffle from inside.
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
        Vec3 headingDirection = Vec3.ZERO;

        for (AbstractMinecart member : Train.of(cart).cars()) {
            headingDirection = headingDirection.add(member.minecarttweaks$driveHeading());
        }

        return headingDirection.lengthSqr() > EPSILON ? headingDirection.normalize() : Vec3.ZERO;
    }

    /** The furthest centre-to-centre distance at which two carts may be linked. */
    public static double maxLinkDistance() {
        return MAX_LINK_DISTANCE;
    }

    /**
     * How much track to walk looking for the far end of a coupling, which is not how far apart the
     * two may be — that is {@link #maxLinkDistance()}, measured as the straight line between them.
     */
    public static double maxLinkWalk() {
        return MAX_LINK_DISTANCE * LINK_ARC_ALLOWANCE;
    }

    /** The distance along the rails the coupling holds consecutive cars at. */
    public static double targetSpacing() {
        return TARGET_SPACING;
    }

    /**
     * How much track either side of its leading car a train's ribbon is trimmed to, for a train
     * whose cars are spread over {@code trainLength} of it.
     */
    public static double trimReach(double trainLength) {
        return trainLength + MAX_LINK_DISTANCE + RIBBON_MARGIN;
    }

    /**
     * Records the rail block {@code cart} is stepping through on its train's ribbon. Only the car
     * leading its train records: a trailing car crosses blocks the ribbon already holds in its
     * middle, which the ribbon cannot tell from a jump and would answer by starting over.
     */
    public static void recordTrack(AbstractMinecart cart, BlockPos pos) {
        if (!ModGameRules.trainsEnabled(cart.level()) || !cart.hasLink()) {
            return;
        }

        Train train = Train.of(cart);

        if (train.leader() != cart) {
            return;
        }

        TrackRibbon ribbon = train.ribbon();
        Vec3 movement = cart.getDeltaMovement().horizontal();
        int previousFrame = ribbon.arcEpoch();

        ribbon.record(cart.level(), pos, movement);

        // A ribbon started over numbers itself off the movement just handed to it — the one moment
        // anything knows which way round the train lies without measuring two cars that may be
        // standing on top of each other. Taken here rather than left to the placer to read back,
        // and only on a movement there is a direction in — a reading off no movement would settle
        // the lie on whichever way the rail happened to be walked.
        if (ribbon.arcEpoch() != previousFrame && movement.lengthSqr() > EPSILON * EPSILON) {
            train.relaidOn(cart);
        }
    }

    /**
     * Whether {@code cart}'s train is light enough to be shifted by leaning on it at all, which
     * stops at {@link #PUSHABLE_MASS}. How hard it is below that is {@link #mass}'s to say.
     *
     * <p>A mass rather than a car count, so under the physical model the same visible train answers
     * differently loaded than empty. The vanilla model reads it as a car count regardless, every
     * cart weighing one there.
     */
    public static boolean shovable(AbstractMinecart cart) {
        return mass(cart) < PUSHABLE_MASS;
    }

    /**
     * Whether the placer is holding {@code cart} where it wants it, so that a shove on it is the
     * train's to answer rather than the cart's own.
     *
     * <p>Asked of the car and not of its train, because the two can differ within one train and a
     * single answer for all of it gets one of them wrong whichever way it is given. A car the
     * placer holds has its movement written for it, so a shove left on it is overwritten before it
     * moves anything and has to be handed to the car vanilla drives; a car the placer has let go
     * of is an ordinary cart, and handing its shove to the leading car leaves the car that was
     * actually pushed standing.
     */
    public static boolean carried(AbstractMinecart cart) {
        if (!ModGameRules.trainsEnabled(cart.level()) || !cart.hasLink()) {
            return false;
        }

        Train train = Train.of(cart);

        // The leading car is normally the one car vanilla drives, so it is carried for as long as
        // the placer has not handed its whole train back; a train against a buffer seats it too.
        return TrainPlacer.seated(cart) || (cart == train.leader() && train.holding());
    }

    /**
     * How fast {@code cart}'s train is really travelling, in blocks per tick. Read off the car that
     * leads it, which is the one vanilla still drives along the rails — every other car's movement
     * is written by the placer from this same figure, so asking one of those is reading back what
     * was written rather than measuring anything.
     */
    public static double drivenSpeed(AbstractMinecart cart) {
        if (!cart.hasLink() || !ModGameRules.trainsEnabled(cart.level())) {
            return cart.getDeltaMovement().length();
        }

        AbstractMinecart leader = Train.of(cart).leader();

        return (leader == null ? cart : leader).getDeltaMovement().length();
    }

    /**
     * The downhill pull {@code cart}'s train is owed this tick, to add to {@code movement}, or null
     * where vanilla's own {@code pull} already answers for it.
     *
     * <p>Gravity is the one force the placer took away without replacing. It is added inside
     * {@code moveAlongTrack}, which a seated car never reaches, so a train on a grade gets exactly
     * one car's pull however many cars are on the slope — and carries the mass of all of them.
     *
     * <p>Force over mass, which is what the rest of this class already does with the engine: each
     * car on a grade pulls downhill by its own weight while the whole train's weight resists, so
     * the share is the mass-weighted mean of the cars' grades. A train wholly on one slope comes to
     * exactly one, which is right — a long train rolls downhill no faster than a single cart. A
     * train with one car over the brow comes to a fraction of it, and one balanced across a crest
     * to nothing.
     *
     * <p>Worked out rather than rescaled off vanilla's answer, because vanilla's pull for a car on
     * the level is the zero vector and no multiple of that is anything: a train whose leading car
     * reached flat track went weightless with its other cars still on the bank. Only the magnitude
     * is borrowed, which is where the water case and the speed term still come from.
     */
    public static @Nullable Vec3 slopePull(AbstractMinecart cart, Vec3 movement, Vec3 pull) {
        if (!cart.hasLink() || !ModGameRules.trainsEnabled(cart.level())) {
            return null;
        }

        Train train = Train.of(cart);
        List<AbstractMinecart> cars = train.cars();

        // Only the car whose movement is an input owns the train's pull, since every other car's is
        // written over by the placer; and a train closing on itself has no lower end to be pulled
        // towards, which is also why its car order means nothing.
        if (cars.size() < 2 || train.leader() != cart) {
            return null;
        }

        boolean headLeads = cars.getFirst() == cart;
        Vec3 chord = cart.position()
                .subtract(cars.get(headLeads ? 1 : cars.size() - 2).position())
                .horizontal();

        if (chord.lengthSqr() <= ANY_MOVEMENT) {
            return null;
        }

        // Laid along the rail rather than along that chord, so a pull taken through a corner stays
        // on the track; the chord only has to say which of the block's two ends is the front.
        RailPath.Crossing crossing = RailPath.crossing(
                cart.level(), cart.getCurrentBlockPosOrRailBelow(), chord);

        if (crossing == null) {
            return null;
        }

        double share = downhillShare(cars, RailForces.forLevel(cart.level()), headLeads);
        boolean flatTrack = pull.lengthSqr() == 0.0;

        if (share == 0.0) {
            return flatTrack ? null : Vec3.ZERO;
        }

        return crossing.forward()
                .scale(share * (flatTrack ? slideSpeed(cart, movement) : pull.length()));
    }

    // How much of one car's worth of downhill pull a train is owed in the direction its leading end
    // points, from the mass-weighted mean of its cars' grades.
    private static double downhillShare(
            List<AbstractMinecart> cars, RailForces forces, boolean headLeads) {
        double weighted = 0.0;
        double total = 0.0;

        for (int i = 0; i < cars.size(); i++) {
            AbstractMinecart car = cars.get(i);

            // Towards the head: the chord from the car behind this one to it, and for the head
            // itself the chord from the car behind it, which lies along the same line.
            Vec3 headward = cars.get(i == 0 ? 0 : i - 1).position()
                    .subtract(cars.get(i == 0 ? 1 : i).position())
                    .horizontal();

            weighted += forces.cartMass(car) * RailPath.grade(car, headward);
            total += forces.cartMass(car);
        }

        // Downhill rather than up, and towards the leading end rather than the head: a train
        // climbing towards its head is pulled back towards its tail.
        return total == 0.0 ? 0.0 : -weighted / total * (headLeads ? 1.0 : -1.0);
    }

    // Vanilla's own figure for one car's worth of downhill pull, which it works out inside
    // calculateSlopeSpeed and only ever hands back laid along a slope it actually found.
    private static double slideSpeed(AbstractMinecart cart, Vec3 movement) {
        double slide = Math.max(
                RailDrag.SLOPE_PULL, movement.horizontalDistance() * SLOPE_SPEED_GAIN);

        return cart.isInWater() ? slide * WATER_SLIDE : slide;
    }

    /**
     * What {@code cart}'s train should leave a lit powered rail doing, given the {@code movement}
     * it arrived with, or null where vanilla's own answer stands.
     *
     * <p>The boost lives inside the {@code moveAlongTrack} the placer cancels, so today a lit rail
     * under any car but the leading one does nothing whatever. Read over the whole train instead:
     * the share of its mass standing on lit rails, times vanilla's own figure.
     *
     * <p>Sharing it reads as a weakening and is not one. Every car spends the same time over a
     * section, so the share integrates back to exactly what the leading car collects alone today —
     * a train lying fully across a booster accelerates at vanilla's 0.06, and one a quarter across
     * gains a quarter as much for four times as long. What the share moves is where the boost
     * lands, not how much of it there is.
     *
     * <p>The standing branch is the exception and the one place the models part, because vanilla
     * launches a stopped cart by <em>assigning</em> it a speed rather than by adding an impulse,
     * and a speed has no mass to be divided by — so {@link RailForces#launch} decides it.
     *
     * <p>Answers for a cart coupled to nothing as readily as for a train, since a load is a load
     * whether or not anything is linked to it. That costs the default nothing: a lone cart weighs
     * one there, so its share is one and every branch here hands back the figure vanilla computed.
     */
    public static @Nullable Vec3 boostPull(AbstractMinecart cart, Vec3 movement) {
        List<AbstractMinecart> cars = drivenCars(cart);

        if (cars == null) {
            return null;
        }

        // A brake anywhere under the train outranks a rail lit anywhere else, the driven car's own
        // included: the parking brake is a hold, and a train launched off one lit rail would only
        // be halted again a tick later. Declined outright rather than left to vanilla, which would
        // still boost the driven car off whatever it is standing on.
        if (braking(cars)) {
            return movement;
        }

        RailForces forces = RailForces.forLevel(cart.level());
        double share = railShare(cars, forces, RailBrake::boosts);

        if (share <= 0.0) {
            return null;
        }

        // Rolling, vanilla's boost ignores which way the rail points and merely speeds the cart up,
        // so the train's own travel is the whole of the heading.
        if (movement.length() > RailBrake.ROLLING_SPEED) {
            return movement.normalize().scale(movement.length() + RailBrake.BOOST_SPEED * share);
        }

        // Vanilla's kick carries whatever speed the cart already had, which is under the rolling
        // threshold but need not be nothing, so the whole of its figure is what gets shared.
        return launchVelocity(
                cart, cars, forces.launch(movement.length() + RailBrake.LAUNCH_SPEED, share));
    }

    /**
     * What {@code cart}'s train should leave a braking rail doing, or null where no car of it is on
     * one. The same fault as the boost and the same repair, but for the hold.
     *
     * <p>How much braking the share buys is {@link RailForces#halted}'s to say, because this is the
     * one force the two models cannot state in the same units — vanilla's is a fraction of the
     * speed and the physical model's is a force over the mass. What is settled here is the share
     * itself, and it reaches a whole train either way.
     *
     * <p>Only the retardation shares. A clamped shoe is a constraint and not a force, so the dead
     * stop under vanilla's own floor is all-or-nothing on any car — which is both what stops a
     * train creeping off a rail it is fractionally standing on, and what stops a player towing one
     * off a brake by its far end.
     */
    public static @Nullable Vec3 braked(AbstractMinecart cart, Vec3 movement) {
        List<AbstractMinecart> cars = drivenCars(cart);

        if (cars == null) {
            return null;
        }

        double share = railShare(cars, RailForces.forLevel(cart.level()), RailBrake::brakes);

        if (share <= 0.0) {
            return null;
        }

        return movement.length() < RailBrake.HALT_FLOOR
                ? Vec3.ZERO
                : RailForces.forLevel(cart.level()).halted(movement, share);
    }

    /** Whether a brake has hold of any car of {@code cart}'s train, or of {@code cart} alone. */
    public static boolean held(AbstractMinecart cart) {
        return cart.hasLink() && ModGameRules.trainsEnabled(cart.level())
                ? braking(Train.of(cart).cars())
                : RailBrake.brakes(cart);
    }

    /**
     * How fast a standing train leaves a brake whose signal has come back, sharing vanilla's own
     * kick over the cars that are actually over lit rails. Vanilla's figure outright for one cart.
     */
    public static double launchSpeed(AbstractMinecart cart) {
        List<AbstractMinecart> cars = drivenCars(cart);

        if (cars == null) {
            return RailBrake.LAUNCH_SPEED;
        }

        RailForces forces = RailForces.forLevel(cart.level());

        return forces.launch(RailBrake.LAUNCH_SPEED, railShare(cars, forces, RailBrake::boosts));
    }

    // The cars whose rails this cart answers for: a whole train when it is the car driving one,
    // itself alone when it is coupled to nothing, and null when it is a car the placer is about to
    // move anyway. Every track force below is written into the movement vanilla is going to step
    // along, and on a train that movement belongs to one car; the rest are placed from it.
    //
    // A cart coupled to nothing is a train of one rather than a case to decline, so that its load
    // reaches its rails the way it already reaches its drag, its engine and its gravity. Under
    // VANILLA every cart weighs one, so a lone cart's share comes to exactly one and vanilla's own
    // figure is what comes back out of every law here.
    //
    // Asked through driven rather than the leading end, because a train that has never moved has no
    // leading end and is exactly the train a launch is for.
    private static @Nullable List<AbstractMinecart> drivenCars(AbstractMinecart cart) {
        if (!cart.hasLink() || !ModGameRules.trainsEnabled(cart.level())) {
            return List.of(cart);
        }

        if (drivenCar(cart) != cart) {
            return null;
        }

        List<AbstractMinecart> cars = Train.of(cart).cars();

        return cars.size() < 2 ? List.of(cart) : cars;
    }

    // Which way a standing train leaves, read at each car that is over a lit rail and carried up to
    // the driven one along the track. The rail need not be anywhere near the car vanilla drives,
    // and two cars round a bend do not agree on a direction; lit rails that disagree cancel.
    private static @Nullable Vec3 launchVelocity(
            AbstractMinecart cart, List<AbstractMinecart> cars, double speed) {
        if (speed <= 0.0) {
            return null;
        }

        Vec3 headingDirection = Vec3.ZERO;

        for (AbstractMinecart car : cars) {
            Vec3 railDirection = RailBrake.boosts(car)
                    ? car.getRedstoneDirection(car.getCurrentBlockPosOrRailBelow())
                    : Vec3.ZERO;

            if (railDirection.lengthSqr() > 0.0) {
                headingDirection = headingDirection.add(alongTrack(car, cart, railDirection));
            }
        }

        return headingDirection.lengthSqr() <= ANY_MOVEMENT
                ? null
                : headingDirection.normalize().scale(speed);
    }

    // How much of one car's worth of track force a train is owed, from nought to one: the cars
    // standing on rails that answer test, over what the whole train weighs.
    //
    // Counted on top and weighed underneath, which is the whole of how mass reaches a rail force. A
    // rail pushes each car on it with the same fixed force whatever that car is carrying, so the
    // numerator is a count; the train's own mass is what that force has to move, so the denominator
    // is a sum of masses. Weighing both would cancel the mass out again and leave a loaded train
    // accelerating and stopping exactly like an empty one. Under VANILLA every car weighs one, so
    // this is the plain car ratio it has always been.
    private static double railShare(
            List<AbstractMinecart> cars, RailForces forces, Predicate<AbstractMinecart> test) {
        int matched = 0;
        double total = 0.0;

        for (AbstractMinecart car : cars) {
            if (test.test(car)) {
                matched++;
            }

            total += forces.cartMass(car);
        }

        return total <= 0.0 ? 0.0 : matched / total;
    }

    // Whether any of these cars is standing on a brake.
    private static boolean braking(List<AbstractMinecart> cars) {
        for (AbstractMinecart car : cars) {
            if (RailBrake.brakes(car)) {
                return true;
            }
        }

        return false;
    }

    /**
     * The shove {@code push} landed on {@code shoved} as the car {@code driven} should receive it:
     * how hard it urges the train along its own rails, laid back down along the rails there.
     *
     * <p>A push is a vector in the world and a train is not a straight line. The car a player leans
     * on and the car vanilla drives point the same way only while the track between them is
     * straight — round a bend they differ by the bend, and on a U-turn they run flat opposite, so a
     * push meant to send the train forward reaches the one car that can act on it as a push
     * backwards. What survives the carry is the scalar: how much of the shove is along the track.
     *
     * <p>The component across the rails is dropped rather than carried, which costs nothing. A cart
     * cannot move that way, and vanilla discards it a moment later at the rail itself.
     */
    public static Vec3 alongTrack(AbstractMinecart shoved, AbstractMinecart driven, Vec3 push) {
        TrackRibbon ribbon = Train.of(shoved).ribbon();
        Double shovedArc = ribbon.arcOf(shoved);
        Double drivenArc = shoved == driven ? shovedArc : ribbon.arcOf(driven);
        Vec3 shovedTangent = shovedArc == null ? null : ribbon.tangentAt(shovedArc);
        Vec3 drivenTangent = drivenArc == null ? null : ribbon.tangentAt(drivenArc);

        // A train the ribbon cannot place is one whose shape it cannot speak for either, so the
        // shove goes on as it arrived rather than being projected onto a guess.
        if (shovedTangent == null || drivenTangent == null) {
            return push;
        }

        double travelComponent = push.horizontal().dot(shovedTangent);

        return new Vec3(drivenTangent.x() * travelComponent, push.y(),
                drivenTangent.z() * travelComponent);
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
    public static AbstractMinecart drivenCar(AbstractMinecart cart) {
        if (!cart.hasLink() || !ModGameRules.trainsEnabled(cart.level())) {
            return cart;
        }

        Train train = Train.of(cart);
        AbstractMinecart leader = train.leader();

        if (leader != null) {
            return leader;
        }

        // Only a train of one has no leading end to read, and that one car is this one.
        return cart;
    }

    /**
     * Whether any train is lying across the rail at {@code pos} — not merely whether a cart is
     * inside that block this instant.
     *
     * <p>A switch asks this before any change of curve, a car's steering included. At speed a
     * cart is inside a given block for a fraction of a tick and the gap between two cars passes
     * over one entirely, so a check for a cart standing there reads a long train as a run of brief
     * absences and changes the rails halfway along it. Only the leading car reads rails now, so
     * nothing else re-arms the wait either. The ribbon records the track the train is on, which
     * answers for its whole length at once.
     */
    public static boolean crossedByTrain(Level level, BlockPos pos) {
        return crossedBy(level, pos, Train::of);
    }

    /**
     * {@link #crossedByTrain}'s answer read off the trains the carts near {@code pos} already
     * hold, rebuilding none, for an observer that must not settle a train ahead of its own cars.
     * A train whose links changed since its cars last asked for it is not counted.
     */
    public static boolean crossedByHeldTrain(Level level, BlockPos pos) {
        return crossedBy(level, pos, Train::peek);
    }

    /**
     * The mass of {@code cart}'s train, in empty minecarts. A cart on its own is a train of one.
     *
     * <p>What a cart weighs is {@link RailForces#cartMass}'s to say, and under the vanilla model
     * every cart weighs one — so this is the plain car count it has always been, and a loaded car
     * still weighs what an empty one does. Only the physical model gives it a real figure, which is
     * how a quantity read by the shove, the drive, the brake and the wreck check is gated in one
     * place rather than at each of them.
     */
    public static double mass(AbstractMinecart cart) {
        RailForces forces = RailForces.forLevel(cart.level());

        // Short-circuited rather than walked: this is asked of every cart every tick, and most
        // carts on a server are not linked to anything.
        return cart.hasLink()
                ? Train.of(cart).mass(forces, cart.level().getGameTime())
                : forces.cartMass(cart);
    }

    /**
     * The lowest speed ceiling any car of {@code cart}'s train will accept, given
     * {@code ownCeiling} as the ceiling of the car asking. Just {@code ownCeiling} for a lone cart.
     *
     * <p>A repair, not a departure, and it runs under both force models. Vanilla has no trains, so
     * the ensemble is the answer: a line of carts being pushed along cannot outrun whatever is
     * pushing it, and vanilla halves a furnace cart's ceiling. Without this the ceiling would be
     * whichever car happened to be leading — a furnace train capped at four blocks a second one
     * way round and at eight the other, doubling its speed when it reversed. The model does not
     * come into it because each car's own figure is already the model's: the physical model undoes
     * vanilla's halving, so the lowest ceiling is then the same as everyone else's and this
     * changes nothing.
     *
     * <p>Asked only of the driven car, which is what keeps it from recursing. Every other car's
     * hook stands down, so walking the train to ask each one costs one figure per car rather than
     * one walk per car.
     */
    public static double trainCeiling(AbstractMinecart cart, ServerLevel level, double ownCeiling) {
        if (!cart.hasLink() || !ModGameRules.trainsEnabled(level) || drivenCar(cart) != cart) {
            return ownCeiling;
        }

        double lowest = ownCeiling;

        for (AbstractMinecart car : Train.of(cart).cars()) {
            if (car != cart) {
                lowest = Math.min(lowest, car.minecarttweaks$ownMaxSpeed(level));
            }
        }

        return lowest;
    }

    /**
     * What one car of {@code cart}'s train weighs on average, in empty minecarts, or what
     * {@code cart} itself weighs when it is coupled to nothing.
     *
     * <p>The figure a retarding force divides by, as against {@link #mass}, which is the whole
     * train and is what a shove or an impact is shared over. Both come to one per car under the
     * vanilla model.
     */
    public static double meanCarMass(AbstractMinecart cart) {
        RailForces forces = RailForces.forLevel(cart.level());

        return cart.hasLink()
                ? Train.of(cart).meanCarMass(forces, cart.level().getGameTime())
                : forces.cartMass(cart);
    }

    /**
     * How hard two trains are about to meet, or zero if they are not closing on each other at all.
     *
     * <p>The rate the gap shrinks rather than either train's own speed, so a heavy train merely
     * catching a light one up barely registers, times the reduced mass {@code m_a·m_b/(m_a+m_b)} —
     * which keeps a loaded train hitting a parked cart on the parked cart's scale, not the train's.
     */
    public static double closingMomentum(AbstractMinecart a, AbstractMinecart b) {
        Vec3 betweenVector = b.position().subtract(a.position()).horizontal();

        if (betweenVector.lengthSqr() < EPSILON) {
            return 0.0;
        }

        Vec3 lineDirection = betweenVector.normalize();
        double closing = a.getDeltaMovement().dot(lineDirection)
                - b.getDeltaMovement().dot(lineDirection);

        if (closing <= 0.0) {
            return 0.0;
        }

        double massA = mass(a);
        double massB = mass(b);

        return closing * massA * massB / (massA + massB);
    }

    // Holds train together by seating it on the track its leading car recorded, and cuts it
    // where that cannot be done.
    //
    // There is no second system. A train is placed or it is not, and a train that is not is
    // either waiting — standing still, or on a ribbon not yet grown to reach it — or else coming
    // apart for a reason this can name. Nothing corrects a car towards where it ought to be, so
    // there is no correction to lose a race against, and no gap that has to be measured.
    private static void holdTrain(ServerLevel level, Train train) {
        // Stamped at the top, so a train that bailed still counts as having been laid out. What
        // this records is that somebody asked, not that the asking worked — a train refused every
        // tick is being looked after, and the one thing that must not follow from a refusal is a
        // second car deciding the first never came.
        train.setHeldAt(level.getGameTime());
        reachTrain(level, train);

        TrainPlacer.Refusal refusal = TrainPlacer.seat(train, TARGET_SPACING);

        // One fault reading a tick, whichever kind it is, so the two cannot clear each other's
        // count. A refusal naming a car on the ground takes the tick, a diverted car any other,
        // refused or not; the car a tick passes over keeps its count standing, not cleared.
        int divertedIndex = divertedCarIndex(level, train);

        if (refusal != null && cutIfLost(level, train, refusal, divertedIndex)) {
            return;
        }

        // A refusal cutIfLost passed on names no car or one in the air, whose count then stands.
        int refusedIndex = refusal == null ? TrainPlacer.Refusal.NONE : refusal.car();

        // A train cut here is two trains from the next tick, so there is nothing left to trim or
        // measure against: both halves go on from where they now are.
        if (train.faultHeld(divertedIndex, refusedIndex, SEVER_TICKS)
                && divertedIndex != TrainPlacer.Refusal.NONE) {
            TrainMeter.sever(train, divertedIndex, keptIndex(train, divertedIndex), "DIVERTED",
                    "a switch has diverted its track for " + SEVER_TICKS + " ticks");
            sever(level, train, divertedIndex);
            return;
        }

        if (refusal != null) {
            return;
        }

        trimRibbon(train);
    }

    // What to do about a train the ribbon could not seat, answering whether its refusal took the
    // tick's fault reading. Most refusals are a train waiting rather
    // than a train in trouble: one with no leading end is standing still, one that cannot yet
    // read which way round it lies has not moved far enough to say, one with no room for its tail
    // is up against the end of its track, one whose track has not reached a car may yet record it,
    // and one whose seats are fouled is waiting on another train to clear them. None of those
    // names a car, and so none is a reason to break anything. A refusal
    // that does name one names a car the track cannot answer for, or one the train is running
    // away from, and once that has held it is a sever.
    private static boolean cutIfLost(
            ServerLevel level, Train train, TrainPlacer.Refusal refusal, int divertedIndex) {
        if (refusal.car() == TrainPlacer.Refusal.NONE) {
            return false;
        }

        // A car in the air is not a car the track has lost. It is between one piece of track and
        // the next, and where it is now says nothing about whether it will come down on rails —
        // which is the question, and one only a landing can answer. The count is left standing
        // rather than cleared, so a train already failing takes up where it left off once down;
        // the tick goes to holdTrain's other reading, which clears every other car's.
        if (!train.cars().get(refusal.car()).onGround()) {
            return false;
        }

        // First claim on the tick: this fault holds for as long as it is true, while a diverted
        // reading moves car to car as a train runs over a switch and would starve it.
        if (train.faultHeld(refusal.car(), divertedIndex, SEVER_TICKS)) {
            TrainMeter.sever(train, refusal.car(), keptIndex(train, refusal.car()),
                    refusal.why().name(), "held " + SEVER_TICKS + " ticks");
            sever(level, train, refusal.car());
        }

        return true;
    }

    // Which car of a train a switch is currently taking off its recorded track, or NONE. Read from
    // each car's own seat rather than worked out again here, so the check and the placement cannot
    // come to different answers about where a car is.
    //
    // The answer alone is not a reason to cut. Cars running across a switch that disagrees with the
    // record each show it for a tick in turn, which is not a train being taken apart; only a
    // disagreement that holds on one car means anything, so this answers a question and leaves the
    // deciding to the same held count every other fault goes through.
    private static int divertedCarIndex(ServerLevel level, Train train) {
        AbstractMinecart leader = train.leader();
        TrackRibbon ribbon = train.ribbon();
        List<AbstractMinecart> cars = train.cars();

        for (int i = 0; i < cars.size(); i++) {
            AbstractMinecart car = cars.get(i);
            Double seatArc = car.minecarttweaks$seatArc(ribbon.arcEpoch());

            // A seat arc from a numbering the ribbon has since left says nothing about where this
            // car sits now, so it is no evidence of a switch having moved under it. Nor does the
            // last seat of a car the placer has let go of, which vanilla now runs on the rails.
            if (car != leader && seatArc != null && TrainPlacer.seated(car)
                    && ribbon.diverted(level, seatArc)) {
                return i;
            }
        }

        return TrainPlacer.Refusal.NONE;
    }

    // Breaks the link between car and whichever neighbour lies towards the leading car, so that
    // what stays coupled to the leader is the part still on track the ribbon can answer for and
    // what goes free is the part being taken somewhere else. A leading car that is itself the fault
    // is cut from the one neighbour it has.
    private static void sever(ServerLevel level, Train train, int carIndex) {
        List<AbstractMinecart> cars = train.cars();
        int keepIndex = keptIndex(train, carIndex);

        if (keepIndex < 0) {
            return;
        }

        AbstractMinecart from = cars.get(carIndex);
        AbstractMinecart to = cars.get(keepIndex);

        Linkable.unlink(from, to);
        LinkAction.dropChain(level, from, to);
    }

    // The neighbour sever cuts car carIndex away from, or -1 if it has none that way. Its own
    // method so the sever line names the same cut the sever makes.
    private static int keptIndex(Train train, int carIndex) {
        List<AbstractMinecart> cars = train.cars();
        AbstractMinecart leader = train.leader();
        int leadIndex = leader == null ? 0 : cars.indexOf(leader);
        // Which way the leading car lies up the list, and so which neighbour to keep: the one
        // towards it, or for the leading car itself the one neighbour it has.
        int fromHead = leadIndex == 0 ? 1 : -1;
        int towardsSign = carIndex == leadIndex ? fromHead : -fromHead;
        int keepIndex = carIndex + towardsSign;

        return keepIndex < 0 || keepIndex >= cars.size() ? -1 : keepIndex;
    }

    // Whether the train any linked cart near pos resolves to, through trainOf, lies across it.
    private static boolean crossedBy(Level level, BlockPos pos,
            Function<AbstractMinecart, @Nullable Train> trainOf) {
        if (!ModGameRules.trainsEnabled(level)) {
            return false;
        }

        for (AbstractMinecart cart : level.getEntitiesOfClass(
                AbstractMinecart.class, new AABB(pos).inflate(CROSSING_REACH),
                AbstractMinecart::hasLink)) {
            Train train = trainOf.apply(cart);

            if (train != null && spans(train, pos)) {
                return true;
            }
        }

        return false;
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

        if (leader == null) {
            return;
        }

        Vec3 movement = leader.getDeltaMovement().horizontal();
        int previousFrame = ribbon.arcEpoch();

        // A ribbon holds only what the leading car has stepped along, and a car standing still
        // steps along nothing — so a train that was stopped when its world loaded has an empty one
        // and no way to come by a first node. Seeded with the rail it stands on, which is the block
        // its own first step would have recorded anyway; the growth below lays the rest from there.
        //
        // The same answer serves a ribbon holding track that is not the track its leading car is
        // standing on — a knock off the rails and back on, a load, a reset behind it. Left alone
        // that refuses the seating every tick for as long as the car stays where it is, since the
        // ribbon only grows from an arc it can already place that car at. record() continues from
        // what is there when it can and starts over when it cannot, which is the same judgement it
        // makes for every other block the car steps onto.
        if (ribbon.isEmpty() || ribbon.arcOfLeader(leader) == null) {
            BlockPos underPos = leader.getCurrentBlockPosOrRailBelow();

            if (!BaseRailBlock.isRail(level.getBlockState(underPos))) {
                return;
            }

            ribbon.record(level, underPos, movement);
        }

        Double arc = ribbon.arcOfLeader(leader);

        if (arc == null) {
            return;
        }

        // Both ways, not just behind: a train reverses by swapping which end leads, and the cars
        // that were behind the old one are ahead of the new.
        // Every gap counted at a full link's worth, not at what it is seated at: a train built this
        // tick has no intervals recorded and its cars may stand a whole link apart. Asking for less
        // would leave the seating wanting track the ribbon was never sent for, bail, and so never
        // record the intervals that would have made the ask smaller.
        double reach = train.length(MAX_LINK_DISTANCE) + 1.0;

        ribbon.extendTo(level, arc - reach, arc + reach);

        // A reset numbered the new ribbon off this movement, so the lie is settled from it as in
        // recordTrack. Unlike there, this car may be bouncing back into its train, so check first.
        if (ribbon.arcEpoch() != previousFrame && movement.lengthSqr() > EPSILON * EPSILON
                && trailsLeader(train, leader, arc)) {
            train.relaidOn(leader);
        }
    }

    // Whether the train's far end lies behind its leading car on the ribbon, as relaidOn assumes.
    private static boolean trailsLeader(Train train, AbstractMinecart leader, double leadArc) {
        List<AbstractMinecart> cars = train.cars();
        Double farArc = train.ribbon().arcOf(
                cars.getFirst() == leader ? cars.getLast() : cars.getFirst());

        return farArc != null && farArc < leadArc;
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

        double reach = trimReach(train.length(MAX_LINK_DISTANCE));
        ribbon.trim(arc - reach, arc + reach);
    }

    // One car's rider asking to move. Client intent rather than the player's own velocity, which a
    // passenger does not have — it is the same field vanilla's own nudge reads.
    private static boolean steering(AbstractMinecart cart) {
        return cart.getFirstPassenger() instanceof ServerPlayer player
                && player.getLastClientMoveIntent().lengthSqr() > 0.0;
    }
}
