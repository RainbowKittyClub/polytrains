package club.rainbowkitty.polytrains.display;

import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import org.joml.Quaternionf;

import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.kalic0re.common.display.ItemDisplayPose;
import club.rainbowkitty.kalic0re.common.display.ItemDisplays;
import club.rainbowkitty.kalic0re.common.vehicle.CargoDisplayHolder;
import club.rainbowkitty.polytrains.Polytrains;
import club.rainbowkitty.polytrains.init.EntityAttachments;

/**
 * The fittings on a furnace cart — a smokestack, a whistle and the brake lever — drawn as one item
 * model posed in the cart's frame, and the one place that knows which side of the cart the lever
 * is on.
 *
 * <p>The model is the {@code choo_choo_parts} group of {@code art/furnace_minecart.bbmodel}, baked
 * by {@code scripts/bbmodel-group-to-item-model.py}. It was built around a copy of the cart and its
 * furnace as vanilla draws them, so its axes are the cart's own frame as they stand: x along the
 * cart with the furnace's door on -x, which is the cart's front (a placed furnace cart is turned
 * to face its placer: {@code MinecartHelper#placementLook}), z across it, and Blockbench's origin
 * at the cart's position. One transform places all of it. The lever stands on +z, the cart's left
 * as it faces its front.
 *
 * <p>Nothing here is clicked. The cart is: a click landing on the lever's half of its box throws or
 * releases the lever ({@link #onLeverSide}), and anywhere else is the cart's as before. The box is
 * the cart's own, so it is wherever the client draws the cart, which nothing positioned beside it
 * could be.
 *
 * <p>Two models, one per lever position: thrown is the group as drawn, released turns its
 * {@code brake_lever} group 45° about z, which tips the lever toward the door.
 */
public class ChooChooPartsDisplay extends CargoDisplayHolder {

    private static final Identifier BRAKE_ON = Polytrains.id("choo_choo_parts_brake_on");
    private static final Identifier BRAKE_OFF = Polytrains.id("choo_choo_parts_brake_off");

    // Every item display is drawn half a turn about its own y before its transform applies, which
    // would stand the smokestack over the door; the model is turned back first.
    private static final Quaternionf UNDO_RENDERER_TURN = ItemDisplayPose.orientation(0.0f);

    // How high above the cart's position a click has to land to count as on a side or the top
    // rather than the underside, which no click on the lever's half is meant to reach: a pixel.
    private static final double UNDERSIDE = 1.0 / 16.0;

    private final ItemDisplayElement parts;

    // Whether the lever was last drawn thrown.
    private boolean shownThrown;

    /** @param engine the furnace cart the parts are on */
    public ChooChooPartsDisplay(AbstractMinecart engine) {
        super(engine);
        this.shownThrown = EntityAttachments.leverSet(engine);
        this.parts = addCargoElement(ItemDisplays.element());

        parts.setItem(partsStack(shownThrown));
        parts.setRightRotation(UNDO_RENDERER_TURN);

        // Snapping, never easing, as a tender's coal does: the cart turns in whole-tick steps.
        parts.setInterpolationDuration(0);

        poseNow();
    }

    /**
     * Puts the parts on {@code engine} and announces them to every client already watching the
     * cart. A rider is otherwise only announced to a client when it starts watching.
     */
    public static ChooChooPartsDisplay attach(AbstractMinecart engine) {
        ChooChooPartsDisplay display = new ChooChooPartsDisplay(engine);

        EntityAttachment.ofTicking(display, engine);
        display.sendPacket(new ClientboundSetPassengersPacket(engine));
        return display;
    }

    /**
     * Whether a click on {@code engine}'s box at {@code hit}, relative to its position, is on the
     * lever's half: the cart's left side, or the left half of its front, back or top.
     *
     * <p>The box does not turn with the cart, but the half is measured across the cart all the
     * same: its frame's +z, which its pitch never moves.
     */
    public static boolean onLeverSide(AbstractMinecart engine, Vec3 hit) {
        double yaw = Math.toRadians(engine.getYRot());
        double across = hit.x * Math.sin(yaw) + hit.z * Math.cos(yaw);

        return across > 0.0 && hit.y > UNDERSIDE;
    }

    @Override
    protected void onTick() {
        boolean thrown = EntityAttachments.leverSet(cart());

        if (thrown != shownThrown) {
            shownThrown = thrown;
            parts.setItem(partsStack(thrown));
        }

        super.onTick();
    }

    // The model's origin is the cart's position, so the whole pose is the cart's frame.
    @Override
    protected void applyPose(float yaw, float pitch, boolean turned) {
        Quaternionf cartFrame = cartFrame(yaw, pitch);

        parts.setLeftRotation(new Quaternionf(cartFrame));
        parts.setTranslation(cargoOrigin(cartFrame, 0.0f));
    }

    // The parts with the lever thrown or released, carried on a lever, which is what a client
    // without this mod's resource pack is shown instead.
    private static ItemStack partsStack(boolean thrown) {
        return ItemDisplays.modelStack(Items.LEVER, thrown ? BRAKE_ON : BRAKE_OFF);
    }
}
