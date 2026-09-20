package club.rainbowkitty.minecarttweaks.link;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Item;

import club.rainbowkitty.minecarttweaks.MinecartTweaks;

/** Tag keys this mod defines. */
public final class LinkTags {

    // Static-only; blocks instantiation.
    private LinkTags() {}

    /** Item tag keys. */
    public static final class Items {

        /** Items a link is made out of, spent from the pack when the wrench makes one. */
        public static final TagKey<Item> LINK_ITEMS =
                TagKey.create(Registries.ITEM, MinecartTweaks.id("link_items"));

        // Static-only; blocks instantiation.
        private Items() {}
    }
}
