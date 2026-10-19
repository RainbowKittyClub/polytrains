package club.rainbowkitty.polytrains.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.MinecartItem;

import club.rainbowkitty.polytrains.ext.MinecartItemExt;

/** Access to the entity type a cart item places. */
@Mixin(MinecartItem.class)
public abstract class MinecartItemMixin implements MinecartItemExt {

    @Accessor("type")
    @Override
    public abstract EntityType<? extends AbstractMinecart> polytrains$type();
}
