package club.rainbowkitty.minecarttweaks.rail;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.rule.MTGameRules;
import club.rainbowkitty.minecarttweaks.train.Train;
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

    // Vanilla's own figures for a container cart, which it varies by how full the cart is: the
    // comparator reading, nought to fifteen, is subtracted from full and each step back is worth
    // this much less friction. An empty chest cart keeps 0.995 against a plain cart's 0.975.
    private static final double CONTAINER_KEEP = 0.98;

    private static final double CONTAINER_EMPTIER = 0.001;

    private static final int FULL_SIGNAL = 15;

    // What vanilla leaves of any cart's kept fraction once it is in water.
    private static final double WATER_KEEP = 0.95;

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
    public static Vec3 slowed(AbstractMinecart cart, Vec3 movement) {
        Vec3 slowed = shared(cart, movement);

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

    /**
     * {@code slowed} rescaled from this car's own rolling friction to its whole train's mean.
     *
     * <p>Only the leading car reaches vanilla's slowdown at all now, so without this a train
     * coasts on whichever kind of car happens to be leading it — and vanilla's figures differ by
     * five to one between an empty chest cart and a plain one. Averaging keeps the spread, which
     * is vanilla's own mechanic and worth having, while making the train rather than one car of it
     * the thing that carries the load.
     *
     * <p>A train whose cars are all alike averages to exactly what its leader was already given,
     * whatever vanilla's figures happen to be, so this only ever moves a train of mixed cars.
     */
    private static Vec3 shared(AbstractMinecart cart, Vec3 slowed) {
        if (!cart.hasLink() || !MTGameRules.trainsEnabled(cart.level())) {
            return slowed;
        }

        List<AbstractMinecart> cars = Train.of(cart).cars();
        double own = keepOf(cart);
        double total = 0.0;

        for (AbstractMinecart car : cars) {
            total += keepOf(car);
        }

        if (own <= 0.0) {
            return slowed;
        }

        double scale = total / cars.size() / own;

        return slowed.multiply(scale, 1.0, scale);
    }

    // Vanilla's rolling friction for one cart, worked out the way that kind of cart works it out:
    // a container cart from how empty it is, anything else off its behaviour, either of them cut
    // again in water. Read rather than asked for, because asking means running the cart's whole
    // slowdown — which for a furnace cart re-aims the engine, and for every car but the leader
    // would be a second helping of friction on a car the placer is going to move anyway.
    private static double keepOf(AbstractMinecart cart) {
        double keep = cart instanceof AbstractMinecartContainer container
                        && container.getContainerLootTable() == null
                ? CONTAINER_KEEP + emptiness(container) * CONTAINER_EMPTIER
                : cart.getBehavior().getSlowdownFactor();

        return cart.isInWater() ? keep * WATER_KEEP : keep;
    }

    // How far from full a container cart is, on the comparator's own scale.
    private static int emptiness(AbstractMinecartContainer container) {
        return FULL_SIGNAL - AbstractContainerMenu.getRedstoneSignalFromContainer(container);
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
