package club.rainbowkitty.polytrains.train;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.polytrains.ext.AbstractMinecartExt;
import club.rainbowkitty.polytrains.ext.Linkable;
import club.rainbowkitty.polytrains.rail.RailDrag;
import club.rainbowkitty.polytrains.rail.TrackRibbon;
import club.rainbowkitty.polytrains.util.MinecartHelper;

/**
 * Seats a train's trailing cars along the ribbon at an offset from the leading car's arc, rather
 * than adjusting the distance between each pair on every tick.
 *
 * <p>Only the leading car is left to vanilla. Every other car is measured from its arc and placed
 * outright, so one car the ribbon cannot answer for leaves the rest seated where they belong;
 * only a bail with no frame of reference at all — no leading car, or no way to read which end
 * leads — hands the whole train back.
 */
public final class TrainPlacer {

    // Below this, an arc step counts as no movement at all.
    private static final double EPSILON = 1.0E-5;

    // One rail step can turn a cart a full right angle (two opposite corners), which float noise
    // puts on either side of square.
    private static final double NOSE_TURN_SLACK = 0.1;

    // How far past flush against a blocked end still counts as touching it.
    private static final double CONTACT_SLACK = 0.2;

    // How much further than a tick's travel two arcs may be and still be two ends of one move. One
    // node's worth of track, since that is the grain a sweep is answered at.
    private static final double SWEEP_SLACK = 1.5;

    // Past an end car's box, how near another train's cart must stand to be looked at as the end
    // of the track.
    private static final double END_SHELL = 0.5;

    // How far past a car a joint it can no longer cross is looked for: past any draw-back.
    private static final double BARRED_REACH = 2.0;

    // Finding where a car clears another's box, by step along the track then halving the last.
    // Stepped by box, since two boxes on a diagonal touch with their middles 1.39 apart.
    private static final double CLEAR_STEP = 0.1;
    private static final double CLEAR_REACH = 2.0;
    private static final int CLEAR_HALVINGS = 4;

    // Spacing of the boxes a car's run to its seat is checked at: under half a car, so the boxes
    // overlap and nothing between two of them is missed.
    private static final double PATH_STEP = 0.4;

    // How far a run to a seat may bend outside the two boxes it joins, on a U-turn.
    private static final double RUN_SHELL = 1.0;

    // How far past a car's own box a player still counts as leaning on it.
    private static final double SHOVE_SHELL = 0.1;

    // Minimum cosine between a player's push and the rail for it to count as shoving the car.
    private static final double SHOVE_ARC = 0.5;

    // How far a car flying off a jump may trail the car ahead of it before its chain pulls it on:
    // slack for the spread of a flight, short of the link's full length.
    private static final double AIR_SLACK = 2.5;

    // Ticks between full passes for a train holdStill keeps, staggered per train: the most a
    // missed wake can leave one standing.
    private static final int STILL_RECHECK_TICKS = 20;

    /**
     * Why a train could not be seated. {@link #RIBBON_SHORT} and {@link #CAR_OFF_RIBBON} name one
     * car's own track; {@link #LEADER_IN_AIR} a leading car flown off the end of its track, which
     * the rest still follow off it; the rest leave the placer with no frame of reference at all.
     */
    public enum Bail {
        NO_LEADING_END(true),
        LEADER_OFF_RIBBON(true),
        LEADER_IN_AIR(false),
        ORDER_UNREADABLE(true),
        RIBBON_SHORT(false),
        CAR_OFF_RIBBON(false),
        SEAT_FOULED(false);

        private final boolean whole;

        private Bail(boolean whole) {
            this.whole = whole;
        }

        /** Whether this bail takes the whole train back rather than leaving one car loose. */
        public boolean whole() {
            return whole;
        }
    }

    /**
     * Why a train could not be seated, and which car the answer is about.
     *
     * @param why the kind of bail
     * @param car the car index the bail names, or {@link #NONE} when it names the train instead
     * @param named the car index the log line names, or {@link #NONE}
     * @param arc the arc the log line names, or NaN
     * @param at the cart whose block the log line names, or null
     */
    public record Refusal(Bail why, int car, int named, double arc,
            @Nullable AbstractMinecart at) {

        /** The car index used when a refusal is about the train rather than one of its cars. */
        public static final int NONE = -1;
    }

    // Where one car goes: the point on the ribbon, the rail block under it, and its tangent.
    private record Seat(Vec3 position, BlockPos rail, Vec3 tangent) {}

    // Static-only; blocks instantiation.
    private TrainPlacer() {}

    /**
     * Seats every trailing car of {@code train} on its ribbon at intervals easing toward
     * {@code target}, and reports why when it could not. A refusal naming one car leaves the rest
     * seated around it; one naming none releases the whole train back to vanilla.
     *
     * @param train the train to seat
     * @param target the coupling interval each gap eases toward
     * @return the reason seating failed, or null if it fully succeeded
     */
    public static @Nullable Refusal seat(Train train, double target) {
        Refusal attempt = attempt(train, target);

        if (attempt != null && attempt.why().whole()) {
            release(train);
        }

        if (train.seatingChanged(attempt == null ? null : attempt.why())
                && TrainMeter.enabled()) {
            TrainMeter.refusal(train, attempt, attempt == null
                    ? "seated"
                    : "not seated, " + loose(train) + " loose: " + describe(train, attempt));
        }

        return attempt;
    }

    // Counts cars the placer is not holding, the leading car aside — vanilla usually drives it.
    private static int loose(Train train) {
        AbstractMinecart leader = train.leader();
        int loose = 0;

        for (AbstractMinecart car : train.cars()) {
            if (car != leader && !seated(car)) {
                loose++;
            }
        }

        return loose;
    }

    /**
     * Whether {@code car} is being placed on its track rather than driven along it by vanilla.
     *
     * <p>Normally no for a train's leading car even while its train is held — that car is the one
     * vanilla drives, and the only one that records track. A train backed against a buffer is the
     * exception: its leading car is placed as well, so that it stops exactly at the wall. One tick
     * of grace covers a car that ticks before its leader seats the train.
     */
    public static boolean seated(AbstractMinecart car) {
        if (!(car instanceof AbstractMinecartExt ext)) {
            return false;
        }

        long seatedTick = ext.polytrains$seatedAt();

        return seatedTick != 0 && seatedTick + 1 >= car.level().getGameTime();
    }

    /**
     * Whether {@code car}, flown off the end of its train's track behind the leading car, is moved
     * through the air by the placer rather than by its own tick, with the same tick of grace.
     */
    public static boolean flown(AbstractMinecart car) {
        if (!(car instanceof AbstractMinecartExt ext)) {
            return false;
        }

        long flownTick = ext.polytrains$flownAt();

        return flownTick != 0 && flownTick + 1 >= car.level().getGameTime();
    }

    /**
     * Every cart of another train standing against {@code end}, a car of {@code train}: as far past
     * its box as the placer looks for another train's cart to pack short of.
     */
    public static List<AbstractMinecart> cartsAgainst(Train train, AbstractMinecart end) {
        return foreignCarts(train, end.getBoundingBox().inflate(END_SHELL));
    }

    // Seats the train if the ribbon can answer for every car, naming the first bail otherwise.
    private static @Nullable Refusal attempt(Train train, double target) {
        AbstractMinecart leader = train.leader();

        if (leader == null || train.size() <= 1) {
            return new Refusal(Bail.NO_LEADING_END, Refusal.NONE, Refusal.NONE, Double.NaN, null);
        }

        TrackRibbon ribbon = train.ribbon();
        List<AbstractMinecart> cars = train.cars();
        Double leadArc = ribbon.arcOfLeader(leader);

        if (leadArc == null) {
            // Released, the cars behind stand a tick where they were seated if they tick before
            // it, then shunt each other to a stop on the ramp, solid to one another once let go.
            if (followOff(train, leader)) {
                return new Refusal(Bail.LEADER_IN_AIR, train.indexOf(leader), Refusal.NONE,
                        ribbon.leaderArc(), leader);
            }

            return new Refusal(Bail.LEADER_OFF_RIBBON,
                    ribbon.isEmpty() ? Refusal.NONE : train.indexOf(leader), Refusal.NONE,
                    Double.NaN, leader);
        }

        int leadIndex = train.indexOf(leader);
        // Where vanilla left the leading car, which stays its place unless it is seated elsewhere.
        double standingLeadArc = leadArc;

        // +1 when the leading car is the list's head, so a step toward the tail is a step up the
        // list; -1 at the other end.
        int fromHead = leadIndex == 0 ? 1 : -1;
        int tailIndex = leadIndex == 0 ? cars.size() - 1 : 0;
        int nearGapIndex = leadIndex == 0 ? 0 : cars.size() - 2;
        // Laid onto the track from mid-air, a car still flying off a jump would drop to it in one
        // tick; it flies on instead, and vanilla's guarded catch sets it on its rail.
        Train.Departure departure = departureOf(train, leader, ribbon);
        boolean[] flying = stillFlying(cars, leadIndex, fromHead, departure);
        // Nor is a car still on the ramp laid onto the track below; it runs on off the ramp.
        boolean[] departing = departing(train, cars, leadIndex, fromHead, flying);
        boolean[] placedElsewhere = flying.clone();
        // Flown last tick and down now: drawn to its seat from where it came down.
        boolean[] landed = new boolean[cars.size()];

        for (int i = 0; i < cars.size(); i++) {
            placedElsewhere[i] |= departing[i];
            landed[i] = i != leadIndex && !placedElsewhere[i] && flown(cars.get(i));
        }

        Double nextArc = ribbon.arcOf(cars.get(leadIndex + fromHead));
        double speed = leader.getDeltaMovement().horizontalDistance();

        // A leading car seated last tick, its train backed against something, skipped vanilla's
        // move and the clamp inside it, so shoves pile up on it unspent. Capped as vanilla would.
        if (seated(leader) && leader.level() instanceof ServerLevel level) {
            speed = Math.min(speed, leader.polytrains$ownMaxSpeed(level));
        }

        // Where each pair stands, read once: nothing below moves a car before it is placed.
        double[] standing = train.standingGaps(TrainCoupling.maxLinkDistance());
        double[] gaps = train.nextGaps(target, standing, speed, landed, false);
        double travelSign = travelSign(leader, ribbon.tangentAt(leadArc));
        int order = train.orderSign();

        if (order == 0) {
            Integer lieSign = resolveOrder(
                    nextArc, leadArc, fromHead, gaps[nearGapIndex], travelSign);

            if (lieSign == null) {
                return new Refusal(Bail.ORDER_UNREADABLE, Refusal.NONE, Refusal.NONE, leadArc,
                        null);
            }

            // Settled once; later ticks read it back instead of re-deriving it from the cars.
            train.setOrderSign(lieSign);
            order = lieSign;
        }

        Arcs arcs = arcsFor(ribbon, gaps, leadArc, order, fromHead, leadIndex, tailIndex);
        double[] offsets = arcs.offsets();
        double headArc = arcs.headArc();
        double tailArc = arcs.tailArc();

        AbstractMinecart tailCar = cars.get(tailIndex);
        double tailHalfWidth = tailCar.getBbWidth() * 0.5;
        double noseLength = leader.getBbWidth() * 0.5;
        boolean farEnd = tailArc > leadArc;
        // +1 when the tail lies towards the ribbon's far end, so past the tail is up the arcs.
        double tailSign = farEnd ? 1.0 : -1.0;

        // Every other train's cart the checks below can reach, read once and only when asked. Over
        // this tick's run, not the furthest one: that takes in a yard's next line, cart by cart.
        Nearby nearby = new Nearby(train, trainBox(cars).inflate(RUN_SHELL + speed + EPSILON));

        // Where the track behind the tail ends against something it cannot pass: a recorded end
        // run into a block, or a cart of another train standing on this track. A cart is the end
        // of usable track and not a refusal, so the train packs short of it with every car seated.
        Double tailEndArc = nearerEnd(
                recordedEnd(ribbon, leader.level(), tailArc, tailHalfWidth, tailSign),
                foreignEnd(nearby, ribbon, tailCar, tailArc, tailSign, tailHalfWidth), tailSign);
        double roomToEnd = tailEndArc == null
                ? Double.POSITIVE_INFINITY : (tailEndArc - tailArc) * tailSign;
        // Whether the tail end of the train is backed against track it cannot pass.
        boolean blocked = roomToEnd + EPSILON < tailHalfWidth;

        // The same question for the end ahead of the leading car, which a train can be driven
        // into. Inclusive of exact contact, since vanilla already stops a cart its own half-length
        // short of a block, and short of another cart at its box. Read at any distance for a
        // blocked train, since the draw-back below must not pass it.
        double jamReach = noseLength * (1.0 + CONTACT_SLACK);
        Double aheadEndArc = nearerEnd(recordedEnd(ribbon, leader.level(), leadArc,
                        blocked ? Double.POSITIVE_INFINITY : jamReach + EPSILON, -tailSign),
                foreignEnd(nearby, ribbon, leader, leadArc, -tailSign, noseLength), -tailSign);
        double roomAhead = aheadEndArc == null
                ? Double.POSITIVE_INFINITY : (leadArc - aheadEndArc) * tailSign;
        boolean jammed = roomAhead <= jamReach;

        // Held at both ends, a train goes nowhere whichever way it reads as moving, so what pokes
        // past its tail comes out of its couplings rather than out of the end ahead of it.
        boolean squeezed = blocked && jammed;

        // A train too slow to read a heading presses, unless it has just turned away from the
        // blocked end: then it leaves on its engines' aim rather than held against what is there.
        double leaveSign = TrainCoupling.justTurned(leader)
                ? leaveSign(leader, ribbon.tangentAt(leadArc)) : travelSign;
        boolean pressing = blocked && (squeezed || leaveSign == 0.0 || leaveSign == tailSign);

        // Only a train something is actually pushing packs, and only as far back as that reaches.
        int packReach = jammed
                ? pressedGaps(train, ribbon, headArc, offsets, order, leadIndex, -tailSign)
                : 0;
        boolean packing = packReach > 0;

        // Recorded while there is still somewhere to go, so a train with nowhere left to go closes
        // its couplings at the speed it arrived with rather than the zero it reads once stopped.
        // On rails only: vanilla halves a leader resting on a lip, and the cars after it take it.
        if (!packing && leader.isOnRails()) {
            train.setCarried(speed);
        }

        // A pressing or packing train still widens against zero speed: paying that widening into
        // gaps it has nowhere to drive a car into would pin it to the wall it is pressing on.
        if (pressing || packing) {
            gaps = train.nextGaps(target, standing, 0.0, landed, true);
            offsets = offsets(ribbon, gaps, leadArc, order, fromHead);
        }

        if (pressing) {
            tailArc = leadArc + order * (offsets[leadIndex] - offsets[tailIndex]);
            roomToEnd = (tailEndArc - tailArc) * tailSign;

            // The train only stops once there is no give left to close.
            if (train.compress(gaps, target, tailHalfWidth - roomToEnd,
                    tailIndex > leadIndex, gaps.length) > EPSILON) {
                speed = 0.0;
            }
        }

        boolean closed = false;

        if (packing) {
            closed = train.pack(gaps, target, fromHead < 0, packReach);

            // Zeroed unless the train reads as leaving the block: a seated car carries the
            // train's own movement, so a nonzero speed here would drive it back into what stopped
            // it.
            if (travelSign == 0.0 || travelSign == -tailSign) {
                speed = 0.0;
            }
        }

        // A train held at both ends goes nowhere, whatever speed shoves have left on its leading
        // car; seated cars given that speed would strike with it and lurch off with it once let go.
        if (squeezed) {
            speed = 0.0;
        }

        if (pressing || packing) {
            arcs = arcsFor(ribbon, gaps, leadArc, order, fromHead, leadIndex, tailIndex);
            offsets = arcs.offsets();
            headArc = arcs.headArc();
            tailArc = arcs.tailArc();
        }

        boolean drawnBack = false;

        if (blocked) {
            roomToEnd = (tailEndArc - tailArc) * tailSign;

            // Draws the whole train back just far enough that its tail clears the blocked end, and
            // never past the end ahead: a car set down inside a block passes through it.
            double pushback = Math.min(Math.max(0.0, tailHalfWidth - roomToEnd),
                    Math.max(0.0, roomAhead - noseLength)) * -tailSign;

            // Nor into a cart standing beside the track there: every other car is laid from it.
            drawnBack = Math.abs(pushback) > EPSILON
                    && !seatTaken(nearby, ribbon, leader, leadArc + pushback);

            if (drawnBack) {
                leadArc += pushback;
                headArc += pushback;
            }
        }

        Seating seating = seatCars(ribbon, cars, headArc, offsets, order, leadIndex, drawnBack,
                leadArc, travelSign, placedElsewhere);

        // A car whose run to its seat meets another train's cart stops where its box first touches
        // it, and the rest of the train stops with it rather than going on without it: held there,
        // its leading car seated too, until the way clears or its engines give up and turn it. It
        // keeps up with the cart holding it and no faster, having struck it at this tick's start.
        // Drawn back no further than any car ran, a car still in contact is let go as before.
        Double laidArc = train.laidLeadArc(leader, leader.level().getGameTime());

        // Which way the cars run to their seats: the way the leading car went since it was laid,
        // which a train stopped this tick by what it met still has and its speed no longer says.
        double runSign = laidArc != null && Math.abs(leadArc - laidArc) > EPSILON
                ? Math.signum(leadArc - laidArc) : travelSign;
        double leadRun = laidArc == null ? 0.0 : (leadArc - laidArc) * runSign;
        // Off a blocked end the draw-back above has the train, which a hold against a run away from
        // that end would undo; one that ran into it is held as any other.
        Contact contact = runSign == 0.0 || blocked && drawnBack && runSign != tailSign
                ? null : contactOf(train, nearby, cars, seating, leadIndex, runSign, leadRun);
        boolean held = false;
        double back = 0.0;

        if (contact != null && contact.depth() > EPSILON) {
            // Run into something, a train lengthens no further into it.
            if (!pressing && !packing) {
                gaps = train.nextGaps(target, standing, 0.0, landed, true);
                arcs = arcsFor(ribbon, gaps, leadArc, order, fromHead, leadIndex, tailIndex);
                offsets = arcs.offsets();
                headArc = arcs.headArc();
                seating = seatCars(ribbon, cars, headArc, offsets, order, leadIndex, drawnBack,
                        leadArc, travelSign, placedElsewhere);
                contact = contactOf(train, nearby, cars, seating, leadIndex, runSign, leadRun);
            }

            // Nor into a cart that has come up behind the leading car since, as the draw-back
            // off a blocked end is not: every other car is laid from it.
            double drawBack = Math.min(contact.depth(), contact.backRoom());

            held = drawBack > EPSILON
                    && !seatTaken(nearby, ribbon, leader, leadArc - runSign * drawBack);
            back = held ? drawBack : 0.0;
        }

        if (held) {
            leadArc -= runSign * back;
            headArc -= runSign * back;
            drawnBack = true;
            speed = Math.min(speed, contact.recede());
            seating = seatCars(ribbon, cars, headArc, offsets, order, leadIndex, drawnBack,
                    leadArc, travelSign, placedElsewhere);
        }

        Seat[] seats = seating.seats();
        double[] seatArcs = seating.seatArcs();
        Refusal refusal = seating.refusal();

        // Waits rather than counts against the train. A cart on this track past either end has
        // shortened it already; what is left stands off this track or between the train's own cars.
        Seat[] offered = seats.clone();
        Foul foul = fouled(ribbon, nearby, cars, seats, seatArcs, leadIndex);

        // A car let go that way is still coupled: the train is drawn back until that car's seat is
        // where it stands, no further than its leading car ran less what the hold drew, and waits
        // there till the seat clears or its engines turn it.
        double tether = foul == null || contact == null ? 0.0 : tetherBack(ribbon, cars, offered,
                seats, seatArcs, leadIndex, runSign, leadRun - back);

        if (tether > EPSILON && !seatTaken(nearby, ribbon, leader, leadArc - runSign * tether)) {
            leadArc -= runSign * tether;
            headArc -= runSign * tether;
            drawnBack = true;
            held = true;
            speed = 0.0;
            seating = seatCars(ribbon, cars, headArc, offsets, order, leadIndex, drawnBack,
                    leadArc, travelSign, placedElsewhere);
            seats = seating.seats();
            seatArcs = seating.seatArcs();
            refusal = seating.refusal();
            foul = fouled(ribbon, nearby, cars, seats, seatArcs, leadIndex);
        }

        if (foul != null && refusal == null) {
            refusal = new Refusal(Bail.SEAT_FOULED, Refusal.NONE, foul.carIndex(), Double.NaN,
                    foul.foulingCart());
        }

        // A train too slow to read a heading keeps facing whichever end nominally leads, rather
        // than reading a direction out of noise.
        double faceSign = travelSign != 0.0 ? travelSign : order * fromHead;
        // The furthest any car can have run to its seat this tick, whatever speed the train is laid
        // with: a hold cuts that speed, and a car packing up or closing a gap outruns its lead.
        double runLimit = furthestRun(leader);
        double moved = 0.0;
        boolean anyElsewhere = false;
        // A placed car's push is the one shove that parts it from another train's cart, so only a
        // train with none in reach, as holdStill takes, goes without it at rest.
        List<AbstractMinecart> inReach = nearby.in(trainBox(cars).inflate(RUN_SHELL));
        boolean shoveAtRest = !inReach.isEmpty();

        for (int i = 0; i < cars.size(); i++) {
            anyElsewhere |= placedElsewhere[i];

            if (i == leadIndex && !drawnBack) {
                continue;
            }

            if (placedElsewhere[i]) {
                continue;
            }

            if (seats[i] == null) {
                // It still holds the movement it was last placed with, which a train stopped since
                // no longer has: let go, it runs no faster than its train now does.
                loosen(cars.get(i));
                slowTo(cars.get(i), speed);
                continue;
            }

            moved = Math.max(moved, place(ribbon, cars.get(i), seats[i], seatArcs[i], faceSign,
                    speed, runLimit, i != leadIndex, shoveAtRest));

            // Drawn back off an end the train is leaving, the leading car stays vanilla's: seated,
            // it skips vanilla's move and is drawn back again every tick that end still presses.
            if (i == leadIndex && !pressing && !held) {
                loosen(cars.get(i));
            }
        }

        flyOn(cars, flying, leadIndex, fromHead);

        // No slower than they left before the landing unless the landed train is held: the air
        // has slowed its leader, and a car leaving slower than that stalls on the lip's support.
        if (departure != null && leader.level() instanceof ServerLevel level) {
            double departSpeed = held || squeezed || pressing || packing
                    ? speed : Math.max(speed, departure.speed());

            runDeparture(level, train, cars, departing, departure, leadIndex, fromHead,
                    Math.min(departSpeed, leader.polytrains$ownMaxSpeed(level)), runLimit);
        }

        boolean gapsHeld = train.gapsHeld(gaps);

        train.setGaps(gaps);

        if (closed) {
            train.setPacked(leader.level().getGameTime());
        }

        // Billed once at the head rather than once per car: every car crosses the same joints the
        // head already did, so the total comes out the same either way.
        Double previousHeadArc = train.headArc();
        double turn = previousHeadArc == null ? 0.0 : ribbon.turnBetween(previousHeadArc, headArc);

        train.setHeadArc(headArc);
        train.setTurn(turn);
        train.setLaidLead(leader, drawnBack && seats[leadIndex] != null ? leadArc : standingLeadArc,
                leader.level().getGameTime());
        RailDrag.bend(leader, turn);

        // Laid exactly where it stood with nothing left to close, so the next tick may skip all
        // this; the leading car, never placed here, stays vanilla's. Nor while a car's push still
        // has another train's cart to part it from, which holding still would skip.
        boolean still = refusal == null && !anyElsewhere && !drawnBack && !closed && gapsHeld
                && moved <= EPSILON && speed <= EPSILON
                && leader.getDeltaMovement().horizontalDistance() < EPSILON
                && !touching(cars, inReach);

        if (still) {
            markStill(train, cars, seats, leadIndex, leader, inReach);
        } else {
            train.wake();
        }

        // A train held still in reach would miss this one coming till its next full pass.
        if (moved > EPSILON || speed > EPSILON || anyElsewhere) {
            for (AbstractMinecart other : inReach) {
                Train.wakeTrainOf(other);
            }
        }

        if (refusal != null) {
            return refusal;
        }

        return null;
    }

    // Whether any of cars stands against one of others, as near as a cart's push reaches.
    private static boolean touching(List<AbstractMinecart> cars, List<AbstractMinecart> others) {
        for (AbstractMinecart other : others) {
            AABB otherBox = other.getBoundingBox().inflate(EPSILON);

            for (AbstractMinecart car : cars) {
                if (car.getBoundingBox().intersects(otherBox)) {
                    return true;
                }
            }
        }

        return false;
    }

    // Records where every car but the leading one was placed this tick, for holdStill.
    private static void markStill(Train train, List<AbstractMinecart> cars, Seat[] seats,
            int leadIndex, AbstractMinecart leader, List<AbstractMinecart> inReach) {
        Vec3[] positions = new Vec3[cars.size()];
        BlockPos[] rails = new BlockPos[cars.size()];

        for (int i = 0; i < cars.size(); i++) {
            if (i != leadIndex && seats[i] != null) {
                positions[i] = cars.get(i).position();
                rails[i] = seats[i].rail();
            }
        }

        train.markStill(leader.level().getGameTime(), positions, rails, leader.position(),
                inReach);
    }

    /**
     * Holds {@code train} where it was laid out last tick, if it was laid out still and nothing
     * has moved it since, doing for each placed car what placing it again would do. False, having
     * changed nothing, when the train needs laying out in full.
     *
     * <p>A fixed point of {@link #seat}: whatever wakes a parked train reaches its leading car's
     * movement, a car's position, the arc frame, a cart in reach, or a player in reach. A switch
     * thrown near it, a shove, a strike, another train moving in reach or a cart added in reach
     * wakes it outright, and a full pass now and then covers anything missed.
     */
    public static boolean holdStill(ServerLevel level, Train train) {
        long tick = level.getGameTime();

        if (!train.stillSince(tick)
                || Math.floorMod(tick + System.identityHashCode(train), STILL_RECHECK_TICKS) == 0) {
            return false;
        }

        AbstractMinecart leader = train.leader();

        // Vanilla's own floor for a cart going nowhere, not zero: a coasted cart keeps a crumb.
        if (leader == null || train.laidLeadArc(leader, tick) == null
                || !leader.position().equals(train.stillLeaderPos())
                || leader.getDeltaMovement().horizontalDistanceSqr() >= EPSILON * EPSILON
                || train.driving()) {
            return false;
        }

        List<AbstractMinecart> cars = train.cars();
        BlockState[] rails = new BlockState[cars.size()];

        for (int i = 0; i < cars.size(); i++) {
            AbstractMinecart car = cars.get(i);
            Vec3 position = train.stillPosition(i);
            BlockPos rail = train.stillRail(i);

            if (position == null || rail == null) {
                if (car != leader) {
                    return false;
                }

                continue;
            }

            if (!seated(car) || flown(car) || !car.position().equals(position)) {
                return false;
            }

            rails[i] = level.getBlockState(rail);

            if (!BaseRailBlock.isRail(rails[i])) {
                return false;
            }
        }

        // Carts in reach must stand where they stood; a train coming into reach wakes this one,
        // which is cheaper than asking the level for carts every tick.
        if (!train.neighboursInPlace()) {
            return false;
        }

        AABB leanBox = trainBox(cars).inflate(SHOVE_SHELL);

        // A rider leans on nothing; anyone else in reach may be packing a jammed train.
        for (ServerPlayer player : level.players()) {
            if (!player.isSpectator() && player.getBoundingBox().intersects(leanBox)
                    && !(player.getVehicle() instanceof AbstractMinecart ridden
                            && train.contains(ridden))) {
                return false;
            }
        }

        for (int i = 0; i < cars.size(); i++) {
            AbstractMinecart car = cars.get(i);
            Vec3 position = train.stillPosition(i);
            BlockPos rail = train.stillRail(i);

            if (position == null || rail == null || !(car instanceof AbstractMinecartExt ext)) {
                continue;
            }

            ext.polytrains$setSeatedAt(tick);
            // Takes back the gravity vanilla gave it this tick, as placing it would.
            car.setDeltaMovement(Vec3.ZERO);

            // As vanilla fires the rail a cart rests on every tick.
            if (rails[i].is(Blocks.ACTIVATOR_RAIL)) {
                car.activateMinecart(level, rail.getX(), rail.getY(), rail.getZ(),
                        rails[i].getValue(PoweredRailBlock.POWERED));
            }

            car.applyEffectsFromBlocks(position, position);
        }

        train.restampLaid(tick);
        train.setTurn(0.0);
        train.restampStill(tick);

        return true;
    }

    /**
     * Wakes every train {@link #holdStill} may be keeping with a car in reach of {@code cart},
     * which has just been added to its level: a cart that arrives without moving would wake none.
     */
    public static void wakeTrainsNear(AbstractMinecart cart) {
        for (AbstractMinecart other : cart.level().getEntitiesOfClass(AbstractMinecart.class,
                cart.getBoundingBox().inflate(RUN_SHELL), near -> near != cart)) {
            Train.wakeTrainOf(other);
        }
    }

    // Runs the cars still seated on after a leading car flown off the ribbon's end, and has each
    // leave the track at that end as the leading car did, one a tick; those gone ahead it flies on.
    // False, having moved nothing, for a leader knocked off mid-track or into a wall: it releases.
    private static boolean followOff(Train train, AbstractMinecart leader) {
        TrackRibbon ribbon = train.ribbon();

        // The old movement casts whatever block a car stands on to a rail, so it releases instead.
        if (!(leader.level() instanceof ServerLevel level)
                || !(leader.getBehavior() instanceof NewMinecartBehavior)
                || leader.isOnRails() || ribbon.isEmpty()) {
            return false;
        }

        double runSign = travelSign(leader, ribbon.tangentAt(ribbon.leaderArc()));
        boolean farEnd = runSign > 0.0;

        // Past the end as arcOfLeader reads it, so the two never disagree.
        if (runSign == 0.0 || !(ribbon.pastEnd(leader.position(), farEnd) > 0.0)
                || leader.onGround() && !ribbon.atLip(leader, farEnd)
                || ribbon.blockedBeyond(level, farEnd)) {
            return false;
        }

        List<AbstractMinecart> cars = train.cars();
        int leadIndex = cars.indexOf(leader);
        int fromHead = leadIndex == 0 ? 1 : -1;
        // What the train ran at off the rails, not what the air has left the leader: each car then
        // leaves at the leader's speed and flies its path a tick or two behind it.
        double speed = Math.min(train.carried(), leader.polytrains$ownMaxSpeed(level));
        List<AbstractMinecart> flying = new ArrayList<>();
        int firstIndex = leadIndex + fromHead;

        // A car let go is fine gone ahead off the end, not loose on the track; planRun refuses one
        // behind a seated car.
        while (inTrain(cars, firstIndex) && seatArcOn(ribbon, cars.get(firstIndex)) == null) {
            if (cars.get(firstIndex).isOnRails()) {
                return false;
            }

            flying.add(cars.get(firstIndex));
            firstIndex += fromHead;
        }

        Run run = null;

        if (inTrain(cars, firstIndex)) {
            run = planRun(ribbon, cars, firstIndex, fromHead, runSign,
                    farEnd ? ribbon.farArc() : ribbon.nearArc(), speed);

            if (run == null || fouled(ribbon, new Nearby(train, null), cars, run.seats(),
                    run.seatArcs(), leadIndex) != null) {
                return false;
            }

            keepDeparture(train, ribbon, leader, farEnd, cars.get(fromHead > 0
                    ? cars.size() - 1 : 0), speed);
        }

        double runLimit = furthestRun(leader);

        // Front first, each after the car ahead has moved, so each is held behind where that car
        // is now, not where it stood before a tick that could come either side of this one.
        for (AbstractMinecart car : flying) {
            fly(level, car);
        }

        if (run != null) {
            executeRun(level, ribbon, cars, run, runSign, speed, runLimit);
        }

        // As a release does: the head's last arc is no turn to charge from once it lands.
        train.setHeadArc(null);
        train.setTurn(0.0);

        return true;
    }

    // Keeps a copy of ribbon, cut to the stretch between tailCar and the end its train leaves by,
    // for the cars still on it once the leading car lands and the ribbon restarts under it.
    private static void keepDeparture(Train train, TrackRibbon ribbon, AbstractMinecart leader,
            boolean farEnd, AbstractMinecart tailCar, double speed) {
        Train.Departure kept = train.departure();
        Double tailArc = seatArcOn(ribbon, tailCar);

        if (tailArc == null || (kept != null && kept.leader() == leader
                && kept.ribbon().arcEpoch() == ribbon.arcEpoch())) {
            return;
        }

        TrackRibbon copy = new TrackRibbon(ribbon);

        if (farEnd) {
            copy.trim(tailArc - 1.0, copy.farArc());
        } else {
            copy.trim(copy.nearArc(), tailArc + 1.0);
        }

        train.setDeparture(new Train.Departure(copy, farEnd, leader, speed));
    }

    // Where each car from firstIndex out runs this tick towards the end at endArc, and the one
    // leaving it; one car leaving at a time, the rest held a gap behind the car ahead.
    private record Run(Seat[] seats, double[] seatArcs, int leavingIndex) {}

    // The run of the cars from firstIndex out, or null where one is not seated on ribbon, stands
    // out of order, or has no rail at its seat.
    private static @Nullable Run planRun(TrackRibbon ribbon, List<AbstractMinecart> cars,
            int firstIndex, int fromHead, double runSign, double endArc, double speed) {
        Seat[] seats = new Seat[cars.size()];
        double[] seatArcs = new double[cars.size()];
        int leavingIndex = Refusal.NONE;
        // Where the seated car ahead goes this tick and where it stood, which the next keeps its
        // gap to; none past a car leaving, whose way off the end is its own.
        Double aheadArc = null;
        double aheadSeatArc = 0.0;

        for (int i = firstIndex; inTrain(cars, i); i += fromHead) {
            AbstractMinecart car = cars.get(i);
            Double seatArc = seatArcOn(ribbon, car);

            if (seatArc == null
                    || aheadArc != null && (aheadSeatArc - seatArc) * runSign < 0.0) {
                return null;
            }

            // Along the rails, as offsets are: kept in arc, a gap shuts up coming off a slope.
            double gap = aheadArc == null ? 0.0 : ribbon.trackBetween(aheadSeatArc, seatArc);
            double limitArc = aheadArc == null
                    ? endArc : aheadArc - runSign * ribbon.arcAlong(aheadArc, gap, -runSign);
            double arc = seatArc + runSign * ribbon.arcAlong(seatArc, speed, runSign);
            boolean leaving = false;

            // Two leaving one tick would both come down where vanilla sets a car off an end.
            if ((arc - limitArc) * runSign > EPSILON) {
                leaving = aheadArc == null && leavingIndex == Refusal.NONE;
                arc = limitArc;
            }

            seats[i] = seatAt(ribbon, car, arc);
            seatArcs[i] = arc;

            if (seats[i] == null) {
                return null;
            }

            if (leaving) {
                leavingIndex = i;
            } else {
                aheadArc = arc;
                aheadSeatArc = seatArc;
            }
        }

        return new Run(seats, seatArcs, leavingIndex);
    }

    // Places run's cars along ribbon, the car leaving first, off the end before the car behind is
    // seated up to it.
    private static void executeRun(ServerLevel level, TrackRibbon ribbon,
            List<AbstractMinecart> cars, Run run, double runSign, double speed, double runLimit) {
        Seat[] seats = run.seats();
        double[] seatArcs = run.seatArcs();
        int leavingIndex = run.leavingIndex();

        if (leavingIndex != Refusal.NONE) {
            place(ribbon, cars.get(leavingIndex), seats[leavingIndex], seatArcs[leavingIndex],
                    runSign, speed, runLimit, true, true);
            leaveTrack(level, cars.get(leavingIndex));
        }

        for (int i = 0; i < cars.size(); i++) {
            if (seats[i] != null && i != leavingIndex) {
                place(ribbon, cars.get(i), seats[i], seatArcs[i], runSign, speed, runLimit, true,
                        true);
            }
        }
    }

    // The departure train's cars are still running off, or null, dropped once its leading car has
    // changed or while no restart under that car has left those cars' track behind.
    private static Train.@Nullable Departure departureOf(
            Train train, AbstractMinecart leader, TrackRibbon ribbon) {
        Train.Departure departure = train.departure();

        if (departure != null && (departure.leader() != leader
                || departure.ribbon().arcEpoch() == ribbon.arcEpoch())) {
            train.setDeparture(null);
            return null;
        }

        return departure;
    }

    // Which cars behind the leading car are still seated on the track their train left by. The
    // departure outlives them while a flown car rests on its lip, read as flying only while kept.
    private static boolean[] departing(Train train, List<AbstractMinecart> cars, int leadIndex,
            int fromHead, boolean[] flying) {
        boolean[] departing = new boolean[cars.size()];
        Train.Departure departure = train.departure();

        if (departure == null) {
            return departing;
        }

        boolean any = false;

        for (int i = leadIndex + fromHead; inTrain(cars, i); i += fromHead) {
            AbstractMinecart car = cars.get(i);

            departing[i] = !flying[i] && car.isOnRails()
                    && seatArcOn(departure.ribbon(), car) != null;
            any |= departing[i] || flying[i] && atLip(departure, car);
        }

        if (!any) {
            train.setDeparture(null);
        }

        return departing;
    }

    // Runs the departing cars on off the end of the track their train left by, as followOff did
    // before the leading car landed; drops the departure, moving none, where that track fails.
    private static void runDeparture(ServerLevel level, Train train, List<AbstractMinecart> cars,
            boolean[] departing, Train.Departure departure, int leadIndex, int fromHead,
            double speed, double runLimit) {
        int firstIndex = leadIndex + fromHead;

        while (inTrain(cars, firstIndex) && !departing[firstIndex]) {
            firstIndex += fromHead;
        }

        if (!inTrain(cars, firstIndex)) {
            return;
        }

        TrackRibbon ribbon = departure.ribbon();
        double runSign = departure.farEnd() ? 1.0 : -1.0;
        Run run = planRun(ribbon, cars, firstIndex, fromHead, runSign,
                departure.farEnd() ? ribbon.farArc() : ribbon.nearArc(), speed);

        // A cart or a mined rail on the ramp: next tick seats those cars as before.
        if (run == null || fouled(ribbon, new Nearby(train, null), cars, run.seats(),
                run.seatArcs(), leadIndex) != null) {
            train.setDeparture(null);
            return;
        }

        executeRun(level, ribbon, cars, run, runSign, speed, runLimit);
    }

    // Where car is seated along ribbon's arc numbering, or null if it is not seated on it.
    private static @Nullable Double seatArcOn(TrackRibbon ribbon, AbstractMinecart car) {
        return seated(car) ? car.polytrains$seatArc(ribbon.arcEpoch()) : null;
    }

    // Whether index names one of cars.
    private static boolean inTrain(List<AbstractMinecart> cars, int index) {
        return index >= 0 && index < cars.size();
    }

    // Has vanilla move car off the end now, a whole tick's run past it as it did the leading car;
    // seated to the tick's end, it moves once whichever of the two ticks first, and is flown after.
    private static void leaveTrack(ServerLevel level, AbstractMinecart car) {
        loosen(car);
        fly(level, car);

        if (car instanceof AbstractMinecartExt ext) {
            ext.polytrains$setSeatedAt(level.getGameTime() - 1);
        }
    }

    // Has vanilla move car on now, marked first so the move holds it behind the car ahead and its
    // own tick, before or after this, does not move it again.
    private static void fly(ServerLevel level, AbstractMinecart car) {
        if (car instanceof AbstractMinecartExt ext) {
            ext.polytrains$setFlownAt(level.getGameTime());
        }

        if (car.getBehavior() instanceof NewMinecartBehavior behavior) {
            behavior.moveAlongTrack(level);
        }
    }

    // Which cars behind the leading car the placer flew off a jump and neither rails nor ground
    // has caught yet; ground still on the departure's lip is not yet down.
    private static boolean[] stillFlying(List<AbstractMinecart> cars, int leadIndex, int fromHead,
            Train.@Nullable Departure departure) {
        boolean[] flying = new boolean[cars.size()];

        for (int i = leadIndex + fromHead; i >= 0 && i < cars.size(); i += fromHead) {
            AbstractMinecart car = cars.get(i);

            flying[i] = flown(car) && !car.isOnRails()
                    && (!car.onGround() || atLip(departure, car));
        }

        return flying;
    }

    // Whether car rests on the support just past the end its train left by, which vanilla reads
    // as ground: a car leaving slowly touches it as its leading car did.
    private static boolean atLip(Train.@Nullable Departure departure, AbstractMinecart car) {
        return departure != null && departure.ribbon().atLip(car, departure.farEnd());
    }

    // Flies on, front first, the cars still in the air behind a train come back onto its track,
    // each after the car ahead has moved. The train runs on along the rails meanwhile, so a car
    // trailing the one ahead by more than the slack is pulled on by its chain.
    private static void flyOn(List<AbstractMinecart> cars, boolean[] flying, int leadIndex,
            int fromHead) {
        if (!(cars.get(leadIndex).level() instanceof ServerLevel level)) {
            return;
        }

        for (int i = leadIndex + fromHead; i >= 0 && i < cars.size(); i += fromHead) {
            if (!flying[i]) {
                continue;
            }

            AbstractMinecart car = cars.get(i);
            Vec3 toAhead = cars.get(i - fromHead).position()
                    .subtract(car.position().add(car.getDeltaMovement()));

            if (toAhead.length() > AIR_SLACK) {
                car.setDeltaMovement(car.getDeltaMovement()
                        .add(toAhead.normalize().scale(toAhead.length() - AIR_SLACK)));
            }

            fly(level, car);
        }
    }

    // Where each car goes with the head at a given arc, and the first reason one has no seat.
    private record Seating(Seat[] seats, double[] seatArcs, @Nullable Refusal refusal) {}

    // Seats every car at its offset from headArc, the leading car only when drawn back and none
    // still flying or running off a ramp, leaving null the seats the ribbon cannot reach or has no
    // rail at.
    private static Seating seatCars(TrackRibbon ribbon, List<AbstractMinecart> cars,
            double headArc, double[] offsets, int order, int leadIndex, boolean drawnBack,
            double leadArc, double travelSign, boolean[] placedElsewhere) {
        Seat[] seats = new Seat[cars.size()];
        double[] seatArcs = new double[cars.size()];
        Refusal refusal = null;

        // The leading car is skipped unless drawn back: normally vanilla places it, but a train
        // backed against a wall needs it seated too, so it stops exactly at the wall.
        for (int i = 0; i < cars.size(); i++) {
            if (i == leadIndex && !drawnBack || placedElsewhere[i]) {
                continue;
            }

            double arc = headArc - order * offsets[i];

            seatArcs[i] = arc;

            if (!ribbon.reaches(arc)) {
                // A car the train is moving away from is being left behind for good; one it is
                // moving toward may yet be recorded, so waiting on it costs nothing.
                boolean leaving = travelSign != 0.0
                        && Math.signum(arc - leadArc) != travelSign;

                refusal = refusal != null ? refusal
                        : new Refusal(Bail.RIBBON_SHORT, leaving ? i : Refusal.NONE, i, arc, null);
                continue;
            }

            seats[i] = seatAt(ribbon, cars.get(i), arc);

            if (seats[i] == null) {
                refusal = refusal != null ? refusal
                        : new Refusal(Bail.CAR_OFF_RIBBON, i, i, arc, null);
            }
        }

        return new Seating(seats, seatArcs, refusal);
    }

    // A seated car's run to its seat along the ribbon: the arc it stands at, the way it goes, and
    // how far.
    private record SeatRun(double startArc, double sign, double length) {}

    // The run car makes to a seat at seatArc; null for a car not seated last tick, standing off
    // the ribbon, or further from its seat than a tick can carry it.
    private static @Nullable SeatRun runOf(
            TrackRibbon ribbon, AbstractMinecart car, double seatArc, double runLimit) {
        Double startArc = seated(car) ? ribbon.arcNear(car, seatArc) : null;

        if (startArc == null || Math.abs(seatArc - startArc) > runLimit) {
            return null;
        }

        return new SeatRun(startArc, Math.signum(seatArc - startArc), Math.abs(seatArc - startArc));
    }

    // How far the cars' runs to their seats reach past where each first meets another train's
    // cart, at most; how far back against their run the train can be drawn without taking any
    // car behind where it stood; and the least any cart met is moving on out of those runs' way.
    private record Contact(double depth, double backRoom, double recede) {}

    // The contact the train makes running runSign to these seats, its leading car having run
    // leadRun of the way; a run against runSign is give taken up, not ground to stop short on.
    private static Contact contactOf(Train train, Nearby nearby, List<AbstractMinecart> cars,
            Seating seating, int leadIndex, double runSign, double leadRun) {
        TrackRibbon ribbon = train.ribbon();
        double runLimit = furthestRun(cars.get(leadIndex));
        double depth = 0.0;
        double backRoom = Math.max(0.0, leadRun);
        double recede = Double.POSITIVE_INFINITY;

        for (int i = 0; i < cars.size(); i++) {
            Seat seat = seating.seats()[i];
            AbstractMinecart car = cars.get(i);
            SeatRun run = i == leadIndex || seat == null
                    ? null : runOf(ribbon, car, seating.seatArcs()[i], runLimit);

            if (run == null) {
                continue;
            }

            double forwardRun = run.sign() * runSign * run.length();

            backRoom = Math.min(backRoom, Math.max(0.0, forwardRun));

            if (forwardRun <= EPSILON) {
                continue;
            }

            AABB reachBox = car.getBoundingBox().minmax(boxAt(car, seat.position()));
            Vec3 runDirection = seat.position().subtract(car.position()).horizontal();

            for (AbstractMinecart other : nearby.in(reachBox.inflate(RUN_SHELL))) {
                double past = forwardRun - clearRun(ribbon, car, run, other);

                if (past > EPSILON) {
                    depth = Math.max(depth, past);
                    recede = Math.min(recede, runDirection.lengthSqr() < EPSILON * EPSILON ? 0.0
                            : other.getDeltaMovement().horizontal().dot(runDirection.normalize()));
                }
            }
        }

        return new Contact(depth, backRoom, Math.max(0.0, recede));
    }

    // How far, up to room, the train is drawn back against runSign for each car let go for a
    // fouled seat to have that seat no further along than where the car stands.
    private static double tetherBack(TrackRibbon ribbon, List<AbstractMinecart> cars,
            Seat[] offered, Seat[] seats, double[] seatArcs, int leadIndex, double runSign,
            double room) {
        double back = 0.0;

        for (int i = 0; i < cars.size(); i++) {
            if (i == leadIndex || offered[i] == null || seats[i] != null) {
                continue;
            }

            Double standArc = ribbon.arcNear(cars.get(i), seatArcs[i]);

            if (standArc != null) {
                back = Math.max(back, (seatArcs[i] - standArc) * runSign);
            }
        }

        return Math.min(back, Math.max(0.0, room));
    }

    // How far along run car goes before its box meets other's on ground it did not already cover:
    // all of it if it never does, none if other already lies inside it on the side it runs to.
    private static double clearRun(
            TrackRibbon ribbon, AbstractMinecart car, SeatRun run, AbstractMinecart other) {
        AABB otherBox = other.getBoundingBox();

        if (car.getBoundingBox().intersects(otherBox)) {
            Vec3 endPosition = ribbon.positionAt(run.startArc() + run.sign() * run.length());
            Vec3 runDirection = endPosition == null
                    ? Vec3.ZERO : endPosition.subtract(car.position()).horizontal();

            return other.position().subtract(car.position()).horizontal().dot(runDirection) > 0.0
                    ? 0.0 : run.length();
        }

        double clear = 0.0;

        while (clear < run.length()) {
            double along = Math.min(clear + PATH_STEP, run.length());

            if (touches(ribbon, car, run, along, otherBox)) {
                // Halved down between the last step that was clear and this one, which is not.
                double touching = along;

                for (int halving = 0; halving < CLEAR_HALVINGS; halving++) {
                    double middle = (clear + touching) * 0.5;

                    if (touches(ribbon, car, run, middle, otherBox)) {
                        touching = middle;
                    } else {
                        clear = middle;
                    }
                }

                return clear;
            }

            clear = along;
        }

        return run.length();
    }

    // Whether car's box, along run by along, meets otherBox; not where the ribbon has no track.
    private static boolean touches(TrackRibbon ribbon, AbstractMinecart car, SeatRun run,
            double along, AABB otherBox) {
        Vec3 position = ribbon.positionAt(run.startArc() + run.sign() * along);

        return position != null && boxAt(car, position).intersects(otherBox);
    }

    // The first foreign cart lying across one of these seats, or across the ground reaching it.
    private record Foul(int carIndex, AbstractMinecart foulingCart) {}

    // Clears the seats another train's cart stands in or lies across the track to, naming the
    // first. The leading car's seat, taken only to draw a train back, is cleared uncounted.
    private static @Nullable Foul fouled(TrackRibbon ribbon, Nearby nearby,
            List<AbstractMinecart> cars, Seat[] seats, double[] seatArcs, int leadIndex) {
        AABB span = null;

        for (int i = 0; i < seats.length; i++) {
            if (seats[i] != null) {
                AABB sweptBox = swept(cars.get(i), seats[i]);

                span = span == null ? sweptBox : span.minmax(sweptBox);
            }
        }

        if (span == null) {
            return null;
        }

        List<AbstractMinecart> others = nearby.in(span.inflate(RUN_SHELL));

        if (others.isEmpty()) {
            return null;
        }

        double runLimit = furthestRun(cars.get(leadIndex));
        Foul first = null;

        for (int i = 0; i < seats.length; i++) {
            if (seats[i] == null) {
                continue;
            }

            AbstractMinecart car = cars.get(i);
            AABB seatBox = boxAt(car, seats[i].position());
            AABB sweptBox = swept(car, seats[i]);
            List<AABB> pathBoxes = null;
            boolean pathRead = false;

            for (AbstractMinecart other : others) {
                // Nothing is seated into a cart, but a car wedged in one is let back out of it:
                // held fouled, its train is tethered to it and can never leave.
                if (backsOut(car, seats[i], other)) {
                    continue;
                }

                AABB otherBox = other.getBoundingBox();
                boolean fouls = seatBox.intersects(otherBox);

                // A seated car ran here, so the way it came is checked too: along its track where
                // that can be read, and across both boxes where it cannot.
                if (!fouls && seated(car) && sweptBox.inflate(RUN_SHELL).intersects(otherBox)) {
                    if (!pathRead) {
                        pathBoxes = pathBoxes(ribbon, car, seatArcs[i], runLimit);
                        pathRead = true;
                    }

                    fouls = pathBoxes == null ? sweptBox.intersects(otherBox)
                            : runsThrough(car, seats[i], pathBoxes, other);
                }

                if (fouls) {
                    first = first != null || i == leadIndex ? first : new Foul(i, other);

                    seats[i] = null;
                    break;
                }
            }
        }

        return first;
    }

    // The car's box at each step along its track between where it stands and its seat, both
    // ends left out; null when the two are further apart than a tick can carry it.
    private static @Nullable List<AABB> pathBoxes(
            TrackRibbon ribbon, AbstractMinecart car, double seatArc, double runLimit) {
        Double startArc = ribbon.arcNear(car, seatArc);

        if (startArc == null || Math.abs(seatArc - startArc) > runLimit) {
            return null;
        }

        int steps = (int) Math.ceil(Math.abs(seatArc - startArc) / PATH_STEP);
        List<AABB> boxes = new ArrayList<>(Math.max(0, steps - 1));

        for (int step = 1; step < steps; step++) {
            Vec3 position = ribbon.positionAt(startArc + (seatArc - startArc) * step / steps);

            if (position != null) {
                boxes.add(boxAt(car, position));
            }
        }

        return boxes;
    }

    // Whether car, its box already inside other's, runs to seat without closing on other's middle:
    // a car standing still in it has not moved out, so it still fouls.
    private static boolean backsOut(AbstractMinecart car, Seat seat, AbstractMinecart other) {
        Vec3 runDirection = seat.position().subtract(car.position()).horizontal();

        return runDirection.lengthSqr() > EPSILON * EPSILON
                && car.getBoundingBox().intersects(other.getBoundingBox())
                && other.position().subtract(car.position()).horizontal().dot(runDirection) <= 0.0;
    }

    // Whether car, running to its seat, meets other on ground its box did not already cover, or
    // runs on past the middle of one it was already up against.
    private static boolean runsThrough(
            AbstractMinecart car, Seat seat, List<AABB> pathBoxes, AbstractMinecart other) {
        AABB otherBox = other.getBoundingBox();

        if (car.getBoundingBox().intersects(otherBox)) {
            Vec3 runDirection = seat.position().subtract(car.position()).horizontal();

            return other.position().subtract(car.position()).horizontal().dot(runDirection) > 0.0
                    && other.position().subtract(seat.position()).horizontal()
                            .dot(runDirection) < 0.0;
        }

        for (AABB pathBox : pathBoxes) {
            if (pathBox.intersects(otherBox)) {
                return true;
            }
        }

        return false;
    }

    // The furthest a car of leader's train can run along its track in one tick, a node to spare.
    private static double furthestRun(AbstractMinecart leader) {
        return (leader.level() instanceof ServerLevel level
                ? leader.polytrains$ownMaxSpeed(level) : 0.0) + SWEEP_SLACK;
    }

    // Every cart in box but this train's own, a cart coupled to one of its cars counting as one
    // of its own even where a chunk border splits the walk and train.contains says otherwise.
    private static List<AbstractMinecart> foreignCarts(Train train, AABB box) {
        List<AbstractMinecart> cars = train.cars();

        return cars.getFirst().level().getEntitiesOfClass(AbstractMinecart.class, box,
                car -> !train.contains(car) && !coupledToAny(cars, car));
    }

    // The box every one of cars stands in.
    private static AABB trainBox(List<AbstractMinecart> cars) {
        AABB box = cars.getFirst().getBoundingBox();

        for (AbstractMinecart car : cars) {
            box = box.minmax(car.getBoundingBox());
        }

        return box;
    }

    // Whether inner lies wholly inside outer, faces included.
    private static boolean encloses(AABB outer, AABB inner) {
        return outer.minX <= inner.minX && outer.minY <= inner.minY && outer.minZ <= inner.minZ
                && outer.maxX >= inner.maxX && outer.maxY >= inner.maxY
                && outer.maxZ >= inner.maxZ;
    }

    // One train's foreignCarts over a box grown to cover every area asked of it, each answer cut
    // to its own area. Exact while no foreign cart moves, which none does before the place loop.
    private static final class Nearby {
        private final Train train;
        private final @Nullable AABB seed;
        private @Nullable AABB queried;
        private List<AbstractMinecart> carts = List.of();

        private Nearby(Train train, @Nullable AABB seed) {
            this.train = train;
            this.seed = seed;
        }

        // The carts foreignCarts would give for area.
        private List<AbstractMinecart> in(AABB area) {
            if (queried == null || !encloses(queried, area)) {
                AABB grown = queried != null ? queried.minmax(area)
                        : seed != null ? seed.minmax(area) : area;

                carts = foreignCarts(train, grown);
                queried = grown;
            }

            if (carts.isEmpty()) {
                return List.of();
            }

            List<AbstractMinecart> inArea = new ArrayList<>();

            for (AbstractMinecart cart : carts) {
                if (cart.getBoundingBox().intersects(area)) {
                    inArea.add(cart);
                }
            }

            return inArea;
        }
    }

    // The end another train's cart makes of this track past endCar, outwardSign away from the
    // train: how far endCar's middle may come, pushed out by halfLength as a recorded end is.
    private static @Nullable Double foreignEnd(Nearby nearby, TrackRibbon ribbon,
            AbstractMinecart endCar, double reachArc, double outwardSign, double halfLength) {
        Vec3 reachPosition = ribbon.positionAt(reachArc);
        AABB aroundBox = endCar.getBoundingBox();

        if (reachPosition != null) {
            aroundBox = aroundBox.minmax(boxAt(endCar, reachPosition));
        }

        List<AbstractMinecart> others = nearby.in(aroundBox.inflate(END_SHELL));

        if (others.isEmpty()) {
            return null;
        }

        Double standing = ribbon.arcNear(endCar, reachArc);
        double standingArc = standing == null ? reachArc : standing;
        Double nearest = null;

        // Not asked whether the two collide: that answer comes off rail memos a tick old, which
        // at a junction lets two carts standing in each other pass, and the placer passes nothing.
        for (AbstractMinecart other : others) {
            Double otherArc = ribbon.arcNear(other, standingArc);

            // One level with or behind endCar has no seat short of it, so it is no end.
            if (otherArc == null || (otherArc - standingArc) * outwardSign <= 0.0) {
                continue;
            }

            Double clearArc = firstClearArc(ribbon, endCar, other.getBoundingBox(), otherArc,
                    -outwardSign);

            if (clearArc != null) {
                nearest = nearerEnd(nearest, clearArc + outwardSign * halfLength, outwardSign);
            }
        }

        return nearest;
    }

    // The arc nearest fromArc, stepping towards stepSign, where car's box clears otherBox; null if
    // it never touched there, or the track or CLEAR_REACH runs out first.
    private static @Nullable Double firstClearArc(TrackRibbon ribbon, AbstractMinecart car,
            AABB otherBox, double fromArc, double stepSign) {
        double touchingArc = fromArc;

        for (int step = 0; step * CLEAR_STEP <= CLEAR_REACH; step++) {
            double arc = fromArc + stepSign * step * CLEAR_STEP;
            Vec3 position = ribbon.positionAt(arc);

            if (position == null) {
                return null;
            }

            if (boxAt(car, position).intersects(otherBox)) {
                touchingArc = arc;
                continue;
            }

            // A cart the track never brings a car into is beside it, not in the way.
            if (step == 0) {
                return null;
            }

            // Halved down between the last step that touched and this one, which is clear.
            for (int halving = 0; halving < CLEAR_HALVINGS; halving++) {
                double middleArc = (touchingArc + arc) * 0.5;
                Vec3 middle = ribbon.positionAt(middleArc);

                if (middle == null || boxAt(car, middle).intersects(otherBox)) {
                    touchingArc = middleArc;
                } else {
                    arc = middleArc;
                }
            }

            return arc;
        }

        return null;
    }

    // The recorded end outwardSign away from fromArc, if nearer than reach and the rail stops
    // there against something solid; null otherwise. Near first, so no far block is ever read.
    // A joint the track has since been cut at counts as that end, wherever the ribbon goes on to.
    private static @Nullable Double recordedEnd(TrackRibbon ribbon, Level level, double fromArc,
            double reach, double outwardSign) {
        Double barredArc = ribbon.barredJoint(
                level, fromArc, Math.min(reach, BARRED_REACH), outwardSign);

        if (barredArc != null) {
            return barredArc;
        }

        boolean farEnd = outwardSign > 0.0;
        double endArc = farEnd ? ribbon.farArc() : ribbon.nearArc();

        return (endArc - fromArc) * outwardSign < reach && ribbon.blockedBeyond(level, farEnd)
                ? endArc : null;
    }

    // Whether a cart of another train stands where car would be seated at arc.
    private static boolean seatTaken(
            Nearby nearby, TrackRibbon ribbon, AbstractMinecart car, double arc) {
        Vec3 position = ribbon.positionAt(arc);

        return position != null && !nearby.in(boxAt(car, position)).isEmpty();
    }

    // Of two ends on the side outwardSign points to, whichever the train meets first.
    private static @Nullable Double nearerEnd(
            @Nullable Double a, @Nullable Double b, double outwardSign) {
        if (a == null || b == null) {
            return a == null ? b : a;
        }

        return a * outwardSign <= b * outwardSign ? a : b;
    }

    // Car's box as it would stand with its middle at position.
    private static AABB boxAt(AbstractMinecart car, Vec3 position) {
        return car.getBoundingBox().move(position.subtract(car.position()));
    }

    // Whether any of these cars holds a link to other, read off the slots rather than the walk.
    // Only the two ends: the walk took in both links of every car between them.
    private static boolean coupledToAny(List<AbstractMinecart> cars, AbstractMinecart other) {
        return Linkable.coupled(cars.getFirst(), other) || Linkable.coupled(cars.getLast(), other);
    }

    // Where a car may meet another train's cart reaching its seat: its current box joined to the
    // box it will stand in, which bounds the track between for the fouling check to look along.
    private static AABB swept(AbstractMinecart car, Seat seat) {
        AABB seatBox = boxAt(car, seat.position());

        return seated(car) ? car.getBoundingBox().minmax(seatBox) : seatBox;
    }

    // How far back from the blocked end something is actually pushing, in cars — 0 if nothing is.
    private static int pressedGaps(Train train, TrackRibbon ribbon, double headArc,
            double[] offsets, int order, int leadIndex, double pushSign) {
        List<AbstractMinecart> cars = train.cars();
        int packReach = 0;
        // Every player near the train, read once and only for a car its engine is not driving.
        List<Player> players = null;

        for (int i = 0; i < cars.size(); i++) {
            AbstractMinecart car = cars.get(i);
            Vec3 tangent = ribbon.tangentAt(headArc - order * offsets[i]);
            Vec3 alongDirection = tangent == null ? Vec3.ZERO : tangent.horizontal();

            if (alongDirection.lengthSqr() < EPSILON * EPSILON) {
                continue;
            }

            Vec3 pushDirection = alongDirection.normalize().scale(pushSign);

            if (drives(car, pushDirection)) {
                packReach = Math.max(packReach, Math.abs(i - leadIndex));
                continue;
            }

            if (players == null) {
                players = car.level().getEntitiesOfClass(
                        Player.class, trainBox(cars).inflate(SHOVE_SHELL));
            }

            if (shoved(car, pushDirection, players)) {
                packReach = Math.max(packReach, Math.abs(i - leadIndex));
            }
        }

        return packReach;
    }

    // Whether this car's own engine is driving it toward the blocked end.
    private static boolean drives(AbstractMinecart car, Vec3 pushDirection) {
        return car.polytrains$driving()
                && car.polytrains$driveHeading().horizontal().dot(pushDirection) > 0.0;
    }

    // Whether a player is leaning on this car from the side away from the blocked end.
    private static boolean shoved(AbstractMinecart car, Vec3 pushDirection, List<Player> players) {
        AABB leanBox = car.getBoundingBox().inflate(SHOVE_SHELL);

        for (Player player : players) {
            if (!player.getBoundingBox().intersects(leanBox)) {
                continue;
            }

            Vec3 behindDirection = car.position().subtract(player.position()).horizontal();

            if (!player.isSpectator()
                    && behindDirection.lengthSqr() > EPSILON * EPSILON
                    && behindDirection.normalize().dot(pushDirection) > SHOVE_ARC) {
                return true;
            }
        }

        return false;
    }

    // The offset table and the head/tail arcs it implies, bundled so both recomputations agree.
    private record Arcs(double[] offsets, double headArc, double tailArc) {}

    // Derives the head and tail arcs from a fresh offset table.
    private static Arcs arcsFor(TrackRibbon ribbon, double[] gaps, double leadArc, int order,
            int fromHead, int leadIndex, int tailIndex) {
        double[] offsets = offsets(ribbon, gaps, leadArc, order, fromHead);
        double headArc = leadArc + order * offsets[leadIndex];
        double tailArc = headArc - order * offsets[tailIndex];

        return new Arcs(offsets, headArc, tailArc);
    }

    // Each car's arc distance from the head, walked outward along the rails one gap at a time.
    private static double[] offsets(TrackRibbon ribbon, double[] gaps, double leadArc,
            int order, int fromHead) {
        double[] spans = new double[gaps.length];
        double cursorArc = leadArc;
        double step = order * fromHead;

        // Walked from the head outward, whichever end of the list that is.
        for (int away = 0; away < gaps.length; away++) {
            int i = fromHead > 0 ? away : gaps.length - 1 - away;

            spans[i] = ribbon.arcAlong(cursorArc, gaps[i], -step);
            cursorArc -= step * spans[i];
        }

        double[] out = new double[gaps.length + 1];

        for (int i = 0; i < gaps.length; i++) {
            out[i + 1] = out[i] + spans[i];
        }

        return out;
    }

    // Hands one car back to vanilla without disturbing the rest of its train.
    private static void loosen(AbstractMinecart car) {
        if (car instanceof AbstractMinecartExt ext) {
            ext.polytrains$setSeatedAt(0L);
            ext.polytrains$setFlownAt(0L);
        }
    }

    // Slows car to speed along the way it is already going, if it is going any faster.
    private static void slowTo(AbstractMinecart car, double speed) {
        Vec3 movement = car.getDeltaMovement();
        double carSpeed = movement.horizontalDistance();

        if (carSpeed > speed + EPSILON) {
            double scale = speed / carSpeed;

            car.setDeltaMovement(movement.x * scale, movement.y, movement.z * scale);
        }
    }

    // Hands the whole train back to vanilla; the next seating starts with no prior arc to turn.
    private static void release(Train train) {
        train.setHeadArc(null);
        train.setTurn(0.0);
        train.setDeparture(null);

        for (AbstractMinecart car : train.cars()) {
            loosen(car);
        }
    }

    // Which way the train lies, read from how far the next car sits from the leader — null if the
    // two are too close together to tell.
    private static @Nullable Integer lieOf(
            @Nullable Double nextArc, double leadArc, int fromHead, double gap) {
        if (nextArc == null) {
            return null;
        }

        double away = (leadArc - nextArc) * fromHead;

        return Math.abs(away) < gap * 0.25 ? null : (int) Math.signum(away);
    }

    // Falls back to the leader's own travel direction when the cars are too close to read the lie.
    private static @Nullable Integer resolveOrder(@Nullable Double nextArc, double leadArc,
            int fromHead, double gap, double travelSign) {
        Integer lieSign = lieOf(nextArc, leadArc, fromHead, gap);

        return lieSign != null ? lieSign
                : travelSign != 0.0 ? (int) travelSign * fromHead : null;
    }

    // Which way the leader is moving along the rail, or zero if too slow to say.
    private static double travelSign(AbstractMinecart leader, @Nullable Vec3 tangent) {
        if (tangent == null) {
            return 0.0;
        }

        double travelComponent = leader.getDeltaMovement().horizontal().dot(tangent);

        return Math.abs(travelComponent) < EPSILON ? 0.0 : Math.signum(travelComponent);
    }

    // Which way along tangent the leading car is about to go, as TrainCoupling.hauled lays the
    // train's drive on it: its movement, or, all but standing or just turned, its engines' aim.
    private static double leaveSign(AbstractMinecart leader, @Nullable Vec3 tangent) {
        if (tangent == null) {
            return 0.0;
        }

        double leaveComponent =
                TrainCoupling.leavingHeading(leader, leader.getDeltaMovement()).dot(tangent);

        return Math.abs(leaveComponent) < EPSILON ? 0.0 : Math.signum(leaveComponent);
    }

    // Where one car would sit on recorded track, or null if that track is gone.
    private static @Nullable Seat seatAt(TrackRibbon ribbon, AbstractMinecart car, double arc) {
        TrackRibbon.Spot spot = ribbon.spotAt(arc);

        if (spot == null || spot.tangent() == null
                || !BaseRailBlock.isRail(car.level().getBlockState(spot.block()))) {
            return null;
        }

        return new Seat(spot.position(), spot.block(), spot.tangent());
    }

    // Puts one car on its seat along ribbon and does by hand everything vanilla would have done
    // moving it there, answering how far that moved it.
    private static double place(TrackRibbon ribbon, AbstractMinecart car, Seat seat, double arc,
            double faceSign, double speed, double runLimit, boolean shouldAim,
            boolean shoveAtRest) {
        Vec3 previousPosition = car.position();
        Vec3 forward = seat.tangent().scale(faceSign);

        // Read before sweep records the new arc over it.
        Double lastArc = shouldAim && seated(car)
                ? car.polytrains$seatArc(ribbon.arcEpoch()) : null;
        Vec3 lastTangent = lastArc == null ? null : ribbon.tangentAt(lastArc);

        // Fires activator rails and the like before the car's position changes underneath them.
        sweep(ribbon, car, arc, runLimit);

        car.setOldPosAndRot();
        car.setOnRails(true);
        car.resetFallDistance();

        car.setDeltaMovement(forward.scale(speed));
        car.setPos(seat.position().x, seat.position().y, seat.position().z);

        // A seated car covers no ground of its own, so nothing else re-aims its engine along the
        // train's actual heading.
        Vec3 headingDirection = forward.horizontal();

        if (speed > EPSILON && headingDirection.lengthSqr() > EPSILON * EPSILON) {
            car.polytrains$aimEngine(headingDirection.normalize());
        }

        Vec3 displacement = car.position().subtract(previousPosition);

        if (!(car.getBehavior() instanceof NewMinecartBehavior behavior)) {
            return displacement.length();
        }

        if (shouldAim) {
            aim(car, seat.tangent(), lastTangent, faceSign, displacement);
        }

        // Otherwise the client is left replaying this tick's move on the next one.
        if (displacement.length() > EPSILON) {
            behavior.lerpSteps.add(new NewMinecartBehavior.MinecartStep(car.position(),
                    car.getDeltaMovement(), car.getYRot(), car.getXRot(),
                    (float) displacement.length()));
        }

        // Vanilla hangs both off Entity.move, which a placed car never reaches; a cart at rest
        // never moves, so it pushes and picks up nothing.
        if (shoveAtRest || displacement.length() > EPSILON || speed > EPSILON) {
            behavior.pushAndPickupEntities();
        }

        car.applyEffectsFromBlocks(previousPosition, car.position());

        return displacement.length();
    }

    // Fires every activator rail a car crosses reaching its seat, then records where it ended up.
    private static void sweep(TrackRibbon ribbon, AbstractMinecart car, double arc,
            double runLimit) {
        if (!(car.level() instanceof ServerLevel level)
                || !(car instanceof AbstractMinecartExt ext)) {
            return;
        }

        // Where the car still stands, nothing having moved it yet this tick. Read off the ribbon
        // rather than remembered from the last sweep: a remembered arc is a figure from an older
        // reckoning, and a change of leading end re-bases the arcs without moving a car an inch.
        Double was = ribbon.arcOf(car, arc);

        // Further apart than a tick can carry a car and the track between is not crossed ground
        // but merely unaccounted for, so only the rail the car lands on is owed anything.
        double from = was == null || Math.abs(was - arc) > runLimit ? arc : was;

        ribbon.blocksBetween(from, arc, block -> {
            BlockState crossed = level.getBlockState(block);

            if (crossed.is(Blocks.ACTIVATOR_RAIL)) {
                car.activateMinecart(level, block.getX(), block.getY(), block.getZ(),
                        crossed.getValue(PoweredRailBlock.POWERED));
            }
        });

        ext.polytrains$setSeatArc(arc, ribbon.arcEpoch());
        ext.polytrains$setSeatedAt(level.getGameTime());
    }

    // Points a car along the rail the way it already faces, and pitches it to the grade it just
    // climbed rather than to a fixed angle read off the rail shape.
    private static void aim(AbstractMinecart car, Vec3 tangent, @Nullable Vec3 lastTangent,
            double faceSign, Vec3 displacement) {
        Vec3 alongDirection = tangent.horizontal();

        if (alongDirection.lengthSqr() < EPSILON * EPSILON) {
            return;
        }

        alongDirection = alongDirection.normalize();

        int noseSign = noseOf(car, alongDirection, lastTangent);

        boolean flipped = noseSign * faceSign < 0.0;

        car.setFlipped(flipped);
        car.setYRot(MinecartHelper.yawOf(alongDirection.scale(noseSign)));

        // A car placed where it already stood keeps whatever pitch it has.
        if (displacement.horizontalDistanceSqr() < EPSILON * EPSILON) {
            return;
        }

        float pitch = (float) (90.0
                - Math.atan2(displacement.horizontalDistance(), displacement.y) * 180.0 / Math.PI);

        car.setXRot(Math.clamp(flipped ? -pitch : pitch, -45.0F, 45.0F) % 360.0F);
    }

    /**
     * Keeps {@code car} facing the end it already leads with, however it got there.
     *
     * <p>A cart may turn honestly through a corner, but may not flip end for end — which vanilla
     * otherwise allows on any step not taken along the rails, a collision being one such step.
     * Runs before every rail step vanilla takes and again at tick end, so it never compares across
     * more than one step's turn.
     */
    public static void holdNose(AbstractMinecart car) {
        if (!(car instanceof AbstractMinecartExt ext)) {
            return;
        }

        Vec3 noseVector = ext.polytrains$noseVector();

        if (noseVector == null) {
            return;
        }

        Vec3 headingDirection = MinecartHelper.headingOf(car.getYRot());

        if (headingDirection.dot(noseVector) >= -NOSE_TURN_SLACK) {
            ext.polytrains$setNoseVector(headingDirection);
            return;
        }

        car.setFlipped(car.getDeltaMovement().horizontal().dot(noseVector) < 0.0);
        car.setYRot(MinecartHelper.yawOf(noseVector));
    }

    // Which way along the track a car faces, read from its recorded nose rather than its current
    // yaw — vanilla re-aims a driven car's yaw off a step that is not always along the rails. Read
    // against the tangent at the car's last seat when there is one, since a tick can turn it 180°.
    private static int noseOf(
            AbstractMinecart car, Vec3 alongDirection, @Nullable Vec3 lastTangent) {
        if (!(car instanceof AbstractMinecartExt ext)) {
            return 1;
        }

        Vec3 heldNoseVector = ext.polytrains$noseVector();
        Vec3 referenceNoseVector = heldNoseVector == null
                ? MinecartHelper.headingOf(car.getYRot()) : heldNoseVector;

        // Every ribbon tangent points up the arc, so the side of the last seat's tangent the nose
        // was on is its side here, however far the track turned in between.
        Vec3 lastAlong = lastTangent == null ? Vec3.ZERO : lastTangent.horizontal();
        double carried = lastAlong.lengthSqr() < EPSILON * EPSILON
                ? 0.0 : referenceNoseVector.dot(lastAlong.normalize());
        double side = Math.abs(carried) > EPSILON
                ? carried : referenceNoseVector.dot(alongDirection);
        int noseSign = side < 0.0 ? -1 : 1;

        ext.polytrains$setNoseVector(alongDirection.scale(noseSign));

        return noseSign;
    }

    // A refusal's log line, built only when it is logged: nothing it reads has moved since.
    private static String describe(Train train, Refusal refusal) {
        TrackRibbon ribbon = train.ribbon();
        int last = train.size() - 1;

        return switch (refusal.why()) {
            case NO_LEADING_END -> "cars=" + train.size();
            case LEADER_IN_AIR -> "leader at " + refusal.at().getCurrentBlockPosOrRailBelow()
                    + ", left the ribbon at " + round(refusal.arc()) + " of " + span(ribbon);
            case LEADER_OFF_RIBBON -> "leader on " + refusal.at().getCurrentBlockPosOrRailBelow()
                    + ", ribbon of " + ribbon.size() + " nodes running " + span(ribbon);
            case ORDER_UNREADABLE -> "leader at " + round(refusal.arc()) + " of " + span(ribbon)
                    + ", next car not far enough along it to say";
            case SEAT_FOULED -> "a cart of another train lies at "
                    + refusal.at().getCurrentBlockPosOrRailBelow() + ", across the seat of car "
                    + refusal.named() + "/" + last;
            case RIBBON_SHORT -> "car " + refusal.named() + "/" + last + " wants arc "
                    + round(refusal.arc()) + " of " + span(ribbon);
            case CAR_OFF_RIBBON -> "car " + refusal.named() + "/" + last + " at arc "
                    + round(refusal.arc()) + " has no rail under it";
        };
    }

    // The arc range a ribbon covers, for a bail's detail line.
    private static String span(TrackRibbon ribbon) {
        return round(ribbon.nearArc()) + ".." + round(ribbon.farArc());
    }

    // Two decimals — finer than any of these figures is meaningful to.
    private static String round(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
