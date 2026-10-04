package club.rainbowkitty.serversidetrains.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;

/**
 * Reaches vanilla's burn time for one item of cart fuel. It is private, and javac folds it into
 * {@code addFuel} as a literal, so a copy of the number here would drift from vanilla silently.
 */
@Mixin(MinecartFurnace.class)
public interface MinecartFurnaceAccessor {

    /** Ticks one item of {@code furnace_minecart_fuel} burns for in a cart. */
    @Accessor("FUEL_TICKS_PER_ITEM")
    static int serversidetrains$fuelTicksPerItem() {
        throw new AssertionError("mixin accessor not applied");
    }
}
