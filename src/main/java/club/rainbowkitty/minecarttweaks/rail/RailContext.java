package club.rainbowkitty.minecarttweaks.rail;

import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongSet;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;

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

    // How far past its own half-length the track a cart covers reaches, so that two carts whose
    // boxes meet at a block edge both cover the block across it.
    private static final double COVER_SLACK = 0.1;

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
        RailContext mine = cart.minecarttweaks$railContext();
        RailContext theirs = other.minecarttweaks$railContext();

        return mine.covers(theirs) || theirs.covers(mine);
    }

    /**
     * The rail blocks {@code cart} can be over before its next move ends: its own half-length
     * either side of where it stands, and on over the ground its movement carries it, walked along
     * the rails. Callers should prefer the cart's own memo.
     */
    public static LongSet covering(AbstractMinecart cart) {
        double halfLength = cart.getBbWidth() * 0.5 + COVER_SLACK;

        return RailPath.covered(
                cart, cart.getDeltaMovement().horizontalDistance() + halfLength, halfLength);
    }

    /**
     * Whether two carts can run into each other before either's next move ends: the track each
     * covers, from where it stands now, shares a block. Walked along the rails, so it follows a
     * curve, a grade or a switch's roads where a straight reach would leave them, and two carts
     * crossing one junction both cover it.
     */
    public static boolean meets(AbstractMinecart cart, AbstractMinecart other) {
        LongSet mine = cart.minecarttweaks$coveredTrack();
        LongSet theirs = other.minecarttweaks$coveredTrack();
        LongSet fewer = mine.size() <= theirs.size() ? mine : theirs;
        LongSet more = fewer == mine ? theirs : mine;

        for (LongIterator blocks = fewer.iterator(); blocks.hasNext(); ) {
            if (more.contains(blocks.nextLong())) {
                return true;
            }
        }

        return false;
    }

    /** Whether the rail {@code other} stands on is this stretch of track or a step off it. */
    public boolean covers(RailContext other) {
        if (!onRails() || !other.onRails()) {
            return false;
        }

        BlockPos pos = other.rail();
        return pos.equals(rail) || pos.equals(first) || pos.equals(second);
    }
}
