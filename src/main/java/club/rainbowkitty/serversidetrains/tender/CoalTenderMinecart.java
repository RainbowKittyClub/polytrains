package club.rainbowkitty.serversidetrains.tender;

import org.jspecify.annotations.Nullable;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.kalic0re.common.vehicle.CargoDisplayHolder;
import club.rainbowkitty.kalic0re.common.vehicle.CartCargo;
import club.rainbowkitty.kalic0re.common.vehicle.CartCargoSupport;

/**
 * A coal tender: a minecart that carries coal and charcoal for the furnace cart coupled to it.
 *
 * <p>A container cart of its own rather than a chest cart, because it holds a hopper's worth and
 * nothing but what a furnace cart burns by itself. Every client is told it is a plain minecart (see
 * {@link CoalTenders#TYPE}), so it may carry no synched data of its own: a vanilla client's tracker
 * is sized for the vanilla class, and an extra field would index past it and disconnect the client.
 */
public class CoalTenderMinecart extends AbstractMinecartContainer implements CartCargo {

    /** Slots a tender holds: a hopper's worth. */
    public static final int SLOTS = 5;

    private final CartCargoSupport support;

    /**
     * @param type the tender's entity type
     * @param level the level the tender is being created in
     */
    public CoalTenderMinecart(EntityType<? extends CoalTenderMinecart> type, Level level) {
        super(type, level);
        this.support = CartCargoSupport.of(this);
    }

    /** Whether a tender takes {@code stack}: coal and charcoal, all a cart burns unaided. */
    public static boolean holds(ItemStack stack) {
        return stack.is(ItemTags.FURNACE_MINECART_FUEL);
    }

    @Override
    public int getContainerSize() {
        return SLOTS;
    }

    // What a hopper asks before it pushes into the tender, and what the menu's slots ask too.
    @Override
    public boolean canPlaceItem(int slot, ItemStack stack) {
        return holds(stack);
    }

    @Override
    protected AbstractContainerMenu createMenu(int containerId, Inventory inventory) {
        return new TenderMenu(containerId, inventory, this);
    }

    /**
     * Answers only the main hand, as kalic0re's cargo carts do and for the same reason: a client
     * watching what it takes for a plain minecart with riders — the load is one — sends every click
     * twice, and the second, with the off hand, would open the tender again.
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand, Vec3 location) {
        return hand == InteractionHand.MAIN_HAND
                ? super.interact(player, hand, location)
                : InteractionResult.PASS;
    }

    @Override
    protected Item getDropItem() {
        return CoalTenders.ITEM;
    }

    @Override
    public ItemStack getPickResult() {
        return new ItemStack(CoalTenders.ITEM);
    }

    @Override
    public BlockState cargoState() {
        return Blocks.COAL_BLOCK.defaultBlockState();
    }

    // Null for now, so vanilla draws the coal block in the cart at its own size.
    @Override
    public @Nullable CargoDisplayHolder createCargoDisplay() {
        return null;
    }

    @Override
    public void tick() {
        super.tick();
        support.tick();
    }

    @Override
    public void remove(RemovalReason reason) {
        support.destroy();
        super.remove(reason);
    }
}
