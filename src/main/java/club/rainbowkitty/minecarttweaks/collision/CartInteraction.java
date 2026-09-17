package club.rainbowkitty.minecarttweaks.collision;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.gamerules.GameRule;

import club.rainbowkitty.minecarttweaks.ext.Linkable;
import club.rainbowkitty.minecarttweaks.rail.RailContext;
import club.rainbowkitty.minecarttweaks.rule.MTGameRules;
import club.rainbowkitty.minecarttweaks.train.TrainCoupling;
import club.rainbowkitty.minecarttweaks.train.TrainPlacer;

/**
 * The single answer to what a cart does to something it meets. The movement code
 * ({@link AbstractMinecart#canCollideWith}), vanilla's mutual shove ({@code push}) and
 * {@link CartImpact} all read it from here, so the three cannot disagree with each other.
 */
public enum CartInteraction {

    /** Not a collision at all — the cart neither notices it nor is stopped by it. */
    IGNORE(false, false),

    /** Vanilla's solid collision: each stops the other, and a moving cart still throws it clear. */
    COLLIDE(true, true),

    /** Pickup: the cart is stopped by it until it takes it aboard, and never harms it. */
    COLLECT(true, false),

    /** Driven straight through, hurt and thrown off the rail. */
    RUN_OVER(false, true),

    /** Two trains meeting hard enough that neither survives it. */
    WRECK(false, false),

    /** Two cars of one train the placer is not holding: each stops the other, neither harms it. */
    BUFFER(true, false);

    // Speed at which a cart starts running things down, in blocks per tick: above it any cart at
    // all injures what it meets and drives on through, below it a cart still throws what it is up
    // against clear but does no harm doing so, and is stopped by it as vanilla intends.
    private static final double HURTING_SPEED = 0.2;

    // Below this a cart is standing rather than moving slowly, so nothing it is up against is being
    // run down however hard it is trying to pull away.
    private static final double STANDING = 1.0E-3;

    private final boolean blocks;
    private final boolean strikes;

    CartInteraction(boolean blocks, boolean strikes) {
        this.blocks = blocks;
        this.strikes = strikes;
    }

    /**
     * What {@code cart} does to {@code other} this tick.
     *
     * <p>Hot — asked once per candidate per move step — so every test is a field read or a cached
     * train walk. Must stay symmetric: {@code getEntityCollisions} asks each cart about the other
     * separately, and an asymmetric answer would give one a wall the other did not have.
     */
    public static CartInteraction of(AbstractMinecart cart, Entity other) {
        // Vanilla's own participation test first, so nothing below has to repeat it.
        if (!participates(cart, other)) {
            return IGNORE;
        }

        if (other instanceof AbstractMinecart car) {
            // Carts that merely pass close by on separate track are no concern of each other's.
            if (!RailContext.sharesTrack(cart, car)) {
                return IGNORE;
            }

            // Two cars of one train never wreck each other and never strike each other, in any
            // state the train can be in: a train coming apart must not destroy its own cars. What
            // does change with the state is whether they are solid to each other — while the placer
            // lays them at fixed offsets they must not block each other or the car leading them,
            // and while it is not laying them they are ordinary carts that have to stop each other
            // or an unplaced train piles into its own head.
            if (TrainCoupling.sameTrain(cart, car) || Linkable.coupled(cart, car)) {
                return placed(cart, car) ? IGNORE : BUFFER;
            }

            return wrecks(cart, car) ? WRECK : COLLIDE;
        }

        // Ahead of running it over on purpose: a cart slow enough to collect is by default slow
        // enough that a lone one does no harm anyway, and a long train creeping along should be
        // loading livestock rather than killing it.
        if (collects(cart, other)) {
            return COLLECT;
        }

        return runsOver(cart, other) ? RUN_OVER : COLLIDE;
    }

    /**
     * Whether the pair are in this conversation at all. {@link CartImpact}'s sweep filters on this
     * rather than on {@code canCollideWith}, which by then answers no to what it means to hit.
     */
    public static boolean participates(AbstractMinecart cart, Entity other) {
        return AbstractBoat.canVehicleCollide(cart, other);
    }

    /**
     * Whether the pair exchange momentum. Only a player or another cart: {@code stepAlongTrack}
     * folds even a sideways shove back into track speed, so anything that can push a cart brakes it
     * far harder than it moves it. Asked from both sides, because vanilla shoves from both and the
     * two arrive at different mixins.
     *
     * <p>The {@code sameTrain} test is what still holds for a derailed car, which collides with its
     * neighbours but must not be shoved by them while it is trying to land.
     */
    public static boolean pushes(AbstractMinecart cart, Entity other) {
        return (other instanceof Player || other instanceof AbstractMinecart)
                && of(cart, other).blocks()
                && !TrainCoupling.sameTrain(cart, other);
    }

    /**
     * How hard {@code cart} hits: its speed alone. Length is not a term — a train hits with its
     * leading car, and what length bears on is how little the impact slows it, which is
     * {@link CartImpact}'s to apply. One figure drives both the harm threshold and the damage, so
     * the two cannot disagree.
     */
    public static double impactSpeed(AbstractMinecart cart) {
        return cart.getDeltaMovement().length();
    }

    /**
     * Whether {@code cart} is hitting hard enough to injure what it meets, by
     * {@code minecarttweaks:cart_impact_speed}. The default is 4 blocks per second, and it is
     * the same for a lone cart and for the head of a train of fifty.
     */
    public static boolean injures(AbstractMinecart cart) {
        double limit = threshold(cart, MTGameRules.CART_IMPACT_SPEED, Double.MAX_VALUE);

        // Squared: this is on the per-candidate path, where impactSpeed's sqrt is not worth taking.
        return cart.getDeltaMovement().lengthSqr() > limit * limit;
    }

    /**
     * Whether {@code cart} is in a state to take a mob aboard at all: empty, built to carry one,
     * and moving slowly enough by {@code minecarttweaks:cart_pickup_speed}. Everything the pickup
     * turns on is about the mob rather than the cart, and vanilla tests those identically, so this
     * is the whole of what the pickup site has to ask.
     */
    public static boolean gathers(AbstractMinecart cart) {
        double limit = threshold(cart, MTGameRules.CART_PICKUP_SPEED, 0.0);

        return cart.isRideable() && !cart.isVehicle()
                && cart.getDeltaMovement().lengthSqr() < limit * limit;
    }

    /** Whether the cart's movement is stopped by it — what {@code canCollideWith} answers. */
    public boolean blocks() {
        return blocks;
    }

    /** Whether the cart hits it, throwing it clear and injuring it if it is moving fast enough. */
    public boolean strikes() {
        return strikes;
    }

    // Coupled cars pass through each other, but only while both are on the rails that hold them
    // apart: a derailed car is a solid obstacle again, which stops the train closing up over it.
    // Rail memos first, then the train test, which is the expensive half.
    // Whether the placer is holding this pair where it wants them, in which case neither may be
    // solid to the other. A derailed car is never placed, so it goes on stopping its neighbours
    // while it is trying to land, which is what it did before any of this.
    private static boolean placed(AbstractMinecart cart, AbstractMinecart other) {
        return cart.minecarttweaks$railContext().onRails()
                && other.minecarttweaks$railContext().onRails()
                && (TrainPlacer.seated(cart) || TrainPlacer.seated(other));
    }

    // Two trains meeting hard enough to write each other off. Read off the closing momentum rather
    // than either train's own speed, so a heavy train overhauling a light one does not wreck it for
    // the crime of being caught up with.
    private static boolean wrecks(AbstractMinecart cart, AbstractMinecart other) {
        double limit = threshold(cart, MTGameRules.CART_WRECK_MOMENTUM, 0.0);

        return limit > 0 && TrainCoupling.closingMomentum(cart, other) > limit;
    }

    // Vanilla's own pickup filter, plus a speed of its own: only a cart slow enough to stop for
    // something takes it aboard. A cart already carrying something, never meant to, or arriving too
    // fast to be getting on runs the thing over instead.
    private static boolean collects(AbstractMinecart cart, Entity other) {
        return gathers(cart)
                && !(other instanceof Player)
                && !(other instanceof IronGolem)
                && !other.isPassenger();
    }

    // Every threshold here is a game rule stated per second, against speeds measured per tick. Off
    // the server there is no rule to read, so each caller names the value that makes its own test
    // answer no — the client only predicts movement and never decides whether something is harmed.
    private static double threshold(
            AbstractMinecart cart, GameRule<Integer> rule, double offServer) {
        return cart.level() instanceof ServerLevel level
                ? level.getGameRules().get(rule) / 20.0
                : offServer;
    }

    // Whether the cart owns the collision rather than yielding to it: an engine at any speed, and
    // any cart at all once it is moving fast enough to injure.
    private static boolean runsOver(AbstractMinecart cart, Entity other) {
        // The train's own speed, not this car's. A placed car's movement is written from the
        // leader's, so reading it here would have each car answer from a copy — and a car whose
        // copy is stale answers differently from its neighbours, leaving a train that is solid at
        // one end and walked through at the other.
        double speed = TrainCoupling.drivenSpeed(cart);

        // Speed first: underPower walks the train, and a cart about to hit something is almost
        // always over the bar anyway. Power only counts while the train is actually moving — it
        // covers a laden train pulling away too slowly to clear the bar, not a standing one. A
        // burning furnace cart held against a buffer is under power indefinitely, and because the
        // drive is shared every car of its train would answer yes and let players walk through it.
        if (speed > HURTING_SPEED || (speed > STANDING && TrainCoupling.underPower(cart))) {
            return true;
        }

        // Anything a cart can injure it drives through as well, or it stalls on what it is killing.
        // Only bites when cart_impact_speed is set below HURTING_SPEED, and never for a player, who
        // must be able to lean on a slow train without it turning on them.
        return !(other instanceof Player) && injures(cart);
    }
}
