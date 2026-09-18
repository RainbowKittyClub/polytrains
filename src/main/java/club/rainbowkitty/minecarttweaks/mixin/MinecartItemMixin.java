package club.rainbowkitty.minecarttweaks.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.MinecartItem;

import club.rainbowkitty.minecarttweaks.ext.MinecartItemExt;

/** Larger cart stacks, plus access to the entity type a cart item places. */
@Mixin(MinecartItem.class)
public abstract class MinecartItemMixin implements MinecartItemExt {

    // Vanilla carts stack one to a slot, which makes laying out a train a trip per car.
    @Unique
    private static final int STACK_SIZE = 16;

    // Raises every cart item's stack size as it is constructed.
    @ModifyArg(method = "<init>", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/item/Item;<init>"
                    + "(Lnet/minecraft/world/item/Item$Properties;)V"))
    private static Item.Properties minecarttweaks$increaseStackSize(Item.Properties properties) {
        return properties.stacksTo(STACK_SIZE);
    }

    @Accessor("type")
    @Override
    public abstract EntityType<? extends AbstractMinecart> minecarttweaks$type();
}
