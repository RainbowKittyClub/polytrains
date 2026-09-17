package club.rainbowkitty.minecarttweaks.item;

import eu.pb4.polymer.core.api.item.PolymerItem;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.jspecify.annotations.Nullable;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import club.rainbowkitty.minecarttweaks.MinecartTweaks;
import club.rainbowkitty.minecarttweaks.block.SwitchedRailBlock;

/** A tool that throws a right-clicked switched rail to its other branch while it is idle. */
public class WrenchItem extends Item implements PolymerItem {

    // Shown when the switch will not throw because a cart is standing on it.
    private static final Component OCCUPIED =
            Component.translatable(MinecartTweaks.MOD_ID + ".switch.occupied")
                    .withStyle(ChatFormatting.RED);

    /** Constructs a wrench with the given item properties. */
    public WrenchItem(Properties properties) {
        super(properties);
    }

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
            player.swing(context.getHand(), true);
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
            player.sendOverlayMessage(OCCUPIED);
        }
    }
}
