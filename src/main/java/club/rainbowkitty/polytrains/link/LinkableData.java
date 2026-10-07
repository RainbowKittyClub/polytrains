package club.rainbowkitty.polytrains.link;

import java.util.Optional;
import java.util.UUID;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.UUIDUtil;

/**
 * A cart's link state: two unordered neighbour slots, each holding a linked cart's UUID.
 *
 * @param slotA one neighbour slot
 * @param slotB the other neighbour slot
 */
public record LinkableData(Optional<UUID> slotA, Optional<UUID> slotB) {

    /** The link state with neither slot occupied. */
    public static final LinkableData EMPTY = new LinkableData(Optional.empty(), Optional.empty());

    /** Persistence codec for this record. */
    public static final Codec<LinkableData> CODEC = RecordCodecBuilder.create(instance ->
            instance.group(
                    UUIDUtil.CODEC.optionalFieldOf("slotA").forGetter(LinkableData::slotA),
                    UUIDUtil.CODEC.optionalFieldOf("slotB").forGetter(LinkableData::slotB)
            ).apply(instance, LinkableData::new));

    /** Returns whether either slot is in use. */
    public boolean hasLink() {
        return slotA.isPresent() || slotB.isPresent();
    }

    /** Returns whether either slot is free. */
    public boolean hasFreeSlot() {
        return slotA.isEmpty() || slotB.isEmpty();
    }

    /**
     * Returns whether either slot names {@code id}. Asked of the slot rather than of the cart it
     * resolves to, so it still answers for a neighbour that is not currently loaded.
     */
    public boolean holds(UUID id) {
        return slotA.filter(id::equals).isPresent() || slotB.filter(id::equals).isPresent();
    }

    /**
     * Returns a copy holding the given UUID, unchanged if it is already held or both slots are
     * occupied. Idempotent so a slot can never end up holding the same cart twice.
     */
    public LinkableData withNeighbour(UUID id) {
        if (holds(id)) {
            return this;
        }
        if (slotA.isEmpty()) {
            return new LinkableData(Optional.of(id), slotB);
        }
        if (slotB.isEmpty()) {
            return new LinkableData(slotA, Optional.of(id));
        }
        return this;
    }

    /** Returns a copy with the given UUID cleared from both slots. */
    public LinkableData withoutNeighbour(UUID id) {
        return new LinkableData(
                slotA.filter(held -> !held.equals(id)),
                slotB.filter(held -> !held.equals(id)));
    }
}
