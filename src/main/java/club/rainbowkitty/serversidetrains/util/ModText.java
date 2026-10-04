package club.rainbowkitty.serversidetrains.util;

import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import club.rainbowkitty.serversidetrains.ServersideTrains;

/**
 * Text a player is shown, carrying its English along for a client without this mod's resource
 * pack, which has none of its lang file either and would otherwise show the bare key.
 *
 * <p>The English is the server's own: Fabric API loads every mod's {@code en_us.json} into the
 * server's language, so the lang file stays the one place the text is written.
 */
public final class ModText {

    // Static-only; blocks instantiation.
    private ModText() {}

    /** {@code key}, in the client's own language if it has the key and in English if not. */
    public static MutableComponent translatable(String key, Object... args) {
        return Component.translatableWithFallback(
                key, Language.getInstance().getOrDefault(key, null), args);
    }

    /**
     * Gives every item of this mod a name that falls back to English.
     *
     * <p>Called once the items are all registered. An item's name is its {@code ITEM_NAME}
     * component, which {@code Item.Properties} sets to the plain translatable name after every
     * component it holds, and an item's initializers run in the order they were added — so the
     * name has to come from one added after the item's own, which every item has by now.
     */
    public static void nameItems() {
        BuiltInRegistries.ITEM.listElements()
                .filter(item -> item.key().identifier().getNamespace()
                        .equals(ServersideTrains.MOD_ID))
                .forEach(item -> {
                    Component name = translatable(item.value().getDescriptionId());

                    BuiltInRegistries.DATA_COMPONENT_INITIALIZERS.add(item.key(),
                            (components, context, key) ->
                                    components.set(DataComponents.ITEM_NAME, name));
                });
    }
}
