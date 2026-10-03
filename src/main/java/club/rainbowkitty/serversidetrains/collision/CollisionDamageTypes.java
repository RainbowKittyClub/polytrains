package club.rainbowkitty.serversidetrains.collision;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.damagesource.DamageType;

import club.rainbowkitty.serversidetrains.ServersideTrains;

/** Damage type keys this mod defines. */
public final class CollisionDamageTypes {

    /** Damage a moving cart deals to anything it runs into. */
    public static final ResourceKey<DamageType> MINECART_DAMAGE =
            ResourceKey.create(Registries.DAMAGE_TYPE, ServersideTrains.id("minecart"));

    // Static-only; blocks instantiation.
    private CollisionDamageTypes() {}
}
