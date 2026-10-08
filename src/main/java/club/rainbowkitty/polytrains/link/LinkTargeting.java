package club.rainbowkitty.polytrains.link;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import net.fabricmc.fabric.api.entity.event.v1.ServerPlayerEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import org.jspecify.annotations.Nullable;

import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.scores.TeamColor;

import club.rainbowkitty.kalic0re.common.aim.Crosshair;
import club.rainbowkitty.kalic0re.common.glow.PrivateGlow;
import club.rainbowkitty.polytrains.init.EntityAttachments;

/**
 * The live picture a player gets while they are holding something that couples carts: a standing
 * prompt on the action bar, the cart they have picked outlined, and the cart under their crosshair
 * outlined in whether linking the two would be allowed.
 *
 * <p>Driven from a tick loop rather than from the click, because all of it answers to things the
 * click does not know: where the player is looking, how far they have walked since, and whether
 * the cart they picked is still there.
 */
public final class LinkTargeting {

    // How long the picked cart's outline stays on, then off. Only that one blinks: the verdict on
    // the cart under the crosshair answers a question asked right now, and one that comes and
    // goes is harder to read than one that stays.
    private static final int BLINK_TICKS = 16;

    // How long a click's own message holds the action bar before the standing prompt returns.
    private static final int NOTICE_TICKS = 40;

    // How often an unchanged action-bar line is re-sent: the client fades one after 40 of its 60
    // ticks, so this stays well inside that.
    private static final int OVERLAY_REFRESH = 20;

    // How far a player can walk from the cart they picked before the pair is given up on. Beyond
    // this they have left rather than paused, and a selection they cannot see is one they have
    // forgotten making.
    private static final double SELECTION_RANGE = 10.0;

    private static final Map<UUID, Session> SESSIONS = new HashMap<>();

    // Static-only; blocks instantiation.
    private LinkTargeting() {}

    /** Wires the per-tick update and the disconnect cleanup. */
    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(LinkTargeting::onEndTick);
        ServerPlayerEvents.LEAVE.register(LinkTargeting::onLeave);
    }

    /**
     * Puts {@code message} on {@code player}'s action bar for a couple of seconds, ahead of the
     * standing prompt. Everything a click has to say goes through here, or the next tick's prompt
     * would wipe it before it was read.
     */
    public static void notice(ServerPlayer player, Component message) {
        Session session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session());

        long now = player.level().getGameTime();

        session.noticeUntil = now + NOTICE_TICKS;
        session.notice = message;
        player.sendOverlayMessage(message);
        session.shownKey = message;
        session.shownTo = player;
        session.shownAt = now;
    }

    private static void onEndTick(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            update(player);
        }
    }

    private static void update(ServerPlayer player) {
        if (!(player.level() instanceof ServerLevel level)) {
            return;
        }

        Session session = SESSIONS.computeIfAbsent(player.getUUID(), id -> new Session());

        // Asked whatever the player is holding: a selection goes stale because the cart went or
        // the player walked off, neither of which is about what is in their hands.
        AbstractMinecart selected = heldSelection(player, level);

        if (!LinkAction.holdsWrench(player)) {
            clearOutlines(player, level, session);
            // So the prompt shows the moment the wrench is picked up again.
            session.shownKey = null;
            return;
        }

        AbstractMinecart aimed = Crosshair.entity(player, AbstractMinecart.class);
        boolean blinkOn = (level.getGameTime() % (BLINK_TICKS * 2L)) < BLINK_TICKS;
        List<Outline> wanted = new ArrayList<>(2);

        if (selected != null) {
            wanted.add(new Outline(selected.getId(), TeamColor.YELLOW, blinkOn));
        }

        if (aimed != null && aimed != selected) {
            wanted.add(new Outline(aimed.getId(),
                    LinkAction.wouldAct(player, level, selected, aimed)
                            ? TeamColor.GREEN
                            : TeamColor.RED,
                    true));
        }

        applyOutlines(player, level, session, wanted);
        showActionBar(player, level, session);
    }

    // The cart the player has picked, giving the pair up where it can no longer be held to.
    private static @Nullable AbstractMinecart heldSelection(
            ServerPlayer player, ServerLevel level) {
        UUID selectedId = EntityAttachments.getLinkSelection(player);

        if (selectedId == null) {
            return null;
        }

        Entity entity = level.getEntity(selectedId);

        if (!(entity instanceof AbstractMinecart cart) || cart.isRemoved()) {
            LinkAction.dropSelection(player, LinkAction.Notice.GONE);
            return null;
        }

        if (player.distanceToSqr(cart) > SELECTION_RANGE * SELECTION_RANGE) {
            LinkAction.dropSelection(player, LinkAction.Notice.WALKED_OFF);
            return null;
        }

        return cart;
    }

    // Sends only what changed: the outlines a player is shown are a lie their client holds onto
    // until told otherwise, so a steady picture needs no packets at all between blinks.
    private static void applyOutlines(
            ServerPlayer player, ServerLevel level, Session session, List<Outline> wanted) {
        if (wanted.equals(session.outlines)) {
            return;
        }

        // Compared by cart rather than whole, so a blink does not put the cart out and light it
        // again: only a cart that has dropped off the list needs telling.
        for (Outline stale : session.outlines) {
            if (wanted.stream().noneMatch(kept -> kept.entityId() == stale.entityId())) {
                unlight(player, level, stale.entityId());
            }
        }

        for (Outline outline : wanted) {
            Entity entity = level.getEntity(outline.entityId());

            if (entity != null) {
                PrivateGlow.show(player, entity, outline.colour(), outline.lit());
            }
        }

        session.outlines = wanted;
    }

    private static void clearOutlines(ServerPlayer player, ServerLevel level, Session session) {
        for (Outline outline : session.outlines) {
            unlight(player, level, outline.entityId());
        }

        session.outlines = List.of();
    }

    private static void unlight(ServerPlayer player, ServerLevel level, int entityId) {
        Entity entity = level.getEntity(entityId);

        if (entity != null) {
            PrivateGlow.clear(player, entity);
        }
    }

    // The click's own message while it is still fresh, and the standing prompt the rest of the
    // time. Vanilla fades an action-bar line after a few seconds, so the prompt is re-sent.
    private static void showActionBar(ServerPlayer player, ServerLevel level, Session session) {
        long now = level.getGameTime();
        Component notice = session.notice;

        if (notice != null && now < session.noticeUntil) {
            overlay(player, session, notice, () -> notice, now);
            return;
        }

        session.notice = null;

        WrenchMode mode = EntityAttachments.getWrenchMode(player);

        overlay(player, session, mode, () -> LinkAction.modeLabel(mode), now);
    }

    // Sends a line only when it differs from the one shown, or that one is due a refresh.
    private static void overlay(ServerPlayer player, Session session, Object key,
            Supplier<Component> message, long now) {
        if (key == session.shownKey && player == session.shownTo
                && now - session.shownAt < OVERLAY_REFRESH) {
            return;
        }

        player.sendOverlayMessage(message.get());
        session.shownKey = key;
        session.shownTo = player;
        session.shownAt = now;
    }

    private static void onLeave(ServerPlayer player) {
        SESSIONS.remove(player.getUUID());
    }

    // One cart a player is being shown, what it is being shown in, and whether it is lit this
    // tick — the blink is part of the picture, so a change to it is a change to send.
    private record Outline(int entityId, TeamColor colour, boolean lit) {}

    // What one player is currently being shown, so that a tick that changes nothing sends nothing.
    private static final class Session {
        private List<Outline> outlines = List.of();
        private @Nullable Component notice;
        private long noticeUntil;
        private @Nullable Object shownKey;
        private @Nullable ServerPlayer shownTo;
        private long shownAt;
    }
}
