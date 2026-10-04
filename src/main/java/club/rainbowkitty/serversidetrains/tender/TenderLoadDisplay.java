package club.rainbowkitty.serversidetrains.tender;

import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import club.rainbowkitty.kalic0re.common.display.ItemDisplays;
import club.rainbowkitty.kalic0re.common.vehicle.CargoDisplayHolder;

/**
 * The coal riding in a {@link CoalTenderMinecart}: one block of coal stretched to the cart's
 * inside and standing as high as the tender is full, mounted as the cart's passenger and posed in
 * its frame.
 *
 * <p>Vanilla's own cargo slot cannot draw this. It scales whatever block it holds by a uniform
 * 0.75, and the inside of a cart is 16 pixels long, 12 wide and 8 deep, so a load that fills it is
 * a stretched block, which only a display element can be.
 */
public class TenderLoadDisplay extends CargoDisplayHolder {

    // The inside of a cart, in pixels, from MinecartModel's walls and floor: 16 along the cart, 12
    // across it, with the floor's top 3 above the cart's position and the rim 11 above it.
    private static final float INSIDE_LENGTH = 16.0f;
    private static final float INSIDE_WIDTH = 12.0f;
    private static final float FLOOR_TOP = 3.0f;

    // A sliver taken off the length and width, under a tenth of a pixel, so no face of the load
    // lands on a wall and flickers against it — what sensible-storage trimmed its cart's shulker
    // box by.
    private static final float WALL_GAP = 0.005f;

    // A hair above the floor for the same reason, though the floor is only ever seen from below.
    private static final float FLOOR_GAP = 0.01f;

    private static final float PIXEL = 1.0f / 16.0f;

    private final CoalTenderMinecart tender;
    private final ItemDisplayElement load;

    // The height the load was last posed at, in whole pixels.
    private int shownPixels;

    /** @param tender the tender this load rides */
    public TenderLoadDisplay(CoalTenderMinecart tender) {
        super(tender);
        this.tender = tender;
        this.load = addCargoElement(ItemDisplays.element());
        this.shownPixels = tender.loadPixels();
        showCoal(shownPixels > 0);

        // Snapping, never easing: a cart's rotation reaches the client in whole-tick steps, and a
        // load easing between those steps would slide about inside the cart.
        load.setInterpolationDuration(0);

        // Before the holder is attached, so no client is sent an unposed block — an element is
        // spawned with no transform at all, which for this one is a full-size cube in the floor.
        poseNow();
    }

    @Override
    protected void onTick() {
        int pixels = tender.loadPixels();

        if (pixels != shownPixels) {
            if ((pixels > 0) != (shownPixels > 0)) {
                showCoal(pixels > 0);
            }

            shownPixels = pixels;
            // The height lives in the pose, and the cart may not have turned this tick.
            markPoseDirty();
        }

        super.onTick();
    }

    // The item is set only when the load appears or goes, not on every pose: a fresh stack is a
    // change of tracked data whether or not it holds the same item, and would be re-sent each turn.
    private void showCoal(boolean shown) {
        load.setItem(shown ? new ItemStack(Items.COAL_BLOCK) : ItemStack.EMPTY);
    }

    // The block in the cart's own frame, where its length runs along x and its width along z, so
    // the scale is the inside's length, the load's height and the inside's width as they are. The
    // renderer's extra half turn of every item display is a half turn of a cube with the same face
    // all round, so nothing here has to undo it.
    @Override
    protected void applyPose(float yaw, float pitch, boolean turned) {
        if (shownPixels <= 0) {
            return;
        }

        float height = shownPixels * PIXEL;
        float centreY = FLOOR_TOP * PIXEL + FLOOR_GAP + height / 2.0f;
        Quaternionf cartFrame = cartFrame(yaw, pitch);

        load.setLeftRotation(cartFrame);
        load.setScale(new Vector3f(
                INSIDE_LENGTH * PIXEL - WALL_GAP, height, INSIDE_WIDTH * PIXEL - WALL_GAP));
        load.setTranslation(cargoOrigin(cartFrame, centreY));
    }
}
