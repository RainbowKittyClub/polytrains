package club.rainbowkitty.minecarttweaks.train;

import java.util.List;

import com.mojang.logging.LogUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.ext.AbstractMinecartExt;
import club.rainbowkitty.minecarttweaks.rail.RailDrag;
import club.rainbowkitty.minecarttweaks.rail.TrackRibbon;
import club.rainbowkitty.minecarttweaks.util.MinecartHelper;

/**
 * Seats a train's trailing cars on the track its leading car recorded, instead of correcting the
 * distance between each pair of them.
 *
 * <p>Spacing is built rather than achieved: car <i>i</i> is placed at the head's arc length less
 * every gap ahead of it, so there is no distance to measure and nothing to converge on. A link
 * stretching because two cars disagreed about their speed cannot arise here, since a seated car
 * has no speed of its own to disagree with. The gaps are held one per pair, so a train carries
 * whatever spacing it was coupled at and closes each one on the target separately.
 *
 * <p>Only the leading car is left to vanilla. It is what defines the curve, so driving it from the
 * curve would be reading back what it wrote.
 *
 * <p>All of the train or none of it. A train half seated and half corrected would have the two
 * holding the same car to different places, so a ribbon that cannot answer for one car hands back
 * the whole train and nothing is moved.
 */
public final class TrainPlacer {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Below this an arc step is treated as no direction at all.
    private static final double EPSILON = 1.0E-5;

    // Of a car's own nose, how much further than flush against a block still counts as standing
    // against it. Vanilla clamps a stopped cart exactly its nose short, so without slack the test
    // turns on two figures being the same number — and they stop being the same the moment the
    // ribbon's chord across a node differs from the line the cart was really stopped along.
    private static final double CONTACT_SLACK = 0.2;

    // How far past a car's own box a player still counts as leaning on it.
    private static final double SHOVE_SHELL = 0.1;

    // How squarely behind a car a player has to stand to be pushing it along the track rather than
    // standing beside it — a cosine, so about sixty degrees either side of the rail.
    private static final double SHOVE_ARC = 0.5;


    /**
     * Why a train could not be seated. The tag is the finite part, so an intermittent fault is read
     * by what kind it is rather than by a detail line that differs every tick; the detail carries
     * the arcs and blocks that say which instance of it this was.
     *
     * <p>Only {@link #CAR_OFF_RIBBON} names track that is gone. {@link #RIBBON_SHORT} names track
     * the leading car has not recorded yet, which a train can wait for unless it is running the
     * other way.
     */
    public enum Bail {
        NO_LEADING_END,
        LEADER_OFF_RIBBON,
        LEADER_MID_TRAIN,
        ORDER_UNREADABLE,
        RIBBON_SHORT,
        CAR_OFF_RIBBON,
        SEAT_FOULED
    }

    /**
     * Why a train could not be seated and which of its cars the answer is about, or {@code NONE}
     * when the whole train is at fault rather than one car. The index is what makes a refusal
     * actionable: a train that cannot be placed has to be cut somewhere, and it is cut at the car
     * the ribbon could not answer for.
     */
    public record Refusal(Bail why, int car, String detail) {

        /** The car index used when a refusal is about the train rather than one of its cars. */
        public static final int NONE = -1;
    }

    // Where one car is going to be put: the point on the ribbon, the rail block under it, and the
    // way the track runs there. Resolved for every car before any of them moves.
    private record Seat(Vec3 at, BlockPos rail, Vec3 tangent) {}

    // Static-only; blocks instantiation.
    private TrainPlacer() {}

    /**
     * Seats every trailing car of {@code train} along its ribbon at intervals easing towards
     * {@code target}, and says whether it could, naming the car it could not place when there is
     * one. Nothing is moved unless every car could be placed, so a refusal leaves the train exactly
     * as it was.
     */
    public static @Nullable Refusal seat(Train train, double target) {
        Refusal attempt = attempt(train, target);

        // A train handed back to the correction is driven along its rails again from this tick, so
        // it must stop reading as placed the moment that happens. The one-tick grace in seated() is
        // there for cars that tick before their leader, and without this it also covers the tick a
        // train falls back on — leaving those cars steering no junction while vanilla moves them
        // over one, which is the shape of a train that stops going straight through.
        if (attempt != null) {
            release(train);
        }

        if (train.seatingChanged(attempt == null ? null : attempt.why())
                && LOGGER.isDebugEnabled()) {
            LOGGER.debug("train of {} {}", train.size(), attempt == null
                    ? "seated" : "not seated, " + attempt.why() + ": " + attempt.detail());
        }

        return attempt;
    }

    /**
     * Whether {@code car} is being placed on its track rather than driven along it. Takes the tick
     * before as well: a train is seated once, on its leader's tick, and the rest of its cars may
     * have ticked already this one.
     */
    public static boolean seated(AbstractMinecart car) {
        if (!(car instanceof AbstractMinecartExt ext)) {
            return false;
        }

        long at = ext.minecarttweaks$seatedAt();

        return at != 0 && at + 1 >= car.level().getGameTime();
    }

    // Seats the train if the ribbon can answer for every car of it, and names what stopped it if
    // not. One outcome out, rather than a log line per bail, so the caller decides what is worth
    // saying and this stays the description of an attempt.
    private static @Nullable Refusal attempt(Train train, double target) {
        AbstractMinecart leader = train.leader();

        if (leader == null || train.size() < 2) {
            return new Refusal(Bail.NO_LEADING_END, Refusal.NONE, "cars=" + train.size());
        }

        TrackRibbon ribbon = train.ribbon();
        List<AbstractMinecart> cars = train.cars();
        Double leadArc = ribbon.arcOfLeader(leader);

        if (leadArc == null) {
            // A ribbon with nothing in it has not lost the leading car, it has never met one, and
            // a train waiting on track to be recorded is not a train in trouble. Naming a car for
            // that takes the leading car off every standing train ten ticks after a restart, and
            // then off what is left of it ten ticks later again. One that does hold track and
            // still cannot place that car has lost it, which is a car worth naming.
            return new Refusal(Bail.LEADER_OFF_RIBBON,
                    ribbon.isEmpty() ? Refusal.NONE : cars.indexOf(leader), "leader on "
                    + leader.getCurrentBlockPosOrRailBelow() + ", ribbon of " + ribbon.size()
                    + " nodes running " + span(ribbon));
        }

        int lead = cars.indexOf(leader);

        if (lead != 0 && lead != cars.size() - 1) {
            return new Refusal(Bail.LEADER_MID_TRAIN, Refusal.NONE,
                    "lead=" + lead + "/" + (cars.size() - 1));
        }

        // Where the car beside the leading one sits says which way round the train lies.
        Double nextArc = ribbon.arcOf(cars.get(lead == 0 ? 1 : cars.size() - 2));
        double speed = leader.getDeltaMovement().horizontalDistance();
        double widest = TrainCoupling.maxLinkDistance();
        double[] gaps = train.nextGaps(target, widest, speed);
        int order = train.order();

        if (order == 0) {
            Integer lie = lieOf(nextArc, leadArc, lead, gaps[lead == 0 ? 0 : gaps.length - 1]);

            if (lie == null) {
                return new Refusal(Bail.ORDER_UNREADABLE, Refusal.NONE,
                        "leader at " + round(leadArc) + " of " + span(ribbon)
                        + ", next car not far enough along it to say");
            }

            // Settled once: from here on it is remembered, not read off the cars again.
            train.setOrder(lie);
            order = lie;
        }

        double[] offsets = offsets(ribbon, gaps, leadArc, order, lead);

        int tail = lead == 0 ? cars.size() - 1 : 0;
        double clearance = cars.get(tail).getBbWidth() * 0.5;
        double nose = leader.getBbWidth() * 0.5;
        double headArc = leadArc + order * offsets[lead];
        double tailArc = headArc - order * offsets[tail];
        boolean farEnd = tailArc > leadArc;
        double room = farEnd ? ribbon.to() - tailArc : tailArc - ribbon.from();
        boolean blocked = room + EPSILON < clearance
                && ribbon.blockedBeyond(leader.level(), farEnd);
        double read = facing(leader, ribbon.tangentAt(leadArc));

        // Whether the train is driving its tail further into that end rather than away from it. A
        // train too slow to read a heading off counts as pressing: it has nowhere to lengthen to
        // either way, and holding it still is what a train standing against a wall should do.
        boolean pressing = blocked && (read == 0.0 || read == (farEnd ? 1.0 : -1.0));

        // The other end of the same question. blocked answers for the end behind the train, which
        // it can only be backed off; this answers for the end ahead of its leading car, which is
        // the one a train is driven into. Vanilla stops a cart half its own length short of a
        // block, so a leading car already that close is standing against one rather than coming
        // up on it — which makes this test inclusive where the other is not.
        double ahead = farEnd ? leadArc - ribbon.from() : ribbon.to() - leadArc;
        boolean jammed = ahead <= nose * (1.0 + CONTACT_SLACK)
                && ribbon.blockedBeyond(leader.level(), !farEnd);

        // Only a train something is pressing packs, and only as far back as that pressing
        // reaches. Touching a block is not being pushed into it, and a train that closed up for
        // merely standing against a buffer would shut itself behind anyone who leaned on it.
        int reach = jammed
                ? pressedGaps(train, ribbon, headArc, offsets, order, lead, farEnd ? -1.0 : 1.0)
                : 0;
        boolean packing = reach > 0;

        // Recorded for the ticks it has somewhere to go, so that when it has not, what it closes
        // its couplings at is the speed it arrived with rather than the nothing it reads now.
        if (!packing) {
            train.setCarried(speed);
        }

        // A train with nowhere to put the ground its leading car covers is refused its widening,
        // at whichever end it has run out of track: paid into the gaps it would only drive a car
        // into whatever stopped the train. One merely leaving a blocked end widens as usual, its
        // head making the room its tail is following out — taking that away would pin a train to
        // a wall it is driving off, which a train that still has widening to do meets every tick.
        if (pressing || packing) {
            gaps = train.nextGaps(target, widest, 0.0);
            offsets = offsets(ribbon, gaps, leadArc, order, lead);
        }

        // What it cannot put behind it, a train packs into itself; one driven up against a block
        // packs onto the car that met it, a step a tick. Either way the pair nearest the block
        // closes first, the way cars driven into a buffer meet.
        if (pressing) {
            tailArc = leadArc + order * (offsets[lead] - offsets[tail]);
            room = farEnd ? ribbon.to() - tailArc : tailArc - ribbon.from();

            // Only once there is no give left does the train stop. Until then its leading car
            // still has somewhere to go, and stopping it would be stopping it short.
            if (train.compress(gaps, target, clearance - room, tail > lead, gaps.length)
                    > EPSILON) {
                speed = 0.0;
            }
        }

        boolean closed = false;

        if (packing) {
            closed = train.pack(gaps, target, lead == cars.size() - 1, reach);

            // A train standing against a block is given no speed either. A seated car is handed
            // the train's own movement every tick, and a furnace car re-aims its push along
            // whatever movement it is holding, so a velocity pointing back off the block is all
            // it takes to turn the whole train around. One reading as leaving keeps its speed, or
            // it could never drive off the block at all.
            if (read == 0.0 || read == (farEnd ? -1.0 : 1.0)) {
                speed = 0.0;
            }
        }

        if (pressing || packing) {
            offsets = offsets(ribbon, gaps, leadArc, order, lead);
            headArc = leadArc + order * offsets[lead];
            tailArc = headArc - order * offsets[tail];
        }

        // A car cannot be put inside a block, and vanilla stops a moving cart half its own length
        // short of one — so a train whose tail would still sit closer than that to a blocked end
        // of its track is drawn back to where it fits, its leading car seated standing with the
        // rest rather than going on through the cars it is pushing, which the placer holds out of
        // its way.
        if (blocked) {
            room = farEnd ? ribbon.to() - tailArc : tailArc - ribbon.from();

            double back = Math.max(0.0, clearance - room) * (farEnd ? -1.0 : 1.0);

            leadArc += back;
            headArc += back;
        }

        Seat[] seats = new Seat[cars.size()];

        for (int i = 0; i < cars.size(); i++) {
            if (i == lead && !blocked) {
                continue;
            }

            double arc = headArc - order * offsets[i];

            // Track the leading car has not recorded yet. Named as lost only while that car is
            // running the other way, since a standing train can wait for it and one driving
            // towards it will record it; one driving away is leaving the car behind for good.
            if (!ribbon.reaches(arc)) {
                boolean leaving = read != 0.0 && Math.signum(arc - leadArc) != read;

                return new Refusal(Bail.RIBBON_SHORT, leaving ? i : Refusal.NONE, "car " + i
                        + "/" + (cars.size() - 1) + " wants arc " + round(arc) + " of "
                        + span(ribbon));
            }

            seats[i] = seatAt(ribbon, cars.get(i), arc);

            if (seats[i] == null) {
                return new Refusal(Bail.CAR_OFF_RIBBON, i, "car " + i + "/" + (cars.size() - 1)
                        + " at arc " + round(arc) + " has no rail under it");
            }
        }

        // Nothing is laid where another train already lies. A seat is applied outright rather than
        // moved into, so the ground between a car and its seat is crossed without anything standing
        // in it being touched — and at the speeds this allows that ground is a whole train, passed
        // through cleanly and noticed by neither. Refused as a train waiting rather than a train in
        // trouble: it names no car, so it costs nothing towards a sever, and vanilla drives the
        // cars until the way is clear, which is the one state in which they are solid to each
        // other.
        // Refused for as long as it takes, with no allowance that runs out. Seating a train into
        // another once one did is not a smaller failure than leaving it loose: a seat is applied
        // outright, so the two trains end up inside each other, and neither of them moves again
        // under its own power to work its way out.
        AbstractMinecart fouling = fouled(train, cars, seats);

        if (fouling != null) {
            return new Refusal(Bail.SEAT_FOULED, Refusal.NONE, "a cart of another train lies at "
                    + fouling.getCurrentBlockPosOrRailBelow() + ", across this train's seats");
        }

        // A train too slow to read a heading off has not stopped lying the way it lies, so its
        // movement is still laid along the end that leads — at this speed the zero vector either
        // way. What that does not license is turning the cars: which end nominally leads is a held
        // answer, and on a train rebuilt around a link change it is whichever end came first. Nor
        // is the inferred end a facing to remember, or the first real movement of a train that
        // has never moved reads as a reversal of it.
        double facing = read != 0.0 ? read : (lead == 0 ? order : -order);

        for (int i = 0; i < cars.size(); i++) {
            if (i != lead || blocked) {
                place(train, cars.get(i), seats[i], headArc - order * offsets[i],
                        facing, speed, i != lead);
            }
        }

        train.setGaps(gaps);

        // Stamped here and not where the gaps were closed: a refusal between the two throws the
        // closing away, and a train marked as closing when nothing did holds off the engine's own
        // switchback for a tick it had not earned.
        if (closed) {
            train.setPacked(leader.level().getGameTime());
        }

        // Charged here because this is the one place per tick that knows where the head was and
        // where it now is. Every car crosses the same joints as the head, so billing the head once
        // and not dividing by the length is the same total as billing each car as it passes —
        // noticed as the train enters the corner rather than spread over the cars going through it.
        Double was = train.headArc();
        double turn = was == null ? 0.0 : ribbon.turnBetween(was, headArc);

        train.setHeadArc(headArc);
        train.setTurn(turn);
        RailDrag.bend(leader, turn);

        return null;
    }

    // The first cart of another train lying where one of these seats would put a car, or across
    // the ground that car would cover getting there, or null for a clear run. One entity query for
    // the whole train rather than one per car: the query is the expensive half, and a cart close
    // enough to foul any seat is inside the span of all of them.
    private static @Nullable AbstractMinecart fouled(
            Train train, List<AbstractMinecart> cars, Seat[] seats) {
        AABB span = null;

        for (int i = 0; i < seats.length; i++) {
            if (seats[i] != null) {
                AABB swept = swept(cars.get(i), seats[i]);

                span = span == null ? swept : span.minmax(swept);
            }
        }

        if (span == null) {
            return null;
        }

        List<AbstractMinecart> others = cars.getFirst().level().getEntitiesOfClass(
                AbstractMinecart.class, span, car -> !train.contains(car));

        for (int i = 0; i < seats.length; i++) {
            if (seats[i] == null) {
                continue;
            }

            AABB swept = swept(cars.get(i), seats[i]);

            for (AbstractMinecart other : others) {
                if (swept.intersects(other.getBoundingBox())) {
                    return other;
                }
            }
        }

        return null;
    }

    // The ground one car covers reaching its seat: the box it stands in now joined to the box it
    // would stand in there. A placed car is put at its seat rather than moved to it, so there is
    // nothing else that describes what it goes past on the way.
    //
    // Only for a car the placer is already holding. One being taken back up has been loose and may
    // be anywhere by now, and joining those two boxes gives a volume that grows with however far it
    // scattered — which fouls on whatever it reaches, refuses the seating, and leaves the car loose
    // to scatter further. A train knocked apart could then never be gathered again.
    private static AABB swept(AbstractMinecart car, Seat seat) {
        AABB at = car.getBoundingBox().move(seat.at().subtract(car.position()));

        return seated(car) ? car.getBoundingBox().minmax(at) : at;
    }

    // How many of the couplings behind the car that met the block are being pressed into
    // it. Only those are in compression: past the furthest thing pushing that way the train is
    // being towed rather than shoved, and a coupling in tension does not close up.
    //
    // Two things push: an engine of the train's own, and a player leaning on a car. Either is
    // asked against the rail under the car it acts on, so one round a bend still counts as
    // pushing the way the track goes there, and the furthest of them sets the span.
    private static int pressedGaps(Train train, TrackRibbon ribbon, double headArc,
            double[] offsets, int order, int lead, double towards) {
        List<AbstractMinecart> cars = train.cars();
        int reach = 0;

        for (int i = 0; i < cars.size(); i++) {
            AbstractMinecart car = cars.get(i);
            Vec3 tangent = ribbon.tangentAt(headArc - order * offsets[i]);
            Vec3 along = tangent == null ? Vec3.ZERO : tangent.horizontal();

            if (along.lengthSqr() < EPSILON * EPSILON) {
                continue;
            }

            Vec3 into = along.normalize().scale(towards);

            if (drives(car, into) || shoved(car, into)) {
                reach = Math.max(reach, Math.abs(i - lead));
            }
        }

        return reach;
    }

    // Whether this car's own engine is pushing it towards the block.
    private static boolean drives(AbstractMinecart car, Vec3 into) {
        return car.minecarttweaks$driving()
                && car.minecarttweaks$driveHeading().horizontal().dot(into) > 0.0;
    }

    // Whether a player is leaning on this car from the side away from the block. Standing against
    // it is the whole of the test: vanilla shoves an entity out of a box it overlaps whether or
    // not whoever is overlapping it is moving, so someone in contact is already pushing.
    private static boolean shoved(AbstractMinecart car, Vec3 into) {
        for (Player player : car.level().getEntitiesOfClass(
                Player.class, car.getBoundingBox().inflate(SHOVE_SHELL))) {
            Vec3 behind = car.position().subtract(player.position()).horizontal();

            if (!player.isSpectator()
                    && behind.lengthSqr() > EPSILON * EPSILON
                    && behind.normalize().dot(into) > SHOVE_ARC) {
                return true;
            }
        }

        return false;
    }

    // How far behind the head each car sits, as a running total of the gaps ahead of it. One entry
    // per car, the head's being zero, so a seat is one subtraction wherever it is asked for.
    //
    // Laid in arc, which is a horizontal measure, while a gap is meant along the rails — so each
    // one is walked over the track it actually spans rather than scaled by a single reading. The
    // train is not short enough for one reading to do: taken at the leading car, a train nosing
    // onto a grade takes every one of its couplings down at once, and the cars still on the flat
    // behind it are drawn together by a third with nothing under them to account for it.
    //
    // Walked from the leading car, the one whose arc is known, outwards through the train; which
    // end that is decides which way the walk runs.
    private static double[] offsets(TrackRibbon ribbon, double[] gaps, double leadArc,
            int order, int lead) {
        double[] spans = new double[gaps.length];
        double at = leadArc;

        // Cars run head to tail against the arc, so a gap towards the tail is a step by -order and
        // one towards the head a step by +order.
        if (lead == 0) {
            for (int i = 0; i < gaps.length; i++) {
                spans[i] = ribbon.arcAlong(at, gaps[i], -order);
                at -= order * spans[i];
            }
        } else {
            for (int i = gaps.length - 1; i >= 0; i--) {
                spans[i] = ribbon.arcAlong(at, gaps[i], order);
                at += order * spans[i];
            }
        }

        double[] out = new double[gaps.length + 1];

        for (int i = 0; i < gaps.length; i++) {
            out[i + 1] = out[i] + spans[i];
        }

        return out;
    }

    // Drops every car's seat mark, so that none of this train reads as placed.
    private static void release(Train train) {
        // Where the head was stops meaning anything once vanilla is driving it again: the ground it
        // covers in between is not measured from here, and the next seating would charge the whole
        // gap as one turn.
        train.setHeadArc(null);
        train.setTurn(0.0);

        for (AbstractMinecart car : train.cars()) {
            if (car instanceof AbstractMinecartExt ext) {
                ext.minecarttweaks$setSeatedAt(0L);
            }
        }
    }

    // Which way round the train lies on its ribbon: +1 with its head at the higher arc. Read off
    // where the car beside the leading one actually sits, not off which way the train is moving.
    //
    // Motion answers this wrongly at exactly the moment it is most often asked. A train that has
    // just been coupled, or has just come to rest against something, has a leading car whose
    // remaining movement is noise — and the answer is then held for the life of the ribbon's
    // numbering, so one bad reading lays the train backwards for good, seating its cars off the end
    // of the track it is standing on. Two cars closer together than a quarter of their own gap
    // are not far enough apart to say, which is what a collapsed train looks like as well.
    private static @Nullable Integer lieOf(
            @Nullable Double nextArc, double leadArc, int lead, double gap) {
        if (nextArc == null) {
            return null;
        }

        double away = (leadArc - nextArc) * (lead == 0 ? 1.0 : -1.0);

        return Math.abs(away) < gap * 0.25 ? null : (int) Math.signum(away);
    }

    // Which way along the ribbon the leading car is travelling, as a sign on the tangent, or zero
    // where it is too slow to say. Read off the leader because it is the one car vanilla drives,
    // and the only one whose movement is its own.
    private static double facing(AbstractMinecart leader, @Nullable Vec3 tangent) {
        if (tangent == null) {
            return 0.0;
        }

        double along = leader.getDeltaMovement().horizontal().dot(tangent);

        return Math.abs(along) < EPSILON ? 0.0 : Math.signum(along);
    }

    // Where one car would go on track the ribbon reaches, or null if the track it recorded there
    // has since been taken up.
    private static @Nullable Seat seatAt(TrackRibbon ribbon, AbstractMinecart car, double arc) {
        Vec3 at = ribbon.positionAt(arc);
        BlockPos rail = ribbon.blockAt(arc);
        Vec3 tangent = ribbon.tangentAt(arc);

        if (at == null || rail == null || tangent == null
                || !BaseRailBlock.isRail(car.level().getBlockState(rail))) {
            return null;
        }

        return new Seat(at, rail, tangent);
    }

    // Puts one car on its seat, and pays back by hand everything vanilla hangs off the move this
    // car is no longer making.
    private static void place(Train train, AbstractMinecart car, Seat seat, double arc,
            double facing, double speed, boolean lay) {
        Vec3 was = car.position();
        Vec3 forward = seat.tangent().scale(facing);

        // What the stepper would have set crossing each of these blocks, so that a placed car is
        // only ever refused the move itself.
        sweep(train, car, arc);

        car.setOldPosAndRot();
        car.setOnRails(true);
        car.resetFallDistance();

        // The ribbon carries the height the rail runs at, so the seat is the whole answer and there
        // is nothing left for vanilla's rail-transition adjustment to correct. That adjustment is
        // no longer asked for: it turns a cart through a latch meant to fire once at a transition,
        // and on a hill it picks between exactly plus and minus 45 degrees.
        car.setDeltaMovement(forward.scale(speed));
        car.setPos(seat.at().x, seat.at().y, seat.at().z);

        // The one thing a seated car cannot do for itself. Vanilla re-aims an engine off the
        // ground it covered, and a seated car covers none, so a train's engines are handed the
        // heading the train is actually running on — without which they keep the one they were
        // lit on, through every corner since.
        Vec3 heading = forward.horizontal();

        if (speed > EPSILON && heading.lengthSqr() > EPSILON * EPSILON) {
            car.minecarttweaks$aimEngine(heading.normalize());
        }

        if (!(car.getBehavior() instanceof NewMinecartBehavior behavior)) {
            return;
        }

        Vec3 moved = car.position().subtract(was);

        if (lay) {
            aim(car, seat.tangent(), facing, moved, train.ribbon().frame());
        }

        // Nothing else tells the client this car moved, and a step short of the ground it covered
        // leaves the client still replaying this tick's move during the next.
        if (moved.length() > EPSILON) {
            behavior.lerpSteps.add(new NewMinecartBehavior.MinecartStep(car.position(),
                    car.getDeltaMovement(), car.getYRot(), car.getXRot(),
                    (float) moved.length()));
        }

        // Vanilla hangs these off Entity.move, which a placed car does not reach, so without them
        // a train would pass through mobs and players and cross a block without being in it.
        behavior.pushAndPickupEntities();
        car.applyEffectsFromBlocks(was, car.position());
    }

    // Fires what each rail block a car is about to cross owes it, then records where it ends up so
    // the next tick knows where it started. Activator rails are the whole point: a car covers
    // several blocks a tick, so firing only the one it parks on primes a TNT car on a quarter of
    // the rails that should and leaves a hopper car enabled over track that disables it.
    private static void sweep(Train train, AbstractMinecart car, double arc) {
        if (!(car.level() instanceof ServerLevel level)
                || !(car instanceof AbstractMinecartExt ext)) {
            return;
        }

        double from = seated(car) ? ext.minecarttweaks$seatArc() : arc;

        train.ribbon().blocksBetween(from, arc, block -> {
            BlockState crossed = level.getBlockState(block);

            if (crossed.is(Blocks.ACTIVATOR_RAIL)) {
                car.activateMinecart(level, block.getX(), block.getY(), block.getZ(),
                        crossed.getValue(PoweredRailBlock.POWERED));
            }
        });

        ext.minecarttweaks$setSeatArc(arc);
        ext.minecarttweaks$setSeatedAt(level.getGameTime());
    }

    // Lays one car along the rail under it, pointing the way that car already points. Which end a
    // cart leads with cannot change while it is on a rail, so it is remembered and read back rather
    // than worked out again from which way the train is going. Working it out is what a heading
    // cannot do: a U-turn swings a car through 180 degrees without reversing it, and two cars of
    // one train may honestly face opposite ways, so there is no train-wide answer to hold either.
    //
    // Grade comes out of the ground covered rather than off the rail, so a car crossing onto a
    // slope pitches by what it climbed instead of snapping to a fixed angle.
    private static void aim(
            AbstractMinecart car, Vec3 tangent, double facing, Vec3 moved, int frame) {
        Vec3 along = tangent.horizontal();

        if (along.lengthSqr() < EPSILON * EPSILON) {
            return;
        }

        along = along.normalize();

        int nose = noseOf(car, along, frame);

        // Vanilla's own record of a cart running backwards. Derived here rather than toggled, so
        // that no reading of it can be missed, doubled or taken a tick late: whatever it was, it is
        // this now. Kept true because the climb below reads it, and because vanilla reads it the
        // moment this car stops being placed.
        boolean flipped = nose * facing < 0.0;

        car.setFlipped(flipped);
        car.setYRot(MinecartHelper.yawOf(along.scale(nose)));

        // The climb has only the ground covered to read from, so a car placed where it already
        // stood keeps the angle it has.
        if (moved.horizontalDistanceSqr() < EPSILON * EPSILON) {
            return;
        }

        float pitch = (float) (90.0
                - Math.atan2(moved.horizontalDistance(), moved.y) * 180.0 / Math.PI);

        car.setXRot(Math.clamp(flipped ? -pitch : pitch, -45.0F, 45.0F) % 360.0F);
    }

    // Which way along the ribbon a car's nose points, taken once per arc numbering and remembered
    // after. Read off the way the car is already lying, which is along its rail or against it and
    // so cannot be read wrong; an answer taken against another numbering is no answer, a
    // re-recorded ribbon being free to run the other way.
    private static int noseOf(AbstractMinecart car, Vec3 along, int frame) {
        if (!(car instanceof AbstractMinecartExt ext)) {
            return 1;
        }

        int held = ext.minecarttweaks$nose(frame);

        if (held != 0) {
            return held;
        }

        int nose = MinecartHelper.headingOf(car.getYRot()).dot(along) < 0.0 ? -1 : 1;

        ext.minecarttweaks$setNose(nose, frame);

        return nose;
    }

    // The stretch of arc a ribbon covers, for a bail line to say what it fell outside of.
    private static String span(TrackRibbon ribbon) {
        return round(ribbon.from()) + ".." + round(ribbon.to());
    }

    // Two decimals, which is finer than any of these numbers is meaningful to.
    private static String round(double value) {
        return String.format("%.2f", value);
    }
}
