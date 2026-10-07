package club.rainbowkitty.polytrains.item;

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTabOutput;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import club.rainbowkitty.polytrains.Polytrains;

/** Non-block item registrations. */
public final class WrenchItems {

    // Registry key the wrench is registered and given its settings under.
    private static final ResourceKey<Item> WRENCH_KEY = Polytrains.itemKey("wrench");

    /** The wrench item. */
    public static final Item WRENCH =
            new WrenchItem(new Item.Properties().stacksTo(1).setId(WRENCH_KEY));

    // Static-only; blocks instantiation.
    private WrenchItems() {}

    /** Registers all non-block items into the vanilla registry. */
    public static void init() {
        Registry.register(BuiltInRegistries.ITEM, WRENCH_KEY, WRENCH);
    }

    /** Adds this mod's non-block items to the given creative-tab entry builder. */
    public static void addCreativeTabEntries(FabricCreativeModeTabOutput output) {
        output.insertAfter(Items.ACTIVATOR_RAIL, WRENCH);
    }

    /** Whether this stack is the wrench. */
    public static boolean isWrench(ItemStack stack) {
        return stack.is(WRENCH);
    }
}
