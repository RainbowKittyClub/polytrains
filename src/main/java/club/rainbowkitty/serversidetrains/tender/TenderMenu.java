package club.rainbowkitty.serversidetrains.tender;

import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.HopperMenu;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * A tender's menu: vanilla's hopper menu, slot for slot, with slots that take only what the tender
 * does.
 *
 * <p>A copy rather than a subclass, because {@code HopperMenu} builds plain slots in its
 * constructor and a plain slot takes anything. A vanilla client draws this as a hopper — it is
 * sent the hopper's menu type and lays the slots out itself — so the refusal happens here, on the
 * server, and the client is corrected when it tries.
 */
public class TenderMenu extends AbstractContainerMenu {

    private final Container tender;

    /**
     * @param containerId the id the client knows this menu by
     * @param inventory the opening player's inventory
     * @param tender the tender's container
     */
    public TenderMenu(int containerId, Inventory inventory, Container tender) {
        super(MenuType.HOPPER, containerId);
        this.tender = tender;
        checkContainerSize(tender, HopperMenu.CONTAINER_SIZE);
        tender.startOpen(inventory.player);

        for (int x = 0; x < HopperMenu.CONTAINER_SIZE; x++) {
            addSlot(new FuelSlot(tender, x, 44 + x * 18, 20));
        }

        addStandardInventorySlots(inventory, 8, 51);
    }

    @Override
    public boolean stillValid(Player player) {
        return tender.stillValid(player);
    }

    // HopperMenu's, unchanged: moveItemStackTo already asks each slot's mayPlace, so a shift-click
    // of anything but coal or charcoal finds no room in the tender.
    @Override
    public ItemStack quickMoveStack(Player player, int slotIndex) {
        ItemStack clicked = ItemStack.EMPTY;
        Slot slot = slots.get(slotIndex);

        if (slot != null && slot.hasItem()) {
            ItemStack stack = slot.getItem();
            clicked = stack.copy();

            if (slotIndex < tender.getContainerSize()) {
                if (!moveItemStackTo(stack, tender.getContainerSize(), slots.size(), true)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, 0, tender.getContainerSize(), false)) {
                return ItemStack.EMPTY;
            }

            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }

        return clicked;
    }

    @Override
    public void removed(Player player) {
        super.removed(player);
        tender.stopOpen(player);
    }

    // A slot that takes only what its container would.
    private static final class FuelSlot extends Slot {

        FuelSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return container.canPlaceItem(getContainerSlot(), stack);
        }
    }
}
