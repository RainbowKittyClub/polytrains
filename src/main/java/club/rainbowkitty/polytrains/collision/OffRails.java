package club.rainbowkitty.polytrains.collision;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import net.minecraft.core.Direction;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

import club.rainbowkitty.polytrains.ext.Linkable;
import club.rainbowkitty.polytrains.train.TrainCoupling;
import club.rainbowkitty.polytrains.train.TrainPlacer;

/**
 * A car off its rails, and its set-down back onto them. Such a car shares track with no cart, so
 * {@link CartInteraction}'s rail test alone would let it through every cart; vanilla's move lets a
 * box through anything it already overlaps; and vanilla sets a car down onto a rail wherever the
 * rail is. All three are held here to what a step along the rail is held to: another train's car
 * stops the car where the two first touch, and one the car is already inside it goes no deeper
 * into. A car of a train flying a jump is stopped where it first touches the car of its own train
 * ahead as well, taking only the speed it closes at: the stop and shove that make two cars solid
 * would strand it short of the landing.
 */
public final class OffRails {

    // Closer than this two boxes count as touching rather than one inside the other: the
    // tolerance VoxelShape.collide settles a face at, from which CartImpact.deepens counts too.
    private static final double INSIDE = Shapes.EPSILON;

    // Entity.move's own floor: a grounded step under it, its fall stopped, goes nowhere at all.
    private static final double RESTING = 1.0E-7;

    // Static-only; blocks instantiation.
    private OffRails() {}

    /**
     * Whether {@code cart} and {@code other} can meet without sharing track: cars of two trains,
     * one of them off its rails. Read off each car's on-rails flag, which vanilla sets as the car
     * moves, not its rail memo, which still has a car that ran off its rails this tick on them.
     */
    public static boolean meet(AbstractMinecart cart, AbstractMinecart other) {
        return (!cart.isOnRails() || !other.isOnRails()) && ofAnotherTrain(cart, other);
    }

    /**
     * {@code movement}, a move of {@code cart} flown off a jump behind its train's leading car,
     * stopped where its box first meets a car of its own train, still flying or landed ahead of
     * it, an axis at a time in the order vanilla's move settles them. The cart keeps no speed
     * closing on the car that stopped it, so it flies on at that car's pace rather than pressing
     * into it every tick. Any other move, a derailment's among them, is left as it was.
     */
    public static Vec3 behindOwnTrain(AbstractMinecart cart, Vec3 movement) {
        if (movement.lengthSqr() == 0.0 || !TrainPlacer.flown(cart)) {
            return movement;
        }

        AABB box = cart.getBoundingBox();
        List<AbstractMinecart> mates = carts(cart, box.expandTowards(movement),
                near -> !ofAnotherTrain(cart, near));

        if (mates.isEmpty()) {
            return movement;
        }

        Vec3 allowed = Vec3.ZERO;
        Vec3 speed = cart.getDeltaMovement();

        for (Direction.Axis axis : Direction.axisStepOrder(movement)) {
            double distance = movement.get(axis);
            AABB fromBox = box.move(allowed);
            double reach = distance;
            AbstractMinecart stopper = null;

            // As vanilla's move does, this passes a box the cart is already inside, stranding none.
            for (AbstractMinecart mate : mates) {
                double mateReach = Shapes.create(mate.getBoundingBox())
                        .collide(axis, fromBox, reach);

                if (Math.abs(mateReach) < Math.abs(reach)) {
                    reach = mateReach;
                    stopper = mate;
                }
            }

            allowed = allowed.with(axis, reach);

            if (stopper != null) {
                double theirs = stopper.getDeltaMovement().get(axis);

                speed = speed.with(axis, distance > 0.0
                        ? Math.min(speed.get(axis), theirs) : Math.max(speed.get(axis), theirs));
            }
        }

        cart.setDeltaMovement(speed);

        return allowed;
    }

    /**
     * {@code movement}, a move of {@code cart} off its rails, less each axis that would take its
     * box deeper into a car of another train it is already inside, settled in the order vanilla's
     * move settles them. An axis that brings the two apart is kept, so the car can still get out.
     */
    public static Vec3 shallowerPart(AbstractMinecart cart, Vec3 movement) {
        if (movement.lengthSqr() == 0.0) {
            return movement;
        }

        // A car at rest on the ground, which vanilla's move would not move.
        if (cart.onGround() && movement.y() < 0.0 && movement.y() * movement.y() >= RESTING
                && movement.horizontalDistanceSqr() <= RESTING) {
            return movement;
        }

        AABB box = cart.getBoundingBox();
        List<AABB> insideBoxes = new ArrayList<>();

        for (AbstractMinecart other : carts(cart, box, near -> ofAnotherTrain(cart, near))) {
            if (depth(box, other.getBoundingBox()) > INSIDE) {
                insideBoxes.add(other.getBoundingBox());
            }
        }

        if (insideBoxes.isEmpty()) {
            return movement;
        }

        Vec3 allowed = Vec3.ZERO;

        for (Direction.Axis axis : Direction.axisStepOrder(movement)) {
            double distance = movement.get(axis);
            Vec3 tried = allowed.with(axis, distance);

            if (distance != 0.0 && !deeper(box.move(allowed), box.move(tried), insideBoxes)) {
                allowed = tried;
            }
        }

        return allowed;
    }

    /**
     * {@code setDown}, the set-down of {@code cart} from where its box was {@code fromBox} onto its
     * rail, or the stepper's lift of it onto a slope's line, cut short where the box first meets a
     * car of another train, as vanilla's move stops a box at a face, or cut to nothing if it would
     * take the box deeper into one it is inside.
     */
    public static Vec3 shortOfCars(AbstractMinecart cart, AABB fromBox, Vec3 setDown) {
        List<VoxelShape> solids = new ArrayList<>();

        for (AbstractMinecart other : carts(cart, fromBox.expandTowards(setDown),
                near -> ofAnotherTrain(cart, near))) {
            AABB otherBox = other.getBoundingBox();
            double depth = depth(fromBox, otherBox);

            if (depth <= INSIDE) {
                solids.add(Shapes.create(otherBox));
            } else if (depth(fromBox.move(setDown), otherBox) > depth) {
                return Vec3.ZERO;
            }
        }

        if (solids.isEmpty()) {
            return setDown;
        }

        // An axis at a time in vanilla's order, as Entity.collide settles a move, so a set-down
        // stopped on a roof still slides across onto the rail's line.
        Vec3 allowed = Vec3.ZERO;

        for (Direction.Axis axis : Direction.axisStepOrder(setDown)) {
            double distance = setDown.get(axis);

            if (distance != 0.0) {
                allowed = allowed.with(axis,
                        Shapes.collide(axis, fromBox.move(allowed), solids, distance));
            }
        }

        return allowed;
    }

    // Whether other is a car of a train other than cart's, a lone cart counting as a train.
    private static boolean ofAnotherTrain(AbstractMinecart cart, AbstractMinecart other) {
        return !Linkable.coupled(cart, other) && !TrainCoupling.sameTrain(cart, other);
    }

    // The carts cart can meet at all whose boxes reach into area and that pass test.
    private static List<AbstractMinecart> carts(
            AbstractMinecart cart, AABB area, Predicate<AbstractMinecart> test) {
        return cart.level().getEntitiesOfClass(AbstractMinecart.class, area.inflate(INSIDE),
                other -> other != cart && CartInteraction.participates(cart, other)
                        && test.test(other));
    }

    // Whether a box moved from fromBox to toBox stands deeper inside any of boxes than it did.
    private static boolean deeper(AABB fromBox, AABB toBox, List<AABB> boxes) {
        for (AABB other : boxes) {
            if (depth(toBox, other) > Math.max(depth(fromBox, other), INSIDE)) {
                return true;
            }
        }

        return false;
    }

    // How far box stands inside other: the least distance along one axis that parts them, zero
    // or less while they are apart.
    private static double depth(AABB box, AABB other) {
        return Math.min(Math.min(
                Math.min(box.maxX, other.maxX) - Math.max(box.minX, other.minX),
                Math.min(box.maxY, other.maxY) - Math.max(box.minY, other.minY)),
                Math.min(box.maxZ, other.maxZ) - Math.max(box.minZ, other.minZ));
    }
}
