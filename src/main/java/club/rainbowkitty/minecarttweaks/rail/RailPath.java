package club.rainbowkitty.minecarttweaks.rail;

import it.unimi.dsi.fastutil.longs.Long2DoubleMap;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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

    /**
     * The least track one rail block can be worth: a corner, the chord between two adjacent edge
     * midpoints. Bounds how many blocks a given run of track can have been spent over.
     */
    public static final double MIN_BLOCK_SPAN = Math.sqrt(0.5);

    // The four directions one rail can lead to another in.
    private static final Vec3 EAST = new Vec3(1.0, 0.0, 0.0);

    private static final Vec3 WEST = new Vec3(-1.0, 0.0, 0.0);

    private static final Vec3 SOUTH = new Vec3(0.0, 0.0, 1.0);

    private static final Vec3 NORTH = new Vec3(0.0, 0.0, -1.0);

    // How a reading of the track takes a switch it crosses: as it's set — what a cart crossing it
    // right now is riding — or as the rails it joins whichever way it's thrown, since whether two
    // carts share a run of track is a question about the track, not the throw.
    private enum Switches {
        LAID,
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
     * @param span how far apart the two midpoints are horizontally — the unit vanilla charges
     *     movement in
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

        if (!railAt(level, goal)) {
            return null;
        }

        if (start.equals(goal)) {
            Vec3 displacement = to.position().subtract(from.position()).horizontal();

            if (displacement.lengthSqr() < EPSILON * EPSILON) {
                return null;
            }

            Vec3 backDirection = displacement.reverse().normalize();
            return new RailPath(displacement.length(), backDirection, backDirection);
        }

        BlockPos[] firstSteps = neighbours(level, start, from.getDeltaMovement(), Switches.JOINED);

        if (firstSteps == null) {
            return null;
        }

        // On a circuit short enough that both ways round come in under the limit, the nearer one is
        // the gap; whichever was walked first would sometimes be the long way round.
        RailPath nearest = null;

        for (BlockPos firstStep : firstSteps) {
            RailPath path = walk(level, from, to, start, firstStep, goal, limit);

            if (path != null && (nearest == null || path.distance() < nearest.distance())) {
                nearest = path;
            }
        }

        return nearest;
    }

    /**
     * The rail blocks under the track within {@code ahead} of {@code cart} the way it is moving and
     * within {@code behind} the other way, or {@code behind} both ways for a cart going nowhere, as
     * packed positions; empty for a cart off the rails. A junction is crossed straight and a switch
     * is left by every road it can be thrown to, since which one a cart takes is settled only as it
     * arrives.
     */
    public static LongSet covered(AbstractMinecart cart, double ahead, double behind) {
        Level level = cart.level();
        BlockPos start = cart.getCurrentBlockPosOrRailBelow();
        BlockState state = level.getBlockState(start);
        // The most reach any walk had left arriving at each block, so that a road reaching a
        // block a longer one covered first still goes on as far as its own reach allows.
        Long2DoubleMap reached = new Long2DoubleOpenHashMap();

        if (!BaseRailBlock.isRail(state)) {
            return reached.keySet();
        }

        reached.put(start.asLong(), Math.max(ahead, behind));

        Vec3 movement = cart.getDeltaMovement().horizontal();
        boolean moving = movement.lengthSqr() > EPSILON * EPSILON;

        // Standing on a switch, the car may yet be steered out by any of its three ends.
        if (state.getBlock() instanceof SwitchedRailBlock) {
            Vec3 centrePosition = Vec3.atCenterOf(start).horizontal();

            for (Direction end : switchEnds(state)) {
                Vec3 edgePosition = centrePosition.add(
                        new Vec3(end.getStepX(), 0.0, end.getStepZ()).scale(0.5));

                cover(level, start, neighbour(level, start, end.getUnitVec3i(), false),
                        (moving ? ahead : behind)
                                - cart.position().horizontal().distanceTo(edgePosition),
                        reached);
            }

            return reached.keySet();
        }

        // A junction is left by the axis it is laid to when the cart moves, which a crossing cart
        // may lay again first, so both are covered, the one it is not moving along only as far as
        // its own length; any other rail is left as it is laid.
        RailShape[] railShapes = state.getBlock() instanceof JunctionRailBlock
                ? new RailShape[] {RailShape.NORTH_SOUTH, RailShape.EAST_WEST}
                : new RailShape[] {shape(state, Vec3.ZERO, Switches.LAID)};

        for (RailShape railShape : railShapes) {
            boolean along = moving && (railShapes.length == 1
                    || railShape == JunctionRailBlock.shapeFor(movement));
            BlockPos[] around = neighboursFor(level, start, railShape);
            BlockPos aheadExit = along ? exitTaken(around, start, movement) : around[0];
            BlockPos behindExit = aheadExit == around[0] ? around[1] : around[0];

            cover(level, start, aheadExit, (along ? ahead : behind)
                    - distanceToExit(cart.position(), start, around, direction(start, aheadExit)),
                    reached);
            cover(level, start, behindExit, behind
                    - distanceToExit(cart.position(), start, around, direction(start, behindExit)),
                    reached);
        }

        return reached.keySet();
    }

    /**
     * Whether the rail {@code cart} stands on climbs, drops or runs level in the direction of
     * {@code towardsDirection}: 1, -1 or 0.
     *
     * <p>The direction is an argument rather than the cart's own movement, which a cart standing
     * still has none of. A train stalled on a hill is exactly when its grade has to be legible,
     * and the direction is then the line its cars lie along rather than anything they are doing.
     */
    public static int grade(AbstractMinecart cart, Vec3 towardsDirection) {
        BlockState state = cart.level().getBlockState(cart.getCurrentBlockPosOrRailBelow());
        Vec3 alongDirection = towardsDirection.horizontal();

        if (alongDirection.lengthSqr() < EPSILON * EPSILON) {
            return 0;
        }

        RailShape railShape = shape(state, alongDirection, Switches.LAID);

        return railShape == null ? 0 : grade(railShape, alongDirection);
    }

    /**
     * The height the rail at {@code pos}, laid to {@code railShape}, runs at over the point
     * ({@code x}, {@code z}): where vanilla's stepper keeps a car standing on it.
     */
    public static double heightAt(BlockPos pos, RailShape railShape, double x, double z) {
        var exits = AbstractMinecart.exits(railShape);
        Vec3i firstExit = exits.getFirst();
        Vec3i secondExit = exits.getSecond();

        if (firstExit.getY() == secondExit.getY()) {
            return pos.getY() + RAIL_LIFT;
        }

        // The exit marked level is the one the rail climbs towards; the rise runs from nought at
        // the low edge to one at that one.
        Vec3i high = firstExit.getY() == 0 ? firstExit : secondExit;
        double rise = 0.5 + (x - pos.getX() - 0.5) * high.getX()
                + (z - pos.getZ() - 0.5) * high.getZ();

        return pos.getY() + RAIL_LIFT + Math.clamp(rise, 0.0, 1.0);
    }

    /**
     * How the track runs across the block at {@code pos} for a cart entering it by
     * {@code movement}, or null if that block is not a rail.
     */
    public static @Nullable Crossing crossing(Level level, BlockPos pos, Vec3 movement) {
        RailShape railShape = shape(level.getBlockState(pos), movement, Switches.LAID);

        if (railShape == null) {
            return null;
        }

        BlockPos[] around = neighboursFor(level, pos, railShape);
        Vec3 exitDirection = direction(pos, exitTaken(around, pos, movement));
        Vec3 entryDirection = otherExit(around, pos, exitDirection);
        Vec3 floorPosition = Vec3.atBottomCenterOf(pos);

        return new Crossing(
                floorPosition.add(entryDirection.scale(0.5))
                        .add(0.0, edgeHeight(railShape, entryDirection), 0.0),
                floorPosition.add(exitDirection.scale(0.5))
                        .add(0.0, edgeHeight(railShape, exitDirection), 0.0),
                exitDirection,
                span(around, pos, exitDirection));
    }

    /**
     * The rail block a cart leaves the block at {@code pos} by, travelling {@code movement}, or
     * null where {@code pos} is not a rail or the cart is not going anywhere.
     */
    public static @Nullable BlockPos ahead(Level level, BlockPos pos, Vec3 movement) {
        if (movement.horizontalDistanceSqr() <= 0.0) {
            return null;
        }

        BlockPos[] around = neighbours(level, pos, movement);

        return around == null ? null : exitTaken(around, pos, movement);
    }

    /**
     * The two rails the block at {@code pos} exits onto, in the order its shape lists them, taking
     * a switch as it is set; null if that block is not a rail.
     *
     * <p>On a slope the Y of an exit marks which end is the low one, it is not an offset to add:
     * the rail beyond the high end stands a block up, and the one beyond the low end is level with
     * the slope or a block down. Offsetting by the marker lands on air a block under the track
     * instead. {@code movement} only matters on a junction rail, which picks its axis from it.
     */
    public static BlockPos @Nullable [] neighbours(Level level, BlockPos pos, Vec3 movement) {
        return neighbours(level, pos, movement, Switches.LAID);
    }

    // As the public neighbours, taking a switch the given way.
    private static BlockPos @Nullable [] neighbours(
            Level level, BlockPos pos, Vec3 movement, Switches switches) {
        RailShape railShape = shape(level.getBlockState(pos), movement, switches);

        return railShape == null ? null : neighboursFor(level, pos, railShape);
    }

    // The two blocks a rail laid to railShape at pos exits onto.
    private static BlockPos[] neighboursFor(Level level, BlockPos pos, RailShape railShape) {
        var exits = AbstractMinecart.exits(railShape);
        Vec3i firstExit = exits.getFirst();
        Vec3i secondExit = exits.getSecond();
        boolean sloped = firstExit.getY() != secondExit.getY();

        return new BlockPos[] {
                neighbour(level, pos, firstExit, sloped && firstExit.getY() == 0),
                neighbour(level, pos, secondExit, sloped && secondExit.getY() == 0)};
    }

    // How high above its own block the rail runs at the edge in the direction of edge. Level track
    // sits a tenth proud all the way across; a slope climbs a whole block between its two edges, so
    // the high one is that much further up. Reading the ends of the line adjustToRails walks means
    // a placed car and a car vanilla stepped agree about where the rail is.
    private static double edgeHeight(RailShape railShape, Vec3 edge) {
        var exits = AbstractMinecart.exits(railShape);
        Vec3i firstExit = exits.getFirst();
        Vec3i secondExit = exits.getSecond();

        if (firstExit.getY() == secondExit.getY()) {
            return RAIL_LIFT;
        }

        // The exit marked level is the one the rail climbs towards; the other marks the low end.
        Vec3i high = firstExit.getY() == 0 ? firstExit : secondExit;

        return outwardComponent(high, edge) > 0.0 ? 1.0 + RAIL_LIFT : RAIL_LIFT;
    }

    // Whether the rail climbs, drops or runs level in the direction of alongDirection.
    private static int grade(RailShape railShape, Vec3 alongDirection) {
        var exits = AbstractMinecart.exits(railShape);
        Vec3i firstExit = exits.getFirst();
        Vec3i secondExit = exits.getSecond();

        if (firstExit.getY() == secondExit.getY()) {
            return 0;
        }

        // On a slope the exit marked level is the one the rail climbs towards, and the other is
        // marked a block down; it is a label for which end is which, not an offset to travel by.
        double climb = outwardComponent(
                firstExit.getY() == 0 ? firstExit : secondExit, alongDirection);

        // A direction across the slope rather than along it climbs neither way. Cannot arise from
        // an exit, which is always on the axis, but a chord between two carts can lie anywhere.
        return Math.abs(climb) < EPSILON ? 0 : (climb > 0.0 ? 1 : -1);
    }

    // Whichever of the block's two exits the movement is heading closest to.
    private static BlockPos exitTaken(BlockPos[] around, BlockPos pos, Vec3 movement) {
        double firstAlignment = direction(pos, around[0]).dot(movement);
        double secondAlignment = direction(pos, around[1]).dot(movement);

        return firstAlignment >= secondAlignment ? around[0] : around[1];
    }

    // How much of forward points out of the block by exit.
    private static double outwardComponent(Vec3i exit, Vec3 forward) {
        return forward.x * exit.getX() + forward.z * exit.getZ();
    }

    // Walks the track from start out through firstStep, returning the path to goal or null if the
    // walk runs off the rails, dead-ends or passes the limit.
    private static @Nullable RailPath walk(Level level, Entity from, Entity to,
            BlockPos start, BlockPos firstStep, BlockPos goal, double limit) {
        Vec3 fromExit = direction(start, firstStep);
        BlockPos previous = start;
        BlockPos current = firstStep;
        double walkedDistance = 0.0;

        // Bounded by the track walked, not the blocks it took: a corner is worth seven tenths of a
        // block, so spending the limit one block per step left a link round a bend barely two
        // thirds the reach a straight one got. The cap only guards a circuit short enough to loop.
        int maxSteps = (int) Math.ceil(limit / MIN_BLOCK_SPAN) + 1;

        for (int steps = 1; steps <= maxSteps && walkedDistance <= limit; steps++) {
            if (current.equals(goal)) {
                Vec3 toEntry = direction(current, previous);
                BlockPos[] aroundStart = neighbours(level, start, fromExit, Switches.JOINED);
                BlockPos[] aroundGoal = neighbours(level, goal, toEntry, Switches.JOINED);

                // A formality: between walks only from rail to rail.
                if (aroundStart == null || aroundGoal == null) {
                    return null;
                }

                // Each block is worth the run of track across it, not a flat one: a corner is only
                // the chord between two adjacent edges, about seven tenths of a block.
                double distance = distanceToExit(from.position(), start, aroundStart, fromExit)
                        + walkedDistance
                        + distanceToExit(to.position(), goal, aroundGoal, toEntry);

                return new RailPath(distance, otherExit(aroundStart, start, fromExit), toEntry);
            }

            // One reading of the block's exits serves both the run of track across it and the step
            // off it — the whole per-block cost of the walk.
            Vec3 movement = direction(previous, current);
            BlockPos[] around = neighbours(level, current, movement, Switches.JOINED);

            if (around == null) {
                return null;
            }

            BlockPos onward = exitTaken(around, current, movement);
            walkedDistance += span(around, current, direction(current, onward));

            previous = current;
            current = onward;
        }

        return null;
    }

    // Adds to reached every rail a walk from previous onto current enters with reach left over,
    // budget being what is left of it at current's near edge. Every block costs at least a
    // corner's worth, so the walk ends however the track loops.
    private static void cover(Level level, BlockPos previous, BlockPos current, double budget,
            Long2DoubleMap reached) {
        while (budget > 0.0) {
            BlockState state = level.getBlockState(current);

            // A block some walk reached with as much reach left has been walked on from there.
            if (!BaseRailBlock.isRail(state)
                    || reached.getOrDefault(current.asLong(), -1.0) >= budget) {
                return;
            }

            reached.put(current.asLong(), budget);

            Vec3 movement = direction(previous, current);

            if (state.getBlock() instanceof SwitchedRailBlock) {
                for (Direction end : switchEnds(state)) {
                    BlockPos onward = neighbour(level, current, end.getUnitVec3i(), false);

                    if (!onward.equals(previous)) {
                        cover(level, current, onward, budget - MIN_BLOCK_SPAN, reached);
                    }
                }

                return;
            }

            BlockPos[] around = neighbours(level, current, movement, Switches.LAID);

            if (around == null) {
                return;
            }

            BlockPos onward = exitTaken(around, current, movement);

            budget -= Math.max(span(around, current, direction(current, onward)),
                    MIN_BLOCK_SPAN);
            previous = current;
            current = onward;
        }
    }

    // The three ends a switch joins, whichever way it is thrown.
    private static Direction[] switchEnds(BlockState state) {
        Direction facing = state.getValue(SwitchedRailBlock.FACING);

        return new Direction[] {
            facing, facing.getOpposite(), SwitchedRailBlock.rightEdge(facing)};
    }

    // How much of block, whose exits are around, a cart at position has left to cross before exit.
    private static double distanceToExit(
            Vec3 position, BlockPos block, BlockPos[] around, Vec3 exit) {
        Vec3 leavingPosition = Vec3.atCenterOf(block).horizontal().add(exit.scale(0.5));

        return Math.min(position.horizontal().distanceTo(leavingPosition),
                span(around, block, exit));
    }

    // The run of track across block, between the midpoints of its two edges.
    private static double span(BlockPos[] around, BlockPos block, Vec3 exit) {
        Vec3 centrePosition = Vec3.atCenterOf(block).horizontal();

        return centrePosition.add(otherExit(around, block, exit).scale(0.5))
                .distanceTo(centrePosition.add(exit.scale(0.5)));
    }

    // The exit a cart entering through exit would leave by.
    private static Vec3 otherExit(BlockPos[] around, BlockPos block, Vec3 exit) {
        return direction(block, exitTaken(around, block, exit.reverse()));
    }

    // The shape a moving cart sees, or null where the block is not a rail. A junction rail
    // selects its axis from that movement. A switch is read by its rails rather than its throw,
    // but only where the question is whether track connects — the ribbon records where a car did
    // go, and there the switch as laid is the truth.
    private static @Nullable RailShape shape(BlockState state, Vec3 movement, Switches switches) {
        if (!BaseRailBlock.isRail(state)) {
            return null;
        }

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
        int xSign = Integer.signum(to.getX() - from.getX());

        if (xSign != 0) {
            return xSign > 0 ? EAST : WEST;
        }

        int zSign = Integer.signum(to.getZ() - from.getZ());

        if (zSign != 0) {
            return zSign > 0 ? SOUTH : NORTH;
        }

        return Vec3.ZERO;
    }
}
