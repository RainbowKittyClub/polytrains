package club.rainbowkitty.minecarttweaks.train;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.mojang.logging.LogUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.ext.AbstractMinecartExt;
import club.rainbowkitty.minecarttweaks.rail.RailForces;
import club.rainbowkitty.minecarttweaks.rail.TrackRibbon;

/**
 * One train's cars in order, head first, held across ticks. Every coupling question is the same
 * walk over the same links, and one walk per car per question is quadratic in the length of the
 * train — so the walk is done once and its result handed to every car of the train.
 *
 * <p>A train is derived state and is never saved: the links are the durable half
 * ({@code MTAttachments.LINK}), and a train is rebuilt from them on the first tick after a load.
 */
public final class Train {

    private static final Logger LOGGER = LogUtils.getLogger();

    // A train cannot outgrow max_train_length, but a link graph corrupted by a half-written save
    // could; the walks are bounded so a bad one cannot hang the server tick.
    private static final int MAX_WALK = 1024;

    // Below this a train is treated as stopped, and a stopped train keeps whichever end was
    // leading when it stopped rather than losing track it is still sitting on.
    private static final double EPSILON = 1.0E-5;

    // How squarely the head must be heading along the chord to the car behind it before that chord
    // is allowed to name the leading end — a cosine, so about 70 degrees. Through a bend the chord
    // between two cars sits well off the heading of either, and a flip there throws away the whole
    // ribbon, so a weak reading holds rather than guesses.
    private static final double LEADER_HOLD = 0.35;

    // Consecutive ticks a confident reading must name the other end before the train is relaid onto
    // it. A reversal names it for every tick after it; a blip names it once.
    private static final int LEADER_FLIP_TICKS = 3;

    // The most of a block the seating interval closes on its target in one tick, however much room
    // the leading car has made. A ceiling only: what usually paces the expansion is the ground that
    // car covered, and this keeps a train released at speed from snapping out to length in one go.
    private static final double PITCH_STEP = 0.05;

    // Of the target, the tightest interval a measured spacing is believed at. Below this the cars
    // have collapsed into each other rather than been stood close together.
    private static final double PITCH_FLOOR = 0.25;

    private final List<AbstractMinecart> cars;
    private final boolean closed;

    // Set when the link graph under this train changes. It lives on the one object every car of
    // the train points at, so marking it marks it for all of them at once — which is what lets
    // membership below stay an identity test rather than a scan.
    private boolean stale;

    // The last tick this train checked its cars are still in the world. Once a tick rather than
    // once a call: membership is asked per collision candidate per movement step.
    private long checkedTick;

    private final TrackRibbon ribbon;

    // Decided once a tick and held. Which end leads steers what the ribbon records, so two callers
    // in one tick disagreeing would record from both ends of the train at once.
    private @Nullable AbstractMinecart leader;
    private long leaderTick = Long.MIN_VALUE;

    // The other end, while it is being argued for but not yet granted.
    private @Nullable AbstractMinecart flipTo;
    private int flipHeld;

    // Which way round this train lies on its ribbon: +1 when its head sits at the higher arc, and
    // zero until that has been settled. Owned rather than measured, because measuring it means
    // resolving a car's block back to a node, and a circuit records every one of its blocks twice.
    // A reversal changes which end leads; it does not change which end is where.
    private int order;

    // The ribbon reset count this order was settled against. A reset renumbers the arcs, so an
    // order taken before one says nothing about the frame that came after it.
    private int orderAt = -1;

    // Which way along its ribbon this train was last laid, and the arc frame that says what that
    // means. A reset re-records the ribbon along the leading car's travel, so the sign before one
    // describes nothing after it.
    private int facing;
    private int facingAt = -1;

    // This train's mean rolling friction, held for the tick that worked it out. Read once for every
    // rail block the leading car crosses, and working it out walks every car's inventory.
    private double rolling;
    private long rollingTick = Long.MIN_VALUE;

    // This train's total mass, held the same way and for the same reason: the friction, drive and
    // shove paths all ask for it, and a container car's own mass is a walk of its inventory.
    private double mass;
    private long massTick = Long.MIN_VALUE;

    // The interval the cars are being seated at, or zero before there is anything to ease from.
    private double pitch;

    // Where this train's head was when it was last seated, and the ribbon arc frame that says what
    // it means. A reset renumbers the arcs, so a position taken before one describes nothing after
    // it and the turn between the two would be the whole ribbon.
    private double headArc;
    private int headArcAt = -1;

    // How far the head turned reaching that arc, in right angles. Charged as it happens and kept
    // only so the meter can report it.
    private double turn;

    // How the last seating attempt went, so only a change of answer is worth reporting.
    private TrainPlacer.@Nullable Bail bail;

    // Consecutive ticks the ribbon has failed to place each car. Allocated with the train and
    // dropped with it, so a train rebuilt around a link change starts forgiven rather than
    // inheriting a count taken against a different shape.
    private int @Nullable [] faults;

    // Starts checked on the tick it was built, so its first use does not re-sweep its own cars.
    private Train(List<AbstractMinecart> cars, boolean closed, long tick,
            @Nullable TrackRibbon inherited) {
        this.cars = cars;
        this.closed = closed;
        this.checkedTick = tick;
        this.ribbon = inherited == null ? new TrackRibbon() : inherited;
    }

    /**
     * Marks the train {@code car} belongs to as needing a fresh walk. Call after any change to the
     * link graph, on both ends of the link — the two may be in different trains, and after a split
     * the halves must not go on sharing one.
     */
    public static void invalidate(AbstractMinecart car) {
        if (!(car instanceof AbstractMinecartExt ext)) {
            return;
        }

        Train held = ext.minecarttweaks$train();

        if (held != null) {
            held.stale = true;
        }
    }

    /**
     * The train {@code car} belongs to, reusing the one it is already holding unless the link graph
     * has moved under it. A car with no links is a train of one.
     */
    public static Train of(AbstractMinecart car) {
        long tick = car.level().getGameTime();
        Train held = car instanceof AbstractMinecartExt ext
                ? ext.minecarttweaks$train() : null;

        if (held != null && held.usable(tick)) {
            return held;
        }

        // The ribbon is track, not membership. A train that sheds a car in a sever is still on the
        // rails it recorded, and starting that half over with nothing costs it the one thing that
        // could have held it together — so the next tick cannot seat it, the correction takes it
        // instead, and the sever that just happened happens again. Track that no longer describes
        // where the train is fails the same way an empty one does, through arcOfLeader.
        Train built = build(car, tick, held == null ? null : held.ribbon);

        for (AbstractMinecart member : built.cars) {
            if (member instanceof AbstractMinecartExt ext) {
                ext.minecarttweaks$setTrain(built);
            }
        }

        return built;
    }

    /**
     * The track this train is running on, recorded by its leading car and empty until that car has
     * crossed a rail block. Carried across a rebuild, since the rails do not move when the link
     * graph does; a ribbon that no longer describes where the train is reads as one it has left.
     */
    public TrackRibbon ribbon() {
        return ribbon;
    }

    /**
     * The end car this train is travelling towards, or null if it closes on itself or has never
     * moved. Not the same thing as its head: head is a fixed identity that survives a reversal, and
     * a train reverses routinely — a stall part-way up a grade, a reversed furnace cart, a shove.
     *
     * <p>Sticky in both directions. The answer is held for the tick, and an ambiguous reading keeps
     * the end that was already leading instead of dropping to null or flipping, because every
     * consumer of this pays for a change of mind and none of them pays for a stale answer.
     */
    public @Nullable AbstractMinecart leader() {
        if (closed) {
            return null;
        }

        long tick = cars.getFirst().level().getGameTime();

        if (tick != leaderTick) {
            AbstractMinecart was = leader;

            leaderTick = tick;
            leader = settledLeader();

            // Which end leads decides what the ribbon records and which way the cars are laid, so
            // a train that changes its mind about that is a train about to look very strange.
            if (was != null && leader != null && was != leader && LOGGER.isDebugEnabled()) {
                LOGGER.debug("train of {} changed leading end, order {}", cars.size(), order());
            }
        }

        return leader;
    }

    /**
     * Records that the ribbon could not place car {@code car} this tick — or, with a negative
     * {@code car}, that it placed all of them — and answers whether the same car has failed for
     * {@code ticks} in a row.
     *
     * <p>A sever is irreversible and the fault behind it is not: a car crossing a junction,
     * catching air, or waiting on a ribbon that has not grown to reach it is unplaceable for a
     * tick or two and then fine. Deciding on one tick's reading breaks trains that were never in
     * trouble, so the reading has to hold before it costs anything. Every other car is cleared by
     * the same call, so a fault that moves from car to car never accumulates anywhere.
     */
    public boolean faultHeld(int car, int ticks) {
        if (faults == null || faults.length != cars.size()) {
            faults = new int[cars.size()];
        }

        boolean held = false;

        for (int i = 0; i < faults.length; i++) {
            faults[i] = i == car ? faults[i] + 1 : 0;
            held |= i == car && faults[i] >= ticks;
        }

        return held;
    }

    /**
     * Records how a seating attempt went — null for seated, otherwise why not — and answers whether
     * that differs from the attempt before. A train that starts or stops being placed on its track,
     * or starts failing for a different reason, says so once each time, which is the only rate an
     * intermittent fault can be read at: every tick buries it and once a second misses it outright.
     *
     * <p>Compared on the reason and not on the line reporting it, since that line carries arcs and
     * block positions that differ every tick and would report a steady fault as a new one.
     */
    public boolean seatingChanged(TrainPlacer.@Nullable Bail now) {
        boolean changed = bail != now;

        bail = now;

        return changed;
    }

    /**
     * Whether the placer is holding this train — its last seating attempt placed it — so that its
     * cars move as one and a shove on any of them belongs to the car vanilla drives. A train not
     * yet attempted answers yes, its first attempt being a tick away.
     */
    public boolean placed() {
        return bail == null;
    }

    /**
     * The interval to seat this train's cars at this tick, closing on {@code target} no faster
     * than the leading car is making room for it. Not recorded here: {@link #setPitch} records it
     * once every car has been seated at it, so an interval a refused seating never used does not
     * stand for the next attempt.
     *
     * <p>A train is linked from carts a player stood wherever they liked, and seats are measured
     * from the leading car, so taking the target outright would walk every other car backwards at
     * once — into whatever the train is parked against. A train lengthens by pulling its front
     * forward, never by pushing its back: the interval grows out of the ground the leading car has
     * actually covered, shared over the gaps that have to absorb it, and a car that has not moved
     * lengthens nothing. Never above the target, since carts stood wider than that close up onto
     * track already recorded.
     *
     * @param measured how far apart the cars actually are, used only to start from
     * @param advance how far the leading car moved this tick, which is all the room there is
     */
    public double nextPitch(double target, double measured, double advance) {
        int gaps = cars.size() - 1;
        double next = pitch > 0.0 ? pitch : target;

        // Never wider than the cars actually stand. A seated train stands exactly at its interval,
        // so a narrower reading is a train something else has closed up — shunted against the end
        // of its track while it could not be seated — and the interval follows it in rather than
        // asking for room the train no longer has. Never narrower than a car, either: closer than
        // that is two cars inside each other, which vanilla's own shove is left to separate.
        if (measured > 0.0) {
            double floor = Math.max(target * PITCH_FLOOR, cars.getFirst().getBbWidth());

            next = Math.clamp(Math.min(next, measured), floor, target);
        }

        if (next < target && gaps > 0) {
            next += Math.min(target - next, Math.min(advance / gaps, PITCH_STEP));
        }

        return next;
    }

    /** Records the interval this train's cars have just been seated at. */
    public void setPitch(double interval) {
        pitch = interval;
    }

    /** Whether any car of this train is driving it, whatever that car is currently putting out. */
    public boolean driving() {
        for (AbstractMinecart car : cars) {
            if (car.minecarttweaks$driving()) {
                return true;
            }
        }

        return false;
    }

    /**
     * The acceleration this train's engines give it: their summed output over what the train
     * weighs, which is force over mass and nothing else.
     *
     * <p>The divisor used to be the car count less two trailers per engine — an arbitrary
     * generosity standing in for the mass effect, so that a furnace cart and two cars climbed a
     * slope as readily as one alone. Real mass produces that curve directly and better: a furnace
     * cart weighs more than the cars it hauls, so double-heading buys more than the cars it costs,
     * and loading a short train is enough to stop it climbing where adding cars used to be the only
     * way.
     *
     * <p>The mass is the whole train's, not the mean per car, and that difference is the point.
     * Resistance is per car, so it divides by the mean and length drops out; a single engine's
     * output is not per car, so it divides by the total and length is exactly what it is up
     * against.
     */
    public double drive() {
        double drive = 0.0;

        for (AbstractMinecart car : cars) {
            drive += car.minecarttweaks$driveAcceleration();
        }

        if (drive <= 0.0) {
            return 0.0;
        }

        Level level = cars.getFirst().level();

        return drive / mass(RailForces.of(level), level.getGameTime());
    }

    /**
     * What this whole train weighs under {@code forces}, in empty minecarts, held for {@code tick}.
     *
     * <p>Comes to exactly the car count under the vanilla model, where every cart weighs one — so
     * every divisor built on this reads as the count it was before mass existed, and only the
     * physical model ever moves one.
     */
    public double mass(RailForces forces, long tick) {
        if (massTick == tick) {
            return mass;
        }

        double total = 0.0;

        for (AbstractMinecart car : cars) {
            total += forces.cartMass(car);
        }

        mass = total;
        massTick = tick;

        return total;
    }

    /**
     * What one car of this train weighs on average under {@code forces}, in empty minecarts.
     *
     * <p>The figure a retarding force divides by, and a mean rather than the total for the reason
     * nothing else here scales with length either: resistance is per car and so is mass, so a long
     * train coasts like a short one. Dividing by the total instead would have ten empty cars coast
     * five times as far as one, which is neither model.
     */
    public double meanCarMass(RailForces forces, long tick) {
        return mass(forces, tick) / cars.size();
    }

    /**
     * This train's mean rolling friction as worked out on {@code tick}, or zero if it has not been
     * worked out on that tick. A real mean is never zero, so zero is the whole of "ask again".
     */
    public double rolling(long tick) {
        return rollingTick == tick ? rolling : 0.0;
    }

    /** Holds this train's mean rolling friction for the rest of {@code tick}. */
    public void setRolling(long tick, double mean) {
        rolling = mean;
        rollingTick = tick;
    }

    /**
     * Where this train's head sat when it was last seated, or null if that is not known against the
     * ribbon's current arc frame — never seated, or seated before a reset renumbered the arcs.
     */
    public @Nullable Double headArc() {
        return headArcAt == ribbon.resets() ? headArc : null;
    }

    /**
     * Records where this train's head has been seated, or forgets it with null. Forgotten leaves
     * the next seating nothing to measure a turn against, which is the right answer for a train
     * that has been driven by vanilla in between: the ground it covered was not recorded from here.
     */
    public void setHeadArc(@Nullable Double arc) {
        headArc = arc == null ? 0.0 : arc;
        headArcAt = arc == null ? -1 : ribbon.resets();
    }

    /** How far this train's head turned on its last seating, in right angles. */
    public double turn() {
        return turn;
    }

    /** Records how far this train's head has just turned, in right angles. */
    public void setTurn(double angles) {
        turn = angles;
    }

    /**
     * Which way round this train lies on its ribbon — +1 when its head sits at the higher arc — or
     * zero if that has not been settled against the ribbon's current numbering.
     */
    public int order() {
        return ribbon.resets() == orderAt ? order : 0;
    }

    /** Settles which way round this train lies, for as long as the ribbon keeps its arc frame. */
    public void setOrder(int sign) {
        if (order != 0 && order != sign && LOGGER.isDebugEnabled()) {
            LOGGER.debug("train of {} now lies {} on its ribbon, was {}", cars.size(), sign, order);
        }

        order = sign;
        orderAt = ribbon.resets();
    }

    /**
     * Whether this train has turned round along its ribbon since it was last laid, recording
     * {@code sign} as the way it runs now. False for the first laying in an arc frame, which is a
     * train being placed rather than one reversing. Only ever handed a sign read off the leading
     * car's own movement: an end inferred for a standing train is not a direction it ran in.
     *
     * <p>This is the whole of when a car flips. Which end a cart leads with is a fact about the
     * train's direction along the track, not about where the cart points in the world: taking a
     * U-turn swings a car's heading through 180 degrees without reversing it, and any test that
     * compares one heading against the last cannot tell those apart. Vanilla's own test can afford
     * to, because its stepper turns a cart a few degrees at a time and only a real reversal ever
     * lands in its 175-to-185 window; a placed car crosses whole blocks between ticks.
     */
    public boolean reversed(int sign) {
        boolean known = facingAt == ribbon.resets() && facing != 0;
        boolean turned = known && sign != facing;

        facing = sign;
        facingAt = ribbon.resets();

        return turned;
    }

    /** The cars of this train, head first, or in no particular order when it closes on itself. */
    public List<AbstractMinecart> cars() {
        return cars;
    }

    /** How many cars this train has. */
    public int size() {
        return cars.size();
    }

    /** Whether {@code car} is one of this train's. */
    public boolean contains(AbstractMinecart car) {
        return cars.contains(car);
    }

    /**
     * The one car that speaks for this train when it has no leading end: its head, or the
     * lowest-UUID car of a train that closes on itself and so has no head. Exactly one car of any
     * train answers itself here, which is what keeps a per-car tick from asking once per car.
     */
    public @Nullable AbstractMinecart anchor() {
        return closed ? lowest(cars) : cars.getFirst();
    }

    /**
     * The car {@code car} follows, or null if it heads the train, is unlinked, or the train closes
     * on itself and so has no head to follow towards.
     */
    public @Nullable AbstractMinecart follows(AbstractMinecart car) {
        if (closed) {
            return null;
        }

        int index = cars.indexOf(car);

        return index > 0 ? cars.get(index - 1) : null;
    }

    // Holds the end that leads against a reading that has not stood for long. The tick a ribbon
    // resets, the placer bails and every car is moved by vanilla instead, so one car's velocity can
    // confidently name the wrong end for exactly as long as it takes to lay the train backwards.
    private @Nullable AbstractMinecart settledLeader() {
        AbstractMinecart read = decideLeader();

        if (read == null || leader == null || read == leader || !cars.contains(leader)) {
            flipHeld = 0;
            return read;
        }

        if (read != flipTo) {
            flipTo = read;
            flipHeld = 0;
        }

        return ++flipHeld >= LEADER_FLIP_TICKS ? read : leader;
    }

    // Which end the train is running towards, judged along the ribbon rather than through space. A
    // single car has nothing to measure against and is simply its own leading end.
    private @Nullable AbstractMinecart decideLeader() {
        AbstractMinecart head = cars.getFirst();
        Vec3 heading = head.getDeltaMovement().horizontal();
        double speed = heading.length();
        AbstractMinecart held = heldLeader();

        if (cars.size() == 1) {
            return speed < EPSILON ? null : head;
        }

        // A train with nothing to read a leading end off — never moved, or moving with no track
        // recorded yet — names its head rather than nothing. Without an end the placer refuses
        // outright and every car runs on vanilla's own pipeline: a standing train straddling a
        // grade collapses, and a moving one stretches out along the track, for good, since the
        // ribbon only ever grows behind the car already named. Either end answers for where the
        // cars go, because lieOf reads the lie off their arcs and not off this choice, and the
        // ribbon it starts is what lets the next reading correct the end. It does not answer for
        // which way the cars face, which is why the placer declines to turn a train it cannot
        // read a heading from.
        if (speed < EPSILON) {
            return held != null ? held : head;
        }

        Double headArc = ribbon.arcOf(head);
        Double nextArc = ribbon.arcOf(cars.get(1));
        Vec3 tangent = headArc == null ? null : ribbon.tangentAt(headArc);

        if (headArc == null || nextArc == null || tangent == null) {
            return held != null ? held : head;
        }

        // Which way the head end lies along the ribbon, against which way the train is running
        // along it. Both are arc, because a chord through space says nothing about track that
        // doubles back on itself: round a tight turn the car behind sits level with or ahead of the
        // one in front, so a chord reads the train as running the other way and lays it backwards.
        double outward = headArc - nextArc;
        double along = heading.dot(tangent) / speed;

        if (Math.abs(outward) < EPSILON || Math.abs(along) < LEADER_HOLD) {
            return held;
        }

        return along * outward > 0.0 ? head : cars.getLast();
    }

    // The end that was leading, so long as it is still one of ours — a train rebuilt around a split
    // can be holding a car that has since gone its own way.
    private @Nullable AbstractMinecart heldLeader() {
        return leader != null && cars.contains(leader) ? leader : null;
    }

    // Whether this train can still be handed out. A link change marks it stale outright; a car
    // leaving the world is caught by the sweep below, which every removal should have made
    // unnecessary but which costs one pass a tick and covers whatever a removal hook misses.
    private boolean usable(long tick) {
        if (stale) {
            return false;
        }

        if (tick == checkedTick) {
            return true;
        }

        checkedTick = tick;

        for (AbstractMinecart car : cars) {
            if (car.isRemoved()) {
                stale = true;
                return false;
            }
        }

        return true;
    }

    // Walks the whole train from car and orders it head first. The head is whichever of the two
    // ends has the lower UUID, which is the one thing about a train that both of its ends agree on
    // without either having to be told. Only the ends are compared: a car in the middle cannot head
    // a train however low its own UUID.
    private static Train build(AbstractMinecart car, long tick,
            @Nullable TrackRibbon inherited) {
        List<AbstractMinecart> order = new ArrayList<>();
        AbstractMinecart end = walk(car, null);

        if (end == null) {
            walk(car, order);
            return new Train(order, true, tick, inherited);
        }

        walk(end, order);

        if (order.size() > 1 && sortsFirst(order.getLast(), order.getFirst())) {
            Collections.reverse(order);
        }

        return new Train(order, false, tick, inherited);
    }

    // Follows the links away from start, collecting into sink when one is given. Returns the car
    // the track runs out at, or null if it comes back round on itself.
    private static @Nullable AbstractMinecart walk(
            AbstractMinecart start, @Nullable List<AbstractMinecart> sink) {
        AbstractMinecart previous = null;
        AbstractMinecart current = start;

        for (int steps = 0; steps <= MAX_WALK; steps++) {
            if (sink != null) {
                sink.add(current);
            }

            AbstractMinecart onward = onward(current, previous);

            if (onward == null) {
                return current;
            }

            if (onward == start) {
                return null;
            }

            previous = current;
            current = onward;
        }

        return null;
    }

    // The neighbour of car that is not the one it was reached from.
    private static @Nullable AbstractMinecart onward(
            AbstractMinecart car, @Nullable AbstractMinecart previous) {
        for (AbstractMinecart neighbour : car.getNeighbours()) {
            if (neighbour != previous) {
                return neighbour;
            }
        }

        return null;
    }

    // The car of the lowest UUID, which is the tie-break every car of a train reaches the same way.
    private static @Nullable AbstractMinecart lowest(List<AbstractMinecart> cars) {
        AbstractMinecart best = null;

        for (AbstractMinecart car : cars) {
            if (best == null || sortsFirst(car, best)) {
                best = car;
            }
        }

        return best;
    }

    // Whether a's UUID sorts before b's.
    private static boolean sortsFirst(AbstractMinecart a, AbstractMinecart b) {
        return a.getUUID().compareTo(b.getUUID()) < 0;
    }
}
