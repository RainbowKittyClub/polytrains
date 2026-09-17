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
a bumper stop, and a grade a train can be stalled part-way up so that it rolls back down.

Nothing bounces a cart in this game: a bumper stop is a place a train comes to rest, and what it
tests is that the train stops as one piece rather than concertinaing or severing. A reversal has to
be produced by the train running out of momentum and rolling back, by a shove from the far end, or by
a furnace cart re-aimed the other way.

A stage is not finished until its "Done when" is observed, so a failure at stage N implicates stage N
and not the ones before it.

## S1 — Dev-server harness restored — **done**

**Build.** Add the dev-server block and the Quilt loader pins to `gradle.properties`:
`dev_server_port=25603`, `dev_rcon_port=25579`, `dev_rcon_password=dev`,
`dev_datapack_pack_format=107`, `dev_client_options_version=4903`, `dev_client_username=TestPlayer`.
Move the `gradle` submodule pointer to the tip of its `gradle` branch, which carries two devserver
fixes: a data version stamped into the seeded `options.txt`, without which the client reads it as
schema 0 and datafixing its `key_` entries throws so that *no* seeded option applies, and opping the
name the dev client joins under. Ignore `run-quilt/`, generated by the Quilt smoke run.

**Done when.** The dev server boots to `For help, type`, `rcon "list"` answers, and a dev client joins
as `TestPlayer` with no offline-mode name prompt.


### As built

The dev-server block and the Quilt pins went into `gradle.properties` and the submodule pointer moved
`11c15a0` → `44f78a4`; `run-quilt/` is ignored alongside `run/`.

The baseline did not lint. Three Checkstyle errors were already present at `c403af0` — declaration
order in `AbstractMinecartMixin`, a stray blank line before the closing brace in `RailBrake` and in
`LinkChain` — and the pre-commit hook blocks on them, so every commit below would have been blocked.
They are the errors the two `style:` commits in the shed gap had fixed. Cleared first, on their own.

## S2 — Two rail fixes — **done**

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


### As built

`RailPath.walk` now ends on `between <= limit`, with `maxSteps = ceil(limit / MIN_BLOCK_SPAN) + 1` —
`MIN_BLOCK_SPAN` being `√0.5`, the least track one block can be worth — left only as the guard
against a circuit short enough to walk forever.

`SwitchedRailBlock.toggle` returns whether it threw, so the wrench can report a refusal rather than
infer one. `updateState` records `POWERED` unconditionally and defers only the curve, through a
scheduled `settleLater`. Occupancy is asked as `occupiedByOther`, so the cart that just left a switch
does not refuse its own throw.

**A known hole, not closed here.** After `updateState` defers the curve, the crossing car's own
`steer` → `orient` recomputes the resting shape from the *new* `POWERED` and writes it — throwing the
curve under the car by the other path. The reference implementation of this fix has the same hole.
It is listed under Open decisions rather than fixed, because the diverted-sever check in S6 changes
what the right answer is.

## S3 — A train with an identity — **done**

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


### As built

`Train` replaces `TrainSnapshot` with the walk and the head-first tie-break carried over unchanged,
so the stage is a lifetime change and nothing else.

The brief listed the invalidation sites; there was a third it had not, in the `Entity.remove` hook at
`EntityMixin.java:37`. Per-train invalidation is exactly the change that makes a missed site silent:
under the old static counter every train in the world was invalidated together, so no call site could
be wrong on its own. Worth re-checking by grep, not by reasoning, whenever a link is mutated.

`Train.order` and the ribbon field arrived in S4 as planned.

## S4 — The ribbon, recorded inert and measured — **done**

**Build.** Add `TrackRibbon` and `TrainMeter`, and the `RailPath.crossing` query that builds every
ribbon node. Record from `recordTrack` at `makeStepAlongTrack` HEAD, leading car only. `extendTo`
walks the track out to the train's own span each tick before anything is seated, which covers the
after-restart and after-reset cases with one mechanism rather than a bootstrap special case. Growth
refuses to enter a switch, for the reason in the findings; the cost is that a train linked across one
cannot seat until its leading car records that block for real.

Nothing is placed. The meter reports the baseline S5 has to beat.

**Done when.** The meter reports a spacing baseline for a six-car train over the whole test track and
the ribbon reports plausible extents for it, with no behaviour change.


### As built

`TrackRibbon`, `TrainMeter`, and in `RailPath` the `crossing` query with `RAIL_LIFT`, `edgeHeight`,
and a `Switches` parameter on `shape`/`neighbours` that says whether a switch reads as laid or as
joined. `SwitchedRailBlock` gained `shapeFor` — a pure extraction of the decision `orient` already
made, so the S6 diverted check and the steering cannot drift apart — and `linkShape`.

`TrainCoupling` gained `recordTrack`, `reachTrain` and `trimRibbon`. **`trimRibbon` is load-bearing,
not housekeeping**: `MAX_NODES` is enforced in `trim` and in `grow` but never in `record`, so a
recording train with no trim grows nodes for as long as it runs and every `arcOf` degrades with it.

**This stage is not purely inert, contrary to the Build text above.** Routing the link walk through
`Switches.JOINED` means a link traced across a switch can now succeed where it previously returned
`null`, which reaches `holdTrain`'s sever check and `MinecartHelper.canUseFreeExit`.

**The baseline.** Two runs of a six-car train, 8 s and 12 s, with the ribbon live:

| | Run 1 | Run 2 |
|---|---|---|
| Spacing `worst` | 0.00–0.02 | 0.00–0.20 |
| Ribbon residual | 0.00–0.02 | 0.00–0.21 |
| `resets` | 0 | 0 |
| Ribbon | `nodes=34–37 span≈32` | `nodes=33–37 span≈32` |

`resets=0` through both, including across the break, and the residual tracking the independent oracle
to within 0.01 is what the stage was standing the meter up to show. Span settling near 32 inside the
40-wide trim window is the moving window working, not drift. The meter's output lands in
`run/logs/debug.log` and never on the console — see `docs/TESTING.md`.

**"Over the whole test track" is not reachable at this stage**, and that is the finding. The old
coupling severs a six-car train after 12–15 s of running, well before it completes the circuit, so
the baseline is necessarily partial. The sever is not drift: `breaking` is 2.8125 against a 2.0 pitch
and the measured gap never passed 2.20. It is a two-tick failure — `RailPath.between` returns `null`
on a corner or a chunk border, `holdTrain` splits the run and settles the halves separately
(`TrainCoupling.java:318`), so for one tick nothing holds that car to the one ahead; at 5 blocks per
tick one unheld tick is far past 2.8125, and the next tick severs on straight-line distance. S5's
cancel-the-vanilla-move is what removes the unheld state that failure falls into.

So the numbers the stages below are measured against:

- **Spacing while coupled: 0.02 typical, 0.20 worst** — what S5 must match.
- **Coupling survival: 12–15 s of running** — what S6 must fix.
- **Bail reasons seen: `no leading end` (34), `leader off ribbon` (2)** — the second only ever fired
  on the tick of a sever, as a consequence of it; the ribbon is not consulted for severing at S4.

## S5 — Cars placed on the ribbon — **done**

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
shows no visible jump in any car; a train stalled part-way up a grade rolls back down coherently,
through the moment it is stationary and the end that leads changes. The absence
of the jump is the requirement that matters — a teleport is what the old clamps existed to hide.


### As built

`TrainPlacer` seats every trailing car at `headArc - order * i * pitch`, with the vanilla move
cancelled for a seated car and `sweep`, `pushAndPickupEntities` and `applyEffectsFromBlocks` repaid by
hand. The hold moved to `tick` TAIL, driven by the leading car, falling back to the settler where
there is no leading end so that a closed or never-moved train is still corrected as it was.

Bails carry a `Bail` tag rather than a boolean, and are compared on the tag: the detail line names
arcs and blocks that differ every tick, so comparing the line would report a steady fault as a new one
every tick and a boolean would hide a fault that changed kind. Four of the five fired in testing —
`NO_LEADING_END`, `CAR_OFF_RIBBON`, `LEADER_OFF_RIBBON`, `ORDER_UNREADABLE`. `LEADER_MID_TRAIN` did
not, and cannot: the car order and `decideLeader` agree by construction, so it guards a contradiction
in `Train` rather than a situation on the track.

**What the placer broke, and what that taught.** Every one of these is the same shape — something
vanilla did for free, or something the old system papered over by having every car read the rails.

- **A sever cost the surviving half its ribbon**, because `Train` held a fresh one per object. The
  placer then could not seat it, the correction took it, and it severed again: eight cars came apart
  in five ticks, four of those severs caused by the three before. The ribbon is track, not membership.
- **Junctions stopped being laid.** `moveAlongTrack` re-reads each block's shape as it reaches it,
  several blocks a tick, while `steer` lays only the block a cart stands on when the tick begins.
  Eight cars each laying the block under them had hidden that; one leading car does not. Laid one
  block ahead of the cart now, on every step.
- **A car handed back to the correction still read as placed** for one tick, through the grace in
  `seated()` that exists for cars ticking before their leader — so it steered no junction while
  vanilla moved it over one. The placer drops the seat marks when it bails.
- **Reversal turned cars through 180 degrees** rather than flipping them, sweeping the chain to the
  car behind through the cart body, and the flip test compared two angles unwrapped. Vanilla negates
  `xRot` for a flipped cart on *every* rotation, not only at the flip; without that clause a car that
  had reversed once rendered every later climb as a descent, which is why slopes failed while flat
  track was clean.
- **Collision predicates read a placed car's velocity**, which the placer writes from the leader's, so
  a car with a stale copy answered differently from its neighbours and a train was solid at one end
  and walked through at the other. Anything asking what a train is doing asks the leading car.

**Deferred, not fixed: a stalled train does not roll back.** Gravity is the acceleration site the
twelve did not count — `calculateSlopeSpeed` is inside `moveAlongTrack`, so a seated car never gets
it and a train on a grade has one car's worth of downhill pull whatever its length. It belongs with
S7 and is recorded there. Everything else in "Done when" was observed: eight cars stable over the
test track, a switch splitting a train in both directions, no visible jump, and chains behaving
through a reversal.

**Two world settings confounded most of this stage's measurements**, and both are worth knowing
before trusting a speed number: `max_minecart_speed` was left at 1 from an earlier session, capping
every plain cart at 0.05 blocks per tick — below the 0.06 a single powered rail adds. That went
unnoticed because `furnace_minecart_speed` replaced the ceiling outright rather than working within
it, so furnace trains ran at 1.0 regardless and looked normal. The rule is gone; the trap is in
`docs/TESTING.md`.

## S6 — The placer made total — **done**

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

### As built

`TrainCoupling` was rewritten rather than edited down, and `settle`, `nudge`, `slack`, `signedSpeed`,
the run-splitting and the distance sever went with `SETTLE`, `MAX_SHIFT`, `MOVING_SHIFT`,
`NUDGE_ATTEMPTS` and `SEVER_SLACK`. `RailPath.alongTrack` and the public `exitTaken` are gone;
`exitTaken` survives private, because `ahead` needs it. `TARGET_SPACING` is the car pitch and
`MAX_LINK_DISTANCE` the coupling reach, as planned. A grep for the deleted names over `src/main/java`
returns only prose and unrelated identifiers — the switch's own `settleLater` and `SETTLE_DELAY`.

The driver is six calls: `reachTrain`, `TrainPlacer.seat`, `cutIfLost` if that refuses, `divertedCar`
into `sever`, `trimRibbon`, `TrainMeter.measure`. All five bail reasons are handled by name.

**Every sever is now an event held for ten ticks.** The diverted check was the one fault with no
persistence, and it over-fired at once: a switch shared between two legs is re-steered constantly, so
a single tick's disagreement cut a train that was running correctly. It went through `faultHeld(car,
ticks)`, which counts the named car and zeroes every other in the same call — one car failing for ten
consecutive ticks is a fault, ten cars failing once each is a train crossing something awkward.

**The stage's real finding was that a reversal is two failures, not one.** A train reversed itself on
the test track, and the instrumented chain turned out to be: the ribbon resets; `order()` returns zero
because it is keyed to `ribbon.resets()`, which is deliberate; the placer bails `ORDER_UNREADABLE` and
drops the seat marks; vanilla therefore moves all eight cars itself for that tick; and `decideLeader`
reads one car's vanilla velocity and confidently names the other end. `order` and the leading end
re-bootstrap on the same tick from the worst evidence available, and the leading end is the *input* to
`order`. Which end leads is a fact about links and motion and has no business depending on the
ribbon's numbering, so it is now held for three ticks against a confident reading — a real reversal
names its new end on every tick after it, and while the train decelerates through zero `decideLeader`
already returns the end it had.

**The resets were not eliminated, only made survivable.** Two in one second at `y=-53`, with an
earlier cluster at `y=-46`, on track with switches being re-steered continuously a few blocks away,
at 0.16 blocks per tick and `worst=0.00` — so not the head outrunning its own ribbon, and not a
holding failure. The ribbon had recorded a different route through that area than the head then took.
Left open; with the leading end held, a reset now costs a tick of seating rather than the train.

**`sameTrain` cannot answer whether two carts are chained.** Carts were being destroyed at chunk
borders, and there is exactly one path in the mod that destroys a cart — `CartImpact.writeOff`, from
`CartInteraction.WRECK`. It was guarded by `TrainCoupling.sameTrain`, which walks resolved
neighbours, and `getNeighbours` drops a slot whose UUID will not resolve — which is precisely what an
unloaded chunk causes. A train straddling a border genuinely becomes two `Train` objects, so two carts
chained to each other went into the wreck test against one another and both died. The link slots hold
UUIDs and do not care what resolves, so `Linkable.coupled` asks them directly, and `CartInteraction`
gained `BUFFER` so a same-train pair answers before the wreck test rather than after it.

**Steering had to be laid from the step, not the heading.** S5 moved junction laying one block ahead;
S6 found the direction was also wrong. Vanilla turns the delta onto the rail *inside*
`stepAlongTrack`, after the `makeStepAlongTrack` HEAD hook, so the hook reads the heading the cart had
coming *into* the block. On straight track that is the same vector; one block past a bend it is ninety
degrees out, and a junction there was laid on the pre-bend axis and turned the train back onto it. The
axis now comes from `next.subtract(pos)`. The switch had both halves of the fault — it also picked its
lookahead block with `on.relative(travel)` — and only ever laid one switch per tick, so at speed four
of every five switches crossed were read however they were last left. It has a `steerAhead` of its
own now, fired per step alongside the junction's.

**Two more of the same shape, found by playing rather than reading.** Fuelling a furnace cart set its
impulse from a direction to the *player*, which across a track drives a standing cart at the rail and
leaves vanilla's stepper to break a tie between two exits it agrees with equally — out whichever end
rounding favours, flipping the cart and its chain anchors. Impulses are projected onto the rail now.
And a cart's front was whichever way it first happened to roll, because vanilla sets no yaw on
placement; it is taken from the placer's look, and thereafter changes only when travel truly reverses,
which replaced a 175-to-185-degree window that a car covering several blocks a tick steps over.

**A ribbon node is a position, not a promise.** Editing track under a running train crashed the
server: `TrackRibbon.grow` walks one block off its own end node, and mined-out rail read as air into a
cast to `BaseRailBlock`. Every other caller of `RailPath.neighbours` reads the block under a live cart
and guards; that one read recorded state, which is why it was the one the world could invalidate.

**Not observed.** The chunk-border leg of "Done when" is unverified — the fix above is reasoned from
the single destroy path, not watched. Everything else was: a switch splitting a train in both
directions, a train run off the end of track, corners at speed without a sever, and a train shoved
without one.

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

**Gravity is a site the twelve did not count.** A seated car never reaches `moveAlongTrack`, so
`calculateSlopeSpeed` runs on the leading car alone and a train on a grade has one car's worth of
downhill pull whatever its length. On the way up that flatters it; stalled, it is why a train is slow
to roll back. Mass governs the head here as everywhere else, so the pull wants scaling by the cars
behind rather than repointing.

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

**Which path should own the deferred switch curve.** Settled the way S6 predicted, by the sever
becoming an acceptable outcome rather than by guarding `orient`. A car's own `steer` → `orient` still
recomputes the shape from the live `POWERED`, and `occupiedByOther` discounts only the steering cart,
so a curve thrown under a crossing car does still arrive by that path — but the diverted check then
notices the rail no longer joins the edges the ribbon recorded and cuts ahead of that car, which is a
stated sever reason. `occupied` also asks `TrainCoupling.crossedByTrain` now, so a whole train lying
across a switch reads as occupancy rather than as a run of brief absences between cars.

**Part of S7 has already landed**, out of order, because S6 could not be finished without it. A shove
now reaches `TrainCoupling.driven` — the car vanilla still drives — rather than whichever car was
leaned on, whose velocity the placer overwrites; that was the "a shove landing on the fifth car has to
reach the head" half of S7's shove work, and only the *retune* half is left. `furnaceSpeed` is gone
entirely with its rule. The other ten sites are untouched.

**`furnace_minecart_speed` is gone**, settled by removing it. It set a furnace cart's ceiling
outright from its own rule — 20 blocks per second against the vanilla rule's 8 — and `trainSpeed`
handed that to every car the furnace cart towed by taking the highest ceiling in the train, so a
furnace train ran at two and a half times the world's stated cap and lowering that cap did nothing to
it. One speed rule now governs every cart. The mixin keeps its real job, undoing vanilla's halving of
a furnace cart so it is not the slowest car in its own train, and takes the ceiling from
`max_minecart_speed` like everything else. Furnace trains run at 0.4 blocks per tick at the vanilla
default rather than 1.0; a world that wants the old speed raises `max_minecart_speed` to 20, which
now also raises the carts it tows instead of leaving them behind.

**What rolling friction should be.** Reworked in S7, because the model it had could not be tuned
out of a fault it created. Vanilla's friction is purely proportional — a fixed fraction of the speed
each tick — so it never reaches zero, and this mod raised that fraction as the speed fell to finish
the job. Two proportional terms, and the product of them has *two* equilibria on a slope: with
`rail_drag_speed` at its default the stable one sits at 0.052 blocks per tick and the escape velocity
above it at 0.090, so a cart starting from rest on any grade is caught at a little over one metre per
second and can never climb out to vanilla's own 6.25. The speeds were right and the shape was wrong.

Rolling resistance is a roughly constant force, not a proportional one, and a constant loss of speed
per tick brings a cart to a dead stop in finite time by itself — which is the whole of what the ramp
and the crawl floor were both reaching for, and it cannot create a second equilibrium because the
dynamics stay linear in the speed. So the ramp, the floor and the length scaling under it are gone
and one flat term replaces them. The length scaling is not merely unnecessary but wrong: resistance
is per car and so is mass, so a long train coasts like a short one, exactly as a long train rolls
downhill no faster than a single cart.

The rule keeps its name, default and meaning. It reads as the speed at which this flat loss matches
vanilla's proportional drag on an empty cart, so below that speed the flat term is the larger of the
two — which is what "the speed below which rolling friction is laid on harder" always said. It is
capped at a quarter of the pull gravity gives on a slope, because the rule's range runs to a thousand
and most of that, taken at face value, would exceed gravity and stop a cart rolling downhill at all.

Measured against the old model at the same rule value of 3: downhill from rest 0.84 blocks per second
becomes 4.53, a stop from one metre per second takes about a second either way, and a *ridden* cart's
stop from that speed falls from most of a minute to a second and a quarter. The cap binds for every
value above two, so a saved world keeps its 3 and needs no migration; 1 is the one gentler setting,
worth 5.09 downhill, and 0 still means vanilla's friction alone and a cart that never quite stops.

**What the car pitch should be.** Settled at 1.5, by watching a train on the test track after S5.
The 2.0 it had was tuned as a correction target — the value a spacing error was measured against —
and under placement it is simply how far apart cars sit, where it read as too loose. `MAX_LINK_DISTANCE`
deliberately stays at 2.25: it governs how far apart a player may couple two carts, which should not
shrink just because a coupled train now rides tighter.

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
