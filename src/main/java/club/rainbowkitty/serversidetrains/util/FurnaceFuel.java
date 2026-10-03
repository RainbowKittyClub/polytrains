package club.rainbowkitty.serversidetrains.util;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;

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
