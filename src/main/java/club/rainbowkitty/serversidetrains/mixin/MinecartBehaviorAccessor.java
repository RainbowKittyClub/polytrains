package club.rainbowkitty.serversidetrains.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartBehavior;

/**
 * Reaches the cart a behavior drives. It is protected and has no getter, and {@code @Shadow}
 * resolves fields only against the target class itself, so a subclass mixin cannot see it.
 */
@Mixin(MinecartBehavior.class)
public interface MinecartBehaviorAccessor {

    /** The cart this behavior moves. */
    @Accessor("minecart")
    public AbstractMinecart serversidetrains$cart();
}
