package club.rainbowkitty.minecarttweaks.rule;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRule;

import club.rainbowkitty.minecarttweaks.MinecartTweaks;

/** This mod's game rules. Every setting is live-toggleable; none of them are cached. */
public final class MTGameRules {

    // Ceiling shared with vanilla's max_minecart_speed.
    private static final int MAX_SPEED = 1000;

    /**
     * Whether trains work at all: linking, following and the chain between carts. All three are
     * built on the movement {@code minecart_improvements} brings, so the rule carries that pack as
     * a requirement and simply does not exist on a world without it — the same arrangement vanilla
     * gives {@code max_minecart_speed}.
     */
    public static final GameRule<Boolean> TRAINS_ENABLED =
            GameRuleBuilder.forBoolean(true)
                    .requiredFeatures(FeatureFlagSet.of(FeatureFlags.MINECART_IMPROVEMENTS))
                    .buildAndRegister(MinecartTweaks.id("trains_enabled"));

    /**
     * Most carts one train may hold, refused at link time so an over-long train cannot exist.
     * A value of one refuses every link.
     */
    public static final GameRule<Integer> MAX_TRAIN_LENGTH =
            registerInteger("max_train_length", 128, 1, 256);

    /**
     * Whether a furnace cart accepts anything a furnace would burn, rather than only the items in
     * {@code furnace_minecart_fuel}.
     */
    public static final GameRule<Boolean> FURNACES_CAN_USE_ALL_FUELS =
            registerBoolean("furnaces_can_use_all_fuels", true);

    /** Off by default: a moving cart holding its own chunks loaded is the costliest thing here. */
    public static final GameRule<Boolean> FURNACE_MINECARTS_LOAD_CHUNKS =
            registerBoolean("furnace_minecarts_load_chunks", false);

    /**
     * Scales the speed-based damage a moving cart deals on impact — half its speed in blocks per
     * second, times {@code minecart_damage / 20}. So 20 leaves the mod's own figure untouched
     * (vanilla carts deal no collision damage at all, so there is nothing else to scale), and 0
     * disables collision damage entirely.
     */
    public static final GameRule<Integer> MINECART_DAMAGE =
            registerInteger("minecart_damage", 20, 0, 1000);

    /**
     * Closing momentum at which two trains meeting write each other off, in empty carts times
     * blocks per second. The default 4 is a lone car closing at four blocks a second, which two
     * carts running into each other near full speed clear. Zero disables wrecking, leaving them to
     * collide and stop.
     *
     * <p>Does nothing at all unless {@link #REALISTIC_PHYSICS} is on: carts destroying each other
     * is part of that model, and without it two trains meeting simply stop or shunt each other
     * along, whatever this is set to.
     */
    public static final GameRule<Integer> CART_WRECK_MOMENTUM =
            registerInteger("cart_wreck_momentum", 4, 0, MAX_SPEED);

    /**
     * Impact speed below which a cart harms nothing it runs into, in blocks per second. The default
     * is 4, and it does not move with the train's length — a train hits a mob with its leading car,
     * not with its whole mass. What length does decide is how little the impact slows the train.
     */
    public static final GameRule<Integer> CART_IMPACT_SPEED =
            registerInteger("cart_impact_speed", 4, 0, MAX_SPEED);

    /**
     * Speed below which an empty cart takes a mob aboard rather than running it down, in blocks per
     * second. The default 4 meets {@code cart_impact_speed} exactly, so there is no speed at which
     * a cart both scoops and injures. Zero stops carts collecting at all.
     */
    public static final GameRule<Integer> CART_PICKUP_SPEED =
            registerInteger("cart_pickup_speed", 4, 0, MAX_SPEED);

    /**
     * Whether a cart an unpowered powered rail brought to a stop leaves again the way it came in,
     * once that rail has its signal back. Vanilla only accelerates a cart once it's already
     * been pushed.
     */
    public static final GameRule<Boolean> CARTS_RESUME_FROM_BRAKES =
            registerBoolean("carts_resume_from_brakes", true);

    /**
     * Speed below which rolling friction is laid on harder, in blocks per second, or zero to leave
     * vanilla's alone. Vanilla takes a fixed fraction of the speed each tick — a fortieth of an
     * empty cart's, but only a three-hundredth of a ridden one's — so a cart with someone aboard
     * rolls on for the better part of a minute after it looks stopped.
     *
     * <p>Read as the speed at which this mod's flat rolling resistance matches that fraction on an
     * empty cart: below it the flat term is the larger of the two, and being flat it brings a cart
     * to a dead stop rather than forever halving what is left. {@code RailDrag} caps it at an
     * eighth of the pull gravity gives on a slope, and that cap binds at every value above zero, so
     * in practice this reads as on or off — zero for vanilla's friction alone, anything else for
     * the flat stop. Without the cap most of this range would stop a cart rolling downhill at all.
     */
    public static final GameRule<Integer> RAIL_DRAG_SPEED =
            registerInteger("rail_drag_speed", 3, 0, MAX_SPEED);

    /**
     * Whether carts run on this mod's own force model instead of vanilla's. Off by default, so a
     * world that installs this mod for its links, chains, junctions and switches gets vanilla's
     * friction, acceleration, speed ceilings and collisions untouched.
     *
     * <p>On, friction and the engine become forces divided by what a cart weighs — a loaded train
     * accelerates and stops differently from an empty one, an empty cart no longer coasts further
     * than a full one, corners cost speed, and two trains meeting hard enough write each other
     * off. Trains themselves are not gated by this: vanilla has no trains to mirror, so a train is
     * this mod's own feature under either model.
     */
    public static final GameRule<Boolean> REALISTIC_PHYSICS =
            registerBoolean("realistic_physics", false);

    /** Burn time ceiling in ticks, raising vanilla's hardcoded 32000. */
    public static final GameRule<Integer> FURNACE_MAX_BURN_TIME =
            registerInteger("furnace_max_burn_time", 72000, 0, Integer.MAX_VALUE);

    // GameRules.get throws for a rule the world's features exclude, and there is no public way to
    // ask whether one is present, so the answer is settled once on load and read from here instead.
    // Enabled features are fixed for the lifetime of a loaded world, so once is enough.
    private static boolean trainsSupported;

    // Static-only; blocks instantiation.
    private MTGameRules() {}

    /** Forces class initialization so the rules land in the registry before it freezes. */
    public static void init() {}

    /** Records whether the loaded world carries the pack {@link #TRAINS_ENABLED} is built on. */
    public static void setTrainsSupported(boolean supported) {
        trainsSupported = supported;
    }

    /** Whether trains should run, accounting for a world that cannot support them at all. */
    public static boolean trainsEnabled(ServerLevel level) {
        return trainsSupported && level.getGameRules().get(TRAINS_ENABLED);
    }

    /** As {@link #trainsEnabled(ServerLevel)}, and false on the client, where no rule is read. */
    public static boolean trainsEnabled(Level level) {
        return level instanceof ServerLevel serverLevel && trainsEnabled(serverLevel);
    }

    // Registers a boolean rule under this mod's namespace.
    private static GameRule<Boolean> registerBoolean(String path, boolean defaultValue) {
        return GameRuleBuilder.forBoolean(defaultValue)
                .buildAndRegister(MinecartTweaks.id(path));
    }

    // Registers an integer rule under this mod's namespace, clamped to the given range.
    private static GameRule<Integer> registerInteger(
            String path, int defaultValue, int min, int max) {
        return GameRuleBuilder.forInteger(defaultValue)
                .range(min, max)
                .buildAndRegister(MinecartTweaks.id(path));
    }
}
