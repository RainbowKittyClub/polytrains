package club.rainbowkitty.minecarttweaks.train;

import java.util.List;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
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

import club.rainbowkitty.minecarttweaks.ext.AbstractMinecartExt;
import club.rainbowkitty.minecarttweaks.ext.Linkable;
import club.rainbowkitty.minecarttweaks.rail.RailDrag;
import club.rainbowkitty.minecarttweaks.rail.TrackRibbon;
import club.rainbowkitty.minecarttweaks.util.MinecartHelper;

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

    // How far past flush against a blocked end still counts as touching it.
    private static final double CONTACT_SLACK = 0.2;

    // How much further than a tick's travel two arcs may be and still be two ends of one move. One
    // node's worth of track, since that is the grain a sweep is answered at.
    private static final double SWEEP_SLACK = 1.5;

    // Past an end car's box, how near another train's cart must stand to be looked at as the end
    // of the track.
    private static final double END_SHELL = 0.5;

    // Finding where a car clears another's box, by step along the track then halving the last.
    // Stepped by box, since two boxes on a diagonal touch with their middles 1.39 apart.
    private static final double CLEAR_STEP = 0.1;
    private static final double CLEAR_REACH = 2.0;
    private static final int CLEAR_HALVINGS = 4;

    // How far past a car's own box a player still counts as leaning on it.
    private static final double SHOVE_SHELL = 0.1;

    // Minimum cosine between a player's push and the rail for it to count as shoving the car.
    private static final double SHOVE_ARC = 0.5;

    /**
     * Why a train could not be seated. {@link #RIBBON_SHORT} and {@link #CAR_OFF_RIBBON} name one
     * car's own track; the rest leave the placer with no frame of reference for the train at all.
     */
    public enum Bail {
        NO_LEADING_END(true),
        LEADER_OFF_RIBBON(true),
        ORDER_UNREADABLE(true),
        RIBBON_SHORT(false),
        CAR_OFF_RIBBON(false),
        SEAT_FOULED(false);

        private final boolean whole;

        Bail(boolean whole) {
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
     * @param detail arcs and blocks identifying this instance, for logging
     */
    public record Refusal(Bail why, int car, String detail) {

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
                    : "not seated, " + loose(train) + " loose: " + attempt.detail());
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

        long seatedTick = ext.minecarttweaks$seatedAt();

        return seatedTick != 0 && seatedTick + 1 >= car.level().getGameTime();
    }

    // Seats the train if the ribbon can answer for every car, naming the first bail otherwise.
    private static @Nullable Refusal attempt(Train train, double target) {
        AbstractMinecart leader = train.leader();

        if (leader == null || train.size() <= 1) {
            return new Refusal(Bail.NO_LEADING_END, Refusal.NONE, "cars=" + train.size());
        }

        TrackRibbon ribbon = train.ribbon();
        List<AbstractMinecart> cars = train.cars();
        Double leadArc = ribbon.arcOfLeader(leader);

        if (leadArc == null) {
            return new Refusal(Bail.LEADER_OFF_RIBBON,
                    ribbon.isEmpty() ? Refusal.NONE : cars.indexOf(leader), "leader on "
                    + leader.getCurrentBlockPosOrRailBelow() + ", ribbon of " + ribbon.size()
                    + " nodes running " + span(ribbon));
        }

        int leadIndex = cars.indexOf(leader);

        // +1 when the leading car is the list's head, so a step toward the tail is a step up the
        // list; -1 at the other end.
        int fromHead = leadIndex == 0 ? 1 : -1;
        int tailIndex = leadIndex == 0 ? cars.size() - 1 : 0;
        int nearGapIndex = leadIndex == 0 ? 0 : cars.size() - 2;

        Double nextArc = ribbon.arcOf(cars.get(leadIndex + fromHead));
        double speed = leader.getDeltaMovement().horizontalDistance();

        // A leading car seated last tick, its train backed against something, skipped vanilla's
        // move and the clamp inside it, so shoves pile up on it unspent. Capped as vanilla would.
        if (seated(leader) && leader.level() instanceof ServerLevel level) {
            speed = Math.min(speed, leader.minecarttweaks$ownMaxSpeed(level));
        }

        double maxLinkDistance = TrainCoupling.maxLinkDistance();
        double[] gaps = train.nextGaps(target, maxLinkDistance, speed);
        double travelSign = travelSign(leader, ribbon.tangentAt(leadArc));
        int order = train.orderSign();

        if (order == 0) {
            Integer lieSign = resolveOrder(
                    nextArc, leadArc, fromHead, gaps[nearGapIndex], travelSign);

            if (lieSign == null) {
                return new Refusal(Bail.ORDER_UNREADABLE, Refusal.NONE,
                        "leader at " + round(leadArc) + " of " + span(ribbon)
                        + ", next car not far enough along it to say");
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

        // Where the track behind the tail ends against something it cannot pass: a recorded end
        // run into a block, or a cart of another train standing on this track. A cart is the end
        // of usable track and not a refusal, so the train packs short of it with every car seated.
        Double tailEndArc = nearerEnd(
                recordedEnd(ribbon, leader.level(), tailArc, tailHalfWidth, tailSign),
                foreignEnd(train, ribbon, tailCar, tailArc, tailSign, tailHalfWidth), tailSign);
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
                foreignEnd(train, ribbon, leader, leadArc, -tailSign, noseLength), -tailSign);
        double roomAhead = aheadEndArc == null
                ? Double.POSITIVE_INFINITY : (leadArc - aheadEndArc) * tailSign;
        boolean jammed = roomAhead <= jamReach;

        // Held at both ends, a train goes nowhere whichever way it reads as moving, so what pokes
        // past its tail comes out of its couplings rather than out of the end ahead of it.
        boolean squeezed = blocked && jammed;

        // A train too slow to read a heading counts as pressing: it has nowhere to lengthen
        // either way, so holding it still is correct either way this reads.
        boolean pressing = blocked && (squeezed || travelSign == 0.0 || travelSign == tailSign);

        // Only a train something is actually pushing packs, and only as far back as that reaches.
        int packReach = jammed
                ? pressedGaps(train, ribbon, headArc, offsets, order, leadIndex, -tailSign)
                : 0;
        boolean packing = packReach > 0;

        // Recorded while there is still somewhere to go, so a train with nowhere left to go closes
        // its couplings at the speed it arrived with rather than the zero it reads once stopped.
        if (!packing) {
            train.setCarried(speed);
        }

        // A pressing or packing train still widens against zero speed: paying that widening into
        // gaps it has nowhere to drive a car into would pin it to the wall it is pressing on.
        if (pressing || packing) {
            gaps = train.nextGaps(target, maxLinkDistance, 0.0);
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
                    && !seatTaken(train, ribbon, leader, leadArc + pushback);

            if (drawnBack) {
                leadArc += pushback;
                headArc += pushback;
            }
        }

        Seat[] seats = new Seat[cars.size()];
        Refusal refusal = null;

        // The leading car is skipped unless drawn back: normally vanilla places it, but a train
        // backed against a wall needs it seated too, so it stops exactly at the wall.
        for (int i = 0; i < cars.size(); i++) {
            if (i == leadIndex && !drawnBack) {
                continue;
            }

            double arc = headArc - order * offsets[i];

            if (!ribbon.reaches(arc)) {
                // A car the train is moving away from is being left behind for good; one it is
                // moving toward may yet be recorded, so waiting on it costs nothing.
                boolean leaving = travelSign != 0.0
                        && Math.signum(arc - leadArc) != travelSign;

                refusal = refusal != null ? refusal
                        : new Refusal(Bail.RIBBON_SHORT, leaving ? i : Refusal.NONE, "car " + i
                        + "/" + (cars.size() - 1) + " wants arc " + round(arc) + " of "
                        + span(ribbon));
                continue;
            }

            seats[i] = seatAt(ribbon, cars.get(i), arc);

            if (seats[i] == null) {
                refusal = refusal != null ? refusal
                        : new Refusal(Bail.CAR_OFF_RIBBON, i, "car " + i + "/"
                        + (cars.size() - 1) + " at arc " + round(arc) + " has no rail under it");
            }
        }

        // Waits rather than counts against the train. A cart on this track past either end has
        // shortened it already; what is left stands off this track or between the train's own cars.
        Foul foul = fouled(train, cars, seats, leadIndex);

        if (foul != null && refusal == null) {
            refusal = new Refusal(Bail.SEAT_FOULED, Refusal.NONE, "a cart of another train lies at "
                    + foul.foulingCart().getCurrentBlockPosOrRailBelow()
                    + ", across the seat of car " + foul.carIndex() + "/" + (cars.size() - 1));
        }

        // A train too slow to read a heading keeps facing whichever end nominally leads, rather
        // than reading a direction out of noise.
        double faceSign = travelSign != 0.0 ? travelSign : order * fromHead;

        for (int i = 0; i < cars.size(); i++) {
            if (i == leadIndex && !drawnBack) {
                continue;
            }

            if (seats[i] == null) {
                loosen(cars.get(i));
                continue;
            }

            place(train, cars.get(i), seats[i], headArc - order * offsets[i],
                    faceSign, speed, i != leadIndex);
        }

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
        RailDrag.bend(leader, turn);

        if (refusal != null) {
            return refusal;
        }

        return null;
    }

    // The first foreign cart lying across one of these seats, or across the ground reaching it.
    private record Foul(int carIndex, AbstractMinecart foulingCart) {}

    // Clears the seats another train's cart stands in or lies across reaching, naming the first.
    // The leading car's seat, taken only to draw a train back, is cleared uncounted.
    private static @Nullable Foul fouled(
            Train train, List<AbstractMinecart> cars, Seat[] seats, int leadIndex) {
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

        List<AbstractMinecart> others = foreignCarts(train, span);
        Foul first = null;

        for (int i = 0; i < seats.length; i++) {
            if (seats[i] == null) {
                continue;
            }

            AABB sweptBox = swept(cars.get(i), seats[i]);

            for (AbstractMinecart other : others) {
                if (sweptBox.intersects(other.getBoundingBox())) {
                    first = first != null || i == leadIndex ? first : new Foul(i, other);

                    seats[i] = null;
                    break;
                }
            }
        }

        return first;
    }

    // Every cart in box but this train's own, a cart coupled to one of its cars counting as one
    // of its own even where a chunk border splits the walk and train.contains says otherwise.
    private static List<AbstractMinecart> foreignCarts(Train train, AABB box) {
        List<AbstractMinecart> cars = train.cars();

        return cars.getFirst().level().getEntitiesOfClass(AbstractMinecart.class, box,
                car -> !train.contains(car) && !coupledToAny(cars, car));
    }

    // The end another train's cart makes of this track past endCar, outwardSign away from the
    // train: how far endCar's middle may come, pushed out by halfLength as a recorded end is.
    private static @Nullable Double foreignEnd(Train train, TrackRibbon ribbon,
            AbstractMinecart endCar, double reachArc, double outwardSign, double halfLength) {
        Vec3 reachPosition = ribbon.positionAt(reachArc);
        AABB aroundBox = endCar.getBoundingBox();

        if (reachPosition != null) {
            aroundBox = aroundBox.minmax(boxAt(endCar, reachPosition));
        }

        List<AbstractMinecart> others = foreignCarts(train, aroundBox.inflate(END_SHELL));

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
    private static @Nullable Double recordedEnd(TrackRibbon ribbon, Level level, double fromArc,
            double reach, double outwardSign) {
        boolean farEnd = outwardSign > 0.0;
        double endArc = farEnd ? ribbon.farArc() : ribbon.nearArc();

        return (endArc - fromArc) * outwardSign < reach && ribbon.blockedBeyond(level, farEnd)
                ? endArc : null;
    }

    // Whether a cart of another train stands where car would be seated at arc.
    private static boolean seatTaken(
            Train train, TrackRibbon ribbon, AbstractMinecart car, double arc) {
        Vec3 position = ribbon.positionAt(arc);

        return position != null && !foreignCarts(train, boxAt(car, position)).isEmpty();
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
    private static boolean coupledToAny(List<AbstractMinecart> cars, AbstractMinecart other) {
        for (AbstractMinecart car : cars) {
            if (Linkable.coupled(car, other)) {
                return true;
            }
        }

        return false;
    }

    // The ground a car crosses reaching its seat: its current box joined to the box it will stand
    // in, so a fouling check catches a train it passes through as well as one it lands on.
    private static AABB swept(AbstractMinecart car, Seat seat) {
        AABB seatBox = boxAt(car, seat.position());

        return seated(car) ? car.getBoundingBox().minmax(seatBox) : seatBox;
    }

    // How far back from the blocked end something is actually pushing, in cars — 0 if nothing is.
    private static int pressedGaps(Train train, TrackRibbon ribbon, double headArc,
            double[] offsets, int order, int leadIndex, double pushSign) {
        List<AbstractMinecart> cars = train.cars();
        int packReach = 0;

        for (int i = 0; i < cars.size(); i++) {
            AbstractMinecart car = cars.get(i);
            Vec3 tangent = ribbon.tangentAt(headArc - order * offsets[i]);
            Vec3 alongDirection = tangent == null ? Vec3.ZERO : tangent.horizontal();

            if (alongDirection.lengthSqr() < EPSILON * EPSILON) {
                continue;
            }

            Vec3 pushDirection = alongDirection.normalize().scale(pushSign);

            if (drives(car, pushDirection) || shoved(car, pushDirection)) {
                packReach = Math.max(packReach, Math.abs(i - leadIndex));
            }
        }

        return packReach;
    }

    // Whether this car's own engine is driving it toward the blocked end.
    private static boolean drives(AbstractMinecart car, Vec3 pushDirection) {
        return car.minecarttweaks$driving()
                && car.minecarttweaks$driveHeading().horizontal().dot(pushDirection) > 0.0;
    }

    // Whether a player is leaning on this car from the side away from the blocked end.
    private static boolean shoved(AbstractMinecart car, Vec3 pushDirection) {
        for (Player player : car.level().getEntitiesOfClass(
                Player.class, car.getBoundingBox().inflate(SHOVE_SHELL))) {
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
            ext.minecarttweaks$setSeatedAt(0L);
        }
    }

    // Hands the whole train back to vanilla; the next seating starts with no prior arc to turn.
    private static void release(Train train) {
        train.setHeadArc(null);
        train.setTurn(0.0);

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

    // Where one car would sit on recorded track, or null if that track is gone.
    private static @Nullable Seat seatAt(TrackRibbon ribbon, AbstractMinecart car, double arc) {
        Vec3 position = ribbon.positionAt(arc);
        BlockPos rail = ribbon.blockAt(arc);
        Vec3 tangent = ribbon.tangentAt(arc);

        if (position == null || rail == null || tangent == null
                || !BaseRailBlock.isRail(car.level().getBlockState(rail))) {
            return null;
        }

        return new Seat(position, rail, tangent);
    }

    // Puts one car on its seat and does by hand everything vanilla would have done moving it there.
    private static void place(Train train, AbstractMinecart car, Seat seat, double arc,
            double faceSign, double speed, boolean shouldAim) {
        Vec3 previousPosition = car.position();
        Vec3 forward = seat.tangent().scale(faceSign);

        // Fires activator rails and the like before the car's position changes underneath them.
        sweep(train, car, arc, speed);

        car.setOldPosAndRot();
        car.setOnRails(true);
        car.resetFallDistance();

        car.setDeltaMovement(forward.scale(speed));
        car.setPos(seat.position().x, seat.position().y, seat.position().z);

        // A seated car covers no ground of its own, so nothing else re-aims its engine along the
        // train's actual heading.
        Vec3 headingDirection = forward.horizontal();

        if (speed > EPSILON && headingDirection.lengthSqr() > EPSILON * EPSILON) {
            car.minecarttweaks$aimEngine(headingDirection.normalize());
        }

        if (!(car.getBehavior() instanceof NewMinecartBehavior behavior)) {
            return;
        }

        Vec3 displacement = car.position().subtract(previousPosition);

        if (shouldAim) {
            aim(car, seat.tangent(), faceSign, displacement);
        }

        // Otherwise the client is left replaying this tick's move on the next one.
        if (displacement.length() > EPSILON) {
            behavior.lerpSteps.add(new NewMinecartBehavior.MinecartStep(car.position(),
                    car.getDeltaMovement(), car.getYRot(), car.getXRot(),
                    (float) displacement.length()));
        }

        // Vanilla hangs both off Entity.move, which a placed car never reaches.
        behavior.pushAndPickupEntities();
        car.applyEffectsFromBlocks(previousPosition, car.position());
    }

    // Fires every activator rail a car crosses reaching its seat, then records where it ended up.
    private static void sweep(Train train, AbstractMinecart car, double arc, double speed) {
        if (!(car.level() instanceof ServerLevel level)
                || !(car instanceof AbstractMinecartExt ext)) {
            return;
        }

        // Where the car still stands, nothing having moved it yet this tick. Read off the ribbon
        // rather than remembered from the last sweep: a remembered arc is a figure from an older
        // reckoning, and a change of leading end re-bases the arcs without moving a car an inch.
        Double was = train.ribbon().arcOf(car, arc);

        // Further apart than a tick's travel and the track between is not crossed ground but merely
        // unaccounted for, so only the rail the car lands on is owed anything.
        double from = was == null || Math.abs(was - arc) > speed + SWEEP_SLACK ? arc : was;

        train.ribbon().blocksBetween(from, arc, block -> {
            BlockState crossed = level.getBlockState(block);

            if (crossed.is(Blocks.ACTIVATOR_RAIL)) {
                car.activateMinecart(level, block.getX(), block.getY(), block.getZ(),
                        crossed.getValue(PoweredRailBlock.POWERED));
            }
        });

        ext.minecarttweaks$setSeatArc(arc, train.ribbon().arcEpoch());
        ext.minecarttweaks$setSeatedAt(level.getGameTime());
    }

    // Points a car along the rail the way it already faces, and pitches it to the grade it just
    // climbed rather than to a fixed angle read off the rail shape.
    private static void aim(
            AbstractMinecart car, Vec3 tangent, double faceSign, Vec3 displacement) {
        Vec3 alongDirection = tangent.horizontal();

        if (alongDirection.lengthSqr() < EPSILON * EPSILON) {
            return;
        }

        alongDirection = alongDirection.normalize();

        int noseSign = noseOf(car, alongDirection);

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
     */
    public static void holdNose(AbstractMinecart car) {
        if (!(car instanceof AbstractMinecartExt ext)) {
            return;
        }

        Vec3 noseVector = ext.minecarttweaks$noseVector();

        if (noseVector == null) {
            return;
        }

        Vec3 headingDirection = MinecartHelper.headingOf(car.getYRot());

        if (headingDirection.dot(noseVector) >= 0.0) {
            ext.minecarttweaks$setNoseVector(headingDirection);
            return;
        }

        car.setFlipped(car.getDeltaMovement().horizontal().dot(noseVector) < 0.0);
        car.setYRot(MinecartHelper.yawOf(noseVector));
    }

    // Which way along the track a car faces, read from its recorded nose rather than its current
    // yaw — vanilla re-aims a driven car's yaw off a step that is not always along the rails.
    private static int noseOf(AbstractMinecart car, Vec3 alongDirection) {
        if (!(car instanceof AbstractMinecartExt ext)) {
            return 1;
        }

        Vec3 heldNoseVector = ext.minecarttweaks$noseVector();
        Vec3 referenceNoseVector = heldNoseVector == null
                ? MinecartHelper.headingOf(car.getYRot()) : heldNoseVector;
        int noseSign = referenceNoseVector.dot(alongDirection) < 0.0 ? -1 : 1;

        ext.minecarttweaks$setNoseVector(alongDirection.scale(noseSign));

        return noseSign;
    }

    // The arc range a ribbon covers, for a bail's detail line.
    private static String span(TrackRibbon ribbon) {
        return round(ribbon.nearArc()) + ".." + round(ribbon.farArc());
    }

    // Two decimals — finer than any of these figures is meaningful to.
    private static String round(double value) {
        return String.format("%.2f", value);
    }
}
