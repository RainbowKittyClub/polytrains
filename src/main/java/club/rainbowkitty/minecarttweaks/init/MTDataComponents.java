package club.rainbowkitty.minecarttweaks.init;

import eu.pb4.polymer.core.api.other.PolymerComponent;

import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;

import club.rainbowkitty.minecarttweaks.MinecartTweaks;

/** Data-component type registrations. */
public final class MTDataComponents {

    /**
     * Where a link selection used to be stored, before it moved onto the player. Registered but
     * never written: an unknown component id is a decode error for the whole stack, so a chain
     * saved by an earlier build would fail to load without this. Safe to delete a release on.
     */
    public static final DataComponentType<SelectedCart> SELECTED_CART =
            DataComponentType.<SelectedCart>builder()
                    .persistent(SelectedCart.CODEC)
                    .networkSynchronized(SelectedCart.STREAM_CODEC)
                    .build();

    // Static-only; blocks instantiation.
    private MTDataComponents() {}

    /** Registers all data component types into the vanilla registry. */
    public static void init() {
        Registry.register(
                BuiltInRegistries.DATA_COMPONENT_TYPE,
                MinecartTweaks.id("selected_cart"),
                SELECTED_CART);

        // Marks the type server-only so registry sync ignores it and the value is stripped from
        // client-bound stacks; only the server ever reads it.
        PolymerComponent.registerDataComponent(SELECTED_CART);
    }
}
