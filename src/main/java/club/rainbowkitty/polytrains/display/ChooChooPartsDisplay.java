package club.rainbowkitty.polytrains.display;

import java.util.function.Consumer;

import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import eu.pb4.polymer.virtualentity.api.elements.ItemDisplayElement;
import eu.pb4.polymer.virtualentity.api.elements.VirtualElement;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.kalic0re.common.display.ItemDisplayPose;
import club.rainbowkitty.kalic0re.common.display.ItemDisplays;
import club.rainbowkitty.kalic0re.common.display.RenderTrail;
import club.rainbowkitty.kalic0re.common.vehicle.CargoDisplayHolder;
import club.rainbowkitty.polytrains.Polytrains;
import club.rainbowkitty.polytrains.init.EntityAttachments;
import club.rainbowkitty.polytrains.item.WrenchItems;
import club.rainbowkitty.polytrains.rule.ModGameRules;
import club.rainbowkitty.polytrains.train.BrakeLevers;
import club.rainbowkitty.polytrains.util.FurnaceFuel;

/**
 * The fittings on a furnace cart — a smokestack, a whistle and the brake lever — drawn as one item
 * model posed in the cart's frame, and the box a client clicks the lever by.
 *
 * <p>The model is the {@code choo_choo_parts} group of {@code art/furnace_minecart.bbmodel}, baked
 * by {@code scripts/bbmodel-group-to-item-model.py}. It was built around a copy of the cart and its
 * furnace as vanilla draws them, so its axes are the cart's own frame as they stand: x along the
 * cart with the furnace's door on -x, which is the cart's front (a placed furnace cart is turned
 * to face its placer: {@code MinecartHelper#placementLook}), z across it, and Blockbench's origin
 * at the cart's position. One transform places all of it. The lever stands on +z, the cart's left
 * as it faces its front. Two models, one per lever position: thrown is the group as drawn,
 * released turns its {@code brake_lever} group 45° about z, which tips the lever toward the door.
 *
 * <p>The smokestack and whistle are drawn only while {@code enable_choochoo} is on; off, the same
 * lever is drawn alone, from two more models of its group by itself. While they are drawn and the
 * furnace burns, the stack puffs campfire smoke on a steady beat, five times sparser parked.
 *
 * <p>The lever is clicked through a {@link ClickBox} reaching to the furnace's top, so a client's
 * click on it never crackles as one on the furnace cart itself does under the Sounds mod. A click
 * on its lever half throws or releases the lever ({@link #throwsLever}); anything else is the
 * cart's, passed on. Fuel goes past it: a player holding fuel the cart would take is not shown the
 * box, so their click lands on the cart and feeds it — unless they are sneaking, which shows them
 * the box again, and the lever with it.
 */
public class ChooChooPartsDisplay extends CargoDisplayHolder {

    // The whole group with the lever thrown and released, and the lever alone, for a world with
    // enable_choochoo off. The lever is baked the same in both.
    private static final Identifier PARTS_BRAKE_ON = Polytrains.id("choo_choo_parts_brake_on");
    private static final Identifier PARTS_BRAKE_OFF = Polytrains.id("choo_choo_parts_brake_off");
    private static final Identifier LEVER_ON = Polytrains.id("brake_lever_on");
    private static final Identifier LEVER_OFF = Polytrains.id("brake_lever_off");

    // The middle of the smokestack's top, in the cart's frame: the model's stack_top, 5 pixels
    // square and centred at (1.5, 0) once its group's half turn is taken, tops out 21.5 pixels
    // above the cart's position. Moves with the model: read it off the baked stack_top.
    private static final Vector3f STACK_TOP = new Vector3f(1.5f / 16.0f, 21.5f / 16.0f, 0.0f);

    // Campfire smoke, one puff on a steady beat rather than a campfire's two or three on one tick
    // in nine (CampfireBlockEntity#particleTick): every 4 ticks while the engine drives, about a
    // campfire's own average, and every 20 while it burns parked. Rising at a campfire's 0.07 a
    // tick, from within a pixel of the mouth's middle. Each puff's size, sprite and life are still
    // the client's to pick (SingleQuadParticle, CampfireSmokeParticle); nothing sent sets them.
    private static final int SMOKE_INTERVAL = 4;
    private static final int PARKED_SMOKE_INTERVAL = 20;
    private static final double SMOKE_RISE = 0.07;
    private static final double SMOKE_SPREAD = 1.0 / 16.0;

    // Every item display is drawn half a turn about its own y before its transform applies, which
    // would stand the smokestack over the door; the model is turned back first.
    private static final Quaternionf UNDO_RENDERER_TURN = ItemDisplayPose.orientation(0.0f);

    // How high above the foot of the box it lands on a click has to be to count as on a side or the
    // top rather than the underside, which no click on the lever's half is meant to reach: a pixel.
    private static final double UNDERSIDE = 1.0 / 16.0;

    // The lever box's top above the cart's position: the furnace's top (1.03125, as
    // AbstractMinecartRenderer draws it) and a hair, which the lever stands under.
    private static final float BOX_TOP = 17.0f / 16.0f;

    // Vanilla's allowance past a player's reach for a click on an entity
    // (ServerGamePacketListenerImpl#handleInteract), which a virtual entity is not given.
    private static final double REACH_BUFFER = 3.0;

    private final ItemDisplayElement parts;
    private final ClickBox leverBox;

    // Where the cart has been, so the smoke leaves the stack the client is drawing, which is the
    // cart as it was renderLag() ticks ago; the holder's own trail is its own.
    private final RenderTrail smokeTrail;

    // Whether the lever was last drawn thrown, and the smokestack and whistle drawn at all.
    private boolean shownThrown;
    private boolean shownChooChoo;

    /** @param engine the furnace cart the parts are on */
    public ChooChooPartsDisplay(AbstractMinecart engine) {
        super(engine);
        this.shownThrown = EntityAttachments.leverSet(engine);
        this.shownChooChoo = chooChoo(engine);
        this.smokeTrail = new RenderTrail(engine, MAX_RENDER_LAG);
        this.parts = addCargoElement(ItemDisplays.element());
        this.leverBox = new ClickBox(this, BOX_TOP,
                player -> !feedsPast(engine, player, player.isShiftKeyDown()),
                new LeverBoxHandler());

        parts.setItem(partsStack(shownThrown, shownChooChoo));
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
     * Whether a click throws or releases {@code engine}'s lever: the main hand's click on the
     * lever's half — the cart's left side, or the left half of its front, back or top — plain, or
     * sneaking with fuel in hand. The wrench is for the cart wherever it lands, and so is fuel
     * offered without sneaking, which feeds it; a shift-click with anything else is the cart's.
     *
     * @param sneaking whether the click is a shift-click
     * @param hit where the click landed, relative to the foot of the box it landed on: the lever
     *     box's or the cart's, which stand on the same line
     */
    public static boolean throwsLever(AbstractMinecart engine, Player player,
            InteractionHand hand, boolean sneaking, Vec3 hit) {
        ItemStack held = player.getMainHandItem();
        boolean fuel = FurnaceFuel.feeds(engine.level(), held);

        return hand == InteractionHand.MAIN_HAND
                && !WrenchItems.isWrench(held)
                && (fuel ? sneaking : !sneaking)
                && onLeverSide(engine, hit);
    }

    // Whether player's click, sneaking or not, offers engine fuel, which goes past the lever box.
    private static boolean feedsPast(AbstractMinecart engine, Player player, boolean sneaking) {
        return !sneaking && FurnaceFuel.feeds(engine.level(), player.getMainHandItem());
    }

    // On the lever's half: across the cart toward its frame's +z, which its pitch never moves, and
    // not on the underside. The box does not turn with the cart; the half is measured all the same.
    private static boolean onLeverSide(AbstractMinecart engine, Vec3 hit) {
        double yaw = Math.toRadians(engine.getYRot());
        double across = hit.x * Math.sin(yaw) + hit.z * Math.cos(yaw);

        return across > 0.0 && hit.y > UNDERSIDE;
    }

    @Override
    protected void onTick() {
        boolean thrown = EntityAttachments.leverSet(cart());
        boolean chooChoo = chooChoo(cart());

        // Set only on a change: a fresh stack is new tracked data whether or not it is the same.
        if (thrown != shownThrown || chooChoo != shownChooChoo) {
            shownThrown = thrown;
            shownChooChoo = chooChoo;
            parts.setItem(partsStack(thrown, chooChoo));
        }

        smokeTrail.push(cart());

        // Parked is lit but not driving: braked, or held by its train.
        int interval = cart().polytrains$driving() ? SMOKE_INTERVAL : PARKED_SMOKE_INTERVAL;

        if (chooChoo && cart().polytrains$fuel() > 0
                && cart().tickCount % interval == 0
                && cart().level() instanceof ServerLevel level) {
            smoke(level);
        }

        leverBox.tick(getWatchingPlayers());
        super.onTick();
    }

    // A puff from the stack's mouth, where the client is drawing it.
    private void smoke(ServerLevel level) {
        double lag = renderLag();
        Vector3f mouth = cartFrame(smokeTrail.yaw(lag), smokeTrail.pitch(lag))
                .transform(new Vector3f(STACK_TOP));
        Vec3 at = smokeTrail.position(lag).add(mouth.x(), mouth.y(), mouth.z());
        RandomSource random = cart().getRandom();

        // A count of zero sends one particle whose spread is its velocity, scaled by the speed:
        // straight up at the rise, as a campfire's.
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, false, true,
                at.x + (random.nextDouble() * 2.0 - 1.0) * SMOKE_SPREAD,
                at.y,
                at.z + (random.nextDouble() * 2.0 - 1.0) * SMOKE_SPREAD,
                0, 0.0, 1.0, 0.0, SMOKE_RISE);
    }

    // Whether engine's world draws the smokestack and whistle; read each tick, never kept.
    private static boolean chooChoo(AbstractMinecart engine) {
        return engine.level() instanceof ServerLevel level
                && ModGameRules.ENABLE_CHOOCHOO.get(level.getServer());
    }

    @Override
    protected void startWatchingExtraPackets(ServerGamePacketListenerImpl player,
            Consumer<Packet<ClientGamePacketListener>> packetConsumer) {
        super.startWatchingExtraPackets(player, packetConsumer);
        leverBox.startWatching(player, packetConsumer);
    }

    @Override
    public boolean stopWatching(ServerGamePacketListenerImpl player) {
        leverBox.stopWatching(player);
        return super.stopWatching(player);
    }

    // The model's origin is the cart's position, so the whole pose is the cart's frame. The box is
    // a world-aligned box, which no pose turns.
    @Override
    protected void applyPose(float yaw, float pitch, boolean turned) {
        Quaternionf cartFrame = cartFrame(yaw, pitch);

        parts.setLeftRotation(new Quaternionf(cartFrame));
        parts.setTranslation(cargoOrigin(cartFrame, 0.0f));
    }

    // The parts, or the lever alone, with the lever thrown or released, carried on a lever, which
    // is what a client without this mod's resource pack is shown instead.
    private static ItemStack partsStack(boolean thrown, boolean chooChoo) {
        Identifier model = chooChoo
                ? (thrown ? PARTS_BRAKE_ON : PARTS_BRAKE_OFF)
                : (thrown ? LEVER_ON : LEVER_OFF);

        return ItemDisplays.modelStack(Items.LEVER, model);
    }

    // What the lever box does when clicked: the lever, if the click is for it, and otherwise
    // whatever the cart does. A client offers the box only the main hand's half of a click, since
    // a responding box answers it.
    private final class LeverBoxHandler implements VirtualElement.InteractionHandler {

        private final VirtualElement.InteractionHandler toCart = ClickBox.passedTo(cart());

        @Override
        public void interact(ServerPlayer player, InteractionHand hand, Vec3 pos,
                boolean usingSecondaryAction) {
            if (cart().isRemoved()) {
                return;
            }

            if (!throwsLever(cart(), player, hand, usingSecondaryAction, pos)) {
                toCart.interact(player, hand, pos, usingSecondaryAction);
                return;
            }

            if (!player.isWithinEntityInteractionRange(cart().getBoundingBox()
                    .expandTowards(0.0, BOX_TOP - cart().getBbHeight(), 0.0), REACH_BUFFER)) {
                return;
            }

            BrakeLevers.toggle(cart());
            // The clicking client swung its own arm on the box's response; this is for the rest.
            //? if >=26.3 {
            /*player.swing(hand, player.getItemInHand(hand).getInteractAnimation(), false);
            *///?} else {
            player.swing(hand);
            //?}
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
