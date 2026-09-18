package club.rainbowkitty.minecarttweaks.rail;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import com.mojang.logging.LogUtils;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.block.SwitchedRailBlock;

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
 * simply builds another. Nothing here moves a cart.
 */
public final class TrackRibbon {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Hands every arc numbering, on every ribbon, a number of its own. Atomic because a ribbon
    // is built on whichever thread a cart ticks on.
    private static final AtomicInteger FRAMES = new AtomicInteger();

    // A ribbon only has to span the train plus room to grow at the leading end. A cap that a
    // correct trim never reaches, so a trim that stops being called leaks a bounded amount.
    private static final int MAX_NODES = 256;

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

    // The rail blocks and crossing points this train has traversed, in order.
    private final Deque<Node> nodes = new ArrayDeque<>();

    // Where along this ribbon the leading car was last recorded. Held rather than looked back up:
    // a circuit shorter than the ribbon records every one of its blocks twice, a lap apart, and the
    // two are indistinguishable by the block a car stands on. Which of them a car is really on is
    // only answerable from where it was a moment ago.
    private double leaderArc;

    // Kept for the meter, and monotonic over this ribbon's whole life rather than per attempt —
    // the question they answer is whether resets have stopped, which a counter that resets cannot.
    private int resets;
    private int revisits;

    // Which arc numbering this ribbon is on. Handed out from a counter shared by every ribbon,
    // so state a car holds against one numbering is never mistaken for state against another —
    // which the reset count alone cannot manage, two ribbons on their first both answering it.
    private int frame = FRAMES.incrementAndGet();

    /** Whether this ribbon has any track recorded yet. */
    public boolean isEmpty() {
        return nodes.isEmpty();
    }

    /** How many rail blocks are recorded. */
    public int size() {
        return nodes.size();
    }

    /** The arc length at the near end of the recorded track. */
    public double from() {
        return nodes.isEmpty() ? 0.0 : nodes.getFirst().arc();
    }

    /** The arc length at the far end of the recorded track. */
    public double to() {
        Node last = nodes.peekLast();
        return last == null ? 0.0 : last.arc() + last.span();
    }

    /** How many times this ribbon has been thrown away and restarted from one block. */
    public int resets() {
        return resets;
    }

    /**
     * Which arc numbering this ribbon is on, as a number no other ribbon's numbering shares.
     * Anything remembered in terms of an arc — how far along it something sits, or which way
     * along it something points — is only good for the numbering it was taken against.
     */
    public int frame() {
        return frame;
    }

    /** How many times a car has reported track this ribbon already holds, away from both ends. */
    public int revisits() {
        return revisits;
    }

    /**
     * Records that a car crossed the rail block at {@code pos} travelling along {@code movement},
     * extending whichever end of the ribbon that block continues. Track already recorded is
     * ignored: whichever car reports it, it is track this ribbon knows, and discarding twenty good
     * nodes over one unremarkable block is the more expensive mistake by far. Only a block the
     * ribbon has never seen is a discontinuity — a reversal onto untracked rail, a teleport, a
     * reload — and that restarts the ribbon rather than inventing track between the two.
     */
    public void record(Level level, BlockPos pos, Vec3 movement) {
        if (!nodes.isEmpty()) {
            // Where the leading car just was is the only thing that separates a lap later from a
            // reversal, so it is asked before either end of the ribbon is.
            Double near = occurrenceNear(pos, leaderArc);

            if (near != null) {
                revisits++;
                leaderArc = near;
                return;
            }

            // New track extends the end the car is heading off, not whichever end this block sits
            // next to — on a closed circuit the two ends are the same place and that is both.
            Vec3 tangent = tangentAt(leaderArc);
            boolean ahead = tangent == null || movement.dot(tangent) >= 0.0;

            if (ahead && adjacent(nodes.getLast().block(), pos)) {
                append(level, pos, movement);
                leaderArc = nodes.getLast().arc();
                return;
            }

            if (!ahead && adjacent(nodes.getFirst().block(), pos)) {
                prepend(level, pos, movement);
                leaderArc = nodes.getFirst().arc();
                return;
            }

            if (holds(pos)) {
                revisits++;
                leaderArc = nearestOccurrence(pos, leaderArc);
                return;
            }

            resets++;
            frame = FRAMES.incrementAndGet();
            LOGGER.debug("ribbon reset at {}: unknown to its {} nodes, running {} to {}",
                    pos, nodes.size(), nodes.getFirst().block(), nodes.getLast().block());
            nodes.clear();
        }

        RailPath.Crossing crossing = RailPath.crossing(level, pos, movement);

        if (crossing != null) {
            nodes.add(new Node(pos, crossing.entry(), crossing.exit(), 0.0, crossing.span()));
            leaderArc = 0.0;
        }
    }

    /**
     * Where the leading car sits along this ribbon, or null if the block it stands on is not one
     * this ribbon records.
     *
     * <p>The same question {@link #arcOf} answers, settled differently where a block appears more
     * than once: by which occurrence the car was last recorded on rather than by which is nearest
     * in space. On a circuit the two occurrences are a lap apart but can be a couple of blocks
     * from each other, so the nearest in space is a coin toss, and losing it puts the whole train
     * a lap from where it belongs.
     */
    public @Nullable Double arcOfLeader(AbstractMinecart leader) {
        BlockPos pos = leader.getCurrentBlockPosOrRailBelow();
        Vec3 at = leader.position().horizontal();
        Double best = null;
        double nearest = Double.MAX_VALUE;

        for (Node node : nodes) {
            if (!node.block().equals(pos)) {
                continue;
            }

            double arc = node.arc() + along(node, at) * node.span();
            double away = Math.abs(arc - leaderArc);

            if (away < nearest) {
                nearest = away;
                best = arc;
            }
        }

        if (best != null) {
            leaderArc = best;
        }

        return best;
    }

    // The arc of an occurrence of pos within a block of where the leading car already was, or null
    // if this ribbon holds none. A record is made once per block that car crosses, so a genuine
    // continuation is always that close and anything further off is a different lap.
    private @Nullable Double occurrenceNear(BlockPos pos, double from) {
        for (Node node : nodes) {
            if (node.block().equals(pos)
                    && node.arc() + node.span() >= from - CONTINUITY
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

        for (Node node : nodes) {
            if (node.block().equals(pos) && Math.abs(node.arc() - from) < nearest) {
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

        double along = node.span() < EPSILON ? 0.0 : (arc - node.arc()) / node.span();
        return node.entry().lerp(node.exit(), along);
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
        Vec3 at = car.position().horizontal();
        Double best = null;
        double nearest = Double.MAX_VALUE;
        double apart = Double.MAX_VALUE;

        for (Node node : nodes) {
            if (!node.block().equals(pos)) {
                continue;
            }

            double along = along(node, at);
            double arc = node.arc() + along * node.span();
            double away = node.entry().lerp(node.exit(), along).horizontal().distanceToSqr(at);
            double lap = Math.abs(arc - leaderArc);

            // A lap's two occurrences are one point, so they score identically and the ranking
            // falls through to which of them the train is actually running on.
            if (away < nearest - EPSILON || (away < nearest + EPSILON && lap < apart)) {
                nearest = away;
                apart = lap;
                best = arc;
            }
        }

        return best;
    }

    /**
     * Whether the rail at arc {@code arc} would now send a car off the track this ribbon recorded.
     *
     * <p>Only a switch can answer yes. A seated car is placed on recorded track and does no rail
     * reading of its own, so a switch thrown behind the leading car is otherwise ignored entirely
     * and the cars behind it are drawn across the rails rather than along them.
     */
    public boolean diverted(Level level, double arc) {
        Node node = nodeAt(arc);

        if (node == null) {
            return false;
        }

        BlockState state = level.getBlockState(node.block());

        if (!(state.getBlock() instanceof SwitchedRailBlock)) {
            return false;
        }

        Vec3 centre = Vec3.atCenterOf(node.block()).horizontal();
        Direction in = edge(node.entry(), centre);
        Direction out = edge(node.exit(), centre);

        if (in == null || out == null) {
            return false;
        }

        // The cart arrives heading away from the edge it came in by, which is the approach the
        // switch reads to decide where it sends one.
        var exits = AbstractMinecart.exits(SwitchedRailBlock.shapeFor(state, in.getOpposite()));

        return !(joins(exits.getFirst(), in) && joins(exits.getSecond(), out))
                && !(joins(exits.getFirst(), out) && joins(exits.getSecond(), in));
    }

    // Which edge of its block a recorded edge midpoint lies on, or null if it reads as neither.
    private static @Nullable Direction edge(Vec3 point, Vec3 centre) {
        Vec3 away = point.horizontal().subtract(centre);

        return away.lengthSqr() < EPSILON ? null
                : Direction.getApproximateNearest(away.x, 0.0, away.z);
    }

    // Whether a rail shape's exit marker points along an edge. Its Y marks the high end of a slope
    // rather than an offset, so only the horizontal part names a direction.
    private static boolean joins(Vec3i exit, Direction edge) {
        return exit.getX() == edge.getStepX() && exit.getZ() == edge.getStepZ();
    }

    /** Whether arc length {@code arc} lies on the recorded track rather than off either end. */
    public boolean reaches(double arc) {
        return !nodes.isEmpty() && arc >= from() - EPSILON && arc <= to() + EPSILON;
    }

    /**
     * Whether the track ends at this ribbon's far or near end against something solid: the block
     * past the end node is not rail and has a collision shape. A car seated closer than half its
     * length to that end would be put inside it, which is where vanilla stops a moving cart.
     */
    public boolean blockedBeyond(Level level, boolean farEnd) {
        if (nodes.isEmpty()) {
            return false;
        }

        Node end = farEnd ? nodes.getLast() : nodes.getFirst();
        Vec3 out = outward(end, !farEnd);
        BlockPos beyond = out == null ? null : onward(level, end.block(), out);

        if (beyond == null) {
            return false;
        }

        BlockState past = level.getBlockState(beyond);

        return !BaseRailBlock.isRail(past) && !past.getCollisionShape(level, beyond).isEmpty();
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

        for (Node node : nodes) {
            if (node.block().equals(pos)
                    && node.arc() + node.span() >= low - EPSILON
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
     */
    public void extendTo(Level level, double from, double to) {
        while (from() > from + EPSILON && grow(level, true)) {
            continue;
        }

        while (to() < to - EPSILON && grow(level, false)) {
            continue;
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

        for (Node node : nodes) {
            if (node.arc() + node.span() >= low - EPSILON && node.arc() <= high + EPSILON) {
                action.accept(node.block());
            }
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

        while (nodes.size() > MAX_NODES) {
            nodes.removeFirst();
        }
    }

    // Whether this ribbon records pos anywhere along it.
    private boolean holds(BlockPos pos) {
        for (Node node : nodes) {
            if (node.block().equals(pos)) {
                return true;
            }
        }

        return false;
    }

    // How far along a node's chord a point projects, clamped to the block. Flat on both sides: arc
    // is a horizontal measure, and a grade would otherwise lengthen the chord a car is measured
    // against without lengthening the arc it is measured into.
    private static double along(Node node, Vec3 at) {
        Vec3 chord = node.exit().subtract(node.entry()).horizontal();
        double length = chord.length();

        if (length < EPSILON) {
            return 0.0;
        }

        return Math.clamp(
                at.horizontal().subtract(node.entry().horizontal()).dot(chord) / (length * length),
                0.0, 1.0);
    }

    // Which way a node's own chord runs, or null where its two edge midpoints coincide. Flat: a
    // grade tilts a chord without turning it, and arc is a horizontal measure throughout.
    private static @Nullable Vec3 heading(Node node) {
        Vec3 chord = node.exit().subtract(node.entry()).horizontal();

        return chord.lengthSqr() > EPSILON ? chord.normalize() : null;
    }

    // The node whose span covers arc, or null if arc is off either end.
    private @Nullable Node nodeAt(double arc) {
        for (Node node : nodes) {
            if (arc >= node.arc() - EPSILON && arc <= node.arc() + node.span() + EPSILON) {
                return node;
            }
        }

        return null;
    }

    // Walks one block off one end and records it, answering whether the ribbon actually grew. Goes
    // straight to the two builders rather than through record, which reads an unknown block as a
    // discontinuity and would throw away the ribbon it was asked to extend.
    private boolean grow(Level level, boolean nearEnd) {
        if (nodes.isEmpty() || nodes.size() >= MAX_NODES) {
            return false;
        }

        Node end = nearEnd ? nodes.getFirst() : nodes.getLast();

        // Out through the edge the ribbon ends on, which is the heading the builders read a
        // crossing by; prepend turns the near end's result back round into ribbon order.
        Vec3 out = outward(end, nearEnd);
        BlockPos next = out == null ? null : onward(level, end.block(), out);

        // Not stopped by track this ribbon already holds: a circuit re-enters its own blocks a lap
        // on, and refusing to walk there leaves a looping train with no arc to sit at. A switch
        // does stop it: which way it sends a car depends on how that car arrives, and this walk
        // has no car and runs one of its two ends backwards. Guessing wrong here does not bend the
        // track a little, it lays the ribbon down the other leg and the train is placed onto it.
        if (next == null || level.getBlockState(next).getBlock() instanceof SwitchedRailBlock) {
            return false;
        }

        int before = nodes.size();

        if (nearEnd) {
            prepend(level, next, out);
        } else {
            append(level, next, out);
        }

        return nodes.size() > before;
    }

    // The flat unit axis out of a node through its entry edge or its exit edge, or null where the
    // two coincide. Read off the edge itself: on a corner the entry is square to the exit, so the
    // reverse of the way out is not the way in.
    private static @Nullable Vec3 outward(Node node, boolean entry) {
        Direction edge = edge(entry ? node.entry() : node.exit(),
                Vec3.atCenterOf(node.block()).horizontal());

        return edge == null ? null : new Vec3(edge.getStepX(), 0.0, edge.getStepZ());
    }

    // Whichever rail the block at from exits onto in the direction of out, or null where the track
    // does not carry on that way. Only a strictly forward exit counts, so the walk cannot double
    // back on the block it came from.
    private static @Nullable BlockPos onward(Level level, BlockPos from, Vec3 out) {
        // A recorded block is a position, not a promise: rail mined out from under the ribbon
        // reads as air here, and asking air for its exits casts it to a rail.
        if (!BaseRailBlock.isRail(level.getBlockState(from))) {
            return null;
        }

        BlockPos best = null;
        double nearest = 0.0;

        for (BlockPos candidate : RailPath.neighbours(level, from, out)) {
            double along = (candidate.getX() - from.getX()) * out.x
                    + (candidate.getZ() - from.getZ()) * out.z;

            if (along > nearest) {
                nearest = along;
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
    }

    // Whether two rail blocks can be consecutive: orthogonal neighbours, allowing the one-block
    // rise or fall of a slope.
    private static boolean adjacent(BlockPos a, BlockPos b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getZ() - b.getZ()) == 1
                && Math.abs(a.getY() - b.getY()) <= 1;
    }
}
