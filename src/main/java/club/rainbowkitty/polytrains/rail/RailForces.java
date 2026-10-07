package club.rainbowkitty.polytrains.rail;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;
import net.minecraft.world.entity.vehicle.minecart.MinecartHopper;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.polytrains.rule.PhysicsGameRules;

/**
 * Which set of force laws a cart runs on, chosen by {@code realistic_physics}. Every decision the
 * two models disagree about is a method here, so the pair can be read against each other in one
 * place and a law added without an answer for both is a compile error rather than a site someone
 * forgot to gate.
 *
 * <p>{@link #VANILLA} is the default and is vanilla's own motion: each of these either returns the
 * figure vanilla computed or declines to fire at all. {@link #PHYSICAL} is the model this mod grew
 * — forces divided by what a cart weighs, a flat retarding term that brings a cart to a dead stop,
 * and a charge for every kink the track turns through.
 *
 * <p>"Exactly vanilla" is only well defined for a single unlinked cart, since vanilla has no
 * trains. The default is bit-exact for one cart and reproduces the <em>ensemble</em> for a train:
 * what those same carts would do in vanilla if a player were pushing them along in formation.
 * Several of this mod's scalings exist to rebuild that ensemble rather than depart from it — the
 * placer leaves one car running vanilla's pipeline and writes the rest — so those stay ungated;
 * turning them off would yield neither model.
 *
 * <p>{@link #cartMass} is the whole of how mass is gated: everything that divides by a mass reads
 * it from here, and at a uniform 1.0 those divisions are the car counts they were before this
 * existed, so the drive divisor and the slope weighting need no model of their own.
 */
public enum RailForces {

    /** Vanilla's own friction, engine and ceiling, and no wrecking. */
    VANILLA {
        @Override
        public double cartMass(AbstractMinecart cart) {
            return EMPTY_MASS;
        }

        @Override
        public Vec3 retarded(AbstractMinecart cart, Vec3 movement, Vec3 applied) {
            return RailDrag.shared(cart, applied);
        }

        @Override
        public double bendLoss(double turn) {
            return 0.0;
        }

        @Override
        public boolean drivesEngine() {
            return false;
        }

        @Override
        public double engineOutput(double speed) {
            return VANILLA_IMPULSE;
        }

        @Override
        public double launch(double kick, double share) {
            return share > 0.0 ? kick : 0.0;
        }

        @Override
        public Vec3 halted(Vec3 movement, double share) {
            return movement.scale(1.0 - share * (1.0 - RailBrake.HALT_KEEP));
        }

        @Override
        public double topSpeed(double halved, double full) {
            return halved;
        }

        @Override
        public boolean wrecks() {
            return false;
        }
    },

    /** This mod's own model: force over mass, a flat stop, and a price for cornering. */
    PHYSICAL {
        @Override
        public double cartMass(AbstractMinecart cart) {
            return weigh(cart);
        }

        @Override
        public Vec3 retarded(AbstractMinecart cart, Vec3 movement, Vec3 applied) {
            return RailDrag.resisted(cart, movement);
        }

        @Override
        public double bendLoss(double turn) {
            return RailDrag.bendFraction(turn);
        }

        @Override
        public boolean drivesEngine() {
            return true;
        }

        @Override
        public double engineOutput(double speed) {
            return speed <= BASE_SPEED
                    ? ENGINE_FORCE
                    : ENGINE_FORCE * BASE_SPEED / speed;
        }

        @Override
        public double launch(double kick, double share) {
            return kick * share;
        }

        @Override
        public Vec3 halted(Vec3 movement, double share) {
            double speed = movement.length();
            double left = speed - BRAKE_FORCE * share;

            return left <= 0.0 ? Vec3.ZERO : movement.scale(left / speed);
        }

        @Override
        public double topSpeed(double halved, double full) {
            return full;
        }

        @Override
        public boolean wrecks() {
            return true;
        }
    };

    // What an empty cart weighs. Every mass in this mod is in these units — "empty minecarts" —
    // including the ones a player sets, so a game rule's figure and a figure here compare directly.
    private static final double EMPTY_MASS = 1.0;

    // A furnace cart instead, carrying its firebox. Costs it half an empty minecart's worth of its
    // own output, which is what makes double-heading worth doing.
    private static final double ENGINE_MASS = 1.5;

    // A chest cart empty and full. Its box is heavy before it holds anything, and 27 slots hold a
    // great deal, so it is both the heaviest empty cart and much the heaviest loaded one.
    private static final double CHEST_MASS = 1.2;

    // A chest cart fully laden.
    private static final double CHEST_LADEN = 3.0;

    // A hopper cart empty and full. A funnel, not cargo: empty it weighs what a plain cart does,
    // and five slots cannot hold much.
    private static final double HOPPER_MASS = 1.0;

    // A hopper cart fully laden.
    private static final double HOPPER_LADEN = 1.5;

    // Mass of one rider or passenger.
    private static final double RIDER_MASS = 1.0;

    // The comparator reading a full container gives.
    private static final double FULL_SIGNAL = 15.0;

    // What one lit engine puts out per tick under the physical model, before the train's mass
    // divides it down. Sized off the only fixed bar there is: a train climbs a grade when its
    // output beats gravity's own pull, so six times that pull puts the tipping point at mass 6.0 —
    // six empty minecarts. A short train climbs, loading it past that tips it to marginal or
    // stalls it, and a long one cannot at all.
    private static final double ENGINE_FORCE = 6.0 * RailDrag.SLOPE_PULL;

    // Speed at which the engine leaves its constant-torque region, in blocks per tick — 1.54 blocks
    // a second. Derived from the balance a powered train settles at: the flat rolling term is
    // suppressed under power, so effort meets only the proportional loss and mass cancels out of
    // both sides, leaving one car's top speed at the square root of ENGINE_FORCE times this over
    // the drag rate — 7.6 blocks a second for a lone engine, just under the default ceiling, so
    // the curve limits a furnace cart and the clamp is only a backstop. Train length divides it:
    // n cars top out at one car's speed over the square root of n.
    private static final double BASE_SPEED = 0.077;

    // What one braked car takes out of a train per tick under the physical model, before the
    // train's mass divides it down. Sized against the one reference vanilla offers: its own
    // halving stops a lone empty cart from the default ceiling in about three quarters of a block,
    // and a constant force of this size stops it in 0.73. Also comfortably twice the engine, so a
    // brake holds a double-headed train.
    private static final double BRAKE_FORCE = 14.0 * RailDrag.SLOPE_PULL;

    // Vanilla's engine, as a per-tick figure something other than the engine cart can be given.
    // Vanilla re-adds its whole impulse every tick against a 0.8 decay, so the impulse *is* the
    // output; this mod normalises it, making that one block a tick. Vanilla's own magnitude is how
    // far the player was standing from the cart, which is arbitrary and lands past the speed
    // ceiling for any plausible value, so a normalised one is observably the same engine.
    private static final double VANILLA_IMPULSE = 1.0;

    /**
     * Which model {@code level} runs. Vanilla wherever no rule can be read, the client included:
     * nothing in this model is the client's to apply.
     */
    public static RailForces forLevel(Level level) {
        return level instanceof ServerLevel serverLevel
                && serverLevel.getGameRules().get(PhysicsGameRules.REALISTIC_PHYSICS)
                ? PHYSICAL
                : VANILLA;
    }

    /**
     * What one cart weighs, in empty minecarts. A uniform 1.0 under {@link #VANILLA} keeps a lone
     * cart bit-exact: {@code TrainCoupling.mass} answers 1 for an unlinked cart today, making the
     * shove divided by mass indistinguishable from vanilla's.
     */
    public abstract double cartMass(AbstractMinecart cart);

    /**
     * This model's whole retarding law, given the {@code movement} the cart arrived with and what
     * vanilla's own slowdown {@code applied} to it. Both are passed rather than composed because
     * each model takes the one it's built on and discards the other: the vanilla model rescales
     * vanilla's figure to the train's mean, while the physical model throws that figure away and
     * works from the raw movement — see {@link RailDrag}.
     */
    public abstract Vec3 retarded(AbstractMinecart cart, Vec3 movement, Vec3 applied);

    /**
     * The fraction of its speed a train loses to {@code turn} right angles of kink in its track.
     */
    public abstract double bendLoss(double turn);

    /**
     * Whether this model replaces vanilla's engine with a force of its own. False leaves vanilla's
     * powered branch to run untouched — it decays the cart's speed and re-adds the impulse each
     * tick, a curve that tails off, and it re-aims the impulse itself on the way; true replaces
     * that with a flat force divided by the train's mass.
     */
    public abstract boolean drivesEngine();

    /**
     * What one lit engine puts out per tick at {@code speed}, in blocks per tick, before a train's
     * mass divides it down.
     *
     * <p>Asked even under the model that does not drive the engine: a train whose leading car is
     * not itself an engine still needs the drive given somewhere, and vanilla, having no trains,
     * states no figure for that. This is the one quantity the vanilla model has to synthesise
     * rather than read — what vanilla's own impulse amounts to per tick, handed to whichever car
     * is carrying the train.
     *
     * <p>The vanilla model ignores {@code speed} by construction, not by omission: vanilla re-adds
     * its whole impulse every tick whatever the cart is doing, against a decay, so its output is
     * flat and the curve it produces comes from the decay instead. The physical model is a traction
     * curve — flat to {@link #BASE_SPEED}, then falling as {@code 1/v} so power stays at the rating
     * the flat region ends on. That flat region is also a guard: it starts a heavy train, the grade
     * figures rest on it, and it stops the falling branch ever dividing by a speed near zero.
     *
     * <p>Because effort falls, this is a reading and not a property, and a test of it is not a test
     * of whether the engine is lit — see {@code polytrains$driving}.
     */
    public abstract double engineOutput(double speed);

    /**
     * How fast a standing train leaves when {@code share} of its mass is over lit rails, given the
     * {@code kick} vanilla gives one cart.
     *
     * <p>The one track force the two models answer differently rather than differing only through
     * {@link #cartMass}: vanilla's launch is a velocity it <em>assigns</em> rather than an impulse
     * it adds, and a velocity has no mass to divide by, so no arithmetic settles it and the two
     * models disagree outright. The default hands a train the whole of vanilla's kick off any one
     * lit rail, keeping a railway built on a single starter rail working; the physical model shares
     * it as momentum, so a long or loaded train wants proportionally more rail beneath it before it
     * will start.
     */
    public abstract double launch(double kick, double share);

    /**
     * What a braking train leaves the rail doing, given the {@code movement} it arrived with and
     * the {@code share} of one car's braking its whole mass is owed.
     *
     * <p>The other half of {@link #launch}: vanilla writes the brake as a fraction of the speed,
     * which no mass divides and whose integral does not survive being spread along a train. The
     * default keeps that fraction, since reproducing vanilla is its whole purpose — measured
     * consequence: a longer train sheds less over the same rail, an eight-car train losing about a
     * third less to one brake rail than a single cart does.
     *
     * <p>The physical model replaces it with a retarding force over the train's mass — the same
     * shape the boost, gravity and the engine already have. That makes a loaded train harder to
     * stop than an empty one, and it is the only form under which accelerating and braking answer
     * to a load the same way.
     */
    public abstract Vec3 halted(Vec3 movement, double share);

    /**
     * A furnace cart's speed ceiling, given the {@code halved} figure vanilla computes and the
     * {@code full} one every other cart gets. Vanilla halves it, which leaves an engine slower than
     * the carts it tows; only this mod's model has a reason to undo that.
     */
    public abstract double topSpeed(double halved, double full);

    /**
     * Whether two carts meeting hard enough destroy each other, rather than stopping or shunting
     * each other along. Wrecking goes with the model rather than a rule of its own, since it is
     * the one place this mod's forces break a player's property, and a world that asked for
     * vanilla motion hasn't asked for that.
     */
    public abstract boolean wrecks();

    // What one cart weighs under the physical model. Read per tick rather than cached, since a
    // hopper cart's mass changes continuously and a comparator read is a short loop.
    private static double weigh(AbstractMinecart cart) {
        return carriedMass(cart) + cart.getPassengers().size() * RIDER_MASS;
    }

    // What the cart and whatever it holds come to. Each kind of container carries its own pair of
    // endpoints rather than a shared cargo term, because what a cart weighs before it holds
    // anything and how much it can hold both differ by kind.
    private static double carriedMass(AbstractMinecart cart) {
        if (cart instanceof MinecartFurnace) {
            return ENGINE_MASS;
        }

        if (cart instanceof MinecartHopper hopper) {
            return interpolatedMass(hopper, HOPPER_MASS, HOPPER_LADEN);
        }

        // A chest cart, and anything else with an inventory: a box of goods on a cart is what that
        // pair describes, and there is nothing else to measure a modded container cart by.
        if (cart instanceof AbstractMinecartContainer container) {
            return interpolatedMass(container, CHEST_MASS, CHEST_LADEN);
        }

        return EMPTY_MASS;
    }

    // Where between empty and full a container cart sits, on the comparator's own scale — the same
    // reading vanilla's friction is built from, so neither model has to measure a cart twice. An
    // unopened loot cart reads as full, which is vanilla's own choice at
    // AbstractMinecartContainer:102.
    private static double interpolatedMass(
            AbstractMinecartContainer container, double empty, double full) {
        double fillSignal = container.getContainerLootTable() != null
                ? FULL_SIGNAL
                : AbstractContainerMenu.getRedstoneSignalFromContainer(container);

        return empty + (full - empty) * fillSignal / FULL_SIGNAL;
    }
}
