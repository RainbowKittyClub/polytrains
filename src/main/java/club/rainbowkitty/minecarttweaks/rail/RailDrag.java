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
 * The resistance this mod lays on a cart, over whatever vanilla's own slowdown left of its speed:
 * rolling resistance for every tick it is on rails, and a bend charge for every kink in the track
 * its train runs through. The two have opposite shapes on purpose, because they are different
 * mechanisms — see {@link #slowed} and {@link #bend}.
 *
 * <p>Rolling resistance is a flat loss of speed per tick rather than another fraction of it.
 * Vanilla's friction is purely proportional, so it never reaches zero and a cart crawls on long
 * after it looks stopped — worst with someone aboard, where the fraction is a three-hundredth a
 * tick. A constant loss reaches zero
 * in finite time by itself, and, being constant, cannot do what raising that fraction as the speed
 * fell used to: the product of two proportional terms has two equilibria on a slope, and a cart
 * starting from rest was caught at the slower of them, a little over one metre per second, with no
 * way to climb out to the speed the grade should have given it.
 *
 * <p>Nothing here scales with the length of a train, and that is not an omission. Resistance is per
 * car and so is mass, so a long train coasts like a short one — the same reason a long train rolls
 * downhill no faster than a single cart.
 *
 * <p>A class of its own rather than part of the mixin that asks for it, because vanilla overrides
 * that slowdown per kind of cart and not every override calls the one this used to hang off.
 * {@code AbstractMinecartContainer} returns its own figure outright, so a chest or hopper cart
 * reaches this only by being asked directly.
 */
public final class RailDrag {

    // Vanilla's own minimum per-tick pull on a slope (NewMinecartBehavior#calculateSlopeSpeed).
    private static final double SLOPE_PULL = 0.0078125;

    // Of the slope pull, the most rolling resistance is ever allowed to be. Gravity has to win on a
    // slope by a clear margin or a cart barely rolls down one: at a quarter an empty cart settles
    // at about 4.5 blocks a second against the 6.1 it would reach with no resistance at all. The
    // cap binds for every rail_drag_speed above two, which is the point — the rule's range runs to
    // a thousand, and taken at face value most of it would stop a cart rolling downhill at all.
    private static final double ROLL_CEILING = 0.25;

    // The fraction of its speed a train loses to a right angle of turn. Nothing like the physical
    // figure — a wheelset slamming into a 45-degree kink loses the whole of its sideways component,
    // about 29 percent, and vanilla track has no easements at all — so this is a gameplay number
    // chosen to make a four-corner circuit cost about eight percent a lap.
    private static final double BEND_LOSS = 0.02;

    // The fraction of its speed vanilla takes from an empty cart each tick, which is what
    // rail_drag_speed's own figure is read against (NewMinecartBehavior#getSlowdownFactor).
    private static final double EMPTY_DRAG = 0.025;

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
     * {@code movement} with a train's shared friction and this mod's rolling resistance taken off
     * it, or as little of either as is owed.
     *
     * <p>Resistance is not laid on while something is still driving the cart — an engine, or a
     * rider holding a direction, whose nudge is a thousandth of a block a tick and would be taken
     * off again here before it could accumulate into anything to start on. Bend loss is not behind
     * that guard and does not come through here at all; see {@link #bend}.
     */
    public static Vec3 slowed(AbstractMinecart cart, Vec3 movement) {
        Vec3 slowed = shared(cart, movement);

        if (TrainCoupling.underPower(cart) || TrainCoupling.riderSteering(cart)) {
            return slowed;
        }

        double speed = slowed.horizontalDistance();
        double roll = roll(cart);

        if (roll <= 0.0 || speed <= 0.0) {
            return slowed;
        }

        // The whole of what is left, where what is left is less than one tick's worth: a flat loss
        // is what brings a cart to a dead stop instead of forever approaching one.
        if (speed <= roll) {
            return new Vec3(0.0, slowed.y(), 0.0);
        }

        double left = (speed - roll) / speed;

        return new Vec3(slowed.x() * left, slowed.y(), slowed.z() * left);
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

        Train train = Train.of(cart);
        long tick = cart.level().getGameTime();
        double mean = train.rolling(tick);

        if (mean <= 0.0) {
            mean = meanKeep(train.cars());
            train.setRolling(tick, mean);
        }

        double own = keepOf(cart);

        if (own <= 0.0) {
            return slowed;
        }

        double scale = mean / own;

        return slowed.multiply(scale, 1.0, scale);
    }

    // Held for the tick by the train itself: vanilla asks for a slowdown once per rail block its
    // leading car crosses, which at speed is several times a tick, and every car's own figure
    // means a walk of its inventory.
    private static double meanKeep(List<AbstractMinecart> cars) {
        double total = 0.0;

        for (AbstractMinecart car : cars) {
            total += keepOf(car);
        }

        return total / cars.size();
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

    // How much speed rolling resistance takes off a cart in one tick, in blocks. rail_drag_speed
    // names the speed at which that loss matches vanilla's proportional drag on an empty cart, so
    // below that speed this is the larger of the two — which is what the rule has always said, by
    // a mechanism that now stops a cart rather than slowing its approach to a stop. None of it
    // wherever the rule does not reach, the client included.
    private static double roll(AbstractMinecart cart) {
        if (!(cart.level() instanceof ServerLevel serverLevel)) {
            return 0.0;
        }

        double until = serverLevel.getGameRules().get(MTGameRules.RAIL_DRAG_SPEED) / 20.0;

        return Math.min(until * EMPTY_DRAG, SLOPE_PULL * ROLL_CEILING);
    }

    /**
     * Takes off {@code cart} the speed its train has just lost running through {@code turn} right
     * angles of kink in its track. Charged where the turn is measured rather than through
     * {@link #slowed}: vanilla asks for slowdown once per rail block a car crosses, which at speed
     * is several times a tick, and an event billed per block would be billed several times over.
     *
     * <p>A fraction of the speed, not a flat loss, and the one place in this class where that is
     * the right shape. Rolling resistance is a force and a flat loss is how a force reads; a kink
     * is an impact, and what an impact costs is the sideways component of whatever speed arrived at
     * it — so a fast train loses proportionally the same at a corner as a slow one, which is what
     * makes the charge per corner rather than per tick spent in it. It also cannot stall anything:
     * the loss goes to nothing as the speed does, so an engine or a rider always outruns it from
     * rest, and bend loss needs none of the guards rolling resistance needs.
     *
     * <p>Not divided by the length of the train. Every car takes its own impact and every car
     * carries its own mass, so a long train loses the same fraction to a corner as a single cart —
     * the same reason nothing else here scales with length.
     */
    public static void bend(AbstractMinecart cart, double turn) {
        if (turn <= 0.0) {
            return;
        }

        double left = Math.max(0.0, 1.0 - BEND_LOSS * turn);

        cart.setDeltaMovement(cart.getDeltaMovement().multiply(left, 1.0, left));
    }
}
