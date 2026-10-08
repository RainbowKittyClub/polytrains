package club.rainbowkitty.polytrains.train;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.mojang.logging.LogUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.Minecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartChest;
import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.entity.EntityTypeTest;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.polytrains.ext.Linkable;
import club.rainbowkitty.polytrains.rail.RailBrake;
import club.rainbowkitty.polytrains.rail.RailDrag;
import club.rainbowkitty.polytrains.rail.RailForces;
import club.rainbowkitty.polytrains.rail.RailPath;
import club.rainbowkitty.polytrains.rail.TrackRibbon;
import club.rainbowkitty.polytrains.rule.ModGameRules;

/**
 * What the trains in a level are doing, in lines a script reads back: once a second a
 * {@code meter} line for every train and every loose cart, and an {@code event=} line for each
 * sever, strike, overlap, clip, overspeed, stalled engine turning round, switch change and change
 * of seating. Every line names its trains and carts by {@link Train#id} and
 * {@link Train#cartId}, so one train can be followed from line to line and two of the same length
 * told apart. All of it is written at DEBUG and costs nothing below it.
 *
 * <p>The sample is taken after the level's tick and settles nothing on the way: it rebuilds no
 * train and names no leading end, so the tick after it runs as it would have run without it.
 *
 * <p>Spacing is inter-car distance along the rails, walked by {@link RailPath}, which knows nothing
 * about the ribbon and nothing about how the cars reached where they are. A figure comparing a car
 * against the ribbon's own prediction reads near zero once the ribbon drives the cars, whether or
 * not the train looks right; one walked independently keeps meaning something.
 *
 * <p>Mass rides along on the same line. It measures nothing — the model is asked and it answers —
 * but it names the force model a reading was taken under, without which a coast or grade figure
 * cannot be placed afterwards.
 */
public final class TrainMeter {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Once a second. Per-tick output is unreadable and the numbers barely move between ticks.
    private static final int LOG_PERIOD_TICKS = 20;

    // How far to walk before giving up on a pair. Past the breaking distance the coupling would
    // have severed them anyway, so a longer walk measures a train that no longer exists.
    private static final double REACH_MULTIPLIER = 2.0;

    // Slack over a cart's ceiling, as a fraction of it, on top of what vanilla itself adds after
    // its clamp (see overspeedLimit).
    private static final double OVERSPEED_MARGIN = 0.01;

    // Horizontal centre distance inside which two carts of different trains are inside each other.
    private static final double OVERLAP_DISTANCE = 0.5;

    // How deep two trains' cars may stand inside each other at the end of a tick before it is a
    // clip: the least of the three axis overlaps, how far one box must move to clear the other.
    private static final double CLIP_DEPTH = 0.01;

    // Beyond this a figure is written in exponent form: a runaway would print a hundred digits.
    private static final double PLAIN_LIMIT = 1.0E6;

    // The fastest each cart has moved in one tick since its last meter line. Keyed by the entity
    // rather than its UUID, since a cart reloaded or taken to another dimension is a new entity.
    private static final Map<AbstractMinecart, Double> PEAKS = new IdentityHashMap<>();

    // The last tick each cart was seen over its ceiling, so an overspeed is written once when it
    // starts rather than on every tick it lasts.
    private static final Map<AbstractMinecart, Long> OVER = new IdentityHashMap<>();

    // The last tick each pair of cars was seen clipping, the same way.
    private static final Map<Clip, Long> CLIPPING = new HashMap<>();

    // The game time each level was last sampled at. A frozen tick rate still runs the end of each
    // level's tick without advancing the time that picks a sample.
    private static final Map<ResourceKey<Level>, Long> SAMPLED = new HashMap<>();

    // Static-only; blocks instantiation.
    private TrainMeter() {}

    /** Whether meter and event lines are being written, so a caller can skip building one. */
    public static boolean enabled() {
        return LOGGER.isDebugEnabled();
    }

    /**
     * Writes a {@code meter} line for every train of two or more cars in {@code level} and one for
     * every cart in no train, every {@link #LOG_PERIOD_TICKS} ticks while DEBUG is on, and a
     * {@code clip} line every tick for each pair of two trains' cars that has just come to stand
     * inside each other. For the end of the level's tick, when every car has moved.
     *
     * <p>Reads each train as its cars last left it. A train whose links have changed since any of
     * its cars last asked for it is skipped, and read at the next sample once they have rebuilt it.
     */
    public static void sample(ServerLevel level) {
        if (!LOGGER.isDebugEnabled()) {
            forgetAll();
            return;
        }

        long tick = level.getGameTime();
        Long sampledTick = SAMPLED.put(level.dimension(), tick);

        if (sampledTick != null && sampledTick == tick) {
            return;
        }

        clips(level, tick);

        if (tick % LOG_PERIOD_TICKS != 0) {
            return;
        }

        boolean trains = ModGameRules.trainsEnabled(level);
        Set<AbstractMinecart> written = Collections.newSetFromMap(new IdentityHashMap<>());

        for (AbstractMinecart cart : level.getEntities(
                EntityTypeTest.forClass(AbstractMinecart.class), Entity::isAlive)) {
            if (written.contains(cart)) {
                continue;
            }

            if (!trains || !cart.hasLink()) {
                written.add(cart);
                writeCart(level, tick, cart);
                continue;
            }

            Train train = Train.peek(cart);

            if (train == null) {
                continue;
            }

            written.addAll(train.cars());

            if (train.size() < 2) {
                writeCart(level, tick, cart);
            } else {
                writeTrain(level, tick, train);
            }
        }

        PEAKS.keySet().removeIf(Entity::isRemoved);
        OVER.entrySet().removeIf(
                entry -> entry.getKey().isRemoved() || entry.getValue() < tick - 1);
    }

    /**
     * How fast {@code movement} is going, as the figure vanilla's speed clamp bounds: its length
     * across the ground. On rails vanilla scales that length down to the ceiling, keeping the
     * direction, so a diagonal or a grade reads no faster than the same speed on the level.
     */
    public static double speed(Vec3 movement) {
        return Math.hypot(movement.x(), movement.z());
    }

    /**
     * The fastest a cart with this {@code ceiling} can legitimately be going at the top of a tick,
     * with 1% to spare. Vanilla clamps a car's speed on the first step of each tick only, then
     * adds, once a tick each, what a lit powered rail gives — a boost over the ceiling, or a kick
     * from rest — and the pull of a grade met on a later step (NewMinecartBehavior's
     * calculateTrackSpeed), so a car at its ceiling carries both over it into the next tick.
     */
    public static double overspeedLimit(double ceiling) {
        double lit = Math.max(ceiling + RailBrake.BOOST_SPEED,
                RailBrake.ROLLING_SPEED + RailBrake.LAUNCH_SPEED);

        return ceiling * OVERSPEED_MARGIN + lit
                + Math.max(RailDrag.SLOPE_PULL, lit * TrainCoupling.SLOPE_SPEED_GAIN);
    }

    /**
     * Notes the movement {@code cart} starts this tick with, for {@code maxspeed} on its next
     * {@code meter} line, and writes an {@code overspeed} line on the tick its {@link #speed} first
     * passes the {@link #overspeedLimit} of {@code ceiling}. Nothing unless DEBUG is on.
     *
     * @param ceiling the most the rails leave in the cart's movement after their clamp
     * @param clamped whether {@code movement} is about to be clamped as absurd; such a tick is
     *     always written, and marked so
     */
    public static void observe(ServerLevel level, AbstractMinecart cart, Vec3 movement,
            double ceiling, boolean clamped) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }

        long tick = level.getGameTime();
        double observedSpeed = speed(movement);
        double limit = overspeedLimit(ceiling);

        PEAKS.merge(cart, observedSpeed, Math::max);

        if (!clamped && !(observedSpeed > limit)) {
            OVER.remove(cart);
            return;
        }

        Long overTick = OVER.put(cart, tick);

        if (clamped || overTick == null || overTick != tick - 1) {
            LOGGER.debug(Line.event("overspeed")
                    .count("tick", tick)
                    .text("cart", Train.cartId(cart))
                    .text("train", trainIdOf(cart))
                    .number("speed", observedSpeed)
                    .number("ceiling", ceiling)
                    .flag("clamped", clamped)
                    .prose("movement " + xyz(movement) + ", limit " + number(limit))
                    .toString());
        }
    }

    /**
     * Writes an {@code event=sever} line for {@code train} about to be cut between car
     * {@code carIndex} and its neighbour {@code keptIndex}, naming the two trains the cut leaves by
     * the ids they will go by. Nothing unless DEBUG is on, or when the two are not neighbours.
     *
     * @param reason {@code DIVERTED}, or the name of the refusal that held
     * @param detail prose to follow the tokens
     */
    public static void sever(
            Train train, int carIndex, int keptIndex, String reason, String detail) {
        List<AbstractMinecart> cars = train.cars();
        int splitIndex = Math.max(carIndex, keptIndex);

        if (!LOGGER.isDebugEnabled() || Math.abs(carIndex - keptIndex) != 1
                || Math.min(carIndex, keptIndex) < 0 || splitIndex >= cars.size()) {
            return;
        }

        AbstractMinecart cut = cars.get(carIndex);

        LOGGER.debug(Line.event("sever")
                .count("tick", cut.level().getGameTime())
                .text("train", train.id())
                .text("at", Train.cartId(cut))
                .text("reason", reason)
                .text("into", Train.trainId(cars.subList(0, splitIndex)) + ","
                        + Train.trainId(cars.subList(splitIndex, cars.size())))
                .prose("car " + carIndex + "/" + (cars.size() - 1) + ", " + detail)
                .toString());
    }

    /**
     * Writes an {@code event=stall_reverse} line for {@code engine}, which has stood under power
     * long enough to turn its train round. Asks {@link Train#of}, as that reversal does straight
     * after.
     */
    public static void stallReversed(AbstractMinecart engine) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }

        LOGGER.debug(Line.event("stall_reverse")
                .count("tick", engine.level().getGameTime())
                .text("train", Train.of(engine).id())
                .text("cart", Train.cartId(engine))
                .text("pos", xyz(engine.position()))
                .text("rail", rail(engine.level(), engine.getCurrentBlockPosOrRailBelow()))
                .toString());
    }

    /**
     * Writes an {@code event=strike} line for {@code striker} running into {@code struck} and
     * sharing its momentum with it. Nothing unless DEBUG is on.
     *
     * @param closing how much faster the striker ran along the contact heading than the car that
     *     takes the push
     * @param shared the speed along that heading the meeting leaves them both
     * @param gained how much of that speed the pushed car was given
     */
    public static void strike(AbstractMinecart striker, AbstractMinecart struck, double closing,
            double shared, double gained) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }

        LOGGER.debug(Line.event("strike")
                .count("tick", striker.level().getGameTime())
                .text("striker", Train.cartId(striker))
                .text("struck", Train.cartId(struck))
                .text("trains", trainIdOf(striker) + "," + trainIdOf(struck))
                .number("closing", closing)
                .number("shared", shared)
                .number("gained", gained)
                .toString());
    }

    /**
     * Writes an {@code event=overlap} line when {@code other} is a cart of another train standing
     * within {@link #OVERLAP_DISTANCE} of {@code cart} across the ground, at a height their boxes
     * share: two vehicles inside each other. Nothing unless DEBUG is on.
     *
     * <p>The height matters where track crosses under track: a car on a slope reaches through the
     * blocks it climbs in a tick, and reads a car passing beneath as right on top of it.
     */
    public static void overlap(AbstractMinecart cart, AbstractMinecart other) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }

        double distance = Math.hypot(other.getX() - cart.getX(), other.getZ() - cart.getZ());

        // Cheapest first: sameTrain asks both cars for their trains.
        if (!(distance <= OVERLAP_DISTANCE) || !sharesHeight(cart, other)
                || Linkable.coupled(cart, other) || TrainCoupling.sameTrain(cart, other)) {
            return;
        }

        LOGGER.debug(Line.event("overlap")
                .count("tick", cart.level().getGameTime())
                .text("cart", Train.cartId(cart))
                .text("other", Train.cartId(other))
                .text("trains", trainIdOf(cart) + "," + trainIdOf(other))
                .number("distance", distance)
                .toString());
    }

    /**
     * Writes an {@code event=switch} line for the switch at {@code pos} being asked to change from
     * {@code from} to {@code to}, with whether a train lies across it — the one case where the
     * change moves rails out from under a train, read without rebuilding any. Nothing for a
     * request that changes nothing.
     *
     * @param applied whether the change was made, rather than deferred or refused
     * @param detail prose to follow the tokens
     */
    public static void switched(Level level, BlockPos pos, RailShape from, RailShape to,
            boolean applied, String detail) {
        if (!LOGGER.isDebugEnabled() || from == to) {
            return;
        }

        LOGGER.debug(Line.event("switch")
                .count("tick", level.getGameTime())
                .text("pos", xyz(Vec3.atLowerCornerOf(pos)))
                .text("from", from.getSerializedName())
                .text("to", to.getSerializedName())
                .flag("spanned", TrainCoupling.crossedByHeldTrain(level, pos))
                .flag("applied", applied)
                .prose(detail)
                .toString());
    }

    /**
     * Writes an {@code event=refusal} line for {@code train}'s seating having just changed:
     * refused, with why and which car, or seated again when {@code refusal} is null.
     *
     * @param detail prose to follow the tokens
     */
    public static void refusal(
            Train train, TrainPlacer.@Nullable Refusal refusal, String detail) {
        if (!LOGGER.isDebugEnabled()) {
            return;
        }

        int carIndex = refusal == null ? TrainPlacer.Refusal.NONE : refusal.car();

        LOGGER.debug(Line.event("refusal")
                .count("tick", train.cars().getFirst().level().getGameTime())
                .text("train", train.id())
                .text("why", refusal == null ? "none" : refusal.why().name())
                .text("car", carIndex == TrainPlacer.Refusal.NONE
                        ? "none" : Integer.toString(carIndex))
                .prose(detail)
                .toString());
    }

    // Whether the two carts' boxes overlap in height.
    private static boolean sharesHeight(AbstractMinecart cart, AbstractMinecart other) {
        return cart.getBoundingBox().maxY > other.getBoundingBox().minY
                && other.getBoundingBox().maxY > cart.getBoundingBox().minY;
    }

    // Writes a clip line for each pair of two trains' cars inside each other deeper than
    // CLIP_DEPTH, once as it starts. Read after every car's move, so never part-way through one.
    private static void clips(ServerLevel level, long tick) {
        for (AbstractMinecart cart : level.getEntities(
                EntityTypeTest.forClass(AbstractMinecart.class), Entity::isAlive)) {
            AABB box = cart.getBoundingBox();

            // Each pair once, from the car whose UUID sorts first.
            for (AbstractMinecart other : level.getEntitiesOfClass(AbstractMinecart.class, box,
                    candidate -> candidate.isAlive()
                            && candidate.getUUID().compareTo(cart.getUUID()) > 0)) {
                double depth = depth(box, other.getBoundingBox());

                if (!(depth > CLIP_DEPTH)) {
                    continue;
                }

                // Judged again next tick, and a clip the pair was already in goes on meanwhile.
                if (unreadable(level, cart) || unreadable(level, other)) {
                    CLIPPING.computeIfPresent(new Clip(cart, other), (pair, seenTick) -> tick);
                    continue;
                }

                if (!twoTrains(level, cart, other)) {
                    continue;
                }

                Long lastTick = CLIPPING.put(new Clip(cart, other), tick);

                if (lastTick == null || lastTick < tick - 1) {
                    LOGGER.debug(Line.event("clip")
                            .count("tick", tick)
                            .text("cart", Train.cartId(cart))
                            .text("other", Train.cartId(other))
                            .text("trains", trainIdOf(cart) + "," + trainIdOf(other))
                            .text("depth", String.format(Locale.ROOT, "%.3f", depth))
                            .prose("at " + xyz(cart.position()) + " and " + xyz(other.position()))
                            .toString());
                }
            }
        }

        CLIPPING.values().removeIf(lastTick -> lastTick < tick - 1);
    }

    // Whether car's train cannot be read this tick, its links having changed since its cars last
    // asked for it.
    private static boolean unreadable(ServerLevel level, AbstractMinecart car) {
        return ModGameRules.trainsEnabled(level) && car.hasLink() && Train.peek(car) == null;
    }

    // Whether the two are cars of two trains, read without rebuilding either, both readable.
    private static boolean twoTrains(ServerLevel level, AbstractMinecart cart,
            AbstractMinecart other) {
        if (Linkable.coupled(cart, other) || Linkable.coupled(other, cart)) {
            return false;
        }

        if (!ModGameRules.trainsEnabled(level)) {
            return true;
        }

        Train train = cart.hasLink() ? Train.peek(cart) : null;

        return train == null || train != (other.hasLink() ? Train.peek(other) : null);
    }

    // How far one box must move to clear the other: the least of their three axis overlaps.
    private static double depth(AABB box, AABB otherBox) {
        double eastward = Math.min(box.maxX, otherBox.maxX) - Math.max(box.minX, otherBox.minX);
        double upward = Math.min(box.maxY, otherBox.maxY) - Math.max(box.minY, otherBox.minY);
        double southward = Math.min(box.maxZ, otherBox.maxZ) - Math.max(box.minZ, otherBox.minZ);

        return Math.min(eastward, Math.min(upward, southward));
    }

    // One train's line: which train, where its leading car is and how fast it has run, what the
    // placer is making of it, and how far its cars stand from where the track says they should.
    private static void writeTrain(ServerLevel level, long tick, Train train) {
        List<AbstractMinecart> cars = train.cars();
        AbstractMinecart leader = train.lastLeader();
        AbstractMinecart reference = leader == null ? cars.getFirst() : leader;
        TrainPlacer.Bail bail = train.lastBail();
        Line line = Line.meter()
                .count("tick", tick)
                .text("train", train.id())
                .count("cars", cars.size())
                .text("kinds", kinds(cars))
                .text("lead", leader == null ? "none" : Train.cartId(leader))
                .text("pos", xyz(reference.position()))
                .number("speed", speed(reference.getDeltaMovement()))
                .number("maxspeed", drainPeak(cars))
                .text("fuel", lowestFuel(cars))
                .text("rail", rail(level, reference.getCurrentBlockPosOrRailBelow()))
                .count("seated", carried(train, leader))
                .text("refusal", bail == null ? "none" : bail.name())
                .flag("packing", train.packing(tick))
                .flag("still", train.still(tick))
                .number("mass", train.mass(RailForces.forLevel(level), tick));

        spacing(line, cars);
        line.number("turn", train.turn());
        ribbonFit(line, train, leader);

        LOGGER.debug(line.toString());
    }

    // One loose cart's line: the part of a train's line one cart has anything to say about.
    private static void writeCart(ServerLevel level, long tick, AbstractMinecart cart) {
        LOGGER.debug(Line.meter()
                .count("tick", tick)
                .text("cart", Train.cartId(cart))
                .text("kind", kind(cart))
                .text("pos", xyz(cart.position()))
                .number("speed", speed(cart.getDeltaMovement()))
                .number("maxspeed", drainPeak(List.of(cart)))
                .text("rail", rail(level, cart.getCurrentBlockPosOrRailBelow()))
                .toString());
    }

    // How far each pair of cars stands from the spacing the coupling aims at, along the rails. A
    // train measuring no pair reads n/a, not 0.00, which would read as a train holding its pitch.
    private static void spacing(Line line, List<AbstractMinecart> cars) {
        double pitch = TrainCoupling.targetSpacing();
        double pathLimit = TrainCoupling.maxLinkDistance() * REACH_MULTIPLIER;
        double worstGap = 0.0;
        double totalGap = 0.0;
        int gapCount = 0;

        for (int i = 1; i < cars.size(); i++) {
            RailPath path = RailPath.between(cars.get(i - 1), cars.get(i), pathLimit);

            if (path == null) {
                continue;
            }

            double error = path.distance() - pitch;
            worstGap = Math.max(worstGap, Math.abs(error));
            totalGap += error;
            gapCount++;
        }

        line.text("pairs", gapCount + "/" + (cars.size() - 1));

        if (gapCount == 0) {
            line.text("gap_worst", "n/a").text("gap_mean", "n/a");
        } else {
            line.number("gap_worst", worstGap).number("gap_mean", totalGap / gapCount);
        }
    }

    // How far each car is from where the ribbon puts it, driving the offsets off the leading car,
    // and what the ribbon has been through. Closed on a seated train, which was placed at these
    // very arcs, so only the spacing judges one.
    private static void ribbonFit(Line line, Train train, @Nullable AbstractMinecart leader) {
        TrackRibbon ribbon = train.ribbon();
        Double leadArc = leader == null ? null : ribbon.arcOf(leader);

        line.count("nodes", ribbon.size());

        if (leader == null || leadArc == null) {
            line.text("span", "n/a").text("checked", "n/a").text("offset_worst", "n/a");
        } else {
            double pitch = TrainCoupling.targetSpacing();
            List<AbstractMinecart> behind = new ArrayList<>(train.cars());
            double worstOffset = 0.0;
            int checked = 0;

            if (behind.getLast() == leader) {
                Collections.reverse(behind);
            }

            // Which way along the ribbon the rest of the train lies, taken from where the second
            // car is: a trim run further at one end leaves the leader nearer one terminal.
            Double nextArc = ribbon.arcOf(behind.get(1));
            double step = nextArc == null
                    ? (leadArc - ribbon.nearArc() >= ribbon.farArc() - leadArc ? -pitch : pitch)
                    : Math.copySign(pitch, nextArc - leadArc);

            for (int i = 1; i < behind.size(); i++) {
                Vec3 predictedPosition = ribbon.positionAt(leadArc + i * step);

                if (predictedPosition == null) {
                    continue;
                }

                worstOffset = Math.max(worstOffset, predictedPosition.horizontal().distanceTo(
                        behind.get(i).position().horizontal()));
                checked++;
            }

            line.number("span", ribbon.farArc() - ribbon.nearArc())
                    .count("checked", checked)
                    .text("offset_worst", checked == 0 ? "n/a" : number(worstOffset));
        }

        // Resets stuck at zero is the point of recording track by arc; revisits climbing says the
        // cars report from the middle, which is expected and costs nothing.
        line.count("resets", ribbon.resets()).count("revisits", ribbon.revisits());
    }

    // How many cars the placer is holding: every car it seated, and the leading car of a train it
    // holds at all — per car, what TrainCoupling.carried answers, read off the last named leader.
    private static int carried(Train train, @Nullable AbstractMinecart leader) {
        boolean holding = train.holding();
        int heldCars = 0;

        for (AbstractMinecart car : train.cars()) {
            if (TrainPlacer.seated(car) || (car == leader && holding)) {
                heldCars++;
            }
        }

        return heldCars;
    }

    // The fastest any of these cars has moved in one tick since a line last drained it, clearing
    // their records for the next.
    private static double drainPeak(List<AbstractMinecart> cars) {
        double peak = 0.0;

        for (AbstractMinecart car : cars) {
            Double carPeak = PEAKS.remove(car);

            if (carPeak != null) {
                peak = Math.max(peak, carPeak);
            }
        }

        return peak;
    }

    // The least burn time any engine among these cars has left, in ticks; none without one.
    private static String lowestFuel(List<AbstractMinecart> cars) {
        int lowest = Integer.MAX_VALUE;

        for (AbstractMinecart car : cars) {
            if (car instanceof MinecartFurnace) {
                lowest = Math.min(lowest, car.polytrains$fuel());
            }
        }

        return lowest == Integer.MAX_VALUE ? "none" : Integer.toString(lowest);
    }

    // One letter per car, in order: furnace, chest, hopper, plain minecart, anything else.
    private static String kinds(List<AbstractMinecart> cars) {
        StringBuilder letters = new StringBuilder(cars.size());

        for (AbstractMinecart car : cars) {
            letters.append(kind(car));
        }

        return letters.toString();
    }

    // The letter one car's kind is written as.
    private static String kind(AbstractMinecart car) {
        if (car instanceof MinecartFurnace) {
            return "F";
        }

        if (car instanceof MinecartChest) {
            return "C";
        }

        if (car instanceof MinecartHopper) {
            return "H";
        }

        return car instanceof Minecart ? "M" : "X";
    }

    // The id of the train cart is in, read without rebuilding anything: its own id when it is
    // coupled to nothing, or when its train has not been rebuilt since its links last changed.
    private static String trainIdOf(AbstractMinecart cart) {
        Train train = cart.hasLink() ? Train.peek(cart) : null;

        return train == null ? Train.cartId(cart) : train.id();
    }

    // The block at pos by registry id, with (on) or (off) for one that carries a signal.
    private static String rail(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();

        return state.hasProperty(BlockStateProperties.POWERED)
                ? id + (state.getValue(BlockStateProperties.POWERED) ? "(on)" : "(off)")
                : id;
    }

    // A point or a movement as x,y,z.
    private static String xyz(Vec3 vector) {
        return number(vector.x()) + "," + number(vector.y()) + "," + number(vector.z());
    }

    // A figure to two decimals, finer than any of these is meaningful to, or in exponent form past
    // PLAIN_LIMIT; NaN and the infinities by name.
    private static String number(double value) {
        if (!Double.isFinite(value)) {
            return Double.toString(value);
        }

        return String.format(Locale.ROOT, Math.abs(value) < PLAIN_LIMIT ? "%.2f" : "%.3e", value);
    }

    // Drops every per-cart record, for DEBUG having been turned off: they would otherwise be
    // drained into the first lines written after it came back on, long out of date.
    private static void forgetAll() {
        if (!PEAKS.isEmpty()) {
            PEAKS.clear();
        }

        if (!OVER.isEmpty()) {
            OVER.clear();
        }

        if (!CLIPPING.isEmpty()) {
            CLIPPING.clear();
        }
    }

    // Two cars of different trains, the first the one whose UUID sorts first.
    private record Clip(AbstractMinecart cart, AbstractMinecart other) {}

    // One line in the format every meter and event line shares: a leading word, then key=value
    // tokens whose values hold no spaces, then free prose.
    private static final class Line {
        private final StringBuilder buffer;

        // Starts a line with its leading word.
        private Line(String lead) {
            buffer = new StringBuilder(lead);
        }

        // A meter line, led by the bare word.
        private static Line meter() {
            return new Line("meter");
        }

        // An event line, led by a token naming the event.
        private static Line event(String kind) {
            return new Line("event=" + kind);
        }

        // Adds a token whose value is already written out.
        private Line text(String key, String value) {
            buffer.append(' ').append(key).append('=').append(value);
            return this;
        }

        // Adds a token holding a figure.
        private Line number(String key, double value) {
            return text(key, TrainMeter.number(value));
        }

        // Adds a token holding a whole count.
        private Line count(String key, long value) {
            return text(key, Long.toString(value));
        }

        // Adds a token holding true or false.
        private Line flag(String key, boolean value) {
            return text(key, Boolean.toString(value));
        }

        // Ends the tokens with free prose, its '=' written ':' so no word of it reads as a token.
        private Line prose(String words) {
            buffer.append(' ').append(words.replace('=', ':'));
            return this;
        }

        @Override
        public String toString() {
            return buffer.toString();
        }
    }
}
