package club.rainbowkitty.minecarttweaks.util;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartBehavior;
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
import club.rainbowkitty.minecarttweaks.init.MTAttachments;
import club.rainbowkitty.minecarttweaks.init.MTItems;
import club.rainbowkitty.minecarttweaks.init.MTTags;
import club.rainbowkitty.minecarttweaks.init.WrenchMode;
import club.rainbowkitty.minecarttweaks.rail.RailPath;
import club.rainbowkitty.minecarttweaks.rule.MTGameRules;
import club.rainbowkitty.minecarttweaks.train.Train;
import club.rainbowkitty.minecarttweaks.train.TrainCoupling;
import club.rainbowkitty.minecarttweaks.visual.LinkTargeting;

/** Utility methods for minecart linking, upgrading, and brake logic. */
public final class MinecartHelper {
    /** Why a link was not allowed. Each constant names its own overlay message. */
    public enum Refusal {
        DISABLED,
        SAME_TRAIN,
        DERAILED,
        NO_CHAIN,
        NOT_LINKED,
        NOT_COUPLED,
        TOO_FAR,
        TOO_LONG,
        BLOCKED;

        /** The message shown to the player, in the colour a refusal is shown in. */
        public Component message() {
            return linkMessage(name().toLowerCase(Locale.ROOT), ChatFormatting.RED);
        }
    }

    /** What a link has to say when it is not refusing. Each constant names its own message. */
    public enum Notice {
        SELECTED(ChatFormatting.YELLOW),
        CLEARED(ChatFormatting.GRAY),
        LINKED(ChatFormatting.GREEN),
        UNLINKED(ChatFormatting.GREEN),
        GONE(ChatFormatting.GRAY),
        WALKED_OFF(ChatFormatting.RED),
        LINK_MODE(ChatFormatting.AQUA),
        UNLINK_MODE(ChatFormatting.GOLD);

        private final ChatFormatting colour;

        Notice(ChatFormatting colour) {
            this.colour = colour;
        }

        /** The message shown to the player, in this outcome's own colour. */
        public Component message() {
            return linkMessage(name().toLowerCase(Locale.ROOT), this.colour);
        }
    }

    // Static-only; blocks instantiation.
    private MinecartHelper() {}

    /**
     * Answers one use of a minecart. The wrench does whichever job it is set to; shift-clicking
     * with anything else tries to craft the cart into whatever that item upgrades it to.
     */
    public static InteractionResult useMinecart(
            Player player, Level level, InteractionHand hand, AbstractMinecart minecart) {
        // A click the client cannot consume is offered to the off hand as well, and the main hand
        // has already answered it: a second wrench would attempt the pair again, against a
        // selection the first has spent, and anything else would upgrade the cart being coupled.
        // Asked of when the last click was answered rather than of what either hand holds, which
        // a swap between the two halves of one click would otherwise change the answer to.
        if (hand == InteractionHand.OFF_HAND
                && level instanceof ServerLevel answered
                && MTAttachments.answeredUseAt(player, answered.getGameTime())) {
            return InteractionResult.PASS;
        }

        // Both of the things a use can mean here are shift-clicks. Leaving the plain click alone
        // is also what lets a player still ride the cart: vanilla's own ride declines a sneaking
        // click before any of this runs, which is sturdier than cancelling the ride afterwards.
        if (!player.isShiftKeyDown()) {
            return InteractionResult.PASS;
        }

        ItemStack heldItem = player.getItemInHand(hand);

        if (isWrench(heldItem)) {
            // The client answers its own click so its hand swings without waiting on the server;
            // everything the link itself needs is server-side.
            if (!(level instanceof ServerLevel serverLevel)
                    || !(player instanceof ServerPlayer viewer)) {
                return InteractionResult.SUCCESS;
            }

            MTAttachments.setAnsweredUseAt(player, serverLevel.getGameTime());

            if (!MTGameRules.trainsEnabled(serverLevel)) {
                LinkTargeting.notice(viewer, Refusal.DISABLED.message());
                return InteractionResult.FAIL;
            }

            useWrench(viewer, serverLevel, minecart);
            return InteractionResult.SUCCESS;
        }

        if (player instanceof ServerPlayer serverPlayer
                && level instanceof ServerLevel serverLevel
                && tryUpgradeMinecart(serverPlayer, serverLevel, hand, minecart, heldItem)) {
            MTAttachments.setAnsweredUseAt(player, serverLevel.getGameTime());
            player.swing(hand, true);
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
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
     * Points the cart {@code behavior} drives along the rail at {@code on}, whichever of that
     * rail's two directions {@code look} agrees with.
     *
     * <p>Settled as the cart is put down rather than read off the first movement it happens to
     * make. A cart has a front — its chain hangs off one end of it — so a cart that later runs the
     * other way runs backwards instead of turning round to suit.
     *
     * <p>Leaves the flip alone. A cart is placed unflipped, and the rail adjustment that follows
     * reconciles the two itself: it reads the rail's own first exit, which is this heading or its
     * opposite, and turns the cart end for end rather than round if it is the opposite.
     */
    public static void faceAlongRail(
            Level level, BlockPos on, MinecartBehavior behavior, Vec3 look) {
        BlockPos exit = RailPath.ahead(level, on, look.horizontal());

        if (exit != null) {
            behavior.setYRot(yawOf(Vec3.atLowerCornerOf(exit.subtract(on)).horizontal()));
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

    /** Whether either of the player's hands holds the tool that couples carts. */
    public static boolean holdsWrench(Player player) {
        return isWrench(player.getMainHandItem()) || isWrench(player.getOffhandItem());
    }

    /**
     * Whether clicking {@code target} with the wrench as it is set would do anything: act on the
     * pair it makes with {@code first}, or be picked as one end of a pair of its own.
     *
     * <p>The same questions the click itself asks, so what the display promises is what happens.
     */
    public static boolean canUse(ServerPlayer player, ServerLevel serverLevel,
            @Nullable AbstractMinecart first, AbstractMinecart target) {
        WrenchMode mode = MTAttachments.getWrenchMode(player);

        return first == null
                ? startRefusal(player, mode, target) == null
                : pairRefusal(player, serverLevel, mode, first, target) == null;
    }

    /** The standing label naming the job the wrench is set to. */
    public static Component modeLabel(WrenchMode mode) {
        return (mode == WrenchMode.UNLINK ? Notice.UNLINK_MODE : Notice.LINK_MODE).message();
    }

    /** Gives up a player's half-made link, telling them which way it went. */
    public static void dropSelection(ServerPlayer player, Notice why) {
        MTAttachments.setLinkSelection(player, null);
        LinkTargeting.notice(player, why.message());
    }

    /** Whether {@code entity} is a cart currently sitting on no rail. */
    public static boolean isDerailed(Entity entity) {
        return entity instanceof AbstractMinecart cart
                && !BaseRailBlock.isRail(
                        cart.level().getBlockState(cart.getCurrentBlockPosOrRailBelow()));
    }

    // The overlay line for one link outcome, under this mod's shared link message keys.
    private static Component linkMessage(String outcome, ChatFormatting colour) {
        return Component.translatable(MinecartTweaks.MOD_ID + ".link." + outcome)
                .withStyle(colour);
    }

    // Whether this stack is the tool a link is made with.
    private static boolean isWrench(ItemStack stack) {
        return stack.is(MTItems.WRENCH);
    }

    // Whether this stack is one of the things a link is made out of.
    private static boolean isLinkItem(ItemStack stack) {
        return stack.is(MTTags.Items.LINK_ITEMS);
    }

    // Why this cart cannot be picked as one end, or null if it can.
    private static @Nullable Refusal startRefusal(
            ServerPlayer player, WrenchMode mode, AbstractMinecart target) {
        if (mode == WrenchMode.UNLINK) {
            return target.hasLink() ? null : Refusal.NOT_LINKED;
        }

        if (!hasLinkItem(player)) {
            return Refusal.NO_CHAIN;
        }
        if (isDerailed(target)) {
            return Refusal.DERAILED;
        }

        return target.hasFreeSlot() ? null : Refusal.BLOCKED;
    }

    // Why these two cannot be acted on as a pair, or null if they can.
    private static @Nullable Refusal pairRefusal(ServerPlayer player, ServerLevel serverLevel,
            WrenchMode mode, AbstractMinecart first, AbstractMinecart target) {
        if (mode == WrenchMode.UNLINK) {
            return Linkable.coupled(first, target) ? null : Refusal.NOT_COUPLED;
        }

        return refusalFor(player, serverLevel, first, target);
    }

    // Whether the player is carrying anything a link can be made out of.
    private static boolean hasLinkItem(ServerPlayer player) {
        return player.hasInfiniteMaterials() || findLinkItem(player) >= 0;
    }

    // Spends one link item out of the player's pack, wherever in it they are keeping them.
    private static void takeLinkItem(ServerPlayer player) {
        int slot = findLinkItem(player);

        if (!player.hasInfiniteMaterials() && slot >= 0) {
            player.getInventory().getItem(slot).shrink(1);
        }
    }

    // The first slot holding a link item, or -1. Asked of the whole inventory rather than of a
    // hand: the wrench is what is held now, so the chain being spent is wherever it was put.
    private static int findLinkItem(ServerPlayer player) {
        Inventory inventory = player.getInventory();

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isLinkItem(inventory.getItem(slot))) {
                return slot;
            }
        }

        return -1;
    }

    /**
     * Does whichever job the wrench is set to. Both take the same two clicks: the first picks one
     * cart, a second on a different one acts on the pair, and a second on the same one calls it
     * off — only the questions asked along the way differ between them.
     */
    private static void useWrench(
            ServerPlayer player, ServerLevel serverLevel, AbstractMinecart target) {
        WrenchMode mode = MTAttachments.getWrenchMode(player);
        UUID selected = MTAttachments.getLinkSelection(player);

        if (selected == null) {
            Refusal refusal = startRefusal(player, mode, target);

            if (refusal != null) {
                refuseLink(player, target, refusal);
                return;
            }

            MTAttachments.setLinkSelection(player, target.getUUID());
            LinkTargeting.notice(player, Notice.SELECTED.message());
            target.playSound(SoundEvents.CHAIN_PLACE);
            return;
        }

        MTAttachments.setLinkSelection(player, null);
        Entity firstEntity = serverLevel.getEntity(selected);

        // Clicking the picked cart again calls the pair off, as does one whose other end has gone.
        // Said out loud: a click that silently undid the one before it reads as a click that did
        // nothing, and the answer to that is to click again, which undoes the next one too.
        if (selected.equals(target.getUUID())
                || !(firstEntity instanceof AbstractMinecart first)) {
            LinkTargeting.notice(player, Notice.CLEARED.message());
            target.playSound(SoundEvents.CHAIN_BREAK);
            return;
        }

        Refusal refusal = pairRefusal(player, serverLevel, mode, first, target);

        if (refusal != null) {
            refuseLink(player, target, refusal);
            return;
        }

        if (mode == WrenchMode.UNLINK) {
            unlinkPair(player, first, target);
        } else {
            linkPair(player, first, target);
        }
    }

    // Couples the pair, spending a chain out of the player's pack.
    private static void linkPair(
            ServerPlayer player, AbstractMinecart first, AbstractMinecart target) {
        Linkable.link(first, target);

        // Until now the two carts were tracked separately, so they are almost certainly being
        // replayed on different ticks.
        TrainCoupling.resyncTrain(target);

        LinkTargeting.notice(player, Notice.LINKED.message());
        target.playSound(SoundEvents.CHAIN_PLACE);
        takeLinkItem(player);
    }

    // Breaks the one coupling between the pair, handing the chain that held it back to the player
    // rather than dropping it: they were stood there to take it off, so they are who it belongs to.
    private static void unlinkPair(
            ServerPlayer player, AbstractMinecart first, AbstractMinecart target) {
        Linkable.unlink(first, target);

        // Nothing is handed back in creative, where nothing was spent making it either.
        if (!player.hasInfiniteMaterials()) {
            player.getInventory().placeItemBackInInventory(new ItemStack(Items.IRON_CHAIN));
        }

        LinkTargeting.notice(player, Notice.UNLINKED.message());
        target.playSound(SoundEvents.CHAIN_BREAK);
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
                craftedCart.minecarttweaks$type(), EntitySpawnReason.DISPENSER, crafted, player);

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

    /** Returns why linking these two is not allowed, or null if it is. */
    public static @Nullable Refusal refusalFor(ServerPlayer player, ServerLevel serverLevel,
            AbstractMinecart first, AbstractMinecart target) {
        if (!hasLinkItem(player)) {
            return Refusal.NO_CHAIN;
        }

        // Both carts have to have a free slot, so one in the middle of a train is refused
        // whichever of the two it was clicked as. Asked of the slots rather than of the carts they
        // resolve to, so that this agrees with what Linkable.link will actually accept.
        if (!first.hasFreeSlot() || !target.hasFreeSlot()) {
            return Refusal.BLOCKED;
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

        // Measured as the chord — the line the chain itself spans — and not as the run of track
        // between the two. A pair either side of a curve sits as close together as the same pair
        // on a straight, and charging the reach for the arc would make a bend cost more to link
        // across than the gap it actually leaves.
        double linkLimit = TrainCoupling.maxLinkDistance();

        if (first.distanceTo(target) >= linkLimit) {
            return Refusal.TOO_FAR;
        }

        // There still has to be track for the chain to follow, walked with room for the arc a
        // chord that long can hide behind it. Nothing else asks this — canUseFreeExit walks the
        // rails, but answers yes outright for a cart with no link to hold the walk against, which
        // is both of them the first time a pair is made.
        double reach = TrainCoupling.maxLinkWalk();

        if (RailPath.between(first, target, reach) == null) {
            return Refusal.TOO_FAR;
        }

        if (!canUseFreeExit(first, target, reach)
                || !canUseFreeExit(target, first, reach)) {
            return Refusal.BLOCKED;
        }

        return isObstructed(serverLevel, first, target) ? Refusal.BLOCKED : null;
    }

    // Whether candidate lies beyond middle's unoccupied rail exit, or neither exit is occupied.
    // The reach is how much track to walk, not how far apart the two may be — that is the chord,
    // and it has been answered before this is asked.
    private static boolean canUseFreeExit(
            AbstractMinecart middle, AbstractMinecart candidate, double reach) {
        if (!middle.hasLink()) {
            return true;
        }

        List<AbstractMinecart> neighbours = middle.getNeighbours();
        if (neighbours.size() != 1) {
            return false;
        }

        RailPath held = RailPath.between(neighbours.getFirst(), middle, reach);
        RailPath offered = RailPath.between(middle, candidate, reach);

        return held != null
                && offered != null
                && held.toForward().dot(offered.fromForward()) > 0.5;
    }

    // Tells the player why. The player's selection is already cleared by this point, so the next
    // click starts a fresh pair rather than retrying against a cart that was refused.
    private static void refuseLink(ServerPlayer player, Entity target, Refusal refusal) {
        LinkTargeting.notice(player, refusal.message());
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
