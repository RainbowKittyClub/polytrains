package club.rainbowkitty.minecarttweaks.rail;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A car's step along its rail, kept on the line of the step when it meets another cart. Vanilla's
 * move settles one axis at a time, so a step up or down a slope or round a corner that meets a
 * cart's box leaves that line: lifted off a slope, set down on a roof, or slid sideways off a
 * corner. The stepper corrects only a car it finds below a slope, by lifting it straight up
 * through whatever held it down, and a car left beside its rail is lifted by the offset too.
 */
public final class SlopeStep {

    // Closer than this two faces count as touching: the tolerance VoxelShape.collide settles a
    // face at.
    private static final double TOUCH = 1.0E-7;

    // How far off its rail's line, or its step's, a car may stand and still be on it.
    private static final double ON_LINE = 1.0E-3;

    // Static-only; blocks instantiation.
    private SlopeStep() {}

    /**
     * Runs {@code move}, the stepper moving {@code cart} by {@code step} along the rail at
     * {@code railPos} laid to {@code shape}, and puts the car back on the step's line where it
     * first touched another cart if the move left that line or carried it past there. The move
     * stands otherwise, with the collisions and pushes it reported.
     *
     * <p>{@code Entity.move} settles height first, so a step up a slope into another cart's box
     * rises without advancing, one down a slope onto a cart's roof slides across it, and one up a
     * slope under a cart's floor slides beneath it, to be lifted into it by the stepper after. A
     * step round a corner is held on one axis and slides on along the other.
     */
    public static void moveOnRail(AbstractMinecart cart, Vec3 step, BlockPos railPos,
            RailShape shape, Runnable move) {
        Vec3 start = cart.position();
        // Read before the move, as the move reads it: the pushes the move hands out change
        // speeds, and whether a cart may pass through something can turn on its speed.
        double reach = reach(cart, step, railPos, shape);

        move.run();

        if (reach >= 1.0) {
            return;
        }

        Vec3 flatStep = step.horizontal();
        double progress = flatStep.lengthSqr() < TOUCH * TOUCH ? 0.0
                : cart.position().subtract(start).horizontal().dot(flatStep)
                        / flatStep.lengthSqr();

        // Near its line counts as on it only short of where it touched: past there it slid over
        // a roof, which can leave it far enough above its rail for the next step to lose it.
        if (progress <= reach && onLine(start, step, cart.position())) {
            return;
        }

        // No further along the step than the move itself took the car, which may have been
        // held back more.
        cart.setPos(start.add(step.scale(Math.clamp(Math.min(reach, progress), 0.0, 1.0))));
        // A car on its rail stands on the rail, not on the roof the move set it down on.
        cart.setOnGround(false);
    }

    /**
     * Whether {@code movement}, with nothing along the slope laid to {@code shape}, leaves it by
     * its low end. Vanilla sends such a movement out by the shape's first exit and reads it as
     * climbing, aiming a block above that exit: a block above the rail wherever the first exit
     * is the low one, which it is on an ascending east or south rail.
     */
    public static boolean squareDownSlope(Vec3 movement, RailShape shape) {
        var exits = AbstractMinecart.exits(shape);
        Vec3i firstExit = exits.getFirst();

        return firstExit.getY() < exits.getSecond().getY()
                && movement.x() * firstExit.getX() + movement.z() * firstExit.getZ() == 0.0;
    }

    // How much of step the car can take along its line before touching an entity it cannot pass
    // through, one if it never does, which leaves whatever bends the move to blocks. One too for
    // a car already above its rail, which has no line left to hold to. Blocks are the move's own
    // affair: the collision context it builds for a cart leaves out the ones a slope stands on.
    private static double reach(AbstractMinecart cart, Vec3 step, BlockPos railPos,
            RailShape shape) {
        if (aboveRail(cart.position(), railPos, shape)) {
            return 1.0;
        }

        AABB box = cart.getBoundingBox();
        double reach = 1.0;

        for (VoxelShape solid : cart.level().getEntityCollisions(cart, box.expandTowards(step))) {
            reach = Math.min(reach, touchAt(box, step, solid.bounds()));
        }

        return reach;
    }

    // Whether position stands above the line the rail at railPos, laid to shape, runs along.
    private static boolean aboveRail(Vec3 position, BlockPos railPos, RailShape shape) {
        return position.y() - RailPath.heightAt(railPos, shape, position.x(), position.z())
                > ON_LINE;
    }

    // Whether position stands on the line step runs along from start.
    private static boolean onLine(Vec3 start, Vec3 step, Vec3 position) {
        Vec3 offset = position.subtract(start);
        double stepLengthSqr = step.lengthSqr();
        Vec3 across = stepLengthSqr < TOUCH * TOUCH ? offset
                : offset.subtract(step.scale(offset.dot(step) / stepLengthSqr));

        return across.lengthSqr() <= ON_LINE * ON_LINE;
    }

    // The fraction of step box covers before it first touches other, or one if it never does. A
    // box overlapping other already is let through, as the move itself lets it out.
    private static double touchAt(AABB box, Vec3 step, AABB other) {
        double enter = Double.NEGATIVE_INFINITY;
        double leave = Double.POSITIVE_INFINITY;

        for (Direction.Axis axis : Direction.Axis.VALUES) {
            double move = step.get(axis);
            double low = box.min(axis);
            double high = box.max(axis);

            if (move == 0.0) {
                if (high <= other.min(axis) + TOUCH || low >= other.max(axis) - TOUCH) {
                    return 1.0;
                }

                continue;
            }

            // Measured the way the step moves: the gap still to close, and the run until clear.
            double ahead = move > 0.0 ? other.min(axis) - high : low - other.max(axis);
            double through = move > 0.0 ? other.max(axis) - low : high - other.min(axis);

            if (through <= TOUCH) {
                return 1.0;
            }

            if (ahead >= -TOUCH) {
                enter = Math.max(enter, Math.max(ahead, 0.0) / Math.abs(move));
            }

            leave = Math.min(leave, through / Math.abs(move));
        }

        return enter == Double.NEGATIVE_INFINITY || enter >= leave || enter >= 1.0 ? 1.0 : enter;
    }
}
