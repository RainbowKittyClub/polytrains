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
against a dev server of its own; so are P9 and P10, planned from the first run with P6 to P8 in, and
P11, planned from the first with P9 and P10 in. P12 and P13, and the rest of P5, were planned after
R1 from what it does not measure; they are independent of each other, and R1 runs again once they
land. P14, planned from the runs with P13 in, builds on it. P15 to P17, planned from P14's runs, are
independent of each other, and R1 runs again once they land. P18 and P19 come from the user's
reports of `3bjump` after them, P19 building on P18.

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

**With P6 to P8 in, the severs left are `DIVERTED` and the stalls are jams that clear.** One
ten-minute run on the torture track against the last run before them:

| After | Sever | Stall (queued) | Overlap | Overspeed | Switch-safe |
|---|---|---|---|---|---|
| P5 | 12, 11 `LEADER_OFF_RIBBON` | 9 (2) | 23,331 | 40 | 1 `DIVERTED` sever |
| P6–P8 | 8, all `DIVERTED` | 2 (10) | 17,851 | 0 | 8 `DIVERTED` severs |

Driven trains ran 10,975 to 17,708 blocks against 2,966 to 11,029. No car was lifted off its rail,
nothing went over its ceiling, and no stall was refused `SEAT_FOULED`; every stall cleared, after
600 to 1,500 ticks. The `DIVERTED` severs come from a switch changed under a train whose span read
empty for a tick, and from a switch changed beyond a train's end and then reversed onto. The engines
they cut loose run into other trains' spans, which is most of the overlap: the worst pair is a
four-car train and an engine two `DIVERTED` severs left alone. They are planned as P9 and P10.

**With P9 and P10 in, only overlap is left.** The first ten-minute run with them in passed every
clause but Bounce: no sever, 28 couplings held at every sample, no stall, no overspeed, Switch-safe
with 321 spanned changes held, and driven trains running 14,940 to 20,619 blocks. Bounce failed on
6,875 `overlap` lines alone — 2,291 episodes, 2,243 of them under ten ticks and none longer than 28,
spread across the track, at every distance from 0.0 to 0.5 — cars of two trains passing into one
another in pile-ups, the strike between them logged only once they were inside each other. It is
planned as P11.

**R1 is blind to low speed, to a jump, and to a coupling stretched past its link.** R1's two runs
were at `max_minecart_speed=100`. At 40 the user sees cars detach and their chains stretch across
the track before they snap back to the train. The samples of both R1 runs show it too: a coupled
pair farther apart than a link (3 blocks) in 28 and 22 of 601 samples, worst 8.29 and 9.39 blocks,
which only a car the placer has let go can be, so it is not packet delivery. On the `3bjump` rig
(box `{x:-18,y:-53,z:25,w:10,d:10}`), a strip ending eastward whose westward end curves round and
jumps back onto the eastbound track, the train turns round in mid-air: the stall check counts a tick
stalled when the leading car shows next to no horizontal movement, which a car climbing or falling
steeply does as much as one wedged against a buffer. A run switched from 100 to 40 part-way logged 8
overlaps and 4 overspeeds, worst 2.52, the overspeeds from the ceilings dropping under trains
already moving, so it says nothing clean about low speed. They are planned as P12 and P13, and P5's
bound is settled as the trim window.

**With P12 and P13 in, coupling holds and overlap is back.** Read through P13's Coupling clause,
both R1 runs fail it: a coupled pair past a link in 30 and 26 of 601 samples, worst 8.29 and 9.39
blocks. With P13 in, two consecutive runs at 40 passed every clause, and a run at 100 passed
Coupling, widest 2.02, but failed Bounce on 3 overlap lines, one episode of two ticks. Overlaps
showed in 7 of the 16 runs after P13's tether, against none of 5 before it; each one traced was on a
slope and involved the lone cart or a car let go. They are planned as P14.

**With P14 in, what is left fails no clause every run.** Jams that hold two trains to the end of a
run failed Stall in three of the eight runs on P14's server before its last fix, in six of P13's at
40 and in one of P5's. The corner clips P14's trace found, cars of two trains inside one another by
up to 0.3 on curves and junctions while `CartInteraction` lets them pass, stay short of the overlap
line, but one wedged the lone cart into a train's seat. And a car moved off its rails or set back
onto them is outside every guard a step on the rail has. They are planned as P15 to P17.

## How these stages get tested

On the mod's own dev server (`run/server.properties`: game port 25603, rcon 25579, password in the
file), against the rig group `torturetrack`, which `mtxrig:rig` places from the signs it finds
within 16 blocks of `(-10,-54,-15)`, the middle of the track a block under its rails, and 8 above or
below, which stops short of the sign at `(-9,-45,-7)` it has never placed. `TrainMeter` and the
event lines write at DEBUG, which Loom's generated log4j routes to `run/logs/debug.log` only (root
logger DEBUG, `net.minecraft` capped at INFO, rolled on startup and at 200 MB). The sampler
`scripts/torture.py` (M5) drives the run and reads both the world over rcon and the log from a mark.

A stage is finished when its "Done when" is observed on that server by the sampler or by the probe
the stage names. The user's in-world confirmation is waived for every stage in this document; that
waiver rests on M5's self-check, which has to catch an injected sever and an injected stall before
any verdict from it is believed.

The bar is six clauses and three vacuity checks, each machine-checked by M5:

| Clause | Fails when | Read from |
|---|---|---|
| Alive | rcon stops answering before game time has advanced 12,000 ticks from the rig call; or `run/crash-reports/` gains a file; or the log gains an `ERROR` or a stack trace from the mod's package. | heartbeat, directory listing, log scan |
| Sever | the set of link edges — unordered pairs of cart UUIDs from the `minecarttweaks:link` slots — at any sample differs from the set at the first sample; or an `event=sever` line appears. | per-second link dump; M4 |
| Coupling | two coupled cars stand more than a link (`maxLinkDistance`, 3 blocks) apart at any sample, with no allowance. The worst pair is named. | per-second positions and link dump |
| Stall | a train with at least one furnace car has every car within 5 blocks of where it stood 600 ticks earlier. Reported per train; a train queued behind a stalled one is reported too, and named as queued. | per-second positions |
| Bounce | an `event=overlap` (cars of two trains within 0.5 blocks); or an `event=clip` (their boxes inside each other deeper than 0.01 at the end of a tick, P16); or an `event=overspeed` (horizontal speed above the cart's own ceiling by more than 1%); or a sever within 40 ticks of a strike. Momentum sharing at each strike is reported, not judged. | M4 |
| Switch-safe | an `event=switch` with `spanned=true`; or a sever whose reason is the diverted-track one. | M4 |

Vacuity: fewer than ten strikes between different trains, any driven train travelling under 100
blocks, or fewer than 35 carts at the end fails the run as "not a torture", whatever the clauses
say — the abtestloop control passed every clause in September precisely because its trains never
met.

The protocol, which M5 automates: check the two rules over rcon, `max_minecart_speed` against
`--speed` (100 unless given); set the search radius; reset and rig the group round the track's
middle; record the log line count and the game time; sample every second until game time has
advanced 12,000 ticks or rcon dies; then parse the log from the mark, print the verdict table, save
the series, reset the group and put the search radius back. After a crash: `devserver.sh restart`,
then kill `@e[tag=mtx]` before the reloaded carts re-accelerate.

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

## M3 — TrainMeter samples the level, with identities — **done**

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
| `clip` | TrainMeter.sample at the end of each level's tick (added by P16), once per pair as two trains' cars come to intersect deeper than 0.01, the least of the three axis overlaps | `cart= other= trains=<idA>,<idB> depth=` |
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

## P1 — Collision momentum on a placed car is laid along its rail and ceilinged — **done**

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

### As built

Built as the Build says, `shunt` now taking the level and the struck train's speed at the struck
car, and its gain laid along the rails and clamped. It did not reach its own Done when alone — 29 to
51 overspeeds a run while it was the only cap — because vanilla's push was the larger injector,
which P6 capped. Both R1 runs logged no `overspeed`, and `shared` lies between the two speeds by
construction, being their mass-weighted mean.

## P2 — The switch guard reads the geometry and runs every tick — **done**

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

### As built

`orient` refuses under a spanning train and logs it `applied=false`; `diverted` compares the
recorded edges with the held `SHAPE`; `holdTrain` counts a diverted car on a refused tick, one fault
reading a tick; an airborne car's own count stands while every other clears. It also needed a change
the Build did not name: `steer` read a car's approach off its diagonal heading on a curve and asked
for the straight run under the train's own leader, so it now traces back to the edge the car came in
by. `DIVERTED` severs went on until P3's growth across a switch and P9's steady occupancy. With the
consist frozen across `(-14,-53,-2)` and a redstone block set beside it, the line read `spanned=true
applied=false` and the shape stayed. Both R1 runs passed Switch-safe, holding 227 and 237 changes
asked of a spanned switch.

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

## P4 — A meeting leaves the train's lie settled and the shove in the right frame — **done**

**Build.** `reachTrain`'s record (TrainCoupling.java:1018-1026) gets the epoch guard and
`train.relaidOn` call that `recordTrack` has (:315-326), so a ribbon reset at a meeting does not
leave `orderSign` at zero and the train refused `ORDER_UNREADABLE` or laid backwards — K2. The
pushback in `AbstractMinecartMixin` (:407-436) reads the struck car's own velocity, not
`drivenCar`'s, so a train rounding a curve pushes a player clear instead of walking through — C4.

**Done when.** No `ORDER_UNREADABLE` refusal follows a `strike` in R1, and every head-on strike is
followed within 40 ticks by both trains' `speed` changing sign.

### As built

K2 relays the lie only where the train's far end lies behind its leading car on the grown ribbon:
the Build's form laid a train backwards whenever the named leader was running back into its own
train — a struck train after a head-on meeting, a released train, a fallback head after a rebuild.
It is not what keeps `ORDER_UNREADABLE` away after a strike, since a reset with the leader moving
already settled the lie; one with the leader standing and a packed neighbour is left as it was. C4
reads the touched car's own velocity, which the placer writes along that car's own seat.

Neither R1 run logged an `ORDER_UNREADABLE` refusal. Both trains reversing after every head-on
strike no longer describes the design: since P10 one train of a pressing pair turns and the other
follows. Of 1,311 and 1,190 head-on meetings, 60% had the pair apart and no longer closing forty
ticks later, and the user accepted that as the bounce.

## P5 — Ribbon reach is tied to the length rule, and the field stall is chased — **done**

**Build.** `MAX_NODES` (TrackRibbon.java:50) becomes a function of `max_train_length`
(ModGameRules.java:38) and the seating pitch, `extendTo` splits its budget between the two ends
rather than spending it on the near one — S4; `append` and `prepend` (:822, :836) enforce the bound
that `grow` does — L4. T5, the train that stops with its engines lit and `blocked`, `pressing`,
`jammed` and `packing` all false, has no fix to name until M3's data catches one: the stall report
gives the train, the tick, the rail under its leader and its refusal state, and the fix is whatever
that shows.

**Done when.** R1's stall count is zero, and no `meter` line's ribbon node count exceeds twice the
train's car count plus the reach margin for the whole run.

Decided after R1: the bound is the trim window, `6n + 16` blocks of span for a train of `n` cars,
held strictly. `holdTrain` returns before `trimRibbon` when a seating is refused, so a refused train
goes untrimmed while its leading car records on; trim it too.

### As built

The node cap is the trim window of the longest train `max_train_length` allows, every node counted a
corner, plus a part-node at each end: 1,111 nodes at the default of 128, where the old 256 held no
train over 39 cars. At the cap, `append` and `prepend` drop the far end rather than the block
`record` hands over, which would leave the leading car off its own ribbon, and `extendTo` grows one
block off each end in turn, so the near end cannot spend the cap before the far end has any. T5
named no fix of its own: the stalls the runs caught came from P7's fouled pairs, P8's engines cut
loose off their rails and P10's jams, and R1 reported none.

Held to the trim window, the bound failed only for a refused train, which `holdTrain` returned from
before trimming while its leading car recorded on. It now trims after every seating, refused or not,
and before any cut; both halves of a cut keep every seat on the trimmed ribbon. A linked train of
one, a car whose partners are unloaded or in another dimension, always refuses and is left
untrimmed, since its ribbon still runs under them. The trim drops whole nodes, so each end can keep
one node reaching past the window and a span can read up to two blocks over `6n + 16`. A car let go
that falls more than `1.5(n - 1) + 11` blocks behind its seat, 15.5 for four cars, loses the track
under it; R1's worst coupled pair stood 9.39 apart.

Observed on a server of its own: two ten-minute runs passing every clause and every vacuity check,
with no span over the window in 3,600 train lines each: at most 32.5, 43.9 and 56.0 blocks and 37,
51 and 64 nodes for trains of 4, 6 and 8 cars, against 40, 52 and 64 blocks. A run on the code
before, on the same server, reached 67.0 for eight cars, refused `SEAT_FOULED`, and failed Stall
once, which neither run after it did: a train caught between two others at the `(-1,-53,-12)`
switch, its engines reversing every 4 to 12 ticks while the train behind struck it at 2.50 about
every 70 ticks. Not chased: P10 named a train caught between two among its jams, and one can still
hold long enough to count.

## P6 — A collision leaves every car under its ceiling — **done**

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

### As built

Two clamps, not one. The Build's, in `EntityMixin.shoveTrain`, runs the push it lands on the car a
train is driven by through `CartImpact.stored`, which scales horizontal speed to the ceiling
`bounded` and the meter use. Alone it left 10 overspeeds a run, all vanilla's push landing on cars
the placer was not holding, such as cars loosened by `SEAT_FOULED`, so a `@ModifyArg` on the one
`setDeltaMovement` inside `Entity.push(DDD)` holds every other cart's push the same way. A
temporary trace on `setDeltaMovement` before the change attributed 62 of 71 overspeeds to
`shoveTrain` handing on `pushOtherMinecart`'s mean branch, 8 to its furnace branch and 1 to
vanilla's push on a loose car; the placer's copy down the train was not an injector.

Vanilla's push was kept. Over 16 two-train meetings in three variants, capping or cancelling it made
no difference to overlap, and once, cancelled, it let a jam build vertical movement of −430 to
−7,748 blocks a tick for X1 to clamp. It still counts a meeting twice after `shunt`, left open.

Observed on a server of its own: four ten-minute runs with no `overspeed`, and the control at
`maxspeed` 2.50 on 599 of 600 lines. The first run with P6 to P8 in logged no `overspeed` either.

## P7 — A fouling cart is the end of the track, not a refusal — **done**

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

### As built

A cart of another train standing on the ribbon is an end of usable track at either end, not only
ahead: the seats that foul are mostly at the tail, when a shoved or reversing train backs into
another. The limit is found by stepping the end car's box along the ribbon until it clears the cart
(`firstClearArc`), since two boxes on a diagonal still touch with centres 1.39 apart. A train
blocked at the tail and jammed ahead compresses and stops; the draw-back never passes the end ahead
nor lands the leading car in a taken seat; a leading car seated last tick has its speed held to its
ceiling. The swept half of `fouled` checks the car's run in boxes along the ribbon, new ground only.

S17's questions: a fouling cart with no arc on the ribbon keeps `SEAT_FOULED`, now 0.3% of fouls
rather than 26%, and so does a case S17 did not name, a cart already inside the train's span, 86%
of what remains and the product of collision pass-throughs. Two trains drawing back do not
oscillate on open track or at a buffer, but two that fill a small loop end to end stall-reverse
together every four ticks and never move (P10). The swept half keeps refusing a real pass-through.

Observed on a server of its own: head-on, four cars against four over 400 stepped ticks, no
`SEAT_FOULED`, no overlap and every car seated, where the code before logged 12 refusals and 7
overlaps; one train pushed the other to a buffer and packed there, and both left again by stall
reversal. A ten-minute run had no stall refused `SEAT_FOULED`, nor had the first with P6 to P8 in.

## P8 — A head-on meeting leaves both leading cars on their rails — **done**

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

### As built

The lift is vanilla's collision against a solid cart box on a slope: `Entity.collide` settles the
vertical axis first, a slope step is diagonal, and the stepper only corrects a car it finds below
its slope line (NewMinecartBehavior.java:456). Climbing into a cart, a car rises the whole step and
is blocked across; coming down onto a cart's roof, it lands there and is carried over. A temporary
`setPosRaw` trace put 115 of 117 lifts in a 3,000-tick sample under `stepAlongTrack`'s move, none
in the placer and none in vanilla's push. A second source: a movement square to a slope aims a block
above the shape's first exit, the low end on ascending east and south rails.

`SlopeStep.moveOnRail` wraps the stepper's move: vanilla's move runs unchanged, then a car left
above its rail line goes back to where its box first touched a solid entity, no further than the
move covered, with `onGround` cleared. `SlopeStep.squareDownSlope` stops the square-movement aim.
Both are `@WrapOperation`s in `NewMinecartBehaviorMixin`. A refused car resting on an entity's box
with no block under it waits uncounted as one in the air does, for at most 40 ticks a fault.

It also named the `1e150`: a car blocked exactly on a block edge fails `stepAlongTrack`'s
zero-progress test, since vanilla places the target 1.0E-5 past the edge and the test wants more,
so the car loops through its move about `movementLeft / 1e-5` times in one tick, repeating vanilla's
cart push on each pass — 36.5, 73 and 895 blocks a tick on the struck cart in three samples, each
caught by X1. P6's clamp on every push stops the repeats compounding; the loop itself stays.

Observed on a server of its own: 19 head-on meetings at 2.5 blocks a tick each, on replicas of the
corner and hill, the dip and a straight, stepped a tick at a time — no leading car lifted, nothing
severed; a ten-minute run with no `LEADER_OFF_RIBBON` sever or refusal, as had the first with P6 to
P8 in.

## P9 — Switch occupancy holds steady, and a ribbon forgets a switch thrown past it — **done**

**Build.** Every sever in the first run with P6 to P8 in was `DIVERTED`, and they start two ways.
Occupancy flickers under a jammed train: at `(-14,-53,-2)` a signal change was held with
`spanned=true`, the settle three ticks later (`SETTLE_DELAY`, SwitchedRailBlock.java:71) laid the
new curve with `spanned=false`, and three ticks after that a car's request was held with
`spanned=true` again, the train lying across the switch throughout and stall-reversing every four
ticks. `spans` (TrainCoupling.java:1051) takes a train's span from the cars whose arc it can read,
so a train with cars unreadable for a tick drops out of it. The curve went down under the train, its
recorded straight run no longer matched, and the car on the switch was cut ten ticks later. And a
switch thrown while no train lies across it can still be on a train's ribbon beyond its ends, since
the trim keeps the train's length and a margin either side of the leading car, so a train reversing
onto it is seated along a shape the rails no longer have. Make occupancy steady — a car that cannot
be placed counts as lying across, or the settle waits out a train it cannot read — and have a switch
that changes shape make each ribbon holding it outside its train's span forget it from there
outward, so that growth (P3) reads it again as laid.

**Done when.** Two ten-minute torture runs have no `DIVERTED` sever and pass Switch-safe, and a
train jammed across `(-14,-53,-2)` and stall-reversing leaves the switch's shape alone.

### As built

Occupancy flickered for a reason the Build did not name. On a loop shorter than twice a train, a
car's block is recorded on two laps, and `arcOf` breaks the tie towards the leading car's lap, so
the far car read a lap off and the switch fell out of the span with the train across it. `spans` now
reads each car on the lap of the car before it, from the leading end: a seated car on its seat's
lap, any other within its own width, a leading car nothing can read at the ribbon's cursor. The
settle is unchanged, so the settle, a signal, a steer and the wrench all read one answer. "A car
that cannot be placed counts as lying across" was built and taken out: it only ever fired on wrecked
trains, and it could stop a train's own leading car steering.

`TrackRibbon.forgetThrown` walks out from each end of the train's span and, at the first switch
whose laid shape no longer joins its recorded edges, drops it and everything past it, so that P3's
growth records it again as laid. It runs at every seating, before growth in `reachTrain`, and from
`SwitchedRailBlock.onPlace` on every change of shape for trains in reach, the latter for a leading
car that steps onto a switch it has just steered. `diverted` shares its test.

Of the eight severs in the first run with P6 to P8 in, two were a switch changed under the train and
six a switch thrown past its end. Observed on a server of its own: two ten-minute runs with no sever
and Switch-safe passed, 372 and 122 spanned changes held; a consist penned across `(-14,-53,-2)`
under a signal toggled every 15 ticks for 600 ticks had the switch changed under it 12 times and a
car cut before, and neither after. The first run with P9 and P10 in had no sever.

## P10 — Two trains jammed nose to nose part instead of reversing together — **done**

**Build.** With P6 to P8 in, the stalls are jams that clear after 600 to 1,500 ticks — `a91c0477`
from +6440 to +7180 at `(-2,-53,-24)`, `8b9c9326` from +10400 to +11940 at `(-14,-53,-13)`, ten more
trains queued behind them — and a jammed pair logs a strike every tick. The furnace stall reversal
(`countStall`, MinecartFurnaceMixin.java:332) turns every engine of a train that has not moved for
four ticks (`STALL_TICKS`), so two trains pressing nose to nose stall on the same tick and reverse
together; P7 found two trains filling a small loop end to end reversing in lockstep every four ticks
and never moving. Find what holds each jam in the runs — a pressing pair, a train caught between
two, a loop filled end to end — and break the symmetry, for instance by letting one train of a
pressing pair reverse, chosen the same way from both sides. Resolve by building it whether the
reversal is the whole story.

**Done when.** A ten-minute torture run reports no stall and no queued train, and two trains filling
a small loop end to end circulate.

### As built

The reversal was not the whole story: most jams held because the stall check never fired. It counted
a stalled tick only while the cart's movement was under 3e-4 blocks a tick, and every strike wrote
movement back, so a lone engine read about 1.0 while its position did not change for 1,400 ticks.
The check now reads how far the train's leading car actually moved.

Of a pressing pair, each train's engines driving into the other at touching ends, the train whose
lowest car UUID sorts first turns; the other waits up to 20 ticks and holds 8 after it turns, and a
train never waits on one that turned within 8 ticks and presses again, so one trapped against a
buffer is not held. The reversed heading is carried along the rails to each engine. A train pulling
away from a blocked end has its leading car left to vanilla rather than seated every tick, which is
what lets a filled loop circulate, and a train under 0.05 blocks a tick starts on its engines' aim
rather than its own crawl.

The run it was planned from held a train caught between two on the `x=-2` line and the `x=-14`
column filled with cars that `DIVERTED` severs had cut loose. Not fixed: two trains whose cars
interleave after passing through each other at a junction can still stand `SEAT_FOULED`, the case P7
left and P11 takes.

Observed on a server of its own: two trains filling a 24-block loop end to end circulate, 8 and 8
cars going 13 to 34 laps in 600 ticks against 0.4 to 1.4 in 300 before, and a ten-minute run had no
stall and no queued train. So had the first run with P9 and P10 in.

## P11 — Cars of two trains never pass into one another — **done**

**Build.** With P9 and P10 in, overlap is the one clause left: 6,875 lines in 2,291 episodes, almost
all under ten ticks, spread across the track at every distance from 0.0 to 0.5 — cars of two trains
inside one another in pile-ups, the strike between them logged only once they already were, as
`b580e030` into `29d06e39` at a closing speed of 2.51 and a distance of 0.09. Find which code puts
each car inside the other — vanilla's move, which stops at a box solid to it; the placer, which
seats a train along its ribbon against where the other train stood at the time; a push; or a
junction, where `CartInteraction` answers from rail memos a tick old — and stop it there. Check
first whether an overlap line is two cars inside one another at the end of the tick or only part-way
through it, since the line is written at one car's tick head against the other car wherever it now
is; if only part-way, the measurement is what is wrong and is fixed instead. P7 traced the
`SEAT_FOULED` it left to the same pass-throughs, and the open question of vanilla's push after
`shunt` is settled here: retry cancelling it for a pair `shunt` settles, judged on overlap.

**Done when.** A ten-minute torture run logs no `overlap` and fails no other clause.

### As built

The overlaps were real. A temporary end-of-tick check of every pair of cars from two trains found 92
to 93% of overlap lines to be cars inside one another at the start or end of the tick; the one
measurement error was a car climbing a slope sweeping down onto a car on flat track 3.2 blocks
below, so `TrainMeter.overlap` also asks that the two boxes share height. A trace of every position
change put 90% of first contacts on vanilla's move of a loose or leading car through another train's
car that `CartInteraction` had called IGNORE — its rail memos and the swept-box walk both missed
pairs at junctions, switches and curves — 9% on the stepper lifting a car left below a slope into
the car above, one on the placer and none on a push.

`RailContext.meets` now intersects the rail blocks each car can be over before its next move ends,
walked along the rails over both axes of a junction and all three roads of a switch. A car already
inside another train's car is refused a step that carries it deeper, since vanilla's move stops a
box only at a face it has not yet crossed. Making crossings solid exposed a derailment: a diagonal
corner step blocked on one axis slid off its line and, on a slope past a junction, read air, so P8's
`SlopeStep.moveOnRail` now also puts back a car left below its rail or slid sideways at a corner.
Vanilla's push was kept: cancelled for a pair `shunt` settles, a run logged 18 overlaps, a sever and
a stall, and kept, none.

Observed on a server of its own: two ten-minute runs passing every clause and every vacuity check,
with no overlap.

## P12 — A train in the air does not turn round — **done**

**Build.** The furnace stall check (`countStall` in `MinecartFurnaceMixin`) counts a tick stalled
when the train's leading car covers next to no ground or holds next to no horizontal movement
(`standing`), and turns the whole train after four such ticks. A car climbing or falling steeply, or
at the top of a jump, meets that as well as one wedged against a buffer, so on the `3bjump` rig the
train turns round in mid-air. Count nothing while the leading car is off its rails, and count its
vertical movement as movement, so that only a train on its rails and going nowhere stalls.

**Done when.** On `3bjump`, over ten minutes, the train turns only at the strip's eastern end and
never in the air, and a torture run at 100 still reports no stall.

### As built

The stall check was not what turned the train. A trace of every reversal of an engine's push on
`3bjump` found all 21 made in the air to be the engine re-aiming itself off its own movement
(`aimAlong` from `accelerate`), the leading car off its rails every time; the stall check counted an
airborne leader one tick a jump, the tick it struck the face of the ramp's support and dropped,
never the four it turns on. With the leading car in the air, `holdTrain` refuses `LEADER_OFF_RIBBON`
and the cars behind it go loose; solid to one another on the ramp, they stop dead, and the engine,
climbed off the rail it was last aimed on, reads vanilla's slope pull as its movement and turns
west. A leader that then lands against the support with no movement is seated at rest and the train
starts off on its engines' aim, back the way it came; one that lands still moving is re-aimed east
by the placer. So the turn showed at 60, 64 and 70 of the speeds tried from 20 to 100, and at no
other.

A reversal read off the cart's own movement is now refused while the train's leading car is in the
air, and the rail it was refused on is stamped, so the same-rail rule goes on holding the engine
once the train lands; the placer's aim is untouched. The stall check counts nothing while the
leading car is in the air. Both read the air, off its rails and off the ground, not only off its
rails: a leader run off an unbuffered end onto the ground is off its rails too, and the stall
reversal is what brings it back. Vertical movement is not counted as movement: the air already
covers every car it was aimed at, it moved the stall threshold on slopes, and under realistic
physics it would read gravity kept in `deltaMovement.y` as movement.

Observed on a server of its own, with the `3bjump` rig as the user built it: in ten minutes at 100
the train turned 276 times over 275 jumps, every turn at the eastern end with the leading car on its
rails against the buffer, and at 60, where the code before turned it 44 times in 1,500 ticks, 178
times over 177 jumps, likewise; 1,000 ticks at each of ten speeds from 20 to 100 turned it nowhere
else. P10's loop and pressing-pair probes ran identical tick for tick, and a ten-minute torture run
at 100 passed every clause and every vacuity check. Not fixed: under realistic physics the train
cannot clear the jump, and its leader hangs on the edge of the ramp's support, on the ground,
stall-reversing every four ticks; and an engine loose on a slope while its leading car is on its
rails could still read the slope's pull as a reversal, which no run showed.

## P13 — A coupled car stays within a link of its train, at any speed — **done**

**Build.** Give `torture.py` a `--speed` for the value of `max_minecart_speed` a run is checked
against, and a clause that fails when two coupled cars stand farther apart than a link
(`maxLinkDistance`, 3 blocks) at any sample, reporting the worst. Then run at 40 from the start and
find what lets a car go loose and drift from its train — a refusal handing it to vanilla, a seat it
cannot be put back in — and what still lets cars of two trains pass into one another at that speed,
and stop each where it happens.

**Done when.** Two consecutive ten-minute runs at 40 pass every clause, the new one included, and a
run at 100 still does.

### As built

`torture.py` takes `--speed`, the `max_minecart_speed` a run is checked against, 100 unless given,
refuses a server set otherwise and records the speed in the header and the JSON. The Coupling clause
fails a run on any sample with two coupled cars more than a link apart, with no allowance, and names
the worst pair. At 40 on the code before, runs read 15 to 82 such readings, worst 20.05 blocks.

Every car found apart from its train was one the placer had let go and nothing held near it.
Junctions and switches let cars go: a crossing train, or a standing leading car's own engines aimed
round a bend, relaid a junction under a standing car, which restarted onto the other arm; and a
switch held against an arriving car turned it onto a leg it had not come by, past which the ribbon
could not grow. A junction is now laid by a standing car's drive and a leading car keeps the arm its
train recorded; a car stops short of a switch laid against it, which is an end its train packs short
of, and a train running tail first asks the switch past its far car to open. Trains ran on without
the cars they let go: a car refused `SEAT_FOULED` was left behind, and the hold that should have
stopped its train read its direction off the leading car's speed, which a train stopped that tick by
what it met no longer has. The train is now drawn back to the car's first contact, the hold reads
the way the leading car ran, and while a car stays let go its train is tethered to it, drawn back
until that car's seat is where it stands. Cars let go ran off on their own: at the speed they were
last placed with, 2.45 blocks a tick while their train stood; on their own engine under the vanilla
force model; or through their own train's seated cars, which `CartInteraction` let pass whenever
either car was seated. A car is let go no faster than its train now runs, a furnace car let go
coasts, and only cars the placer holds pass through one another. The activator sweep reaches as far
as one tick can carry a car.

A code review of the work up to `368af0b` found six faults. Five were fixed, one with an edge left:
a leading car that ran less than a let-go car's seat is ahead is drawn back only part of the way,
and `fouled` checks only other trains' carts. The sixth was left as it is: a blocked train pushed
off its end is not tethered, since drawing it back would undo the push-off.

Observed on a server of its own, on `dd1cc7d`: the self-check exact; two consecutive ten-minute runs
at 40 passing every clause, the widest coupling 2.17 and 1.72 blocks; and a run at 100 passing
Coupling at 2.02 but failing Bounce on 3 overlap lines, one episode of two ticks, the lone cart
rammed into a train's leading car on the hill slope. That, and the rise in overlaps since the
tether, is P14's.

Its Done when was met with P14 in, on `b92194a`: two consecutive runs at 40 passed every clause, and
so did two at 100 (see R1).

## P14 — Cars on a slope never pass into one another — **done**

**Build.** With P13 in, every overlap traced is on a slope and involves the lone cart or a car let
go: a leading car resting flush on the lone cart's roof on the dip slope, carried down into it by
vanilla's move on the third tick and held there six ticks, 0.47 blocks apart; a car let go riding
the roofs of another train's cars after both trains let cars go on the same slope, 19 lines; and the
lone cart rammed into a train's leading car on the hill slope, two ticks, 0.22 apart. P13's lead:
vanilla's collision lets a box through an overlap above 1e-7 (`Shapes.EPSILON`) while
`CartImpact.deepens` counts only one above 1e-4 (CartImpact.java:31 and :164 at `dd1cc7d`), and
`SlopeStep.moveOnRail` returns early on a step that stays on its line without checking `reach`
(SlopeStep.java:51). Find which code puts each car inside the other and stop it there, and find
whether P13's holds and tether make such meetings more frequent: overlaps showed in 7 of 16 runs
after the tether (`3e1d17b`) against none of 5 before it.

**Done when.** Two consecutive ten-minute runs at 100 and two at 40 pass every clause, Coupling
included.

### As built

The overlaps were real: an end-of-tick check of every pair of cars from two trains found each
overlap line's pair already inside one another at the end of the tick before, none only part-way
through one.

Two things put them there, both on a slope and at a roof. Vanilla's stepper aims 1e-5 past the block
edge, so its slope line sits 1e-5 off the rail's own, and after the move it lifts a car below that
line onto it, outside the step `SlopeStep` wraps: a lone cart shoved back and forth was lifted 2e-5
into the floor of a leading car resting on its roof. That is past `Shapes.EPSILON`, beyond which
`VoxelShape.collide` lets a box through unstopped, but short of the 1e-4 `deepens` counted, so the
leading car's next move went straight down into the cart, 0.64 deep. `deepens` now counts a car
inside another from `Shapes.EPSILON`, as vanilla does. And a step down a slope that met a roof slid
on across it and was kept for ending within `ON_LINE` of its line, though past where it touched and
up to 1.4e-3 above its rail; the next step read the car as above its rail and left it there, and it
rode the roofs of three carts, came off its rails and was set down inside a fourth. A step near its
line is now kept only if it stopped short of where it touched. The lone cart rammed into a leading
car on the hill slope, P13's failing case, was not reproduced; its log shows the same squeeze at a
roof.

P13's holds do not make such meetings more frequent. With the hold and tether switched off, roof
contacts were about as many, 397 and 552 against 470 and 525 at 40 and 100, twice as many cars were
let go, and that arm gave the one overlap episode of the four runs; P13's 7 of 16 against none of 5
is within chance, a one-sided Fisher p of about 0.10.

Observed on a server of its own, on `b92194a`: the self-check exact, then two consecutive ten-minute
runs at 100 and two at 40 passing every clause, the widest coupling 2.02 and 1.82 blocks. Not fixed:
jams that hold two trains for the rest of a run, one a hopper pressed up into the floor of another
train's tail car on a slope, which failed Stall in three of the eight runs on earlier code before
those, and in six of P13's at 40; corner clips of up to 0.3 on curves and junctions between cars
`CartInteraction` calls IGNORE, never within the overlap line, one of which wedged the lone cart in
a train's seat for 600 ticks (P11's `RailContext.meets`); and moves off the rails, `comeOffTrack`
and `adjustToRails`, which lie outside the step wrapper and which `deepens` never sees.

## P15 — A jam that holds two trains breaks — **done**

**Build.** The stalls left are jams that hold to the end of a run, in two shapes. At the
`(-1,-53,-12)` switch, a train caught between two, its engines reversing every 4 to 12 ticks while
the train behind struck it at 2.50 about every 70 ticks and the meter read no refusal (P5's run on
`e681e53`), and five trains queued there (a P14 run at 40). On the ascending-east slope at
`(-5,-53,-20)`, a hopper pressed up into the floor of another train's tail car at roof contact,
striking every tick and stall-reversing every four to five ticks while neither train moved (a P14
run at 100 on `4a561c5`, before its fix to the slope step, which may or may not bear on it). P10
broke a pressing pair and a filled loop, not these. Reproduce each with a probe of its own, find
what holds it, and break it.

**Done when.** Each probe's jam clears on its own where the code before held it, and four
consecutive ten-minute runs, two at 100 and two at 40, pass every clause.

### As built

Of the three jams named, none held on main in a probe of its own. The switch jam was a car arriving
by the switch's unjoined leg and pressing the train standing on the switch: a probe of that
formation held it 761 of 800 ticks on `e681e53` and at most 20 on `96c4958`, where P13's stop short
of a switch laid against a car keeps it off. The five trains queued at the switch and the hopper on
the slope cleared within 20 to 100 ticks from every restored sample, on their own code and on main;
the samples do not carry what held them, and they have no mechanism.

Two other jams held, and both are fixed. A car whose box was already inside another train's cart had
every seat refused, so it was let go every tick and P13's tether held its train to it for good: a
hopper stood inside the lone chest at the `(-3,-27)` hairpin for 560 ticks. Such a car is now seated
where its run to the seat leads away from the cart's middle; one standing still, or running towards
it, still fouls. And a stall turn left a standing train led from the end it turned from, since a
standing train reads no new front from its movement, so the car at its new front stayed placed and
packed short of a cart against it as an end of track, reversing every four ticks while nothing
moved: the part the lone chest played in the serpentine and hairpin gridlocks of the runs with P16
and P17. A stall turn now leads a held train at once from the end its engines face, whose car
vanilla then drives into what it meets; back-to-back reversals of trains with several engines fell
by three quarters.

Observed on a server of its own, rebased onto P16 and P17: the wedge probe stood 10 of 600 ticks
where it had stood 580, and a train with a chest at its tail pushed it the length of a 57-block line
where it had reversed 99 times without moving; P10's loop and line probes still circulated and
cleared. Four consecutive ten-minute runs on `b535ea0`, two at 100 and two at 40, passed every
clause, clips included, as the same four had on `f63afcb`. Not fixed: a pressing pair at a one-row
U-turn, two leading cars side by side, is judged ahead through space and so never yields (P10);
trains that all push back at both ends gridlock with nothing local to break it; and the queue and
slope jams have no mechanism.

## P16 — Cars of two trains do not clip on curves and junctions — **done**

**Build.** P14's end-of-tick check of every pair of cars from two trains found vanilla's move of a
leading or loose car carrying it into another train's car by up to 0.3 blocks on curves and
junctions, their centres 0.94 to 1.34 apart, for pairs `CartInteraction` answered IGNORE:
`RailContext.meets` (P11) still misses pairs there. None comes within the 0.5 of an overlap line, so
no clause sees them, but one wedged the lone cart into a train's seat, which refused `SEAT_FOULED`
for 600 ticks and failed Stall. Make the check a DEBUG line, `event=clip` when the boxes of two
trains' cars intersect deeper than 0.01 blocks, edge-triggered per pair, which fails Bounce as an
overlap does; then find which pairs `meets` misses and why, and make them collide.

**Done when.** A probe of the wedge holds on the code before and not after, and a ten-minute run at
100 and one at 40 pass every clause, clips included.

### As built

`TrainMeter` writes `event=clip` once as two trains' cars come to stand inside each other deeper
than 0.01 at the end of a level's tick, the depth being the least of the three axis overlaps of
their boxes, and `torture.py` fails Bounce on it. The code before logged 34 and 30 clips at 100 and
40, deepest 0.15, every one across a block corner with the centres 0.92 to 1.34 apart: at a curve's
inside corner beside another line, and across the two legs of one-row U-turns. There each car's
covered rails stopped short of the other's, while a box riding a curve's chord spills 0.24 into the
diagonal block. `RailPath.covered` now also records the runs of line a car's middle can take before
its next move ends, and `meets` answers yes where the two boxes ridden along those runs overlap past
`Shapes.EPSILON`, tested exactly and asked in entity-id order so both cars get the same answer;
parallel track, 0.02 apart, stays IGNORE.

Observed on a server of its own: the wedge replica held its train 400 ticks on the code before, the
lone cart 0.04 inside its third car and the train refusing `SEAT_FOULED`; on `a995165` the cart
stopped flush at the car's face and the train was 16 blocks on by +80. A run at 100 and one at 40
passed every clause with no clip. Not fixed: a car off its rails has no runs and stays IGNORE (P17),
and under realistic physics a hard meeting at such a corner can now WRECK, as any COLLIDE pair can.
One run of the fix before its review gridlocked three trains, 20 cars, in the bottom balloon loop,
which holds about 13; it did not recur in the two runs after, and that kind of jam is P15's.

## P17 — A car off its rails is never set down inside another — **done**

**Build.** A step along the rail is guarded (`SlopeStep`, `deepens`), but two moves are not.
`AbstractMinecart.comeOffTrack` (AbstractMinecart.java:377-389) moves a car off its rails with
vanilla's `move`, which lets a box already inside another through, and
`NewMinecartBehavior.adjustToRails` (NewMinecartBehavior.java:154, called at :243 when a car's rails
change and at :53 on its first tick) sets a car onto its rail with `setPos`, through anything. In a
P14 run the roof ride P14 then ended carried a car off its rails 0.31 into another train's car, and
`adjustToRails` set it down onto its rail inside that car, 0.60 deep. Other ways off the rails
remain: a track end, a jump, a shove. Keep either move from carrying a car deeper into another
train's car, as `deepens` does for a step, and show it with a probe that brings a car off or back
onto its rails at another train's car.

**Done when.** The probe's car ends against the other car rather than inside it, where the code
before set it inside, and a ten-minute run at 100 and one at 40 pass every clause.

### As built

The move P14 saw was not vanilla letting a box already inside another through. A car off its rails
shares track with no cart, so `CartInteraction` answered IGNORE between it and every cart, and the
move in `comeOffTrack` carried it 2.33 west into two cars it had stood clear of; `adjustToRails`
then set it down 0.60 deep. Two cars of different trains, one of them off its rails by vanilla's
`isOnRails`, now COLLIDE, or WRECK under physics. Two new injections guard the moves: a
`@WrapOperation` on the `move` in `AbstractMinecart.comeOffTrack` takes each axis of a move by a car
already inside another train's car only where it does not deepen the overlap, and one on both calls
of `adjustToRails` in `NewMinecartBehavior` (`tick` and `moveAlongTrack`) replays a set-down against
other trains' cars axis by axis, stops it where the car first meets one, and marks the car off its
rails so the set-down is tried again until the rail is clear. Stopping short follows vanilla's move,
`SlopeStep` and the placer; a car left where it was, above a roof with no speed along its rail,
would hang in the air.

Observed on a server of its own: every probe, a car run off a rail's end or its rails into a
standing cart, dropped onto one or summoned above one, ended 0.30 to 0.70 inside the other car on
the code before and against it or on its roof after; a run at 100 and one at 40 passed every clause,
and `3bjump` turned only at its eastern end. Another run at 100 failed Stall on two trains jammed on
their rails at the corner by `(-2,-53,-26)`. On `e80d980`, with P16 in, a run at 100 passed every
clause with no clip, and one at 40 failed Stall on jams at two one-row U-turns, the bottom balloon
loop and the serpentine, every car on its rails: P15's kind. Not fixed: the stepper's hill lift
(NewMinecartBehavior.java:457) is still a bare `setPos`, and `3bjump` fails Coupling, 53 readings in
ten minutes up to 12.59 blocks, as it did before P17: while the leading car flies, the rest of its
train is loose.

## P18 — A train keeps its leading end through a jump — **done**

**Build.** On `3bjump` the train leaves the lip with the car that led it up the ramp in front, and
while it flies `holdTrain` refuses `LEADER_OFF_RIBBON` with three cars loose. About eleven ticks
later it is seated again, led from the car at its other end, which the placer lays at the front of
the landing track, some 17 blocks on from where it stood on the ramp, with the rest behind it. So
the same end reaches the eastern stop every lap, where the U-turn through the loop, which keeps the
end in front, should have it alternate lap by lap. P12's check counted where stall turns happened,
which this never is, and passed. In the air the cars also spread 10 blocks apart, what `3bjump`'s
Coupling failure reads (P17). Find what hands the lead to the tail end while the leader flies or
lands — the leader decision, the ribbon started over at the landing, or the order read off it — and
keep the end that left the lip in front when it lands.

**Done when.** Ten minutes on `3bjump` at 40 and at 100, traced tick by tick, land every jump with
the car that led up the ramp in front, and a ten-minute torture run at each speed still passes every
clause.

### As built

It was the ribbon, not the leader decision: the ribbon failed to start over where it should have.
The U-turn and ramp are short enough that the ribbon still held the landing track from the train's
run west, so at 40 the leading car came down on a block it knew and `record` read a revisit, moving
its arc from about 2, where it left the lip, to about 20 on the old pass with no reset, and so with
the old lie, right on the ramp and backwards there. The placer laid the other three cars ahead of
it, and three ticks later `settledLeader` handed the lead to the car now in front. At 100 the car
lands past the ribbon's far end, on a block it does not know, which restarts the ribbon, so 100
never showed it. The ribbon now keeps whether its leading car stands off every block it records;
while it does, a reading more than 1.5 behind where the car left, against the way it runs, is a
landing on another pass, and the ribbon starts over at the landing block, its lie settled as after
any reset.

Observed on a server of its own, traced tick by tick for ten minutes at each speed: at 40, none of
133 jumps landed with the car that led up the ramp in front before and all 130 after, the car first
at the eastern stop alternating on 129 of 129 laps where it had on none; at 100, all 275 before and
after. The self-check was exact, and a ten-minute torture run at 100 and one at 40 passed every
clause. Not fixed: the cars still spread apart in the air, the rear ones stopped on the ramp while
the leader flies, so `3bjump` still fails Coupling, up to 12.24 blocks at 40 and 13.46 at 100,
though at 40 the readings halved.

## P19 — Every car of a train follows it off a jump — **done**

**Build.** With P18 in, the train lands the right way round, but its last car is left at the foot of
the ramp every jump. As the leading car leaves the lip, `holdTrain` refuses `LEADER_OFF_RIBBON` and
the three cars behind stand one tick where they were seated, nothing moving them, then go on loose.
Loose cars of one train are solid to each other, so the last, climbing faster than the car ahead,
strikes it: at 40 it goes from 0.97 blocks a tick to nothing in one tick while the car it struck
goes from 1.20 to 1.43, and it slides back down the ramp while the others fly, until the placer lays
it 8.5 blocks on at the landing. That is most of `3bjump`'s Coupling failure. Keep the cars still on
the track moving with the train while its leading car flies, so each follows it off the lip, whether
by driving them on along the ribbon, by what cars of one train let go do to each other, or by where
the tick is lost.

**Done when.** Ten minutes on `3bjump` at 40 and at 100, traced tick by tick, see every car of the
train leave the lip on every jump and none stop on the ramp, and a ten-minute torture run at each
speed still passes every clause. Coupling on `3bjump` is reported.

### As built

Two things left the car at the foot of the ramp, both from `holdTrain` releasing the train as its
leader flew. Cars ticking before the leader had already skipped vanilla's move as seated, so they
stood a tick; then, loose and solid to one another, vanilla moved them rear first, each running into
the car ahead before that car moved and shunting its speed forward, which stranded the last on the
ramp. At 40 this happened only in jumps led by the car that ticks last; at 100, in every jump. A
leading car in the air past the unblocked end of its ribbon now leaves the rest seated: they run on
along the ribbon, each the same distance along the rails, at the speed the train carried off them,
and at the end vanilla moves each off as it moved the leader, one a tick, stamped so it moves once
whatever the tick order. Any other case releases as before; the refusal is `LEADER_IN_AIR`.

Observed on a server of its own, ten minutes traced tick by tick at each speed: at 40 all four cars
left the lip on all 130 jumps, where 65 had left one behind, and Coupling on `3bjump` fell from 39
readings, widest 12.24, to none, widest 2.01; at 100 all four left on all 275 jumps, where every
jump had left one or two behind, and Coupling fell from 131 readings, widest 13.46, to 21, widest
3.90, each the tick of vanilla's leap off the end. P18's landing held on every jump, the self-check
was exact, and a ten-minute torture run at 100 and one at 40 passed every clause. Not fixed: that
leap, which sets a car leaving an end a full tick's run past it; and at 40, cars leaving a tick
apart overlap in the air, fewer than before.

# Part R — The bar

## R1 — Ten minutes clean, twice — **done**

**Build.** Nothing. `python3 scripts/torture.py run` after each P stage lands, and again at the end.

**Done when.** Two consecutive runs pass every clause and the three vacuity checks. The two verdict
tables are pasted into this stage's "As built", with the commit each ran against.

### As built

The bar was reached after P11, on the main checkout's server, by the two runs following the
self-check; both ran against `aab9960`. It took stages the plan did not start with — P6 to P11,
planned from the runs after each landing — and one run after each landing is recorded in the
findings.

Run 1 (`run-torturetrack-20260924-211008.json`):

| Clause | Verdict | Detail |
|---|---|---|
| Alive | PASS | ran to +12000 ticks; no crash report; no ERROR or stack trace from the mod |
| Sever | PASS | 28 couplings held at every sample; no `event=sever` |
| Stall | PASS | no driven train stood within 5 blocks for 600 ticks |
| Bounce | PASS | 0 overlap, 0 overspeed, 0 sever within 40 ticks of a strike; 19,193 strikes |
| Switch-safe | PASS | no switch thrown under a spanning train, no `DIVERTED` sever; 227 changes held |
| vacuity | PASS | 19,193 strikes between trains; driven trains ran 10,984–20,952 blocks; 35 carts |

Run 2 (`run-torturetrack-20260924-212029.json`):

| Clause | Verdict | Detail |
|---|---|---|
| Alive | PASS | ran to +12000 ticks; no crash report; no ERROR or stack trace from the mod |
| Sever | PASS | 28 couplings held at every sample; no `event=sever` |
| Stall | PASS | no driven train stood within 5 blocks for 600 ticks |
| Bounce | PASS | 0 overlap, 0 overspeed, 0 sever within 40 ticks of a strike; 17,866 strikes |
| Switch-safe | PASS | no switch thrown under a spanning train, no `DIVERTED` sever; 237 changes held |
| vacuity | PASS | 17,866 strikes between trains; driven trains ran 17,254–20,049 blocks; 35 carts |

P4 and P5 read R1 for their own Done when; each is recorded under its own heading. The largest
ribbons were 46, 59 and 71 nodes for trains of 4, 6 and 8 cars, spanning 44.0, 53.1 and 64.6 blocks
in run 1 and 37.5, 51.6 and 63.9 in run 2, against the `6n + 16` blocks of the trim window; twice
the car count plus a margin, read literally, no running train comes near.

Reopened after P13. Its Coupling clause makes the bar six clauses, which both runs above fail, and
with P13 in a run at 100 failed Bounce on 3 overlaps; P14 takes them, and its Done when is this
bar's at both speeds.

Reached again after P14, on six clauses, by the two runs at 100 that followed the self-check on
P14's server, both against `b92194a`; the two runs at 40 after them passed every clause as well.

Run 1 (`run-torturetrack-20260926-025040.json`):

| Clause | Verdict | Detail |
|---|---|---|
| Alive | PASS | ran to +12000 ticks; no crash report; no ERROR or stack trace from the mod |
| Sever | PASS | 28 couplings held at every sample; no `event=sever` |
| Coupling | PASS | 28 couplings within 3 blocks at every sample; widest 2.02 |
| Stall | PASS | no driven train stood within 5 blocks for 600 ticks |
| Bounce | PASS | 0 overlap, 0 overspeed, 0 sever within 40 ticks of a strike; 15,119 strikes |
| Switch-safe | PASS | no switch thrown under a spanning train, no `DIVERTED` sever; 245 spanned changes held |
| vacuity | PASS | 15,119 strikes between trains; driven trains ran 15,788–21,270 blocks; 35 carts |

Run 2 (`run-torturetrack-20260926-030049.json`):

| Clause | Verdict | Detail |
|---|---|---|
| Alive | PASS | ran to +12000 ticks; no crash report; no ERROR or stack trace from the mod |
| Sever | PASS | 28 couplings held at every sample; no `event=sever` |
| Coupling | PASS | 28 couplings within 3 blocks at every sample; widest 2.02 |
| Stall | PASS | no driven train stood within 5 blocks for 600 ticks |
| Bounce | PASS | 0 overlap, 0 overspeed, 0 sever within 40 ticks of a strike; 15,058 strikes |
| Switch-safe | PASS | no switch thrown under a spanning train, no `DIVERTED` sever; 272 spanned changes held |
| vacuity | PASS | 15,058 strikes between trains; driven trains ran 16,936–19,947 blocks; 35 carts |

Runs still vary: of the eight runs before these on P14's server, on earlier code, three failed Stall
on jams that held, and the jams are not fixed (P14).

Reached again after P15 to P17, on six clauses with clips counted in Bounce, by the two runs at 100
that followed the self-check on P15's server, both against `b535ea0`, which is main with all three
in; the two runs at 40 after them passed every clause as well, the widest coupling 1.82 and 1.73.

Run 1 (`run-torturetrack-20260926-173235.json`):

| Clause | Verdict | Detail |
|---|---|---|
| Alive | PASS | ran to +12000 ticks; no crash report; no ERROR or stack trace from the mod |
| Sever | PASS | 28 couplings held at every sample; no `event=sever` |
| Coupling | PASS | 28 couplings within 3 blocks at every sample; widest 1.98 |
| Stall | PASS | no driven train stood within 5 blocks for 600 ticks |
| Bounce | PASS | 0 overlap, 0 clip, 0 overspeed, 0 sever within 40 ticks of a strike; 14,521 strikes |
| Switch-safe | PASS | no switch thrown under a spanning train, no `DIVERTED` sever; 239 spanned changes held |
| vacuity | PASS | 14,521 strikes between trains; driven trains ran 16,152–19,583 blocks; 35 carts |

Run 2 (`run-torturetrack-20260926-174253.json`):

| Clause | Verdict | Detail |
|---|---|---|
| Alive | PASS | ran to +12000 ticks; no crash report; no ERROR or stack trace from the mod |
| Sever | PASS | 28 couplings held at every sample; no `event=sever` |
| Coupling | PASS | 28 couplings within 3 blocks at every sample; widest 2.02 |
| Stall | PASS | no driven train stood within 5 blocks for 600 ticks |
| Bounce | PASS | 0 overlap, 0 clip, 0 overspeed, 0 sever within 40 ticks of a strike; 15,532 strikes |
| Switch-safe | PASS | no switch thrown under a spanning train, no `DIVERTED` sever; 213 spanned changes held |
| vacuity | PASS | 15,532 strikes between trains; driven trains ran 16,788–18,966 blocks; 35 carts |

---

## Open decisions

- **The lone chest cart at `(-14,-53,-14)`.** A one-cart strip with its own sign, so treated as
  an intended obstacle. Settled by the user.
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

Two clients at once corrupt each other's replies: every command's output goes into one shared
`RconConsoleSource` buffer, cleared as each command starts (DedicatedServer.java:713-717), so
nothing else may talk rcon to a server a run is sampling.
