package club.rainbowkitty.minecarttweaks.visual;

import java.util.List;
import java.util.Optional;

import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPlayerTeamPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.scores.PlayerTeam;
import net.minecraft.world.scores.Scoreboard;
import net.minecraft.world.scores.TeamColor;

import club.rainbowkitty.minecarttweaks.MinecartTweaks;

/**
 * Lights one entity up for one player, without the rest of the server seeing it.
 *
 * <p>Glowing is a bit of an entity's shared flags and its colour comes from the scoreboard team the
 * entity is in, so both are server state that every client is told about. This lies to one client
 * instead: it sends that player a flags value the server does not hold, and a team that exists
 * only in their copy of the scoreboard. Nothing here touches the entity or the real scoreboard, so
 * there is nothing to clean up on the server and nothing another player can be shown by mistake.
 *
 * <p>The lie is only as durable as the client's copy. Any real update to the entity's flags
 * overwrites it, so a caller has to keep saying it — which is what the blink is doing anyway.
 */
public final class PrivateGlow {

    // Which bit of the shared flags byte means glowing.
    private static final int GLOWING_FLAG = 6;

    // The teams only ever exist in one client's scoreboard, so they are built against a throwaway
    // one rather than the world's: they are here to be serialised into a packet, never registered.
    private static final Scoreboard PACKET_SCOREBOARD = new Scoreboard();

    // Static-only; blocks instantiation.
    private PrivateGlow() {}

    /**
     * Shows {@code viewer} the outline of {@code entity} in {@code colour}, or takes it away again.
     *
     * <p>Must be repeated to stay up: the client forgets it as soon as the server tells it anything
     * else about that entity.
     */
    public static void show(
            ServerPlayer viewer, Entity entity, TeamColor colour, boolean lit) {
        viewer.connection.send(colourPacket(entity, colour));
        viewer.connection.send(new ClientboundSetEntityDataPacket(
                entity.getId(), List.of(flags(entity, lit))));
    }

    /**
     * Puts {@code viewer}'s copy of {@code entity} back to what the server actually holds.
     * Harmless on an entity that was never lit, and on one that has since been removed.
     */
    public static void clear(ServerPlayer viewer, Entity entity) {
        viewer.connection.send(new ClientboundSetEntityDataPacket(
                entity.getId(), List.of(flags(entity, false))));
    }

    // The entity's own flags byte with the glowing bit forced on or off, packed as the one synced
    // value to overwrite. Built from the live byte so nothing else about the entity is disturbed:
    // a cart that is genuinely on fire stays on fire in the copy the viewer is sent.
    private static SynchedEntityData.DataValue<?> flags(Entity entity, boolean lit) {
        byte held = entity.getEntityData().get(Entity.DATA_SHARED_FLAGS_ID);
        byte value = (byte) (lit
                ? held | 1 << GLOWING_FLAG
                : held & ~(1 << GLOWING_FLAG));

        return SynchedEntityData.DataValue.create(Entity.DATA_SHARED_FLAGS_ID, value);
    }

    // A team carrying the wanted colour, with this entity as its only member. One team per colour
    // rather than one per entity, so aiming from cart to cart re-sends a membership rather than
    // building a team each tick; the client drops an entity from the team it was in as it joins
    // the next, so the two colours cannot both be on at once.
    private static ClientboundSetPlayerTeamPacket colourPacket(
            Entity entity, TeamColor colour) {
        PlayerTeam team = new PlayerTeam(PACKET_SCOREBOARD,
                MinecartTweaks.MOD_ID + "_" + colour.getSerializedName());
        team.setColor(Optional.of(colour));
        team.getPlayers().add(entity.getStringUUID());

        return ClientboundSetPlayerTeamPacket.createAddOrModifyPacket(team, true);
    }
}
