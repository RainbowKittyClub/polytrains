package club.rainbowkitty.serversidetrains.tender;

import org.jspecify.annotations.Nullable;

import net.minecraft.tags.ItemTags;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecartContainer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.kalic0re.common.vehicle.CargoDisplayHolder;
import club.rainbowkitty.kalic0re.common.vehicle.CartCargo;
import club.rainbowkitty.kalic0re.common.vehicle.CartCargoSupport;
import club.rainbowkitty.serversidetrains.rule.ModGameRules;
import club.rainbowkitty.serversidetrains.util.FurnaceFuel;

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

    // Saved-data key for the burn left in the coal the tender has opened. Capitalised to match the
    // vanilla keys it sits beside.
    private static final String BURN_LEFT_TAG = "BurnLeft";

    private final CartCargoSupport support;

    // Ticks of burn left in the coal this tender has opened and not yet handed over. An engine is
    // fed by the tick rather than by the item, so a coal is opened whole and spent a tick at a
    // time.
    private int burnLeft;

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

    /**
     * Hands {@code engine} up to {@code wanted} ticks of burn from the tenders coupled straight to
     * it, and returns how many it got: fewer only when they have run dry.
     *
     * <p>Coupled means what it means for every other train behaviour here — a link, trains
     * enabled, and the tender loaded — so a link left behind when trains are switched off, or one
     * to a tender in an unloaded chunk, feeds nothing.
     */
    public static int feed(AbstractMinecart engine, int wanted) {
        if (wanted <= 0 || !engine.hasLink() || !ModGameRules.trainsEnabled(engine.level())) {
            return 0;
        }

        int given = 0;

        for (AbstractMinecart neighbour : engine.getNeighbours()) {
            if (neighbour instanceof CoalTenderMinecart tender) {
                given += tender.draw(wanted - given);

                if (given >= wanted) {
                    break;
                }
            }
        }

        return given;
    }

    /**
     * Gives up to {@code wanted} ticks of burn, opening coal or charcoal one at a time as the last
     * runs out, and returns how many it gave.
     */
    public int draw(int wanted) {
        int given = 0;

        while (given < wanted && (burnLeft > 0 || openNext())) {
            int taken = Math.min(burnLeft, wanted - given);

            burnLeft -= taken;
            given += taken;
        }

        return given;
    }

    // Opens the first coal or charcoal in slot order, or answers false when there is none left.
    private boolean openNext() {
        for (int slot = 0; slot < SLOTS; slot++) {
            if (holds(getItem(slot))) {
                removeItem(slot, 1);
                burnLeft += FurnaceFuel.ticksPerCoal();
                return true;
            }
        }

        return false;
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

    // No key at all is no coal open, which is also what a tender reads back from an older save.
    @Override
    protected void addAdditionalSaveData(ValueOutput output) {
        super.addAdditionalSaveData(output);

        if (burnLeft > 0) {
            output.putInt(BURN_LEFT_TAG, burnLeft);
        }
    }

    @Override
    protected void readAdditionalSaveData(ValueInput input) {
        super.readAdditionalSaveData(input);
        burnLeft = Math.max(0, input.getIntOr(BURN_LEFT_TAG, 0));
    }
}
