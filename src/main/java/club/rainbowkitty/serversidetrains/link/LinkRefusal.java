package club.rainbowkitty.serversidetrains.link;

import java.util.Locale;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import club.rainbowkitty.serversidetrains.ServersideTrains;
import club.rainbowkitty.serversidetrains.ext.Linkable;
import club.rainbowkitty.serversidetrains.rail.RailPath;
import club.rainbowkitty.serversidetrains.rule.ModGameRules;
import club.rainbowkitty.serversidetrains.train.Train;
import club.rainbowkitty.serversidetrains.train.TrainCoupling;
import club.rainbowkitty.serversidetrains.util.MinecartHelper;

/** Why linking, or unlinking, two carts is not allowed. Each constant names its own message. */
public enum LinkRefusal {
    DISABLED,
    SAME_TRAIN,
    DERAILED,
    NO_CHAIN,
    NOT_LINKED,
    NOT_COUPLED,
    TOO_FAR,
    TOO_LONG,
    BLOCKED;

    // Slack on a distance measured against a round limit, so that noise in the last place of the
    // arithmetic does not decide a link.
    private static final double EPSILON = 1.0E-6;

    /** The message shown to the player, in red. */
    public Component message() {
        return Component.translatable(
                        ServersideTrains.MOD_ID + ".link." + name().toLowerCase(Locale.ROOT))
                .withStyle(ChatFormatting.RED);
    }

    /** Returns why linking these two is not allowed, or null if it is. */
    public static @Nullable LinkRefusal refusalFor(ServerPlayer player, ServerLevel serverLevel,
            AbstractMinecart first, AbstractMinecart target) {
        if (!hasLinkItem(player)) {
            return NO_CHAIN;
        }

        // Both carts have to have a free slot, so one in the middle of a train is refused
        // whichever of the two it was clicked as. Asked of the slots rather than of the carts they
        // resolve to, so this agrees with what Linkable.link will actually accept.
        if (!first.hasFreeSlot() || !target.hasFreeSlot()) {
            return BLOCKED;
        }

        Train firstTrain = Train.of(first);

        if (firstTrain.contains(target)) {
            return SAME_TRAIN;
        }

        // The length the two would make together, not either one's own, so two trains each within
        // the limit cannot be joined into one that is over it. The sizes add only because
        // same_train has already ruled out the two overlapping.
        if (firstTrain.size() + Train.of(target).size()
                > serverLevel.getGameRules().get(ModGameRules.MAX_TRAIN_LENGTH)) {
            return TOO_LONG;
        }

        if (MinecartHelper.isDerailed(first) || MinecartHelper.isDerailed(target)) {
            return DERAILED;
        }

        // Measured as the chord the chain spans, not the track run between them, so a curve costs
        // no more reach to link across than a straight span of the same length.
        //
        // Inclusive of the limit: a cart sits at its rail block's centre, so a pair placed a whole
        // number of blocks apart lands exactly on the limit — excluding it would cost the reach
        // its last block.
        double maxLinkDistance = TrainCoupling.maxLinkDistance();

        if (first.distanceTo(target) > maxLinkDistance + EPSILON) {
            return TOO_FAR;
        }

        // There still has to be track for the chain to follow, walked with room for the arc a
        // chord that long can hide behind. Nothing else asks this: canUseFreeExit walks the
        // rails, but answers yes outright for a cart with no existing link — true of both carts
        // the first time a pair is made.
        double maxLinkWalk = TrainCoupling.maxLinkWalk();

        if (RailPath.between(first, target, maxLinkWalk) == null) {
            return TOO_FAR;
        }

        if (!canUseFreeExit(first, target, maxLinkWalk)
                || !canUseFreeExit(target, first, maxLinkWalk)) {
            return BLOCKED;
        }

        return isObstructed(serverLevel, first, target) ? BLOCKED : null;
    }

    /** Why this cart cannot be picked as one end, or null if it can. */
    public static @Nullable LinkRefusal startRefusal(
            ServerPlayer player, WrenchMode mode, AbstractMinecart target) {
        if (mode == WrenchMode.UNLINK) {
            return target.hasLink() ? null : NOT_LINKED;
        }

        if (!hasLinkItem(player)) {
            return NO_CHAIN;
        }
        if (MinecartHelper.isDerailed(target)) {
            return DERAILED;
        }

        return target.hasFreeSlot() ? null : BLOCKED;
    }

    /** Why these two cannot be acted on as a pair, or null if they can. */
    public static @Nullable LinkRefusal pairRefusal(ServerPlayer player, ServerLevel serverLevel,
            WrenchMode mode, AbstractMinecart first, AbstractMinecart target) {
        if (mode == WrenchMode.UNLINK) {
            return Linkable.coupled(first, target) ? null : NOT_COUPLED;
        }

        return refusalFor(player, serverLevel, first, target);
    }

    /**
     * The first slot holding a link item, or -1. Asked of the whole inventory rather than of a
     * hand: the wrench is what is held now, so the chain being spent is wherever it was put.
     */
    public static int findLinkItem(ServerPlayer player) {
        Inventory inventory = player.getInventory();

        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            if (isLinkItem(inventory.getItem(slot))) {
                return slot;
            }
        }

        return -1;
    }

    // Whether the player is carrying anything a link can be made out of.
    private static boolean hasLinkItem(ServerPlayer player) {
        return player.hasInfiniteMaterials() || findLinkItem(player) >= 0;
    }

    // Whether this stack is one of the things a link is made out of.
    private static boolean isLinkItem(ItemStack stack) {
        return stack.is(LinkTags.Items.LINK_ITEMS);
    }

    // Whether candidate lies beyond middle's unoccupied rail exit, or neither exit is occupied.
    // The maxLinkWalk is how much track to walk, not how far apart the two may be — that is the
    // chord, and it has been answered before this is asked.
    private static boolean canUseFreeExit(
            AbstractMinecart middle, AbstractMinecart candidate, double maxLinkWalk) {
        if (!middle.hasLink()) {
            return true;
        }

        var neighbours = middle.getNeighbours();
        if (neighbours.size() != 1) {
            return false;
        }

        RailPath held = RailPath.between(neighbours.getFirst(), middle, maxLinkWalk);
        RailPath offered = RailPath.between(middle, candidate, maxLinkWalk);

        return held != null
                && offered != null
                && held.toForward().dot(offered.fromForward()) > 0.5;
    }

    // A chain cannot reach past an intervening cart, so linking through one would produce a train
    // whose carts pass through each other.
    private static boolean isObstructed(
            ServerLevel serverLevel, AbstractMinecart first, AbstractMinecart target) {
        var fromPosition = first.getBoundingBox().getCenter();
        var toPosition = target.getBoundingBox().getCenter();
        var bounds = new AABB(fromPosition, toPosition).inflate(1);

        return ProjectileUtil.getEntityHitResult(
                serverLevel, first, fromPosition, toPosition, bounds,
                entity -> entity instanceof AbstractMinecart && entity != target, 0) != null;
    }
}
