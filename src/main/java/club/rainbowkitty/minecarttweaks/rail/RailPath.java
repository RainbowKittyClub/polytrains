package club.rainbowkitty.minecarttweaks.rail;

import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.block.JunctionRailBlock;
import club.rainbowkitty.minecarttweaks.block.SwitchedRailBlock;

/**
 * The stretch of track between two carts: how far apart they are along the rails, and the exit each
 * of them leaves its own block by to travel from the second towards the first. Both directions are
 * genuine exits of the block the cart stands in, never the reverse of the exit facing the other.
 *
 * @param distance how far apart the two carts are along the rails, in blocks
 * @param fromForward the exit the far cart leaves its own block by, travelling towards the near
 * @param toForward the exit the near cart leaves its own block by, travelling towards the far
 */
public record RailPath(double distance, Vec3 fromForward, Vec3 toForward) {
    // Below this a length or a dot product is treated as zero.
    private static final double EPSILON = 1.0E-5;

    // How far a rail's running surface sits above its own block, read out of adjustToRails, which
    // puts a cart at blockY + its distance from the low edge + this.
    private static final double RAIL_LIFT = 0.1;

    // The least track one rail block can be worth: a corner, the chord between two adjacent edge
    // midpoints. Bounds how many blocks a given run of track can have been spent over.
    private static final double MIN_BLOCK_SPAN = Math.sqrt(0.5);

    // The four directions one rail can lead to another in.
    private static final Vec3 EAST = new Vec3(1.0, 0.0, 0.0);

    private static final Vec3 WEST = new Vec3(-1.0, 0.0, 0.0);

    private static final Vec3 SOUTH = new Vec3(0.0, 0.0, 1.0);

    private static final Vec3 NORTH = new Vec3(0.0, 0.0, -1.0);

    /**
     * How the rail under a cart turns and climbs in the direction the cart is travelling. Both are
     * signed, so a run of corners reversing each other can be told from a genuine bend.
     *
     * @param turn which way the rail turns: -1, 0 or 1
     * @param grade which way the rail climbs: -1, 0 or 1
     */
    public record Bend(int turn, int grade) {

        /** A cart stopped, off the rails, or on track that neither turns nor climbs. */
        public static final Bend NONE = new Bend(0, 0);
    }

    /** How a reading of the track should take a switch it crosses. */
    public enum Switches {
        /**
         * As it is set. What a cart crossing it right now is riding, and so the only honest
         * reading of track a cart has already covered.
         */
        LAID,

        /**
         * As the rails it joins, whichever way it happens to be thrown. Whether two carts share a
         * run of track is a question about the track, not about the throw.
         */
        JOINED
    }

    /**
     * The run of track across one rail block: the two edge midpoints a cart crosses between, and
     * how much track that is worth. Horizontal, like every other distance here — the arc lengths
     * built out of these have to be spendable against the budget vanilla charges movement to.
     *
     * @param entry the edge midpoint the cart comes in by, at the height the rail runs there
     * @param exit the edge midpoint it leaves by, at the height the rail runs there
     * @param forward the exit direction, as a unit vector; horizontal, whatever the grade
     * @param span how far apart the two midpoints are horizontally, which is the unit vanilla
     *     charges movement in
     */
    public record Crossing(Vec3 entry, Vec3 exit, Vec3 forward, double span) {}

    /**
     * Walks the rails from one cart to the other, or returns null if either is off the track or the
     * other cannot be reached within {@code limit} blocks of track.
     */
    public static @Nullable RailPath between(
            AbstractMinecart from, AbstractMinecart to, double limit) {
        Level level = from.level();
        BlockPos start = from.getCurrentBlockPosOrRailBelow();
        BlockPos goal = to.getCurrentBlockPosOrRailBelow();

        if (!railAt(level, start) || !railAt(level, goal)) {
            return null;
        }

        if (start.equals(goal)) {
            Vec3 direct = to.position().subtract(from.position()).horizontal();

            if (direct.lengthSqr() < EPSILON * EPSILON) {
                return null;
            }

            Vec3 back = direct.reverse().normalize();
            return new RailPath(direct.length(), back, back);
        }

        // On a circuit short enough that both ways round come in under the limit, the nearer one is
        // the gap; taking whichever was walked first would sometimes measure the long way round and
        // have the coupling close that instead, driving the train backwards into it.
        RailPath nearest = null;

        for (BlockPos first : neighbours(level, start, from.getDeltaMovement(), Switches.JOINED)) {
            RailPath path = walk(level, from, to, start, first, goal, limit);

            if (path != null && (nearest == null || path.distance() < nearest.distance())) {
                nearest = path;
            }
        }

        return nearest;
    }

    /**
     * Which of the two exits of its own block a cart's movement is heading for, by the same rule
     * vanilla uses to steer it, or null if it is stopped or off the track.
     */
    public static @Nullable Vec3 exitTaken(AbstractMinecart cart) {
        Level level = cart.level();
        BlockPos pos = cart.getCurrentBlockPosOrRailBelow();
        Vec3 movement = cart.getDeltaMovement().horizontal();

        if (!railAt(level, pos) || movement.lengthSqr() < EPSILON * EPSILON) {
            return null;
        }

        BlockPos taken = exitTaken(neighbours(level, pos, movement), pos, movement);
        return direction(pos, taken);
    }

    /**
     * The turn and grade of the rail {@code cart} stands on. Answered together because both are
     * read off the same exit, and finding that exit is the expensive part of either.
     */
    public static Bend bend(AbstractMinecart cart) {
        Level level = cart.level();
        BlockPos pos = cart.getCurrentBlockPosOrRailBelow();
        BlockState state = level.getBlockState(pos);
        Vec3 movement = cart.getDeltaMovement().horizontal();

        if (!BaseRailBlock.isRail(state) || movement.lengthSqr() < EPSILON * EPSILON) {
            return Bend.NONE;
        }

        BlockPos[] around = neighbours(level, pos, movement);
        Vec3 out = direction(pos, exitTaken(around, pos, movement));
        Vec3 in = otherExit(around, pos, out);
        double cross = in.x * out.z - in.z * out.x;

        return new Bend(
                Math.abs(cross) < 0.5 ? 0 : (int) Math.signum(cross), grade(state, out));
    }

    /**
     * How the track runs across the block at {@code pos} for a cart entering it by
     * {@code movement}, or null if that block is not a rail.
     */
    public static @Nullable Crossing crossing(Level level, BlockPos pos, Vec3 movement) {
        if (!railAt(level, pos)) {
            return null;
        }

        BlockPos[] around = neighbours(level, pos, movement);
        Vec3 out = direction(pos, exitTaken(around, pos, movement));
        Vec3 in = otherExit(around, pos, out);
        BlockState state = level.getBlockState(pos);
        Vec3 floor = Vec3.atBottomCenterOf(pos);

        return new Crossing(
                floor.add(in.scale(0.5)).add(0.0, edgeHeight(state, in, movement), 0.0),
                floor.add(out.scale(0.5)).add(0.0, edgeHeight(state, out, movement), 0.0),
                out,
                span(around, pos, out));
    }

    /**
     * The rail block a cart leaves the block at {@code pos} by, travelling {@code movement}, or
     * null where {@code pos} is not a rail or the cart is not going anywhere.
     */
    public static @Nullable BlockPos ahead(Level level, BlockPos pos, Vec3 movement) {
        if (!railAt(level, pos) || movement.horizontalDistanceSqr() <= 0.0) {
            return null;
        }

        return exitTaken(neighbours(level, pos, movement), pos, movement);
    }

    /**
     * {@code forward} given the rise of the rail {@code cart} stands on, so that moving a cart
     * along it climbs a slope rather than travelling into the hillside and off the track.
     */
    public static Vec3 alongTrack(AbstractMinecart cart, Vec3 forward) {
        Level level = cart.level();
        BlockPos pos = cart.getCurrentBlockPosOrRailBelow();

        if (!railAt(level, pos)) {
            return forward;
        }

        BlockState state = level.getBlockState(pos);
        var exits = AbstractMinecart.exits(shape(state, forward, Switches.LAID));
        Vec3i first = exits.getFirst();
        Vec3i second = exits.getSecond();
        boolean outIsFirst = towards(first, forward) >= towards(second, forward);
        Vec3i out = outIsFirst ? first : second;
        Vec3i in = outIsFirst ? second : first;

        // The chord between the block's two edge midpoints rather than the exit itself: through a
        // corner the two are 45 degrees apart, so shifting a car along its exit walks it sideways
        // off the rail line and into the next block, to be snapped onto whatever it lands on.
        Vec3 chord = new Vec3(out.getX() - in.getX(), 0.0, out.getZ() - in.getZ());
        Vec3 flat = chord.lengthSqr() < EPSILON ? forward : chord.normalize();

        if (first.getY() == second.getY()) {
            return flat;
        }

        return flat.add(0.0, out.getY() == 0 ? 1.0 : -1.0, 0.0);
    }

    /**
     * The two rails the block at {@code pos} exits onto, in the order its shape lists them.
     *
     * <p>On a slope the Y of an exit marks which end is the low one, it is not an offset to add:
     * the rail beyond the high end stands a block up, and the one beyond the low end is level with
     * the slope or a block down. Offsetting by the marker lands on air a block under the track
     * instead. {@code movement} only matters on a junction rail, which picks its axis from it.
     */
    public static BlockPos[] neighbours(Level level, BlockPos pos, Vec3 movement) {
        return neighbours(level, pos, movement, Switches.LAID);
    }

    /** As {@link #neighbours}, taking a switch the given way rather than as it is set. */
    public static BlockPos[] neighbours(
            Level level, BlockPos pos, Vec3 movement, Switches switches) {
        BlockState state = level.getBlockState(pos);
        var exits = AbstractMinecart.exits(shape(state, movement, switches));
        Vec3i first = exits.getFirst();
        Vec3i second = exits.getSecond();
        boolean sloped = first.getY() != second.getY();

        return new BlockPos[] {
                neighbour(level, pos, first, sloped && first.getY() == 0),
                neighbour(level, pos, second, sloped && second.getY() == 0)};
    }

    // How high above its own block the rail runs at the edge in the direction of edge. Level track
    // sits a tenth proud all the way across; a slope climbs a whole block between its two edges, so
    // the high one is that much further up. Reading the ends of the line adjustToRails walks means
    // a placed car and a car vanilla stepped agree about where the rail is.
    private static double edgeHeight(BlockState state, Vec3 edge, Vec3 movement) {
        var exits = AbstractMinecart.exits(shape(state, movement, Switches.LAID));
        Vec3i first = exits.getFirst();
        Vec3i second = exits.getSecond();

        if (first.getY() == second.getY()) {
            return RAIL_LIFT;
        }

        // The exit marked level is the one the rail climbs towards; the other marks the low end.
        Vec3i high = first.getY() == 0 ? first : second;

        return towards(high, edge) > 0.0 ? 1.0 + RAIL_LIFT : RAIL_LIFT;
    }

    // Whether the rail climbs, drops or runs level in the direction of out.
    private static int grade(BlockState state, Vec3 out) {
        var exits = AbstractMinecart.exits(shape(state, out, Switches.LAID));
        Vec3i first = exits.getFirst();
        Vec3i second = exits.getSecond();

        if (first.getY() == second.getY()) {
            return 0;
        }

        // On a slope the exit marked level is the one the rail climbs towards, and the other is
        // marked a block down; it is a label for which end is which, not an offset to travel by.
        return towards(first.getY() == 0 ? first : second, out) > 0.0 ? 1 : -1;
    }

    // Whichever of the block's two exits the movement is heading closest to.
    private static BlockPos exitTaken(BlockPos[] around, BlockPos pos, Vec3 movement) {
        double first = direction(pos, around[0]).dot(movement);
        double second = direction(pos, around[1]).dot(movement);

        return first >= second ? around[0] : around[1];
    }

    // How much of forward points out of the block by exit.
    private static double towards(Vec3i exit, Vec3 forward) {
        return forward.x * exit.getX() + forward.z * exit.getZ();
    }

    // Walks the track from start out through first, returning the path to goal or null if the walk
    // runs off the rails, dead-ends or passes the limit.
    private static @Nullable RailPath walk(Level level, Entity from, Entity to,
            BlockPos start, BlockPos first, BlockPos goal, double limit) {
        Vec3 fromExit = direction(start, first);
        BlockPos previous = start;
        BlockPos current = first;
        double between = 0.0;

        // Bounded by the track walked, not the blocks it took: a corner is worth seven tenths of a
        // block, so spending the limit one block per step left a link round a bend barely two
        // thirds the reach a straight one got. The cap only guards a circuit short enough to loop.
        int maxSteps = (int) Math.ceil(limit / MIN_BLOCK_SPAN) + 1;

        for (int steps = 1; steps <= maxSteps && between <= limit; steps++) {
            if (current.equals(goal)) {
                Vec3 toEntry = direction(current, previous);

                // Each block is worth the run of track across it, not a flat one: a corner is only
                // the chord between two adjacent edges, about seven tenths of a block.
                double distance = crossing(level, from.position(), start, fromExit)
                        + between
                        + crossing(level, to.position(), goal, toEntry);

                return new RailPath(distance,
                        otherExit(neighbours(level, start, fromExit, Switches.JOINED), start,
                                fromExit), toEntry);
            }

            if (!railAt(level, current)) {
                return null;
            }

            // One reading of the block's exits serves both the run of track across it and the step
            // off it, which is the whole of the per-block cost of the walk.
            Vec3 movement = direction(previous, current);
            BlockPos[] around = neighbours(level, current, movement, Switches.JOINED);
            BlockPos onward = exitTaken(around, current, movement);
            between += span(around, current, direction(current, onward));

            previous = current;
            current = onward;
        }

        return null;
    }

    // How much of block a cart at position has left to cross before its exit.
    private static double crossing(Level level, Vec3 position, BlockPos block, Vec3 exit) {
        Vec3 leaving = Vec3.atCenterOf(block).horizontal().add(exit.scale(0.5));

        return Math.min(position.horizontal().distanceTo(leaving),
                span(neighbours(level, block, exit, Switches.JOINED), block, exit));
    }

    // The run of track across block, between the midpoints of its two edges.
    private static double span(BlockPos[] around, BlockPos block, Vec3 exit) {
        Vec3 centre = Vec3.atCenterOf(block).horizontal();

        return centre.add(otherExit(around, block, exit).scale(0.5))
                .distanceTo(centre.add(exit.scale(0.5)));
    }

    // The exit a cart entering through exit would leave by.
    private static Vec3 otherExit(BlockPos[] around, BlockPos block, Vec3 exit) {
        return direction(block, exitTaken(around, block, exit.reverse()));
    }

    // The shape a moving cart sees; a junction rail selects its axis from that movement. A switch
    // is read by its rails rather than its throw, but only where the question is whether track
    // connects — the ribbon records where a car did go, and there the resting curve is the truth.
    private static RailShape shape(BlockState state, Vec3 movement, Switches switches) {
        if (movement.horizontalDistanceSqr() > 0.0) {
            if (state.getBlock() instanceof JunctionRailBlock) {
                return JunctionRailBlock.shapeFor(movement);
            }

            if (switches == Switches.JOINED
                    && state.getBlock() instanceof SwitchedRailBlock) {
                return SwitchedRailBlock.linkShape(state, movement);
            }
        }

        return state.getValue(((BaseRailBlock) state.getBlock()).getShapeProperty());
    }

    // The block one exit away, taking the rail a step up or down where that is where it lies.
    private static BlockPos neighbour(Level level, BlockPos pos, Vec3i exit, boolean uphill) {
        BlockPos candidate = pos.offset(exit.getX(), uphill ? 1 : 0, exit.getZ());

        if (railAt(level, candidate)) {
            return candidate;
        }

        return railAt(level, candidate.below()) ? candidate.below() : candidate;
    }

    // Whether the block at pos is a rail.
    private static boolean railAt(Level level, BlockPos pos) {
        return BaseRailBlock.isRail(level.getBlockState(pos));
    }

    // Horizontal only, matching the horizontal distances vanilla charges a cart's movement against.
    // A rail's exits are orthogonal, so the answer is always one of four unit vectors: looked up
    // rather than built, since the walk asks this several times per block per link per tick.
    private static Vec3 direction(BlockPos from, BlockPos to) {
        int x = Integer.signum(to.getX() - from.getX());

        if (x != 0) {
            return x > 0 ? EAST : WEST;
        }

        int z = Integer.signum(to.getZ() - from.getZ());

        if (z != 0) {
            return z > 0 ? SOUTH : NORTH;
        }

        return Vec3.ZERO;
    }
}
