package club.rainbowkitty.polytrains.rule;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;

import net.minecraft.world.level.gamerules.GameRule;

import club.rainbowkitty.polytrains.Polytrains;

/**
 * Game rules specific to this mod's realistic-physics force model — see {@link
 * club.rainbowkitty.polytrains.rail.RailForces}. Every setting is live-toggleable; none of
 * them are cached.
 */
public final class PhysicsGameRules {

    // Ceiling shared with vanilla's max_minecart_speed, for gamerule bounds.
    private static final int MAX_SPEED = 1000;

    /**
     * Whether carts run on this mod's own force model instead of vanilla's. Off by default, so a
     * world that installs this mod for its links, chains, junctions and switches gets vanilla's
     * friction, acceleration, speed ceilings and collisions untouched.
     *
     * <p>On, friction and the engine become forces divided by what a cart weighs — a loaded train
     * accelerates and stops differently from an empty one, an empty cart no longer coasts further
     * than a full one, a cart comes to a dead stop rather than crawling on after it looks stopped,
     * corners cost speed, and two trains meeting hard enough write each other off. Trains
     * themselves are not gated by this: vanilla has no trains to mirror, so a train is this mod's
     * own feature under either model.
     */
    public static final GameRule<Boolean> REALISTIC_PHYSICS =
            registerBoolean("realistic_physics", false);

    /**
     * Closing momentum at which two trains meeting write each other off, in empty minecarts times
     * blocks per second — the same mass unit everything else in this mod uses. It is a reduced mass
     * times a closing speed, so the default 4 takes two empty carts meeting at eight blocks a
     * second between them, or two full chest carts at under three. Zero disables wrecking, leaving
     * them to collide and stop.
     *
     * <p>Does nothing at all unless {@link #REALISTIC_PHYSICS} is on: carts destroying each other
     * is part of that model, and without it two trains meeting stop or shunt each other along,
     * whatever this is set to.
     */
    public static final GameRule<Integer> CART_WRECK_MOMENTUM =
            registerInteger("cart_wreck_momentum", 4, 0, MAX_SPEED);

    // Static-only; blocks instantiation.
    private PhysicsGameRules() {}

    /** Forces class initialization so the rules land in the registry before it freezes. */
    public static void init() {}

    // Registers a boolean rule under this mod's namespace.
    private static GameRule<Boolean> registerBoolean(String path, boolean defaultValue) {
        return GameRuleBuilder.forBoolean(defaultValue)
                .buildAndRegister(Polytrains.id(path));
    }

    // Registers an integer rule under this mod's namespace, clamped to the given range.
    private static GameRule<Integer> registerInteger(
            String path, int defaultValue, int min, int max) {
        return GameRuleBuilder.forInteger(defaultValue)
                .range(min, max)
                .buildAndRegister(Polytrains.id(path));
    }
}
