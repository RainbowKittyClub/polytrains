package club.rainbowkitty.serversidetrains.block;

import java.util.function.Function;

import eu.pb4.polymer.core.api.item.PolymerBlockItem;
import net.fabricmc.fabric.api.creativetab.v1.FabricCreativeModeTabOutput;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockBehaviour;

import club.rainbowkitty.serversidetrains.ServersideTrains;

/** Block and block-item registrations. */
public final class RailBlocks {

    /** The junction rail block instance. */
    public static final JunctionRailBlock JUNCTION_RAIL =
            rail("junction_rail", JunctionRailBlock::new);

    /** The switched rail block instance. */
    public static final SwitchedRailBlock SWITCHED_RAIL =
            rail("switch_rail", SwitchedRailBlock::new);

    /** The junction rail block item. */
    public static final BlockItem JUNCTION_RAIL_ITEM = railItem("junction_rail", JUNCTION_RAIL);

    /** The switched rail block item. */
    public static final BlockItem SWITCHED_RAIL_ITEM = railItem("switch_rail", SWITCHED_RAIL);

    // Static-only; blocks instantiation.
    private RailBlocks() {}

    /** Registers all blocks and their corresponding items into the vanilla registries. */
    public static void init() {
        register("junction_rail", JUNCTION_RAIL, JUNCTION_RAIL_ITEM);
        register("switch_rail", SWITCHED_RAIL, SWITCHED_RAIL_ITEM);
    }

    /** Adds this mod's items to the given creative-tab entry builder. */
    public static void addCreativeTabEntries(FabricCreativeModeTabOutput output) {
        output.insertAfter(Items.ACTIVATOR_RAIL, JUNCTION_RAIL_ITEM, SWITCHED_RAIL_ITEM);
    }

    // A rail block carrying vanilla rail settings, built under the key it will be registered by so
    // that the two can never be given different names.
    private static <T extends Block> T rail(
            String name, Function<BlockBehaviour.Properties, T> factory) {
        return factory.apply(BlockBehaviour.Properties.ofFullCopy(Blocks.RAIL)
                .setId(blockKey(name)));
    }

    // Clients without the server's resource pack fall back to a plain rail rather than a missing
    // model, since the custom models only exist in that pack.
    private static BlockItem railItem(String name, Block block) {
        return new PolymerBlockItem(
                block,
                new Item.Properties()
                        .useBlockDescriptionPrefix()
                        .setId(ServersideTrains.itemKey(name)),
                Items.RAIL,
                true);
    }

    // Registers a block and its item together, so the two can never be given differing names.
    private static void register(String name, Block block, BlockItem item) {
        Registry.register(BuiltInRegistries.BLOCK, blockKey(name), block);
        Registry.register(BuiltInRegistries.ITEM, ServersideTrains.itemKey(name), item);
    }

    // This mod's key for a block of that name.
    private static ResourceKey<Block> blockKey(String name) {
        return ResourceKey.create(Registries.BLOCK, ServersideTrains.id(name));
    }
}
