package club.rainbowkitty.polytrains.mixin;

import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.damagesource.DamageType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;

import club.rainbowkitty.polytrains.collision.CollisionDamageTypes;
import club.rainbowkitty.polytrains.util.ModText;

/** Death message for a cart kill: split on whether it was a train and whether it had a rider. */
@Mixin(DamageSource.class)
public abstract class DamageSourceMixin {

    /** {@return whether this source is of the given damage type} */
    @Shadow
    public abstract boolean is(ResourceKey<DamageType> typeKey);

    /** {@return the entity responsible for the damage, or null} */
    @Shadow
    public abstract @Nullable Entity getEntity();

    /** {@return the entity that directly dealt the damage, or null} */
    @Shadow
    public abstract @Nullable Entity getDirectEntity();

    // CartImpact passes the cart as the direct entity and its rider, if any, as the causing entity.
    @Inject(method = "getLocalizedDeathMessage", at = @At("HEAD"), cancellable = true)
    private void polytrains$cartDeathMessage(
            LivingEntity victim, CallbackInfoReturnable<Component> cir) {
        if (!is(CollisionDamageTypes.MINECART_DAMAGE)
                || !(getDirectEntity() instanceof AbstractMinecart cart)) {
            return;
        }

        String key = "death.attack.polytrains." + (cart.hasLink() ? "train" : "minecart");
        Entity rider = getEntity();

        cir.setReturnValue(rider != null
                ? ModText.translatable(
                        key + ".player", victim.getDisplayName(), rider.getDisplayName())
                : ModText.translatable(key, victim.getDisplayName()));
    }
}
