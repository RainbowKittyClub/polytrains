package club.rainbowkitty.minecarttweaks.visual;

import eu.pb4.polymer.virtualentity.api.ElementHolder;
import eu.pb4.polymer.virtualentity.api.attachment.EntityAttachment;
import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.jspecify.annotations.Nullable;

import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.kalic0re.common.display.RenderTrail;
import club.rainbowkitty.minecarttweaks.util.MinecartHelper;

/**
 * The chain a linked cart shows back to the cart it follows, drawn as one block display so that a
 * vanilla client needs nothing installed.
 */
public final class LinkChain extends ElementHolder {

    // How far out from a cart's middle the coupler sits, in cart widths: half its extent, plus a
    // little clear of the end face.
    private static final float COUPLER_REACH = 0.55F;

    // Below this the span between two points is too short to take a direction from.
    private static final double MIN_SPAN = 1.0E-4;

    // A cart's movement reaches the client three ticks late: the server flushes three accumulated
    // lerp steps every third tick, and NewMinecartBehavior plays them back one per tick. So the
    // chain is built from where the carts were three ticks ago, which is where they are drawn.
    private static final double RENDER_LAG = 3.5;

    // The chain model runs up the block's Y axis through (0.5, *, 0.5), so the transform has to
    // cancel that corner offset after rotating it, or the chain hangs beside the carts.
    private static final Vector3f MODEL_AXIS = new Vector3f(0.5F, 0.0F, 0.5F);

    // The cart this chain is drawn from, which carries the display, and the one it is drawn to.
    private final Entity cart;
    private final Entity parent;

    private final BlockDisplayElement chain;

    // The passenger point the display rides at, which every offset is measured from.
    private final Vec3 seat;

    // Delayed samples of each cart, so the chain is built where the client draws them.
    private final RenderTrail cartTrail;
    private final RenderTrail parentTrail;

    // Which end face of each cart the chain is bolted to.
    private boolean cartFront = true;
    private boolean parentFront = true;

    // Whether both carts have been on the track since the ends were chosen.
    private boolean seated;

    // Builds the display and aims it before it is ever sent.
    private LinkChain(Entity cart, Entity parent) {
        this.cart = cart;
        this.parent = parent;
        this.cartTrail = new RenderTrail(cart, RENDER_LAG);
        this.parentTrail = new RenderTrail(parent, RENDER_LAG);

        // Rides the cart rather than being placed at it, so the client draws it against the
        // position it is already showing rather than against server truth.
        this.chain = addPassengerElement(
                new BlockDisplayElement(Blocks.IRON_CHAIN.defaultBlockState()));

        // A ridden element still gets a position packet every tick by default, aimed at the cart's
        // feet rather than its seat, and the client draws it there for the frame before it puts the
        // rider back — a visible drop and snap on every tick of movement.
        chain.ignorePositionUpdates();

        // Riding puts the display at the cart's passenger point, so every offset below is measured
        // from there rather than from the cart's own position.
        this.seat = cart.getType().getDimensions().attachments()
                .getClamped(net.minecraft.world.entity.EntityAttachment.PASSENGER, 0, 0.0F);

        // A display is spawned with no transform, which for this model is stood on end. Aiming it
        // before it is ever sent means no client sees that frame.
        aim();
    }

    /**
     * Brings the chain on {@code cart} into line with the cart it currently follows, creating,
     * retargeting or removing it as needed, and returns the chain to hold onto.
     */
    public static @Nullable LinkChain sync(
            @Nullable LinkChain current, Entity cart, @Nullable Entity parent) {
        boolean usable = parent != null && parent.level() == cart.level();

        if (current != null && (!usable || current.parent != parent)) {
            current.destroy();
            current = null;
        }

        if (!usable) {
            return null;
        }

        if (current == null) {
            current = new LinkChain(cart, parent);
            EntityAttachment.ofTicking(current, cart);

            // Riders are announced to a client when it starts tracking the cart, so a chain built
            // while the cart is already tracked has to announce itself or it never mounts.
            current.sendPacket(new ClientboundSetPassengersPacket(cart));
        }

        return current;
    }

    // Runs from the entity tracker rather than the cart's own tick, so a train nobody is near costs
    // nothing, and a stationary one resolves to the same transform and sends no packet.
    @Override
    protected void onTick() {
        cartTrail.push(cart);
        parentTrail.push(parent);

        // Only worth asking while the ends hold: aim() asks again for itself once they do not.
        if (seated) {
            seated = !MinecartHelper.isDerailed(cart) && !MinecartHelper.isDerailed(parent);
        }

        aim();
    }

    // Rewrites the display's transform so the chain spans the two carts' couplers.
    private void aim() {
        Vec3 cartPos = cartTrail.position(RENDER_LAG);
        float cartYaw = cartTrail.yaw(RENDER_LAG);
        float cartPitch = cartTrail.pitch(RENDER_LAG);
        float parentYaw = parentTrail.yaw(RENDER_LAG);
        float parentPitch = parentTrail.pitch(RENDER_LAG);

        Vec3 cartCentre = centre(cartPos, cartYaw, cartPitch, cart);
        Vec3 parentCentre =
                centre(parentTrail.position(RENDER_LAG), parentYaw, parentPitch, parent);
        Vec3 centres = parentCentre.subtract(cartCentre);

        if (centres.lengthSqr() < MIN_SPAN * MIN_SPAN) {
            return;
        }

        Vec3 cartAxis = lengthwise(cartYaw, cartPitch);
        Vec3 parentAxis = lengthwise(parentYaw, parentPitch);

        // A coupler is bolted to one end of the car, so the face is chosen once. Only a car lifted
        // off the track can be set back down the other way round, which reopens the question.
        if (!seated) {
            Vec3 between = centres.normalize();
            cartFront = cartAxis.dot(between) >= 0.0;
            parentFront = parentAxis.dot(between) < 0.0;
            seated = !MinecartHelper.isDerailed(cart) && !MinecartHelper.isDerailed(parent);
        }

        Vec3 from = cartCentre.add(coupler(cartAxis, cart.getBbWidth(), cartFront));
        Vec3 span = parentCentre
                .add(coupler(parentAxis, parent.getBbWidth(), parentFront))
                .subtract(from);
        double length = span.length();
        Vec3 direction = length < MIN_SPAN ? centres.normalize() : span.scale(1.0 / length);
        Vec3 anchor = from.subtract(cartPos).subtract(seat);

        var rotation = pointAlong(direction);
        var axisOffset = rotation.transform(new Vector3f(MODEL_AXIS));

        chain.setLeftRotation(rotation);
        chain.setScale(new Vector3f(1.0F, (float) length, 1.0F));
        chain.setTranslation(new Vector3f(
                (float) anchor.x - axisOffset.x,
                (float) anchor.y - axisOffset.y,
                (float) anchor.z - axisOffset.z));
    }

    // Measured up the cart's own body rather than straight up from its position, because a pitched
    // cart's middle swings out over the track behind it. On a crest, where the two carts lean
    // apart, taking it as vertical brings the coupler faces together and draws the chain short.
    private static Vec3 centre(Vec3 position, float yaw, float pitch, Entity cart) {
        return position.add(lengthwise(yaw, pitch + 90.0F).scale(cart.getBbHeight() * 0.5));
    }

    // Just off the middle of the cart's front or back face, so the chain stays put on the bodywork
    // instead of sliding round the hull as the two carts swing apart.
    private static Vec3 coupler(Vec3 lengthwise, double width, boolean front) {
        double reach = width * COUPLER_REACH;
        return lengthwise.scale(front ? reach : -reach);
    }

    // The cart's long axis, undoing the mirror vanilla builds its yaw with. A flipped cart's comes
    // out reversed whole, which the caller's front-or-back sign test already answers for.
    private static Vec3 lengthwise(float yaw, float pitch) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double level = Math.cos(pitchRadians);

        return new Vec3(-Math.cos(yawRadians) * level,
                Math.sin(pitchRadians),
                Math.sin(yawRadians) * level);
    }

    // Rotating the model's Y axis onto the span by the shortest arc leaves the roll about that axis
    // free, and the roll is what decides where the model's own (0.5, *, 0.5) axis ends up — so the
    // ends would swing vertically as the span turns. Yaw then pitch pins the roll down instead.
    private static Quaternionf pointAlong(Vec3 direction) {
        return new Quaternionf()
                .rotateY((float) Math.atan2(direction.x, direction.z))
                .rotateX((float) Math.atan2(direction.horizontalDistance(), direction.y));
    }

}
