package club.rainbowkitty.polytrains.collision;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.boat.AbstractBoat;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.gamerules.GameRule;

import club.rainbowkitty.polytrains.ext.Linkable;
import club.rainbowkitty.polytrains.rail.RailContext;
import club.rainbowkitty.polytrains.rail.RailForces;
import club.rainbowkitty.polytrains.rule.ModGameRules;
import club.rainbowkitty.polytrains.rule.PhysicsGameRules;
import club.rainbowkitty.polytrains.train.Train;
import club.rainbowkitty.polytrains.train.TrainCoupling;
import club.rainbowkitty.polytrains.train.TrainPlacer;

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

    /**
     * Two cars of one train the placer is not holding: each stops the other and shunts it along
     * as loose carts do, and neither harms it.
     */
    BUFFER(true, false);

    // Speed at which a cart starts running things down, in blocks per tick. Above it, any cart
    // injures what it meets and drives through; below it, a cart still throws what it's up against
    // clear but does no harm, and is stopped by it as vanilla intends.
    private static final double HURTING_SPEED = 0.2;

    // Below this a cart is standing rather than moving slowly, so nothing it is up against is being
    // run down however hard it is trying to pull away.
    private static final double STANDING = 1.0E-3;

    // Whether the cart's movement is stopped by it — what canCollideWith answers.
    private final boolean blocks;
    // Whether the cart hits it, throwing it clear and injuring it if moving fast enough.
    private final boolean strikes;

    // Constructs a collision type with its blocking and striking properties.
    private CartInteraction(boolean blocks, boolean strikes) {
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
            // Carts that merely pass close by on separate track are no concern of each other's. A
            // car off its rails shares no track, yet it still lands on or runs into another train.
            if (!RailContext.sharesTrack(cart, car) && !RailContext.meets(cart, car)
                    && !OffRails.meet(cart, car)) {
                return IGNORE;
            }

            // Two cars of one train never wreck or strike each other. What varies is whether they
            // are solid to each other: placed cars (fixed offsets) aren't solid to each other or
            // the leader; unplaced ones are ordinary carts that must stop each other, or an
            // unplaced train piles into its own head.
            if (TrainCoupling.sameTrain(cart, car) || Linkable.coupled(cart, car)) {
                return placed(cart, car) ? IGNORE : BUFFER;
            }

            return wrecks(cart, car) ? WRECK : COLLIDE;
        }

        // Checked before running over on purpose: a cart slow enough to collect already does no
        // harm alone, and a slow-creeping train should be loading livestock, not killing it.
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
     * <p>Two cars of one train shove each other only while the placer is not holding them apart,
     * which is what {@link #BUFFER} means: a train that cannot be seated is loose carts, and loose
     * carts shunt. Without that a train standing unseated could be pulled but never pushed, its
     * leading car stopped dead against the car behind it.
     */
    public static boolean pushes(AbstractMinecart cart, Entity other) {
        return (other instanceof Player || other instanceof AbstractMinecart)
                && of(cart, other).blocks();
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
     * {@code polytrains:cart_impact_speed}. The default is 4 blocks per second, and it is
     * the same for a lone cart and for the head of a train of fifty.
     */
    public static boolean injures(AbstractMinecart cart) {
        double speedLimit = threshold(cart, ModGameRules.CART_IMPACT_SPEED, Double.MAX_VALUE);

        // Squared: this is on the per-candidate path, where impactSpeed's sqrt is not worth taking.
        return cart.getDeltaMovement().lengthSqr() > speedLimit * speedLimit;
    }

    /**
     * Whether {@code cart} is in a state to take a mob aboard at all: empty, built to carry one,
     * and moving slowly enough by {@code polytrains:cart_pickup_speed}. Everything the pickup
     * turns on is about the mob rather than the cart, and vanilla tests those identically, so this
     * is the whole of what the pickup site has to ask.
     */
    public static boolean gathers(AbstractMinecart cart) {
        double speedLimit = threshold(cart, ModGameRules.CART_PICKUP_SPEED, 0.0);

        return cart.isRideable() && !cart.isVehicle()
                && cart.getDeltaMovement().lengthSqr() < speedLimit * speedLimit;
    }

    /** Whether the cart's movement is stopped by it — what {@code canCollideWith} answers. */
    public boolean blocks() {
        return blocks;
    }

    /** Whether the cart hits it, throwing it clear and injuring it if it is moving fast enough. */
    public boolean strikes() {
        return strikes;
    }

    // Whether the placer is holding this pair at fixed offsets, in which case neither is solid to
    // the other. A derailed car is never placed, so it keeps stopping its neighbours while it
    // tries to land — coupled cars pass through each other only while both are still on the rails
    // holding them apart. Rail state is checked first, then the train test, the expensive half.
    private static boolean placed(AbstractMinecart cart, AbstractMinecart other) {
        return cart.polytrains$railContext().onRails()
                && other.polytrains$railContext().onRails()
                && held(cart) && held(other);
    }

    // Seated, or the leading car the rest are laid from. A car the placer has let go is neither,
    // and is stopped by the rest of its train like any cart rather than shoved on through it.
    private static boolean held(AbstractMinecart car) {
        if (TrainPlacer.seated(car)) {
            return true;
        }

        Train train = Train.peek(car);

        return train != null && train.lastLeader() == car;
    }

    // Two trains meeting hard enough to write each other off, judged by closing momentum rather
    // than either train's own speed — so a heavy train overtaking a light one isn't punished for
    // catching up.
    //
    // Gated on the physics force model: under vanilla forces nothing wrecks, so the caller falls
    // through to COLLIDE, vanilla's own solid collision, same as pushOtherMinecart. Wrecking rides
    // on the model instead of its own rule because it's the one place this mod's forces break
    // something the player built.
    private static boolean wrecks(AbstractMinecart cart, AbstractMinecart other) {
        if (!RailForces.forLevel(cart.level()).wrecks()) {
            return false;
        }

        double momentumLimit = threshold(cart, PhysicsGameRules.CART_WRECK_MOMENTUM, 0.0);

        return momentumLimit > 0 && TrainCoupling.closingMomentum(cart, other) > momentumLimit;
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
        // The train's own speed, not this car's: a placed car's movement is written from the
        // leader's, so a stale copy here would answer differently from its neighbours — a train
        // solid at one end and walked through at the other.
        double speed = TrainCoupling.drivenSpeed(cart);

        // Speed checked first: underPower walks the whole train, and a cart about to hit something
        // is almost always over the bar anyway. Power only counts while actually moving, covering a
        // laden train pulling away too slowly to clear the bar — not one standing still, since a
        // furnace cart held against a buffer is under power indefinitely and would make its whole
        // train walkable.
        if (speed > HURTING_SPEED || (speed > STANDING && TrainCoupling.underPower(cart))) {
            return true;
        }

        // Anything a cart can injure it drives through as well, or it stalls on what it is killing.
        // Only bites when cart_impact_speed is set below HURTING_SPEED, and never for a player, who
        // must be able to lean on a slow train without it turning on them.
        return !(other instanceof Player) && injures(cart);
    }
}
