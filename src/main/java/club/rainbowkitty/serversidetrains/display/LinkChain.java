package club.rainbowkitty.serversidetrains.display;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import org.jspecify.annotations.Nullable;

import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

import club.rainbowkitty.kalic0re.common.vehicle.CargoDisplayHolder;

/**
 * The chain a linked cart shows back to the cart it follows, drawn as one block display so that a
 * vanilla client needs nothing installed. Owns the display's virtual-entity identity and lifecycle;
 * where it sits between the two carts is {@link LinkVisual}'s to say.
 */
public final class LinkChain extends ElementHolder {

    // The cart this chain follows, so sync() can tell when the pairing itself has changed.
    private final AbstractMinecart parent;

    // This chain's geometry and pose; delegated to every tick.
    private final LinkVisual visual;

    // Builds the display, rides it on the cart, and poses it before it is ever sent.
    private LinkChain(AbstractMinecart cart, AbstractMinecart parent) {
        this.parent = parent;
        this.visual = new LinkVisual(cart, parent);

        // Rides the cart rather than being placed at it, so the client draws it against the
        // position it is already showing rather than against server truth.
        addPassengerElement(visual.chain());

        // A display is spawned with no transform, which for this model is stood on end. Posing it
        // before it is ever sent means no client sees that frame.
        visual.tick();
    }

    /**
     * Brings the chain on {@code cart} into line with the cart it currently follows, creating,
     * retargeting or removing it as needed, and returns the chain to hold onto.
     */
    public static @Nullable LinkChain sync(
            @Nullable LinkChain current, AbstractMinecart cart,
            @Nullable AbstractMinecart parent) {
        boolean usable = parent != null && parent.level() == cart.level();

        if (current != null && (!usable || current.parent != parent)) {
            current.destroy();
            current = null;
        }

        if (!usable) {
            return null;
        }

        if (current == null) {
            current = new LinkChain(cart, parent);
            EntityAttachment.ofTicking(current, cart);

            // Riders are announced to a client when it starts tracking the cart, so a chain built
            // while the cart is already tracked has to announce itself or it never mounts.
            current.sendPacket(new ClientboundSetPassengersPacket(cart));
        }

        return current;
    }

    // Runs from the entity tracker rather than the cart's own tick, so a train nobody is near costs
    // nothing, and a stationary one resolves to the same transform and sends no packet.
    @Override
    protected void onTick() {
        CargoDisplayHolder.keepRidersCurrent(this);
        visual.tick();
    }
}
