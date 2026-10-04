#Kali's Reworked Rails

Inspired by Cammie's Minecart Tweaks: A server-side Fabric mod that makes rails not suck.

## Features

### Furnace Cart Improvements
The furnace cart now accepts any fuel that would work in a normal furnace, including lava!
You can keep dumping fuel into it (to the lava-bucket maximum) to make it run longer.

### Coal Tender Minecart
A minecart and a block of coal make a coal tender minecart: a cart that holds five slots of coal and
charcoal, and nothing else. Couple one straight to a furnace cart and, once you've lit the engine by hand, the
tender keeps it burning at exactly the rate it uses fuel — no topping up, no burn-time cap. The coal
you can see in the tender goes down as it empties. Hoppers can fill it, so a hopper over the track at
a station makes a coaling stop.

### Trains
Link carts together with iron chains to form a train. All carts move together,
but longer trains are harder to move. Use furnace minecarts to power them.
The more furnace carts are in a train, the faster it accelerates.

### Collision
Trains break apart when they collide if they're going fast enough.
Mobs and players can get run over now too!

### More Rail Types
- **Junction rail** — a fixed crossing, letting two lines cross over eachother without joining.
- **Switch rail** — a t-shaped rail that switches between two directions with redstone.
- **Wrench** — a new tool used to configure things like the switch rail.

### Cheaper Rails
All rails use nuggets instead of ingots to make minecarts a viable early to mid-game method of transportation.

## Configuration

Everything is done with `minecarttweaks.` gamerules:

| Key [default value] | Description |
| --- | --- |
| `trains_enabled [true]` | Whether linking, following and chains work. Only exists if `minecart_improvements` is on. |
| `max_train_length [128]` | Maximum number of carts that can be linked together. |
| `minecart_damage [20]` | Scales the damage a moving cart deals, or 0 to turn it off. |
| `cart_impact_speed [4]` | Mobs/players take damage from a cart above this speed, in blocks per second. |
| `cart_wreck_momentum [4]`| Carts break if they collide above this closing momentum, in empty minecarts times blocks per second. 0 never wrecks. Needs `realistic_physics`. |
| `cart_pickup_speed [4]` | The fastest a cart can be moving and safely pick up a mob, in blocks per second. |
| `carts_resume_from_brakes [true]` | Whether a cart stopped by an unpowered powered rail leaves again when the signal returns. |
| `realistic_physics [false]` | Whether carts use this mod's own force model instead of vanilla's. Off by default, so an existing world is unchanged. Trains are not gated by it — vanilla has no trains to mirror. |
| `furnaces_can_use_all_fuels [true]` | Whether furnace carts accept all furnace fuels. |
| `furnace_max_burn_time [72000]` | Furnace burn time ceiling, in ticks. |
| `furnace_minecarts_load_chunks [false]` | Whether moving furnace carts act as chunkloaders. |

## Requirements

Train and collision logic requires the `minecart_tweaks` experimental datapack.
If it's not present a warning is logged at startup and, `trains_enabled` won't appear in gamerules at all.
