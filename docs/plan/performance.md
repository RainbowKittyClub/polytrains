# Performance

Cut what trains cost the server tick, above all parked ones, and stop a train holding chunks
loaded past the edge of the loaded area. Every package was read against the 26.2 decompile, with
each cost traced to the mixin or event that runs it and how often vanilla calls that. The large
costs were then measured with spark on a dev server through `scripts/perf.py`, with the debug log
off as on the live server. A claim marked *static* was read, not measured. Line numbers cite main
at `30d01c9`, where the costs were read.

S1 and S2 come first and are independent of each other: S1 is a chunk-loading bug, S2 the cheap
half of the same read pattern. S3 and S4 change no behaviour and take the placer's scans and rail
reads from per-car to per-train; S5, the idle fast path, is the riskiest stage and goes after them,
so what it still saves is measured on top of theirs. S6, S7 and S8 are independent of everything
else. S9 is a list of small taxes, any order. The work is complete when the yard leg's `holdTrain`
is under 0.1 ms/tick and `perf.py pin` prints three equal counts. At `d4b0766`, `pin` prints three
equal counts, and `holdTrain` is under 0.1 only once the yard has settled: 0.014 after 12,000 ticks
parked, 0.174 after the standard 30 s settle.

---

## Findings that reshape the work

**A parked train costs as much as a moving one, so the work is idle cost, not speed.** 32 parked
seven-car trains (224 carts, nothing moving, no fuel, flat plain rail) spend 0.685 ms/tick in the
`holdTrain` handler, 49% of the whole tick. Moving trains cost 0.719 ms for 28 trains of 140 carts.
A parked linked car costs 4.5 µs a tick against 1.9 µs for the same cart loose. `holdTrain` runs
the whole seating pipeline for every train of two or more cars whose leader ticks, because
`Train.decideLeader` always names one. Its `TrainPlacer.place` calls vanilla
`pushAndPickupEntities` for every seated car (about 0.12 ms/tick in the yard, nearly half of
`place`), which vanilla itself skips for a cart at rest.

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
measured before this work and at `d4b0766`:

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

Every stage below reads **built**: its code is in, builds, lints and boots with no mixin failure,
and it has been measured, but not every clause of its "Done when" has been observed. **done** means
observed. The torture clause holds back all nine: at `d4b0766` the verdict changed from main's, the
Coupling clause failing on one sample, a coupled pair 3.10 apart against the 3.0 bar, after another
train's strike fouled a seat and the placer let three cars go for about 16 ticks. Every run has one
such sample above 2.0 (main's widest was 2.83, the first bench run's 2.15), and a rerun is under way
to tell that tail from a trend. S5, S7 and S8 also still want the user's in-world look, and S9 its
wrench check.

The stages were measured together rather than each against its parent: main at `30d01c9`, a first
bench run at `e126799`, before S2 and S3 were built and with S5 in its first form, and a second at
`d4b0766` with all nine. All three ran on one dev server with the protocol above, so they compare
with each other but not with the Reference table's main rows. A frame that two stages both cut
cannot be credited to either; each stage's Measured paragraph says where that bites.

## S1 — Ribbon and rail reads never load a chunk — **built**

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

### As built

`RailPath.loadedState` reads through `getChunkNow` and returns null for a chunk not loaded. It
guards `RailPath.crossing` and the private `RailPath.neighbours`, not `shape`, which takes a state,
and `TrackRibbon.openSwitchBeyond`, which the list missed. Reads at or under a car stay unguarded;
of those, `RailPath.cover`, which walks a tick's travel, could reach past the edge only at a
`max_minecart_speed` of about 320 or more.

**Measured.** `pin` printed 841 / 887 / 841 at main and 841 / 841 / 841 at both bench runs: met.
Still unobserved: only the torture verdict, which matched main's at the first run.

## S2 — forgetThrown reads only switches, and a dead end is remembered — **built**

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

### As built

There is no per-node switch flag: a switch laid after its node was recorded is never flagged, so the
backstop would read every node anyway. `TrackRibbon.forgetThrown` walks only once `SWITCH_CHANGES`
has moved since its last walk or `FULL_WALK_TICKS` (20) have passed, and every tick while a thrown
switch lies inside the train's span. That counter is one static shared by every level, bumped
through `TrackRibbon.switchChanged` from `SwitchedRailBlock.onPlace`; 26.2 has no `onRemove`, and a
removed switch is never thrown. `TrackRibbon.growEnd` remembers a dead end by its end node,
forgotten when the train moves, a switch changes or 20 ticks pass. `blockedBeyond` is not
remembered: a buffer placed or taken up past a tail would be misread. A switch written with block
updates off, or rail laid past a standing train's dead end, is seen up to 20 ticks late.

**Measured.** Yard `forgetThrown` / `thrown` / `grow` read 0.020 / 0.022 / 0.005 at main, 0.022 /
0.028 / 0.011 at the first run, before S2, and 0.003 / 0.001 / 0.002 at the second: at noise, 2 to 6
samples each, but not absent, since the 20-tick walk is by design (see Open decisions). The yard
measures S5 here too. On moving trains `forgetThrown` fell from 0.017 to 0.007 at speed 8 and from
0.026 to 0.019 at speed 100, where every change of the tracks' eight switches walks every ribbon.
Still unobserved: only the torture verdict.

## S3 — Ribbon lookups by index, once per attempt — **built**

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

### As built

Nodes live in `TrackRibbon.NodeRing`, a ring buffer with a map from each block to its nodes. Lookups
by arc start from a binary search, and lookups by block read only that block's nodes, in order, so
ties break as before. `seatAt` makes one lookup through `TrackRibbon.spotAt`, and
`Train.standingGaps` reads the track between cars once per attempt. The plan miscounted `arcs()`,
which runs at most twice per attempt, and missed the sweep's `blocksBetween`, a walk per placed car,
which binary-searches now too. The `arcOfLeader` memo, NaN for "no arc" and the one-walk
`trackBetween` were not built: once a lookup is a hash they buy nothing worth their risk.

**Measured.** Yard `arcOf` read 0.127 at main, 0.134 at the first run, before S3, and 0.009 at the
second: met, but mostly through S5, which runs a parked train's placer one tick in 20, so the yard
cannot show `holdTrain`'s ribbon share either. On moving trains `arcOf` went from 0.110 to 0.105 at
speed 8 and from 0.139 to 0.084 at speed 100: about a third of it is vanilla's block read under the
car (`getCurrentBlockPosOrRailBelow`), which no index removes. Still unobserved: only the torture
verdict.

## S4 — One rail census per train per tick — **built**

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

### As built

The census on `Train` (`railUnder`, `brakes`, `boosts`, `braking`, `brakingExcept`,
`boostingExcept`, `ceilingExcept`) is keyed on the tick and each car's exact position rather than
cleared by `TrainPlacer.place`, since `fly()`, `SlopeStep` and the step hooks move cars too. It
holds rail states, not grade counts: `downhillShare`'s chord runs through the driven car's live
position. Most of `RailBrake.brakes`' cost was the lever lookup, which `RailBrake.leverHeld` now
makes for a furnace cart only. A lever cannot land late, since `BrakeLevers.toggle` runs between
ticks and calls `Train.forgetCensus`; a powered rail switched mid-tick under a car other than the
driven one can, and the torture track's always-lit rails cannot show it.

**Measured.** In the yard `RailBrake.brakes` read 0.089 at main and was absent at both runs;
`TrainCoupling.braking` read 0.072 and is gone, its census successor `Train.braking` reading 0.011;
`held` read 0.064, then 0.010 and 0.013. Not met on `held` alone, which each engine still asks once
a tick (see Open decisions). Still unobserved: only the torture verdict.

## S5 — A settled train skips the placer — **built**

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

### As built

At the first bench run the fast path never held a yard train: a seven-car train outgrows its
five-rail gap, so every one had another's end car within `RUN_SHELL`, and the per-tick foreign-cart
guard sent it to the full pass. Now a clean full pass that moved nothing and held its gaps records
each placed car, its seat rail and the other trains' carts in reach (`Train.markStill`), unless a
car touches one of them. `TrainPlacer.holdStill` holds the train while none of those has moved, with
no entity query: the recorded carts are checked in place, and players by a loop over
`level.players()`. It also fires an activator rail under a car and applies block effects, as vanilla
does at rest, and tests the leader's movement against vanilla's floor rather than zero. A full pass
still runs every `STILL_RECHECK_TICKS` (20), staggered per train.

The wakes are a switch change (`TrainCoupling.forgetThrown`), a shove (`EntityMixin`, through
`Train.wakeTrainOf`), a strike (`CartImpact.shunt`), another train moving within `RUN_SHELL`
(`TrainPlacer.attempt`) and a cart added in reach (an `ENTITY_LOAD` hook in `Polytrains` calling
`TrainPlacer.wakeTrainsNear`). No lever wake is needed: a lever matters only with fuel, which keeps
the full pass. `TrainPlacer.place` skips `pushAndPickupEntities` at rest only while no other
train's cart is in reach, since that push parts overlapping cars. A cart teleported into reach waits
for the next full pass, and a train parked flush against another never takes the fast path. The
switch check cannot be done as written, since a switch with a train across it refuses to change:
throw one just past a parked train's end, and `/setblock` a shape change under one.

**Measured.** Yard `holdTrain` read 0.699 at main and 0.858 at the first run, where the fast path
only added its checks. At the second it read 0.174 after the standard 30 s settle, most of it
`holdStill` itself (0.121), and 0.014 after 12,000 ticks parked, debug log on: not met in the
standard leg, met once settled (see Open decisions). Parked 12,318 ticks, the yard logged no unseat,
as at main and the first run, and every meter line from 600 ticks on read `still=true`. Still
unobserved: the torture verdict, and the in-world bump and switch.

## S6 — Loose carts skip the train hooks — **built**

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

### As built

Built as planned. The `gathers` reorder does nothing in the loose leg, where a cart at rest never
reaches it, and pays in the yard instead. The shared rail read is lazy, so a seated furnace cart
still reads none. The plan's five CI/CIR allocations are about nine, and none is removed.

**Measured.** In the loose leg every mod frame together came to 0.007 ms/tick at the first run and
under 0.003 at the second, against 0.098 at main counting nested frames: met. Still unobserved:
only the torture verdict.

## S7 — One collision sweep per train, its own cars filtered first — **built**

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

### As built

The per-train sweep was not built: testing every car at one instant changes which cars of another
train are met and when, a risk to the meeting, jam, overlap and clip stages, and its union box round
a curve is far larger than the cars' own. Built instead:

- `strikeNearby` drops the train's own cars through `CartInteraction.sameVehicle`, and
  `CartInteraction.of` answers a placed pair of one train before its track walk.
- `SlopeStep.solidsAlong` runs one entity query per step for both `SlopeStep.reach` and
  `CartImpact.deepens`; `reach` is not skipped on a level step, which would save nothing now.
- One `RailPath.ahead` serves `JunctionRailBlock.layAhead` and `SwitchedRailBlock.orientAhead`;
  `shortOfClosed` keeps its own, since it reads the turned step.
- `CartImpact.shunt` reads both cars' ribbon places once (`TrainCoupling.relayBetween`).

**Measured.** At speed 100, `CartInteraction.of` / `strikeNearby` read 0.236 / 0.305 at main,
0.113 / 0.190 at the first run and 0.170 / 0.193 at the second: not met. `of` rose with the
`SlopeStep` and `RailContext` frames, which nothing since the first run touches, so it reads as more
cart contact in that leg (one leg each, not bisected). What was left at the first run is the strike
box's query (0.059), `of`'s `meets` walk for cars of two trains on adjacent track (0.054), the
ceiling read in `bounded` (0.030, of which 0.013 picks the train's leader) and `shunt` (0.018). None
goes without a behaviour change; see Open decisions. Still unobserved: the torture verdict, and the
in-world meeting and mob strike.

## S8 — Chains stop re-aiming when nothing moved — **built**

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

### As built

The skip goes after both trail pushes and before the seated derail re-check, not only before
`aim()`: the four `getBlockState` per link come from that re-check, not from `aim()`. `LinkVisual`
counts ticks unmoved against `STILL_TICKS`, `RENDER_LAG` rounded up plus one, and `LinkChain`'s
comment is corrected. The leader latch was not built: the skip alone clears the yard, and no moving
leg shows a `towards` frame. A pair standing exactly still re-reads its rails only once a car moves,
which a derailment always does.

**Measured.** In the yard `LinkVisual.towards` was absent at both runs and `LinkChain.onTick` read
0.000 at the second: met. Main on the same dev server already read 0.001 and 0.004, not the 0.026
and 0.092 this stage was planned from, and the chain frame swings between legs of one kind (speed
100: 0.008 at main, then 0.188 and 0.054), so no leg credits the change. Still unobserved: the
torture verdict, and chains on a curve in-world.

## S9 — Small per-tick taxes — **built**

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

### As built

Where a row differs from its plan:

- Row 1: `TrainPlacer.describe` formats the raw `Refusal` under `Locale.ROOT`, meter on only.
- Row 2: the client dims an overlay after 40 of its 60 ticks, so an unchanged prompt is re-sent
  every `OVERLAY_REFRESH` (20) ticks. `wouldAct` is not cached: it reads distance, obstruction,
  inventory and train sizes, not only the rails. The outlines follow the aim, the prompt does not,
  so the in-world check is outlines that track the aim and a prompt that holds steady.
- Row 3: keyed on the train having a coupled tender (`CoalTenderMinecart.coupledTo`), not on fuel.
- Row 4: `TrainPlacer.Nearby` makes one lazy foreign-cart query per attempt, grown when asked
  outside it. Its first seed, grown by `furthestRun`, reached the next yard line and cost more than
  it saved; it is now seeded over the train box grown by `RUN_SHELL` plus this tick's speed, and
  `coupledToAny` asks only the end cars. The frame it moves is `foreignCarts`; `contactOf`,
  `foreignEnd` and `fouled` keep their own geometry.
- Row 7: skips at vanilla's own no-move floor, as horizontal speed takes ~1000 ticks to reach zero.
- Row 8: a burst at chunk load, not a per-tick tax. The car resyncs on its first tick
  (`TrainCoupling.resyncTrainOnce`), and each loaded neighbour's train is invalidated at once.
- Row 9: the held stack's own burn time is read first, and the coal stack is built lazily.

**Measured.** `Train.follows`, `Train.contains`, `TrainPlacer.round`, `shoved` and the resync are
absent at the second run. `foreignCarts` fell from 0.079 to 0.029 at speed 100 and from 0.075 to
0.001 in the yard, with S5's help; `pressedGaps` from 0.022 to 0.002 in the yard. `contactOf`,
`fouled` and `shallowerPart` are still sampled, at 0.014, 0.019 and 0.003 at speed 100, and rows 2,
3, 9 and 10 cannot show with no player connected: not met to the letter (see Open decisions).
Still unobserved: the torture verdict, and the wrench in-world.

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
- **S7's targets need a behaviour change or new numbers.** What is left under `strikeNearby` at
  speed 100 (see S7) is work the outcome rests on. The strike box decides what is struck, so it
  cannot shrink. `strikeNearby` is the tick's first caller of `Train.leader()`, which picks the
  leading end from the movement at that moment, so moving or caching that read can change the pick.
  The `meets` walk is real work for cars of two trains on adjacent track. Settle it by choosing
  between restating the targets at the measured floor, about 0.11 for `of` and 0.19 for
  `strikeNearby`, and a behaviour change such as deciding each train's leader at one fixed point in
  the tick. Folding vanilla's own `Entity.collide` query into `solidsAlong` would save about 0.02
  more and is exact on vanilla, but Lithium, which the live server runs, redirects that call, so it
  waits on a bench boot with Lithium.
- **Bars that ask a working frame to be absent.** S2's `forgetThrown` and `grow`, S4's `held`, and
  S9's `contactOf`, `fouled` and `shallowerPart` still do work by design: the 20-tick walk, each
  engine's own question, the placer's geometry. They can fall, and have, but cannot vanish. Settle
  it by restating each bar as a ceiling over the leg that showed it.
- **Which yard leg judges S5.** The yard's `holdTrain` reads 0.174 after the standard 30 s settle
  and 0.014 after 12,000 ticks, and three earlier standard legs at `556fc6a` read 0.022 to 0.080:
  which trains end up packed against a neighbour, and so off the fast path, depends on which end
  leads, picked by UUID. Settle it by naming the leg the 0.1 bar is judged on, and by giving
  `perf.py yard` a fixed orientation so the standard leg repeats.

## Reference: measured legs

Debug log off, 120 s legs. The first six rows are main at `30d01c9` on 2026-10-07; the last four
are `d4b0766` on 2026-10-08, on another dev server. Values are ms/tick of the server thread, and a
parenthesised value is part of the one before it.

| Leg | Carts | Tick | All cart ticks | `holdTrain` | Tick hooks | `strikeNearby` | Chains | `TrainPlacer.place` |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| Empty world | 0 | 0.125 | — | — | — | — | — | — |
| One track, speed 8 | 35 | 1.122 | 0.681 | 0.213 | 0.123 | (0.089) | 0.023 | 0.056 |
| Four tracks, speed 8 | 140 | 2.478 | 1.767 | 0.719 | 0.369 | (0.244) | 0.123 | 0.198 |
| Four tracks, speed 100 | 140 | 2.797 | 2.067 | 0.585 | 0.426 | (0.267) | 0.080 | 0.173 |
| Yard, parked | 224 | 1.395 | 1.019 | 0.685 | 0.138 | (0.018) | 0.092 | 0.231 |
| Loose, parked | 176 | — | 0.332 | — | 0.037 | — | — | — |
| Four tracks, speed 8, `d4b0766` | 144 | 1.546 | 1.147 | 0.482 | 0.233 | (0.160) | 0.046 | 0.161 |
| Four tracks, speed 100, `d4b0766` | 144 | 2.200 | 1.603 | 0.344 | 0.269 | (0.193) | 0.054 | 0.127 |
| Yard, parked, `d4b0766` | 224 | 0.650 | 0.331 | 0.174 | 0.044 | (0.004) | 0.000 | 0.018 |
| Loose, parked, `d4b0766` | 180 | 0.421 | 0.011 | — | 0.002 | — | — | — |

The loose leg's whole-tick figure is left out because item entities from the killed yard were
still ticking. "Tick hooks" is the cart and furnace `tick` handlers together; `strikeNearby` runs
inside them. Single ticks of 13–30 ms showed up in several legs, one with no carts at all among
them, and 85 ms on a spawn tick; nothing ties them to the mod.

On the second dev server main itself read a tick of 1.654, 2.192, 1.288 and 0.521 for the same four
legs, and `holdTrain` 0.396, 0.573 and 0.699, so compare the `d4b0766` rows with those, not with the
rows above. There Tick is the mean of `tick query`'s average over 24 samples, the tracks and loose
legs count four rig carts that stand in that world, and each speed-100 leg had one long tick: 210 ms
at main, 528 ms at `d4b0766`.

The yard's `holdTrain` at `d4b0766` depends on how long it has stood: 0.174 after the standard 30 s
settle, 0.014 in a 60 s leg after 12,000 ticks parked, with the debug log on.

## Reference: what runs per tick

- **Every entity:** an instanceof in `ServerEntityMixin`'s `sendChanges` HEAD hook, and the
  `Entity.push` injections above for each push pair.
- **Every cart:** the `tick` HEAD hook:
  - one rail read, for `JunctionRailBlock.steer` and `SwitchedRailBlock.steer` on an unseated cart
    and `RailBrake.tick` on any but a furnace cart;
  - `TrainCoupling.tickTrain` (`Train.of`, `follows`);
  - `LinkChain.sync`;
  - `strikeNearby` when moving.

  Then the calc hooks on each rail step, and `holdAfterMove`/`holdNose` at TAIL.
- **Once per train of two or more, parked included:** `holdTrain`, which for a train held still is
  `TrainPlacer.holdStill` on 19 ticks in 20; otherwise:
  - `reachTrain`: ribbon record, grow, `forgetThrown`;
  - `TrainPlacer.seat`;
  - `trimRibbon`;
  - `divertedCarIndex`.
- **Each vanilla step of a driven car:**
  - the three calc hooks, off the train's census;
  - one `RailPath.ahead` for both `steerAhead`s, and `shortOfClosed`'s own;
  - `SlopeStep.solidsAlong`, one entity query for `SlopeStep.moveOnRail` and `CartImpact.deepens`;
  - `recordTrack`.
- **Each engine:** `held`, the stall counter, and `CoalTenderMinecart.feed` with a coupled tender.
- **Each chain:** `LinkVisual.tick` → `aim()`, from Polymer's holder tick, until both cars have held
  still for `STILL_TICKS`.
- **Each online player:** `LinkTargeting.update`; with a wrench in hand, a crosshair raycast, the
  link checks, and an overlay packet when the line changes or every 20 ticks.
