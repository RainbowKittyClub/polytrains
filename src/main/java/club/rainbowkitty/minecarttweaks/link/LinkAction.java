package club.rainbowkitty.minecarttweaks.link;

import java.util.Locale;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import club.rainbowkitty.minecarttweaks.MinecartTweaks;
import club.rainbowkitty.minecarttweaks.ext.Linkable;
import club.rainbowkitty.minecarttweaks.init.EntityAttachments;
import club.rainbowkitty.minecarttweaks.item.WrenchItems;
import club.rainbowkitty.minecarttweaks.train.TrainCoupling;

/** Carries out a link or unlink the wrench has been clicked into, and tells the player about it. */
public final class LinkAction {

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
            return Component.translatable(
                            MinecartTweaks.MOD_ID + ".link." + name().toLowerCase(Locale.ROOT))
                    .withStyle(this.colour);
        }
    }

    // Static-only; blocks instantiation.
    private LinkAction() {}

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
     * Whether clicking {@code target} with the wrench as it is set would do anything: act on the
     * pair it makes with {@code first}, or be picked as one end of a pair of its own.
     *
     * <p>The same questions the click itself asks, so what the display promises is what happens.
     */
    public static boolean canUse(ServerPlayer player, ServerLevel serverLevel,
            @Nullable AbstractMinecart first, AbstractMinecart target) {
        WrenchMode mode = EntityAttachments.getWrenchMode(player);

        return first == null
                ? LinkRefusal.startRefusal(player, mode, target) == null
                : LinkRefusal.pairRefusal(player, serverLevel, mode, first, target) == null;
    }

    /** The standing label naming the job the wrench is set to. */
    public static Component modeLabel(WrenchMode mode) {
        return (mode == WrenchMode.UNLINK ? Notice.UNLINK_MODE : Notice.LINK_MODE).message();
    }

    /** Gives up a player's half-made link, telling them which way it went. */
    public static void dropSelection(ServerPlayer player, Notice why) {
        EntityAttachments.setLinkSelection(player, null);
        LinkTargeting.notice(player, why.message());
    }

    /** Whether either of the player's hands holds the tool that couples carts. */
    public static boolean holdsWrench(Player player) {
        return WrenchItems.isWrench(player.getMainHandItem())
                || WrenchItems.isWrench(player.getOffhandItem());
    }

    /**
     * Does whichever job the wrench is set to. Both take the same two clicks: the first picks one
     * cart, a second on a different one acts on the pair, and a second on the same one calls it
     * off — only the questions asked along the way differ between them.
     */
    public static void useWrench(
            ServerPlayer player, ServerLevel serverLevel, AbstractMinecart target) {
        WrenchMode mode = EntityAttachments.getWrenchMode(player);
        UUID selected = EntityAttachments.getLinkSelection(player);

        if (selected == null) {
            LinkRefusal refusal = LinkRefusal.startRefusal(player, mode, target);

            if (refusal != null) {
                refuseLink(player, target, refusal);
                return;
            }

            EntityAttachments.setLinkSelection(player, target.getUUID());
            LinkTargeting.notice(player, Notice.SELECTED.message());
            target.playSound(SoundEvents.CHAIN_PLACE);
            return;
        }

        EntityAttachments.setLinkSelection(player, null);
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

        LinkRefusal refusal = LinkRefusal.pairRefusal(player, serverLevel, mode, first, target);

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

    // Tells the player why. The player's selection is already cleared by this point, so the next
    // click starts a fresh pair rather than retrying against a cart that was refused.
    private static void refuseLink(ServerPlayer player, Entity target, LinkRefusal refusal) {
        LinkTargeting.notice(player, refusal.message());
        target.playSound(SoundEvents.CHAIN_BREAK);
    }

    // Spends one link item out of the player's pack, wherever in it they are keeping them.
    private static void takeLinkItem(ServerPlayer player) {
        int slot = LinkRefusal.findLinkItem(player);

        if (!player.hasInfiniteMaterials() && slot >= 0) {
            player.getInventory().getItem(slot).shrink(1);
        }
    }
}
