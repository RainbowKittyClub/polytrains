package club.rainbowkitty.polytrains.rail;

import java.util.List;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.polytrains.rule.ModGameRules;
import club.rainbowkitty.polytrains.train.Train;
import club.rainbowkitty.polytrains.train.TrainCoupling;

/**
 * How a cart loses speed: the two models' retarding laws, and the bend charge that goes with one of
 * them. {@link RailForces} picks between them; nothing here reads the rule.
 *
 * <p>{@link #shared} is the whole of the vanilla model — vanilla's own per-cart fraction, rescaled
 * to a train's mean so a mixed train coasts on its ensemble rather than on whichever car leads it.
 * {@link #resisted} is the physical model, which owns the law outright and discards vanilla's
 * factor: a proportional rate plus a flat term, both divided by what one car weighs.
 *
 * <p>The flat term is what brings a cart to a dead stop. Vanilla's friction is purely
 * proportional, so it never reaches zero and a cart crawls on long after it looks stopped — worst
 * with someone aboard, where the fraction is a three-hundredth a tick. A constant loss reaches zero
 * in finite time; a term that instead shrinks with speed cannot, since two proportional terms
 * produce two equilibria on a slope, catching a cart from rest at the slower one — a little over
 * one metre per second, short of the speed the grade should give it.
 *
 * <p>Neither law scales with the length of a train — not an omission. Resistance and mass are both
 * per car, so a long train coasts like a short one, the same reason it rolls downhill no faster
 * than a single cart. The physical model answers to what a car <em>carries</em>, dividing by the
 * mean per-car mass, not the train's total.
 *
 * <p>{@link #bend} is a fraction of the arriving speed rather than a flat loss, and that opposite
 * shape is deliberate: rolling resistance is a force, and a kink is an impact.
 *
 * <p>A class of its own rather than part of the mixin that asks for it: vanilla overrides that
 * slowdown per kind of cart, and not every override reaches the hook this would otherwise sit
 * behind. {@code AbstractMinecartContainer} returns its own figure outright, so a chest or hopper
 * cart reaches this only by being asked directly.
 */
public final class RailDrag {

    // Vanilla's own minimum per-tick pull on a slope (NewMinecartBehavior#calculateSlopeSpeed).
    // Shared with RailForces, which sizes the engine against it: a force that cannot beat gravity
    // is a force that cannot climb, and that is the only fixed bar either model has. Also what
    // TrainCoupling works a train's own pull out of, since vanilla hands it none to rescale.
    public static final double SLOPE_PULL = 0.0078125;

    // Of the slope pull, the most rolling resistance is ever allowed to be. Gravity has to win on a
    // slope by a clear margin or a cart barely rolls down one: at an eighth an empty cart settles
    // at about 5.3 blocks a second against the 6.1 it would reach with no resistance at all. It has
    // to stay well under the slope pull whatever else changes, or gravity loses on a grade and a
    // cart will not roll downhill at all — which is what left a rule reading against it no range to
    // grade across, and why the figure is now fixed here rather than set per world.
    private static final double ROLL_CEILING = 0.125;

    // The flat part of rolling resistance, in blocks of speed a tick, before mass. What brings a
    // cart to a dead stop rather than forever halving what is left.
    private static final double ROLL_TERM = SLOPE_PULL * ROLL_CEILING;

    // The fraction of its speed a train loses to a right angle of turn. Nothing like the physical
    // figure — a wheelset slamming into a 45-degree kink loses the whole of its sideways component,
    // about 29 percent, and vanilla track has no easements at all — so this is a gameplay number
    // chosen to make a four-corner circuit cost about eight percent a lap.
    private static final double BEND_LOSS = 0.02;

    // The fraction of its speed vanilla takes from an empty cart each tick
    // (NewMinecartBehavior#getSlowdownFactor). The physical model's proportional rate as well, so a
    // lone empty cart reads the same under both but for the flat term.
    private static final double EMPTY_DRAG = 0.025;

    // Vanilla's own figures for a container cart, which it varies by how full the cart is: the
    // comparator reading, nought to fifteen, is subtracted from full and each step back is worth
    // this much less friction. An empty chest cart keeps 0.995 against a plain cart's 0.975.
    private static final double CONTAINER_KEEP = 0.98;

    // Fraction of rolling friction lost per step up from empty for a container cart.
    private static final double CONTAINER_EMPTIER = 0.001;

    // Redstone comparator signal level of a full container.
    private static final int FULL_SIGNAL = 15;

    // What vanilla leaves of any cart's kept fraction once it is in water.
    private static final double WATER_KEEP = 0.95;

    // Static-only; blocks instantiation.
    private RailDrag() {}

    /**
     * Takes off {@code cart} the speed its train has just lost running through {@code turn} right
     * angles of kink in its track, or nothing at all under a model that does not charge for
     * cornering. Charged where the turn is measured rather than through {@link #resisted}: vanilla
     * asks for slowdown once per rail block a car crosses, which at speed is several times a tick,
     * and an event billed per block would be billed several times over.
     *
     * <p>A fraction of the speed, not a flat loss — the one place in this class where that shape is
     * right. Rolling resistance is a force, read as a flat loss; a kink is an impact, costing the
     * sideways component of whatever speed arrived at it, so a fast train loses proportionally the
     * same at a corner as a slow one and the charge lands per corner, not per tick spent in it. It
     * also cannot stall anything: the loss goes to nothing as the speed does, so an engine or a
     * rider always outruns it from rest, needing none of the guards rolling resistance needs.
     *
     * <p>Not divided by the length of the train, and not divided by its mass either. Every car
     * takes its own impact and every car carries its own mass, so a long train loses the same
     * fraction to a corner as a single cart, and a loaded one the same fraction as an empty one.
     * This is the one force here with no mass term, and adding one for consistency with the rest
     * would be wrong: what an impact costs is a fraction of the arriving speed, and the mass
     * cancels on both sides of that.
     */
    public static void bend(AbstractMinecart cart, double turn) {
        double lost = turn <= 0.0 ? 0.0 : RailForces.forLevel(cart.level()).bendLoss(turn);

        if (lost <= 0.0) {
            return;
        }

        double left = Math.max(0.0, 1.0 - lost);

        cart.setDeltaMovement(cart.getDeltaMovement().multiply(left, 1.0, left));
    }

    /**
     * The physical model's whole retarding law laid on {@code movement} — the movement the cart
     * arrived with, not anything vanilla left of it. Vanilla's own factor is discarded: it varies
     * by kind of cart and runs the load dependence backwards, the whole reason this model exists.
     *
     * <p>One deceleration, {@code (rate x speed + flat) / mass}, where {@code mass} is what one car
     * of the train weighs. The proportional part is the same rate for every cart — vanilla's own
     * figure for an empty one — and the flat part is what stops it dead.
     *
     * <p>The flat term is not laid on while something is driving the cart: an engine, or a rider
     * holding a direction, whose nudge is a thousandth of a block a tick and would be taken off
     * again here before it could accumulate into anything to start on. The proportional term stays,
     * because it cannot zero a speed and so cannot swallow a nudge. Bend loss is behind neither
     * guard and does not come through here at all; see {@link #bend}.
     */
    public static Vec3 resisted(AbstractMinecart cart, Vec3 movement) {
        double speed = movement.horizontalDistance();

        if (speed <= 0.0) {
            return movement;
        }

        double flat = TrainCoupling.underPower(cart) || TrainCoupling.riderSteering(cart)
                ? 0.0
                : ROLL_TERM;
        double lost = (rate(cart) * speed + flat) / TrainCoupling.meanCarMass(cart);

        // The whole of what is left, where what is left is less than one tick's worth: a flat loss
        // is what brings a cart to a dead stop instead of forever approaching one.
        if (speed <= lost) {
            return new Vec3(0.0, movement.y(), 0.0);
        }

        double left = (speed - lost) / speed;

        return new Vec3(movement.x() * left, movement.y(), movement.z() * left);
    }

    // The fraction of its speed the physical model takes each tick before mass is accounted for.
    // One rate for every kind of cart: vanilla's varies from a fortieth to a three-hundredth by
    // what the cart holds and who is aboard, but this model answers to mass instead — vanilla's
    // own empty-cart figure, so a lone empty cart reads the same in both models but for the flat
    // term. Water is vanilla's second helping, expressed as a rate rather than a factor.
    private static double rate(AbstractMinecart cart) {
        return cart.isInWater()
                ? 1.0 - (1.0 - EMPTY_DRAG) * WATER_KEEP
                : EMPTY_DRAG;
    }

    /**
     * What vanilla's slowdown {@code applied} rescales from this car's own rolling friction to its
     * whole train's mean — the whole of the vanilla model's retarding law; see
     * {@link RailForces#retarded}.
     *
     * <p>Only the leading car reaches vanilla's slowdown at all now, so without this a train
     * coasts on whichever kind of car happens to be leading it — and vanilla's figures differ by
     * five to one between an empty chest cart and a plain one. Averaging keeps that spread —
     * vanilla's own mechanic, worth keeping — while making the train, not one car of it, carry the
     * load.
     *
     * <p>A train whose cars are all alike averages to exactly what its leader was already given,
     * whatever vanilla's figures happen to be, so this only ever moves a train of mixed cars.
     */
    public static Vec3 shared(AbstractMinecart cart, Vec3 applied) {
        if (!cart.hasLink() || !ModGameRules.trainsEnabled(cart.level())) {
            return applied;
        }

        Train train = Train.of(cart);
        long tick = cart.level().getGameTime();
        double mean = train.rollingFriction(tick);

        if (mean <= 0.0) {
            mean = meanKeep(train.cars());
            train.setRollingFriction(tick, mean);
        }

        double ownKeep = keepOf(cart);

        if (ownKeep <= 0.0) {
            return applied;
        }

        double scale = mean / ownKeep;

        return applied.multiply(scale, 1.0, scale);
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
                ? CONTAINER_KEEP + emptiness(container) * CONTAINER_EMPTIER
                : cart.getBehavior().getSlowdownFactor();

        return cart.isInWater() ? keep * WATER_KEEP : keep;
    }

    // How far from full a container cart is, on the comparator's own scale. An unopened loot cart
    // is full: vanilla skips the emptiness bonus for one and leaves it a full cart's figure, rather
    // than falling back to a plain cart's (AbstractMinecartContainer:102-106).
    private static int emptiness(AbstractMinecartContainer container) {
        return container.getContainerLootTable() != null
                ? 0
                : FULL_SIGNAL - AbstractContainerMenu.getRedstoneSignalFromContainer(container);
    }

    /**
     * What the physical model charges for {@code turn} right angles of kink, as a fraction of the
     * speed. Named here so {@link RailForces} can pick between it and nothing at all without the
     * figure leaving the class that reasons about it.
     */
    public static double bendFraction(double turn) {
        return BEND_LOSS * turn;
    }
}
