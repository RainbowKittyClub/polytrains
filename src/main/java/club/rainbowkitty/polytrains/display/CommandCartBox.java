package club.rainbowkitty.polytrains.display;

import java.util.function.Consumer;

import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

import club.rainbowkitty.kalic0re.common.vehicle.CargoDisplayHolder;
import club.rainbowkitty.polytrains.item.WrenchItems;

/**
 * The box a command block cart is clicked through by a player holding the wrench, so the wrench's
 * click reaches the cart without opening its editor.
 *
 * <p>A client opens a command block cart's editor itself, from
 * {@code MinecartCommandBlock#interact}, on any click by a player who can use game-master blocks —
 * sneaking or not, whatever is in hand — and nothing the server sends closes it. A click on this
 * {@link ClickBox} never reaches that code: it is passed to the cart on the server, where the
 * wrench does what it does on any cart. It is shown only to such a player, and only while the
 * wrench is in hand; everyone else clicks the cart, editor and all.
 */
public class CommandCartBox extends CargoDisplayHolder {

    // The box's top above the cart's position: the top of the command block the cart carries,
    // drawn as a furnace cart's furnace is (0.75 scale from 0.28125, AbstractMinecartRenderer),
    // and a hair.
    private static final float TOP = 17.0f / 16.0f;

    private final ClickBox box;

    /** @param cart the command block cart the box rides */
    public CommandCartBox(AbstractMinecart cart) {
        super(cart);
        this.box = new ClickBox(this, TOP,
                player -> player.canUseGameMasterBlocks()
                        && WrenchItems.isWrench(player.getMainHandItem()),
                ClickBox.passedTo(cart));

        poseNow();
    }

    /**
     * Puts the box on {@code cart} and announces it to every client already watching the cart. A
     * rider is otherwise only announced to a client when it starts watching.
     */
    public static CommandCartBox attach(AbstractMinecart cart) {
        CommandCartBox display = new CommandCartBox(cart);

        EntityAttachment.ofTicking(display, cart);
        display.sendPacket(new ClientboundSetPassengersPacket(cart));
        return display;
    }

    @Override
    protected void onTick() {
        box.tick(getWatchingPlayers());
        super.onTick();
    }

    @Override
    protected void startWatchingExtraPackets(ServerGamePacketListenerImpl player,
            Consumer<Packet<ClientGamePacketListener>> packetConsumer) {
        super.startWatchingExtraPackets(player, packetConsumer);
        box.startWatching(player, packetConsumer);
    }

    @Override
    public boolean stopWatching(ServerGamePacketListenerImpl player) {
        box.stopWatching(player);
        return super.stopWatching(player);
    }

    // A world-aligned box, which no pose turns.
    @Override
    protected void applyPose(float yaw, float pitch, boolean turned) {
    }
}
