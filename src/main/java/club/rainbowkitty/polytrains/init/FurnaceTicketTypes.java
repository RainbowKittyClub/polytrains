package club.rainbowkitty.polytrains.init;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.TicketType;

import club.rainbowkitty.polytrains.Polytrains;

/** Chunk ticket types registered by this mod. */
public final class FurnaceTicketTypes {

    // How long a ticket outlives its last refresh, in ticks.
    private static final long TIMEOUT = 20L;

    // All three flags, since loading alone leaves the cart's own chunk unticked and an otherwise
    // empty world stops ticking entities without the dimension kept active.
    /** Held by a burning furnace cart while {@code furnace_minecarts_load_chunks} is on. */
    public static final TicketType FURNACE_MINECART = Registry.register(
            BuiltInRegistries.TICKET_TYPE,
            Polytrains.id("furnace_minecart"),
            new TicketType(TIMEOUT, TicketType.FLAG_LOADING | TicketType.FLAG_SIMULATION
                    | TicketType.FLAG_KEEP_DIMENSION_ACTIVE));

    // Static-only; blocks instantiation.
    private FurnaceTicketTypes() {}

    /** Forces class initialization so the type lands in the registry before it freezes. */
    public static void init() {}
}
