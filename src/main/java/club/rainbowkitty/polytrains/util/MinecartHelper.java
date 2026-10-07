package club.rainbowkitty.polytrains.util;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartBehavior;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.polytrains.ext.Linkable;
import club.rainbowkitty.polytrains.init.EntityAttachments;
import club.rainbowkitty.polytrains.item.WrenchItems;
import club.rainbowkitty.polytrains.link.LinkAction;
import club.rainbowkitty.polytrains.link.LinkRefusal;
import club.rainbowkitty.polytrains.link.LinkTargeting;
import club.rainbowkitty.polytrains.rail.RailPath;
import club.rainbowkitty.polytrains.rule.ModGameRules;
import club.rainbowkitty.polytrains.train.BrakeLevers;

/** Dispatches a minecart's right-click, upgrades it by crafting, and works out its orientation. */
public final class MinecartHelper {

    // Static-only; blocks instantiation.
    private MinecartHelper() {}

    /**
     * Answers one use of a minecart. The wrench does whichever job it is set to; shift-clicking
     * with anything else tries to craft the cart into whatever that item upgrades it to.
     */
    public static InteractionResult useMinecart(
            Player player, Level level, InteractionHand hand, AbstractMinecart minecart) {
        // An unconsumed click also reaches the off hand after the main hand already answered it —
        // this guards against a second wrench retry or upgrade attempt. Keyed on whether the click
        // was answered, not on which hand holds what, so swapping hands mid-click can't change the
        // outcome.
        if (hand == InteractionHand.OFF_HAND
                && level instanceof ServerLevel serverLevel
                && EntityAttachments.answeredUseAt(player, serverLevel.getGameTime())) {
            return InteractionResult.PASS;
        }

        // Both of the things a use can mean here are shift-clicks. Leaving the plain click alone
        // is also what lets a player still ride the cart: vanilla's own ride declines a sneaking
        // click before any of this runs, which is sturdier than cancelling the ride afterwards.
        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        ItemStack heldItem = player.getItemInHand(hand);

        if (WrenchItems.isWrench(heldItem)) {
            // The client answers its own click so its hand swings without waiting on the server;
            // everything the link itself needs is server-side.
            if (!(level instanceof ServerLevel serverLevel)
                    || !(player instanceof ServerPlayer serverPlayer)) {
                return InteractionResult.SUCCESS;
            }

            EntityAttachments.setAnsweredUseAt(player, serverLevel.getGameTime());

            if (!ModGameRules.trainsEnabled(serverLevel)) {
                LinkTargeting.notice(serverPlayer, LinkRefusal.DISABLED.message());
                return InteractionResult.FAIL;
            }

            LinkAction.useWrench(serverPlayer, serverLevel, minecart);
            return InteractionResult.SUCCESS;
        }

        if (player instanceof ServerPlayer serverPlayer
                && level instanceof ServerLevel serverLevel
                && tryUpgradeMinecart(serverPlayer, serverLevel, hand, minecart, heldItem)) {
            EntityAttachments.setAnsweredUseAt(player, serverLevel.getGameTime());
            player.swing(hand, true);
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }

    /**
     * Points the cart {@code behavior} drives along the rail at {@code railPos}, whichever of that
     * rail's two directions {@code lookDirection} agrees with.
     *
     * <p>Settled as the cart is placed, not read off its first movement — a cart has a front (its
     * chain hangs off one end), so one that later runs backward stays backward instead of turning
     * to face it.
     *
     * <p>Leaves flip alone — a cart is placed unflipped, and the rail adjustment that follows
     * reconciles it by reading the rail's first exit (this heading or its opposite) and turning
     * the cart end-for-end if it's the opposite.
     */
    public static void faceAlongRail(
            Level level, BlockPos railPos, MinecartBehavior behavior, Vec3 lookDirection) {
        BlockPos exit = RailPath.ahead(level, railPos, lookDirection.horizontal());

        if (exit != null) {
            behavior.setYRot(yawOf(Vec3.atLowerCornerOf(exit.subtract(railPos)).horizontal()));
        }
    }

    /** The yaw a cart wears to point along {@code heading}, in vanilla's own reckoning. */
    public static float yawOf(Vec3 heading) {
        return (float) ((180.0 - Math.atan2(heading.z, heading.x) * 180.0 / Math.PI) % 360.0);
    }

    /** Which way a cart wearing {@code yaw} points, the inverse of {@link #yawOf(Vec3)}. */
    public static Vec3 headingOf(float yaw) {
        double radians = (180.0 - yaw) * Math.PI / 180.0;

        return new Vec3(Math.cos(radians), 0.0, Math.sin(radians));
    }

    /** Whether {@code entity} is a cart currently sitting on no rail. */
    public static boolean isDerailed(Entity entity) {
        return entity instanceof AbstractMinecart cart
                && !BaseRailBlock.isRail(
                        cart.level().getBlockState(cart.getCurrentBlockPosOrRailBelow()));
    }

    /**
     * Attempts to upgrade the cart under the player's hand using a crafting recipe, preserving its
     * links and passengers.
     */
    private static boolean tryUpgradeMinecart(
            ServerPlayer player, ServerLevel level, InteractionHand hand,
            AbstractMinecart originalMinecart, ItemStack heldItem) {
        if (!originalMinecart.isAlive()) {
            return false;
        }

        ItemStack crafted = craftUpgrade(level, heldItem, originalMinecart.getPickResult());
        if (!(crafted.getItem() instanceof MinecartItem craftedCart)) {
            return false;
        }

        Entity replacement = AbstractMinecart.createMinecart(
                level, originalMinecart.getX(), originalMinecart.getY(), originalMinecart.getZ(),
                craftedCart.polytrains$type(), EntitySpawnReason.DISPENSER, crafted, player);

        originalMinecart.ejectPassengers();
        replacement.copyPosition(originalMinecart);

        // Read the neighbours off the old cart and sever them by hand before it is removed:
        // removing a still-linked cart severs on its own and drops the chains, raining them on the
        // player mid-upgrade.
        List<AbstractMinecart> orphaned = originalMinecart.getNeighbours();
        LinkAction.severLinks(level, originalMinecart, false);

        originalMinecart.remove(Entity.RemovalReason.DISCARDED);
        level.addFreshEntity(replacement);

        if (replacement instanceof AbstractMinecart relinkable) {
            orphaned.forEach(neighbour -> Linkable.link(relinkable, neighbour));

            // A cart upgraded into an engine joins its train's brake as a coupled one would.
            BrakeLevers.matchCoupled(relinkable);
        } else {
            // A result that is no cart at all hands the chains back instead of rewiring the train
            // around itself.
            replacement.spawnAtLocation(level, new ItemStack(Items.IRON_CHAIN, orphaned.size()));
        }

        heldItem.consume(1, player);
        player.setItemInHand(hand, heldItem);

        return true;
    }

    // Resolves what the held item crafts with the cart's own item side by side, or EMPTY if the
    // pair is not a recipe.
    private static ItemStack craftUpgrade(ServerLevel level, ItemStack applied, ItemStack cart) {
        CraftingInput bench = CraftingInput.of(2, 1, List.of(applied, cart));

        return level.recipeAccess()
                .getRecipeFor(RecipeType.CRAFTING, bench, level)
                .map(recipe -> recipe.value().assemble(bench))
                .orElse(ItemStack.EMPTY);
    }
}
