package club.rainbowkitty.polytrains.ext;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

/** Access interface for {@code MinecartItem.type}, the entity placed when the item is used. */
public interface MinecartItemExt {

    /** Returns the entity type this item places when used on a rail. */
    public default EntityType<? extends AbstractMinecart> polytrains$type() {
        throw Unmixed.error();
    }
}
