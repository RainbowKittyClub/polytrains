package club.rainbowkitty.polytrains;

import eu.pb4.polymer.core.api.item.PolymerCreativeModeTabUtils;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.ItemStack;

import club.rainbowkitty.polytrains.block.RailBlocks;
import club.rainbowkitty.polytrains.item.WrenchItems;
import club.rainbowkitty.polytrains.tender.CoalTenders;
import club.rainbowkitty.polytrains.util.ModText;

/**
 * This mod's own creative tab, shown with the coal tender. Polymer lists it in
 * {@code /polymer creative} and syncs it to clients running Polymer.
 */
public final class TrainsTab {

    // Static-only; blocks instantiation.
    private TrainsTab() {}

    /** Registers the tab, once every item it lists is registered. */
    public static void register() {
        CreativeModeTab tab = PolymerCreativeModeTabUtils.builder()
                .title(ModText.translatable("itemGroup." + Polytrains.MOD_ID + ".trains"))
                .icon(() -> new ItemStack(CoalTenders.ITEM))
                .displayItems((params, output) -> {
                    output.accept(RailBlocks.JUNCTION_RAIL_ITEM);
                    output.accept(RailBlocks.SWITCHED_RAIL_ITEM);
                    output.accept(WrenchItems.WRENCH);
                    output.accept(CoalTenders.ITEM);
                })
                .build();
        PolymerCreativeModeTabUtils.registerPolymerCreativeModeTab(Polytrains.id("trains"), tab);
    }
}
