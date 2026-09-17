package club.rainbowkitty.minecarttweaks.util;

import java.util.List;
import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MinecartItem;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.MinecartTweaks;
import club.rainbowkitty.minecarttweaks.ext.Linkable;
import club.rainbowkitty.minecarttweaks.init.MTDataComponents;
import club.rainbowkitty.minecarttweaks.init.MTTags;
import club.rainbowkitty.minecarttweaks.init.SelectedCart;
import club.rainbowkitty.minecarttweaks.rail.RailPath;
import club.rainbowkitty.minecarttweaks.rule.MTGameRules;
import club.rainbowkitty.minecarttweaks.train.Train;
import club.rainbowkitty.minecarttweaks.train.TrainCoupling;

/** Utility methods for minecart linking, upgrading, and brake logic. */
public final class MinecartHelper {
    /** Why a link was not allowed. Each constant names its own overlay message. */
    public enum Refusal {
        DISABLED,
        ALREADY_LINKED,
        SAME_TRAIN,
        DERAILED,
        TOO_FAR,
        TOO_LONG,
        BLOCKED;

        /** The message shown to the player, in the colour a refusal is shown in. */
        public Component message() {
            return Component.translatable(
                            MinecartTweaks.MOD_ID + ".link." + name().toLowerCase(Locale.ROOT))
                    .withStyle(ChatFormatting.RED);
        }
    }

    // Static-only; blocks instantiation.
    private MinecartHelper() {}

    /**
     * Returns true if the player is holding a link item and shift-clicking a minecart,
     * meaning a link attempt should be handled.
     */
    public static boolean mayAttemptLinking(Player player, ItemStack heldItem) {
        if (!heldItem.is(MTTags.Items.LINK_ITEMS)) {
            return false;
        }

        return player.isShiftKeyDown();
    }

    /**
     * Attempts to link or select the target cart for linking. The first click selects one cart, and
     * a second on a different cart with a free neighbour slot completes the link.
     */
    public static void tryLinkMinecart(
            Player player, ServerLevel serverLevel,
            AbstractMinecart target, ItemStack heldItem) {
        SelectedCart selectedCart = heldItem.get(MTDataComponents.SELECTED_CART);
        if (selectedCart == null) {
            if (isDerailed(target)) {
                refuseLink(player, target, Refusal.DERAILED);
                return;
            }

            heldItem.set(MTDataComponents.SELECTED_CART, new SelectedCart(target.getUUID()));

            // Entity.playSound broadcasts to everyone nearby; Player.playSound passes the player as
            // the entity to exclude, so the one person who needs to hear this would not.
            target.playSound(SoundEvents.CHAIN_PLACE);
            return;
        }

        heldItem.remove(MTDataComponents.SELECTED_CART);
        Entity firstEntity = serverLevel.getEntity(selectedCart.value());

        if (selectedCart.value().equals(target.getUUID())
                || !(firstEntity instanceof AbstractMinecart first)) {
            target.playSound(SoundEvents.CHAIN_BREAK);
            return;
        }

        var refusal = refusalFor(serverLevel, first, target);
        if (refusal != null) {
            refuseLink(player, target, refusal);
            return;
        }

        Linkable.link(first, target);

        // Until now the two carts were tracked separately, so they are almost certainly being
        // replayed on different ticks.
        TrainCoupling.resyncTrain(target);

        target.playSound(SoundEvents.CHAIN_PLACE);
        heldItem.consume(1, player);
    }

    /**
     * Unlinks a cart from everything it is coupled to, optionally dropping the chains that held it
     * there. A cart that goes away without this leaves its UUID in its neighbours' slots, and a
     * slot holding a cart that no longer exists is occupied but resolves to nothing — the two carts
     * either side of the gap would be unable to link to anything again.
     */
    public static void severLinks(
            ServerLevel serverLevel, AbstractMinecart cart, boolean dropChains) {
        for (AbstractMinecart neighbour : cart.getNeighbours()) {
            Linkable.unlink(cart, neighbour);

            if (dropChains) {
                dropChain(serverLevel, cart, neighbour);
            }
        }
    }

    /**
     * Drops the chain that held two carts together, halfway between them so that a cart pulled out
     * of the middle of a train leaves one on each side rather than a stack of two underneath it.
     */
    public static void dropChain(
            ServerLevel serverLevel, AbstractMinecart cart, AbstractMinecart neighbour) {
        cart.spawnAtLocation(serverLevel, new ItemStack(Items.IRON_CHAIN),
                neighbour.position().subtract(cart.position()).scale(0.5));
    }

    /**
     * Points {@code cart}'s front along the rail under it, whichever of that rail's two directions
     * {@code look} agrees with.
     *
     * <p>Settled as the cart is put down rather than read off the first movement it happens to
     * make. A cart has a front — its chain hangs off one end of it — so a cart that later runs the
     * other way runs backwards instead of turning round to suit.
     */
    public static void faceAlongRail(Level level, AbstractMinecart cart, Vec3 look) {
        BlockPos on = cart.getCurrentBlockPosOrRailBelow();
        BlockPos exit = RailPath.ahead(level, on, look.horizontal());

        if (exit == null) {
            return;
        }

        cart.setFlipped(false);
        cart.setYRot(yawOf(Vec3.atLowerCornerOf(exit.subtract(on)).horizontal()));
    }

    /** The yaw a cart wears to point along {@code heading}, in vanilla's own reckoning. */
    public static float yawOf(Vec3 heading) {
        return (float) ((180.0 - Math.atan2(heading.z, heading.x) * 180.0 / Math.PI) % 360.0);
    }

    /** Which way a cart at yaw {@code yaw} points its front: the inverse of {@link #yawOf}. */
    public static Vec3 facingOf(float yaw) {
        double radians = Math.toRadians(180.0F - yaw);

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
    public static boolean tryUpgradeMinecart(
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
                craftedCart.minecarttweaks$getType(), EntitySpawnReason.DISPENSER, crafted, player);

        originalMinecart.ejectPassengers();
        replacement.copyPosition(originalMinecart);

        // Read the neighbours off the old cart and sever them by hand before it is removed:
        // removing a still-linked cart severs on its own and drops the chains, raining them on the
        // player mid-upgrade.
        List<AbstractMinecart> orphaned = originalMinecart.getNeighbours();
        severLinks(level, originalMinecart, false);

        originalMinecart.remove(Entity.RemovalReason.DISCARDED);
        level.addFreshEntity(replacement);

        if (replacement instanceof AbstractMinecart relinkable) {
            orphaned.forEach(neighbour -> Linkable.link(relinkable, neighbour));
        } else {
            // A result that is no cart at all hands the chains back instead of rewiring the train
            // around itself.
            replacement.spawnAtLocation(level, new ItemStack(Items.IRON_CHAIN, orphaned.size()));
        }

        heldItem.consume(1, player);
        player.setItemInHand(hand, heldItem);

        return true;
    }

    // Returns why this link is not allowed, or null if it is.
    private static @Nullable Refusal refusalFor(
            ServerLevel serverLevel, AbstractMinecart first, AbstractMinecart target) {
        // Both carts have to have a free slot, so one in the middle of a train is refused
        // whichever of the two it was clicked as. Asked of the slots rather than of the carts they
        // resolve to, so that this agrees with what Linkable.link will actually accept.
        if (!first.hasFreeSlot() || !target.hasFreeSlot()) {
            return Refusal.ALREADY_LINKED;
        }

        Train firstTrain = Train.of(first);

        if (firstTrain.contains(target)) {
            return Refusal.SAME_TRAIN;
        }

        // The length the two would make together, not either one's own, so two trains each within
        // the limit cannot be joined into one that is over it. The sizes add only because
        // same_train has already ruled out the two overlapping.
        if (firstTrain.size() + Train.of(target).size()
                > serverLevel.getGameRules().get(MTGameRules.MAX_TRAIN_LENGTH)) {
            return Refusal.TOO_LONG;
        }

        if (isDerailed(first) || isDerailed(target)) {
            return Refusal.DERAILED;
        }

        double linkLimit = TrainCoupling.maxLinkDistance();
        if (first.distanceTo(target) > linkLimit) {
            return Refusal.TOO_FAR;
        }

        if (!canUseFreeExit(first, target, linkLimit)
                || !canUseFreeExit(target, first, linkLimit)) {
            return Refusal.BLOCKED;
        }

        return isObstructed(serverLevel, first, target) ? Refusal.BLOCKED : null;
    }

    // Whether candidate lies beyond middle's unoccupied rail exit, or neither exit is occupied.
    private static boolean canUseFreeExit(
            AbstractMinecart middle, AbstractMinecart candidate, double limit) {
        if (!middle.hasLink()) {
            return true;
        }

        List<AbstractMinecart> neighbours = middle.getNeighbours();
        if (neighbours.size() != 1) {
            return false;
        }

        RailPath held = RailPath.between(neighbours.getFirst(), middle, limit);
        RailPath offered = RailPath.between(middle, candidate, limit);

        return held != null
                && offered != null
                && held.toForward().dot(offered.fromForward()) > 0.5;
    }

    // Tells the player why, and leaves the stack with its selection already cleared so the next
    // click starts a fresh pair rather than retrying against a cart that was refused.
    private static void refuseLink(Player player, Entity target, Refusal refusal) {
        player.sendOverlayMessage(refusal.message());
        target.playSound(SoundEvents.CHAIN_BREAK);
    }

    // A chain cannot reach past an intervening cart, so linking through one would produce a train
    // whose carts pass through each other.
    private static boolean isObstructed(
            ServerLevel serverLevel, AbstractMinecart first, AbstractMinecart target) {
        var from = first.getBoundingBox().getCenter();
        var to = target.getBoundingBox().getCenter();

        return ProjectileUtil.getEntityHitResult(
                serverLevel, first, from, to, new AABB(from, to).inflate(1),
                entity -> entity instanceof AbstractMinecart && entity != target, 0) != null;
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
