package club.rainbowkitty.serversidetrains.init;

import java.util.UUID;

import com.mojang.serialization.Codec;
import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

import club.rainbowkitty.serversidetrains.ServersideTrains;
import club.rainbowkitty.serversidetrains.link.LinkableData;
import club.rainbowkitty.serversidetrains.link.WrenchMode;

/**
 * This mod's entity data attachments.
 */
public final class EntityAttachments {

    /** The link-state attachment carried by every linked cart. */
    public static final AttachmentType<LinkableData> LINK =
            AttachmentRegistry.createPersistent(ServersideTrains.id("link"), LinkableData.CODEC);

    /**
     * The heading a cart arrived on, kept only while a powered-rail brake holds it stopped.
     * Persistent because a station's whole job is to hold a cart across a restart.
     */
    public static final AttachmentType<Direction> HELD_HEADING = AttachmentRegistry
            .createPersistent(ServersideTrains.id("held_heading"), Direction.CODEC);

    /**
     * The cart a player has picked as one end of the link being built. Held by the player rather
     * than by the chain offering it, so it is the same selection whichever hand a click names
     * and it outlives the stack that started it being spent.
     */
    public static final AttachmentType<UUID> LINK_SELECTION =
            AttachmentRegistry.create(ServersideTrains.id("link_selection"));

    /**
     * The tick a player's last use of a cart was answered on. One click reaches the server once per
     * hand, and the second half must not answer it again.
     */
    public static final AttachmentType<Long> USE_ANSWERED_AT =
            AttachmentRegistry.create(ServersideTrains.id("use_answered_at"));

    /**
     * Which job a player's wrench is doing. Persistent, unlike the selection.
     */
    public static final AttachmentType<WrenchMode> WRENCH_MODE = AttachmentRegistry
            .createPersistent(ServersideTrains.id("wrench_mode"), WrenchMode.CODEC);

    /**
     * Set on a furnace cart whose brake lever is thrown, and absent otherwise. Persistent, since a
     * train left braked is to be found braked.
     */
    public static final AttachmentType<Boolean> BRAKE_LEVER = AttachmentRegistry
            .createPersistent(ServersideTrains.id("brake_lever"), Codec.BOOL);

    // Static-only; blocks instantiation.
    private EntityAttachments() {}

    /** Forces class initialization so the type is registered before the first world loads. */
    public static void init() {}

    // AttachmentTarget is mixed into Entity at runtime rather than interface-injected at compile
    // time, so every call site would otherwise need this cast.
    /** Returns the cart's link state, or {@link LinkableData#EMPTY} if it has never been linked. */
    public static LinkableData getLink(AbstractMinecart minecart) {
        return ((AttachmentTarget) minecart).getAttachedOrElse(LINK, LinkableData.EMPTY);
    }

    /** Replaces the cart's link state. */
    public static void setLink(AbstractMinecart minecart, LinkableData linkData) {
        ((AttachmentTarget) minecart).setAttached(LINK, linkData);
    }

    /** The cart the player has picked as one end of a link, or null if the next click picks one. */
    public static @Nullable UUID getLinkSelection(Player player) {
        return ((AttachmentTarget) player).getAttached(LINK_SELECTION);
    }

    /** Records the cart the player has picked, or null to forget it. */
    public static void setLinkSelection(Player player, @Nullable UUID cartId) {
        ((AttachmentTarget) player).setAttached(LINK_SELECTION, cartId);
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

    /** Whether the cart's brake lever is thrown; only a furnace cart ever has one thrown. */
    public static boolean leverSet(AbstractMinecart minecart) {
        return ((AttachmentTarget) minecart).getAttachedOrElse(BRAKE_LEVER, false);
    }

    /** Throws the cart's brake lever, or releases it. */
    public static void setLever(AbstractMinecart minecart, boolean set) {
        ((AttachmentTarget) minecart).setAttached(BRAKE_LEVER, set ? Boolean.TRUE : null);
    }
}
