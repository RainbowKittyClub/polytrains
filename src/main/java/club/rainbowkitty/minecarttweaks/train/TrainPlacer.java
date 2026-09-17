package club.rainbowkitty.minecarttweaks.train;

import java.util.List;

import com.mojang.logging.LogUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.ext.AbstractMinecartExt;
import club.rainbowkitty.minecarttweaks.rail.TrackRibbon;

/**
 * Seats a train's trailing cars on the track its leading car recorded, instead of correcting the
 * distance between each pair of them.
 *
 * <p>Spacing is built rather than achieved: car <i>i</i> is placed at the leader's arc length less
 * <i>i</i> pitches, so there is no gap to measure and nothing to converge on. A link stretching
 * because two cars disagreed about their speed cannot arise here, since a seated car has no speed
 * of its own to disagree with.
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

    /**
     * Why a train could not be seated. The tag is the finite part, so an intermittent fault is read
     * by what kind it is rather than by a detail line that differs every tick; the detail carries
     * the arcs and blocks that say which instance of it this was.
     */
    public enum Bail {
        NO_LEADING_END,
        LEADER_OFF_RIBBON,
        LEADER_MID_TRAIN,
        ORDER_UNREADABLE,
        CAR_OFF_RIBBON
    }

    // One attempt's outcome. A null tag is a train that seated.
    private record Attempt(@Nullable Bail tag, String detail) {

        static final Attempt SEATED = new Attempt(null, "");

        static Attempt of(Bail tag, String detail) {
            return new Attempt(tag, detail);
        }
    }

    // Where one car is going to be put: the point on the ribbon, the rail block under it, and the
    // way the track runs there. Resolved for every car before any of them moves.
    private record Seat(Vec3 at, BlockPos rail, Vec3 tangent) {}

    // Static-only; blocks instantiation.
    private TrainPlacer() {}

    /**
     * Seats every trailing car of {@code train} at {@code pitch} intervals along its ribbon, and
     * answers whether it did. Nothing is moved unless every car could be placed, so a false answer
     * leaves the train exactly as it was for the coupling to settle instead.
     */
    public static boolean seat(Train train, double pitch) {
        Attempt attempt = attempt(train, pitch);

        if (train.seatingChanged(attempt.tag()) && LOGGER.isDebugEnabled()) {
            LOGGER.debug("train of {} {}", train.size(), attempt.tag() == null
                    ? "seated" : "not seated, " + attempt.tag() + ": " + attempt.detail());
        }

        return attempt.tag() == null;
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
    private static Attempt attempt(Train train, double pitch) {
        AbstractMinecart leader = train.leader();

        if (leader == null || train.size() < 2) {
            return Attempt.of(Bail.NO_LEADING_END, "cars=" + train.size());
        }

        TrackRibbon ribbon = train.ribbon();
        Double leadArc = ribbon.arcOfLeader(leader);

        if (leadArc == null) {
            return Attempt.of(Bail.LEADER_OFF_RIBBON, "leader on "
                    + leader.getCurrentBlockPosOrRailBelow() + ", ribbon of " + ribbon.size()
                    + " nodes running " + span(ribbon));
        }

        List<AbstractMinecart> cars = train.cars();
        int lead = cars.indexOf(leader);

        if (lead != 0 && lead != cars.size() - 1) {
            return Attempt.of(Bail.LEADER_MID_TRAIN, "lead=" + lead + "/" + (cars.size() - 1));
        }

        Vec3 tangent = ribbon.tangentAt(leadArc);
        double facing = facing(leader, tangent);
        int order = train.order();

        if (order == 0) {
            if (facing == 0.0) {
                return Attempt.of(Bail.ORDER_UNREADABLE,
                        "leader at " + round(leadArc) + ", tangent " + (tangent == null
                                ? "none" : "across its motion"));
            }

            // The cars trail the leading one, so the train runs back against the way that car is
            // going. Settled once: from here on it is remembered, not read off the cars again.
            train.setOrder(lead == 0 ? (int) facing : -(int) facing);
            order = train.order();
        }

        // A train too slow to read a heading off has not stopped lying the way it lies.
        if (facing == 0.0) {
            facing = lead == 0 ? order : -order;
        }

        double headArc = leadArc + order * lead * pitch;
        Seat[] seats = new Seat[cars.size()];

        for (int i = 0; i < cars.size(); i++) {
            if (i == lead) {
                continue;
            }

            double arc = headArc - order * i * pitch;

            seats[i] = seatAt(ribbon, cars.get(i), arc);

            if (seats[i] == null) {
                return Attempt.of(Bail.CAR_OFF_RIBBON, "car " + i + "/" + (cars.size() - 1)
                        + " wants arc " + round(arc) + " of " + span(ribbon));
            }
        }

        double speed = leader.getDeltaMovement().horizontalDistance();

        for (int i = 0; i < cars.size(); i++) {
            if (i != lead) {
                place(train, cars.get(i), seats[i], headArc - order * i * pitch, facing, speed);
            }
        }

        return Attempt.SEATED;
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

    // Where one car would go, or null if the ribbon does not reach that far or the track it
    // recorded there has since been taken up.
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
            double facing, double speed) {
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

        if (!(car.getBehavior() instanceof NewMinecartBehavior behavior)) {
            return;
        }

        Vec3 moved = car.position().subtract(was);
        aim(car, moved);

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

    // Points a car along the ground it just covered, which is how vanilla's own stepper turns the
    // cart it moves. Grade comes out of the step rather than off the rail, so a car crossing onto a
    // slope pitches by what it climbed instead of snapping to a fixed angle.
    private static void aim(AbstractMinecart car, Vec3 moved) {
        if (moved.horizontalDistanceSqr() < EPSILON * EPSILON) {
            return;
        }

        double yaw = 180.0 - Math.atan2(moved.z, moved.x) * 180.0 / Math.PI;
        double pitch = 90.0 - Math.atan2(moved.horizontalDistance(), moved.y) * 180.0 / Math.PI;

        car.setYRot((float) (yaw % 360.0));
        car.setXRot((float) Math.clamp(pitch, -45.0, 45.0));
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
