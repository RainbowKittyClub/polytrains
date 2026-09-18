package club.rainbowkitty.minecarttweaks;

import com.mojang.logging.LogUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import org.slf4j.Logger;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.level.Level;

import club.rainbowkitty.minecarttweaks.init.MTAttachments;
import club.rainbowkitty.minecarttweaks.init.MTBlocks;
import club.rainbowkitty.minecarttweaks.init.MTDataComponents;
import club.rainbowkitty.minecarttweaks.init.MTItems;
import club.rainbowkitty.minecarttweaks.init.MTTags;
import club.rainbowkitty.minecarttweaks.init.MTTicketTypes;
import club.rainbowkitty.minecarttweaks.rule.MTGameRules;
import club.rainbowkitty.minecarttweaks.util.MinecartHelper;

/** Mod entrypoint — wires events and registers content. */
public class MinecartTweaks implements ModInitializer {
    /** The mod's namespace, used for all Identifier construction. */
    public static final String MOD_ID = "minecarttweaks";

    private static final Logger LOGGER = LogUtils.getLogger();

    // CreativeModeTabs keeps its tab keys private, so the vanilla keys are rebuilt here. Vanilla
    // lists all four rails in both of these tabs, so the junction rail joins both to match.
    private static final ResourceKey<CreativeModeTab> REDSTONE_BLOCKS_TAB = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("redstone_blocks"));

    private static final ResourceKey<CreativeModeTab> TOOLS_AND_UTILITIES_TAB = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));

    @Override
    public void onInitialize() {
        MTBlocks.init();
        MTItems.init();
        MTDataComponents.init();
        MTGameRules.init();
        MTAttachments.init();
        MTTicketTypes.init();

        // The junction rail's model and texture only reach clients through this pack.
        PolymerResourcePackUtils.addModAssets(MOD_ID);
        PolymerResourcePackUtils.markAsRequired();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            boolean supported = server.getWorldData().enabledFeatures()
                    .contains(FeatureFlags.MINECART_IMPROVEMENTS);
            MTGameRules.setTrainsSupported(supported);

            if (!supported) {
                LOGGER.warn("This world does not have the minecart_improvements pack enabled, which"
                        + " linked trains are built on. Trains are off and {}:trains_enabled is"
                        + " unavailable; enable the pack at world creation to use them.", MOD_ID);
            }
        });

        CreativeModeTabEvents.modifyOutputEvent(REDSTONE_BLOCKS_TAB)
                .register(MTBlocks::addCreativeTabEntries);
        CreativeModeTabEvents.modifyOutputEvent(TOOLS_AND_UTILITIES_TAB)
                .register(MTBlocks::addCreativeTabEntries);
        CreativeModeTabEvents.modifyOutputEvent(TOOLS_AND_UTILITIES_TAB)
                .register(MTItems::addCreativeTabEntries);

        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
                entity instanceof AbstractMinecart minecart
                        ? useMinecart(player, level, hand, minecart)
                        : InteractionResult.PASS);
    }

    /** Constructs a namespaced {@link Identifier} under this mod's namespace. */
    public static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(MOD_ID, name);
    }

    // Shift-clicking a cart with a link item couples it to the last one clicked; with anything
    // else, tries to craft the cart into whatever that item upgrades it to.
    private static InteractionResult useMinecart(
            Player player, Level level, InteractionHand hand, AbstractMinecart minecart) {
        var heldItem = player.getItemInHand(hand);

        // A click the client cannot consume is offered to the off hand as well, and a link item in
        // the main hand has already answered it: a second link item would attempt the pair again,
        // against a selection the first has spent, and anything else would upgrade the cart that
        // was being coupled.
        if (hand == InteractionHand.OFF_HAND
                && player.getMainHandItem().is(MTTags.Items.LINK_ITEMS)) {
            return InteractionResult.PASS;
        }

        if (MinecartHelper.mayAttemptLinking(player, heldItem)) {
            if (!(level instanceof ServerLevel serverLevel)) {
                return InteractionResult.SUCCESS;
            }

            if (!MTGameRules.trainsEnabled(serverLevel)) {
                player.sendOverlayMessage(MinecartHelper.Refusal.DISABLED.message());
                return InteractionResult.FAIL;
            }

            MinecartHelper.tryLinkMinecart(player, serverLevel, minecart, heldItem);
            return InteractionResult.SUCCESS;
        }

        if (player.isShiftKeyDown()
                && player instanceof ServerPlayer serverPlayer
                && level instanceof ServerLevel serverLevel
                && MinecartHelper.tryUpgradeMinecart(
                        serverPlayer, serverLevel, hand, minecart, heldItem)) {
            player.swing(hand, true);
            return InteractionResult.SUCCESS;
        }

        return InteractionResult.PASS;
    }
}
