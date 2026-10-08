package club.rainbowkitty.polytrains.rail;

import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.polytrains.init.EntityAttachments;
import club.rainbowkitty.polytrains.rule.ModGameRules;
import club.rainbowkitty.polytrains.train.TrainCoupling;

/**
 * What a powered rail does to a cart standing on it. Vanilla's unpowered powered rail is a brake
 * with no release: it stops a cart dead, and unless a solid block happens to sit against one side
 * of the rail there is nothing to start it again when the signal comes back. The heading the cart
 * arrived on is kept while it waits, and handed back as the kick vanilla would have given it.
 */
public final class RailBrake {

    // The push vanilla gives a cart it launches from a standstill, so a cart released here leaves
    // exactly as a block-assisted one does (NewMinecartBehavior#calculateBoostTrackSpeed). Shared
    // with TrainCoupling, which shares it out over a train the way vanilla cannot.
    public static final double LAUNCH_SPEED = 0.2;

    // What vanilla adds each tick to a cart already rolling over a lit rail, unlike the launch
    // above, which it assigns to one that is not — two branches of one method, two different
    // quantities a train treats differently.
    public static final double BOOST_SPEED = 0.06;

    // Above this a cart is still rolling, so it is not waiting on anything. Vanilla's own figure
    // for the same question, at the head of the boost it would otherwise be getting.
    public static final double ROLLING_SPEED = 0.01;

    // What vanilla leaves a cart each tick a brake has hold of it, and the speed under which it
    // stops the cart dead rather than halving it (NewMinecartBehavior#calculateHaltTrackSpeed).
    public static final double HALT_KEEP = 0.5;

    // Below this speed a braking cart is stopped dead.
    private static final double HALT_FLOOR = 0.03;

    // Below this a cart is not travelling any way worth remembering.
    private static final double MOVING_EPSILON = 1.0E-3;

    // Static-only; blocks instantiation.
    private RailBrake() {}

    /**
     * Whether {@code cart} is braking: standing on a rail that should stop it, or a furnace cart
     * with its brake lever thrown, which brakes as one car on such a rail does. One car's own
     * answer, and a train's brake is the share of its cars giving it; for whether a train is being
     * held, see {@link TrainCoupling#held}.
     */
    public static boolean brakes(AbstractMinecart cart) {
        return leverHeld(cart)
                || brakingRail(cart.level().getBlockState(cart.getCurrentBlockPosOrRailBelow()));
    }

    /**
     * Whether {@code cart} is a furnace cart with its brake lever thrown. Only an engine carries a
     * lever, so no other cart pays for the attachment lookup.
     */
    public static boolean leverHeld(AbstractMinecart cart) {
        return cart instanceof MinecartFurnace && EntityAttachments.leverSet(cart);
    }

    /** Whether {@code rail} brakes a cart standing on it: a powered rail with no signal. */
    public static boolean brakingRail(BlockState rail) {
        return rail.is(Blocks.POWERED_RAIL) && !rail.getValue(PoweredRailBlock.POWERED);
    }

    /** Whether {@code rail} drives a cart standing on it: a powered rail with its signal. */
    public static boolean boostingRail(BlockState rail) {
        return rail.is(Blocks.POWERED_RAIL) && rail.getValue(PoweredRailBlock.POWERED);
    }

    /**
     * Whether a brake stops {@code movement} dead rather than halving it, measured as vanilla's
     * brake measures it: in three dimensions, so on a grade not by the horizontal part alone.
     */
    public static boolean stopsDead(Vec3 movement) {
        return movement.length() < HALT_FLOOR;
    }

    /** Whether {@code cart} is standing on a rail that should drive it. */
    public static boolean boosts(AbstractMinecart cart) {
        return boostingRail(cart.level().getBlockState(cart.getCurrentBlockPosOrRailBelow()));
    }

    /**
     * Keeps {@code cart}'s brake state current: the heading it arrived on while a brake holds it,
     * and the kick that sends it off again once the signal returns. Called once a tick per cart,
     * before it moves, so the movement read is the one it came in on.
     */
    public static void tick(ServerLevel level, AbstractMinecart cart) {
        // A furnace cart keeps its heading in vanilla's own impulse, which the parking brake
        // suspends rather than clears — it already leaves on that, or on any re-aim given while
        // it waited, and remembering a heading here would overrule either.
        if (cart instanceof MinecartFurnace) {
            return;
        }

        // Read before anything is recorded, so the rule is a live switch: turned off, the mod both
        // stops holding headings and drops any a cart is still carrying from when it was on.
        if (!level.getGameRules().get(ModGameRules.CARTS_RESUME_FROM_BRAKES)) {
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
            remember(cart, rail);
        }
    }

    // Records the direction a cart is travelling over the brake, so there is something to leave on
    // once the signal comes back. Kept up to date while the cart moves at a speed the brake has
    // yet to stop dead, since that is travel: a cart that ran on past the brake and came back over
    // it, off a buffer or another cart, leaves the way it last went rather than the way it first
    // came. Slower than that the heading stands, so the scraps of movement a stopping cart has
    // left do not turn it round. A push along the rail hard enough to pass that floor is travel
    // too: a player walking into a waiting cart moves it the way they walk, and it leaves so.
    //
    // Read along the rail only, the one way a cart on it can go; a push across it, as a player
    // stepping out beside the cart gives, is no heading at all.
    private static void remember(AbstractMinecart cart, BlockState rail) {
        Direction.Axis axis = axisOf(rail.getValue(PoweredRailBlock.SHAPE));
        double along = cart.getDeltaMovement().get(axis);
        double floor = EntityAttachments.getHeldHeading(cart) == null
                ? MOVING_EPSILON
                : HALT_FLOOR;

        if (Math.abs(along) < floor) {
            return;
        }

        EntityAttachments.setHeldHeading(cart, Direction.fromAxisAndDirection(axis,
                along > 0.0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE));
    }

    // The horizontal axis a straight rail runs along, slopes included; a powered rail has no other.
    private static Direction.Axis axisOf(RailShape shape) {
        return switch (shape) {
            case NORTH_SOUTH, ASCENDING_NORTH, ASCENDING_SOUTH -> Direction.Axis.Z;
            default -> Direction.Axis.X;
        };
    }

    // Sends a waiting cart off the way it came in. A cart still rolling never stopped to need this,
    // and vanilla's own boost already has it. Every car of a train remembers its own heading, but
    // only the car vanilla drives is launched on it — a placed car's movement is written over
    // before it carries the launch anywhere.
    private static void release(AbstractMinecart cart) {
        Direction heading = EntityAttachments.getHeldHeading(cart);

        // A train with a brake still under any of its cars is not leaving, whatever the rail under
        // this one is doing; it would only be stopped again in the same tick.
        if (heading == null
                || TrainCoupling.drivenCar(cart) != cart
                || TrainCoupling.held(cart)
                || cart.getDeltaMovement().horizontalDistanceSqr()
                        > ROLLING_SPEED * ROLLING_SPEED) {
            return;
        }

        double speed = TrainCoupling.launchSpeed(cart);

        if (speed <= 0.0) {
            return;
        }

        forget(cart);
        cart.setDeltaMovement(heading.getStepX() * speed, 0.0, heading.getStepZ() * speed);
    }

    // Drops a remembered heading, if there is one.
    private static void forget(AbstractMinecart cart) {
        if (EntityAttachments.getHeldHeading(cart) != null) {
            EntityAttachments.setHeldHeading(cart, null);
        }
    }
}
