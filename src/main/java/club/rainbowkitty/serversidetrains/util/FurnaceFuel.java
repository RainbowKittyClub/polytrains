package club.rainbowkitty.serversidetrains.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;

import club.rainbowkitty.serversidetrains.mixin.MinecartFurnaceAccessor;
import club.rainbowkitty.serversidetrains.rule.ModGameRules;

/** What a furnace cart will burn once it is no longer held to coal and charcoal. */
public final class FurnaceFuel {

    // Vanilla gives coal 1600 ticks in a furnace and 3600 in a cart, so every burn duration is
    // scaled by the ratio rather than a cart-specific table being invented.
    private static final double CART_SCALE = 2.25;

    // Static-only; blocks instantiation.
    private FurnaceFuel() {}

    /** How many ticks {@code stack} burns for in a cart, or zero if nothing will burn it. */
    public static int burnDuration(ServerLevel level, ItemStack stack) {
        return (int) (level.fuelValues().burnDuration(stack) * CART_SCALE);
    }

    /**
     * How many ticks one of {@code stack} buys a cart that is fed it, or zero if the cart would
     * refuse it: vanilla's figure for its own fuel, and with {@code furnaces_can_use_all_fuels}
     * anything a furnace burns — the same two paths a player's hand goes down.
     */
    public static int cartBurnDuration(ServerLevel level, ItemStack stack) {
        if (stack.is(ItemTags.FURNACE_MINECART_FUEL)) {
            return MinecartFurnaceAccessor.serversidetrains$fuelTicksPerItem();
        }

        return level.getGameRules().get(ModGameRules.FURNACES_CAN_USE_ALL_FUELS)
                ? burnDuration(level, stack)
                : 0;
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
}
