package club.rainbowkitty.polytrains.train;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.mojang.logging.LogUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.polytrains.ext.AbstractMinecartExt;
import club.rainbowkitty.polytrains.rail.RailBrake;
import club.rainbowkitty.polytrains.rail.RailForces;
import club.rainbowkitty.polytrains.rail.TrackRibbon;

/**
 * One train's cars in order, head first, held across ticks. Every coupling question is the same
 * walk over the same links, and one walk per car per question is quadratic in the length of the
 * train — so the walk is done once and its result handed to every car of the train.
 *
 * <p>A train is derived state and is never saved: the links are the durable half
 * ({@code EntityAttachments.LINK}), and a train is rebuilt from them on the first tick after a
 * load.
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

    // The most a gap coupled wider than the target closes in one tick. A flat rate, unlike the
    // packing and the widening: those spend ground the train has covered, and a train stood still
    // after a coupling still has to draw itself in off nothing at all.
    private static final double PITCH_STEP = 0.05;

    // Of the target, the tightest interval a measured spacing is believed at. Below this the cars
    // have collapsed into each other rather than been stood close together.
    private static final double PITCH_FLOOR = 0.25;

    // The minecarts in this train, head first.
    private final List<AbstractMinecart> cars;

    // Set when the link graph under this train changes. It lives on the one object every car of
    // the train points at, so marking it marks it for all of them at once, keeping membership
    // below an identity test rather than a scan.
    private boolean stale;

    // The last tick this train checked its cars are still in the world. Once a tick rather than
    // once a call: membership is asked per collision candidate per movement step.
    private long checkedTick;

    // The rail track this train has been recorded on.
    private final TrackRibbon ribbon;

    // Decided once a tick and held. Which end leads steers what the ribbon records, so two callers
    // in one tick disagreeing would record from both ends of the train at once.
    private @Nullable AbstractMinecart leader;
    // The tick when the leading end was last settled.
    private long leaderTick = Long.MIN_VALUE;

    // The other end, while it is being argued for but not yet granted.
    private @Nullable AbstractMinecart flipTo;
    // How many consecutive ticks flipTo has been the prospective leading end.
    private int flipHeld;

    // Which way round this train lies on its ribbon: +1 when its head sits at the higher arc, and
    // zero until that has been settled. Owned rather than measured, because measuring it means
    // resolving a car's block back to a node, and a circuit records every one of its blocks twice.
    // A reversal changes which end leads; it does not change which end is where.
    private int orderSign;

    // The ribbon arc frame this order was settled against. A reset renumbers the arcs, so an
    // order taken before one says nothing about the frame that came after it.
    private int orderSignAt = -1;

    // This train's mean rolling friction, held for the tick that worked it out. Read once for every
    // rail block the leading car crosses, and working it out walks every car's inventory.
    private double rollingFriction;
    // The tick when rolling friction was last calculated.
    private long rollingFrictionTick = Long.MIN_VALUE;

    // This train's total mass, held the same way and for the same reason: the friction, drive and
    // shove paths all ask for it, and a container car's own mass is a walk of its inventory.
    private double mass;
    // The tick when mass was last calculated.
    private long massTick = Long.MIN_VALUE;
    // What each car weighs, by index, filled in the same pass.
    private final double[] carMasses;

    // What each car stands on, by index: its rail, its lever, and the tick and position read at. A
    // car that moves within the tick is read again, since fly() and the step hooks move cars too.
    private final BlockState[] censusRail;
    private final boolean[] censusLever;
    private final double[] censusX;
    private final double[] censusY;
    private final double[] censusZ;
    private final long[] censusAt;

    // The lowest ceiling of every car but the driven one, for the tick and car it was taken for.
    private double ceilingOthers;
    private long ceilingTick = Long.MIN_VALUE;
    private @Nullable AbstractMinecart ceilingDriven;

    // The tick this train last had give taken out of its gaps by something it has run into.
    private long packedTick = Long.MIN_VALUE;

    // The tick this train last turned round on a stall, and the tick until which it holds its
    // own turn for a train that turned away from it.
    private long turnedTick = Long.MIN_VALUE;
    private long turnHeldTick = Long.MIN_VALUE;

    // The last tick this train was laid out. A train is laid out once a tick by the car leading
    // it, so a gap here is that car not having ticked — which is not a thing a car chooses, and
    // so not a thing the rest of the train can wait out.
    private long heldTick;

    // How fast this train was running on the last tick it had somewhere to run to.
    private double carried;

    // The interval each pair of cars is being seated at, one per gap and head first, or null
    // before this train has been seated. One per gap and not one for the train: a coupling leaves
    // exactly one gap unlike all the rest, and a single interval cannot hold that without moving
    // every other car to match it.
    private double @Nullable [] gaps;

    // Where this train's head was when it was last seated, and the ribbon arc frame that says what
    // it means. A reset renumbers the arcs, so a position taken before one describes nothing after
    // it and the turn between the two would be the whole ribbon.
    private double headArc;
    // The ribbon arc frame headArc was last recorded against.
    private int headArcAt = -1;

    // How far the head turned reaching that arc, in right angles. Charged as it happens and kept
    // only so the meter can report it.
    private double turn;

    // Where the leading car stood when this train was last laid out, which car that was, the tick,
    // and the arc frame: how far that car has run since, which a draw-back may not exceed.
    private double laidLeadArc;
    private @Nullable AbstractMinecart laidLeader;
    private long laidTick = Long.MIN_VALUE;
    private int laidLeadArcAt = -1;

    // The last tick this train was laid out standing still, the arc frame then, where each placed
    // car stood and on which rail (null for one left to vanilla), and where its leading car stood.
    private long stillTick = Long.MIN_VALUE;
    private int stillEpoch = -1;
    private @Nullable Vec3 @Nullable [] stillPositions;
    private @Nullable BlockPos @Nullable [] stillRails;
    private @Nullable Vec3 stillLeaderPos;
    // Every other train's cart in reach then, and where each stood.
    private List<AbstractMinecart> stillNeighbours = List.of();
    private Vec3[] stillNeighbourPositions = new Vec3[0];

    // How the last seating attempt went, so only a change of answer is worth reporting, and
    // whether one has been made at all — a train built this tick has been neither placed nor
    // refused, which is not the same as having been placed.
    private TrainPlacer.@Nullable Bail bail;
    private boolean attempted;

    // Consecutive ticks the ribbon has failed to place each car. Allocated with the train and
    // dropped with it, so a train rebuilt around a link change starts forgiven rather than
    // inheriting a count taken against a different shape.
    private int @Nullable [] faults;

    // Of each car's current fault, the ticks it has stood on another cart's roof. Cleared with
    // the fault, so a car gets one roof's wait per fault.
    private int @Nullable [] perches;

    // The track cars still on a ramp run off after their leading car has landed, or null. Not
    // carried into a rebuilt train: lost, those cars are seated on the landing track instead.
    private @Nullable Departure departure;

    // Starts checked on the tick it was built, so its first use does not re-sweep its own cars.
    // Starts held on it too, for the same reason read the other way round: a train built this tick
    // has not missed being laid out, it has not been due one yet.
    private Train(List<AbstractMinecart> cars, long tick, @Nullable TrackRibbon inherited) {
        this.cars = cars;
        this.checkedTick = tick;
        this.heldTick = tick;
        this.ribbon = inherited == null ? new TrackRibbon() : inherited;

        int size = cars.size();
        this.carMasses = new double[size];
        this.censusRail = new BlockState[size];
        this.censusLever = new boolean[size];
        this.censusX = new double[size];
        this.censusY = new double[size];
        this.censusZ = new double[size];
        this.censusAt = new long[size];
        Arrays.fill(censusAt, Long.MIN_VALUE);
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

        Train held = ext.polytrains$train();

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
                ? ext.polytrains$train() : null;

        if (held != null && held.usable(tick)) {
            return held;
        }

        // The ribbon is track, not membership. A train that sheds a car in a sever is still on the
        // rails it recorded, and starting that half over with nothing costs it the one thing that
        // could have held it together — so the next tick cannot seat it, the correction takes it
        // instead, and the sever that just happened happens again. Track that no longer describes
        // where the train is fails the same way an empty one does, through arcOfLeader.
        //
        // Copied rather than handed over, because a split leaves both halves inheriting from the
        // same train and one ribbon cannot be recorded on from two places at once.
        Train built = build(car, tick, held == null ? null : new TrackRibbon(held.ribbon));

        for (int i = 0; i < built.cars.size(); i++) {
            if (built.cars.get(i) instanceof AbstractMinecartExt ext) {
                ext.polytrains$setTrain(built, i);
            }
        }

        return built;
    }

    /**
     * The train {@code car} is holding, or null if it holds none or the one it holds no longer
     * stands for its links. Unlike {@link #of} this rebuilds nothing and marks nothing, so an
     * observer can read a train without changing what the next tick does with it.
     */
    public static @Nullable Train peek(AbstractMinecart car) {
        Train held = car instanceof AbstractMinecartExt ext ? ext.polytrains$train() : null;

        if (held == null || held.stale) {
            return null;
        }

        for (AbstractMinecart member : held.cars) {
            if (member.isRemoved()) {
                return null;
            }
        }

        return held;
    }

    /** The id {@code car} goes by in the meter's lines: the first eight hex digits of its UUID. */
    public static String cartId(AbstractMinecart car) {
        return car.getUUID().toString().substring(0, 8);
    }

    /**
     * The id a train of {@code cars} goes by: the {@link #cartId} of whichever of them has the
     * lowest UUID, the car every one of them agrees on. {@code none} for no cars at all.
     */
    public static String trainId(List<AbstractMinecart> cars) {
        AbstractMinecart lowestCar = lowest(cars);

        return lowestCar == null ? "none" : cartId(lowestCar);
    }

    /** This train's id, which stays the same for as long as its cars do. */
    public String id() {
        return trainId(cars);
    }

    /**
     * Whether this train turns round before {@code other} when the two stall against each other:
     * the one whose lowest car UUID sorts first, which both reach the same way.
     */
    public boolean turnsBefore(Train other) {
        AbstractMinecart ownLowest = lowest(cars);
        AbstractMinecart otherLowest = lowest(other.cars);

        return ownLowest != null && otherLowest != null && sortsFirst(ownLowest, otherLowest);
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
        long tick = cars.getFirst().level().getGameTime();

        if (tick != leaderTick) {
            AbstractMinecart previousLeader = leader;

            leaderTick = tick;
            leader = settledLeader();

            // Which end leads decides what the ribbon records and which way the cars are laid, so
            // a train that changes its mind about that is a train about to look very strange.
            if (previousLeader != null && leader != null && previousLeader != leader
                    && LOGGER.isDebugEnabled()) {
                LOGGER.debug("train of {} changed leading end, order {}", cars.size(),
                        orderSign());
            }
        }

        return leader;
    }

    /**
     * The end car {@link #leader()} named when it was last asked, without asking again. For an
     * observer, which must not settle a tick's answer ahead of the train itself.
     */
    public @Nullable AbstractMinecart lastLeader() {
        return leader;
    }

    /**
     * Records that the ribbon could not place car {@code carIndex} this tick — or, with a negative
     * {@code carIndex}, that it placed all of them — and answers whether the same car has failed
     * for {@code ticks} in a row.
     *
     * <p>A sever is irreversible and the fault behind it is not: a car crossing a junction,
     * catching air, or waiting on a ribbon that has not grown to reach it is unplaceable for a
     * tick or two and then fine. Deciding on one tick's reading breaks trains that were never in
     * trouble, so the reading has to hold before it costs anything. Every other car is cleared by
     * the same call, so a fault that moves from car to car never accumulates anywhere.
     *
     * @param standingIndex a car that keeps its count as it stands rather than having it cleared,
     *     or a negative index for none: one whose fault this tick is not counted — it is in the
     *     air, or another car's fault took the tick — but is not forgiven either
     */
    public boolean faultHeld(int carIndex, int standingIndex, int ticks) {
        readyCounts();

        boolean held = false;

        for (int i = 0; i < faults.length; i++) {
            if (i == standingIndex && i != carIndex) {
                continue;
            }

            faults[i] = i == carIndex ? faults[i] + 1 : 0;
            perches[i] = i == carIndex ? perches[i] : 0;
            held |= i == carIndex && faults[i] >= ticks;
        }

        return held;
    }

    /**
     * Records that refused car {@code carIndex} stands on another cart's roof this tick, and
     * answers whether it has stood on one for more than {@code ticks} ticks of its current fault.
     * Cleared with that fault, so the wait is bounded however the car comes and goes.
     */
    public boolean perchHeld(int carIndex, int ticks) {
        readyCounts();

        return ++perches[carIndex] > ticks;
    }

    // Allocates the per-car fault and roof counts together, the first time either is asked for.
    private void readyCounts() {
        if (faults == null || perches == null || faults.length != cars.size()) {
            faults = new int[cars.size()];
            perches = new int[cars.size()];
        }
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
    public boolean seatingChanged(TrainPlacer.@Nullable Bail reason) {
        boolean changed = bail != reason || !attempted;

        attempted = true;
        bail = reason;

        return changed;
    }

    /** Why this train's last seating attempt failed, or null if it seated or none was made. */
    public TrainPlacer.@Nullable Bail lastBail() {
        return bail;
    }

    /**
     * Whether the placer is holding this train, so that its cars move as one and a shove on any of
     * them belongs to the car vanilla drives.
     *
     * <p>The train-wide half of a question the per-car {@link TrainPlacer#seated} answers the other
     * half of. That one says whether vanilla's movement is cancelled for one car, which the leading
     * car is normally a no to — it is the car vanilla drives, and the only one that records track.
     * The exception is the few ticks a train backed against a buffer is placed whole, its leading
     * car included. So the leading car of a train this answers yes for is usually held by the
     * placer and moved by vanilla at the same time, and neither answer can stand in for the other.
     *
     * <p>A train never attempted answers no. It used to answer yes on the grounds that its first
     * attempt was a tick away, which made a third answer out of a question that already had two.
     *
     * <p>Read off the cars rather than off the last verdict, because the verdict cannot say how
     * much of the train it left held: the refusal that leaves one car loose on a train of five
     * leaves nothing at all held on a train of two, and they are the same refusal. The seat marks
     * say it exactly. They also survive a rebuild, which the verdict does not — a train reassembled
     * around a link change has been neither placed nor refused, while its cars are still standing
     * where the placer put them — and {@link TrainPlacer#seated} zeroes them the moment a train is
     * handed back, so a release reads here without having to be reported here.
     */
    public boolean holding() {
        for (AbstractMinecart car : cars) {
            if (TrainPlacer.seated(car)) {
                return true;
            }
        }

        return false;
    }

    /** Records that this train was laid out on tick {@code tick}. */
    public void setHeldAt(long tick) {
        heldTick = tick;
    }

    /**
     * Whether a whole tick has gone by without this train being laid out, so that a car which is
     * not the one leading it should do it rather than go on waiting.
     *
     * <p>A train is laid out by its leading car alone, which is right while that car ticks and
     * leaves the train with nobody to lay it out when that car does not. A car can fail to tick
     * without being gone: it can sit in a chunk outside the ticking radius while the cars behind
     * it are inside one, which a train long enough to straddle that border does routinely.
     *
     * <p>A whole tick and not this one. A car that has merely not ticked <i>yet</i> is about to
     * move, and laying the train against where it currently stands would be reading it four blocks
     * early; a car that missed the tick before is not late but stopped, and where it stands is
     * exactly where it is going to be. The two are indistinguishable within a tick and obvious
     * across two, so this waits the tick out rather than guessing.
     *
     * <p>The cost of waiting is that the seat marks expire first — {@link TrainPlacer#seated}
     * forgives one tick, not two — so the cars run on vanilla's own movement for the tick this is
     * deciding in, and are gathered back up at the end of it. One tick loose against being loose
     * for as long as the leading car stays outside the border.
     */
    public boolean unheld(long tick) {
        return heldTick + 1 < tick;
    }

    /**
     * The intervals to seat this train's cars at this tick, one for each gap between two cars and
     * head first, each closing on {@code target} by a bounded step from where that pair of cars
     * actually stands. Not recorded here: {@link #setGaps} records them once every car has been
     * seated at them, so intervals a refused seating never used do not stand for the next attempt.
     *
     * <p>A train is linked from carts a player stood wherever they liked, and every coupling
     * builds the train afresh, so one with nothing recorded starts at the gaps its cars already
     * stand at — the seating that follows moves no car at all. Each gap then closes on the target
     * on its own, which leaves the pair a coupling just made the only cars that move for it.
     *
     * <p>The two directions are not paid for alike. A train lengthens by pulling its front forward
     * and never by pushing its back, so widening is spent out of the ground the leading car has
     * covered, shared over the gaps that have to absorb it; a car that has not moved lengthens
     * nothing. Narrowing draws a pair together, which needs no room made for it and so is not
     * gated on that car having moved at all.
     *
     * @param standing how far apart each pair of cars stands, from {@link #standingGaps}
     * @param advance how far the leading car moved this tick, which is all the room there is
     * @param landed by car, whether that car has just come down off a jump onto this ribbon
     */
    public double[] nextGaps(double target, double[] standing, double advance, boolean[] landed) {
        int count = cars.size() - 1;
        double[] next = new double[count];
        double pitchFloor = floor(target);
        double[] previousGaps = gaps != null && gaps.length == count ? gaps : null;
        boolean leadsFromHead = cars.getFirst() == leader;

        for (int i = 0; i < count; i++) {
            double interval = previousGaps != null ? previousGaps[i] : target;
            // Below a car only where a landing left it so, and then only opening from there.
            double gapFloor = previousGaps != null
                    ? Math.min(pitchFloor, previousGaps[i]) : pitchFloor;

            if (previousGaps == null && standing[i] > 0.0) {
                interval = standing[i];
            }

            int behind = leadsFromHead ? i + 1 : i;

            if (landed[behind] && standing[i] > 0.0) {
                next[i] = landedGap(i, standing[i], target, pitchFloor, leadsFromHead);
                continue;
            }

            // Never wider than the pair actually stands. A seated pair stands exactly at its
            // interval, so a narrower reading is two cars something else has closed up — shunted
            // against the end of the track while the train could not be seated — and the interval
            // follows them in rather than asking for room they no longer have. Never narrower than
            // a car either: closer than that is two cars inside each other, which vanilla's own
            // shove is left to separate.
            if (standing[i] > 0.0) {
                interval = Math.max(Math.min(interval, standing[i]), gapFloor);
            }

            // A gap a landing left inside a car opens at the flat rate even standing, or a train
            // stopped or pressing there would hold its cars inside each other.
            double opening = interval < pitchFloor
                    ? Math.max(advance / count, Math.min(pitchFloor - interval, PITCH_STEP))
                    : advance / count;

            next[i] = interval > target
                    ? interval - Math.min(interval - target, PITCH_STEP)
                    : interval + Math.min(target - interval, opening);
        }

        return next;
    }

    // A car come down off a jump stays where it landed and is drawn to its seat from there: kept to
    // where the car ahead stood before this tick, so it moves on with that car and never back.
    private double landedGap(
            int gap, double standing, double target, double pitchFloor, boolean leadsFromHead) {
        AbstractMinecart ahead = cars.get(leadsFromHead ? gap : gap + 1);
        double aheadRun = ahead == leader ? ahead.getDeltaMovement().horizontalDistance() : 0.0;
        // Nor any closer than it stands now, unless it stands closer than a car already.
        double start = Math.max(standing - aheadRun, Math.min(standing, pitchFloor));

        return start > target ? start - Math.min(start - target, PITCH_STEP) : start;
    }

    /**
     * How far apart each pair of this train's cars stands along the rails, head first, for
     * {@link #nextGaps}. NaN for a pair the ribbon cannot place a car of.
     *
     * @param maxLinkDistance the most two coupled cars can stand apart; a wider reading is a car
     *     that has drifted off this ribbon rather than a gap, and is not started from
     */
    public double[] standingGaps(double maxLinkDistance) {
        double[] carArcs = arcs();
        double[] standing = new double[cars.size() - 1];

        for (int i = 0; i < standing.length; i++) {
            // Along the rails, not in arc. An interval is a distance on the track and arc is its
            // ground plan, so on a hill a correctly seated pair reads as standing closer than its
            // interval — and clamping to that walks the interval down a little further every tick.
            standing[i] = Math.min(ribbon.trackBetween(carArcs[i], carArcs[i + 1]),
                    maxLinkDistance);
        }

        return standing;
    }

    /**
     * Takes up to {@code amount} of track out of this train's gaps, so that a train with nowhere
     * left to put its tail packs into itself rather than being held rigid against what it has met.
     * Closes the gap nearest that end first, the way cars driven into a buffer meet one at a time.
     *
     * @param intervals the gaps to narrow, changed in place
     * @param target the interval the train is seated towards, which sets how tight a gap may go
     * @param amount how much track has to come out of them altogether
     * @param fromLast whether the end that is blocked is the one the last gap sits at
     * @param reach how many gaps back from that end may be closed, the rest being out of the
     *     push's way and so not in compression at all
     * @return how much of {@code amount} the gaps had no give left to absorb
     */
    public double compress(
            double[] intervals, double target, double amount, boolean fromLast, int reach) {
        double pitchFloor = floor(target);
        double remaining = amount;
        int limit = Math.min(reach, intervals.length);

        for (int pass = 0; pass < limit && remaining > 0.0; pass++) {
            int i = fromLast ? intervals.length - 1 - pass : pass;
            double give = Math.min(intervals[i] - pitchFloor, remaining);

            if (give > 0.0) {
                intervals[i] -= give;
                remaining -= give;
            }
        }

        return remaining;
    }

    /**
     * Closes this train's gaps tighter, for a train standing against something it cannot move. It
     * shortens by the ground it would have covered this tick, so one that runs into something
     * shuts as fast as it hit it.
     *
     * <p>Never slower than the flat rate, though. A train leaning on a block has no speed to take
     * this from, and an engine's own output is an acceleration rather than a closing speed — six
     * hundredths of a block a tick over the whole train's mass, which takes a coupling most of
     * two seconds to shut and reads as nothing happening at all.
     *
     * @param intervals the gaps to narrow, changed in place
     * @param target the interval the train is seated towards, which sets how tight a gap may go
     * @param fromLast whether the car that met the block is the one the last gap sits at
     * @param reach how many gaps back from it an engine's push actually reaches
     * @return whether there was any give left to take, false once those gaps are shut tight
     */
    public boolean pack(double[] intervals, double target, boolean fromLast, int reach) {
        double step = Math.max(carried, PITCH_STEP);

        return compress(intervals, target, step, fromLast, reach) < step;
    }

    /**
     * Records how fast this train is running, for the ticks it still has room to run in.
     *
     * <p>Held because a train pressing on something reads zero off its leading car — vanilla takes
     * that car's movement away the moment the block does — and a train closing at the speed it
     * reads there would never close anything at all.
     */
    public void setCarried(double speed) {
        carried = speed;
    }

    /** How fast this train was running on the last tick it had room to run in. */
    public double carried() {
        return carried;
    }

    /**
     * The track a train's cars left its ribbon by, kept for those still on it once the ribbon has
     * restarted under a leading car come down from a jump.
     *
     * @param ribbon a copy of the ribbon as it stood, on the same arc numbering, never grown
     * @param farEnd whether the cars leave by its far end rather than its near one
     * @param leader the leading car that flew off it
     * @param speed what its cars ran off it at before that car landed
     */
    public record Departure(TrackRibbon ribbon, boolean farEnd, AbstractMinecart leader,
            double speed) {}

    /** The track cars still on a ramp are leaving by, or null when none are. */
    public @Nullable Departure departure() {
        return departure;
    }

    /** Records the track cars still on a ramp are leaving by, or null once none are. */
    public void setDeparture(@Nullable Departure departure) {
        this.departure = departure;
    }

    /** Records that this train closed up on this tick. */
    public void setPacked(long tick) {
        packedTick = tick;
    }

    /**
     * Whether this train is still closing up on something it has met, rather than standing shut
     * against it. An engine pressing on a train that still has give is moving something, however
     * little the train as a whole is going anywhere.
     */
    public boolean packing(long tick) {
        return packedTick + 1 >= tick;
    }

    /** Records that this train turned round on a stall on {@code tick}. */
    public void setTurned(long tick) {
        turnedTick = tick;
    }

    /** Whether this train turned round on a stall at most {@code ticks} before {@code tick}. */
    public boolean turnedWithin(long tick, int ticks) {
        return turnedTick + ticks >= tick;
    }

    /** Holds this train's stall turn until {@code tick}, for a train that turned away from it. */
    public void holdTurn(long tick) {
        turnHeldTick = Math.max(turnHeldTick, tick);
    }

    /** Whether this train is holding its stall turn on {@code tick}. */
    public boolean turnHeld(long tick) {
        return tick < turnHeldTick;
    }

    /**
     * Leads this train from the end {@code heading} drives it towards at {@code engine}, at once:
     * for a train its engines have just turned round, which stands still and so names no new
     * front of its own, while the car at that end is placed, and a placed car pushes nothing.
     */
    public void leadTowards(AbstractMinecart engine, Vec3 heading) {
        Double arc = ribbon.arcOf(engine);
        Vec3 tangent = arc == null ? null : ribbon.tangentAt(arc);
        double along = tangent == null ? 0.0 : heading.horizontal().dot(tangent.horizontal());
        int order = orderSign();

        // A train the placer has let go keeps the end it had, as settledLeader keeps it.
        if (cars.size() < 2 || order == 0 || Math.abs(along) < EPSILON || !holding()) {
            return;
        }

        // The head sits at the higher arc when the order is positive.
        leader = along * order > 0.0 ? cars.getFirst() : cars.getLast();
        leaderTick = engine.level().getGameTime();
        flipTo = null;
        flipHeld = 0;
    }

    // The tightest a pair may be seated: two cars closer than this are inside each other, which
    // vanilla's own shove is left to separate.
    private double floor(double target) {
        return Math.max(target * PITCH_FLOOR, cars.getFirst().getBbWidth());
    }

    /**
     * How much track this train's cars are spread over, counting every gap at least
     * {@code gapFloor} wide.
     *
     * @param gapFloor the least a gap counts for, so that a train with nothing recorded yet is
     *     still given room for cars standing as far apart as a coupling leaves them
     */
    public double length(double gapFloor) {
        double total = 0.0;

        for (int i = 0; i < cars.size() - 1; i++) {
            total += Math.max(gaps == null || gaps.length != cars.size() - 1 ? 0.0 : gaps[i],
                    gapFloor);
        }

        return total;
    }

    /** Whether {@code next} seats every gap where it was last seated, to within a hair. */
    public boolean gapsHeld(double[] next) {
        if (gaps == null || gaps.length != next.length) {
            return false;
        }

        for (int i = 0; i < next.length; i++) {
            if (Math.abs(next[i] - gaps[i]) > EPSILON) {
                return false;
            }
        }

        return true;
    }

    /** Records the intervals this train's cars have just been seated at, one per gap. */
    public void setGaps(double[] intervals) {
        gaps = intervals;
    }

    // Where each car sits along the ribbon, NaN for one the ribbon cannot place. Read in one pass
    // because every gap wants both of its cars.
    private double[] arcs() {
        double[] arcPositions = new double[cars.size()];

        for (int i = 0; i < cars.size(); i++) {
            Double arc = ribbon.arcOf(cars.get(i));

            arcPositions[i] = arc == null ? Double.NaN : arc;
        }

        return arcPositions;
    }

    /** Whether any engine of this train has begun to stall: under power, and going nowhere. */
    public boolean stalling() {
        for (AbstractMinecart car : cars) {
            if (car.polytrains$stalling()) {
                return true;
            }
        }

        return false;
    }

    /** Whether any car of this train is driving it, whatever that car is currently putting out. */
    public boolean driving() {
        for (AbstractMinecart car : cars) {
            if (car.polytrains$driving()) {
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
     * <p>The mass is the whole train's, not the mean per car: resistance is per car, so it divides
     * by the mean and length drops out; a single engine's output is not per car, so it divides by
     * the total and length is exactly what it is up against.
     */
    public double driveAcceleration() {
        double totalDriveAcceleration = 0.0;

        for (AbstractMinecart car : cars) {
            totalDriveAcceleration += car.polytrains$driveAcceleration();
        }

        if (totalDriveAcceleration <= 0.0) {
            return 0.0;
        }

        Level level = cars.getFirst().level();

        return totalDriveAcceleration / mass(RailForces.forLevel(level), level.getGameTime());
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

        for (int i = 0; i < cars.size(); i++) {
            carMasses[i] = forces.cartMass(cars.get(i));
            total += carMasses[i];
        }

        mass = total;
        massTick = tick;

        return total;
    }

    /** What the car at {@code index} weighs, as summed into {@link #mass} on {@code tick}. */
    public double carMass(int index, RailForces forces, long tick) {
        mass(forces, tick);

        return carMasses[index];
    }

    /**
     * The rail under the car at {@code index}, read at most once a tick for each place that car
     * stands. Never asks for the leading end, which {@link #leader} works out from this.
     */
    public BlockState railUnder(int index) {
        refreshCensus(index);

        return censusRail[index];
    }

    /** Whether the car at {@code index} is braking, on a brake rail or by its lever. */
    public boolean brakes(int index) {
        refreshCensus(index);

        return censusLever[index] || RailBrake.brakingRail(censusRail[index]);
    }

    /** Whether the car at {@code index} is standing on a lit powered rail. */
    public boolean boosts(int index) {
        refreshCensus(index);

        return RailBrake.boostingRail(censusRail[index]);
    }

    /** Whether any car of this train is braking, on a brake rail or by its lever. */
    public boolean braking() {
        for (int i = 0; i < cars.size(); i++) {
            if (brakes(i)) {
                return true;
            }
        }

        return false;
    }

    /** How many cars other than {@code driven} are braking. */
    public int brakingExcept(AbstractMinecart driven) {
        int count = 0;

        for (int i = 0; i < cars.size(); i++) {
            if (cars.get(i) != driven && brakes(i)) {
                count++;
            }
        }

        return count;
    }

    /** How many cars other than {@code driven} are standing on lit powered rails. */
    public int boostingExcept(AbstractMinecart driven) {
        int count = 0;

        for (int i = 0; i < cars.size(); i++) {
            if (cars.get(i) != driven && boosts(i)) {
                count++;
            }
        }

        return count;
    }

    /** Has every car read its rail and lever afresh the next time it is asked. */
    public void forgetCensus() {
        Arrays.fill(censusAt, Long.MIN_VALUE);
    }

    /** {@link #forgetCensus()} for the train {@code car} holds, if it holds one. */
    public static void forgetCensus(AbstractMinecart car) {
        Train held = peek(car);

        if (held != null) {
            held.forgetCensus();
        }
    }

    /**
     * The lowest speed ceiling of every car but {@code driven}, or infinity when there is no other
     * car. Taken once a tick for the car driving; each other car's own hook stands down.
     */
    public double ceilingExcept(AbstractMinecart driven, ServerLevel level) {
        long tick = level.getGameTime();

        if (ceilingTick == tick && ceilingDriven == driven) {
            return ceilingOthers;
        }

        double lowest = Double.POSITIVE_INFINITY;

        for (AbstractMinecart car : cars) {
            if (car != driven) {
                lowest = Math.min(lowest, car.polytrains$ownMaxSpeed(level));
            }
        }

        ceilingOthers = lowest;
        ceilingTick = tick;
        ceilingDriven = driven;

        return lowest;
    }

    // Reads the car at index's rail and lever, unless already read this tick where it now stands.
    private void refreshCensus(int index) {
        AbstractMinecart car = cars.get(index);
        long tick = car.level().getGameTime();

        if (censusAt[index] == tick && censusX[index] == car.getX()
                && censusY[index] == car.getY() && censusZ[index] == car.getZ()) {
            return;
        }

        censusRail[index] = car.level().getBlockState(car.getCurrentBlockPosOrRailBelow());
        censusLever[index] = RailBrake.leverHeld(car);
        censusX[index] = car.getX();
        censusY[index] = car.getY();
        censusZ[index] = car.getZ();
        censusAt[index] = tick;
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
    public double rollingFriction(long tick) {
        return rollingFrictionTick == tick ? rollingFriction : 0.0;
    }

    /** Holds this train's mean rolling friction for the rest of {@code tick}. */
    public void setRollingFriction(long tick, double mean) {
        rollingFriction = mean;
        rollingFrictionTick = tick;
    }

    /**
     * Where this train's head sat when it was last seated, or null if that is not known against the
     * ribbon's current arc frame — never seated, or seated before a reset renumbered the arcs.
     */
    public @Nullable Double headArc() {
        return headArcAt == ribbon.arcEpoch() ? headArc : null;
    }

    /**
     * Records where this train's head has been seated, or forgets it with null. Forgotten leaves
     * the next seating nothing to measure a turn against, which is the right answer for a train
     * that has been driven by vanilla in between: the ground it covered was not recorded from here.
     */
    public void setHeadArc(@Nullable Double arc) {
        headArc = arc == null ? 0.0 : arc;
        headArcAt = arc == null ? -1 : ribbon.arcEpoch();
    }

    /** How far this train's head turned on its last seating, in right angles. */
    public double turn() {
        return turn;
    }

    /** Records that {@code leader} stood at {@code arc} when this train was laid out at tick. */
    public void setLaidLead(AbstractMinecart leader, double arc, long tick) {
        laidLeader = leader;
        laidLeadArc = arc;
        laidTick = tick;
        laidLeadArcAt = ribbon.arcEpoch();
    }

    /**
     * Where {@code leader} stood when this train was laid out on the tick before {@code tick}, or
     * null if it was not, {@code leader} was not leading it then, or the arcs have been renumbered.
     */
    public @Nullable Double laidLeadArc(AbstractMinecart leader, long tick) {
        return leader == laidLeader && laidTick == tick - 1 && laidLeadArcAt == ribbon.arcEpoch()
                ? laidLeadArc : null;
    }

    /**
     * Has the laying out recorded on the tick before {@code tick} stand for {@code tick} as well,
     * for a train {@link TrainPlacer#holdStill} has found where it was laid.
     */
    public void restampLaid(long tick) {
        if (laidTick == tick - 1) {
            laidTick = tick;
        }
    }

    /**
     * Records that this train was laid out on {@code tick} with nothing moved and nothing left to
     * move: where each placed car stood and on which rail, null for a car not placed, where its
     * leading car stood, and every other train's cart then in reach of it.
     */
    public void markStill(long tick, @Nullable Vec3[] positions, @Nullable BlockPos[] rails,
            Vec3 leaderPos, List<AbstractMinecart> neighbours) {
        stillTick = tick;
        stillEpoch = ribbon.arcEpoch();
        stillPositions = positions;
        stillRails = rails;
        stillLeaderPos = leaderPos;
        stillNeighbours = neighbours;
        stillNeighbourPositions = new Vec3[neighbours.size()];

        for (int i = 0; i < neighbours.size(); i++) {
            stillNeighbourPositions[i] = neighbours.get(i).position();
        }
    }

    /** Whether every cart in reach when this train was last laid out still is where it stood. */
    public boolean neighboursInPlace() {
        for (int i = 0; i < stillNeighbours.size(); i++) {
            AbstractMinecart neighbour = stillNeighbours.get(i);

            if (neighbour.isRemoved()
                    || !neighbour.position().equals(stillNeighbourPositions[i])) {
                return false;
            }
        }

        return true;
    }

    /** Has the still layout recorded before {@code tick} stand for {@code tick} as well. */
    public void restampStill(long tick) {
        stillTick = tick;
    }

    /** Forgets this train's still layout, so the next laying out is a full one. */
    public void wake() {
        stillTick = Long.MIN_VALUE;
    }

    /** Forgets the still layout of whatever train {@code car} was last handed, if any. */
    public static void wakeTrainOf(AbstractMinecart car) {
        Train held = car.polytrains$train();

        if (held != null) {
            held.wake();
        }
    }

    /**
     * Whether this train was laid out still on the tick before {@code tick}, against the arc frame
     * its ribbon still has.
     */
    public boolean stillSince(long tick) {
        return stillTick == tick - 1 && stillEpoch == ribbon.arcEpoch();
    }

    /** Whether this train was laid out still on {@code tick}, for the meter. */
    public boolean still(long tick) {
        return stillTick == tick;
    }

    /** Where the car at {@code index} stood when last laid out still, or null if not placed. */
    public @Nullable Vec3 stillPosition(int index) {
        return stillPositions == null ? null : stillPositions[index];
    }

    /** The rail the car at {@code index} was last laid out still on, or null if not placed. */
    public @Nullable BlockPos stillRail(int index) {
        return stillRails == null ? null : stillRails[index];
    }

    /** Where the leading car stood when this train was last laid out still. */
    public @Nullable Vec3 stillLeaderPos() {
        return stillLeaderPos;
    }

    /** Records how far this train's head has just turned, in right angles. */
    public void setTurn(double angles) {
        turn = angles;
    }

    /**
     * Which way round this train lies on its ribbon — +1 when its head sits at the higher arc — or
     * zero if that has not been settled against the ribbon's current numbering.
     */
    public int orderSign() {
        return ribbon.arcEpoch() == orderSignAt ? orderSign : 0;
    }

    /** Settles which way round this train lies, for as long as the ribbon keeps its arc frame. */
    public void setOrderSign(int sign) {
        if (orderSign != 0 && orderSign != sign && LOGGER.isDebugEnabled()) {
            LOGGER.debug("train of {} now lies {} on its ribbon, was {}",
                    cars.size(), sign, orderSign);
        }

        orderSign = sign;
        orderSignAt = ribbon.arcEpoch();
    }

    /**
     * Settles which way round this train lies on a ribbon that has just been started over, from
     * the one thing a fresh ribbon does say about it.
     *
     * <p>A reset numbers its first node off the movement of the car that stepped onto it, so arc
     * increases the way that car was going — which puts that car at the highest arc of the train,
     * every other car trailing it at lower ones. The head is therefore at the higher arc exactly
     * when the head is the car that was leading, and the lie is that comparison and nothing else.
     *
     * <p>Settled here rather than read back off the cars. Two cars standing closer together than a
     * quarter of their gap cannot say which is in front — what a train collapsed against a buffer
     * looks like, and what {@code ORDER_UNREADABLE} refuses on — and a reading taken from them and
     * got wrong lays the train backwards for the life of the frame. The reset knows the answer
     * outright.
     *
     * <p>Only at the reset. The frame's numbering is fixed from here, but which car leads is not —
     * a train reverses routinely, and after one the leading car is at the <i>lowest</i> arc, so the
     * same comparison made later gives the opposite answer. That is why this cannot be deferred
     * into {@link #orderSign()} and asked lazily.
     */
    public void relaidOn(AbstractMinecart leading) {
        int leadIndex = cars.indexOf(leading);

        if (leadIndex >= 0) {
            setOrderSign(leadIndex == 0 ? 1 : -1);
        }
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
        // Every car of a train is handed it at build, so membership is an identity test.
        return car.polytrains$train() == this;
    }

    /** The index of {@code car} among this train's cars, or -1 if it is not one of them. */
    public int indexOf(AbstractMinecart car) {
        return car.polytrains$train() == this ? car.polytrains$trainIndex() : cars.indexOf(car);
    }

    /** The car {@code car} follows, or null if it heads the train or is unlinked. */
    public @Nullable AbstractMinecart follows(AbstractMinecart car) {
        int index = indexOf(car);

        return index > 0 ? cars.get(index - 1) : null;
    }

    // Holds the end that leads against a reading that has not stood for long. The tick a ribbon
    // resets, the placer bails and every car is moved by vanilla instead, so one car's velocity can
    // confidently name the wrong end for exactly as long as it takes to lay the train backwards.
    private @Nullable AbstractMinecart settledLeader() {
        AbstractMinecart candidateLeader = decideLeader();

        if (candidateLeader == null || leader == null || candidateLeader == leader
                || !cars.contains(leader)) {
            flipHeld = 0;
            return candidateLeader;
        }

        // A train the placer is not holding is loose carts, each driven by vanilla, and a car of
        // it moving towards the others is shunting them rather than leading a reversal. The end
        // already named keeps recording: a change of mind starts the ribbon over from a car whose
        // track may not reach the rest, which is what left them unplaced in the first place.
        //
        // A train held bar one car is still held, and still free to reverse. Asked the other way
        // round, a train with one seat fouled could not change the end it led with — so it could
        // not decide to back off whatever was fouling it, which is half of why it stayed there.
        if (!holding()) {
            flipHeld = 0;
            return leader;
        }

        if (candidateLeader != flipTo) {
            flipTo = candidateLeader;
            flipHeld = 0;
        }

        return ++flipHeld >= LEADER_FLIP_TICKS ? candidateLeader : leader;
    }

    // Which end the train is running towards, judged along the ribbon rather than through space. A
    // single car has nothing to measure against and is its own leading end.
    private @Nullable AbstractMinecart decideLeader() {
        AbstractMinecart head = cars.getFirst();
        Vec3 headingDirection = head.getDeltaMovement().horizontal();
        double speed = headingDirection.length();
        AbstractMinecart heldEnd = heldLeader();

        if (cars.size() == 1) {
            return speed < EPSILON ? null : head;
        }

        // A train with nothing to read a leading end off — never moved, or moving with no track
        // recorded yet — names its head rather than nothing. Without an end the placer refuses
        // outright and every car runs on vanilla's own pipeline: a standing train straddling a
        // grade collapses, and a moving one stretches out along the track, for good, since the
        // ribbon only ever grows behind the car already named. Either end answers for where the
        // cars go, because lieOf reads the lie off their arcs and not off this choice, and the
        // ribbon it starts lets the next reading correct the end. It does not answer for which way
        // the cars face, so the placer declines to turn a train it cannot read a heading from.
        if (speed < EPSILON) {
            return heldEnd != null ? heldEnd : head;
        }

        // Nor does movement a brake is about to cancel. Under a brake anywhere along it, a train
        // moving slower than the dead-stop floor is stopped outright later this tick, but a shove
        // landed between ticks is read here first — a player leaning on a held car for three ticks
        // would hand the lead to the far end of a train that never moved. Anything faster does
        // move it, so a real reversal still names its new end.
        if (RailBrake.stopsDead(head.getDeltaMovement()) && braking()) {
            return heldEnd != null ? heldEnd : head;
        }

        Double headCarArc = ribbon.arcOf(head);
        Double nextArc = ribbon.arcOf(cars.get(1));
        Vec3 tangent = headCarArc == null ? null : ribbon.tangentAt(headCarArc);

        if (headCarArc == null || nextArc == null || tangent == null) {
            return heldEnd != null ? heldEnd : head;
        }

        // Which way the head end lies along the ribbon, against which way the train is running
        // along it. Both are arc, because a chord through space says nothing about track that
        // doubles back on itself: round a tight turn the car behind sits level with or ahead of the
        // one in front, so a chord reads the train as running the other way and lays it backwards.
        double outward = headCarArc - nextArc;
        double headingAlongTangent = headingDirection.dot(tangent) / speed;

        // A reading too weak to name an end keeps the one already named, and names the head when
        // there is none. Not null: every train of two or more has a leading end, because one car of
        // it has to speak for the whole when the per-car tick comes round, and the head answers
        // that as well as either end would.
        if (Math.abs(outward) < EPSILON || Math.abs(headingAlongTangent) < LEADER_HOLD) {
            return heldEnd != null ? heldEnd : head;
        }

        return headingAlongTangent * outward > 0.0 ? head : cars.getLast();
    }

    // The end that was leading, so long as it is still an end of ours — a train rebuilt around a
    // split can be holding a car that has since gone its own way, and one rebuilt around a merge
    // can be holding a car that is now somewhere in the middle. Membership alone is not enough:
    // a leading car mid-train is the one thing the placer cannot lay a train from.
    private @Nullable AbstractMinecart heldLeader() {
        return leader == cars.getFirst() || leader == cars.getLast() ? leader : null;
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
    // ends has the lower UUID — the one fact both ends agree on without being told. Only the ends
    // are compared: a car in the middle cannot head a train however low its own UUID.
    private static Train build(AbstractMinecart car, long tick,
            @Nullable TrackRibbon inherited) {
        List<AbstractMinecart> walked = new ArrayList<>();
        AbstractMinecart end = walk(car, null);

        // A link graph that comes back on itself is corruption, not a kind of train. Coupling two
        // carts already in one train is refused, so nothing a player does builds one; only a save
        // written half way can. Read from the lowest-UUID car so every car of it agrees where the
        // list starts, and go on as an ordinary train — the links are not touched, so this decides
        // nothing but the ordering.
        if (end == null) {
            walk(car, walked);

            AbstractMinecart from = lowest(walked);

            LOGGER.warn("train of {} links back on itself, reading it from {}",
                    walked.size(), from == null ? car.getUUID() : from.getUUID());
            walked.clear();
            walk(from == null ? car : from, walked);

            return new Train(walked, tick, inherited);
        }

        walk(end, walked);

        if (walked.size() > 1 && sortsFirst(walked.getLast(), walked.getFirst())) {
            Collections.reverse(walked);
        }

        return new Train(walked, tick, inherited);
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

            AbstractMinecart nextCar = onward(current, previous);

            if (nextCar == null) {
                return current;
            }

            if (nextCar == start) {
                return null;
            }

            previous = current;
            current = nextCar;
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
