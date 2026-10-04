package club.rainbowkitty.serversidetrains.tender;

import com.mojang.math.Axis;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

import club.rainbowkitty.kalic0re.common.display.ItemDisplays;
import club.rainbowkitty.kalic0re.common.vehicle.CargoDisplayHolder;
import club.rainbowkitty.serversidetrains.ServersideTrains;

/**
 * The coal riding in a {@link CoalTenderMinecart}, and the shovel in it: one block of coal
 * stretched to the cart's inside and standing as high as the tender is full, and a shovel that
 * sinks with it, both mounted as the cart's passengers and posed in its frame.
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

    // The shovel's two poses, as placed against a full load and an empty cart in
    // media/blender_exports/coal_tender/coal_tender_shovel.blend: where the centre of its model
    // sits, in blocks from the cart's position, and how it is turned, both in the cart's frame —
    // x along the cart, y up, z across — which is the .blend's x, z and -y. Checked by putting
    // every vertex of the model through these and the game's pose: each lands within a millionth
    // of a pixel of where Blender has it.
    private static final Vector3f SHOVEL_FULL_AT =
            new Vector3f(0.330214202f, 0.861615658f, 0.213491261f);
    private static final Quaternionf SHOVEL_FULL_TURN =
            new Quaternionf(-0.392578632f, 0.499553680f, 0.315643430f, 0.704767644f);
    private static final Vector3f SHOVEL_EMPTY_AT =
            new Vector3f(-0.011349969f, 0.218750000f, -0.005910277f);
    private static final Quaternionf SHOVEL_EMPTY_TURN =
            new Quaternionf(0.0f, 0.501377761f, 0.0f, 0.865228474f);

    // How far the shovel can sink with the coal before the blade's tip meets the floor, in pixels:
    // its lowest point in the full pose stands this high above the floor's top.
    private static final float SHOVEL_SINK_LIMIT = 3.892530f;

    // Every item display is drawn half a turn about its own y before its transform applies. A
    // cube with one face all round cannot show it, a shovel can, so its model is turned back first.
    private static final Quaternionf UNDO_RENDERER_TURN = Axis.YP.rotationDegrees(180.0f);

    private final CoalTenderMinecart tender;
    private final ItemDisplayElement load;
    private final ItemDisplayElement shovel;

    // The height the load was last posed at, in whole pixels.
    private int shownPixels;

    /** @param tender the tender this load rides */
    public TenderLoadDisplay(CoalTenderMinecart tender) {
        super(tender);
        this.tender = tender;
        this.load = addCargoElement(ItemDisplays.element());
        this.shovel = addCargoElement(ItemDisplays.element());
        this.shownPixels = tender.loadPixels();
        showCoal(shownPixels > 0);

        // Carried on an iron shovel, which is what a client without this mod's resource pack is
        // shown instead.
        shovel.setItem(ItemDisplays.modelStack(
                Items.IRON_SHOVEL, ServersideTrains.id("coal_tender_shovel")));
        shovel.setRightRotation(UNDO_RENDERER_TURN);

        // Snapping, never easing: a cart's rotation reaches the client in whole-tick steps, and a
        // load easing between those steps would slide about inside the cart.
        load.setInterpolationDuration(0);
        shovel.setInterpolationDuration(0);

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

    @Override
    protected void applyPose(float yaw, float pitch, boolean turned) {
        Quaternionf cartFrame = cartFrame(yaw, pitch);

        poseShovel(cartFrame);

        if (shownPixels > 0) {
            poseLoad(cartFrame);
        }
    }

    // The block in the cart's own frame, where its length runs along x and its width along z, so
    // the scale is the inside's length, the load's height and the inside's width as they are. The
    // renderer's extra half turn of every item display is a half turn of a cube with the same face
    // all round, so nothing here has to undo it.
    private void poseLoad(Quaternionf cartFrame) {
        float height = shownPixels * PIXEL;
        float centreY = FLOOR_TOP * PIXEL + FLOOR_GAP + height / 2.0f;

        load.setLeftRotation(new Quaternionf(cartFrame));
        load.setScale(new Vector3f(
                INSIDE_LENGTH * PIXEL - WALL_GAP, height, INSIDE_WIDTH * PIXEL - WALL_GAP));
        load.setTranslation(cargoOrigin(cartFrame, centreY));
    }

    // Placed as for a full load and sunk a pixel for every pixel the coal has gone down, until the
    // blade's tip is on the floor; an empty tender's shovel lies on the floor where it was put.
    private void poseShovel(Quaternionf cartFrame) {
        Vector3f at;
        Quaternionf turn;

        if (shownPixels > 0) {
            float sunk = Math.min(CoalTenderMinecart.LOAD_PIXELS - shownPixels, SHOVEL_SINK_LIMIT);

            at = new Vector3f(SHOVEL_FULL_AT).sub(0.0f, sunk * PIXEL, 0.0f);
            turn = SHOVEL_FULL_TURN;
        } else {
            at = new Vector3f(SHOVEL_EMPTY_AT);
            turn = SHOVEL_EMPTY_TURN;
        }

        // Measured from the passenger point the client mounts the element at, which does not turn
        // with the cart, while the pose is in the cart's frame, which does.
        shovel.setTranslation(cartFrame.transform(at).sub(0.0f, PASSENGER_ATTACHMENT_Y, 0.0f));
        shovel.setLeftRotation(new Quaternionf(cartFrame).mul(turn));
    }
}
