package club.rainbowkitty.minecarttweaks.block;

import com.mojang.serialization.MapCodec;
import eu.pb4.polymer.blocks.api.BlockModelType;
import eu.pb4.polymer.blocks.api.MultiPolymerBlockModel;
import eu.pb4.polymer.blocks.api.PolymerBlockResourceUtils;
import eu.pb4.polymer.blocks.api.PolymerTexturedBlock;
import net.fabricmc.fabric.api.networking.v1.context.PacketContext;
import org.jspecify.annotations.Nullable;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.vehicle.minecart.AbstractMinecart;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseRailBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.Vec3;

import club.rainbowkitty.minecarttweaks.MinecartTweaks;
import club.rainbowkitty.minecarttweaks.rail.RailPath;

/** A rail block that allows carts to pass through in both axes without turning. */
public class JunctionRailBlock extends BaseRailBlock implements PolymerTexturedBlock {
    // Tripwire is the only borrowable model that is flat, non-colliding and cutout-rendered, as a
    // rail needs. The multi form is deliberate: with a single model Polymer emits a blockstate file
    // holding only the borrowed state, which blanks every other tripwire state on the client.
    private static final BlockState POLYMER_STATE = PolymerBlockResourceUtils.requestBlock(
            BlockModelType.TRIPWIRE_FLAT,
            MultiPolymerBlockModel.of().with(MinecartTweaks.id("block/junction_rail")));

    /** The rail shape property; only the two straight shapes, since a junction never bends. */
    public static final EnumProperty<RailShape> SHAPE = EnumProperty.create(
            "shape",
            RailShape.class,
            shape -> shape == RailShape.NORTH_SOUTH || shape == RailShape.EAST_WEST);

    /** Codec for block state serialization. */
    public static final MapCodec<JunctionRailBlock> CODEC = simpleCodec(JunctionRailBlock::new);

    /** Constructs a junction rail block with the given block behaviour properties. */
    public JunctionRailBlock(BlockBehaviour.Properties properties) {
        super(true, properties);
        registerDefaultState(defaultBlockState()
                .setValue(SHAPE, RailShape.NORTH_SOUTH)
                .setValue(WATERLOGGED, false));
    }

    /** Returns the straight rail shape selected for the given nonzero horizontal movement. */
    public static RailShape shapeFor(Vec3 movement) {
        return Direction.getApproximateNearest(movement.x(), 0.0, movement.z()).getAxis()
                == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;
    }

    /**
     * Lays the junction rail under {@code cart} along the axis the cart is travelling, so that it
     * passes straight through rather than being turned. Does nothing if the cart is stopped or is
     * not standing on one.
     */
    public static void steer(Level level, AbstractMinecart cart) {
        lay(level, cart.getCurrentBlockPosOrRailBelow(), cart.getDeltaMovement());
    }

    /**
     * Lays the junction rail {@code cart} is about to cross from {@code pos}, so that it is already
     * pointing the right way when the cart gets there.
     *
     * <p>Vanilla re-reads the shape off the blockstate once per rail block crossed, inside a loop
     * that runs several times in a tick, while a cart is only ever standing on one block when a
     * tick begins. Laying that one is enough at walking pace and nothing like enough at speed —
     * every junction crossed after the first is read however it was last left, and turns the cart.
     * A train used to paper over this, each car laying the block it stood on, until the placer left
     * only the leading car reading rails at all.
     */
    public static void steerAhead(Level level, AbstractMinecart cart, BlockPos pos) {
        BlockPos next = RailPath.ahead(level, pos, cart.getDeltaMovement());

        if (next != null) {
            // Along the step onto it rather than the cart's heading: vanilla turns the delta onto
            // the rail inside the step, so a junction just past a bend was laid on the old axis.
            lay(level, next, Vec3.atLowerCornerOf(next.subtract(pos)).horizontal());
        }
    }

    // Points the junction at pos along movement, if that is what is there and it is not already.
    private static void lay(Level level, BlockPos pos, Vec3 movement) {
        if (movement.horizontalDistanceSqr() <= 0) {
            return;
        }

        BlockState state = level.getBlockState(pos);

        if (!(state.getBlock() instanceof JunctionRailBlock)) {
            return;
        }

        RailShape wanted = shapeFor(movement);

        // Vanilla reads the rail shape straight off the blockstate at a dozen inlined call sites,
        // so the junction has to be a real world edit. Writing only on a change, and without
        // neighbour updates, keeps adjacent rails from re-orienting every tick.
        if (state.getValue(SHAPE) != wanted) {
            level.setBlock(pos, state.setValue(SHAPE, wanted), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    public Property<RailShape> getShapeProperty() {
        return SHAPE;
    }

    /**
     * Returns the borrowed vanilla state clients are shown in place of this block. Both shapes
     * share one model, so the client never sees the shape flip that carts trigger.
     */
    @Override
    public BlockState getPolymerBlockState(BlockState state, @Nullable PacketContext context) {
        return POLYMER_STATE;
    }

    // Block codec, required of every BaseRailBlock subclass.
    @Override
    protected MapCodec<? extends BaseRailBlock> codec() {
        return CODEC;
    }

    // Declares the rail shape and waterlogging as this block's state properties.
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(SHAPE, WATERLOGGED);
    }
}
