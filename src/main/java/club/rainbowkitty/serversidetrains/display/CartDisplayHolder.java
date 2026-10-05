package club.rainbowkitty.serversidetrains.display;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.elements.AbstractElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

import club.rainbowkitty.kalic0re.common.vehicle.CargoDisplayHolder;

/**
 * A kalic0re cargo holder whose riders a client is always sent where the cart is now: see
 * {@link #keepRidersCurrent}.
 */
public abstract class CartDisplayHolder extends CargoDisplayHolder {

    /** @param cart the cart the holder's elements ride */
    protected CartDisplayHolder(AbstractMinecart cart) {
        super(cart);
    }

    /**
     * Keeps where a client is first sent each of {@code holder}'s elements at where its cart is
     * now. For a holder whose every element rides the entity it is attached to, and so ignores
     * position updates: the entity carries it.
     *
     * <p>Polymer sends each new watcher such an element wherever it first sent it — for a cart that
     * has travelled since, possibly in chunks that client has not loaded. A client seats only a
     * rider it ticks, and ticks only an entity spawned in a section it is ticking, so the rider
     * stays where it was put, out of sight, until that far-off chunk happens to load: a tender's
     * coal, or the chain between two cars, gone from a train that comes back into view. Called
     * every tick, so the spawn is never more than a tick behind.
     *
     * <p>The fix belongs in kalic0re's {@code CargoDisplayHolder}, whose every holder has the gap.
     */
    public static void keepRidersCurrent(ElementHolder holder) {
        for (VirtualElement element : holder.getElements()) {
            if (element instanceof AbstractElement rider) {
                rider.updateLastSyncedPos();
            }
        }
    }

    @Override
    protected void onTick() {
        keepRidersCurrent(this);
        super.onTick();
    }
}
