package club.rainbowkitty.minecarttweaks.init;

import net.fabricmc.fabric.api.attachment.v1.AttachmentRegistry;
import net.fabricmc.fabric.api.attachment.v1.AttachmentTarget;
import net.fabricmc.fabric.api.attachment.v1.AttachmentType;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

import club.rainbowkitty.minecarttweaks.MinecartTweaks;

/**
 * This mod's entity attachments. Persistent but unsynced — the chain between linked carts is drawn
 * server-side, so the client never needs the value. A client-drawn chain would, which is what
 * {@code docs/plan/client-mod-support.md} S2 is for.
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

    /** The heading a brake is holding the cart on, or null if it is not waiting on one. */
    public static @Nullable Direction getHeldHeading(AbstractMinecart minecart) {
        return ((AttachmentTarget) minecart).getAttached(HELD_HEADING);
    }

    /** Records the heading a brake is holding the cart on, or null to forget it. */
    public static void setHeldHeading(AbstractMinecart minecart, @Nullable Direction heading) {
        ((AttachmentTarget) minecart).setAttached(HELD_HEADING, heading);
    }
}
