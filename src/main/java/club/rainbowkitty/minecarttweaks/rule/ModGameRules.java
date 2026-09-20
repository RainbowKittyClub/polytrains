package club.rainbowkitty.minecarttweaks.rule;

import net.fabricmc.fabric.api.gamerule.v1.GameRuleBuilder;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.gamerules.GameRule;

import club.rainbowkitty.minecarttweaks.MinecartTweaks;

/**
 * This mod's game rules apart from the realistic-physics model's own; see {@link
 * PhysicsGameRules} for those. Every setting is live-toggleable; none of them are cached.
 */
public final class ModGameRules {

    // Ceiling shared with vanilla's max_minecart_speed, for gamerule bounds.
    private static final int MAX_SPEED = 1000;

    /**
     * Whether trains work at all: linking, following and the chain between carts. All three are
     * built on the movement {@code minecart_improvements} brings, so the rule carries that pack as
     * a requirement and does not exist on a world without it — the same arrangement vanilla gives
     * {@code max_minecart_speed}.
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
            registerInteger("max_train_length", 128, 1, 512);

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

    /** Burn time ceiling in ticks, raising vanilla's hardcoded 32000. */
    public static final GameRule<Integer> FURNACE_MAX_BURN_TIME =
            registerInteger("furnace_max_burn_time", 72000, 0, Integer.MAX_VALUE);

    // Whether the world carries TRAINS_ENABLED's feature requirement, cached at load: GameRules.get
    // throws for an excluded rule with no query method; features are fixed for a world's lifetime.
    private static boolean trainsSupported;

    // Static-only; blocks instantiation.
    private ModGameRules() {}

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
