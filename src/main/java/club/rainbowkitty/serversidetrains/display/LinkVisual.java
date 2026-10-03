package club.rainbowkitty.serversidetrains.display;

import eu.pb4.polymer.virtualentity.api.elements.BlockDisplayElement;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.kalic0re.common.display.RenderTrail;
import club.rainbowkitty.serversidetrains.rail.RailPath;
import club.rainbowkitty.serversidetrains.train.TrainCoupling;
import club.rainbowkitty.serversidetrains.util.MinecartHelper;

/**
 * Where the chain between a linked cart and the one it follows sits: the block display element
 * itself, and the geometry that spans and orients it between the two carts' couplers. Owns no
 * virtual-entity identity of its own — {@link LinkChain} is the passenger-mounted holder that does.
 */
public final class LinkVisual {

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

    // The cart this chain is drawn from, and the one it is drawn to.
    private final AbstractMinecart cart;
    private final AbstractMinecart parent;

    // The block display element rendering the chain itself.
    private final BlockDisplayElement chain;

    // The passenger point the display rides at, which every offset is measured from.
    private final Vec3 seatOffset;

    // Delayed samples of each cart, so the chain is built where the client draws them.
    private final RenderTrail cartTrail;
    private final RenderTrail parentTrail;

    // Which end face of each cart the chain is bolted to.
    private boolean cartFront = true;
    private boolean parentFront = true;

    // Whether both carts have been on the track since the ends were chosen.
    private boolean seated;

    /**
     * Builds the chain between {@code cart} and {@code parent}, ready to draw once ticked.
     *
     * @param cart the cart the chain is drawn from
     * @param parent the cart it is drawn to
     */
    public LinkVisual(AbstractMinecart cart, AbstractMinecart parent) {
        this.cart = cart;
        this.parent = parent;
        this.cartTrail = new RenderTrail(cart, RENDER_LAG);
        this.parentTrail = new RenderTrail(parent, RENDER_LAG);
        this.chain = new BlockDisplayElement(Blocks.IRON_CHAIN.defaultBlockState());

        // A ridden element still gets a position packet every tick by default, aimed at the cart's
        // feet rather than its seat, and the client draws it there for the frame before it puts the
        // rider back — a visible drop and snap on every tick of movement.
        chain.ignorePositionUpdates();

        // Riding puts the display at the cart's passenger point, so every offset below is measured
        // from there rather than from the cart's own position.
        this.seatOffset = cart.getType().getDimensions().attachments()
                .getClamped(EntityAttachment.PASSENGER, 0, 0.0F);
    }

    /** The block display element rendering the chain, for a holder to ride it as a passenger. */
    public BlockDisplayElement chain() {
        return chain;
    }

    /** Samples both carts' current position, then re-aims the chain to span their couplers. */
    public void tick() {
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
        Vec3 centreSpan = parentCentre.subtract(cartCentre);

        if (centreSpan.lengthSqr() < MIN_SPAN * MIN_SPAN) {
            return;
        }

        Vec3 cartAxis = lengthwise(cartYaw, cartPitch);
        Vec3 parentAxis = lengthwise(parentYaw, parentPitch);

        // Each car is asked against its own track rather than the line through the air to the
        // other one. Round a corner those two disagree: the line cuts across the inside of the
        // bend and can point at the wrong end of either car, while the exit of the block a car
        // stands on is a direction its body is lying along.
        //
        // A coupler is bolted to one end of the car, but not settled until the placer has laid
        // the train out — its first seating turns each car onto its train's track. A face
        // settled before that names the wrong end a tick later, so re-reading costs nothing: an
        // unseated car's track does not flicker the way the line between two cars does.
        if (!seated) {
            cartFront = cartAxis.dot(towards(parent, cart, centreSpan)) >= 0.0;
            parentFront = parentAxis.dot(towards(cart, parent, centreSpan.reverse())) >= 0.0;
            seated = laid(cart) && laid(parent);
        }

        Vec3 fromPosition = cartCentre.add(coupler(cartAxis, cart.getBbWidth(), cartFront));
        Vec3 spanVector = parentCentre
                .add(coupler(parentAxis, parent.getBbWidth(), parentFront))
                .subtract(fromPosition);
        double length = spanVector.length();
        Vec3 direction = length < MIN_SPAN
                ? centreSpan.normalize()
                : spanVector.scale(1.0 / length);
        Vec3 anchorOffset = fromPosition.subtract(cartPos).subtract(seatOffset);

        var rotation = pointAlong(direction);
        var axisOffset = rotation.transform(new Vector3f(MODEL_AXIS));

        chain.setLeftRotation(rotation);
        chain.setScale(new Vector3f(1.0F, (float) length, 1.0F));
        chain.setTranslation(new Vector3f(
                (float) anchorOffset.x - axisOffset.x,
                (float) anchorOffset.y - axisOffset.y,
                (float) anchorOffset.z - axisOffset.z));
    }

    // Whether the placer has laid this car out on its train's track, which is when its position
    // and facing stop being whatever they happened to be when it was coupled.
    private static boolean laid(AbstractMinecart car) {
        return !MinecartHelper.isDerailed(car) && car.serversidetrains$seatedAt() != 0L;
    }

    // The exit the near car's own block leads to the far one by. Falls back to the line between
    // them where there is no track to read, which is the best a car off the rails can be asked.
    private static Vec3 towards(AbstractMinecart far, AbstractMinecart near, Vec3 fallback) {
        RailPath path = RailPath.between(far, near, TrainCoupling.maxLinkWalk());

        return path == null ? fallback.normalize() : path.toForward();
    }

    // Measured up the cart's own body rather than straight up from its position, because a pitched
    // cart's middle swings out over the track behind it. On a crest, where the two carts lean
    // apart, taking it as vertical brings the coupler faces together and draws the chain short.
    private static Vec3 centre(Vec3 position, float yaw, float pitch, AbstractMinecart cart) {
        return position.add(lengthwise(yaw, pitch + 90.0F).scale(cart.getBbHeight() * 0.5));
    }

    // Just off the middle of the cart's front or back face, so the chain stays put on the bodywork
    // instead of sliding round the hull as the two carts swing apart.
    private static Vec3 coupler(Vec3 axisDirection, double width, boolean front) {
        double reach = width * COUPLER_REACH;
        return axisDirection.scale(front ? reach : -reach);
    }

    // The cart's long axis, undoing the mirror vanilla builds its yaw with. A flipped cart's comes
    // out reversed whole, which the caller's front-or-back sign test already answers for.
    private static Vec3 lengthwise(float yaw, float pitch) {
        double yawRadians = Math.toRadians(yaw);
        double pitchRadians = Math.toRadians(pitch);
        double horizontalScale = Math.cos(pitchRadians);

        return new Vec3(-Math.cos(yawRadians) * horizontalScale,
                Math.sin(pitchRadians),
                Math.sin(yawRadians) * horizontalScale);
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
