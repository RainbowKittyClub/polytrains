# Train ribbon

A train becomes a **ribbon** — a polyline of the track its head has actually crossed — and every
trailing car is placed at a fixed arclength offset along it. The head's motion is left to vanilla's
`moveAlongTrack`. Spacing stops being a per-tick correction applied to every car and becomes a
property of where the cars are put.

Today `TrainCoupling.settle` forces every car in a run onto one shared scalar speed, measures each
link with `RailPath.between`, and teleports each car along its rail by a clamped fraction of its
spacing error. The error is produced in the velocity domain and corrected in the position domain,
where it has to be clamped hard enough to stay invisible — so it never converges, and links break on
bends, on chunk borders and under a player's shove.

Every API claim below was checked against `libs/decompiledjava/minecraft-26.2/` and against this
tree. The geometry stages port code that ran on an earlier attempt and reported `spacing worst=0.00`
with an eight-car train at five blocks a tick; those numbers are quoted as measured, but nothing in
this document has been observed on *this* branch until its stage says `done`.

Stage order. S1 and S2 are independent of the ribbon and of each other; S1 comes first because
nothing after it can be tested without a dev server. S3 → S4 → S5 → S6 is one chain: S3 gives the
train an identity that outlives the tick, S4 records the ribbon inert and stands up the meter that
judges S5, S5 puts the cars on it, and **S6 is the stage that makes the feature complete** — it is
where the old machinery dies and the placer becomes total. S7 and S8 rehome acceleration and
deceleration; they depend on S5, not on S6, and can land in either order.

---

## Findings that reshape the work

**The old machinery must go in one stage, not be left as a fallback.** An earlier attempt built the
ribbon alongside `settle` rather than instead of it. `TrainCoupling` grew from 379 to 662 lines
holding both systems, and every question — who severs, who owns spacing, which way round the train
lies — had two answers that had to agree. Bails to the fallback were zero on the test track, so the
second system earned nothing and cost the ability to reason about the first. S6 deletes it, and S5
instruments every bail specifically so S6 knows what it must handle by name.

**There is no vanilla arclength walker, so the ribbon is recorded rather than walked.**
`NewMinecartBehavior.stepAlongTrack` (`:409`) looks like one but mutates the real entity through
`Entity.move` and is single-block scoped. The only stateless primitives are
`AbstractMinecart.exits(RailShape)` (`AbstractMinecart.java:265`) and `RailState.getConnections()`
(`RailState.java:30`) — and they use opposite slope conventions, `exits` putting `.below()` on the
low offset where `getConnections()` puts `.above()` on the high neighbour. Mixing them is a Y-offset
bug rather than a compile error.

**Walking track forward is unsound at a switch; recording it is sound.** A speculative walk has to
guess how a car would arrive at a switch, and a wrong guess does not bend the track slightly — it
relocates the train onto the other leg. So the ribbon is recorded at `makeStepAlongTrack` HEAD from
the leading car only. For the same reason ribbon *growth*, which walks track no car has crossed,
refuses to enter a switch at all: one of its two ends runs backwards, which makes the guess wrong at
exactly the end that extends behind a freshly linked train.

**Vanilla has no curves, so a polyline is the exact model rather than an approximation.** Every rail
block, corners included, is two straight chords through fixed exit points; `adjustToRails:154`
projects onto the chord explicitly. A corner is worth `√0.5 ≈ 0.707` blocks of arclength, not 1.0.

**`calculateTrackSpeed` cannot be suppressed for trailing cars.** It is tempting — a towed car is not
self-propelled — but it is private (`NewMinecartBehavior.java:304`), holds the *only* per-tick speed
clamp (`:340`) and the only `applyNaturalSlowdown` call (`:338`), and `TrackIteration.shouldIterate()`
is `firstIteration || movementLeft > 1.0E-5` with **no iteration cap** (`:592-594`). Remove the clamp
and a large budget becomes an unbounded per-tick block walk calling `applyEffectsFromBlocks()` twice
per iteration. So trailing cars stop being accelerated by each injection asking whether it is on the
head, never by bypassing vanilla's pipeline.

**A placed car never reaches `Entity.move`, so everything hanging off movement is repaid by hand.**
`TrainPlacer.sweep` fires activator rails for every block between a car's last seat and this one —
firing only the block it parks on primes a TNT cart on a quarter of the rails it should — and `place`
calls `pushAndPickupEntities` and `applyEffectsFromBlocks` directly. Only movement changes;
everything else keeps working.

**Which way round a train lies is owned, not measured.** Deriving it per tick from a trailing car's
arc is a coin toss on a circuit, and because only the sign survives, a wrong reading lays the whole
train backwards rather than producing a small error. `Train.order` is settled once per ribbon arc
frame and keyed to `ribbon.resets()`, since a reset renumbers the arcs and an order taken before one
describes nothing after it.

## How these stages get tested

On this mod's own dev server, `scripts/devserver.sh rkc/mods/minecart-tweaks restart`, against a world
with `minecart_improvements` enabled — trains are off without it and every stage here is a no-op
(`OldMinecartBehavior.stepAlongTrack` is a stub returning `0.0`). S1 exists because that harness is
absent on this branch: without `dev_server_port` in `gradle.properties` the shared build never applies
`gradle/devserver.gradle` (`gradle/build.gradle:154`), so there is no seeded world, no rcon and no
`stopServer`.

`docs/TESTING.md` governs what a pass is worth. A green build proves nothing about mixin targets, so
every stage that adds an injection is checked with `scripts/devserver.sh rkc/mods/minecart-tweaks
mixins` before its in-world observation.

**The meter S4 stands up measures spacing on the ground, not the ribbon's own residual.** That
distinction is the difference between a harness and a tautology: a counter comparing a car against
the ribbon's prediction is open-loop error before S5 and closed-loop tracking error after it, and
after S5 it measures a control loop against its own setpoint and reads near zero whether or not the
train looks right. So the meter is inter-car distance along the track against the car pitch, measured
with `RailPath.between` as an independent oracle, logged at `DEBUG` behind the trains game rule.

The standing test track needs a straight run, a single 90° corner, a pure-curve diagonal, a 2-block
staircase, a slope in both directions, a junction rail, a switch, a circuit that closes on itself, and
a bumper stop that reverses a train under power.

A stage is not finished until its "Done when" is observed, so a failure at stage N implicates stage N
and not the ones before it.

## S1 — Dev-server harness restored — **in progress**

**Build.** Add the dev-server block and the Quilt loader pins to `gradle.properties`:
`dev_server_port=25603`, `dev_rcon_port=25579`, `dev_rcon_password=dev`,
`dev_datapack_pack_format=107`, `dev_client_options_version=4903`, `dev_client_username=TestPlayer`.
Move the `gradle` submodule pointer to the tip of its `gradle` branch, which carries two devserver
fixes: a data version stamped into the seeded `options.txt`, without which the client reads it as
schema 0 and datafixing its `key_` entries throws so that *no* seeded option applies, and opping the
name the dev client joins under. Ignore `run-quilt/`, generated by the Quilt smoke run.

**Done when.** The dev server boots to `For help, type`, `rcon "list"` answers, and a dev client joins
as `TestPlayer` with no offline-mode name prompt.

## S2 — Two rail fixes — **in progress**

**Build.** Two independent fixes, unrelated to the ribbon but both load-bearing under it.

*The link trace is bounded by track walked, not by blocks.* `RailPath.walk` spends a distance budget
as a step count and never compares the accumulated distance against the limit at all. On straight
track the units coincide exactly, which is why it hid; on corners, worth `√0.5` each, a limit of
2.8125 buys three steps and so 2.12 blocks of reach. Bound the loop by the distance and keep the step
count only as a guard against a circuit small enough to walk forever, sized for the all-corners worst
case. `RailPath.between` loses its physics use in S6 but survives as the link-time reach check in
`MinecartHelper.canUseFreeExit`, so without this a player coupling around a corner is silently refused
at three quarters of the reach a straight coupling gets.

*A switch refuses to throw under a cart.* Swapping the curve mid-crossing throws the car off the rail.
`toggle` refuses while occupied and reports it; the wrench sounds and shows an overlay; a redstone
change records the signal but schedules the new curve for once the switch is clear, reusing the
existing settle-back tick rather than a parallel mechanism. This matters more under the ribbon than it
does today: once only the head reads rails, a switch thrown under a trailing car is noticed by nothing
until S6's diverted check.

**Done when.** A cart couples to another around a 90° corner at the centre-to-centre distance that
works on a straight. A wrench swung at an occupied switch refuses, sounds and shows the overlay; a
redstone pulse while occupied applies once the cart is clear.

## S3 — A train with an identity — **in progress**

**Build.** Replace the per-tick `TrainSnapshot` with a `Train` that outlives the tick: the same
link-graph walk and head-first UUID tie-break, but owning its car order and carrying the fields S4 and
S5 fill. Replace the single static generation counter with per-train invalidation, which is the one
semantic change — a link change now invalidates the trains it touched rather than every train in the
world, so both sides of a `link`/`unlink` have to be invalidated or a stale train is handed out. That
failure neither throws nor fails to compile; it shows up as a train holding the wrong cars.

`Train.order` and the ribbon it is keyed to arrive in S4, with `TrackRibbon`.

**Behaviour does not change in this stage.** `settle` still runs, on the same cars in the same order.

**Done when.** A six-car train behaves as it does today — same spacing, same breaks, same speeds — and
the mixin check reports no failures. `mass()` still short-circuits on `hasLink()` before walking
anything: it is asked of every cart every tick and most carts on a server are linked to nothing.

## S4 — The ribbon, recorded inert and measured — **in progress**

**Build.** Add `TrackRibbon` and `TrainMeter`, and the `RailPath.crossing` query that builds every
ribbon node. Record from `recordTrack` at `makeStepAlongTrack` HEAD, leading car only. `extendTo`
walks the track out to the train's own span each tick before anything is seated, which covers the
after-restart and after-reset cases with one mechanism rather than a bootstrap special case. Growth
refuses to enter a switch, for the reason in the findings; the cost is that a train linked across one
cannot seat until its leading car records that block for real.

Nothing is placed. The meter reports the baseline S5 has to beat.

**Done when.** The meter reports a spacing baseline for a six-car train over the whole test track and
the ribbon reports plausible extents for it, with no behaviour change.

## S5 — Cars placed on the ribbon — **planned**

**Build.** Add `TrainPlacer`. Cancel `moveAlongTrack` for a seated car and place it with `setPos`;
run the placer at `tick` TAIL so the train is laid against where its leading car actually ended the
tick. Repay `sweep`, `pushAndPickupEntities` and `applyEffectsFromBlocks` by hand. Derive yaw and
pitch from the step delta, as vanilla's own stepper does — `adjustToRails` turns the cart through a
latch meant to fire once at a transition and on a hill picks between exactly ±45°, so the chord under
the car is the smoothing and no window filter is wanted.

Cancelling the vanilla move is not optional: without it a seated car is moved by both vanilla and the
placer and which wins depends on `EntityTickList` order.

The fallback stays, but every bail is instrumented with its reason. That log is S6's specification.

**Done when.** The meter beats S4's baseline on every track feature, within a tenth of a block of the
car pitch at full speed; an eight-car train round the diagonal, through the circuit and up a slope
shows no visible jump in any car; a train reversed by a bumper stop runs back coherently. The absence
of the jump is the requirement that matters — a teleport is what the old clamps existed to hide.

## S6 — The placer made total — **planned**

**Build.** Delete `settle`, `nudge`, `slack`, `signedSpeed`, `holdTrain`'s run-splitting and the
distance-based sever, and with them `SETTLE`, `MAX_SHIFT`, `MOVING_SHIFT`, `NUDGE_ATTEMPTS` and
`SEVER_SLACK`. `TARGET_SPACING` survives as the car pitch — a layout constant, not a correction
target — and `MAX_LINK_DISTANCE` as the coupling-time reach check only. `RailPath.alongTrack` and
`exitTaken` go with `nudge`; `bend`, `neighbours` and `crossing` stay. Rewrite `TrainCoupling` rather
than editing it down: the point is that it never holds two systems at once.

Severing moves from distance to events, which is what makes the placer total — per-car derail and
sever rather than handing back the whole train. A link breaks when a car derails and cannot be
recovered onto the ribbon, when a car is removed, or when a switch is taking a car off the train's own
track. That last check asks whether the rail at an arc still joins the two edges the ribbon recorded
there, answered from the same rule `orient` steers by so the two cannot drift apart, and cuts *ahead*
of the offending car since everything behind it is what the switch is taking. It has to run for every
train rather than only for seated ones.

**A chunk unloading under a train is not a sever condition.** The mod already treats unloading as
survivable — `EntityMixin` documents that it deliberately does not sever, and a neighbour whose UUID
will not resolve is silently dropped — so a partly-unloaded train already presents as two shorter
trains with the link intact. The ribbon must tolerate a gap it cannot extend across rather than
reading one as track ending.

**Done when.** A train breaks only for a stated reason: driving a car off the end of track severs, a
car destroyed mid-train severs either side of it, a switch taking a car off the train's track severs
ahead of it. A train crossing a chunk border, taking a corner at full speed, or shoved hard by a
player does **not** sever — those three are the regression this document exists to fix. Every bail
reason S5 logged is handled by name.

## S7 — Acceleration and deceleration on the head — **planned**

**Build.** Train mass keeps governing the head; what changes is that it governs the head *only*.
Twelve sites, all reached through `applyNaturalSlowdown` and `getMaxSpeed`.

Deleted: `towedDrive`, which gave a non-engine car the train's acceleration and exists only because
every car had to self-propel to hold formation; `signedSpeed`, already gone with S6; and
`driveHeading`, which solves "a standing *trailing* car has no movement to read a heading off" — the
head always has vanilla's own impulse.

Kept but narrowed to the head: the furnace cart's flat-impulse acceleration, which is right for a solo
cart and stays as it is, with only its shared divisor moved; the train-wide speed ceiling, which loses
its per-car loop and with it the static reentrancy guard that loop needed; the length-scaled crawl
floor and slow-speed drag; and the brake's launch, which on a trailer would be overwritten by the
placer next tick. `sharedDrive`'s formula is pure arithmetic and moves onto `Train` unchanged.
Impact drag keeps its one-over-cars falloff but is applied once rather than written to every car.

**The shove needs retuning, not repointing.** Train length is currently divided out twice — once in
`EntityMixin`'s push and again in `settle`'s mean of the cars' speeds — and its comment records that
either alone loses out to rail friction. The settle average is gone, so the surviving division will
under-shove and the crawl floor will swallow it. Separately, a shove landing on the fifth car has to
reach the head, because that car's velocity is now an output.

**Done when.** A furnace cart and two cars climb a slope as readily as one alone and a twelve-car
train visibly does not; two furnace carts accelerate a train faster than one; a player shoving a
parked three-car train moves it about as far as they do today, measured rather than eyeballed; and a
train under no power still comes to a dead stop rather than crawling.

## S8 — Bend loss re-expressed — **planned**

**Build.** Bend loss is the only deceleration with nowhere to go: it scales a shared scalar velocity
that no longer exists and lives inside the function S6 deletes. Re-express it as drag on the head
proportional to the bends the whole train is in — the ribbon knows every car's arc and the shape at
each, so the count can come from the ribbon rather than from a per-car counter.

Settle `bendsEntered()` while here. It is a consuming counter contracted to be called exactly once a
tick whose only caller is `settle`, and the contract is already violated: `settle` returns early for a
run of fewer than two cars, before the bends loop, so a one-car run never consumes. Either the new
reader honours it or the counter is replaced by a ribbon query and deleted.

**Done when.** A long train through a sequence of corners loses measurably more speed than the same
train over an equal length of straight, and a single cart's cornering is unchanged from vanilla.
Numbers recorded, since this is the one stage with no prior art to match.

## Open decisions

**Whether `driveHeading` is really unnecessary.** S7 deletes it on the reasoning that only a standing
trailing car lacked a heading to start from, and trailing cars no longer start themselves. If a train
whose head is a non-engine car refuses to start from a standstill, that is the case it was covering
and it comes back narrowed to the head. Settled by building S7.

**What the car pitch should be.** `TARGET_SPACING` survives S6 as a layout constant rather than a
correction target, which changes what it is for: today it is the value an error is measured against
and is therefore tuned against the clamps, and afterwards it is simply how far apart cars sit. The
number that looked right under correction need not be the number that looks right under placement.
Settled by watching a train on the test track after S5.

**Rotation smoothing on effectively-diagonal track** is deliberately not in this document. The ribbon
enables it and it has no dependency in the other direction. The chord under a placed car is already a
form of smoothing, so the question is whether anything further is wanted at all.

## Reference: vanilla movement, verified

| Claim | Citation |
|---|---|
| `tick` runs `computeSpeed`, `handlePortal`, then `behavior.tick()` | `AbstractMinecart.java:280` |
| Arc-length stepper, abstract on the base | `MinecartBehavior.java:34` |
| Implemented on the new behavior | `NewMinecartBehavior.java:409` |
| Stubbed to `0.0` on legacy movement | `OldMinecartBehavior.java:361` |
| `getBehavior()` is public | `AbstractMinecart.java:147` |
| Rail exits, relative offsets, `.below()` on the low side | `AbstractMinecart.java:265` |
| Neighbour rail positions, `.above()` on the high side | `RailState.java:30`, `:34` |
| Corner is a straight chord, projected onto explicitly | `NewMinecartBehavior.java:154` |
| Sub-step loop, budget in `TrackIteration.movementLeft` | `NewMinecartBehavior.java:233-263` |
| No iteration cap; loop ends when the budget is spent | `NewMinecartBehavior.java:592-594` |
| Only speed clamp, and only `applyNaturalSlowdown`, are here | `NewMinecartBehavior.java:340`, `:338` |
| Yaw derived from the step delta | `NewMinecartBehavior.java:278` |
| `setRotation` flips rather than spins in a 175°–185° band | `NewMinecartBehavior.java:218-229` |
| Activator rails fire inside the move | `NewMinecartBehavior.java:249-251` |
| `applyEffectsFromBlocks` runs twice per iteration | `NewMinecartBehavior.java:297-299` |
| Empty `lerpSteps` still sends a synthetic weight-1.0 step | `ServerEntity.java:233-251` |
| Client replays a batch over three ticks | `NewMinecartBehavior.java:30`, `:78` |
| Devserver gating on `dev_server_port` | `gradle/build.gradle:154` |

`ServerEntityMixin` is unchanged by all of this. It batches a whole train's position updates onto one
beat, which the vanilla client's display chain needs and which also keeps the cars from stuttering
relative to each other; both effects outlive `settle`. See `docs/plan/client-mod-support.md`.
