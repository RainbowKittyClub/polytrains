package club.rainbowkitty.serversidetrains;

import com.mojang.logging.LogUtils;
import eu.pb4.polymer.resourcepack.api.PolymerResourcePackUtils;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import org.slf4j.Logger;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.flag.FeatureFlags;
import net.minecraft.world.item.CreativeModeTab;

import club.rainbowkitty.serversidetrains.block.RailBlocks;
import club.rainbowkitty.serversidetrains.init.EntityAttachments;
import club.rainbowkitty.serversidetrains.init.FurnaceTicketTypes;
import club.rainbowkitty.serversidetrains.item.WrenchItems;
import club.rainbowkitty.serversidetrains.link.LinkTargeting;
import club.rainbowkitty.serversidetrains.rule.ModGameRules;
import club.rainbowkitty.serversidetrains.rule.PhysicsGameRules;
import club.rainbowkitty.serversidetrains.tender.CoalTenders;
import club.rainbowkitty.serversidetrains.train.TrainMeter;
import club.rainbowkitty.serversidetrains.util.MinecartHelper;

/** Mod entrypoint — wires events and registers content. */
public class ServersideTrains implements ModInitializer {
    /** The mod's namespace, used for every Identifier but its game rules'. */
    public static final String MOD_ID = "serversidetrains";

    /** The game rules' namespace, short because players type it in every /gamerule. */
    public static final String RULES_NAMESPACE = "sstrains";

    // This mod's logger.
    private static final Logger LOGGER = LogUtils.getLogger();

    // CreativeModeTabs keeps its tab keys private, so the vanilla keys are rebuilt here. Vanilla
    // lists all four rails in both of these tabs, so the junction rail joins both to match.
    private static final ResourceKey<CreativeModeTab> REDSTONE_BLOCKS_TAB = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("redstone_blocks"));

    private static final ResourceKey<CreativeModeTab> TOOLS_AND_UTILITIES_TAB = ResourceKey.create(
            Registries.CREATIVE_MODE_TAB, Identifier.withDefaultNamespace("tools_and_utilities"));

    @Override
    public void onInitialize() {
        RailBlocks.init();
        WrenchItems.init();
        CoalTenders.init();
        ModGameRules.init();
        PhysicsGameRules.init();
        EntityAttachments.init();
        FurnaceTicketTypes.init();
        LinkTargeting.init();

        // The junction rail's model and texture only reach clients through this pack.
        PolymerResourcePackUtils.addModAssets(MOD_ID);
        PolymerResourcePackUtils.markAsRequired();

        ServerLifecycleEvents.SERVER_STARTED.register(server -> {
            boolean supported = server.getWorldData().enabledFeatures()
                    .contains(FeatureFlags.MINECART_IMPROVEMENTS);
            ModGameRules.setTrainsSupported(supported);

            if (!supported) {
                LOGGER.warn("This world does not have the minecart_improvements pack enabled, which"
                        + " linked trains are built on. Trains are off and {}:trains_enabled is"
                        + " unavailable; enable the pack at world creation to use them.",
                        RULES_NAMESPACE);
            }
        });

        // Sampled after each level's tick, once every car of every train has moved.
        ServerTickEvents.END_LEVEL_TICK.register(TrainMeter::sample);

        CreativeModeTabEvents.modifyOutputEvent(REDSTONE_BLOCKS_TAB)
                .register(RailBlocks::addCreativeTabEntries);
        CreativeModeTabEvents.modifyOutputEvent(TOOLS_AND_UTILITIES_TAB)
                .register(RailBlocks::addCreativeTabEntries);
        CreativeModeTabEvents.modifyOutputEvent(TOOLS_AND_UTILITIES_TAB)
                .register(WrenchItems::addCreativeTabEntries);
        CreativeModeTabEvents.modifyOutputEvent(TOOLS_AND_UTILITIES_TAB)
                .register(CoalTenders::addCreativeTabEntries);

        UseEntityCallback.EVENT.register((player, level, hand, entity, hitResult) ->
                entity instanceof AbstractMinecart minecart
                        ? MinecartHelper.useMinecart(player, level, hand, minecart)
                        : InteractionResult.PASS);
    }

    /** Constructs a namespaced {@link Identifier} under this mod's namespace. */
    public static Identifier id(String name) {
        return Identifier.fromNamespaceAndPath(MOD_ID, name);
    }

    /** Constructs a game rule's {@link Identifier}, under {@link #RULES_NAMESPACE}. */
    public static Identifier ruleId(String name) {
        return Identifier.fromNamespaceAndPath(RULES_NAMESPACE, name);
    }
}
