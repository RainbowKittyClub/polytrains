package club.rainbowkitty.polytrains.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;

import club.rainbowkitty.polytrains.mixin.MinecartFurnaceAccessor;
import club.rainbowkitty.polytrains.rule.ModGameRules;

/** What a furnace cart will burn once it is no longer held to coal and charcoal. */
public final class FurnaceFuel {

    // Static-only; blocks instantiation.
    private FurnaceFuel() {}

    /**
     * How many ticks one coal or charcoal burns for in a cart: vanilla's own figure for an item of
     * {@code furnace_minecart_fuel}, read from vanilla rather than copied.
     */
    public static int ticksPerCoal() {
        return MinecartFurnaceAccessor.polytrains$fuelTicksPerItem();
    }

    /** Whether {@code stack} is something a cart burns unaided: coal and charcoal. */
    public static boolean burnsUnaided(ItemStack stack) {
        return stack.is(ItemTags.FURNACE_MINECART_FUEL);
    }

    /**
     * How many ticks one of {@code stack} buys a cart that is fed it, or zero if the cart would
     * refuse it: vanilla's figure for its own fuel, and with {@code furnaces_can_use_all_fuels}
     * anything a furnace burns — the same two paths a player's hand goes down.
     */
    public static int cartBurnDuration(ServerLevel level, ItemStack stack) {
        if (burnsUnaided(stack)) {
            return ticksPerCoal();
        }

        return level.getGameRules().get(ModGameRules.FURNACES_CAN_USE_ALL_FUELS)
                ? burnDuration(level, stack)
                : 0;
    }

    /**
     * Whether a furnace cart would take {@code stack} from a hand, by {@link #cartBurnDuration}. A
     * client, which has no game rules to read, knows only coal and charcoal.
     */
    public static boolean feeds(Level level, ItemStack stack) {
        return level instanceof ServerLevel serverLevel
                ? cartBurnDuration(serverLevel, stack) > 0
                : burnsUnaided(stack);
    }

    /**
     * Takes one offered fuel item out of the player's hand, emptying a bucket back into it rather
     * than swallowing it whole. Any sound is played at {@code cart}, which is what was fuelled.
     */
    public static void take(ServerLevel level, Entity cart,
            Player player, InteractionHand hand, ItemStack stack) {
        if (!(stack.getItem() instanceof BucketItem bucket)) {
            stack.consume(1, player);
            return;
        }

        bucket.playEmptySound(player, level, cart.blockPosition());
        player.setItemInHand(hand, ItemUtils.createFilledResult(
                stack, player, BucketItem.getEmptySuccessItem(stack, player)));
    }

    // How many ticks stack burns for in a cart, or zero if nothing will burn it: its furnace burn
    // time scaled as vanilla scales coal's between a furnace and a cart (1600 to 3600), rather than
    // a cart-specific table being invented. Both figures are read, so a mod changing either moves
    // every other fuel with it.
    private static int burnDuration(ServerLevel level, ItemStack stack) {
        int coalInFurnace = level.fuelValues().burnDuration(new ItemStack(Items.COAL));

        return coalInFurnace <= 0
                ? 0
                : (int) ((long) level.fuelValues().burnDuration(stack) * ticksPerCoal()
                        / coalInFurnace);
    }
}
