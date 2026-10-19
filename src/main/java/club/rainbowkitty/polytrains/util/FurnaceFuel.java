package club.rainbowkitty.polytrains.util;

//? if >=26.3
//import java.util.Optional;

//? if >=26.3
//import net.minecraft.core.component.DataComponents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
//? if >=26.3
//import net.minecraft.world.item.component.CookingFuel;
import net.minecraft.world.level.Level;
//? if >=26.3 {
/*import net.minecraft.world.level.storage.loot.LootContext;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.providers.number.ints.ResolvableInt;
*///?}

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

        return ModGameRules.FURNACES_CAN_USE_ALL_FUELS.get(level.getServer())
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
        //? if >=26.3 {
        /*int burn = furnaceBurnTime(level, stack);
        *///?} else {
        int burn = level.fuelValues().burnDuration(stack);
        //?}

        if (burn <= 0) {
            return 0;
        }

        //? if >=26.3 {
        /*int coalInFurnace = furnaceBurnTime(level, Coal.STACK);
        *///?} else {
        int coalInFurnace = level.fuelValues().burnDuration(Coal.STACK);
        //?}

        return coalInFurnace <= 0
                ? 0
                : (int) ((long) burn * ticksPerCoal() / coalInFurnace);
    }

    //? if >=26.3 {
    /*// 26.3 reads burn time off a data component in a loot context. Given no block, the
    // fast-cooking predicate fails, so this is a plain furnace's figure, as before.
    private static int furnaceBurnTime(ServerLevel level, ItemStack stack) {
        LootContext context = new LootContext.Builder(
                new LootParams.Builder(level).create(LootContextParamSets.EMPTY))
                .create(Optional.empty());

        return ResolvableInt.getFromItem(
                stack, DataComponents.COOKING_FUEL, CookingFuel::burnTime, context, 0);
    }
    *///?}

    // Built on first use, after registries bind; only ever read, never handed out.
    private static final class Coal {
        private static final ItemStack STACK = new ItemStack(Items.COAL);
    }
}
