package club.rainbowkitty.minecarttweaks.rail;

import org.jspecify.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.rule.MTGameRules;

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
 * <p>"Exactly vanilla" is only well defined for a single unlinked cart, because vanilla has no
 * trains. So the default is bit-exact for one cart and reproduces the <em>ensemble</em> for a
 * train: what those same carts would do in vanilla if a player were pushing them along in
 * formation. Several of this mod's scalings exist to rebuild that ensemble rather than to depart
 * from it — the placer leaves one car running vanilla's pipeline and writes the rest — and those
 * are not gated, because turning them off yields neither model.
 *
 * <p>{@link #cartMass} is the whole of how mass is gated. Everything that divides by a mass reads
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
        public Vec3 retarded(AbstractMinecart cart, Vec3 movement) {
            return RailDrag.shared(cart, movement);
        }

        @Override
        public double bendLoss(double turn) {
            return 0.0;
        }

        @Override
        public @Nullable Vec3 engine(Vec3 movement, Vec3 push, double drive) {
            return null;
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
        public Vec3 retarded(AbstractMinecart cart, Vec3 movement) {
            return RailDrag.slowed(cart, movement);
        }

        @Override
        public double bendLoss(double turn) {
            return RailDrag.bendFraction(turn);
        }

        @Override
        public @Nullable Vec3 engine(Vec3 movement, Vec3 push, double drive) {
            return movement.add(push.scale(drive));
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

    // What an empty cart weighs, and so the unit every other figure here is expressed in.
    private static final double EMPTY_MASS = 1.0;

    // A furnace cart instead, carrying its firebox. Costs it half a car's worth of its own output,
    // which is what makes double-heading worth doing.
    private static final double ENGINE_MASS = 1.5;

    // A full container cart's contents, and a passenger, on top of the cart itself.
    private static final double FULL_CARGO = 1.0;

    private static final double RIDER_MASS = 1.0;

    // The comparator reading a full container gives.
    private static final double FULL_SIGNAL = 15.0;

    /**
     * Which model {@code level} runs. Vanilla wherever no rule can be read, the client included:
     * nothing in this model is the client's to apply.
     */
    public static RailForces of(Level level) {
        return level instanceof ServerLevel serverLevel
                && serverLevel.getGameRules().get(MTGameRules.REALISTIC_PHYSICS)
                ? PHYSICAL
                : VANILLA;
    }

    /**
     * What one cart weighs, in empty carts. A uniform 1.0 under {@link #VANILLA}, which is what
     * keeps a lone cart bit-exact: {@code TrainCoupling.mass} answers 1 for an unlinked cart today,
     * and that 1 is what makes the shove divided by mass indistinguishable from vanilla's.
     */
    public abstract double cartMass(AbstractMinecart cart);

    /**
     * {@code movement} with this model's whole retarding law taken off it. Vanilla's is a fraction
     * of the speed per tick, varying by kind of cart and averaged over a train; this mod's adds a
     * flat term divided by mass, which reaches zero in finite time and runs the load dependence the
     * other way — see {@link RailDrag}.
     */
    public abstract Vec3 retarded(AbstractMinecart cart, Vec3 movement);

    /**
     * The fraction of its speed a train loses to {@code turn} right angles of kink in its track.
     */
    public abstract double bendLoss(double turn);

    /**
     * {@code movement} with a lit engine's impulse added, or null to leave vanilla's own engine to
     * it. Vanilla decays the cart's speed and re-adds the impulse each tick, which is a curve that
     * tails off; this mod pushes at a flat force divided by the train's mass.
     */
    public abstract @Nullable Vec3 engine(Vec3 movement, Vec3 push, double drive);

    /**
     * A furnace cart's speed ceiling, given the {@code halved} figure vanilla computes and the
     * {@code full} one every other cart gets. Vanilla halves it, which leaves an engine slower than
     * the carts it tows; only this mod's model has a reason to undo that.
     */
    public abstract double topSpeed(double halved, double full);

    /**
     * Whether two carts meeting hard enough destroy each other, rather than stopping or shunting
     * each other along. Wrecking goes with the model rather than staying on a rule of its own: it
     * is the one place this mod's forces break a player's property, and a world that asked for
     * vanilla motion has not asked for that.
     */
    public abstract boolean wrecks();

    // What one cart weighs under the physical model. Read per tick rather than cached, since a
    // hopper cart's mass changes continuously and a comparator read is a short loop.
    private static double weigh(AbstractMinecart cart) {
        double mass = cart instanceof MinecartFurnace ? ENGINE_MASS : EMPTY_MASS;

        if (cart instanceof AbstractMinecartContainer container) {
            mass += cargo(container);
        }

        return mass + cart.getPassengers().size() * RIDER_MASS;
    }

    // What a container cart's contents weigh, on the comparator's own scale — the same reading
    // vanilla's friction is built from, so neither model has to measure a cart twice. An unopened
    // loot cart reads as full, which is vanilla's own choice at AbstractMinecartContainer:102.
    private static double cargo(AbstractMinecartContainer container) {
        if (container.getContainerLootTable() != null) {
            return FULL_CARGO;
        }

        return FULL_CARGO
                * AbstractContainerMenu.getRedstoneSignalFromContainer(container) / FULL_SIGNAL;
    }
}
