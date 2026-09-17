package club.rainbowkitty.minecarttweaks.collision;

import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.init.MTDamageTypes;
import club.rainbowkitty.minecarttweaks.rule.MTGameRules;
import club.rainbowkitty.minecarttweaks.train.Train;
import club.rainbowkitty.minecarttweaks.train.TrainCoupling;

/**
 * What a moving cart does to whatever it runs into. Which of them it may do anything to at all is
 * {@link CartInteraction}'s to say, never this class's — everything here is the consequence.
 */
public final class CartImpact {

    // Uniform margin around the cart's box for something it is up against right now.
    private static final double CONTACT_SHELL = 0.1;

    // Below this a cart is treated as stopped: it neither shoves nor strikes.
    private static final double STILL = 1.0E-4;

    // Damage per block per tick of impact speed, per point of minecart_damage: the speed in blocks
    // per second halved, so at the rule's default of 20 a cart at vanilla's top speed deals two
    // hearts.
    private static final double DAMAGE_PER_SPEED_PER_RULE = 0.5;

    // Fractions of the cart's speed carried into the throw along the rail and upwards. Sideways
    // takes the whole of it, which is the direction that actually clears the track.
    private static final double KNOCKBACK_FORWARD = 0.6;
    private static final double KNOCKBACK_VERTICAL = 0.4;

    // What one car weighs, in players. Nothing in the game states a minecart's mass, so this is the
    // assumption the whole drag figure rests on: a cart is three of the entity it is sized against.
    private static final double CAR_MASS = 3.0;

    // Most of its speed one impact may cost a train, however large the thing hit.
    private static final double MAX_IMPACT_DRAG = 0.5;

    // Bounds on the multiplier a struck entity's size puts on the shove and on the train's speed
    // loss, keeping a bat from being ignored and an ender dragon from launching.
    private static final double MIN_SIZE_FACTOR = 0.25;
    private static final double MAX_SIZE_FACTOR = 4.0;

    // Human dimensions, the baseline every struck entity's size is measured against.
    private static final double REFERENCE_VOLUME = boxVolume(
            EntityTypes.PLAYER.getDimensions().width(),
            EntityTypes.PLAYER.getDimensions().height());

    // Static-only; blocks instantiation.
    private CartImpact() {}

    /**
     * Puts every cart {@code cart} is touching onto its own movement, shoves anything living out of
     * its way, and injures what it is running into fast enough — throwing that clear and bleeding
     * the train's speed by the impact.
     */
    public static void strikeNearby(ServerLevel level, AbstractMinecart cart) {
        Vec3 movement = cart.getDeltaMovement();

        if (movement.lengthSqr() <= STILL * STILL) {
            return;
        }

        // Speed alone decides what the cart throws, and what it hurts, and how badly. The train's
        // weight tells on the other side of the impact only: in how little the train is slowed.
        int damageRule = level.getGameRules().get(MTGameRules.MINECART_DAMAGE);
        boolean hurts = damageRule > 0 && CartInteraction.injures(cart);
        float damage = (float) (CartInteraction.impactSpeed(cart)
                * DAMAGE_PER_SPEED_PER_RULE * damageRule);

        // Both ways along the movement: back over the ground just swept, so a fast cart still
        // catches what it passed clean through in one tick, and forward over the ground about to
        // be, since this runs before the cart moves and what it is bearing down on is ahead of it.
        AABB sweep = cart.getBoundingBox()
                .expandTowards(movement.reverse())
                .expandTowards(movement)
                .inflate(CONTACT_SHELL);

        List<Entity> inReach = level.getEntities(
                cart, sweep, other -> CartInteraction.participates(cart, other));
        double drag = 0.0;

        for (Entity other : inReach) {
            CartInteraction hit = CartInteraction.of(cart, other);

            if (other instanceof AbstractMinecart struck) {
                // Both carts run this in the same tick, so the second call finds the first gone.
                // Whoever was riding is set down unhurt: losing the carts is the whole of the cost.
                if (hit == CartInteraction.WRECK) {
                    writeOff(level, cart);
                    writeOff(level, struck);
                    return;
                }

                // The terms of CartInteraction.pushes that the interaction in hand does not settle;
                // asking it outright would resolve the whole pair a second time.
                if (hit.blocks() && !TrainCoupling.sameTrain(cart, struck)) {
                    struck.setDeltaMovement(movement);
                }
            }

            if (other instanceof LivingEntity living
                    && hit.strikes()
                    && living.isAlive()
                    && !living.isPassenger()) {
                // Billed for every shove, not only for a hit that lands: what slows the train is
                // the momentum it hands over, and a mob it cannot hurt — under the threshold, in
                // its damage cooldown, or immune — is shoved out of the way just the same.
                drag += shove(cart, living, sizeFactor(living));

                if (hurts) {
                    living.hurtServer(level, living.damageSources().source(
                            MTDamageTypes.MINECART_DAMAGE, cart, cart.getFirstPassenger()), damage);
                }
            }
        }

        if (drag > 0.0) {
            slowTrain(cart, drag / movement.length());
        }
    }

    // Breaks the cart into its item, unless something else already removed it this tick.
    private static void writeOff(ServerLevel level, AbstractMinecart cart) {
        if (!cart.isRemoved()) {
            cart.destroy(level, cart.getPickResult().getItem());
        }
    }

    // Throws what the cart hit clear of the rail, mostly sideways: a shove along the track only
    // sets it running ahead to be caught again next tick. Square-rooted, so the bounds on size
    // spread the throw fourfold, not sixteenfold. Returns what the shove costs one car.
    private static double shove(AbstractMinecart cart, LivingEntity living, double sizeFactor) {
        Vec3 movement = cart.getDeltaMovement();
        Vec3 heading = movement.horizontal();

        if (heading.lengthSqr() <= STILL * STILL) {
            return 0.0;
        }

        heading = heading.normalize();

        // Thrown to the side it already leans to, so it leaves by the shorter way round the cart.
        Vec3 side = heading.rotateClockwise90();
        Vec3 clear = side.scale(
                living.position().subtract(cart.position()).dot(side) < 0.0 ? -1.0 : 1.0);

        double give = movement.length() / Math.sqrt(sizeFactor);
        Vec3 thrown = clear.scale(give)
                .add(heading.scale(give * KNOCKBACK_FORWARD))
                .add(0.0, give * KNOCKBACK_VERTICAL, 0.0);
        Vec3 own = living.getDeltaMovement();
        Vec3 after = new Vec3(
                carried(own.x(), thrown.x()),
                Math.max(own.y(), thrown.y()),
                carried(own.z(), thrown.z()));

        living.setDeltaMovement(after);
        living.hurtMarked = true;

        // Momentum conservation along the track only; the rails take the rest. Read off the speed
        // actually gained rather than the one aimed, so a mob already flung clear costs the train
        // nothing and a train cannot brake itself to a halt on one mob it keeps clipping through.
        return Math.max(0.0, after.subtract(own).dot(heading)) * sizeFactor / CAR_MASS;
    }

    // The entity's own velocity or the cart's imparted push along one axis, whichever is greater in
    // the push's direction — so a mob already flung clear is neither slowed nor re-accelerated.
    private static double carried(double own, double push) {
        return push >= 0 ? Math.max(own, push) : Math.min(own, push);
    }

    // Bleeds the accumulated impact off every car of the train equally: one car's worth of momentum
    // loss spread over the whole train, so the speed a given impact costs falls off as one over the
    // number of cars. Drag arrives as the fraction a lone cart would lose.
    private static void slowTrain(AbstractMinecart cart, double drag) {
        List<AbstractMinecart> cars = Train.of(cart).cars();
        double loss = Math.min(MAX_IMPACT_DRAG, drag / cars.size());

        for (AbstractMinecart car : cars) {
            Vec3 m = car.getDeltaMovement();
            car.setDeltaMovement(m.x() * (1.0 - loss), m.y(), m.z() * (1.0 - loss));
        }
    }

    // A struck entity's size relative to a player, clamped: how far the throw carries it divides by
    // this, and how much its impact costs the train multiplies by it.
    private static double sizeFactor(LivingEntity living) {
        double volume = boxVolume(living.getBbWidth(), living.getBbHeight());
        return Math.clamp(volume / REFERENCE_VOLUME, MIN_SIZE_FACTOR, MAX_SIZE_FACTOR);
    }

    // Volume of an entity's bounding box, which is square in plan.
    private static double boxVolume(double width, double height) {
        return width * width * height;
    }
}
