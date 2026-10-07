package club.rainbowkitty.polytrains.train;

import java.util.List;

import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.entity.vehicle.minecart.MinecartFurnace;

import club.rainbowkitty.polytrains.init.EntityAttachments;
import club.rainbowkitty.polytrains.rule.ModGameRules;

/**
 * The brake lever beside every furnace cart's furnace, thrown and released for all the engines of
 * a train at once.
 *
 * <p>A thrown lever brakes its own engine as one car standing on an unpowered powered rail does,
 * and like such a rail holds the train, which parks every engine of it: see
 * {@link club.rainbowkitty.polytrains.rail.RailBrake#brakes}. So a train brakes by a rail's
 * worth for each engine it has, and coasts to a stop on that rather than on its engines' drive.
 */
public final class BrakeLevers {

    // Vanilla's lever click, at the volume and the two pitches LeverBlock plays it at.
    private static final float CLICK_VOLUME = 0.3f;
    private static final float THROWN_PITCH = 0.6f;
    private static final float RELEASED_PITCH = 0.5f;

    // How far above the cart's position the click is played: the middle of the lever, which stands
    // from about 0.37 to 1.0 (ChooChooPartsDisplay).
    private static final double CLICK_HEIGHT = 0.7;

    // Static-only; blocks instantiation.
    private BrakeLevers() {}

    /**
     * Throws {@code engine}'s lever if it is released, or releases it if it is thrown, and sets
     * every engine of its train the same way — so a train whose engines disagreed, two trains with
     * levers set differently having been coupled, agrees again from the lever clicked.
     *
     * @return whether the levers are now thrown
     */
    public static boolean toggle(AbstractMinecart engine) {
        boolean thrown = !EntityAttachments.leverSet(engine);

        setAll(trainOf(engine), thrown);
        engine.level().playSound(null, engine.getX(), engine.getY() + CLICK_HEIGHT, engine.getZ(),
                SoundEvents.LEVER_CLICK, SoundSource.NEUTRAL, CLICK_VOLUME,
                thrown ? THROWN_PITCH : RELEASED_PITCH);
        return thrown;
    }

    /**
     * Throws the lever of every engine in {@code cart}'s train if any one of them is thrown. For a
     * coupling that has just joined two trains, or brought an engine into one: an engine added to
     * a parked train is parked with it. A coupling never releases a brake — that takes a click.
     */
    public static void matchCoupled(AbstractMinecart cart) {
        List<AbstractMinecart> cars = trainOf(cart);

        for (AbstractMinecart car : cars) {
            if (EntityAttachments.leverSet(car)) {
                setAll(cars, true);
                return;
            }
        }
    }

    // The cars a lever answers for: the whole train, or the cart alone when it is not in one.
    private static List<AbstractMinecart> trainOf(AbstractMinecart cart) {
        return cart.hasLink() && ModGameRules.trainsEnabled(cart.level())
                ? Train.of(cart).cars()
                : List.of(cart);
    }

    // Sets every furnace cart among cars the same way.
    private static void setAll(List<AbstractMinecart> cars, boolean thrown) {
        for (AbstractMinecart car : cars) {
            if (car instanceof MinecartFurnace) {
                EntityAttachments.setLever(car, thrown);
            }
        }
    }
}
