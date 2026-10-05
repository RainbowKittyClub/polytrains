package club.rainbowkitty.serversidetrains.display;

import java.util.function.Consumer;

import eu.pb4.polymer.virtualentity.api.VirtualEntityUtils;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.InteractionElement;
import eu.pb4.polymer.virtualentity.api.elements.SimpleEntityElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.core.Direction;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeverBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.AttachFace;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.serversidetrains.init.EntityAttachments;
import club.rainbowkitty.serversidetrains.item.WrenchItems;
import club.rainbowkitty.serversidetrains.train.BrakeLevers;

/**
 * The brake lever on top of a furnace cart: a lever drawn on the furnace, turning and tipping with
 * the cart, and an invisible slime sitting over it for a client to click.
 *
 * <p>The lever is a block display posed in the cart's frame, as a tender's coal is. The slime is
 * what a vanilla client can click, and it cannot be posed: a rider sits on its vehicle's passenger
 * point, which for a cart is low inside the furnace. So it rides an interaction instead, as tall as
 * the furnace stands above that point and too thin to be clicked itself, whose own passenger point
 * is its top. Both points are straight up from the cart whatever its pitch, so on a slope the slime
 * stays over the cart's middle while the lever tips with the furnace — about half a block apart at
 * a full rail's grade.
 */
public class BrakeLeverDisplay extends CartDisplayHolder {

    // The top of the furnace a furnace cart carries, above the cart's position: the renderer draws
    // it at three quarters scale from 0.28125, for a display offset of 6 pixels
    // (AbstractMinecartRenderer).
    private static final float FURNACE_TOP = 1.03125f;

    // A hair between the lever's base and the furnace, which the renderer jitters by up to 0.002 a
    // cart, so the base never sinks into it.
    private static final float SEAT_GAP = 0.003f;

    // The lever is drawn at the scale the furnace is.
    private static final float LEVER_SCALE = 0.75f;

    // How much higher than the cart's own box a click on the lever may land and still be in reach:
    // the slime's top, a size-one slime standing 0.52 on the furnace.
    private static final double LEVER_REACH = FURNACE_TOP + 0.52 - 0.7;

    // Vanilla's allowance past a player's reach for a click on an entity
    // (ServerGamePacketListenerImpl#handleInteract), which a virtual entity is not given.
    private static final double REACH_BUFFER = 3.0;

    private final BlockDisplayElement lever;
    private final InteractionElement riser;
    private final SimpleEntityElement slime;

    // Whether the lever was last drawn thrown.
    private boolean shownThrown;

    /** @param engine the furnace cart the lever is on */
    public BrakeLeverDisplay(AbstractMinecart engine) {
        super(engine);
        this.shownThrown = EntityAttachments.leverSet(engine);
        this.lever = addCargoElement(new BlockDisplayElement(leverState(shownThrown)));

        // Snapping, never easing, as a tender's coal does: the cart turns in whole-tick steps.
        lever.setInterpolationDuration(0);

        // From the cart's passenger point up to the furnace's top, and no wider than a line.
        this.riser = addCargoElement(new InteractionElement());
        riser.setSize(0.0f, FURNACE_TOP - PASSENGER_ATTACHMENT_Y);

        this.slime = new SimpleEntityElement(EntityTypes.SLIME);
        slime.setInvisible(true);
        slime.setSilent(true);
        slime.setNoGravity(true);
        slime.ignorePositionUpdates();
        slime.setOffset(new Vec3(0.0, FURNACE_TOP, 0.0));
        slime.setInteractionHandler(new LeverHandler());
        addElement(slime);

        poseNow();
    }

    /**
     * Puts a lever on {@code engine} and announces it to every client already watching the cart.
     * A rider is otherwise only announced to a client when it starts watching.
     */
    public static BrakeLeverDisplay attach(AbstractMinecart engine) {
        BrakeLeverDisplay display = new BrakeLeverDisplay(engine);

        EntityAttachment.ofTicking(display, engine);
        display.sendPacket(new ClientboundSetPassengersPacket(engine));
        return display;
    }

    @Override
    protected void onTick() {
        boolean thrown = EntityAttachments.leverSet(cart());

        if (thrown != shownThrown) {
            shownThrown = thrown;
            lever.setBlockState(leverState(thrown));
        }

        super.onTick();
    }

    // The slime rides the riser, which no holder can say for it: a holder's riders all ride the
    // entity it is attached to. Sent after every element has spawned.
    @Override
    protected void startWatchingExtraPackets(ServerGamePacketListenerImpl player,
            Consumer<Packet<ClientGamePacketListener>> packetConsumer) {
        super.startWatchingExtraPackets(player, packetConsumer);
        packetConsumer.accept(VirtualEntityUtils.createClientboundSetPassengersPacket(
                riser.getEntityId(), new int[] {slime.getEntityId()}));
    }

    // The lever's base runs along the cart and its handle tips fore and aft: a floor lever facing
    // east lies along x, which the cart's frame runs along the cart.
    @Override
    protected void applyPose(float yaw, float pitch, boolean turned) {
        Quaternionf cartFrame = cartFrame(yaw, pitch);
        float half = LEVER_SCALE / 2.0f;

        lever.setLeftRotation(new Quaternionf(cartFrame));
        lever.setScale(new Vector3f(LEVER_SCALE));
        lever.setTranslation(cartFrame
                .transform(new Vector3f(-half, FURNACE_TOP + SEAT_GAP, -half))
                .sub(0.0f, PASSENGER_ATTACHMENT_Y, 0.0f));
    }

    // A floor lever along the cart, thrown or not.
    private static BlockState leverState(boolean thrown) {
        return Blocks.LEVER.defaultBlockState()
                .setValue(LeverBlock.FACE, AttachFace.FLOOR)
                .setValue(LeverBlock.FACING, Direction.EAST)
                .setValue(LeverBlock.POWERED, thrown);
    }

    // What the slime does when clicked. A client offers a slime each click once per hand, since
    // a slime takes nothing it is offered, so only the main hand's half is answered. A punch, a
    // pick, a shift-click and anything done with the wrench are meant for the cart and are passed
    // to it as if aimed there.
    private final class LeverHandler implements VirtualElement.InteractionHandler {

        private final VirtualElement.InteractionHandler toCart =
                VirtualElement.InteractionHandler.redirect(cart());

        @Override
        public void interact(ServerPlayer player, InteractionHand hand, Vec3 pos,
                boolean usingSecondaryAction) {
            if (hand != InteractionHand.MAIN_HAND || cart().isRemoved()) {
                return;
            }

            if (usingSecondaryAction || WrenchItems.isWrench(player.getMainHandItem())) {
                toCart.interact(player, hand, pos, usingSecondaryAction);
                return;
            }

            if (!player.isWithinEntityInteractionRange(
                    cart().getBoundingBox().expandTowards(0.0, LEVER_REACH, 0.0), REACH_BUFFER)) {
                return;
            }

            BrakeLevers.toggle(cart());
            player.swing(hand, true);
        }

        @Override
        public void attack(ServerPlayer player) {
            toCart.attack(player);
        }

        @Override
        public void pickItem(ServerPlayer player, boolean includeData) {
            toCart.pickItem(player, includeData);
        }
    }
}
