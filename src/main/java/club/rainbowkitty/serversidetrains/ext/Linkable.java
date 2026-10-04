package club.rainbowkitty.serversidetrains.ext;

import java.util.List;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

import club.rainbowkitty.serversidetrains.init.EntityAttachments;

/**
 * Interface injected onto {@link AbstractMinecart} to link carts together. Links are symmetric:
 * each cart holds up to two unordered neighbour slots. Use the static {@link #link} and
 * {@link #unlink} methods to modify the graph; never write neighbour slots directly.
 */
public interface Linkable {

    /**
     * Returns the carts this one is linked to, resolved fresh: zero, one or two of them. A slot
     * whose cart no longer exists resolves to nothing, so this can be shorter than the number of
     * slots in use — ask {@link #hasFreeSlot} rather than this when deciding whether a link fits.
     */
    public default List<AbstractMinecart> getNeighbours() {
        throw Unmixed.error();
    }

    /**
     * Returns whether either slot is in use, counting slots rather than resolved carts. Answers
     * without resolving a cart or building a list, so it is the guard to put in front of a walk
     * over the train.
     */
    public default boolean hasLink() {
        throw Unmixed.error();
    }

    /** Returns whether this cart has a slot free, counting slots rather than resolved carts. */
    public default boolean hasFreeSlot() {
        throw Unmixed.error();
    }

    /**
     * Places {@code neighbour} in the first free slot, doing nothing if both are full or it is
     * already held. Use {@link #link} to keep both sides in step.
     */
    public default void addNeighbour(AbstractMinecart neighbour) {
        throw Unmixed.error();
    }

    /**
     * Clears whichever slot holds {@code neighbour}. Use {@link #unlink} to keep both sides in
     * step.
     */
    public default void removeNeighbour(AbstractMinecart neighbour) {
        throw Unmixed.error();
    }

    /**
     * Whether {@code a} holds a link slot naming {@code b}, asked of the slots rather than of the
     * carts they resolve to.
     *
     * <p>Two carts are coupled whether or not either can currently see the other. A neighbour in an
     * unloaded chunk drops out of {@link #getNeighbours} and out of the train walk built on it, so
     * a train straddling a chunk border reads as two shorter trains — and anything that asks "are
     * these one vehicle" by comparing trains will say no about two carts that are chained together.
     */
    public static boolean coupled(AbstractMinecart a, AbstractMinecart b) {
        return EntityAttachments.getLink(a).holds(b.getUUID());
    }

    /**
     * Symmetrically links {@code a} and {@code b}, doing nothing unless both have a slot free.
     * Checked before either write so a link can never end up recorded by one cart alone, which
     * leaves the two disagreeing about the shape of the train.
     */
    public static void link(AbstractMinecart a, AbstractMinecart b) {
        if (a != b && a.hasFreeSlot() && b.hasFreeSlot()) {
            a.addNeighbour(b);
            b.addNeighbour(a);
        }
    }

    /**
     * Symmetrically removes any direct link between {@code a} and {@code b}.
     * Does nothing if they are not directly linked.
     */
    public static void unlink(AbstractMinecart a, AbstractMinecart b) {
        a.removeNeighbour(b);
        b.removeNeighbour(a);
    }
}
