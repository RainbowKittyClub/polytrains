package club.rainbowkitty.polytrains.rail;

import java.util.List;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;

/**
 * The stretch of track a cart occupies: the rail it stands on and the two its exits lead to. Held
 * so "are these two on the same piece of track" can be answered by comparing block positions
 * rather than walking the rails — cheap enough to ask from the collision path. Whether two carts
 * can meet further off than that is {@link #meets}, which walks the rails once per cart and move.
 *
 * <p>A cart caches one of these per tick. It is therefore as of the start of the tick, and a cart
 * moving fast enough to cross a block within one has a neighbourhood a block behind where it now
 * is — harmless, because the three blocks recorded still overlap the one it moved to.
 *
 * @param rail the rail the cart stands on, or null if it is not on one
 * @param first the block one exit leads to, rail or not; null only in {@link #OFF_RAILS}
 * @param second the block the other exit leads to, rail or not; null only in {@link #OFF_RAILS}
 */
public record RailContext(
        @Nullable BlockPos rail,
        @Nullable BlockPos first,
        @Nullable BlockPos second) {

    /** A cart that is not on a rail at all, and so shares track with nothing. */
    public static final RailContext OFF_RAILS = new RailContext(null, null, null);

    // How far past where its movement carries its middle the track a cart covers reaches, so that
    // two carts whose boxes meet at a block edge both cover the block across it.
    private static final double COVER_SLACK = 0.1;

    // Closer than this two boxes' faces count as touching rather than overlapping: the tolerance
    // VoxelShape.collide settles a face at.
    private static final double OVERLAP_TOLERANCE = Shapes.EPSILON;

    // Bounds on where two runs' points stand: an upper and a lower one on each of three axes, and
    // the two sides of the square the second run's fraction along it keeps to.
    private static final int BOUNDS = 8;

    /** Whether the cart stands on a rail at all. */
    public boolean onRails() {
        return rail != null;
    }

    /** Reads {@code cart}'s current stretch of track. Callers should prefer the cart's own memo. */
    public static RailContext of(AbstractMinecart cart) {
        Level level = cart.level();
        BlockPos rail = cart.getCurrentBlockPosOrRailBelow();

        // Read off the block, not isOnRails(): a seated car skips the movement code that sets
        // that flag, so it can be stale.
        BlockPos[] around =
                RailPath.neighbours(level, rail, cart.getDeltaMovement().horizontal());

        if (around == null) {
            return OFF_RAILS;
        }

        return new RailContext(rail, around[0], around[1]);
    }

    /**
     * Whether two carts stand on the same rail or one exit apart — the limit of what this checks.
     * It covers every pair a tick of ordinary movement can bring together, while a pair closing
     * faster than a block a tick can meet beyond it, caught instead by {@link #meets}.
     *
     * <p>True if either says so: a junction rail picks its shape from the cart reading it, so a
     * cart on one sees only its own axis, and erring towards neighbours merely collides as vanilla
     * intends. Both sides read the start-of-tick memo, not live positions, or a cart crossing a
     * block boundary mid-tick gives the pair walls that disagree.
     */
    public static boolean sharesTrack(AbstractMinecart cart, AbstractMinecart other) {
        RailContext mine = cart.polytrains$railContext();
        RailContext theirs = other.polytrains$railContext();

        return mine.covers(theirs) || theirs.covers(mine);
    }

    /**
     * The track {@code cart} can be over before its next move ends: its middle as far along the
     * rails as its movement carries it and a little either way, and its box half its length
     * further. Callers should prefer the cart's own memo.
     */
    public static RailPath.Reach covering(AbstractMinecart cart) {
        return RailPath.covered(
                cart, cart.getDeltaMovement().horizontalDistance() + COVER_SLACK, COVER_SLACK);
    }

    /**
     * Whether two carts can run into each other before either's next move ends: the track each
     * covers, from where it stands now, shares a block, or their boxes ridden along it overlap.
     * Walked along the rails, so it follows a curve, a grade or a switch's roads where a straight
     * reach would leave them, and two carts crossing one junction both cover it.
     *
     * <p>The boxes answer what the blocks cannot: at a curve a box spills off its own line into a
     * line beside it, its own track's or another's, whose rails lie further along the track than
     * either cart gets.
     */
    public static boolean meets(AbstractMinecart cart, AbstractMinecart other) {
        // Asked in one order whichever cart asks, so the two answers cannot differ by a rounding.
        AbstractMinecart lowerCart = cart.getId() <= other.getId() ? cart : other;
        AbstractMinecart higherCart = lowerCart == cart ? other : cart;
        RailPath.Reach mine = lowerCart.polytrains$coveredTrack();
        RailPath.Reach theirs = higherCart.polytrains$coveredTrack();

        return sharesRail(mine.rails(), theirs.rails())
                || boxesMeet(lowerCart, mine.runs(), higherCart, theirs.runs());
    }

    /** Whether the rail {@code other} stands on is this stretch of track or a step off it. */
    public boolean covers(RailContext other) {
        if (!onRails() || !other.onRails()) {
            return false;
        }

        BlockPos pos = other.rail();
        return pos.equals(rail) || pos.equals(first) || pos.equals(second);
    }

    // Whether the two sets of packed rail positions share one.
    private static boolean sharesRail(LongSet mine, LongSet theirs) {
        LongSet fewer = mine.size() <= theirs.size() ? mine : theirs;
        LongSet more = fewer == mine ? theirs : mine;

        for (LongIterator blocks = fewer.iterator(); blocks.hasNext(); ) {
            if (more.contains(blocks.nextLong())) {
                return true;
            }
        }

        return false;
    }

    // Whether the two carts' boxes overlap past OVERLAP_TOLERANCE on every axis with each middle
    // somewhere on its own runs. Either may be anywhere on its runs, since either may move first.
    private static boolean boxesMeet(AbstractMinecart cart, List<RailPath.Segment> runs,
            AbstractMinecart other, List<RailPath.Segment> otherRuns) {
        AABB box = cart.getBoundingBox();
        AABB otherBox = other.getBoundingBox();
        // How far cart's middle may stand from other's on each axis with the boxes overlapping.
        double[] lowest = {
            otherBox.minX - other.getX() - box.maxX + cart.getX() + OVERLAP_TOLERANCE,
            otherBox.minY - other.getY() - box.maxY + cart.getY() + OVERLAP_TOLERANCE,
            otherBox.minZ - other.getZ() - box.maxZ + cart.getZ() + OVERLAP_TOLERANCE};
        double[] highest = {
            otherBox.maxX - other.getX() - box.minX + cart.getX() - OVERLAP_TOLERANCE,
            otherBox.maxY - other.getY() - box.minY + cart.getY() - OVERLAP_TOLERANCE,
            otherBox.maxZ - other.getZ() - box.minZ + cart.getZ() - OVERLAP_TOLERANCE};

        for (RailPath.Segment run : runs) {
            for (RailPath.Segment otherRun : otherRuns) {
                if (standsWithin(run, otherRun, lowest, highest)) {
                    return true;
                }
            }
        }

        return false;
    }

    // Whether a point of run and a point of otherRun stand apart by between lowest and highest on
    // every axis, each indexed by axis. The two points lie fractions s and t along their runs, and
    // each bound is a line across the unit square of (s, t): eliminating t pairs every bound above
    // it with every one below, leaving an interval of s that holds a point if the bounds do.
    private static boolean standsWithin(RailPath.Segment run, RailPath.Segment otherRun,
            double[] lowest, double[] highest) {
        // Each axis alone first, over the whole square: nearly every pair fails one outright.
        for (Direction.Axis axis : Direction.Axis.values()) {
            double start = run.from().get(axis) - otherRun.from().get(axis);
            double gain = run.to().get(axis) - run.from().get(axis);
            double otherGain = otherRun.from().get(axis) - otherRun.to().get(axis);

            if (start + Math.max(0.0, gain) + Math.max(0.0, otherGain) < lowest[axis.ordinal()]
                    || start + Math.min(0.0, gain) + Math.min(0.0, otherGain)
                            > highest[axis.ordinal()]) {
                return false;
            }
        }

        // Each bound as sGain * s + tGain * t <= limit: two an axis, and t's own two sides.
        double[] sGains = new double[BOUNDS];
        double[] tGains = new double[BOUNDS];
        double[] limits = new double[BOUNDS];

        for (Direction.Axis axis : Direction.Axis.values()) {
            int upper = 2 * axis.ordinal();
            double start = run.from().get(axis) - otherRun.from().get(axis);

            sGains[upper] = run.to().get(axis) - run.from().get(axis);
            tGains[upper] = otherRun.from().get(axis) - otherRun.to().get(axis);
            limits[upper] = highest[axis.ordinal()] - start;
            sGains[upper + 1] = -sGains[upper];
            tGains[upper + 1] = -tGains[upper];
            limits[upper + 1] = start - lowest[axis.ordinal()];
        }

        tGains[BOUNDS - 2] = -1.0;
        tGains[BOUNDS - 1] = 1.0;
        limits[BOUNDS - 1] = 1.0;

        // The interval of s left, from s's own two sides.
        double[] interval = {0.0, 1.0};

        for (int above = 0; above < BOUNDS; above++) {
            if (tGains[above] == 0.0) {
                if (!narrow(interval, sGains[above], limits[above])) {
                    return false;
                }
            } else if (tGains[above] > 0.0) {
                for (int below = 0; below < BOUNDS; below++) {
                    double rise = tGains[above];
                    double fall = -tGains[below];

                    if (fall > 0.0 && !narrow(interval,
                            fall * sGains[above] + rise * sGains[below],
                            fall * limits[above] + rise * limits[below])) {
                        return false;
                    }
                }
            }
        }

        return interval[0] <= interval[1];
    }

    // Narrows the interval of s to where sGain * s <= limit; false once nothing of it is left.
    private static boolean narrow(double[] interval, double sGain, double limit) {
        if (sGain > 0.0) {
            interval[1] = Math.min(interval[1], limit / sGain);
        } else if (sGain < 0.0) {
            interval[0] = Math.max(interval[0], limit / sGain);
        } else if (limit < 0.0) {
            return false;
        }

        return interval[0] <= interval[1];
    }
}
