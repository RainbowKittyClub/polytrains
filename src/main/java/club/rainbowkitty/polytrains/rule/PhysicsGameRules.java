package club.rainbowkitty.polytrains.rule;

import club.rainbowkitty.kalic0re.common.config.Setting;

/**
 * Server settings specific to this mod's realistic-physics force model — see {@link
 * club.rainbowkitty.polytrains.rail.RailForces}. Declared alongside {@link ModGameRules}' own,
 * with game rules standing in the same way. Every setting is live-toggleable; none of them are
 * cached.
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
    public static final Setting<Boolean> REALISTIC_PHYSICS =
            ModGameRules.toggle("realistic_physics", false);

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
    public static final Setting<Integer> CART_WRECK_MOMENTUM =
            ModGameRules.range("cart_wreck_momentum", 4, 0, MAX_SPEED);

    // Static-only; blocks instantiation.
    private PhysicsGameRules() {}

    /** Forces class initialization, so these settings are declared before registration. */
    public static void init() {}
}
