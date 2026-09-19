package club.rainbowkitty.minecarttweaks.init;

import java.util.UUID;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

import club.rainbowkitty.minecarttweaks.MinecartTweaks;

/**
 * This mod's entity attachments. None are synced — the chain between linked carts is drawn
 * server-side, so the client never needs the value. A client-drawn chain would, which is what
 * {@code docs/plan/client-mod-support.md} S2 is for. The cart's own state persists; a player's
 * half-made link does not, so a relog starts the next pair fresh rather than resuming one whose
 * other end may be gone.
 */
public final class MTAttachments {

    /** The link-state attachment carried by every linked cart. */
    public static final AttachmentType<LinkableData> LINK =
            AttachmentRegistry.createPersistent(MinecartTweaks.id("link"), LinkableData.CODEC);

    /**
     * The heading a cart arrived on, kept only while a powered-rail brake holds it stopped.
     * Persistent because a station's whole job is to hold a cart across a restart.
     */
    public static final AttachmentType<Direction> HELD_HEADING = AttachmentRegistry
            .createPersistent(MinecartTweaks.id("held_heading"), Direction.CODEC);

    /**
     * The cart a player has picked as one end of the link being built. Held by the player rather
     * than by the chain offering it, so that it is the same selection whichever hand a click names
     * and it outlives the stack that started it being spent.
     */
    public static final AttachmentType<UUID> LINK_SELECTION =
            AttachmentRegistry.create(MinecartTweaks.id("link_selection"));

    /**
     * The tick a player's last use of a cart was answered on. One click reaches the server once per
     * hand, and the second half must not answer it again.
     */
    public static final AttachmentType<Long> USE_ANSWERED_AT =
            AttachmentRegistry.create(MinecartTweaks.id("use_answered_at"));

    /**
     * Which job a player's wrench is doing. Persistent, unlike the selection: a tool's setting is
     * something the player chose, not something one session happened to be part-way through.
     */
    public static final AttachmentType<WrenchMode> WRENCH_MODE = AttachmentRegistry
            .createPersistent(MinecartTweaks.id("wrench_mode"), WrenchMode.CODEC);

    // Static-only; blocks instantiation.
    private MTAttachments() {}

    /** Forces class initialization so the type is registered before the first world loads. */
    public static void init() {}

    // AttachmentTarget is mixed into Entity at runtime rather than interface-injected at compile
    // time, so every call site would otherwise need this cast.
    /** Returns the cart's link state, or {@link LinkableData#EMPTY} if it has never been linked. */
    public static LinkableData getLink(AbstractMinecart minecart) {
        return ((AttachmentTarget) minecart).getAttachedOrElse(LINK, LinkableData.EMPTY);
    }

    /** Replaces the cart's link state. */
    public static void setLink(AbstractMinecart minecart, LinkableData data) {
        ((AttachmentTarget) minecart).setAttached(LINK, data);
    }

    /** The cart the player has picked as one end of a link, or null if the next click picks one. */
    public static @Nullable UUID getLinkSelection(Player player) {
        return ((AttachmentTarget) player).getAttached(LINK_SELECTION);
    }

    /** Records the cart the player has picked, or null to forget it. */
    public static void setLinkSelection(Player player, @Nullable UUID cart) {
        ((AttachmentTarget) player).setAttached(LINK_SELECTION, cart);
    }

    /** Whether this player's use of a cart has already been answered on {@code tick}. */
    public static boolean answeredUseAt(Player player, long tick) {
        return ((AttachmentTarget) player).getAttachedOrElse(USE_ANSWERED_AT, Long.MIN_VALUE)
                == tick;
    }

    /** Records that this player's use of a cart has been answered on {@code tick}. */
    public static void setAnsweredUseAt(Player player, long tick) {
        ((AttachmentTarget) player).setAttached(USE_ANSWERED_AT, tick);
    }

    /** The job the player's wrench is set to, making links until they switch it. */
    public static WrenchMode getWrenchMode(Player player) {
        return ((AttachmentTarget) player).getAttachedOrElse(WRENCH_MODE, WrenchMode.LINK);
    }

    /** Records the job the player's wrench is set to. */
    public static void setWrenchMode(Player player, WrenchMode mode) {
        ((AttachmentTarget) player).setAttached(WRENCH_MODE, mode);
    }

    /** The heading a brake is holding the cart on, or null if it is not waiting on one. */
    public static @Nullable Direction getHeldHeading(AbstractMinecart minecart) {
        return ((AttachmentTarget) minecart).getAttached(HELD_HEADING);
    }

    /** Records the heading a brake is holding the cart on, or null to forget it. */
    public static void setHeldHeading(AbstractMinecart minecart, @Nullable Direction heading) {
        ((AttachmentTarget) minecart).setAttached(HELD_HEADING, heading);
    }
}
