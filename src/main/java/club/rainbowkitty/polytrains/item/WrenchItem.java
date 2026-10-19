package club.rainbowkitty.polytrains.item;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.kalic0re.common.aim.Crosshair;
import club.rainbowkitty.polytrains.Polytrains;
import club.rainbowkitty.polytrains.block.SwitchedRailBlock;
import club.rainbowkitty.polytrains.init.EntityAttachments;
import club.rainbowkitty.polytrains.link.LinkAction;
import club.rainbowkitty.polytrains.link.LinkTargeting;
import club.rainbowkitty.polytrains.link.WrenchMode;
import club.rainbowkitty.polytrains.util.ModText;

/** A tool that throws a right-clicked switched rail to its other branch while it is idle. */
public class WrenchItem extends Item implements PolymerItem {

    // Shown when the switch will not throw because a cart is standing on it.
    private static final Component OCCUPIED_MESSAGE =
            ModText.translatable(Polytrains.MOD_ID + ".switch.occupied")
                    .withStyle(ChatFormatting.RED);

    /** Constructs a wrench with the given item properties. */
    public WrenchItem(Properties properties) {
        super(properties);
    }

    /**
     * Switches which job the wrench is doing, when it is swung at nothing in particular.
     *
     * <p>A click that hit a block or an entity was meant for that, and this leg runs for those too
     * once whatever was hit has declined the click — so open air is what it is asked about.
     */
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        if (!(level instanceof ServerLevel serverLevel)
                || !(player instanceof ServerPlayer viewer)) {
            return InteractionResult.PASS;
        }

        // A click reaches the server once per hand, and a wrench in both would switch twice over,
        // leaving the mode where it started.
        if (EntityAttachments.answeredUseAt(viewer, serverLevel.getGameTime())
                || !Crosshair.onNothing(viewer)) {
            return InteractionResult.PASS;
        }

        EntityAttachments.setAnsweredUseAt(viewer, serverLevel.getGameTime());

        WrenchMode mode = EntityAttachments.getWrenchMode(viewer).next();
        EntityAttachments.setWrenchMode(viewer, mode);

        // A pair half made under one job means nothing under the other.
        EntityAttachments.setLinkSelection(viewer, null);

        LinkTargeting.notice(viewer, LinkAction.modeLabel(mode));

        // Sent straight to the one player: Player.playSound excludes the player it is called on,
        // and the level's own would tell everyone nearby that somebody flipped a tool.
        viewer.connection.send(new ClientboundSoundPacket(
                BuiltInRegistries.SOUND_EVENT.wrapAsHolder(SoundEvents.LEVER_CLICK),
                SoundSource.PLAYERS,
                viewer.getX(), viewer.getY(), viewer.getZ(), 0.6F, 1.2F,
                serverLevel.getRandom().nextLong()));

        return InteractionResult.SUCCESS_SERVER;
    }

    /** Throws the switched rail under the click, or PASSes for anything else. */
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        BlockState state = level.getBlockState(pos);

        if (!(state.getBlock() instanceof SwitchedRailBlock)) {
            return InteractionResult.PASS;
        }
        if (!(level instanceof ServerLevel serverLevel)) {
            return InteractionResult.SUCCESS;
        }

        Player player = context.getPlayer();

        if (!SwitchedRailBlock.toggle(serverLevel, pos, state)) {
            refuse(serverLevel, pos, player);
            return InteractionResult.FAIL;
        }

        if (player != null) {
            //? if >=26.3 {
            /*player.swing(context.getHand(), context.getItemInHand().getInteractAnimation(), true);
            *///?} else {
            player.swing(context.getHand(), true);
            //?}
        }
        return InteractionResult.SUCCESS;
    }

    @Override
    public Item getPolymerItem(ItemStack stack, PacketContext context) {
        return Items.STICK;
    }

    // A refused throw is otherwise indistinguishable from a missed click, so it gets a sound of its
    // own as well as the overlay: the player is usually looking at the cart, not at the rail.
    private static void refuse(ServerLevel level, BlockPos pos, @Nullable Player player) {
        level.playSound(null, pos, SoundEvents.DISPENSER_FAIL, SoundSource.BLOCKS, 0.6F, 1.0F);

        if (player != null) {
            player.sendOverlayMessage(OCCUPIED_MESSAGE);
        }
    }
}
