# Ten minutes clean

The train physics made robust enough that the torture track runs ten minutes at
`max_minecart_speed=100` and `realistic_physics=false` with no crash, no stall, no sever, trains
bouncing off one another, and neither switch steering under a train that spans it. A server restart
resets the ten-minute clock, so a restart mid-run is outside the bar and S2 of the audit stays
open rather than in scope.

Every number in this document comes from the rig audit of 2026-09-23 (the track placed twice over
rcon and dumped, the rails flood-filled by shape) or from the source at `02b4731`, cited by line.
Two things are unverified and said so where they matter: where the one-tick jump to `1e150` comes
from, and whether the chain-display crash X3 is really gone.

Stage order. Part M first: the rule that no behavioural change is committed untested is waived for
this work on the strength of the sampler alone, so nothing later counts until the sampler can read
it. M1 and M2 are independent of everything; M3 and M4 are one piece of work; M5 is written against
the line format M3 and M4 define and can be built alongside them. X1 shares a site with M4's
overspeed line and is built with it; X2 is independent. P1 to P5 are independent of each other
except that P1 rebases on M4's edit to the same method. R1 is the bar and makes the work complete.
P6 to P8, planned from the runs after P1 to P5, are independent of each other and each is built
against a dev server of its own; R1 runs again once they land.

---

## Findings that reshape the work

**The rig has never placed the train set the track was built for.** Seven `[mtxrig]` signs name
`torturetrack`, marking seven strips of 2, 5, 5, 5, 7, 9 and 9 cells — 42 anchored cells, of which
the seven sign cells carry no cart, so 35 carts were intended in trains of 1, 4, 4, 4, 6, 8 and 8.
The rig placed 32 carts in ten trains of 5, 5, 4, 3, 3, 1, 1, 1, 1 and 8, identically on two
consecutive placements. Two causes. The box `{x:-24,y:-53,z:-24,w:28,d:26}` ends at `z=-24`, while
the strip along `x=-1` runs to `z=-27`: the spread pass anchors past the box but the placing pass
walks only inside it, so the three northernmost cars — a furnace, a chest, a hopper — were never
summoned, and the double-headed eight-car consist ran as a five-car with one engine. And
`couple_now.mcfunction:4` links each new cart to the *previously placed* one within three blocks;
the scan is row-major (rows southward, cells eastward), so any strip that turns a corner or runs
north–south is cut wherever another strip's cart falls in the rows between. The `x=-20` strip
C-H-H-F became C-H-H plus a lone F; the `z=-16` L became five cars plus a lone chest; the `-6/-8` L
became a lone furnace plus F-C-C. Every stall, sever and speed figure measured so far was measured
on this wrong roster.

**Neither stalls nor bounces are observable today, and the meter is blind to a third of the
carts.** `TrainMeter.measure` (TrainMeter.java:55-59) returns for any train of fewer than two cars,
so the four singletons the rig produced — and the one it was meant to — never appear. No line names
its train, so two trains of the same size are indistinguishable across lines. It samples once a
second and reads `drivenSpeed`, so a single-tick spike is invisible: the run that crashed logged
nothing above 3.55 before the `4.85e150`. There is no log line at all for a furnace stall
reversal (MinecartFurnaceMixin.java:334-347), for a strike (CartImpact.java:138-173), or for a
switch steering under a spanning train (SwitchedRailBlock.java:292); the two sever lines
(TrainCoupling.java:886, :919) carry a size, not an identity. The bar's clauses therefore need
definitions before they need code, and the definitions are in the testing section below.

**Persistent rcon is three orders of magnitude faster than a `devserver.sh rcon` call.** The
sampler that ran the crashed session spawned the wrapper per command at roughly 300 ms each and
managed one sample every 3.3 s. A single authenticated socket answers in about 0.15 ms per command
(twenty `list` calls in 3 ms); the whole rig audit — two placements, 35-cart dumps, a 296-block
flood fill with per-block shape queries — took 0.35 s. A full per-cart dump of position, motion and
links every second is free, so the sampler can be exact rather than coarse. Vanilla splits a reply
longer than 4096 characters into several packets with the same request id
(RconClient.java:125-131), so a client must read until a marker command's reply arrives.

---

**The planned stages do not reach the bar.** One ten-minute run on the torture track after each
landing, counting what each clause failed on:

| After | Sever | Stall (queued) | Overlap | Overspeed (worst) | Switch-safe |
|---|---|---|---|---|---|
| M1–M5, X1, X2 | 7 | 6 | 15,520 | 169 (4.07) | 79 thrown under a train |
| P1 | 3 | 5 | 19,509 | 29 (3.63) | 56 thrown under a train |
| P2 | 3 | 3 (9) | 2,936 | 43 (3.27) | 1 `DIVERTED` sever |
| P3 | 1 | 2 (4) | 19,831 | 46 (3.84) | pass |
| P4 | 6 | 5 (6) | 15,540 | 51 (3.33) | pass |
| P5 | 12 | 9 (2) | 23,331 | 40 (3.57) | 1 `DIVERTED` sever |

No run crashed, and every vacuity check passed. Every stall reported was a train standing still,
which keeps the stall threshold as it is. The runs vary widely from one to the next — the track
logs about 50,000 strikes in ten minutes — so one run per landing does not rank the landings.
What stays is three kinds of failure that no planned stage addresses:

- A head-on meeting lifts a leading car onto the block above its rail — two trains' leaders stood on
  `(-20,-52,-3)` together in the P4 run — so the train refuses `LEADER_OFF_RIBBON` and cuts that
  car off ten ticks later. Every sever after P2 but one `DIVERTED` is this refusal, and the cut
  engine runs on alone, leaves the track and stalls off it.
- Two trains that foul each other each refuse `SEAT_FOULED` and stand inside one another for the
  rest of the run. That is most of the overlap count, since a pair logs about two lines a tick.
  train-identity.md's S17 is the plan for it.
- Cars carry speed past their ceiling out of collisions: 2.7 to 3.8 against 2.5, mostly on leading
  cars, where the control never reads over 2.50. `EntityMixin.shoveTrain` adds vanilla's
  cart-to-cart push to the car a train is driven by with no ceiling, the clamp P1 gave `shunt`, and
  the placer copies that car's speed down the train.

They are planned as P6 to P8.

## How these stages get tested

On the mod's own dev server (`run/server.properties`: game port 25603, rcon 25579, password in the
file), against the rig group `torturetrack` placed over the box `{x:-24,y:-53,z:-30,w:28,d:32}` —
the box that covers the track once M1 lands. `TrainMeter` and the event lines write at DEBUG, which
Loom's generated log4j routes to `run/logs/debug.log` only (root logger DEBUG, `net.minecraft`
capped at INFO, rolled on startup and at 200 MB). The sampler `scripts/torture.py` (M5) drives the
run and reads both the world over rcon and the log from a mark.

A stage is finished when its "Done when" is observed on that server by the sampler or by the probe
the stage names. The user's in-world confirmation is waived for every stage in this document; that
waiver rests on M5's self-check, which has to catch an injected sever and an injected stall before
any verdict from it is believed.

The bar is five clauses and three vacuity checks, each machine-checked by M5:

| Clause | Fails when | Read from |
|---|---|---|
| Alive | rcon stops answering before game time has advanced 12,000 ticks from the rig call; or `run/crash-reports/` gains a file; or the log gains an `ERROR` or a stack trace from the mod's package. | heartbeat, directory listing, log scan |
| Sever | the set of link edges — unordered pairs of cart UUIDs from the `minecarttweaks:link` slots — at any sample differs from the set at the first sample; or an `event=sever` line appears. | per-second link dump; M4 |
| Stall | a train with at least one furnace car has every car within 5 blocks of where it stood 600 ticks earlier. Reported per train; a train queued behind a stalled one is reported too, and named as queued. | per-second positions |
| Bounce | an `event=overlap` (cars of two trains within 0.5 blocks); or an `event=overspeed` (horizontal speed above the cart's own ceiling by more than 1%); or a sever within 40 ticks of a strike. Momentum sharing at each strike is reported, not judged. | M4 |
| Switch-safe | an `event=switch` with `spanned=true`; or a sever whose reason is the diverted-track one. | M4 |

Vacuity: fewer than ten strikes between different trains, any driven train travelling under 100
blocks, or fewer than 35 carts at the end fails the run as "not a torture", whatever the clauses
say — the abtestloop control passed every clause in September precisely because its trains never
met.

The protocol, which M5 automates: check the two rules over rcon; set the box; reset and rig the
group; record the log line count and the game time; sample every second until game time has
advanced 12,000 ticks or rcon dies; then parse the log from the mark, print the verdict table, save
the series, reset the group and restore the yard box `{x:-44,y:-53,z:-4,w:25,d:25}`. After a
crash: `devserver.sh restart`, then kill `@e[tag=mtx]` before the reloaded carts re-accelerate.

Working in a worktree, as `/batch` does. `build.gradle`, `settings.gradle` and `gradlew` are
symlinks into the `gradle/` submodule, so a fresh worktree builds nothing until
`git submodule update --init` has run in it. `run/` is gitignored and exists only in the main
checkout, so a worktree has no dev server, no world, no rig and no `debug.log`: build and lint
there, and observe the "Done when" on the main checkout's dev server after the branch is merged,
never by starting a second server (the ports in `gradle.properties` would collide). The rig
datapack is likewise only in the main checkout's `run/world/datapacks/mtxrig/` until M2 copies it,
so M1 and M2 are one task: copy first, fix the copy under `scripts/mtxrig/`, sync it back with
`rig-sync.sh`, and test on the main checkout. `scripts/torture.py` (M5) can be run from any
checkout against the main one's server, since it only speaks rcon and reads
`rkc/mods/minecart-tweaks/run/logs/debug.log` by path.

P6 to P8 are the exception: they are physics and cannot be judged unrun, so each worktree runs a
dev server of its own. It is a copy of the main checkout's `run/`, taken after `save-all flush`, on
ports of its own set in its `server.properties` — `gradle/devserver.gradle` only appends keys the
file lacks, and `stopServer` finds its process by the checkout's own `launch.cfg` — and it is driven
with `scripts/devserver.sh <worktree>`, `torture.py --run-dir <worktree>/run` and `rig-sync.sh
<worktree>/run`. The main checkout's server stays stopped meanwhile, so a command left pointing at
it fails rather than disturbing it.

The speed ceilings the clauses refer to: vanilla's is `max_minecart_speed / 20` blocks per tick
(NewMinecartBehavior.java:486), 5.0 at the bar's setting, clamped per axis at
AbstractMinecart.java:378-380; the mod halves it for a furnace cart under the vanilla model
(MinecartFurnaceMixin.java:142-144), so 2.5. A placed car never reaches that clamp, because the
placer cancels `moveAlongTrack` for it (AbstractMinecartMixin.java:262-265).

# Part M — Measurement

## M1 — Rig box covers the track and cars couple along their strip — **done**

**Build.** In `run/world/datapacks/mtxrig/` (M2 moves it into the repo). Widen the default box in
`load.mcfunction:7` to `{x:-24,y:-53,z:-30,w:49,d:59}` — same southern edge, six rows further
north — and note in `box.mcfunction` that storage keeps the old box until `mtxrig:box` is called
again. Replace the coupling rule: `couple_now.mcfunction` and `couple_pair.mcfunction` currently
link the new cart to `mtx_prev` within three blocks. Instead, run as the new cart at its own
position and look at the four orthogonal cells one block away (`execute positioned ~1 ~ ~ if
entity @e[tag=mtx_fresh,tag=!mtx_new,distance=..0.6,limit=1]` and the three others); link to each
cart found that still has a free slot, into the new cart's free slot, testing freeness with
`execute unless data entity @s "fabric:attachments"."minecarttweaks:link".slotA`. Reach two cells
in a direction only when the cell between holds an in-line sign at marker level
(`if block ~1 ~1 ~ #minecraft:all_signs`), so a sign standing in a strip is bridged and a parallel
strip two blocks over is not. Drop `mtx_prev`. A third neighbour with no slot left gets a `tellraw`
warning rather than a silent skip. Say in `rig.mcfunction`'s header that a sign belongs at a
strip's end or in its line.

**Done when.** `mtxrig:rig {group:"torturetrack"}` over `{x:-24,y:-53,z:-30,w:28,d:32}` places 35
carts in seven trains of 1, 4, 4, 4, 6, 8 and 8, the `x=-1` consist reading F-C-H-C-C-H-C-F from
north to south, on two consecutive placements — read with `scripts/torture.py --roster` (M5) or a
per-cart `data get entity` dump over rcon.

### As built

`couple_now` probes the four orthogonal cells at `distance=..0.6` and reaches two cells only across
a cell holding both a rail and a sign at marker level. The rail test is an addition, so a sign
standing beside the track between two parallel strips does not bridge them. `couple_pair` tests
both ends for a free slot before writing either. `couple_warn` sends the `tellraw` and also counts
`#unlinked mtxrig`, since rcon never sees a `tellraw`. `mtx_prev` is gone; `mtx_mate` is the working
tag a reset clears. The slope worry in the probe distance is moot: no anchored cell is on an
ascending rail, and `summon` puts a cart exactly at its cell with no tick inside one rig call.

Observed three times with `torture.py --roster`: 35 carts in trains of 1, 4, 4, 4, 6, 8 and 8, no
coupling warning, the `x=-1` consist F-C-H-C-C-H-C-F from `z=-27` to `z=-20`. A throwaway yard
covered the paths the track never uses: a sign in the line bridged, a parallel strip two blocks over
kept apart, a T-shaped strip warned once.

## M2 — Rig versioned: datapack in the repo, track as a structure — **done**

**Build.** Copy `run/world/datapacks/mtxrig/` to `scripts/mtxrig/` (without its `.claude/`
directory) and add `scripts/rig-sync.sh` that copies it back under a mod's `run/world/datapacks/`
and issues `reload` over rcon. Snapshot the track as a structure template at
`scripts/mtxrig/structures/torturetrack.nbt`, covering `x -21..2`, `y -58..-45`, `z -29..0`
(24×14×30, under the 48-block limit), which takes in the rails, the wool markers and signs at
`y=-52`, and whatever powers the 34 activator rails below. A redstone-triggered structure block
saves to memory only — StructureBlock.java:87-88 calls `saveStructure(false)` — so the file has to
come from the block's GUI save (one minute with a client) or from a script that reads the region
files, for which `scripts/idmigrate/` already has the chunk-palette reader. Document the
restore: copy the file to `run/world/generated/mtxrig/structures/`, then
`/place template mtxrig:torturetrack -21 -58 -29`.

**Done when.** `git ls-files scripts/mtxrig` lists the functions and the `.nbt`; in a second box
location on the yard, `rig-sync.sh`, `place template` and `mtxrig:rig` reproduce the 35-cart
placement of M1.

### As built

26.2 loads a world's templates from `generated/<ns>/structure/`, singular
(StructureTemplateManager.java:44), not `structures/`; `rig-sync.sh` installs the template there
and its header carries the restore. The template comes from `scripts/rig-snapshot.py`, which reads
the region files after `save-all flush`. It was taken with no carts and the redstone at rest (both
switches `south_east`, detectors off): 10,080 blocks counting air, `DataVersion` 4903, no entities,
byte-identical when re-taken. The box holds eight `torturetrack` signs, not seven; the one at
`(-9,-45,-7)` stands above the heights the rig reads. `mtxrcon.py` never has two requests in flight,
since vanilla drops a connection whose one read holds two packets.

Observed at a copy shifted by `(+1000,+200,+1000)` and proven air first: `rig-sync.sh`, `place
template` and `--roster` over a box on the copy alone reproduced M1's placement cell for cell, and
everything placed there was removed again.

## M3 — TrainMeter samples the level, with identities — **in progress**

**Build.** Move the meter off `holdTrain` (TrainCoupling.java:893) onto a level-wide sample every
20 ticks from `ServerTickEvents.END_WORLD_TICK` (`fabric-lifecycle-events-v1`, already a dependency
through `ServerLifecycleEvents` at MinecartTweaks.java:58). Walk every `AbstractMinecart` in the
level, group the linked ones by `Train.of` (Train.java:177) and write one line per train and one
per loose cart. A train's id is the first eight hex digits of its lowest car UUID — the tie-break
`Train.lowest` already computes (Train.java:909-917); expose it as `Train.id()`. A cart's id is the
same prefix of its own UUID. Format, one line, `key=value` tokens after the `meter` word:
`meter train=<id> cars=<n> kinds=<FCHM…> lead=<cart> pos=<x>,<y>,<z> speed=<b/t> maxspeed=<b/t>
fuel=<min engine fuel> rail=<block>[(on|off)] seated=<n of cars> refusal=<BAIL|none> packing=<bool>`,
then the spacing and ribbon figures the class already prints. `maxspeed` is the largest horizontal
`getDeltaMovement` any car of the train showed since the previous line, recorded per tick from
the mixin's tick head (AbstractMinecartMixin.java:453) into a map the sampler drains; it is the one
figure that catches a single-tick spike. A loose cart's line is `meter cart=<id> kind=<F> pos=…
speed=… maxspeed=… rail=…`. Keep the `LOGGER.isDebugEnabled()` gate so the cost is zero otherwise,
and keep the meter's rule that a train measuring no pair prints `n/a`, never `0.00`.

**Done when.** With the M1 roster placed, `debug.log` gains exactly seven `meter train=` lines and
one `meter cart=` line per second, ids stable across lines; on the abtestloop control, no furnace
consist's `maxspeed` exceeds 2.50 in a ten-minute run.

### As built

The sampler runs on `ServerTickEvents.END_LEVEL_TICK`; `END_WORLD_TICK` does not exist in the
lifecycle-events version this build uses. It reads trains through `Train.peek`, `lastLeader` and
`lastBail`, which rebuild nothing, so turning DEBUG on changes no tick. `maxspeed` is noted in
`CartImpact.strikeNearby`, where X1 reads the same movement, and the mixin's tick head is untouched.
The line goes past the Build's format: every line carries `tick=`, blocks are registry ids, and the
old spacing and ribbon figures are tokens too (`mass`, `pairs`, `gap_worst`, `gap_mean`, `turn`,
`nodes`, `span`, `checked`, `offset_worst`, `resets`, `revisits`). `fuel` reads through a new
`minecarttweaks$fuel()` on `AbstractMinecartExt`.

The Done when miscounts the roster: its lone chest cart is a `meter cart=` line, so the M1 roster
gives six `meter train=` lines and one `meter cart=` line a second. Observed: 3,600 `train=` lines
over the first ten-minute run, six a second, with steady ids. On the `abtestloop-a` control, ten
minutes long, the `H-C-C-F-H` consist's `maxspeed` read 2.50 on 599 of its 600 lines and never more.

## M4 — Event lines with identities — **done**

**Build.** One DEBUG line per occurrence, each starting `event=<kind>` and naming its trains by
M3's ids, prose after the tokens where the line already has some:

| Kind | Site | Tokens |
|---|---|---|
| `sever` | TrainCoupling.java:886 and :919 | `train= at=<car> reason=<DIVERTED or the Bail> into=<idA>,<idB>` — the halves' ids are the lowest UUIDs of each side of the cut |
| `stall_reverse` | MinecartFurnaceMixin.java:334-347, when the reversal fires | `train= cart= pos= rail=` |
| `strike` | CartImpact.java:160-168, when `gained > 0` | `striker=<cart> struck=<cart> trains=<idA>,<idB> closing= shared= gained=` |
| `overlap` | CartImpact.java:88-91, a struck cart of another train within 0.5 blocks horizontally | `cart= other= trains= distance=` |
| `overspeed` | the per-tick observation of M3, when a cart's horizontal speed exceeds its own ceiling (`AbstractMinecartExt` :56; the mod's inject at AbstractMinecartMixin.java:335) by more than 1% | `cart= train= speed= ceiling=` |
| `switch` | SwitchedRailBlock.java:292 and :347 | `pos= from= to= spanned=<TrainCoupling.crossedByTrain(level, pos)>` (TrainCoupling.java:745) |
| `refusal` | TrainPlacer.java:116, on change only as now | `train= why=<Bail> car=` |

The `overspeed` observation and X1's clamp read the same movement at the same site; build them
together.

**Done when.** The M1 roster placed on the torture track logs `event=strike` lines within its first
minute; `grep -o 'event=[a-z_]*' run/logs/debug.log | sort | uniq -c` shows each kind that
occurred; a redstone block set over rcon beside the switch at `(-14,-53,-2)` while the `z=-2`
consist stands on it logs `event=switch … spanned=true`.

### As built

The sites are the Build's. `switch` is also written where `updateState` throws or holds a switch and
where the wrench throws one, and carries `applied=`: `applied=false` is a change held back because a
train lies across the switch, which Switch-safe does not count. `spanned` is read through
`TrainCoupling.crossedByHeldTrain`, which rebuilds no train, so DEBUG changes nothing. Every line
carries `tick=`, blocks are registry ids, and prose after the tokens never contains `=`.

`overspeed` measures horizontal length, which is what vanilla clamps on rails
(`NewMinecartBehavior.calculateTrackSpeed`); the per-axis clamp at AbstractMinecart.java:378-380 is
`comeOffTrack`, for a derailed cart only. The audit's `speed=3.54` readings were a 45° slope read
as a 3D length. The limit is the ceiling plus 1% plus what vanilla adds after its clamp, a lit
powered rail's boost or a kick from rest and a grade's pull: 5.21 at a ceiling of 5.0, 2.64 at the
furnace train's 2.5. It is written once each time a cart crosses it.

Observed on the torture track once M1–M5, X1 and X2 had landed: strikes from `+8` ticks, every kind
occurred, and a signal change at `(-14,-53,-2)` with the `z=-2` consist across it logged
`event=switch … spanned=true applied=false`.

## M5 — Sampler and verdict in `scripts/torture.py` — **done**

**Build.** Python 3, standard library only, in the mod's `scripts/`. A persistent rcon client that
authenticates once and terminates each read with a marker command, since a long reply arrives as
several 4096-character packets sharing the request id (RconClient.java:125-131); a working copy
from the audit is at the session scratchpad's `rcon.py` and `rigaudit.py`. Subcommands: `--roster`
prints the placed trains as kind sequences with cells; `--selfcheck` and `run` execute the
protocol in the testing section for the group given by `--group` (default `torturetrack`), sampling
once a second of game time: heartbeat, every `mtx_<group>` cart's UUID, position, motion and link
slots, the edge set, the crash-reports listing; on exit — clock elapsed or rcon dead — parse the
log from the mark for `meter` and `event` lines, evaluate the five clauses and three vacuity
checks, print the verdict table, write the series and verdict as JSON under `run/torture/`, reset
the group and restore the yard box. `--selfcheck` runs 90 s on `abtestloop-a` (stable, no crash),
removes one coupling over rcon at 30 s (`data remove entity <uuid> "fabric:attachments".
"minecarttweaks:link".slotA` on both ends) and sets one engine's `RealFuel` to 0 at 45 s so that
train coasts to rest, and must report exactly one sever and one stall.

**Done when.** `python3 scripts/torture.py --selfcheck` reports the one injected sever and the one
injected stall and nothing else, and `python3 scripts/torture.py run` on the torture track ends
with a verdict table, a JSON file and the yard box restored — whatever the verdict says.

### As built

The self-check cuts the engine's fuel at `+300` and the coupling at `+900`, not at 30 s and 45 s: a
train takes about 450 ticks to come to rest once its fuel is gone, so a 45 s cut first reads as a
stall after the 90 s run has ended. A stall must hold at every sample across its window, not just
at its two ends, or a train lapping a short loop in step with the sampling reads as still. A queued
train is a stalled one with a car within 3 blocks of a train that stalled earlier. Travel is speed
integrated over time; the chord between samples undercounts it about threefold. `torture.py`'s
docstring holds these definitions.

Observed: `--selfcheck` reported exactly the injected sever and stall three times on the code before
this work, and again once M3 and M4 were in and it read real `meter` lines. `run` ends with the
table, the JSON under `run/torture/` and the yard box restored, and with rcon dead it says the reset
and restore were skipped.

# Part X — Crash guards

## X1 — Bound the sweep box and repair absurd movement in strikeNearby — **done**

**Build.** In `CartImpact.strikeNearby` (CartImpact.java:63-85), before the sweep box is built:
read the cart's own ceiling through `AbstractMinecartExt` (:56); if the movement is non-finite or
its horizontal length exceeds twice that ceiling, emit M4's `overspeed` event, clamp the movement
to the ceiling — per axis as vanilla does at AbstractMinecart.java:378-380 — write it back with
`setDeltaMovement`, and sweep with the clamped vector. The box is then at most `2·ceiling + 1`
blocks long and `getEntities` cannot be handed an inverted section range. This is the guard;
the injector is P1.

**Done when.** A cart on the yard given `Motion:[1000000d,0d,0d]` over `data merge entity` logs
`event=overspeed` and the server keeps ticking; the torture track no longer crashes in its first
five minutes.

### As built

The absurd bound is twice M4's overspeed limit, not twice the ceiling, so the powered-rail and grade
terms never trip it. A vertical movement larger than the level's height is absurd too, since
`BlockCollisions` walks y unclamped, and a cart on the old minecart behaviour uses a fixed 2.0, what
`OldMinecartBehavior` stores against its 0.4 ceiling. The clamp scales the horizontal movement to
the ceiling as vanilla does on rails and sets a non-finite part to 0.

The Done when's `Motion:[1000000d,0d,0d]` never reaches the mod: loading entity data zeroes any
Motion component over 10 (Entity.java:2158). Observed instead: `Motion:[9.9d,0d,9.9d]` logs
`event=overspeed … speed=14.00 … clamped=true` and the server keeps ticking, and `[7d,0d,0d]` logs
`clamped=false`. No torture run since X1 landed has crashed.

## X2 — RailPath refuses to cast a non-rail block — **done**

**Build.** `RailPath.shape` (RailPath.java:316-328) casts `state.getBlock()` to `BaseRailBlock`
without asking `BaseRailBlock.isRail(state)` first; the Sep 17 crash is that cast on air. Return
`null` for a non-rail state and make every caller stop the walk on it. Leave `neighbour`
(:332-344) returning its candidate whether or not it is rail: `TrackRibbon.blockedBeyond` relies on
reaching and testing the block past the end node, which is what the audit's T5 investigation found
survives of that hypothesis. The cast is the fault, not the reach.

**Done when.** With a rail removed over rcon between two coupled carts on the yard, the walk logs
nothing fatal and the server stays up; `grep -n 'BaseRailBlock)' RailPath.java` finds no unguarded
cast.

### As built

`RailPath.shape` and both `neighbours` return null for a non-rail block, and each caller stops
there: no path, no crossing, no grade, `OFF_RAILS`. `TrackRibbon.onward` and `RailContext.of`, the
two callers outside the class, changed with it. The cast at RailPath.java:356 is the only
`(BaseRailBlock)` cast in `src/`, behind the check at :341.

Observed: with the rail at `(-36,-53,7)` taken out under the rigged `abtestloop-a` train, 200 ticks
ran with no ERROR and only `RIBBON_SHORT` refusals, and the rail was put back.

# Part P — Physics

## P1 — Collision momentum on a placed car is laid along its rail and ceilinged — **in progress**

**Build.** `shunt` (CartImpact.java:138-173): re-lay the gained movement onto the pushed car's own
tangent with `TrainCoupling.alongTrack`, as `EntityMixin.java:88` already does for a shove — the
audit's C3; clamp the pushed car's resulting horizontal speed to its ceiling, the clamp a placed car
otherwise never meets; and guard the pair with `CartInteraction`'s `coupled` test
(CartInteraction.java:92) rather than `TrainCoupling.sameTrain` (:103), so a chain that reads as two
trains across a chunk border is not shunted on top of vanilla's own push — C2. `slowTrain`
(:235-246) gets the same clamp. Rebase on M4's `strike` line in the same method. `TrainPlacer.fouled`
(TrainPlacer.java:315, 339-343) asks `Linkable.coupled` before calling a cart foreign — T3, the same
pattern one file over.

**Done when.** R1's `overspeed` count is zero over ten minutes, and every `strike` line's `shared`
lies between the two carts' speeds along the contact heading.

## P2 — The switch guard reads the geometry and runs every tick — **in progress**

**Build.** `orient` (SwitchedRailBlock.java:277-283) refuses to steer when
`TrainCoupling.crossedByTrain(level, pos)`, as `occupied` (:357-358) already does for the settle;
the diverted check in `TrackRibbon` (:473 per the audit) compares the node's recorded entry and
exit against `state.getValue(SHAPE)` rather than deriving the curve from `POWERED` — S1, the cut
across rails that never moved; `holdTrain` (TrainCoupling.java:874-882) runs `divertedCarIndex`
before the refusal return, not only after a clean seating — S3; and the airborne early return
(:914-916) moves after `faultHeld` so another car's count still clears — S5.

**Done when.** No `event=switch … spanned=true` and no sever with the diverted reason in R1; a
redstone block set beside `(-14,-53,-2)` while the `z=-2` consist stands across it logs a `switch`
line with `spanned=true` and the shape unchanged.

## P3 — A train across a switch re-seats, and the switch's link shape follows its throw — **done**

**Build.** `TrackRibbon.extendTo` (:768 per the audit) stops one block short of a switch, leaving
every car beyond it `RIBBON_SHORT` with no sever and no recovery — T4; let it record the switch by
its current `SHAPE` exits. `linkShape` (SwitchedRailBlock.java:147) always returns the forward
curve while `restingShape` (:114) honours `divergesForward`, so across a default switch the walk
steps to the wrong neighbour and two closing carts can pass through each other — C1, which the
audit asked to confirm in-world first: place two carts closing across `(-1,-53,-12)` in its default
state and see whether they strike.

**Done when.** The `z=-2` consist, whose sign cell is the switch at `(-14,-53,-2)`, shows
`seated=8` in its first `meter` line after placement; the two-cart probe across `(-1,-53,-12)`
logs a `strike`.

### As built

T4 is built: `grow` walks onto a switch only where its current `SHAPE` has an exit on the edge the
walk comes in by, and the recorded node leaves by that shape's other exit. The `z=-2` consist's
first `meter` line after placement reads `seated=8`, its ribbon 59 nodes long.

C1 is not built, because the probe did not reproduce it. The switch at `(-1,-53,-12)` cannot stand
in its default state for the probe: the detector rail at `(0,-53,-12)` throws it for any cart
approaching from the branch. On a copy at rest built at `(4000,150,4000)` (facing north, laid
`south_east`), two carts closing from the branch and the joined end struck at 1.5, 3.0 and 5.0
blocks per tick each, without any change to `linkShape`. The change as built, following the laid
curve rather than `divergesForward`, also stopped `CartInteraction.crossing` catching two fast
carts merging from the branch and the open end early, so it was left out.

## P4 — A meeting leaves the train's lie settled and the shove in the right frame — **in progress**

**Build.** `reachTrain`'s record (TrainCoupling.java:1018-1026) gets the epoch guard and
`train.relaidOn` call that `recordTrack` has (:315-326), so a ribbon reset at a meeting does not
leave `orderSign` at zero and the train refused `ORDER_UNREADABLE` or laid backwards — K2. The
pushback in `AbstractMinecartMixin` (:407-436) reads the struck car's own velocity, not
`drivenCar`'s, so a train rounding a curve pushes a player clear instead of walking through — C4.

**Done when.** No `ORDER_UNREADABLE` refusal follows a `strike` in R1, and every head-on strike is
followed within 40 ticks by both trains' `speed` changing sign.

## P5 — Ribbon reach is tied to the length rule, and the field stall is chased — **in progress**

**Build.** `MAX_NODES` (TrackRibbon.java:50) becomes a function of `max_train_length`
(ModGameRules.java:38) and the seating pitch, `extendTo` splits its budget between the two ends
rather than spending it on the near one — S4; `append` and `prepend` (:822, :836) enforce the bound
that `grow` does — L4. T5, the train that stops with its engines lit and `blocked`, `pressing`,
`jammed` and `packing` all false, has no fix to name until M3's data catches one: the stall report
gives the train, the tick, the rail under its leader and its refusal state, and the fix is whatever
that shows.

**Done when.** R1's stall count is zero, and no `meter` line's ribbon node count exceeds twice the
train's car count plus the reach margin for the whole run.

## P6 — A collision leaves every car under its ceiling — **planned**

**Build.** Vanilla's cart-to-cart push is the injector P1 left. `pushOtherMinecart`
(AbstractMinecart.java:543-575) runs for every pair `CartInteraction.pushes` allows, since
`shoveReaction` hands a cart pair back to vanilla, and leaves each cart 0.2 of its own velocity plus
the pair's mean — up to 1.2 times the ceiling, which vanilla clamps only on the next move.
`EntityMixin.shoveTrain` (EntityMixin.java:67) then lays that push on the car the train is driven
by, divided by the train's mass, with no ceiling, and the placer copies that car's speed down the
train. For two trains the pair has already exchanged momentum in `CartImpact.shunt`, so it is
pushed twice. Clamp the driven car's horizontal speed to its ceiling where `shoveTrain` lands the
push, with the clamp P1 gave `shunt` (`ceilinged`, CartImpact.java:215), and settle whether
vanilla's push should run at all for a pair `shunt` already settles: its velocity half counts the
meeting twice, while its positional half, a twentieth of a block a tick, may be what keeps two
touching carts from sitting inside each other.

**Done when.** A ten-minute torture run logs no `overspeed`, and the `abtestloop-a` control still
reads `maxspeed` 2.50.

## P7 — A fouling cart is the end of the track, not a refusal — **planned**

**Build.** train-identity.md's S17 as written there. `SEAT_FOULED` leaves the fouled car to vanilla,
which drives it back into the train that fouled it, so two trains that meet each refuse and stand
inside one another for the rest of the run — most of every run's overlap count. Resolve the fouling
cart to an arc on this train's ribbon, take the nearest one ahead of the leading car as a limit, and
feed it through the path `blockedBeyond`, `pressing`, `packing` and the `blocked` draw-back already
take (TrainPlacer.java:212-271), so every car keeps its seat and the train packs up short.
`SEAT_FOULED` survives only for a fouling cart with no arc on this ribbon. S17's three questions are
resolved by building it: that case, whether two trains drawing back from each other oscillate, and
whether the swept half of `fouled` (TrainPlacer.java:377) keeps refusing.

**Done when.** S17's own: two trains driven head-on stop with every car seated, no `SEAT_FOULED` and
no loose car on either side, and both resume when one is driven away. Then a ten-minute torture run
reports no stall refused `SEAT_FOULED`.

## P8 — A head-on meeting leaves both leading cars on their rails — **planned**

**Build.** After a head-on meeting a leading car can stand on the block above its rail — in the P4
run two trains' leaders stood on `(-20,-52,-3)` together. Resting on another cart's box it reads as
on the ground, so `cutIfLost` counts `LEADER_OFF_RIBBON` against it instead of waiting as it does
for a car in the air (TrainCoupling.java:925), and severs it ten ticks later; the cut engine runs on
alone, leaves the track and stalls. What lifts the car is not known. Reproduce a head-on meeting of
two trains at 2.5 blocks a tick, on a straight and at the `(-20,-53,-3)` corner, frozen and stepped
a tick at a time, reading each leading car's position, `onGround` and movement and which code moved
it. Fix the lift where it happens, and let a car resting on another cart wait as one in the air
does rather than count towards a sever. Resolve by building it whether the lift is vanilla's
collision against a solid cart box — `CartInteraction.COLLIDE` makes two trains' carts solid to each
other — the placer seating a car into the other train, or the push P6 caps.

**Done when.** Repeated head-on meetings at 2.5 blocks a tick each leave both leading cars on their
rails and sever nothing, and a ten-minute torture run shows no `LEADER_OFF_RIBBON` sever.

# Part R — The bar

## R1 — Ten minutes clean, twice — **in progress**

**Build.** Nothing. `python3 scripts/torture.py run` after each P stage lands, and again at the end.

**Done when.** Two consecutive runs pass all five clauses and the three vacuity checks. The two
verdict tables are pasted into this stage's "As built", with the commit each ran against.

---

## Open decisions

- **The lone chest cart at `(-14,-53,-14)`.** A one-cart strip with its own sign, so treated as
  an intended obstacle. Settled by the user.
- **Where the `1e150` comes from.** X1 clamps it and P1 removes the injector the evidence points
  at, but neither names the tick. Settled by the first `overspeed` event under X1, which will carry
  the cart, the train and the speed.
- **Whether a bounce is "appropriate" beyond not failing.** The clause fails on overlap, overspeed
  and a sever after a strike; the momentum figures are reported for reading. Settled by the user
  looking at R1's strike table.
- **The audit's remaining findings.** S2 (restart), U2 (stale held heading — the track has no
  station), K1, L1, L2, L5 and D3 are outside this bar and stay in the audit.

## Reference: the torture track

Measured 2026-09-23 by flood-filling by shape from the anchored cells. 296 rail blocks in one
connected component, no dead ends, `x -20..1`, `z -28..-1`, `y -57..-46`: a hill climbs seven blocks
along `z=-3` and a dip falls four along `z=-5`. By kind: 247 plain rail, 34 activator rails (all
powered), 4 powered rails (all on, at `(-9..-6,-53,-1)` — no park brake anywhere, so no train has a
legitimate stop), 3 detector rails, 2 switch rails at `(-14,-53,-2)` and `(-1,-53,-12)` (both
`south_east` at rest), 6 junction rails at `(-7,-6)`, `(-14,-6)`, `(-14,-9)`, `(-14,-11)`,
`(-17,-11)`, `(-17,-9)`. Chunk borders at `x=0` and `x=-16`. Plan view, `x` across from `-20`
(left) to `1`, `z` down; `.` rail at `-53`, `'` above, `,` below, `/` `\` ascending, `A` activator,
`P` powered, `D` detector, `S` switch, `J` junction; carts as F furnace, C chest, H hopper; `#` a
sign cell:

```
      x=0987654321098765432101
z= -28                 ....
z= -27                 ...F
z= -26               .....C
z= -25               .....H
z= -24             ....  .C
z= -23             ....  .C
z= -22           ....    .H
z= -21           ....    .C
z= -20         ....  .\'\.F
z= -19         ....  \ .A.#
z= -18        ...    ' ..A.
z= -17       ....    \ .A..
z= -16      ....FHCCF. ..A.
z= -15     .... C      .A..
z= -14     . C  #      #CCFH.
z= -13     . #  .\'\\'\\'\. .
z= -12    .. .......      SD.
z= -11 ...J..J..''//////\..
z= -10 C  .  .'.'...
z=  -9 H .J.AJ..'A'///\.
z=  -8 H ..  .''//'\,  F
z=  -7 F ..  .  'A' \  F
z=  -6 #.....JAA'AA.J#CC
z=  -5 ..\\\\.\\\'A'.
z=  -4 .     .     '
z=  -3 .\////./''A''
z=  -2       #FCCHHFFC.
z=  -1       .D...PPPP.
```

## Reference: rig audit, 2026-09-23

| Measured | Value |
|---|---|
| Signs naming `torturetrack` | 7, at `(-1,-19)`, `(-11,-14)`, `(-4,-14)`, `(-14,-13)`, `(-20,-6)`, `(-6,-6)`, `(-14,-2)` (all `y=-52`) |
| Anchored cells / intended carts | 42 / 35 |
| Carts placed, trains formed | 32, 10 — on both placements |
| Placement dump time | 12 ms and 7 ms for 32 carts |
| Fuel after placement | 71,999 of 72,000 ticks on every engine (`furnace_max_burn_time`) — 60 minutes |
| Hopper carts | disabled while standing on a powered activator, enabled otherwise; an enabled one sweeps dropped chains, so chain counts are not a sever instrument |
| After `mtxrig:reset` | no carts of any kind with tag `mtx`, no iron chains, no markers |

The rig's scan model: `spawn.mcfunction` seeds an anchor under each sign of the group (pass 1),
grows anchors along wool-marked rail for up to 32 generations (`spread`), then places a cart on
every anchored cell inside the box (pass 2), coupling as it goes, and tags the lot `mtx` and
`mtx_<group>`. Working tags `mtx_new`, `mtx_prev`, `mtx_fresh`, `mtx_anchor`; `find` leaves
`mtx_found` markers on signs, `trace` leaves `mtx_trace` on rails. Wool over the rail names the
cart: green furnace, gray chest, magenta hopper, yellow plain.

## Reference: rcon

Vanilla's `RconClient` answers a command with one or more packets of type 0 sharing the request id,
each at most 4096 characters (RconClient.java:125-131), with no terminator. A client that sends a
second, recognisable command straight after the first and reads until that reply arrives gets the
whole answer. Probes: `execute if entity` and the bare `execute if block` return `Test passed` /
`Test failed`; `run say` returns nothing over rcon; `@e[type=#minecraft:minecarts]` is undefined in
26.2 and matches nothing, so name the seven cart types. A persistent socket answers in about
0.15 ms per command; `scripts/devserver.sh … rcon` in about 300 ms.
