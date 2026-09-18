package club.rainbowkitty.minecarttweaks.train;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.block.PoweredRailBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.rail.RailPath;
import club.rainbowkitty.minecarttweaks.rail.TrackRibbon;

/**
 * What the coupling is achieving, measured against the track instead of against itself.
 *
 * <p>Spacing is inter-car distance along the rails, walked by {@link RailPath}, which knows nothing
 * about the ribbon and nothing about how the cars reached where they are. That independence is the
 * point. A counter comparing a car against the ribbon's own prediction measures open-loop error
 * today and a control loop against its own setpoint once the ribbon drives the cars — at which
 * point it reads near zero whether or not the train looks right. This one keeps meaning something.
 *
 * <p>Mass rides along on the same line. It is not a measurement of anything — the model is asked
 * and it answers — but it is the one number that says which force model a reading was taken under,
 * and a coast or grade figure recorded without that is a figure nobody can place afterwards.
 *
 * <p>It re-walks the track rather than borrowing the paths the coupling already computed, for the
 * same reason: an oracle that shares its intermediates with the thing it checks is not an oracle.
 * The cost is paid only while {@code DEBUG} is on, and then only once a second.
 */
public final class TrainMeter {

    private static final Logger LOGGER = LogUtils.getLogger();

    // Once a second. Per-tick output is unreadable and the numbers barely move between ticks.
    private static final int PERIOD = 20;

    // How far to walk before giving up on a pair. Past the breaking distance the coupling would
    // have severed them anyway, so a longer walk measures a train that no longer exists.
    private static final double REACH = 2.0;

    private TrainMeter() {}

    /**
     * Logs how far {@code train} is from the spacing it is aiming at, and how far its cars are from
     * where its ribbon says they should be. Measures nothing and costs nothing unless {@code DEBUG}
     * is on. Nothing here moves a cart or touches the ribbon.
     */
    public static void measure(ServerLevel level, Train train) {
        if (!LOGGER.isDebugEnabled() || level.getGameTime() % PERIOD != 0
                || train.size() < 2) {
            return;
        }

        double pitch = TrainCoupling.targetSpacing();
        List<AbstractMinecart> cars = train.cars();
        double limit = TrainCoupling.maxLinkDistance() * REACH;
        double worstGap = 0.0;
        double totalGap = 0.0;
        int gaps = 0;

        for (int i = 1; i < cars.size(); i++) {
            RailPath path = RailPath.between(cars.get(i - 1), cars.get(i), limit);

            if (path == null) {
                continue;
            }

            double error = path.distance() - pitch;
            worstGap = Math.max(worstGap, Math.abs(error));
            totalGap += error;
            gaps++;
        }

        // Not round(0.0): a train with every car off the rails measures no pair at all, and a
        // spacing of 0.00 printed for that reads as a train holding its pitch perfectly.
        LOGGER.debug(
                "train cars={} mass={} spacing measured={}/{} worst={} mean={} "
                        + "| speed={} rail={} turn={} | {}",
                cars.size(), round(TrainCoupling.mass(cars.getFirst())),
                gaps, cars.size() - 1,
                gaps == 0 ? "n/a" : round(worstGap),
                gaps == 0 ? "n/a" : round(totalGap / gaps),
                round(TrainCoupling.drivenSpeed(cars.getFirst())), underLeader(level, train),
                round(train.turn()), ribbonReport(train, pitch));
    }

    // What the leading car is standing on, so an acceleration reading says what was accelerating
    // it. A train reads as slow for two very different reasons — too little push, or too much drag
    // — and which rail it is over is most of the difference between them.
    private static String underLeader(ServerLevel level, Train train) {
        AbstractMinecart leader = train.leader();

        if (leader == null) {
            return "none";
        }

        BlockState state = level.getBlockState(leader.getCurrentBlockPosOrRailBelow());
        String name = state.getBlock().getName().getString();

        return state.hasProperty(PoweredRailBlock.POWERED)
                ? name + (state.getValue(PoweredRailBlock.POWERED) ? "(on)" : "(off)") : name;
    }

    // How far each car is from where the ribbon puts it, driving the offsets off the leading car.
    // Closed on a seated train — placed at these very arcs — so only the spacing above judges one.
    private static String ribbonReport(Train train, double pitch) {
        TrackRibbon ribbon = train.ribbon();
        AbstractMinecart leader = train.leader();

        if (leader == null) {
            return "ribbon nodes=" + ribbon.size() + " (no leading end)" + churn(ribbon);
        }

        Double leadArc = ribbon.arcOf(leader);

        if (leadArc == null) {
            return "ribbon nodes=" + ribbon.size() + " (leader off ribbon)" + churn(ribbon);
        }

        double worst = 0.0;
        int checked = 0;

        List<AbstractMinecart> behind = new ArrayList<>(train.cars());

        if (behind.getLast() == leader) {
            Collections.reverse(behind);
        }

        // Which way along the ribbon the rest of the train lies, taken from where the second car
        // actually is. A trim that has run further at one end than the other leaves the leader
        // nearer one terminal without that saying anything about which way it is facing.
        Double nextArc = ribbon.arcOf(behind.get(1));
        double step = nextArc == null
                ? (leadArc - ribbon.from() >= ribbon.to() - leadArc ? -pitch : pitch)
                : Math.copySign(pitch, nextArc - leadArc);

        for (int i = 1; i < behind.size(); i++) {
            Vec3 predicted = ribbon.positionAt(leadArc + i * step);

            if (predicted == null) {
                continue;
            }

            worst = Math.max(worst,
                    predicted.horizontal().distanceTo(behind.get(i).position().horizontal()));
            checked++;
        }

        return "ribbon nodes=" + ribbon.size()
                + " span=" + round(ribbon.to() - ribbon.from())
                + " checked=" + checked + " worst=" + round(worst) + churn(ribbon);
    }

    // What the ribbon has been through to reach the state above. Resets stuck at zero is the whole
    // point of recording track by arc rather than rebuilding it; revisits climbing instead says the
    // cars are reporting from the middle, which is expected and costs nothing.
    private static String churn(TrackRibbon ribbon) {
        return " resets=" + ribbon.resets() + " revisits=" + ribbon.revisits();
    }

    // Two decimals, which is finer than any of these numbers is meaningful to.
    private static String round(double value) {
        return String.format("%.2f", value);
    }
}
