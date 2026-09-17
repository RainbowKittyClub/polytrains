package club.rainbowkitty.minecarttweaks.rail;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.rule.MTGameRules;
import club.rainbowkitty.minecarttweaks.train.TrainCoupling;

/**
 * The extra rolling friction this mod lays on a slow cart, over whatever vanilla's own slowdown
 * left of its speed.
 *
 * <p>A class of its own rather than part of the mixin that asks for it, because vanilla overrides
 * that slowdown per kind of cart and not every override calls the one this used to hang off.
 * {@code AbstractMinecartContainer} returns its own figure outright, so a chest or hopper cart
 * reaches this only by being asked directly.
 */
public final class RailDrag {

    // Speed at which a cart is stopped outright, as a fraction of its own width per tick — slow
    // enough that it would take ten seconds to cover its own length.
    private static final double CRAWL = 1.0 / (20.0 * 10.0);

    // Of its speed a cart keeps per tick at a dead standstill, which the extra low-speed drag eases
    // back to one at rail_drag_speed. Vanilla's own figure is 0.975 empty and 0.997 ridden, so this
    // shortens a laden cart's last few blocks from most of a minute to about a second.
    private static final double SLOW_DRAG = 0.8;

    private RailDrag() {
    }

    /**
     * {@code slowed} with this mod's low-speed friction taken off it, or {@code slowed} unchanged
     * where none is owed.
     *
     * <p>Vanilla's rolling friction takes a fixed fraction of the speed, so the last of it is never
     * quite spent and a cart crawls on long after it looks stopped — worst with someone aboard,
     * where that fraction is a three-hundredth a tick. Below {@code rail_drag_speed} the fraction
     * is raised as the speed falls, and below a crawl what is left is dropped outright. Neither
     * applies while something is still driving the cart — an engine, or a rider holding a
     * direction, whose nudge is itself well under the crawl and would be swallowed here.
     */
    public static Vec3 slowed(AbstractMinecart cart, Vec3 slowed) {
        if (TrainCoupling.underPower(cart) || TrainCoupling.riderSteering(cart)) {
            return slowed;
        }

        // Scaled by length: a train moves slower under the same push, and one fixed floor swallows
        // its opening ticks before they accumulate into anything it could start on.
        double crawl = cart.getBbWidth() * CRAWL / TrainCoupling.mass(cart);
        double speed = slowed.horizontalDistance();

        if (speed < crawl) {
            return new Vec3(0.0, slowed.y(), 0.0);
        }

        double drag = drag(cart, speed);

        return drag < 1.0
                ? new Vec3(slowed.x() * drag, slowed.y(), slowed.z() * drag)
                : slowed;
    }

    // How much of its speed a cart under rail_drag_speed keeps: all of it at the rule's own speed,
    // falling to SLOW_DRAG at a standstill. One wherever the rule does not reach, client included.
    private static double drag(AbstractMinecart cart, double speed) {
        if (!(cart.level() instanceof ServerLevel serverLevel)) {
            return 1.0;
        }

        double until = serverLevel.getGameRules().get(MTGameRules.RAIL_DRAG_SPEED) / 20.0;

        if (until <= 0.0 || speed >= until) {
            return 1.0;
        }

        return SLOW_DRAG + (1.0 - SLOW_DRAG) * (speed / until);
    }
}
