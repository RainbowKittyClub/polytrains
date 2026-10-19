package club.rainbowkitty.polytrains.block;

import java.util.EnumMap;
import java.util.Map;

//? if <26.3
import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.MultiPolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.level.material.Fluids;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.polytrains.Polytrains;
import club.rainbowkitty.polytrains.rail.RailPath;
import club.rainbowkitty.polytrains.rail.TrackRibbon;
import club.rainbowkitty.polytrains.train.TrainCoupling;
import club.rainbowkitty.polytrains.train.TrainMeter;

/**
 * A rail that curves a car off its right-hand edge — backward toward where the placer stood by
 * default, forward under a redstone signal. {@link #FACING} is that forward direction. A car
 * running along the forward axis passes straight through the half the switch is not joined to.
 */
public class SwitchedRailBlock extends BaseRailBlock implements PolymerTexturedBlock {

    /** The forward direction — the way the placer was looking; the branch leaves the right edge. */
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;

    /** Whether a redstone signal joins the right edge to the forward half rather than the back. */
    public static final BooleanProperty POWERED = BlockStateProperties.POWERED;

    /** Set by a wrench to throw the branch against the redstone-driven default. */
    public static final BooleanProperty REVERSED = BooleanProperty.create("reversed");

    /** The live shape: a diverging curve at rest, briefly the straight run during a passthrough. */
    public static final EnumProperty<RailShape> SHAPE = EnumProperty.create(
            "shape", RailShape.class,
            shape -> switch (shape) {
                case NORTH_SOUTH, EAST_WEST, SOUTH_EAST, SOUTH_WEST, NORTH_WEST, NORTH_EAST -> true;
                default -> false;
            });

    // 26.3 dropped block codecs.
    //? if <26.3 {
    /** Codec for block state serialization. */
    public static final MapCodec<SwitchedRailBlock> CODEC = simpleCodec(SwitchedRailBlock::new);
    //?}

    // Ticks the switch is held straight for a passthrough before it is asked to settle back.
    private static final int SETTLE_DELAY = 3;

    // Below this a cart is treated as stopped and steer() leaves its facing alone.
    private static final double MOVING_EPSILON = 1.0E-6;

    // How far short of a closed switch's edge a car is stopped: vanilla aims each step 1.0E-5 past
    // an edge, so from here its next step reads as no progress and it halts rather than loops.
    private static final double CLOSED_MARGIN = 1.0E-4;

    // Both textures are drawn with the bottom edge toward the placer, so each is rotated half a
    // turn from FACING's own y-rotation to bring that edge round to FACING's opposite.
    private static final Map<Direction, BlockState> BRANCH_BACK = new EnumMap<>(Direction.class);

    // Polymer states for the forward branch when powered.
    private static final Map<Direction, BlockState> BRANCH_FORWARD = new EnumMap<>(Direction.class);

    static {
        for (Direction facing : Direction.Plane.HORIZONTAL) {
            int yRotation = ((int) facing.toYRot() + 180) % 360;
            BRANCH_BACK.put(facing, borrow("switch_rail_unpowered", yRotation));
            BRANCH_FORWARD.put(facing, borrow("switch_rail_powered", yRotation));
        }
    }

    /** Constructs a switched rail block with the given block behaviour properties. */
    public SwitchedRailBlock(BlockBehaviour.Properties properties) {
        // Not a "straight" rail: it holds a curve, and must not broadcast straight-rail updates.
        super(false, properties);
        registerDefaultState(defaultBlockState()
                .setValue(FACING, Direction.NORTH)
                .setValue(POWERED, false)
                .setValue(REVERSED, false)
                .setValue(SHAPE, restingShape(Direction.NORTH, false))
                .setValue(WATERLOGGED, false));
    }

    // ---- geometry ----

    /** The edge the branch leaves by: the right hand of a car travelling toward {@code facing}. */
    public static Direction rightEdge(Direction facing) {
        return facing.getClockWise();
    }

    /** The curve the switch rests at: the right edge joined forward when thrown, else back. */
    public static RailShape restingShape(Direction facing, boolean forward) {
        return curve(rightEdge(facing), forward ? facing : facing.getOpposite());
    }

    /**
     * The run this switch lays for a cart approaching along {@code travel}: the straight run for
     * one entering by the open end of the forward axis, the resting branch otherwise. What
     * {@link #steer} asks for as a cart arrives, which a switch held under another car or a train
     * refuses; what a switch actually holds is its {@link #SHAPE}.
     */
    public static RailShape shapeFor(BlockState state, Direction travel) {
        Direction facing = state.getValue(FACING);
        boolean forward = divergesForward(state);

        return travel.getOpposite() == openEnd(facing, forward)
                ? straightShape(facing)
                : restingShape(facing, forward);
    }

    /**
     * The run across this switch that a walk over the track should read for an approach along
     * {@code movement}, taken without regard to which way the switch is thrown.
     *
     * <p>A switch joins three ends and a {@link RailShape} holds two, so the pair it is sitting at
     * says which way a car leaves rather than whether the rails connect. A walk asking whether two
     * carts are on one run of track wants the track: entering along the switch's own axis runs
     * straight across however it is thrown, and from the branch edge there is only the curve.
     */
    public static RailShape linkShape(BlockState state, Vec3 movement) {
        Direction facing = state.getValue(FACING);
        Direction travel = Direction.getApproximateNearest(movement.x(), 0.0, movement.z());

        return travel.getAxis() == facing.getAxis()
                ? straightShape(facing)
                : curve(rightEdge(facing), facing);
    }

    /**
     * Orients the switch {@code cart} is riding, so vanilla movement reads the shape this switch's
     * facing, signal and the car's approach call for.
     */
    public static void steer(ServerLevel level, AbstractMinecart cart) {
        Vec3 movement = cart.getDeltaMovement();
        if (movement.horizontalDistanceSqr() <= MOVING_EPSILON) {
            return;
        }

        BlockPos pos = cart.getCurrentBlockPosOrRailBelow();

        orient(level, pos, entryEdge(pos, cart.position(), movement).getOpposite(), cart);
    }

    /**
     * Orients the switch at {@code nextPos}, the one {@code cart} is about to cross onto from
     * {@code pos}, so that it is already laid for this car's approach by the time vanilla reads it.
     *
     * <p>Vanilla reads each rail block's shape as it reaches it, several of them to a tick, so one
     * switch laid per tick covers the first and none of the rest. The approach is taken from the
     * step onto the block rather than the car's heading, which vanilla turns onto the rail only
     * once the step is under way — so a switch just past a bend used to be read the pre-bend way.
     */
    public static void orientAhead(
            ServerLevel level, AbstractMinecart cart, BlockPos pos, BlockPos nextPos) {
        int eastDelta = nextPos.getX() - pos.getX();
        int southDelta = nextPos.getZ() - pos.getZ();

        if (eastDelta != 0 || southDelta != 0) {
            orient(level, nextPos,
                    Direction.getApproximateNearest(eastDelta, 0.0, southDelta), cart);
        }
    }

    /**
     * Orients the switch at {@code pos} for {@code cart} arriving on it travelling {@code travel},
     * as {@link #orientAhead} does for a car stepping onto it: for a car the placer carries, which
     * steps onto no rail of its own.
     */
    public static void steerOnto(
            ServerLevel level, BlockPos pos, Direction travel, AbstractMinecart cart) {
        orient(level, pos, travel, cart);
    }

    /**
     * {@code step}, cut short where it would carry {@code cart}'s box across the edge of the rail
     * at {@code railPos}, laid to {@code shape}, into a switch laid against that edge.
     *
     * <p>A switch another car rides, or a train lies across, keeps its curve against a car that
     * arrives by the end the curve does not join. Let in, vanilla turns that car onto a leg it did
     * not come by, into whatever is crossing, and it stands there with no rail back out. Its box
     * is kept out as well as its middle: a car poking into the switch fouls the curve through it.
     */
    public static Vec3 shortOfClosed(
            AbstractMinecart cart, BlockPos railPos, RailShape shape, Vec3 step) {
        Vec3 runDirection = step.horizontal();

        if (runDirection.lengthSqr() <= MOVING_EPSILON
                || !(cart.level() instanceof ServerLevel level)) {
            return step;
        }

        var exits = AbstractMinecart.exits(shape);
        Vec3i exit = Vec3.atLowerCornerOf(exits.getFirst()).horizontal().dot(runDirection)
                >= Vec3.atLowerCornerOf(exits.getSecond()).horizontal().dot(runDirection)
                ? exits.getFirst() : exits.getSecond();
        Direction travel = Direction.getApproximateNearest(exit.getX(), 0.0, exit.getZ());
        BlockPos aheadPos = RailPath.ahead(level, railPos, runDirection);
        BlockPos nextPos = aheadPos == null ? null : closedSwitch(level, aheadPos, travel);
        double across = exit.getX() != 0 ? step.x() * exit.getX() : step.z() * exit.getZ();

        if (nextPos == null || across <= 0.0) {
            return step;
        }

        // Asked first, as a car stepping onto it asks, in case nothing holds it: laid for this car,
        // it lets the car on.
        orient(level, nextPos, travel, cart);

        if (openTo(level.getBlockState(nextPos), travel.getOpposite())) {
            return step;
        }

        // How far the box's leading face may still go towards the edge, and the step's share of it.
        double toEdge = exit.getX() != 0
                ? (exit.getX() > 0 ? nextPos.getX() - cart.getBoundingBox().maxX
                        : cart.getBoundingBox().minX - railPos.getX())
                : (exit.getZ() > 0 ? nextPos.getZ() - cart.getBoundingBox().maxZ
                        : cart.getBoundingBox().minZ - railPos.getZ());

        return step.scale(Math.clamp((toEdge - CLOSED_MARGIN) / across, 0.0, 1.0));
    }

    /**
     * Whether the switch in {@code state} is laid with an end at {@code edge} of its block, so a
     * car can come on or go off it there.
     */
    public static boolean openTo(BlockState state, Direction edge) {
        var exits = AbstractMinecart.exits(state.getValue(SHAPE));

        return exits.getFirst().getX() == edge.getStepX()
                        && exits.getFirst().getZ() == edge.getStepZ()
                || exits.getSecond().getX() == edge.getStepX()
                        && exits.getSecond().getZ() == edge.getStepZ();
    }

    /**
     * Whether the switch at {@code pos} keeps its shape against a car asking for another: a cart
     * stands on it or a train lies across it.
     */
    public static boolean held(Level level, BlockPos pos) {
        return occupied(level, pos);
    }

    // Pos, if the block there is a switch laid against a car arriving travelling travel; null
    // otherwise.
    private static @Nullable BlockPos closedSwitch(Level level, BlockPos pos, Direction travel) {
        BlockState state = level.getBlockState(pos);

        return state.getBlock() instanceof SwitchedRailBlock
                && !openTo(state, travel.getOpposite()) ? pos : null;
    }

    @Override
    public Property<RailShape> getShapeProperty() {
        return SHAPE;
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Level level = context.getLevel();
        BlockPos pos = context.getClickedPos();
        Direction facing = context.getHorizontalDirection();
        boolean powered = level.hasNeighborSignal(pos);

        return defaultBlockState()
                .setValue(FACING, facing)
                .setValue(POWERED, powered)
                .setValue(SHAPE, restingShape(facing, powered))
                .setValue(WATERLOGGED, level.getFluidState(pos).is(Fluids.WATER));
    }

    /**
     * Flips a switch to its other branch, from wherever its redstone signal has left it.
     *
     * @return whether it was thrown; false while a cart stands on the switch, since the curve
     *         under a car is the one it entered on and swapping it mid-crossing derails the car
     */
    public static boolean toggle(ServerLevel level, BlockPos pos, BlockState state) {
        boolean reversed = !state.getValue(REVERSED);
        boolean forward = state.getValue(POWERED) != reversed;
        RailShape thrown = restingShape(state.getValue(FACING), forward);

        if (occupied(level, pos)) {
            TrainMeter.switched(level, pos, state.getValue(SHAPE), thrown, false,
                    "wrench refused while occupied");
            return false;
        }

        TrainMeter.switched(level, pos, state.getValue(SHAPE), thrown, true, "thrown by a wrench");
        level.setBlock(
                pos,
                state.setValue(REVERSED, reversed).setValue(SHAPE, thrown),
                Block.UPDATE_CLIENTS);
        return true;
    }

    /** The borrowed vanilla state a client is shown; the passthrough keeps the resting look. */
    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        Map<Direction, BlockState> model =
                divergesForward(state) ? BRANCH_FORWARD : BRANCH_BACK;
        return model.get(state.getValue(FACING));
    }

    // Whether the branch is thrown to the forward half. A redstone signal throws it forward; a
    // wrench (REVERSED) flips it from wherever the signal leaves it.
    private static boolean divergesForward(BlockState state) {
        return state.getValue(POWERED) != state.getValue(REVERSED);
    }

    // The straight run along the forward axis, which a passthrough car takes.
    private static RailShape straightShape(Direction facing) {
        return facing.getAxis() == Direction.Axis.Z ? RailShape.NORTH_SOUTH : RailShape.EAST_WEST;
    }

    // The end of the forward axis the switch is not joined to, so a car from there passes straight.
    private static Direction openEnd(Direction facing, boolean forward) {
        return forward ? facing.getOpposite() : facing;
    }

    // The flat rail shape whose two ends are endA and endB.
    private static RailShape curve(Direction endA, Direction endB) {
        boolean hasNorth = endA == Direction.NORTH || endB == Direction.NORTH;
        boolean hasSouth = endA == Direction.SOUTH || endB == Direction.SOUTH;
        boolean hasEast = endA == Direction.EAST || endB == Direction.EAST;
        if (hasNorth && hasSouth) {
            return RailShape.NORTH_SOUTH;
        }
        if (hasEast && (endA == Direction.WEST || endB == Direction.WEST)) {
            return RailShape.EAST_WEST;
        }
        if (hasSouth && hasEast) {
            return RailShape.SOUTH_EAST;
        }
        if (hasSouth) {
            return RailShape.SOUTH_WEST;
        }
        return hasEast ? RailShape.NORTH_EAST : RailShape.NORTH_WEST;
    }

    // The edge a car inside the block at pos came in by: where the line back along its movement
    // leaves the block. Its heading alone is diagonal on a curve and reads as whichever axis wins.
    private static Direction entryEdge(BlockPos pos, Vec3 position, Vec3 movement) {
        double eastFraction = position.x() - pos.getX();
        double southFraction = position.z() - pos.getZ();
        double backToWestOrEast = movement.x() == 0.0 ? Double.POSITIVE_INFINITY
                : (movement.x() > 0.0 ? eastFraction : 1.0 - eastFraction)
                        / Math.abs(movement.x());
        double backToNorthOrSouth = movement.z() == 0.0 ? Double.POSITIVE_INFINITY
                : (movement.z() > 0.0 ? southFraction : 1.0 - southFraction)
                        / Math.abs(movement.z());

        if (backToWestOrEast < backToNorthOrSouth) {
            return movement.x() > 0.0 ? Direction.WEST : Direction.EAST;
        }

        return movement.z() > 0.0 ? Direction.NORTH : Direction.SOUTH;
    }

    // Points one switch at a car travelling the given way; any other block at pos is left alone. A
    // switch some other car is riding, or a train is lying across, keeps its shape, so the car is
    // routed by whatever it finds on arrival rather than throwing the curve out from under.
    private static void orient(
            ServerLevel level, BlockPos pos, Direction travel, AbstractMinecart cart) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof SwitchedRailBlock switchRail)
                || occupiedByOther(level, pos, cart)) {
            return;
        }

        RailShape resting = restingShape(state.getValue(FACING), divergesForward(state));

        // A car entering from the open end of the forward axis runs straight across; the branch
        // and the joined half both follow the resting curve.
        RailShape wanted = shapeFor(state, travel);
        RailShape current = state.getValue(SHAPE);

        if (current != wanted) {
            // The train's own leading car included: back on the switch after a reversal it has to
            // retrace the shape its ribbon recorded, which is the one held, not be routed anew.
            if (TrainCoupling.crossedByTrain(level, pos)) {
                if (TrainMeter.enabled()) {
                    TrainMeter.switched(level, pos, current, wanted, false,
                            "held under a train lying across it, asked by a cart heading "
                                    + travel.getSerializedName());
                }

                return;
            }

            if (TrainMeter.enabled()) {
                TrainMeter.switched(level, pos, current, wanted, true,
                        "steered by a cart heading " + travel.getSerializedName());
            }

            level.setBlock(pos, state.setValue(SHAPE, wanted), Block.UPDATE_CLIENTS);
        }
        // Keep a settle pending for as long as a car is crossing straight, so the switch does not
        // return to its curve mid-passthrough. A car still on it re-arms this next tick.
        if (wanted != resting && !level.getBlockTicks().hasScheduledTick(pos, switchRail)) {
            level.scheduleTick(pos, switchRail, SETTLE_DELAY);
        }
    }

    // ---- block behaviour ----

    //? if <26.3 {
    @Override
    protected MapCodec<? extends BaseRailBlock> codec() {
        return CODEC;
    }
    //?}

    // Follows the redstone signal, snapping to the matching resting curve, or deferring that curve
    // while a car is crossing. Called from BaseRailBlock.neighborChanged.
    @Override
    protected void updateState(BlockState state, Level level, BlockPos pos, Block neighbourBlock) {
        boolean powered = level.hasNeighborSignal(pos);
        if (powered == state.getValue(POWERED)) {
            return;
        }

        BlockState signalled = state.setValue(POWERED, powered);
        RailShape resting = restingShape(state.getValue(FACING), divergesForward(signalled));

        // The signal belongs to the circuit, so it is recorded whatever stands on the switch; only
        // the curve waits, for the settle to lay it over once the car it would derail has gone.
        if (occupied(level, pos)) {
            TrainMeter.switched(level, pos, state.getValue(SHAPE), resting, false,
                    "signal changed while occupied");
            level.setBlock(pos, signalled, Block.UPDATE_CLIENTS);
            settleLater(level, pos);
            return;
        }

        TrainMeter.switched(level, pos, state.getValue(SHAPE), resting, true, "signal changed");
        level.setBlock(pos, signalled.setValue(SHAPE, resting), Block.UPDATE_CLIENTS);
    }

    // Has the trains near a switch that has just changed shape forget it past their own cars,
    // before a leading car that steered it steps on. Every change of shape passes through here.
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState,
            boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);

        boolean reshaped = oldState.is(this) && oldState.getValue(SHAPE) != state.getValue(SHAPE);

        // Counted first, so the walk below records the count it has seen. A new switch counts as
        // well: track recorded across the rail it replaced can be thrown by it.
        if (!level.isClientSide() && (reshaped || !oldState.is(this))) {
            TrackRibbon.switchChanged();
        }

        if (reshaped) {
            TrainCoupling.forgetThrown(level, pos);
        }
    }

    // Lays the resting curve on once the car that was crossing has gone, whether it was held off by
    // a passthrough or by a signal change. Its schedulers keep at most one of these pending.
    @Override
    protected void tick(BlockState state, ServerLevel level, BlockPos pos, RandomSource random) {
        RailShape resting = restingShape(state.getValue(FACING), divergesForward(state));
        if (state.getValue(SHAPE) == resting) {
            return;
        }
        if (occupied(level, pos)) {
            level.scheduleTick(pos, this, SETTLE_DELAY);
        } else {
            TrainMeter.switched(level, pos, state.getValue(SHAPE), resting, true,
                    "laying its resting curve");
            level.setBlock(pos, state.setValue(SHAPE, resting), Block.UPDATE_CLIENTS);
        }
    }

    // Whether a cart still sits on the switch, or a whole train is lying across it, in which case
    // a change of curve has to wait. The train question is asked separately because a cart is only
    // inside this block for part of a tick at speed and the gap between two cars clears it
    // altogether — so a train reads as a run of brief absences and the curve goes over mid-train.
    private static boolean occupied(Level level, BlockPos pos) {
        return occupiedByOther(level, pos, null) || TrainCoupling.crossedByTrain(level, pos);
    }

    // As occupied, discounting one cart: a car steering a switch is already standing on it by the
    // time it says so, and would otherwise read as the obstacle that stops it being steered.
    private static boolean occupiedByOther(
            Level level, BlockPos pos, @Nullable AbstractMinecart steering) {
        return !level.getEntitiesOfClass(
                AbstractMinecart.class, new AABB(pos), cart -> cart != steering).isEmpty();
    }

    // Asks the switch to reconsider its shape later, so a curve refused while it was busy is not
    // simply lost. The guard keeps at most one pending, as orient's does.
    private void settleLater(Level level, BlockPos pos) {
        if (!level.getBlockTicks().hasScheduledTick(pos, this)) {
            level.scheduleTick(pos, this, SETTLE_DELAY);
        }
    }

    // A switch keeps the shape its facing and signal give it; neighbours never bend it.
    @Override
    protected BlockState updateDir(Level level, BlockPos pos, BlockState state, boolean first) {
        return state;
    }

    // Turns the switch with a rotated structure, carrying its diverge side along.
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return reface(state, rotation.rotate(state.getValue(FACING)));
    }

    // Reflects the switch with a mirrored structure, carrying its diverge side along.
    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return reface(state, mirror.mirror(state.getValue(FACING)));
    }

    // Turns the switch to face a new way, carrying which side it diverges to with it.
    private static BlockState reface(BlockState state, Direction facing) {
        return state.setValue(FACING, facing)
                .setValue(SHAPE, restingShape(facing, divergesForward(state)));
    }

    // Declares facing, signal, diverge side, shape and waterlogging as state properties.
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, POWERED, REVERSED, SHAPE, WATERLOGGED);
    }

    // Reserves one Polymer block state showing the named model at the given yaw.
    private static BlockState borrow(String modelName, int yRotation) {
        return PolymerBlockResourceUtils.requestBlock(
                BlockModelType.TRIPWIRE_FLAT,
                MultiPolymerBlockModel.of().with(
                        PolymerBlockModel.of(
                                Polytrains.id("block/" + modelName), 0, yRotation)));
    }
}
