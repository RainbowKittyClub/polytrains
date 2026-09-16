package club.rainbowkitty.minecarttweaks.rail;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.init.MTAttachments;
import club.rainbowkitty.minecarttweaks.rule.MTGameRules;

/**
 * What a powered rail does to a cart standing on it. Vanilla's unpowered powered rail is a brake
 * with no release: it stops a cart dead, and unless a solid block happens to sit against one side
 * of the rail there is nothing to start it again when the signal comes back. The heading the cart
 * arrived on is kept while it waits, and handed back as the kick vanilla would have given it.
 */
public final class RailBrake {

    // The push vanilla gives a cart it launches from a standstill, so a cart released here leaves
    // exactly as a block-assisted one does (NewMinecartBehavior#calculateBoostTrackSpeed).
    private static final double LAUNCH_SPEED = 0.2;

    // Below this a cart is not travelling any way worth remembering.
    private static final double MOVING_EPSILON = 1.0E-6;

    // Above this a cart is still rolling, so it is not waiting on anything. Vanilla's own figure
    // for the same question, at the head of the boost it would otherwise be getting.
    private static final double ROLLING_SPEED = 0.01;

    // Static-only; blocks instantiation.
    private RailBrake() {}

    /** Whether {@code cart} is standing on a rail that should stop it. */
    public static boolean brakes(AbstractMinecart cart) {
        BlockState rail = cart.level().getBlockState(cart.getCurrentBlockPosOrRailBelow());

        // A powered rail brakes while it has no signal, and accelerates once it has one.
        return rail.is(Blocks.POWERED_RAIL) && !rail.getValue(PoweredRailBlock.POWERED);
    }

    /**
     * Keeps {@code cart}'s brake state current: the heading it arrived on while a brake holds it,
     * and the kick that sends it off again once the signal returns. Called once a tick per cart,
     * before it moves, so the movement read is the one it came in on.
     */
    public static void tick(ServerLevel level, AbstractMinecart cart) {
        // A furnace cart keeps its heading in vanilla's own impulse, which the parking brake
        // suspends rather than clears, so it already leaves on that — and on any re-aim it was
        // given while it waited, which a heading remembered here would overrule.
        if (cart instanceof MinecartFurnace) {
            return;
        }

        // Read before anything is recorded, so the rule is a live switch: turned off, the mod both
        // stops holding headings and drops any a cart is still carrying from when it was on.
        if (!level.getGameRules().get(MTGameRules.CARTS_RESUME_FROM_BRAKES)) {
            forget(cart);
            return;
        }

        BlockState rail = level.getBlockState(cart.getCurrentBlockPosOrRailBelow());

        if (!rail.is(Blocks.POWERED_RAIL)) {
            // A cart that has left the rail is not waiting on it, and a heading kept past that
            // would fire the next time it came to rest somewhere else entirely.
            forget(cart);
            return;
        }

        if (rail.getValue(PoweredRailBlock.POWERED)) {
            release(cart);
        } else {
            remember(cart);
        }
    }

    // Records the direction a cart is still rolling in as the brake brings it to a stop, so there
    // is something to leave on once the signal comes back.
    private static void remember(AbstractMinecart cart) {
        Vec3 movement = cart.getDeltaMovement().horizontal();

        if (movement.lengthSqr() <= MOVING_EPSILON) {
            return;
        }

        Direction heading = Direction.getApproximateNearest(movement.x(), 0.0, movement.z());

        // Written only on a change: this runs every tick of a cart's approach, and an attachment
        // rewritten each time would mark the entity dirty for nothing.
        if (MTAttachments.getHeldHeading(cart) != heading) {
            MTAttachments.setHeldHeading(cart, heading);
        }
    }

    // Sends a waiting cart off the way it came in. A cart still rolling never stopped to need this,
    // and vanilla's own boost already has it.
    private static void release(AbstractMinecart cart) {
        Direction heading = MTAttachments.getHeldHeading(cart);

        if (heading == null
                || cart.getDeltaMovement().horizontalDistanceSqr()
                        > ROLLING_SPEED * ROLLING_SPEED) {
            return;
        }

        forget(cart);
        cart.setDeltaMovement(
                heading.getStepX() * LAUNCH_SPEED, 0.0, heading.getStepZ() * LAUNCH_SPEED);
    }

    // Drops a remembered heading, if there is one.
    private static void forget(AbstractMinecart cart) {
        if (MTAttachments.getHeldHeading(cart) != null) {
            MTAttachments.setHeldHeading(cart, null);
        }
    }

}
