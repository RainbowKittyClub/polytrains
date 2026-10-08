# Performance

Cut what trains cost the server tick, above all parked ones, and stop a train holding chunks
loaded past the edge of the loaded area. Every package was read against the 26.2 decompile, with
each cost traced to the mixin or event that runs it and how often vanilla calls that. The large
costs were then measured with spark on a dev server through `scripts/perf.py`, with the debug log
off as on the live server. A claim marked *static* was read, not measured.

S1 and S2 come first and are independent of each other: S1 is a chunk-loading bug, S2 the cheap
half of the same read pattern. S3 and S4 change no behaviour and take the placer's scans and rail
reads from per-car to per-train; S5, the idle fast path, is the riskiest stage and goes after them,
so what it still saves is measured on top of theirs. S6, S7 and S8 are independent of everything
else. S9 is a list of small taxes, any order. The work is complete when the yard leg's `holdTrain`
is under 0.1 ms/tick and `perf.py pin` prints three equal counts.

---

## Findings that reshape the work

**A parked train costs as much as a moving one, so the work is idle cost, not speed.** 32 parked
seven-car trains (224 carts, nothing moving, no fuel, flat plain rail) spend 0.685 ms/tick in the
`holdTrain` handler, 49% of the whole tick. Moving trains cost 0.719 ms for 28 trains of 140 carts.
A parked linked car costs 4.5 µs a tick against 1.9 µs for the same cart loose. `holdTrain` runs
the whole seating pipeline for every train of two or more cars whose leader ticks, because
`Train.decideLeader` always names one. Its `TrainPlacer.place` calls vanilla
`pushAndPickupEntities` for every seated car (0.23 ms/tick in the yard), which vanilla itself skips
for a cart at rest.

**A parked train holds chunks loaded beyond the loaded area.** A 20-car train standing wholly
inside forceloaded chunks, on a line that runs on into chunks nothing holds, raised the level's
loaded-chunk count from 3364 to 3410. The count fell back to 3364 once the train was removed
(`perf.py pin`, reproduced twice). The cause is that ribbon reach is ±(3(n−1)+1) blocks round the
leader (`TrainCoupling.java:1338`) and `forgetThrown` reads every node outside the train every tick
(`TrackRibbon.java:541`, `:578`). Each `getBlockState` past the edge goes through `Level.getChunk`
(`Level.java:368`), which adds a one-tick `UNKNOWN` ticket (`ServerChunkCache.java:238`,
`TicketType.java:25`) and blocks the main thread until the chunk is loaded (`:165`). Re-read every
tick, those chunks never unload. This was measured on chunks already generated and cached. A cold
read from disk, or of ungenerated chunks, stalls the tick.

**TrainMeter costs nothing on the live server, but it inflates dev numbers.** Every `TrainMeter`
entry point returns on `isDebugEnabled()`, and the live log4j root is `info`. A Loom dev server
roots at `${sys:fabric.log.debug.level:-debug}`, so there the meter runs every tick:
`strike`/`sample`/`clips` came to about 0.12 ms/tick for 35 carts. MSPT from a `torture.py` run
includes that.

**Speed raises the per-step cost, not the placer's.** From 8 to 100 blocks/s on the same 140 carts,
the step wrap `SlopeStep.moveOnRail` (vanilla `move` included) went from 0.23 to 0.43 ms/tick and
`CartInteraction.of` from 0.14 to 0.21. `holdTrain` fell from 0.72 to 0.59. The per-step hooks
(S7) matter on fast lines and the placer (S3–S5) everywhere.

**Parked trains send no per-tick packets** (*static*). Every Polymer element is a passenger with
`ignorePositionUpdates`, and its data goes out only when a value changes. A moving train on a curve
sends one entity-data packet per chain per tick per watcher; that is the pose changing, not waste.

## How these stages get tested

On a polytrains dev server: the main checkout's, or a worktree's with its own ports in
`run/server.properties`. Put spark (any 1.10.x Fabric build, e.g. the one in
`rkc/mods/artifacts/run/mods`) in `run/mods`, and `{ "backgroundProfiler": false }` in
`run/config/spark/config.json`. Boot with the debug log off:
`JAVA_TOOL_OPTIONS=-Dfabric.log.debug.level=info scripts/devserver.sh <mod dir> start`.
`torture.py` reads `debug.log`, so it needs a separate boot without that flag.

`scripts/perf.py` builds the fixtures and runs the legs, and the Reference table holds what each
measured before this work:

| Fixture | What it is |
| --- | --- |
| `tracks [n]` | `n` torture-track copies 1024 apart (`n=4`: 140 carts, 28 trains, 44 engines), fuelled |
| `yard` | 32 seven-car trains parked on flat plain rail, engines at zero fuel; clears the tracks |
| `loose` | the yard swapped for 176 unlinked carts of the same mix, one every other rail, none touching |
| `pin` | the loaded-chunk count before, with and after a 20-car train parked near the loaded edge |

`perf.py leg <name> <seconds>` profiles the server thread at 1 ms and samples `tick query` every
5 s. It decodes with `scripts/loadtest/SparkReport.java` into `run/perf/<name>.txt`. Legs are 120 s
after 20–40 s of settling. spark samples wall-clock time, so compare a stage against its parent
commit measured the same session, not against the table. A frame under ~1 ms total over a leg is
noise.

A stage is not finished until its "Done when" is observed. That always includes behaviour held:
the `torture.py run` verdict at the stage's commit matches its parent's, and for S5, S7 and S8 the
user's in-world look before commit. Nothing here was measured with players connected, so packet
volume per watcher is static. A stage that claims a packet saving adds bots first, through
ViaFabric + ViaBackwards as `scripts/loadtest` does.

## S1 — Ribbon and rail reads never load a chunk — **planned**

**Build.** Add one read helper beside `RailPath.railAt` that returns null when
`level.getChunkSource().getChunkNow(x >> 4, z >> 4)` is null. Route through it every world read
the ribbon makes: `TrackRibbon.grow`/`onward` (`:901`, `:972`), `thrown` (`:578`),
`beyondEnd`/`blockedBeyond` (`:956`, `:626`), and `RailPath.neighbour`/`railAt`/`shape`
(`:611`, `:622`). Each caller says what unloaded means. Growth stops and retries next tick. A node
in an unloaded chunk is not thrown. For `blockedBeyond`, unloaded means **not blocked**: reading it
as blocked would draw a leader back at every chunk edge. `TrainPlacer.recordedEnd`'s infinite
reach ahead (`TrainPlacer.java:297`) needs no cap once its reads are guarded.

**Done when.** `perf.py pin` prints the same count three times, and the torture verdict is
unchanged.

## S2 — forgetThrown reads only switches, and a dead end is remembered — **planned**

**Build.** `forgetThrown` (`TrackRibbon.java:541`, from `TrainCoupling.java:1329` every tick)
re-reads every node outside the train to catch a switch thrown past it. That is about 4.5n+12
reads per train per tick, although `SwitchedRailBlock.onPlace` already calls
`TrainCoupling.forgetThrown` on every shape change. Carry a switch flag on each `Node` when it is
recorded or grown (`crossing()` already holds the state), and read only flagged nodes. Then skip
the walk entirely while a per-level switch-change counter, bumped in `onPlace`/`onRemove`, has not
moved since this ribbon last walked; a full walk every 20 ticks covers a switch changed with
updates suppressed. Separately, `extendTo` retries `grow` at a buffer every tick, and
`blockedBeyond` re-probes it (`TrackRibbon.java:901`, `:626`). Remember a terminal per ribbon end,
and re-probe only when the train moves toward it or the epoch changes.

**Done when.** `forgetThrown`/`thrown` and the dead-end `grow` attempts are absent from the yard
leg's report, and the torture verdict is unchanged. The torture track has two switches, so
behaviour holding here is the real test.

## S3 — Ribbon lookups by index, once per attempt — **planned**

**Build.** Every `TrackRibbon` lookup is a linear walk of its `ArrayDeque`. Callers make about ten
of them per car per tick, so the placer is O(n·R) per train, with R ≈ 6n+6 nodes. The callers:
`nodeAt` `:875`; `arcOf`→`occurrenceUnder` `:473`/`:301`; `arcAlong` `:793`; `trackBetween` `:837`.
Three of the walks come from `TrainPlacer.seatAt` (`:1352`), once each for position, block and
tangent. `Train.arcs()` (`Train.java:673`) is recomputed up to three times per attempt before
anything is placed. `arcOfLeader` is computed four times (`TrainCoupling.java:1308`, `:1318`,
`:1400`, `TrainPlacer.java:210`).

Store nodes in an index-addressable ring buffer. Arcs are contiguous and sorted, so `nodeAt`,
`arcAlong` and `trackBetween` become a binary search and a local step. A `BlockPos`→node-index map
serves `arcOf`, `holds`, `covers` and `occurrenceNear`. Make `seatAt` do one lookup. Memoise
`arcOfLeader` and the per-car arcs and gaps for the attempt, invalidated when `TrainPlacer.place`
moves a car. Compute consecutive `trackBetween`s in one forward walk. Return NaN, not a boxed
`Double`, for "no arc". No behaviour changes.

**Done when.** In the yard leg, `TrackRibbon.arcOf` falls from 0.117 to under 0.02 ms/tick and
`holdTrain` falls by the ribbon frames' share. The torture verdict is unchanged.

## S4 — One rail census per train per tick — **planned**

**Build.** On flat track with no brake or booster rail, vanilla's `hasGainedSlopeSpeed`, `hasHalted`
and `hasBoosted` never flip, so `NewMinecartBehaviorMixin`'s three calc hooks (`:52`, `:67`, `:80`)
fire on every rail step of the driven car. Each walks the whole train:
- `slopePull` → `downhillShare` → `RailPath.grade`, 2 reads per car.
- `braked` → `railShare(RailBrake::brakes)`.
- `boostPull` → `braking` plus `railShare(boosts)`.

On top of that, every tick: `hauled`→`held` (`TrainCoupling.java:711`), `held` once per engine
(`MinecartFurnaceMixin.java:373`) and `trainCeiling` (`:963`), called 2–5 times per attempt through
`furthestRun()`. Within the step loop only the driven car moves, so cache once per train per tick,
on `Train` the way `mass` already is:
- every other car's rail state, as brake, boost and grade counts;
- the masses;
- `trainCeiling`.

Read the driven car live, and invalidate on `TrainPlacer.place`. Use `train.mass()` as
`railShare`'s denominator. In `slopePull`, return before building `RailPath.crossing` when the share
is zero. In `hauled`, test for the leader before the brake walk (`TrainCoupling.java:205`).

**Done when.** In the yard leg `RailBrake.brakes` falls from 0.099 to under 0.02 ms/tick and
`braking`/`held` are absent, with the torture verdict unchanged. A lever thrown mid-tick may land
one tick late; say whether that was seen.

## S5 — A settled train skips the placer — **planned**

**Build.** Add a fast path at the top of `holdTrain` (`TrainCoupling.java:1030`) for a train that
is not stale and whose:
- ribbon `arcEpoch` is unchanged;
- leader movement is exactly zero and at its laid arc;
- every car stands at its last seat;
- last attempt had no bail.

The fast path re-stamps the seat marks and `setHeldAt`; `TrainPlacer.seated` forgives one tick
only, so skipping the stamp unseats the train. It also checks `isRail` under each seat, and does
one entity query over the train's box for a foreign cart or player. Wake the full pass on:
- a switch shape change (`SwitchedRailBlock.onPlace` already reaches the ribbon);
- a lever or brake-rail change;
- a shove, which lands on the leader's movement;
- a foreign car arriving at either end.

In `TrainPlacer.place` (`:1407`), skip `pushAndPickupEntities` for a car not displaced this tick,
as vanilla does at rest (`NewMinecartBehavior.java:410`). With that, a parked empty cart no longer
picks up a mob that walks into it, which a vanilla cart at rest also does not.

**Done when.**
- In the yard leg, `holdTrain` falls from 0.685 to under 0.1 ms/tick.
- The yard parked for ten minutes logs no unseat in `debug.log` (DEBUG boot).
- The torture verdict is unchanged.
- In-world, the user bumps a parked train and throws a switch under one, and both act at once.

## S6 — Loose carts skip the train hooks — **planned**

**Build.** An unlinked cart at rest pays about 0.4 µs a tick over vanilla's 1.9 µs (loose leg):
- `JunctionRailBlock.steer` and `RailBrake.tick`, from the tick hook
  (`AbstractMinecartMixin.java:528`);
- `braked`/`boostPull` through `drivenCars` → `List.of(cart)`;
- about ten `getBlockState` and five CI/CIR allocations.

Pass vanilla's rail `state` through the calc hooks (`NewMinecartBehaviorMixin.java:67`, `:80`).
Return null at once for a cart with no link whose rail is not powered and whose lever is unset;
that is today's result, since the share comes out zero. Read the rail under the cart once per tick
and share it between `RailBrake.tick` and `Junction.steer`. Check `CartInteraction.gathers`'s
`isRideable() && !isVehicle()` before its game-rule read.

**Done when.** In the loose leg, the mod's frames under `AbstractMinecart.tick` sum to under 0.02
ms/tick (from about 0.07), and the torture verdict is unchanged.

## S7 — One collision sweep per train, its own cars filtered first — **planned**

**Build.** Every moving car, seated ones included, runs its own `getEntities` sweep in
`CartImpact.strikeNearby` (`CartImpact.java:79`). At speed the sweep box takes in the train's own
cars, and each goes through `CartInteraction.of` → `RailContext.meets` → a `RailPath.covered` walk
before `sameTrain` returns IGNORE or BUFFER, which `strikeNearby` then ignores. Changes:
- Filter `Linkable.coupled || TrainCoupling.sameTrain` in the predicate at `:105`, before `of()`.
- Sweep once per train over the union of its cars' swept boxes, and hand candidates to cars by box
  test.
- Per vanilla step, fold `SlopeStep.reach`'s `getEntityCollisions` (`SlopeStep.java:100`) and
  `CartImpact.deepens` into one query. Skip `reach` on a level, single-axis step.
- `JunctionRailBlock.steerAhead`, `SwitchedRailBlock.steerAhead` and `shortOfClosed` each compute
  `RailPath.ahead` for the same step (`AbstractMinecartMixin.java:343-360`); compute it once and
  pass it to all three.

**Done when.**
- In the speed-100 tracks leg, `CartInteraction.of` falls from 0.213 to under 0.08 ms/tick and
  `strikeNearby` from 0.267 to under 0.1.
- The torture verdict is unchanged: no new clips, overlaps or severs.
- The user watches a meeting and a mob strike in-world.

## S8 — Chains stop re-aiming when nothing moved — **planned**

**Build.** The chain touching a train's leading car never latches `seated`, so it calls
`towards()` → `RailPath.between` twice every tick. `laid()` needs `polytrains$seatedAt() != 0`
(`LinkVisual.java:161`), and the placer skips the leader (`TrainPlacer.java:731`). That costs
0.026 ms/tick across 32 parked trains. Count the leader as laid the way `CartInteraction.held`
does (`seatedAt != 0 || Train.peek(car).lastLeader() == car`).

Every chain also re-runs `aim()` every tick, parked or not, and watched or not
(`LinkChain.java:71-75`): about 25 objects, 12 trig calls and 4 `getBlockState` per link, for
0.092 ms/tick in the yard. Polymer ticks a holder from `ServerEntity.sendChanges`, which
`ChunkMap.tick` calls for every tracked cart in entity-ticking range, not only watched ones; the
comment at `LinkChain.java:69` saying otherwise is wrong. Skip `aim()` while both cars' position
and rotation have held for more than `RENDER_LAG`+1 ticks.

**Done when.**
- In the yard leg, `LinkVisual.towards` is absent and `LinkChain.onTick` falls under 0.02 ms/tick.
- In-world, chains on a train parked on a curve still meet their couplers, and still follow it
  when it starts.

## S9 — Small per-tick taxes — **planned**

**Build.** Each one is independent, local and low-risk:

| Where | What it costs | Change |
| --- | --- | --- |
| `TrainPlacer.attempt` `:205-260`, `:472`, `:749` | Refusal detail strings built every tick a train is refused, with the meter off; `round()` has no `Locale.ROOT` | Keep raw values in `Refusal`, format in `TrainMeter.refusal` under `Locale.ROOT` |
| `LinkTargeting.showActionBar` `:184` | Overlay packet and new Component every tick per wrench holder, plus `wouldAct`'s refusal checks | Send on change; cache `wouldAct` per (picked, aimed) pair until either rail block changes |
| `MinecartFurnaceMixin` `:405` → `CoalTenderMinecart.feed` | `getNeighbours` (list + 2 UUID lookups) every tick per engine under 3600 fuel, tender or not | Only when a coupled tender is known to hold fuel |
| `TrainPlacer.contactOf` `:791`, `foreignEnd` `:1056`, `fouled` `:912` | Up to n+3 entity queries per moving train per tick | One query over the train box per attempt, shared |
| `TrainPlacer.pressedGaps` `:315` → `shoved` `:1224` | One Player query per car per tick for a train jammed against a buffer | One query over the train box, then per-car tests |
| `Train.follows` `:896`, `contains` | `indexOf` per car per tick: O(n²) | Index stored at build; `contains` by `polytrains$train() == this` |
| `OffRails.shallowerPart` `:106` | `getEntitiesOfClass` every tick per derailed cart at rest | Skip when on ground and not moving horizontally |
| `ServerEntityMixin` `:72` → `resyncTrain` | O(n²) `getEntity` as a long train's chunk loads | Defer to the car's first tick |
| `ChooChooPartsDisplay` `:121`, `:168` → `FurnaceFuel.feeds` | `new ItemStack(COAL)` per watcher per engine per tick | A static stack |
| `CommandCartBox` `:40` | Permission lookup before the cheap wrench test, per watcher per tick | Swap the order |

**Done when.** Each row's frame is absent from the leg that showed it (tracks or yard), and the
wrench prompt still shows and changes as the aim does, in-world.

## Open decisions

- **A linked train of one keeps its whole ribbon.** A car whose partners are unloaded or in another
  dimension is a train of one. Its leader appends every block it crosses (`recordTrack`,
  `TrainCoupling.java:445`), and `trimRibbon` skips trains under two on purpose (`:1396`), so the
  ribbon grows to the `maxNodes` cap: about 1100 nodes at the default `max_train_length`. S1
  removes the chunk loads that trail behind it, S2 the per-tick reads, S3 the scans. What is left
  is memory and a long tail. Settle it by asking whether the history under absent partners is ever
  used once they return, which is what the comment at `:1394` keeps it for.
- **Ribbon sizing.** Reach and trim count 3.0 per gap (`Train.length(MAX_LINK_DISTANCE)`), though
  seated gaps settle at about 1.5, so the ribbon is about twice what a settled train needs. Halving
  it halves every O(R) term, but touches the reasons given at `TrainCoupling.java:1334`. Settle it
  after S3, which makes R cheap anyway.
- **The `Entity.push` injections.** `EntityMixin`'s two cancellable `push` HEAD injections
  (`:56`, `:72`) run for every entity on the server, and both read their `CallbackInfo`, so each
  call allocates one unless escape analysis removes it. Moving them into an
  `AbstractMinecart.push(double, double, double)` override and `WrapOperation`s at vanilla's cart
  call sites frees every other entity, but stops gating another mod's direct `entity.push(cart)`.
  Settle it by measuring a mob crowd first; it may be noise.

## Reference: measured legs

Debug log off, 120 s legs, on 2026-10-07 at `30d01c9`. Values are ms/tick of the server thread, and
a parenthesised value is part of the one before it.

| Leg | Carts | Tick | All cart ticks | `holdTrain` | Tick hooks | `strikeNearby` | Chains | `TrainPlacer.place` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Empty world | 0 | 0.125 | — | — | — | — | — | — |
| One track, speed 8 | 35 | 1.122 | 0.681 | 0.213 | 0.123 | (0.089) | 0.023 | 0.056 |
| Four tracks, speed 8 | 140 | 2.478 | 1.767 | 0.719 | 0.369 | (0.244) | 0.123 | 0.198 |
| Four tracks, speed 100 | 140 | 2.797 | 2.067 | 0.585 | 0.426 | (0.267) | 0.080 | 0.173 |
| Yard, parked | 224 | 1.395 | 1.019 | 0.685 | 0.138 | (0.018) | 0.092 | 0.231 |
| Loose, parked | 176 | — | 0.332 | — | 0.037 | — | — | — |

The loose leg's whole-tick figure is left out because item entities from the killed yard were
still ticking. "Tick hooks" is the cart and furnace `tick` handlers together; `strikeNearby` runs
inside them. Single ticks of 13–30 ms showed up in several legs, one with no carts at all among
them, and 85 ms on a spawn tick; nothing ties them to the mod.

## Reference: what runs per tick

- **Every entity:** an instanceof in `ServerEntityMixin`'s `sendChanges` HEAD hook, and the
  `Entity.push` injections above for each push pair.
- **Every cart:** the `tick` HEAD hook:
  - `Junction.steer`, `RailBrake.tick`;
  - `TrainCoupling.tickTrain` (`Train.of`, `follows`);
  - `LinkChain.sync`;
  - `strikeNearby` when moving.

  Then the calc hooks on each rail step, and `holdAfterMove`/`holdNose` at TAIL.
- **Once per train of two or more, parked included:** `holdTrain`:
  - `reachTrain`: ribbon record, grow, `forgetThrown`;
  - `TrainPlacer.seat`;
  - `trimRibbon`;
  - `divertedCarIndex`.
- **Each vanilla step of a driven car:**
  - the three calc hooks' train walks;
  - `steerAhead` ×2 and `shortOfClosed`, each `RailPath.ahead`;
  - `SlopeStep.moveOnRail` with its pre-query;
  - `CartImpact.deepens`;
  - `recordTrack`.
- **Each engine:** `held`, the stall counter, `CoalTenderMinecart.feed`.
- **Each chain:** `LinkVisual.tick` → `aim()`, from Polymer's holder tick.
- **Each online player:** `LinkTargeting.update`; with a wrench in hand, a crosshair raycast, the
  link checks and an overlay packet.
