package club.rainbowkitty.polytrains.rule;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.flag.FeatureFlagSet;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.level.Level;

import club.rainbowkitty.kalic0re.common.config.ModConfig;
import club.rainbowkitty.kalic0re.common.config.Setting;
import club.rainbowkitty.polytrains.Polytrains;

/**
 * This mod's server settings apart from the realistic-physics model's own; see {@link
 * PhysicsGameRules} for those. Each is a setting of the rkcore-common settings hub, and a game rule
 * of the same id where the hub is absent. Every setting is live-toggleable; none of them are
 * cached.
 */
public final class ModGameRules {

    // Ceiling shared with vanilla's max_minecart_speed, for gamerule bounds.
    private static final int MAX_SPEED = 1000;

    // Every setting of this mod, this class's and PhysicsGameRules' both.
    private static final ModConfig CONFIG = ModConfig.create(Polytrains.MOD_ID);

    /**
     * Whether trains work at all: linking, following and the chain between carts. All three are
     * built on the movement {@code minecart_improvements} brings, so the setting carries that pack
     * as a requirement and does not exist on a world without it — the same arrangement vanilla
     * gives {@code max_minecart_speed}.
     */
    public static final Setting<Boolean> TRAINS_ENABLED = toggle("trains_enabled", true)
            .requires(FeatureFlagSet.of(FeatureFlags.MINECART_IMPROVEMENTS));

    /**
     * Most carts one train may hold, refused at link time so an over-long train cannot exist.
     * A value of one refuses every link.
     */
    public static final Setting<Integer> MAX_TRAIN_LENGTH =
            range("max_train_length", 128, 1, 512);

    /**
     * Whether a furnace cart accepts anything a furnace would burn, rather than only the items in
     * {@code furnace_minecart_fuel}.
     */
    public static final Setting<Boolean> FURNACES_CAN_USE_ALL_FUELS =
            toggle("furnaces_can_use_all_fuels", true);

    /** Off by default: a moving cart holding its own chunks loaded is the costliest thing here. */
    public static final Setting<Boolean> FURNACE_MINECARTS_LOAD_CHUNKS =
            toggle("furnace_minecarts_load_chunks", false);

    /**
     * Whether a furnace cart wears its smokestack and whistle, and smokes from the stack while it
     * burns. Its brake lever is drawn either way. Off by default: a world shows them once asked to.
     */
    public static final Setting<Boolean> ENABLE_CHOOCHOO =
            toggle("enable_choochoo", false);

    /**
     * Scales the speed-based damage a moving cart deals on impact — half its speed in blocks per
     * second, times {@code minecart_damage / 20}. So 20 leaves the mod's own figure untouched
     * (vanilla carts deal no collision damage at all, so there is nothing else to scale), and 0
     * disables collision damage entirely.
     */
    public static final Setting<Integer> MINECART_DAMAGE =
            range("minecart_damage", 20, 0, 1000);

    /**
     * Impact speed below which a cart harms nothing it runs into, in blocks per second. The default
     * is 4, and it does not move with the train's length — a train hits a mob with its leading car,
     * not with its whole mass. What length does decide is how little the impact slows the train.
     */
    public static final Setting<Integer> CART_IMPACT_SPEED =
            range("cart_impact_speed", 4, 0, MAX_SPEED);

    /**
     * Speed below which an empty cart takes a mob aboard rather than running it down, in blocks per
     * second. The default 4 meets {@code cart_impact_speed} exactly, so there is no speed at which
     * a cart both scoops and injures. Zero stops carts collecting at all.
     */
    public static final Setting<Integer> CART_PICKUP_SPEED =
            range("cart_pickup_speed", 4, 0, MAX_SPEED);

    /**
     * Whether a cart an unpowered powered rail brought to a stop leaves again the way it came in,
     * once that rail has its signal back. Vanilla only accelerates a cart once it's already
     * been pushed.
     */
    public static final Setting<Boolean> CARTS_RESUME_FROM_BRAKES =
            toggle("carts_resume_from_brakes", true);

    /** Burn time ceiling in ticks, raising vanilla's hardcoded 32000. */
    public static final Setting<Integer> FURNACE_MAX_BURN_TIME =
            range("furnace_max_burn_time", 72000, 0, Integer.MAX_VALUE);

    // Whether the world carries TRAINS_ENABLED's feature requirement, cached at load: the setting
    // answers its default of true on a world without the pack, so it cannot say so itself.
    private static boolean trainsSupported;

    // Static-only; blocks instantiation.
    private ModGameRules() {}

    /**
     * Declares every setting, {@link PhysicsGameRules}' included, and registers them with the hub
     * or as game rules. Call once from the initializer, before the game-rule registry freezes.
     */
    public static void register() {
        PhysicsGameRules.init();
        CONFIG.register();
    }

    /** Records whether the loaded world carries the pack {@link #TRAINS_ENABLED} is built on. */
    public static void setTrainsSupported(boolean supported) {
        trainsSupported = supported;
    }

    /** Whether trains should run, accounting for a world that cannot support them at all. */
    public static boolean trainsEnabled(ServerLevel level) {
        return trainsSupported && TRAINS_ENABLED.get(level.getServer());
    }

    /** As {@link #trainsEnabled(ServerLevel)}, and false on the client, where nothing is read. */
    public static boolean trainsEnabled(Level level) {
        return level instanceof ServerLevel serverLevel && trainsEnabled(serverLevel);
    }

    // Declares a true/false server setting, with a game rule of its id where the hub is absent.
    static Setting<Boolean> toggle(String key, boolean defaultValue) {
        return CONFIG.toggle(key, defaultValue).server().fallbackRule();
    }

    // Declares a whole-number server setting in [min, max], with a game rule as toggle's has.
    static Setting<Integer> range(String key, int defaultValue, int min, int max) {
        return CONFIG.range(key, defaultValue, min, max).server().fallbackRule();
    }
}
