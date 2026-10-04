package club.rainbowkitty.serversidetrains.tender;

import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTabOutput;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

import club.rainbowkitty.kalic0re.common.vehicle.CargoMinecarts;
import club.rainbowkitty.kalic0re.common.vehicle.PolymerMinecartItem;
import club.rainbowkitty.serversidetrains.ServersideTrains;

/** The coal tender's entity type and item. */
public final class CoalTenders {

    // Registry name the tender's entity type and item share.
    private static final String NAME = "coal_tender";

    // Registry key the tender item is registered and given its settings under.
    private static final ResourceKey<Item> ITEM_KEY =
            ResourceKey.create(Registries.ITEM, ServersideTrains.id(NAME));

    /**
     * The tender's entity type, which every client is told is a plain minecart. Not a chest or
     * hopper cart, though it is a container: a client watching a container cart draws that cart's
     * own block under the load, whatever the cart's display block says.
     */
    public static final EntityType<CoalTenderMinecart> TYPE = CargoMinecarts.register(
            ServersideTrains.MOD_ID, NAME,
            CargoMinecarts.containerCartBuilder(CoalTenderMinecart::new), EntityTypes.MINECART);

    /** The tender item, which places a tender on a rail and is shown as a minecart. */
    public static final Item ITEM = new PolymerMinecartItem(TYPE, Items.MINECART, null,
            new Item.Properties().stacksTo(1).setId(ITEM_KEY));

    // Static-only; blocks instantiation.
    private CoalTenders() {}

    /** Registers the tender item, and with it the entity type, and lets dispensers place it. */
    public static void init() {
        Registry.register(BuiltInRegistries.ITEM, ITEM_KEY, ITEM);
        CargoMinecarts.registerDispenseBehaviour(ITEM, TYPE);
    }

    /** Adds the tender to the given creative-tab entry builder, after the furnace cart. */
    public static void addCreativeTabEntries(FabricCreativeModeTabOutput output) {
        output.insertAfter(Items.FURNACE_MINECART, ITEM);
    }
}
