package club.rainbowkitty.polytrains.display;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Predicate;

import eu.pb4.polymer.virtualentity.api.data.InteractionEntityData;
import eu.pb4.polymer.virtualentity.api.elements.InteractionElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.kalic0re.common.vehicle.CargoDisplayHolder;

/**
 * An interaction box riding a cart, which a client clicks in the cart's place.
 *
 * <p>A client answers a click on a cart itself before the server hears of it, and some carts do
 * something there no server can take back: a command block cart opens its editor, and a furnace
 * cart crackles under the client mod Sounds, which plays it from {@code MinecartFurnace#interact}.
 * A click on this box never reaches that code; it is the server's alone, and the box's handler
 * decides what it does — the cart's own answer included, through {@link #passedTo}.
 *
 * <p>The box is shown to each watching player only while {@code shownTo} holds for them. The rest
 * are told, each alone, that it has no size, which no click can meet, so theirs land on the cart
 * as if the box were not there. Its foot is the cart's passenger point, the lowest a rider stands,
 * so the cart's lowest {@link #FOOT} is the cart's own whoever is looking.
 */
public final class ClickBox {

    /** The box's foot above the cart's position, which a click on the box is measured from. */
    public static final float FOOT = CargoDisplayHolder.PASSENGER_ATTACHMENT_Y;

    // A hair over a cart's 0.98, so a click on the cart above the box's foot meets the box first.
    private static final float WIDTH = 1.0f;

    private final InteractionElement element;
    private final float height;
    private final Predicate<ServerPlayer> shownTo;

    // The watchers told the box has no size.
    private final Set<ServerGamePacketListenerImpl> hiddenFrom = new HashSet<>();

    /**
     * Puts a box on the cart {@code holder} rides.
     *
     * @param top how high above the cart's position the box reaches
     * @param shownTo whether a player is to click the box rather than the cart, asked each tick
     * @param handler what a click on the box does, with its position measured from the box's foot
     */
    public ClickBox(CargoDisplayHolder holder, float top, Predicate<ServerPlayer> shownTo,
            VirtualElement.InteractionHandler handler) {
        this.height = top - FOOT;
        this.shownTo = shownTo;
        this.element = holder.addCargoElement(new InteractionElement(handler));

        element.setSize(WIDTH, height);
        // Responding, so the clicking client swings its own arm, as it does on the cart itself.
        element.setResponse(true);
    }

    /**
     * What clicking {@code cart} itself does, for a click that landed on its box: the click passed
     * on as if made there, through vanilla's own handling and so every callback a click on the cart
     * would meet, with its position moved to be measured from the cart's.
     */
    public static VirtualElement.InteractionHandler passedTo(Entity cart) {
        VirtualElement.InteractionHandler redirect =
                VirtualElement.InteractionHandler.redirect(cart);

        return new VirtualElement.InteractionHandler() {
            @Override
            public void interact(ServerPlayer player, InteractionHand hand, Vec3 pos,
                    boolean usingSecondaryAction) {
                redirect.interact(player, hand, pos.add(0.0, FOOT, 0.0), usingSecondaryAction);
            }

            @Override
            public void attack(ServerPlayer player) {
                redirect.attack(player);
            }

            @Override
            public void pickItem(ServerPlayer player, boolean includeData) {
                redirect.pickItem(player, includeData);
            }
        };
    }

    /**
     * Tells each watcher whose answer to {@code shownTo} has changed since it was last told. For
     * the holder's tick. The box's own data never changes, so nothing resends its true size over
     * what a watcher has been told.
     */
    public void tick(Collection<ServerGamePacketListenerImpl> watchers) {
        for (ServerGamePacketListenerImpl watcher : watchers) {
            boolean hidden = !shownTo.test(watcher.player);

            if (hidden ? hiddenFrom.add(watcher) : hiddenFrom.remove(watcher)) {
                watcher.send(size(hidden));
            }
        }
    }

    /**
     * For the holder's {@code startWatchingExtraPackets}: a new watcher not to be shown the box is
     * sent it at its true size with everything else, and then at none.
     */
    public void startWatching(ServerGamePacketListenerImpl watcher,
            Consumer<Packet<ClientGamePacketListener>> packets) {
        if (shownTo.test(watcher.player)) {
            hiddenFrom.remove(watcher);
        } else {
            hiddenFrom.add(watcher);
            packets.accept(size(true));
        }
    }

    /** For the holder's {@code stopWatching}. */
    public void stopWatching(ServerGamePacketListenerImpl watcher) {
        hiddenFrom.remove(watcher);
    }

    // The box's size as one player is told it: none, or its own.
    private Packet<ClientGamePacketListener> size(boolean hidden) {
        return new ClientboundSetEntityDataPacket(element.getEntityId(), List.of(
                SynchedEntityData.DataValue.create(
                        InteractionEntityData.WIDTH, hidden ? 0.0f : WIDTH),
                SynchedEntityData.DataValue.create(
                        InteractionEntityData.HEIGHT, hidden ? 0.0f : height)));
    }
}
