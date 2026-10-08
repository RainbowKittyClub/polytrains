package club.rainbowkitty.polytrains.rail;

import java.util.Arrays;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Predicate;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.ints.IntArrayList;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.NewMinecartBehavior;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.polytrains.block.SwitchedRailBlock;
import club.rainbowkitty.polytrains.rule.ModGameRules;
import club.rainbowkitty.polytrains.train.TrainCoupling;

/**
 * The track a train is running on, as a polyline with cumulative arc length: one node per rail
 * block its leading car has crossed, in the order it crossed them. Each block is the chord between
 * its two edge midpoints, the same approximation {@link RailPath} measures links with.
 *
 * <p>Arc length is horizontal, because that is the currency vanilla charges movement in — a budget
 * handed to the stepper and an offset along this ribbon have to be the same unit or the two cannot
 * be traded. Positions come back in three dimensions even so: a car has to be put on the rail, and
 * the only other way to find that height is to ask the adjustment vanilla keeps for rail
 * transitions, which is wrong on most grades and turns the car while it is at it. So the two units
 * sit side by side here — arc is flat, geometry is not — and every projection back onto the ribbon
 * drops the height before measuring.
 *
 * <p>Derived state, never saved: a ribbon is rebuilt by running, and a train that loses its own
 * builds another. Nothing here moves a cart.
 */
public final class TrackRibbon {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Hands every arc numbering, on every ribbon, a number of its own. Atomic because a ribbon
    // is built on whichever thread a cart ticks on.
    private static final AtomicInteger EPOCH_COUNTER = new AtomicInteger();

    // Bumped whenever a switch is laid or changes shape, so a ribbon looks for thrown switches only
    // after one has. Shared by every level: counting another level's switch costs one extra walk.
    private static final AtomicInteger SWITCH_CHANGES = new AtomicInteger();

    // The most ticks a ribbon goes between walks, for a switch written with block updates off.
    private static final int FULL_WALK_TICKS = 20;

    // Below this an arc offset or a chord length is treated as zero.
    private static final double EPSILON = 1.0E-5;

    // How far from where the leading car was a block may be recorded and still be that block
    // rather than the same place a lap on. One node's worth of track, with slack.
    private static final double CONTINUITY = 1.5;

    // The turn a right angle is worth, so that a bend is counted in right angles.
    private static final double RIGHT_ANGLE = Math.PI / 2.0;

    // One rail block's worth of track, in ribbon order rather than in travel order. Arc is the
    // cumulative length at entry, so a node covers [arc, arc + span].
    private record Node(BlockPos block, Vec3 entry, Vec3 exit, double arc, double span) {}

    // The block past one end of the ribbon that its track would carry on into, and the way out.
    private record Beyond(BlockPos pos, Vec3 outwardDirection) {}

    // The rail blocks and crossing points this train has traversed, in order.
    private final NodeRing nodes = new NodeRing();

    // Where along this ribbon the leading car was last recorded. Held rather than looked back up:
    // a circuit shorter than the ribbon records every one of its blocks twice, a lap apart, and the
    // two are indistinguishable by the block a car stands on. Which of them a car is really on is
    // only answerable from where it was a moment ago.
    private double leaderArc;

    // Set while the leading car stands off every block recorded here, so the block it comes back
    // down on is judged as a landing, which can be on another pass of the same track.
    private boolean leaderLost;

    // Set while blocksBetween walks the nodes: its action can throw a switch, and so cut them.
    private boolean walking;

    // Kept for the meter, and monotonic over this ribbon's whole life rather than per attempt —
    // the question they answer is whether resets have stopped, which a counter that resets cannot.
    private int resets;
    private int revisits;

    // Which arc numbering this ribbon is on. Handed out from a counter shared by every ribbon,
    // so state a car holds against one numbering is never mistaken for state against another —
    // which the reset count alone cannot manage, two ribbons on their first both answering it.
    private int arcEpoch = EPOCH_COUNTER.incrementAndGet();

    // The switch count and tick of the last walk for thrown switches, and whether it found one
    // under the train, which has to be cut the tick the train leaves it. Not copied: -1 walks.
    private int walkedChanges = -1;
    private long walkedAt;
    private boolean thrownWithin;

    // The end nodes growth last failed off while the train stood, against the switch count and
    // tick then. Keyed by node, so anything that changes an end asks again.
    private @Nullable Node nearStop;
    private @Nullable Node farStop;
    private int stopsChanges = -1;
    private long stopsAt;

    /** A ribbon with no track on it. */
    public TrackRibbon() {
    }

    /**
     * A ribbon holding the same track as {@code other}, for a train being split off the one that
     * recorded it. Both halves are still on those rails, and one object between them is not
     * sharing track but fighting over it: each tick, whichever leader records second reads the
     * other's track as a discontinuity and throws it away, so neither is ever seated again.
     *
     * <p>The arc numbering comes across with the track, because it is the same numbering and not
     * merely a like one. Anything held against it — a car's leading end, a seat, which way round
     * a train lies — still means what it meant.
     */
    public TrackRibbon(TrackRibbon other) {
        nodes.addAll(other.nodes);
        // Not leaderLost: a half is often led by another car, and the next reading sets it again.
        leaderArc = other.leaderArc;
        resets = other.resets;
        revisits = other.revisits;
        arcEpoch = other.arcEpoch;
    }

    /** Whether this ribbon has any track recorded yet. */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** How many rail blocks are recorded. */
    public int size() {
        return nodes.size();
    }

    /** The arc length at the near end of the recorded track. */
    public double nearArc() {
        return nodes.isEmpty() ? 0.0 : nodes.getFirst().arc();
    }

    /** The arc length at the far end of the recorded track. */
    public double farArc() {
        Node last = nodes.peekLast();
        return last == null ? 0.0 : last.arc() + last.span();
    }

    /** How many times this ribbon has been thrown away and restarted from one block. */
    public int resets() {
        return resets;
    }

    /**
     * Which arc numbering this ribbon is on. Anything remembered in terms of an arc — how far
     * along it something sits, or which way along it something points — is only good for the
     * numbering it was taken against, and every reset starts a new one.
     *
     * <p>Two ribbons answer the same only when one was copied from the other — shared numbering,
     * not merely alike.
     */
    public int arcEpoch() {
        return arcEpoch;
    }

    /** How many times a car has reported track this ribbon already holds, away from both ends. */
    public int revisits() {
        return revisits;
    }

    /**
     * Where along this ribbon the leading car was last recorded or read, without reading it again.
     * Meaningless while the ribbon is empty.
     */
    public double leaderArc() {
        return leaderArc;
    }

    /**
     * Records that a car crossed the rail block at {@code pos} travelling along {@code movement},
     * extending whichever end of the ribbon that block continues. Track already recorded is
     * ignored: whichever car reports it, it is track this ribbon knows, and discarding twenty good
     * nodes over one unremarkable block is the more expensive mistake by far. Only a block the
     * ribbon has never seen is a discontinuity — a reversal onto untracked rail, a teleport, a
     * reload — and that restarts the ribbon rather than inventing track between the two. So is a
     * landing behind where the leading car left the ribbon, on track it recorded on another pass.
     */
    public void record(Level level, BlockPos pos, Vec3 movement) {
        if (!nodes.isEmpty()) {
            // Where the leading car just was is the only thing that separates a lap later from a
            // reversal, so it is asked before either end of the ribbon is.
            Double nearArc = occurrenceNear(pos, leaderArc);

            if (nearArc != null) {
                revisits++;
                leaderArc = nearArc;
                leaderLost = false;
                return;
            }

            // New track extends the end the car is heading off, not whichever end this block sits
            // next to — on a closed circuit the two ends are the same place and that is both.
            Vec3 tangent = tangentAt(leaderArc);
            boolean ahead = tangent == null || movement.dot(tangent) >= 0.0;

            if (ahead && adjacent(nodes.getLast().block(), pos)) {
                append(level, pos, movement);
                leaderArc = nodes.getLast().arc();
                leaderLost = false;
                return;
            }

            if (!ahead && adjacent(nodes.getFirst().block(), pos)) {
                prepend(level, pos, movement);
                leaderArc = nodes.getFirst().arc();
                leaderLost = false;
                return;
            }

            if (holds(pos)) {
                double heldArc = nearestOccurrence(pos, leaderArc);

                if (!landsBehind(heldArc, movement)) {
                    revisits++;
                    leaderArc = heldArc;
                    leaderLost = false;
                    return;
                }
            }

            resets++;
            arcEpoch = EPOCH_COUNTER.incrementAndGet();
            LOGGER.debug("ribbon reset at {}: no step along its {} nodes, running {} to {}",
                    pos, nodes.size(), nodes.getFirst().block(), nodes.getLast().block());
            nodes.clear();
        }

        leaderLost = false;

        RailPath.Crossing crossing = RailPath.crossing(level, pos, movement);

        if (crossing != null) {
            nodes.addLast(new Node(pos, crossing.entry(), crossing.exit(), 0.0, crossing.span()));
            leaderArc = 0.0;
        }
    }

    /**
     * Where the leading car sits along this ribbon, or null if the block it stands on is not one
     * this ribbon records, or if the car has come back down behind where it left this ribbon.
     *
     * <p>The same question {@link #arcOf} answers, settled differently where a block appears more
     * than once: by which occurrence the car was last recorded on rather than by which is nearest
     * in space. On a circuit the two occurrences are a lap apart but can be a couple of blocks
     * from each other, so the nearest in space is a coin toss, and losing it puts the whole train
     * a lap from where it belongs.
     *
     * <p>Answered off the block the car stands on where it can be, and off the track running
     * nearest the car where it cannot. Standing on a block is the stricter reading and the one
     * that tells two laps apart, but it is also all-or-nothing: a car knocked a foot sideways by a
     * collision stands on the next block along and reads as having left a ribbon that describes
     * its track exactly. That answer costs the whole train, so it is worth a second question.
     */
    public @Nullable Double arcOfLeader(AbstractMinecart leader) {
        Double best = arcNear(leader, leaderArc);

        // Not yet on this ribbon: record starts it over from the block the car comes down on.
        if (best != null && landsBehind(best, leader.getDeltaMovement().horizontal())) {
            best = null;
        }

        // The clamped projection pins a leader leaving an end at that end, so one in the air past
        // it is off this ribbon, not a car's width later.
        if (best != null && leavesEnd(leader, best)) {
            best = null;
        }

        leaderLost = best == null;

        if (best != null) {
            leaderArc = best;
        }

        return best;
    }

    // Whether a leading car that has been off this ribbon comes back at arc behind where it left
    // it, against the way it is going: onto another pass, where its train lies the other way round.
    private boolean landsBehind(double arc, Vec3 movement) {
        Vec3 tangent = tangentAt(leaderArc);

        if (!leaderLost || tangent == null || Math.abs(arc - leaderArc) <= CONTINUITY) {
            return false;
        }

        double travel = movement.dot(tangent);

        return Math.abs(travel) > EPSILON && Math.signum(travel) != Math.signum(arc - leaderArc);
    }

    // Whether a leading car read at arc has flown off the end it sits at: in the air under the
    // new movement, past that end and going on out. A car resting on the lip still reads there.
    private boolean leavesEnd(AbstractMinecart leader, double arc) {
        if (!(leader.getBehavior() instanceof NewMinecartBehavior)
                || leader.isOnRails() || leader.onGround()) {
            return false;
        }

        boolean farEnd = Math.abs(arc - farArc()) <= EPSILON;

        if (!farEnd && Math.abs(arc - nearArc()) > EPSILON) {
            return false;
        }

        Node end = farEnd ? nodes.getLast() : nodes.getFirst();
        Vec3 heading = heading(end);

        return heading != null
                && leader.getDeltaMovement().horizontal().dot(heading) * (farEnd ? 1.0 : -1.0)
                        > EPSILON
                && pastEnd(leader.position(), farEnd) > EPSILON;
    }

    /**
     * How far {@code position} lies out past the far or near end of this ribbon, in the ground
     * plane along that end's chord: negative short of it, NaN for an empty ribbon or an end whose
     * chord has no length. Reads no world.
     */
    public double pastEnd(Vec3 position, boolean farEnd) {
        if (nodes.isEmpty()) {
            return Double.NaN;
        }

        Node end = farEnd ? nodes.getLast() : nodes.getFirst();
        Vec3 heading = heading(end);

        if (heading == null) {
            return Double.NaN;
        }

        Vec3 endPoint = farEnd ? end.exit() : end.entry();
        Vec3 outward = farEnd ? heading : heading.reverse();

        return position.horizontal().subtract(endPoint.horizontal()).dot(outward);
    }

    /**
     * Where {@code car} sits along this ribbon reckoned from arc {@code near}, answered off the
     * track running nearest it where the block it stands on is not one this ribbon records, or
     * null if no recorded track passes within its own width of it.
     *
     * <p>{@link #arcOfLeader}'s two readings without moving the leading car's cursor. For a cart of
     * another train: whether it stands on this track at all, and where along it.
     */
    public @Nullable Double arcNear(AbstractMinecart car, double near) {
        Double best = arcOf(car, near);

        return best != null ? best : nearestTrack(car.position(), car.getBbWidth(), near);
    }

    // Where along this ribbon the occurrence of pos nearest arc near puts a car standing at
    // position, or null if this ribbon does not record that block at all.
    private @Nullable Double occurrenceUnder(BlockPos pos, Vec3 position, double near) {
        Double best = null;
        double nearest = Double.MAX_VALUE;
        IntArrayList at = nodes.seqsAt(pos);

        for (int k = 0; k < at.size(); k++) {
            Node node = nodes.bySeq(at.getInt(k));
            double arc = node.arc() + chordFraction(node, position) * node.span();
            double arcGap = Math.abs(arc - near);

            if (arcGap < nearest) {
                nearest = arcGap;
                best = arc;
            }
        }

        return best;
    }

    // Where the recorded track passing nearest a car standing off its own rail block would put it,
    // or null if none passes within reach of it.
    //
    // Measured in three dimensions, so track under a bridge cannot answer for a car on it, and
    // bounded by the car's own width — which is just under a block, so a car sitting squarely on a
    // parallel line one block over falls outside and is honestly reported as off this ribbon.
    //
    // Ranked as arcOf ranks: nearest in space, and where two are the same point — which the two
    // occurrences of a lap are — whichever lies nearer arc near.
    private @Nullable Double nearestTrack(Vec3 position, double reach, double near) {
        double limit = reach * reach;
        Double best = null;
        double nearest = Double.MAX_VALUE;
        double apart = Double.MAX_VALUE;

        for (Node node : nodes) {
            double fraction = chordFraction(node, position);
            double distanceSqr = node.entry().lerp(node.exit(), fraction).distanceToSqr(position);

            if (distanceSqr > limit) {
                continue;
            }

            double arc = node.arc() + fraction * node.span();
            double lap = Math.abs(arc - near);

            if (distanceSqr < nearest - EPSILON
                    || (distanceSqr < nearest + EPSILON && lap < apart)) {
                nearest = distanceSqr;
                apart = lap;
                best = arc;
            }
        }

        return best;
    }

    // The arc of an occurrence of pos within a block of where the leading car already was, or null
    // if this ribbon holds none. A record is made once per block that car crosses, so a genuine
    // continuation is always that close and anything further off is a different lap.
    private @Nullable Double occurrenceNear(BlockPos pos, double from) {
        IntArrayList at = nodes.seqsAt(pos);

        for (int k = 0; k < at.size(); k++) {
            Node node = nodes.bySeq(at.getInt(k));

            if (node.arc() + node.span() >= from - CONTINUITY
                    && node.arc() <= from + CONTINUITY) {
                return node.arc();
            }
        }

        return null;
    }

    // Of the occurrences of a block this ribbon holds, the arc of whichever lies closest to where
    // the leading car already was. Continuity is the only thing that tells two laps apart.
    private double nearestOccurrence(BlockPos pos, double from) {
        double best = from;
        double nearest = Double.MAX_VALUE;
        IntArrayList at = nodes.seqsAt(pos);

        for (int k = 0; k < at.size(); k++) {
            Node node = nodes.bySeq(at.getInt(k));

            if (Math.abs(node.arc() - from) < nearest) {
                nearest = Math.abs(node.arc() - from);
                best = node.arc();
            }
        }

        return best;
    }

    /**
     * Where the track is at arc length {@code arc}, at the height the rail runs there, or null if
     * that is off the recorded ends. This is where a car seated at {@code arc} belongs outright.
     */
    public @Nullable Vec3 positionAt(double arc) {
        Node node = nodeAt(arc);

        if (node == null) {
            return null;
        }

        double fraction = node.span() < EPSILON ? 0.0 : (arc - node.arc()) / node.span();
        return node.entry().lerp(node.exit(), fraction);
    }

    /**
     * The rail block the track at arc length {@code arc} runs through, or null if that is off the
     * recorded ends. What a car seated at that arc stands on, and so what it must be snapped to.
     */
    public @Nullable BlockPos blockAt(double arc) {
        Node node = nodeAt(arc);
        return node == null ? null : node.block();
    }

    /**
     * Which way a cart at arc length {@code arc} is travelling, or null if that is off the ends or
     * the block's two edge midpoints coincide.
     *
     * <p>The node's own chord, not the direction it exits by. This whole ribbon is built of chords:
     * {@link #positionAt} lerps a car between the two edge midpoints, {@code span} is how far apart
     * they are, and {@link #turnBetween} measures the joints between them. A corner's two edges are
     * square to each other, so its chord runs at 45 degrees to either — a car placed along that
     * chord and aimed along the exit is aimed 45 degrees off the way it is going, once per corner.
     */
    public @Nullable Vec3 tangentAt(double arc) {
        Node node = nodeAt(arc);
        return node == null ? null : heading(node);
    }

    /**
     * How far the track between arcs {@code from} and {@code to} changes direction, in right
     * angles. A 90° corner is worth one, and the 45° kink onto a diagonal half of one.
     *
     * <p>The turn is in the joints between one block's chord and the next, not in the blocks
     * themselves. A diagonal is built of corner rails and turns nowhere: entering one from the west
     * and leaving north gives the same chord as entering the next from the south and leaving east,
     * so the whole run is one straight line at 45°, which is what a cart riding the chords actually
     * follows. Track off either recorded end counts for nothing.
     */
    public double turnBetween(double from, double to) {
        double low = Math.min(from, to);
        double high = Math.max(from, to);
        double turn = 0.0;
        Vec3 previous = null;

        for (Node node : nodes) {
            Vec3 chord = heading(node);

            if (chord == null) {
                continue;
            }

            // The joint sits at the node's own entry, so a node is charged for how it meets the one
            // before it. Half-open at the low end: a joint already crossed is not crossed again.
            if (previous != null && node.arc() > low && node.arc() <= high) {
                turn += Math.acos(Math.clamp(previous.dot(chord), -1.0, 1.0)) / RIGHT_ANGLE;
            }

            previous = chord;
        }

        return turn;
    }

    /**
     * Where {@code car} sits along this ribbon, or null if the block it stands on is not one this
     * ribbon has recorded. Projected onto that block's chord, so it answers between nodes rather
     * than in whole blocks.
     *
     * <p>A ribbon can hold the same block twice — a circuit, or a reversal that grew back over its
     * own track. Where those two put the car in different places the nearer wins; where they put it
     * in the same place, which they do for a lap, the one on the leading car's lap does.
     */
    public @Nullable Double arcOf(AbstractMinecart car) {
        BlockPos pos = car.getCurrentBlockPosOrRailBelow();
        Vec3 position = car.position().horizontal();
        Double best = null;
        double nearest = Double.MAX_VALUE;
        double apart = Double.MAX_VALUE;
        IntArrayList at = nodes.seqsAt(pos);

        for (int k = 0; k < at.size(); k++) {
            Node node = nodes.bySeq(at.getInt(k));
            double fraction = chordFraction(node, position);
            double arc = node.arc() + fraction * node.span();
            double distanceSqr = node.entry().lerp(node.exit(), fraction)
                    .horizontal().distanceToSqr(position);
            double lap = Math.abs(arc - leaderArc);

            // A lap's two occurrences are one point, so they score identically and the ranking
            // falls through to which of them the train is actually running on.
            if (distanceSqr < nearest - EPSILON
                    || (distanceSqr < nearest + EPSILON && lap < apart)) {
                nearest = distanceSqr;
                apart = lap;
                best = arc;
            }
        }

        return best;
    }

    /**
     * Where {@code car} sits along this ribbon reckoned from arc {@code near}, or null if the block
     * it stands on is not one this ribbon records.
     *
     * <p>The same question {@link #arcOf(AbstractMinecart)} answers, settled differently where a
     * block appears more than once: by which occurrence lies nearer an arc the caller names rather
     * than nearer the leading car. Asking about a car from that car's own arc keeps the answer on
     * that car's lap, where the leading car's cursor would put a car most of a lap behind onto the
     * leading car's lap instead.
     */
    public @Nullable Double arcOf(AbstractMinecart car, double near) {
        return occurrenceUnder(car.getCurrentBlockPosOrRailBelow(), car.position(), near);
    }

    /**
     * Whether the rail at arc {@code arc} no longer joins the two edges this ribbon recorded
     * across it.
     *
     * <p>Only a switch can answer yes. A seated car is placed on recorded track and does no rail
     * reading of its own, so a switch thrown behind the leading car is otherwise ignored entirely
     * and the cars behind it are drawn across the rails rather than along them.
     */
    public boolean diverted(Level level, double arc) {
        Node node = nodeAt(arc);

        return node != null && thrown(level, node);
    }

    /**
     * Forgets the track beyond {@code [low, high]} from the first switch each way that no longer
     * joins the edges recorded across it, so that growth reads that switch again as it now lies.
     *
     * <p>A switch thrown while no car lies across it can still be recorded past either end of the
     * train, and a train reversing onto it would be seated along a curve the rails no longer have.
     * Track between the two arcs is the train's own, which {@link #diverted} answers for instead.
     * Both arcs are to be ones cars stand at, so the track under them always stays.
     */
    public void forgetThrown(Level level, double low, double high) {
        // Arcs wholly off this ribbon say nothing about which of its track is the train's, and a
        // cut mid-walk is left to the train's next seating, which asks again.
        if (walking || nodes.isEmpty() || high < nearArc() - EPSILON || low > farArc() + EPSILON) {
            return;
        }

        int changes = SWITCH_CHANGES.get();
        long now = level.getGameTime();

        // A switch recorded unthrown stays so until one changes, which onPlace counts.
        if (!thrownWithin && changes == walkedChanges && now - walkedAt < FULL_WALK_TICKS) {
            return;
        }

        walkedChanges = changes;
        walkedAt = now;

        Node farCut = firstThrown(level, nodes.iterator(), node -> node.arc() > high + EPSILON);
        Node nearCut = firstThrown(level, nodes.descendingIterator(),
                node -> node.arc() + node.span() < low - EPSILON);

        // Each loop drops the nodes past the switch and then the switch itself.
        while (farCut != null && nodes.removeLast() != farCut) {
            continue;
        }

        while (nearCut != null && nodes.removeFirst() != nearCut) {
            continue;
        }

        thrownWithin = anyThrownWithin(level, low, high);
    }

    /**
     * Counts a switch laid or changed in shape, so every ribbon's next {@link #forgetThrown}
     * reads its track again rather than trusting the last reading.
     */
    public static void switchChanged() {
        SWITCH_CHANGES.incrementAndGet();
    }

    // Whether any node on the train's own track between low and high is a thrown switch.
    private boolean anyThrownWithin(Level level, double low, double high) {
        for (int i = nodes.firstIndex(node -> node.arc() + node.span() >= low - EPSILON);
                i < nodes.size() && nodes.get(i).arc() <= high + EPSILON; i++) {
            if (thrown(level, nodes.get(i))) {
                return true;
            }
        }

        return false;
    }

    // Walking outward, the first node past the train that is a thrown switch, or null. Only nodes
    // past the train have their block read.
    private static @Nullable Node firstThrown(
            Level level, Iterator<Node> outward, Predicate<Node> past) {
        while (outward.hasNext()) {
            Node node = outward.next();

            if (past.test(node) && thrown(level, node)) {
                return node;
            }
        }

        return null;
    }

    // Whether node is a switch that no longer joins the two edges recorded across it.
    private static boolean thrown(Level level, Node node) {
        // A node in an unloaded chunk is not thrown: nothing can throw a switch there.
        BlockState state = RailPath.loadedState(level, node.block());

        if (state == null || !(state.getBlock() instanceof SwitchedRailBlock)) {
            return false;
        }

        Vec3 centre = Vec3.atCenterOf(node.block()).horizontal();
        Direction inEdge = edge(node.entry(), centre);
        Direction outEdge = edge(node.exit(), centre);

        if (inEdge == null || outEdge == null) {
            return false;
        }

        // The shape the switch holds, not the one its signal asks for: a signal that changes under
        // a train is recorded at once, while the shape it would lay waits for the train to clear.
        var exits = AbstractMinecart.exits(state.getValue(SwitchedRailBlock.SHAPE));

        return !(joins(exits.getFirst(), inEdge) && joins(exits.getSecond(), outEdge))
                && !(joins(exits.getFirst(), outEdge) && joins(exits.getSecond(), inEdge));
    }

    // Which edge of its block a recorded edge midpoint lies on, or null if it reads as neither.
    private static @Nullable Direction edge(Vec3 point, Vec3 centre) {
        Vec3 offsetDirection = point.horizontal().subtract(centre);

        return offsetDirection.lengthSqr() < EPSILON ? null
                : Direction.getApproximateNearest(offsetDirection.x, 0.0, offsetDirection.z);
    }

    // Whether a rail shape's exit marker points along an edge. Its Y marks the high end of a slope
    // rather than an offset, so only the horizontal part names a direction.
    private static boolean joins(Vec3i exitMarker, Direction edge) {
        return exitMarker.getX() == edge.getStepX() && exitMarker.getZ() == edge.getStepZ();
    }

    /** Whether arc length {@code arc} lies on the recorded track rather than off either end. */
    public boolean reaches(double arc) {
        return !nodes.isEmpty() && arc >= nearArc() - EPSILON && arc <= farArc() + EPSILON;
    }

    /**
     * Whether the track ends at this ribbon's far or near end against something solid: the block
     * past the end node is not rail and has a collision shape, or is a switch something holds laid
     * against the edge the track enters it by, which stops a moving cart at that edge too. A car
     * seated closer than half its length to that end would be put inside it, or through the switch.
     */
    public boolean blockedBeyond(Level level, boolean farEnd) {
        Beyond beyond = beyondEnd(level, farEnd);

        if (beyond == null) {
            return false;
        }

        BlockState past = RailPath.loadedState(level, beyond.pos());

        // Unloaded is not blocked: no ticking car can be within reach of it.
        if (past == null) {
            return false;
        }

        return !enterable(past, beyond.outwardDirection())
                        && SwitchedRailBlock.held(level, beyond.pos())
                || !BaseRailBlock.isRail(past)
                        && !past.getCollisionShape(level, beyond.pos()).isEmpty();
    }

    /**
     * Asks the switch past this ribbon's far or near end, if one is laid against track running out
     * through that end, to lie open to it for {@code car}, which is about to run onto it. A switch
     * something holds as it is stays so, and this ribbon's growth then stops at it as before.
     */
    public void openSwitchBeyond(ServerLevel level, boolean farEnd, AbstractMinecart car) {
        Beyond beyond = beyondEnd(level, farEnd);
        BlockState state = beyond == null ? null : RailPath.loadedState(level, beyond.pos());

        if (state != null && !enterable(state, beyond.outwardDirection())) {
            SwitchedRailBlock.steerOnto(level, beyond.pos(),
                    Direction.getApproximateNearest(beyond.outwardDirection()), car);
        }
    }

    /**
     * Whether this ribbon records {@code pos} anywhere between arcs {@code from} and {@code to}.
     * Asking within a window rather than of the whole ribbon is the difference between a train
     * being on a block and merely having recorded it: on a circuit shorter than the ribbon's own
     * reach, every block of the loop is recorded at all times.
     */
    public boolean covers(double from, double to, BlockPos pos) {
        double low = Math.min(from, to);
        double high = Math.max(from, to);
        IntArrayList at = nodes.seqsAt(pos);

        for (int k = 0; k < at.size(); k++) {
            Node node = nodes.bySeq(at.getInt(k));

            if (node.arc() + node.span() >= low - EPSILON
                    && node.arc() <= high + EPSILON) {
                return true;
            }
        }

        return false;
    }

    /**
     * Grows the ribbon off whichever of its ends falls short of {@code [from, to]}, by walking the
     * track there instead of waiting for a car to drive it.
     *
     * <p>The one place track is taken on trust rather than from a car that crossed it. A ribbon
     * begins at the single block its leading car has just crossed, so a train that has only now
     * been linked, grown or reset has nowhere to seat the cars it already has — and it is standing
     * on that track the whole time it has none recorded. Walking stops at whatever the track does:
     * a dead end, or a block that is not rail.
     *
     * <p>An end that would not grow while the train stood is not walked again until it moves, a
     * switch changes, or {@value #FULL_WALK_TICKS} ticks pass.
     */
    public void extendTo(Level level, double from, double to, boolean still) {
        int changes = SWITCH_CHANGES.get();
        long now = level.getGameTime();

        if (!still || changes != stopsChanges || now - stopsAt >= FULL_WALK_TICKS) {
            nearStop = null;
            farStop = null;
            stopsChanges = changes;
            stopsAt = now;
        }

        boolean nearGrowing = true;
        boolean farGrowing = true;

        // One block off each end in turn: grown one after the other, the near end could spend the
        // whole cap before the far end had any.
        while (nearGrowing || farGrowing) {
            nearGrowing = nearGrowing && nearArc() > from + EPSILON && growEnd(level, true);
            farGrowing = farGrowing && farArc() < to - EPSILON && growEnd(level, false);
        }
    }

    /**
     * Runs {@code action} for every rail block this ribbon records between arc {@code from} and arc
     * {@code to}, in no particular order. What a car that moved between those two arcs crossed, and
     * so what it owes the effects of: a placed car covers several blocks a tick and is only ever
     * standing on the last of them.
     */
    public void blocksBetween(double from, double to, Consumer<BlockPos> action) {
        double low = Math.min(from, to);
        double high = Math.max(from, to);

        walking = true;

        try {
            for (int i = nodes.firstIndex(node -> node.arc() + node.span() >= low - EPSILON);
                    i < nodes.size() && !(nodes.get(i).arc() > high + EPSILON); i++) {
                Node node = nodes.get(i);

                if (node.arc() + node.span() >= low - EPSILON && node.arc() <= high + EPSILON) {
                    action.accept(node.block());
                }
            }
        } finally {
            walking = false;
        }
    }

    /**
     * Drops the track outside {@code [keep, until]}, which must span the whole train with room to
     * spare. A circuit shorter than the train re-enters blocks its own tail has not left, so
     * trimming by anything but the train's own arc span walks the last car off the end.
     */
    public void trim(double keep, double until) {
        while (nodes.size() > 1 && nodes.getFirst().arc() + nodes.getFirst().span() < keep) {
            nodes.removeFirst();
        }

        while (nodes.size() > 1 && nodes.getLast().arc() > until) {
            nodes.removeLast();
        }
    }

    /**
     * Drops recorded track off either end whose rail has since been taken up, so the ribbon ends
     * where the track now does. Only the ends: a seat on a gap mid-ribbon already finds no rail.
     */
    public void dropTakenUpEnds(Level level) {
        while (nodes.size() > 1 && takenUp(level, nodes.getLast().block())) {
            nodes.removeLast();
        }

        while (nodes.size() > 1 && takenUp(level, nodes.getFirst().block())) {
            nodes.removeFirst();
        }
    }

    /**
     * The arc of the first joint between recorded blocks, within {@code reach} of {@code fromArc}
     * going {@code outwardSign}, that a car can no longer cross, or null where the track holds.
     * Track recorded while open can since have been cut short of either end of the ribbon.
     */
    public @Nullable Double barredJoint(
            Level level, double fromArc, double reach, double outwardSign) {
        Iterator<Node> walk = outwardSign > 0.0 ? nodes.iterator() : nodes.descendingIterator();
        Node inner = null;

        while (walk.hasNext()) {
            Node outer = walk.next();

            if (inner != null) {
                double jointArc = outwardSign > 0.0 ? outer.arc() : inner.arc();
                double ahead = (jointArc - fromArc) * outwardSign;

                if (ahead > reach) {
                    return null;
                }

                if (ahead >= 0.0 && barred(level, inner.block(), outer.block())) {
                    return jointArc;
                }
            }

            inner = outer;
        }

        return null;
    }

    /** Whether this ribbon records {@code pos} anywhere along it. */
    public boolean holds(BlockPos pos) {
        return !nodes.seqsAt(pos).isEmpty();
    }

    // How far along a node's chord a point projects, clamped to the block. Flat on both sides: arc
    // is a horizontal measure, and a grade would otherwise lengthen the chord a car is measured
    // against without lengthening the arc it is measured into.
    private static double chordFraction(Node node, Vec3 position) {
        Vec3 chord = node.exit().subtract(node.entry()).horizontal();
        double length = chord.length();

        if (length < EPSILON) {
            return 0.0;
        }

        return Math.clamp(
                position.horizontal().subtract(node.entry().horizontal()).dot(chord)
                        / (length * length),
                0.0, 1.0);
    }

    /**
     * How much of the track at {@code arc} lies flat: one on the level, and about seven tenths on
     * the steepest rail there is. One outside the ribbon, which measures nothing.
     *
     * <p>Arc is horizontal throughout, so a distance meant along the rails is not one in arc until
     * it has been scaled by this. Without it a spacing holds its ground plan up a hill and stands
     * the cars half again as far apart as they are on the flat.
     */
    public double flatnessAt(double arc) {
        Node node = nodeAt(arc);

        return node == null ? 1.0 : flatness(node);
    }

    /**
     * How much arc it takes to cover {@code trackDistance} of track measured along the rails,
     * starting at {@code arc} and running the way {@code sign} points. The same number on the
     * level, and less than {@code trackDistance} wherever the track between the two ends is
     * tilted.
     *
     * <p>Walked node by node rather than scaled by one reading, because the thing being measured
     * is usually longer than the thing that would be read. A gap standing one car at the foot of a
     * hill and the next on it is part level and part not, and taking all of it at either rate
     * moves a car that has not reached the grade yet — or leaves one already on it short.
     *
     * <p>Track the ribbon does not hold is taken flat, an unrecorded stretch being worth no
     * particular grade. The caller finds out it asked past the end through {@link #reaches}.
     */
    public double arcAlong(double arc, double trackDistance, double sign) {
        if (!(trackDistance > EPSILON)) {
            return 0.0;
        }

        // Begun past the nodes the room test below would skip before anything is spent: arcs rise
        // with index, so those are a run at the walk's start.
        boolean back = sign < 0.0;
        int step = back ? -1 : 1;
        int i;

        if (back) {
            i = nodes.firstIndex(node -> Math.min(arc - node.arc(), node.span()) <= EPSILON) - 1;
        } else {
            i = nodes.firstIndex(node -> !(Math.min(node.arc() + node.span() - arc, node.span())
                    <= EPSILON));
        }
        double cursorArc = arc;
        double left = trackDistance;

        for (; i >= 0 && i < nodes.size() && left > EPSILON; i += step) {
            Node node = nodes.get(i);
            double low = node.arc();
            double high = low + node.span();
            double room = Math.min(sign < 0.0 ? cursorArc - low : high - cursorArc, node.span());

            // Track on the far side of where this started, which the walk reaches only after
            // spending its way there.
            if (room <= EPSILON) {
                continue;
            }

            double flatFraction = flatness(node);
            double takes = left * flatFraction;

            if (takes <= room) {
                return Math.abs(cursorArc + sign * takes - arc);
            }

            cursorArc += sign * room;
            left -= room / flatFraction;
        }

        return Math.abs(cursorArc + sign * left - arc);
    }

    /**
     * How much track, measured along the rails, lies between arcs {@code from} and {@code to} —
     * the inverse of {@link #arcAlong}. The same number on the level, and more than the arc
     * between them wherever the track they span is tilted.
     *
     * <p>What a pair of cars actually stands at, for anything that has to compare that against an
     * interval it is being seated to. The two are only the same measurement on flat ground, and a
     * seated pair reads as closer than it is the moment it is on a hill.
     */
    public double trackBetween(double from, double to) {
        double low = Math.min(from, to);
        double high = Math.max(from, to);
        double trackDistance = 0.0;
        double coveredArc = 0.0;

        // Nodes wholly before low or from high on add nothing, and arcs rise with index.
        for (int i = nodes.firstIndex(node -> node.arc() + node.span() > low);
                i < nodes.size() && !(nodes.get(i).arc() >= high); i++) {
            Node node = nodes.get(i);
            double lo = Math.max(low, node.arc());
            double hi = Math.min(high, node.arc() + node.span());

            if (hi <= lo) {
                continue;
            }

            trackDistance += (hi - lo) / flatness(node);
            coveredArc += hi - lo;
        }

        // Track the ribbon does not hold, taken flat, as arcAlong takes it.
        return trackDistance + (high - low - coveredArc);
    }

    // How much of one node's track lies flat: its chord's ground plan over its chord.
    private static double flatness(Node node) {
        double run = node.exit().subtract(node.entry()).length();

        return run < EPSILON ? 1.0 : node.span() / run;
    }

    // Which way a node's own chord runs, or null where its two edge midpoints coincide. Flat: a
    // grade tilts a chord without turning it, and arc is a horizontal measure throughout.
    private static @Nullable Vec3 heading(Node node) {
        Vec3 chord = node.exit().subtract(node.entry()).horizontal();

        return chord.lengthSqr() > EPSILON ? chord.normalize() : null;
    }

    // The node whose span covers arc, or null if arc is off either end.
    private @Nullable Node nodeAt(double arc) {
        // Arcs rise with index, so no node past the first ending at or after arc can start by it.
        int i = nodes.firstIndex(node -> arc <= node.arc() + node.span() + EPSILON);

        if (i < nodes.size() && arc >= nodes.get(i).arc() - EPSILON) {
            return nodes.get(i);
        }

        return null;
    }

    // Nodes in the trim window of the longest train the rule allows, all corners, plus a part-node
    // at each end: more than a correct trim keeps, so only a trim no longer called meets it.
    private static int maxNodes(Level level) {
        // No rule to read off a server: the default is what a world that never set one allows.
        int maxCars = level instanceof ServerLevel serverLevel
                ? serverLevel.getGameRules().get(ModGameRules.MAX_TRAIN_LENGTH)
                : ModGameRules.MAX_TRAIN_LENGTH.defaultValue();
        double window = 2.0 * TrainCoupling.trimReach(
                (maxCars - 1) * TrainCoupling.maxLinkDistance());

        return (int) Math.ceil(window / RailPath.MIN_BLOCK_SPAN) + 2;
    }

    // Walks one block off one end and records it, answering whether the ribbon actually grew. Goes
    // straight to the two builders rather than through record, which reads an unknown block as a
    // discontinuity and would throw away the ribbon it was asked to extend.
    private boolean grow(Level level, boolean nearEnd) {
        if (nodes.isEmpty() || nodes.size() >= maxNodes(level)) {
            return false;
        }

        Node end = nearEnd ? nodes.getFirst() : nodes.getLast();

        // Out through the edge the ribbon ends on — the heading the builders read a crossing by;
        // prepend turns the near end's result back round into ribbon order.
        Vec3 outwardDirection = outward(end, nearEnd);
        BlockPos next = outwardDirection == null
                ? null : onward(level, end.block(), outwardDirection);

        // Not stopped by track this ribbon already holds: a circuit re-enters its own blocks a lap
        // on, and refusing to walk there leaves a looping train with no arc to sit at. Stopped by
        // an unloaded chunk, without loading it; extendTo asks again later.
        BlockState nextState = next == null ? null : RailPath.loadedState(level, next);

        if (nextState == null || !enterable(nextState, outwardDirection)) {
            return false;
        }

        int before = nodes.size();

        if (nearEnd) {
            prepend(level, next, outwardDirection);
        } else {
            append(level, next, outwardDirection);
        }

        return nodes.size() > before;
    }

    // As grow, skipping an end node it has already failed off since extendTo last forgot them.
    private boolean growEnd(Level level, boolean nearEnd) {
        Node end = nodes.isEmpty() ? null : nearEnd ? nodes.getFirst() : nodes.getLast();

        if (end == null || end == (nearEnd ? nearStop : farStop)) {
            return false;
        }

        if (grow(level, nearEnd)) {
            return true;
        }

        if (nearEnd) {
            nearStop = end;
        } else {
            farStop = end;
        }

        return false;
    }

    // Whether the rail recorded at pos is gone. An unloaded block counts as still there: reading it
    // would load its chunk, and a train would then pin it every tick.
    private static boolean takenUp(Level level, BlockPos pos) {
        BlockState state = RailPath.loadedState(level, pos);

        return state != null && !BaseRailBlock.isRail(state);
    }

    // Whether a car can no longer run from the rail at inner on to the one at outer: that rail is
    // gone, or the step between their heights passes through something solid above the lower one.
    private static boolean barred(Level level, BlockPos inner, BlockPos outer) {
        if (takenUp(level, outer)) {
            return true;
        }

        if (inner.getY() == outer.getY()) {
            return false;
        }

        BlockPos gate = (inner.getY() < outer.getY() ? inner : outer).above();
        BlockState gateState = RailPath.loadedState(level, gate);

        return gateState != null && !gateState.getCollisionShape(level, gate).isEmpty();
    }

    // Whether a walk heading outwardDirection can go on into the block in state.
    private static boolean enterable(BlockState state, Vec3 outwardDirection) {
        if (!(state.getBlock() instanceof SwitchedRailBlock)) {
            return true;
        }

        // A switch only as laid, the shape it holds while a train lies across it. With no car and
        // one end walked backwards, a guessed approach can lay the ribbon down the other leg.
        return SwitchedRailBlock.openTo(
                state, Direction.getApproximateNearest(outwardDirection.reverse()));
    }

    // The flat unit axis out of a node through its entry edge or its exit edge, or null where the
    // two coincide. Read off the edge itself: on a corner the entry is square to the exit, so the
    // reverse of the way out is not the way in.
    private static @Nullable Vec3 outward(Node node, boolean atEntry) {
        Direction outwardEdge = edge(atEntry ? node.entry() : node.exit(),
                Vec3.atCenterOf(node.block()).horizontal());

        return outwardEdge == null ? null
                : new Vec3(outwardEdge.getStepX(), 0.0, outwardEdge.getStepZ());
    }

    // The block past the far or near end that its track would carry on into, and the way out
    // through that end; null for an empty ribbon or an end whose track goes no further.
    private @Nullable Beyond beyondEnd(Level level, boolean farEnd) {
        if (nodes.isEmpty()) {
            return null;
        }

        Node end = farEnd ? nodes.getLast() : nodes.getFirst();
        Vec3 outwardDirection = outward(end, !farEnd);
        BlockPos pos = outwardDirection == null
                ? null : onward(level, end.block(), outwardDirection);

        return pos == null ? null : new Beyond(pos, outwardDirection);
    }

    // Whichever rail the block at fromBlock exits onto in the direction of outwardDirection, or
    // null where the track does not carry on that way. Only a strictly forward exit counts, so
    // the walk cannot double back on the block it came from.
    private static @Nullable BlockPos onward(
            Level level, BlockPos fromBlock, Vec3 outwardDirection) {
        BlockPos[] around = RailPath.neighbours(level, fromBlock, outwardDirection);

        // A recorded block is a position, not a promise: rail mined out from under the ribbon
        // reads as air here, and the walk ends at it.
        if (around == null) {
            return null;
        }

        BlockPos best = null;
        double nearest = 0.0;

        for (BlockPos candidate : around) {
            double forwardComponent = (candidate.getX() - fromBlock.getX()) * outwardDirection.x
                    + (candidate.getZ() - fromBlock.getZ()) * outwardDirection.z;

            if (forwardComponent > nearest) {
                nearest = forwardComponent;
                best = candidate;
            }
        }

        return best;
    }

    // Extends the far end. The car is travelling in ribbon order, so the crossing reads directly.
    private void append(Level level, BlockPos pos, Vec3 movement) {
        RailPath.Crossing crossing = RailPath.crossing(level, pos, movement);

        if (crossing == null) {
            return;
        }

        Node last = nodes.getLast();
        nodes.addLast(new Node(pos, crossing.entry(), crossing.exit(), last.arc() + last.span(),
                crossing.span()));

        // At the cap the near end gives way rather than this block: record hands over the block
        // its leading car is on, and refusing that leaves the car off its own ribbon.
        int limit = maxNodes(level);

        while (nodes.size() > limit) {
            nodes.removeFirst();
        }
    }

    // Extends the near end, which a train that has reversed is the one growing. The car crosses
    // this block against ribbon order, so its entry and exit are the other way round.
    private void prepend(Level level, BlockPos pos, Vec3 movement) {
        RailPath.Crossing crossing = RailPath.crossing(level, pos, movement);

        if (crossing == null) {
            return;
        }

        Node first = nodes.getFirst();
        nodes.addFirst(new Node(pos, crossing.exit(), crossing.entry(),
                first.arc() - crossing.span(), crossing.span()));

        // As in append, the other end gives way at the cap.
        int limit = maxNodes(level);

        while (nodes.size() > limit) {
            nodes.removeLast();
        }
    }

    // Whether two rail blocks can be consecutive: orthogonal neighbours, allowing the one-block
    // rise or fall of a slope.
    private static boolean adjacent(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ()) == 1
                && Math.abs(a.getY() - b.getY()) <= 1;
    }

    // The nodes in ribbon order, addressable by index, with where each block occurs. Sequence
    // numbers rise along the ribbon and survive removal at either end, so the map never renumbers.
    private static final class NodeRing implements Iterable<Node> {

        // An empty block's occurrences, shared rather than allocated per lookup.
        private static final IntArrayList NONE = new IntArrayList(0);

        // Power-of-two ring storage; head is the slot of index 0.
        private Node[] items = new Node[16];
        private int head;
        private int size;

        // The sequence number of index 0.
        private int firstSeq;

        // Each block's sequence numbers, ascending, keyed by BlockPos.asLong.
        private final Long2ObjectOpenHashMap<IntArrayList> seqsByBlock =
                new Long2ObjectOpenHashMap<>();

        // How many nodes are held.
        private int size() {
            return size;
        }

        // Whether no node is held.
        private boolean isEmpty() {
            return size == 0;
        }

        // The node at index i, 0 being the near end.
        private Node get(int i) {
            return items[(head + i) & (items.length - 1)];
        }

        // The node with sequence number seq, which must be held.
        private Node bySeq(int seq) {
            return get(seq - firstSeq);
        }

        // The near end node, throwing when empty as Deque does.
        private Node getFirst() {
            if (size == 0) {
                throw new NoSuchElementException();
            }

            return get(0);
        }

        // The far end node, throwing when empty as Deque does.
        private Node getLast() {
            if (size == 0) {
                throw new NoSuchElementException();
            }

            return get(size - 1);
        }

        // The far end node, or null when empty.
        private @Nullable Node peekLast() {
            return size == 0 ? null : get(size - 1);
        }

        // The sequence numbers of the nodes recording pos, ascending; read-only, empty if none.
        private IntArrayList seqsAt(BlockPos pos) {
            IntArrayList seqs = seqsByBlock.get(pos.asLong());

            return seqs == null ? NONE : seqs;
        }

        // The first index whose node passes reached, or size() if none; reached must be false
        // for a run of indices from 0 and true for every index after.
        private int firstIndex(Predicate<Node> reached) {
            int low = 0;
            int high = size;

            while (low < high) {
                int middle = (low + high) >>> 1;

                if (reached.test(get(middle))) {
                    high = middle;
                } else {
                    low = middle + 1;
                }
            }

            return low;
        }

        // Adds node at the near end.
        private void addFirst(Node node) {
            growIfFull();
            head = (head - 1) & (items.length - 1);
            items[head] = node;
            size++;
            firstSeq--;
            seqsByBlock.computeIfAbsent(node.block().asLong(), key -> new IntArrayList(1))
                    .add(0, firstSeq);
        }

        // Adds node at the far end.
        private void addLast(Node node) {
            growIfFull();
            items[(head + size) & (items.length - 1)] = node;
            seqsByBlock.computeIfAbsent(node.block().asLong(), key -> new IntArrayList(1))
                    .add(firstSeq + size);
            size++;
        }

        // Removes and returns the near end node, throwing when empty as Deque does.
        private Node removeFirst() {
            Node node = getFirst();

            items[head] = null;
            head = (head + 1) & (items.length - 1);
            size--;
            firstSeq++;
            forget(node, 0);
            return node;
        }

        // Removes and returns the far end node, throwing when empty as Deque does.
        private Node removeLast() {
            Node node = getLast();

            items[(head + size - 1) & (items.length - 1)] = null;
            size--;
            forget(node, -1);
            return node;
        }

        // Drops every node.
        private void clear() {
            Arrays.fill(items, null);
            head = 0;
            size = 0;
            firstSeq = 0;
            seqsByBlock.clear();
        }

        // Adds every node of other at the far end, in order.
        private void addAll(NodeRing other) {
            for (int i = 0; i < other.size(); i++) {
                addLast(other.get(i));
            }
        }

        @Override
        public Iterator<Node> iterator() {
            return walk(0, 1);
        }

        // The nodes from the far end back to the near end.
        private Iterator<Node> descendingIterator() {
            return walk(size - 1, -1);
        }

        // Walks from index start by step until off either end.
        private Iterator<Node> walk(int start, int step) {
            return new Iterator<>() {
                private int cursor = start;

                @Override
                public boolean hasNext() {
                    return cursor >= 0 && cursor < size;
                }

                @Override
                public Node next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }

                    Node node = get(cursor);
                    cursor += step;
                    return node;
                }
            };
        }

        // Drops an end node's sequence number from its block: its first (end 0) or last (-1).
        private void forget(Node node, int end) {
            long key = node.block().asLong();
            IntArrayList seqs = seqsByBlock.get(key);

            seqs.removeInt(end == 0 ? 0 : seqs.size() - 1);

            if (seqs.isEmpty()) {
                seqsByBlock.remove(key);
            }
        }

        // Doubles the storage when every slot is taken, laying index 0 at slot 0.
        private void growIfFull() {
            if (size < items.length) {
                return;
            }

            Node[] grown = new Node[items.length * 2];

            for (int i = 0; i < size; i++) {
                grown[i] = get(i);
            }

            items = grown;
            head = 0;
        }
    }
}
