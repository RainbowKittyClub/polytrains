# Force models

Two models for friction and acceleration, chosen by a game rule. The default reproduces vanilla, so
a world that installs this mod for its links, chains, junctions and switches gets vanilla's own
motion; `realistic_physics` opts into the force model this mod has grown instead. Wrecking goes with
that model rather than staying on a rule of its own: it is the one place where this mod's forces
destroy a player's property, and a world that asked for vanilla motion has not asked for that.

Claims about vanilla are checked against `libs/decompiledjava/minecraft-26.2/` and cited by line;
claims about this mod are checked against the tree at `5f6aa3b`. The figures in Reference are
computed from those verified force laws, not measured in-world — the model behind them reproduces
the three numbers `train-ribbon.md` recorded from observation (4.53, 5.09 and 6.09 blocks per second
downhill), which is why they are quoted, but they are arithmetic and a stage is not closed by them.

A stage reads **built** when its code is in, lints, builds and boots with no mixin failure, but its
"Done when" has not been watched in-world yet — the numbers in its "As built" are then computed from
the force laws as written, not measured. **done** means observed. The distinction matters here more
than usual, because every figure in this document is arithmetic until somebody rolls a cart.

Stage order. **S1 names both models and adds the rule, and S2 defines cart mass** — landed
together, for the reason S1's "As built" gives; everything after depends on both, since the physical
model divides almost everything by mass. S3 (friction), S4 (bend
loss), S5 (the engine) and S6 (wrecking) are independent of each other and can land in any order;
**the feature is complete at the end of S6**, since those four cover every gated force and the one
consequence of force this mod lets destroy a player's property. S7 was written as a review, since the one line of
code it held landed in S6, and turned out to be the largest stage here — the Findings entry on a
train freezing on a grade is why. S8 documents what landed and is last. S9 is a loose end this work
exposes rather than creates, and S3 made it live: `rail_drag_speed` now reaches nothing at all under
the default model.

---

## Findings that reshape the work

**"Exactly vanilla" is only well defined for a single unlinked cart.** Vanilla has no trains, so
there is no vanilla behaviour for a train to mirror. The target has to be stated twice: for an
unlinked cart, *bit-exact* — every hook returns what vanilla computed, untouched; for a train, the
**ensemble** those same carts would form in vanilla — what you would see if the links were replaced
by a player pushing the cars along in formation.

**So the divergences split in two, and only half of them can be gated.** The placer leaves one car
running vanilla's pipeline and places the rest, which means several of this mod's scalings exist to
*reconstruct* the ensemble rather than to depart from it. `TrainCoupling.slopePull` is the clearest:
vanilla pulls each of ten cars on a slope by 0.0078125, so the ensemble accelerates at 0.0078125,
while the mod's leading car would take the whole of it and hand the train ten times too much. Turning
that off yields neither vanilla nor physics. The same holds for the train-mean friction, the drive
hand-off to a non-engine leader, the shove divided by mass, and the impact drag applied once.

| Site | Kind |
|---|---|
| `TrainCoupling.hauled` — the train's drive given to a non-engine leader | repair |
| `EntityMixin:85` — a shove divided by the train's mass | repair |
| `CartImpact:193` — impact drag applied once to the driven car | repair |
| `RailBrake:115` — the brake launching the driven car | repair |
| `RailDrag.shared` — friction averaged over the train | repair, **vanilla model only** |
| `TrainCoupling.slopePull` — gravity from the cars on the grade | repair, **weighted per model** |
| `TrainCoupling.braked` — the brake from the cars holding | repair |
| `TrainCoupling.boostPull` — the boost from the cars on lit rails | repair, **launch per model** |
| `RailDrag.slowed`'s retarding law | **departure** |
| `RailDrag.bend` — the per-kink charge | **departure** |
| `Train.drive`'s divisor | **departure** |
| `MinecartFurnaceMixin:122` — flat impulse, no decay, no friction | **departure** |
| `MinecartFurnaceMixin:113` — vanilla's halved ceiling undone | **departure** |

**Some sites are a repair and a departure in the same expression**, which is why this is not a
mechanical job of wrapping hooks in an `if`. `Train.drive()` divides the train's output by
`cars.size() - FREE_CARS * engines`; the ensemble-vanilla divisor is `cars.size()`, so the *divisor*
is what the model picks, not whether the method runs. `TrainCoupling.slopePull` is the same shape,
weighting the cars' grades by count or by mass. And `RailDrag.slowed` holds the train mean and the
mod's own retarding term in one call, which the next finding splits apart entirely.

**Vanilla's furnace impulse is arbitrary, so it cannot be mirrored exactly — only observably.**
`MinecartFurnace.addFuel` sets `push = position().subtract(interactingPos).horizontal()`
(`MinecartFurnace.java:122`), unnormalized: the impulse is the distance from wherever the player was
standing. Terminal speed is `0.975p / 0.22 = 4.43p`, so any plausible `p` is far past the ceiling and
the observable behaviour is "reaches the halved cap at once and climbs any grade". That is the target
to reproduce; reproducing `p` itself would be reproducing a quantity vanilla does not define.

**The impulse projection stays unconditional in both models, and is one of two deliberate deviations
from vanilla in the default — see the stall finding below for the other.** Vanilla projects the impulse onto the direction of travel in
`calculateNewPushAlong` (`MinecartFurnace.java:103-105`) but only once the cart is already moving, so
`push` on a *stationary* cart is unprojected — which is what rotates a cart on its rails when a
furnace is fed into it and stretches the chains across it. Restoring vanilla here would restore that
fault, and the chains this mod draws are what make it visible, so the fix is kept and the deviation
is stated rather than gated. Everything else in the default model is vanilla's own value untouched.

**Mass makes the physical model a separate force law rather than vanilla plus a term, and that is
forced by arithmetic rather than chosen.** A lighter cart has to accelerate more readily and stop
sooner, a heavier one the reverse. Acceleration is easy — `a = F/m` gives it outright. Stopping is
not: rolling resistance proper is proportional to weight, so it cancels in `F/m` and a heavy cart
would stop in the same distance as a light one. What makes a loaded wagon coast further than an empty
one is the *load-independent* part of the resistance — bearing drag, and air — which divides by mass
and so falls as a specific figure the heavier the wagon gets. That is the shape to build.

Vanilla runs the other way, and strongly: `keep = 0.98 + (15 - comparator) × 0.001`
(`AbstractMinecartContainer.java:102-106`) gives a **full** chest cart the *most* friction, 0.98
against an empty one's 0.995. A mass-scaled flat term cannot overcome a proportional term running
against it — with the current flat figure divided by mass, a chest cart still coasts 36.1 blocks
empty and 16.1 full, which is backwards. Making the physical model own the whole retarding law —
deceleration of `(own + flat) / mass` — reads 11.8, 17.9 and 24.0 blocks as it fills, which is the
requirement.

Two things fall out of that. `RailDrag.shared`, the train mean of vanilla's per-cart factors, is a
repair **only the vanilla model needs**: a law that divides by the train's total mass has no per-car
factor left to average. And `Train.drive`'s `FREE_CARS` can go — it was an arbitrary generosity
standing in for the mass effect, and once the drive is divided by real mass the curve it was faking
comes out of the arithmetic.

**Vanilla's cart-against-cart collision is already untouched, so "stop or bounce" needs nothing
built — only the wreck taken off it.** `AbstractMinecartMixin`'s `push` hook returns early for
another minecart on purpose (`:333`), leaving vanilla's `pushOtherMinecart`
(`AbstractMinecart.java:543-576`), which is a real exchange: both carts damped to `× 0.2` of their
own movement and each pushed by the *mean* of the two, with a furnace cart privileged to keep `0.95`
and shunt whatever it met. `CartInteraction.of` already falls through to `COLLIDE` — "vanilla's solid
collision", by its own javadoc — for every cart pair that does not wreck. So the gate is one
predicate, `wrecks`, and what is on the other side of it is vanilla's behaviour by construction
rather than by reimplementation.

What this mod adds on top is one line: a struck cart is put onto the striker's whole movement
(`CartImpact.java:104`). That is mass-blind, as is vanilla's own exchange — vanilla damps the striker
to `0.2` whatever it weighs, so a ten-car train's leader meeting one loose cart is stopped as dead as
a single cart would be. Neither is right once mass exists, which is an opportunity this work opens
rather than a fault it has to close; see Open decisions.

**Vanilla's own ordering is the opposite of the requirement, not a weaker version of it.** Worth
stating plainly because it is easy to remember backwards: `getRedstoneSignalFromContainer` returns 0
for an empty container and 15 for a full one (`AbstractContainerMenu.java:755-769`), so vanilla's
`emptiness` is `15 - signal` and `keep` *rises* with it. An empty chest cart keeps 0.995 and coasts
about 78 blocks; a full one keeps 0.98 and coasts about 19. Vanilla's empty carts coast **further**.
The physical model inverts that, deliberately, and the vanilla model reproduces it exactly by never
touching the factor.

**What a cart weighs is the only thing the two models need to disagree about for mass, and making it
the switch keeps a lone cart bit-exact.** `TrainCoupling.mass` returns `Train.of(cart).size()` today,
which is 1 for an unlinked cart — and that 1 is what makes `EntityMixin`'s `1 / mass` shove
indistinguishable from vanilla. Give mass a real value and a lone full chest cart takes half a shove,
which is a departure nobody asked the rule for. So the switch belongs on **one cart's mass**: 1.0 for
everything under `VANILLA`, the real figure under `PHYSICAL`.

Everything that divides by mass then inherits the model for free, and two of the methods this
document first listed collapse into it. `Train.drive`'s divisor is `mass` in both models, which is
the car count when every car weighs one. `slopePull`'s weighting is the same identity:
`Σ(mᵢ × gradeᵢ) / Σmᵢ` with every `mᵢ` at 1.0 *is* the count-weighted mean it computes now. What is
left model-dependent is only the three places where the two models run genuinely different laws —
the retarding law, the bend charge and the engine — plus the furnace ceiling.

**`RailDrag.keepOf` mirrors vanilla's container friction with one cart wrong, which S3 inherits.**
For an unopened loot cart it falls through to `cart.getBehavior().getSlowdownFactor()` and reads
0.975, where vanilla reads 0.98 — vanilla skips only the *emptiness bonus* for a loot cart, not the
container factor itself (`AbstractMinecartContainer.java:102-106`). It reaches nothing today but the
train mean, and only for a train carrying an unopened loot cart, so the stakes are small; but the
vanilla model's whole claim is that it returns vanilla's own figure, and this one does not. Fixed in
S3, which owns `keepOf`, rather than folded into an otherwise inert stage.

**A gated law has to be applied at whichever vanilla override is the outermost one for that kind of
cart, and only one cart kind makes that hard.** `MinecartFurnace` is the sole override of either
`applyNaturalSlowdown` (`:97`) or `getMaxSpeed` (`:70`) that calls `super` — checked across the whole
vehicle package — and it scales the movement on the way past, by `0.98` coasting or `0.8` plus the
impulse under power. So a hook on `AbstractMinecart.applyNaturalSlowdown` is handed a figure a tick
of vanilla's friction has already come out of, and a hook on `AbstractMinecart.getMaxSpeed` is handed
the ceiling *before* the halving. The physical model works from the movement the cart arrived with,
so the first of those made a furnace cart read as barely different between the two models; the second
would have applied a train's shared ceiling to the wrong figure.

The shape that follows is the same for both: the generic hook stands down for a furnace cart, and
`MinecartFurnaceMixin` asks at its own return, where the argument is still the real one. A container
cart needs no such arrangement — its override never calls super, so it already asks for itself.

**One asymmetry the gating creates, which has to be repaired rather than accepted.** Restoring
vanilla's halved furnace ceiling makes the ceiling depend on which end of a train leads: the leading
car is the one vanilla clamps, so a furnace train capped at 4 m/s one way round runs at 8 the other
and doubles its speed when it reverses. The ensemble answer is 4 either way — a line of carts cannot
outrun what is pushing it — so the leading car takes the lowest ceiling in its train. It needs no
model check of its own, because each car's figure is already the model's: the physical model undoes
the halving, so the lowest ceiling is then everyone's and the repair changes nothing.

**Every vanilla figure in this document assumes `max_minecart_speed` is at its default 8, and the
vanilla engine stops being usable well before that rule's ceiling of 1000.** Vanilla's engine
saturates whatever cap it is given, almost immediately: the impulse is re-added whole each tick
against a `0.8` decay, so terminal speed is `4.43 × |push|` — roughly 4.4 blocks a tick, or 88 m/s —
and any cap below that is reached in about three ticks. At the default 8 the furnace cap is 4 m/s and
that instant saturation is exactly the sedate engine vanilla is known for. At
`max_minecart_speed = 100` the same code puts a standing cart at 50 m/s inside a fifth of a second,
which leaves the track at the first corner.

Measured both ways on the dev server: at 100, a fuelled cart read 1.74 blocks a tick after one second
and the capped 2.5 after two, then ran off the end of the test straight. At 8 it pinned at exactly
0.20 and held it. So the behaviour is right and the rule value is the whole difference.

This is not a fault to fix, because it is what a vanilla world with that rule set would do, and the
physical model is the one that copes — its engine is a fixed force, so it accelerates gradually at
any ceiling. Settled in Open decisions as a reason to turn the rule on rather than as something for
the default to work around, which makes it S8's job to say so in the README.

**Vanilla annihilates its own impulse at any 90-degree change of direction, and a two-block U-turn
is one.** `calculateNewPushAlong` re-aims by
`push.projectedOn(deltaMovement).normalize().scale(push.length())`
(`MinecartFurnace.java:100-106`). The projection onto a perpendicular heading is the zero vector, and
`Vec3.normalize` returns `ZERO` below `1.0E-5` (`Vec3.java:83-86`), so `push` becomes zero — and
vanilla only reaches its own re-aiming while `push.lengthSqr() > 1.0E-7`, so a zeroed impulse locks
the engine off for good. Measured on a two-block U-turn: `push` went from `(0.7071, 0.7071)` to
`(0.0, 0.0)` between the two corner blocks and the cart coasted to a halt with fuel burning.

A single 90-degree corner does not do it — vanilla builds one from two 45-degree kinks, so the dot
product is 0.707 at each and the projection is healthy. It takes a direction change of a right angle
in *one* step, which is what a two-block U-turn is.

**This mod's own `aimAlong` was shielding vanilla from that, and S5 wrongly put the shield behind the
model gate.** The plan already said the aiming is unconditional in both models; the first
implementation bundled it in with the force and declined both together under `VANILLA`, which handed
the fault straight back. Aiming *before* the model decides whose force applies fixes it for a reason
worth stating: once the impulse is already parallel to the movement, vanilla's projection has nothing
left to do, and a projection with nothing to do cannot be degenerate. Verified after the fix —
`|push|` holds at 1.0 through the turn in both models and the cart runs the return leg at full speed.

**A stall is the one state neither model can read a heading in, and a fuelled cart at a dead end
wedges there for good.** Both models re-aim the impulse from the cart's own movement — vanilla in
`calculateNewPushAlong`, which needs `deltaMovement.horizontalDistanceSqr() > 0.001`
(`MinecartFurnace.java:100-106`), and this mod in `aimAlong`, which needs the same above its own
floor. A cart against a buffer has no movement at all: `stepAlongTrack` zeroes a step that covered no
ground, so the engine is handed its impulse straight back into the obstruction every tick, the cart
makes no progress, and the gate that would turn it round never opens. Measured: a cart reaching a
buffer sat at `motion=0` with `pushX=1.0` indefinitely, fuel burning, in **both** models.

So it predates this work — the physical model is the old behaviour and wedges identically — and what
changed is only how visible it is, the impulse into the buffer going from `0.015625` to `1.0` and the
cart arriving in five seconds instead of a minute. The consequence is that a **switchback is
impossible**, which is what surfaced it.

The fix has to count the stall rather than derive it, there being no movement to derive from:
`minecarttweaks$countStall` runs at the top of the tick, where last tick's movement is still what the
cart has, and turns the impulse round after four ticks of no progress under power. Unconditional in
both models, on the same grounds as the impulse projection — the trap is vanilla's, and a cart
nothing can move is not behaviour worth mirroring. That makes two deliberate deviations in the
default model, and they are the same deviation twice: vanilla aims its impulse from the cart's own
movement, which is either absent or perpendicular in exactly the two cases above, and it has no
fallback for either. Both fixes were verified together on a circuit holding two U-turns and a buffer
stop, which both models now run indefinitely.

**A lit furnace cart that comes off the rails is dead for good.** Both models put the engine inside
`applyNaturalSlowdown`, and that is only ever called from `NewMinecartBehavior.calculateTrackSpeed`
(`:338`), which sits in the on-rail branch of `moveAlongTrack`. Off the rails the loop takes its
`else` at `:264-265` and calls `AbstractMinecart.comeOffTrack` (`:377-388`) instead, which clamps,
halves the movement every tick on the ground, moves, and applies air drag — and asks for no impulse
at all. So an off-track cart stops within a few ticks and then sits inert, burning fuel it cannot
use, with a perfectly healthy `push`: measured at `|push| = 1.0` while the movement decayed to
`6.7E-11` and the position stopped changing.

Vanilla's own structure, not introduced here, and it is what made several derailments read as "the
cart stopped and will not move" while diagnosing the two faults above. Left alone deliberately: a
derailed cart ought to stop, and an engine that fires off the rails makes a furnace cart a ground
vehicle that can drive itself over terrain. The only real cost is the wasted fuel. If it is ever
worth changing, the shape is a small impulse inside `comeOffTrack` aimed at the nearest rail,
unconditional in both models like the other two impulse fixes.

**`CartImpact` handed a struck cart the striker's whole movement and billed the striker nothing, so
a line of loose carts created momentum out of one nudge.** The line read
`struck.setDeltaMovement(movement)` — a copy, not an exchange. One cart at `v` became two carts at
`v`, and `drag`, which is what `slowTrain` bills the striker through, was only ever fed by the
living-entity branch. Every cart in the sweep got the copy, and each of them re-ran it the next tick.

Measured on nine unlinked carts standing one block apart, a single `0.05` blocks-a-tick nudge — one
block a second — to the fifth of them:

| | Peak speed reached | Carts left |
|---|---|---|
| Physical model, before | `0.437` | 5 of 9 |
| Vanilla model, before | `0.293` | 9 of 9 |
| Either model, after | `0.069` | 9 of 9 |

Two separate faults in the one line, and this is why it presented as violently as it did. **The
amplification is not gated** — the copy runs under both models, and the vanilla row above shows the
same nudge reaching six times its own size with the rule off. **Only the destruction is gated**, by
`wrecks()`, which is why carts were seen breaking with `realistic_physics` on and not without it: the
amplification pushes closing momentum past `cart_wreck_momentum` and then S6's wrecking does exactly
what it is meant to. The gating was right; what it was gating was a fault.

The second fault is subtler and was visible in the tail of the measurement: the carts drifted at a
**constant** `-0.013` blocks a tick for over a hundred and twenty ticks, where vanilla's `0.975` a
tick should have left a thousandth of it. A copy overwrites a decayed velocity with a neighbour's
undecayed one, so a close-packed group of carts rewrites each other's movement often enough that
**friction never gets a tick's hold on any of them** and they coast for ever. Neither model's
retarding law was reaching them at all.

Fixed as an inelastic meeting along the track: both sides leave at the momentum-weighted mean of
what they arrived with, the struck side written onto the car its train is driven by for the reason
`slowTrain` gives, and the striker billed through the same `drag` a struck mob costs it — so what one
side gains the other loses, whatever their masses. The bill is in `shove`'s own units, which is what
makes the two accountings the same one. Shunting survives and is now physical: a cart arriving at
`0.34` into three standing carts leaves at `0.046` while the three move off at about `0.06` each,
where before it would have handed each of them the whole `0.34`.

The fix also stops the exchange reaching **behind** the striker. The sweep deliberately looks both
ways so a fast cart still catches a mob it passed clean through in one tick, but a cart cannot drag
along one it is moving away from, and reaching backwards is what let one nudge light up a line in
both directions at once.

**A train's gravity is a rescale of the leading car's own downhill pull, so a train stops dead the
moment that one car reaches flat track — and S7's planned fix cannot repair it.** Reported from play
as a chest-cart train freezing on a hill when its furnace cart is broken, leaving two or three cars
at the bottom and the rest on the bank. Two separate faults, both reproduced headless with the
placer's own instrumentation on.

*The placer has no leading end for a train that has never moved.* `Train.decideLeader` reads which
end leads off `cars.getFirst().getDeltaMovement()`, and a train at rest has none, so it falls back to
`heldLeader()` — which is null when nothing was ever held. `leader()` then answers null and
`TrainPlacer` refuses outright: measured as `train of 8 not seated, NO_LEADING_END: cars=8`, every
tick. Every car is then on vanilla's own pipeline with its own gravity, so a train straddling a grade
rolls the cars that are on the slope into the cars that are not — measured collapsing to cars 3 and 4
both at `342.00` and cars 5 and 6 at `342.98` and `343.00`. **The collapse is then permanent**,
because `lieOf` refuses to read which way round a train lies once two cars are closer than a quarter
of the pitch, which is exactly what a collapsed train looks like. `driven()` already falls back to
`train.anchor()` for this same hole and says so in its comment; the placer never got the fallback.
Fixed by answering with an end car, either end, since the orientation is read off the cars and not
off that choice. With it in, the train holds rigid spacing and no car moves alone.

*Gravity reaches only the leader, as a rescale, so a flat leader means a weightless train.*
`NewMinecartBehaviorMixin$sharedSlope` takes `cir.getReturnValue().subtract(movement)` — the pull
vanilla worked out for this one car — and scales it by `TrainCoupling.slopeShare`. Vanilla's pull for
a cart on flat track is the zero vector, and the hook returns early on exactly that; `slopeShare`
agrees, since its `under` argument is the *leader's own* grade and zero makes it answer `1.0`. So a
train whose leading car stands on the flat feels **no downhill force at all**, however many of its
cars hang on the bank. Measured with the first fault repaired: eight cars all on the grade roll down
together at `-0.055` to `-0.077`, and the instant the downhill car reaches level track every velocity
goes to zero with six cars still on the slope. That is the reported behaviour precisely.

**This invalidates S7's approach, not just its formula.** S7 plans `slopeShare` as
`Σ(mᵢ × gradeᵢ) / Σmᵢ`, which is the right weighting and still cannot help: a rescale of zero is
zero, and the failing case is precisely the one where there is nothing to rescale. The pull has to be
*computed* for the train rather than scaled off whichever car happens to be leading — one car's worth
of gravity times the fraction of the train on a grade, laid along the track at the leader and signed
toward whichever end of the train is lower. The ribbon already knows every car's arc and the tangent
at it, so the direction is available; what is not available today is any pull at all to point. Built
that way in S7, which records what the computed pull is made of and what it measures.

Worth noting what is *not* wrong: a lone cart rolls down a grade correctly in both models, measured
accelerating to `-0.20` blocks a tick and coasting out onto the flat, and a train entirely on a grade
rolls correctly too. Only the straddling case fails, which is why it took a report from play to find.

**The brake and the boost are the same fault as gravity, and they do not take the same repair.**
Every per-cart track force lives inside the `moveAlongTrack` the placer cancels, so each reaches
exactly one car of a train. Measured on a four-car train: a leading car onto an unpowered powered
rail stops dead at `x=320.988`, while the same rail under a **trailing** car does nothing at all —
the train rolls off it on friction alone, which is the "a train can be pulled off the brake" report.
A trailing car over two *lit* powered rails gains nothing either.

Gravity's repair does not carry over, because vanilla states these forces in three different units:

| Force | Vanilla's expression | Kind |
| --- | --- | --- |
| Slope | `movement + 0.0078125` along the grade | an impulse |
| Boost, rolling | `normalize().scale(len + 0.06)` | an impulse |
| Boost, standing | `powerDirection.scale(len + 0.2)` | a velocity, **assigned** |
| Brake | `movement.scale(0.5)`, or `ZERO` below 0.03 | a fraction, **multiplied** |

Only the impulses divide by mass. `Σf / Σmᵢ` is meaningless for the other two: an assigned velocity
has no mass to act on, and a fraction has no units at all. What generalises to all four is
**momentum**. A car whose speed is set to `v` while coupled to a train shares that momentum with it,
so the train leaves at `v × Σ_lit mᵢ / Σmᵢ`; a car whose speed is cut to a fraction `k` shares the
loss the same way, so the train keeps `1 − share × (1 − k)`. Both collapse to vanilla's own figure
when every car is on the rail, which is the ensemble test, and both reduce to a car-count ratio under
`VANILLA`, where every car weighs one.

**The rolling boost needs no gate at all, and that is worth stating because it looks like it should.**
`Σ 0.06 / Σmᵢ` at a uniform mass is `k/n × 0.06`, which reads as a train accelerating a tenth as hard
as it does today. It does not: over a whole crossing every car spends the same time on the section,
so the car-ticks sum back to exactly what the leader alone collects now. A ten-car train over an
eight-block booster at 0.4 blocks a tick gains `20 × 0.06` either way. A section a train sits fully
across is unchanged outright; only partial coverage changes, from all-or-nothing at the head to
proportional. What the gate buys here is the mass term and nothing else — a loaded train gains less,
an empty one gains what it always did.

**The standing start is the one departure, and the two models want opposite answers.** Vanilla's
launch branch exists to unstick a stopped cart, and a train is exactly where "unstick" and
"accelerate a mass" come apart. Under `VANILLA` the kick stays whole: one lit rail under any car
launches the train at 0.2, as it launches one cart, so a railway built on a single kick rail keeps
working. Under `PHYSICAL` nothing is free, so it takes the momentum share like everything else and a
long or loaded train needs proportionally more lit rail beneath it to start — the same constraint
the engine and the grade already impose, and one to build around rather than a penalty.

**A brake is a hold, not only a retardation**, which splits its two branches. The `× 0.5` is shared
by momentum, so a train one car of ten onto a brake sheds speed gently and a train fully on brakes
halves exactly as vanilla does. The dead stop below `0.03` is not shared: a clamped brake is a static
constraint, and a held car holds the train coupled to it. All-or-nothing on any car is both what the
report asks for and what stops a train creeping off a rail it is only fractionally standing on.

**Weighing both sides of a share cancels the mass out of it, and the first cut of S11 did.** The
share of a track force a train is owed was written `Σ_matching mᵢ / Σ_all mᵢ`, which reads as a mass
term and is not one: mass appears above and below the divide and goes away. Measured on the eight-
block booster, one tick from 0.100 — a single empty cart gained `0.0565`, four empty cars gained
`0.0565`, and four full chest carts, twelve units of mass, gained `0.0588`, the whole of the
difference being drag rather than the boost. A loaded train accelerated exactly like an empty one
under the model whose entire premise is that it should not.

The correction is one character of algebra: **count on top, weigh underneath**, `count_matching /
Σ_all mᵢ`. A rail pushes each car standing on it with the same fixed force whatever that car is
carrying, so the numerator counts cars; the train's own mass is what that force has to move, so the
denominator sums masses. Under `VANILLA` every car weighs exactly one, so `count/Σmᵢ` *is* `k/n` and
the default is untouched — which is also why the fault survived the first round of measurements, all
of which used empty carts.

**The same expression then settles the brake, which is why the two are one finding.** Vanilla states
the brake as a fraction and the boost as an addition, and a fraction spread along a train does not
conserve what it takes: `(1−x)ⁿ > 1−nx`, so a long train sheds strictly less than a short one over
the same rail. Measured with one brake rail, entering at `0.4`, against a no-brake control where
every train coasted to the same stopping point to fourteen digits — the brake cost a single cart
`8.74` blocks of coast, a four-car train `7.08`, an eight-car train `5.55`. The brake was also
entirely mass-blind: four empty cars and four full chest carts both left `0.4` at `0.194` and
`0.198`, a difference that is again only drag.

So the brake takes the same force-over-mass shape under the physical model, and keeps vanilla's
fraction under the default — `RailForces.halted`, the second method the models answer differently.
Both of the properties that wants are real and both were measured: a loaded four-car train now sheds
`0.036` a tick where an empty one sheds `0.109`, three times harder to stop; and length still tells,
an eight-car train overrunning one brake rail by `2.78` blocks against a two-car train's `0.51`.

That length dependence surviving is worth saying, because the conservation argument says it should
not. It is second order and it is a feedback: braking drops the speed, a slower train spends longer
over the rail, and longer over the rail is more braking still. A short train enters that loop harder.
The same loop runs backwards under the boost, where a train that accelerates gently crosses a booster
more slowly and collects marginally more — the three percent by which a four-car train's peak fell
short of a single cart's.

**An engine reads its own rail for the parking brake, so a furnace train can crawl for ever.**
Reported from play as a furnace train that slows to a crawl over a brake rail above a certain speed
instead of stopping, and reproduced exactly: entering a single brake rail at `0.10`, `0.20` and
`0.30` the train stopped dead, and at `0.40` it settled at `0.010416666666666666` blocks a tick and
held it indefinitely.

That figure names the fault. It is `ENGINE_FORCE / train mass` — `6 × 0.0078125` over `4.5` — one
tick of engine impulse exactly, so the train was carrying precisely what the engine had just given it
and nothing more. Each tick the brake found the speed under its `0.03` floor and returned `Vec3.ZERO`;
each tick the engine added its impulse straight back. Neither ever won.

The speed threshold is the two halves of the fault meeting. `minecarttweaks$parked` was
`RailBrake.brakes(this)`, the engine's **own** rail, while the brake itself had already been made to
read the whole train. Below `0.40` the train came to rest with the furnace still over the rail, so it
parked and stopped properly; at `0.40` the furnace overran the rail and only the fourth car was left
on it, which the brake saw and the engine did not.

So it is the same repair as the brake, the boost and gravity before it, and the fourth instance of
it: `TrainCoupling.held`, the whole train's answer. The consequence is worth stating — a brake under
any car now cuts the engine for as long as any car is over it, so a long train clipping one brake
rail at speed loses its drive for the whole crossing rather than only while the engine is passing.
That is what a parking brake should do, and what `hauled` already did for every train with something
other than an engine at its head.

## How these stages get tested

On this mod's dev server, `scripts/devserver.sh rkc/mods/minecart-tweaks restart`, port 25603,
against a world with `minecart_improvements` enabled. The standing test track from
`train-ribbon.md` covers everything here except the long coast, which wants a straight of 130 blocks
or more — vanilla's ridden cart runs that far and the default model has to as well.

**Every stage is tested by toggling one rule and watching the same cart twice**, which is a stronger
harness than this mod has had: the two readings differ in exactly the thing under test, and the
vanilla reading has a predicted value in the Reference table below to be checked against. A stage is
not finished until its "Done when" is observed, so a failure at stage N implicates stage N.

`rcon "gamerule minecarttweaks:realistic_physics true"` toggles live — game rules are a plain map
read and nothing here caches one, so no restart is needed between the two readings of a leg.

Two checks are code-level rather than in-world, and both are part of S1's exit criterion: no gated
hook may mutate anything in the default model, and `grep` for the rule must find it read through
`RailForces` rather than at the sites.

Adding a rule is safe on a live world. **Removing one is not**: `furnace_minecart_speed` is already
gone from the code and its stale key still sits in the live world's
`data/world/data/minecraft/game_rules.dat`, logging a parse error every boot until it is dropped with
`scripts/nbt.py` with the container *stopped*. S6 must not remove another rule without doing the
same.

## S1 — Both models named, the rule added, nothing gated — **done**

**Build.** Add `MTGameRules.REALISTIC_PHYSICS`, a boolean defaulting to **false**. Add
`rail/RailForces`, an enum of two constants — `VANILLA` and `PHYSICAL` — with one abstract method per
gated decision and a `static RailForces of(Level)` that reads the rule. The methods, from the
inventory above:

| Method | `VANILLA` | `PHYSICAL` |
|---|---|---|
| `cartMass(cart)` | `1.0`, always | the real figure from S2 |
| `retarded(cart, movement)` | vanilla's own factor, train-averaged | `(own + flat) / mass` |
| `bendLoss(turn)` | `0.0` | `BEND_LOSS * turn` |
| `engine(movement, push, drive)` | null — vanilla's own branch runs | the flat engine force |
| `topSpeed(halved, full)` | `halved` | `full` |
| `wrecks()` | `false` | `true` |

`cartMass` at a uniform 1.0 makes the drive divisor and the slope weighting count-weighted without
either needing a model of its own, as Findings sets out — which is what keeps this list to six
entries rather than the eight the gated-sites table has rows for.

Two of these return something other than a figure, for the same reason. `engine` returns null under
`VANILLA` rather than reconstructing vanilla's line, because the site is an `@Inject` that can
simply decline to cancel and leave vanilla's own method to run — reimplementing
`MinecartFurnace.applyNaturalSlowdown` in order to return what it would have returned is strictly
more code and strictly more drift. `wrecks` is a plain predicate because that is genuinely all S6
needs; it is the sixth method the S1 draft did not list, and it belongs here rather than as a
seventh rule.

`topSpeed` is the one that was written wrongly the first time. The mixin injects at `getMaxSpeed`
RETURN, where the value has *already* been halved by `MinecartFurnace.getMaxSpeed`
(`MinecartFurnace.java:71`), so the vanilla model simply leaves it: `0.5 × max_minecart_speed`, which
is 4 m/s at the default 8. In water it is `0.75 ×` on top of the behaviour's own in-water halving
(`NewMinecartBehavior.java:486`), so 3 m/s. The physical model is the one that cancels the halving.

Nothing calls the enum in this stage. It exists so that the two models are legible as a pair in one
file and a missing case is a compile error rather than a site someone forgot; the alternative is a
boolean read at each of the ten sites, which is less code and more drift, and the reason for
preferring the enum is that this refactor's whole risk is a site left ungated.

**Done when.** `rcon "gamerule minecarttweaks:realistic_physics"` answers `false`, toggling it
changes nothing observable, `devserver.sh … mixins` reports no failures, and `grep -rn
REALISTIC_PHYSICS src/main/java` finds it only in `MTGameRules` and `RailForces`.

The default is `false`, which inverts this mod's current behaviour for every existing world. That is
the point of the work, but it means a saved world quietly changes how its carts move on the first
boot after the update — worth a line in the README rather than left for someone to discover.

### As built

Landed with S2, in one commit, because the two cannot be separated in the direction this document
puts them. `RailForces` cannot name `cartMass` without a figure for `PHYSICAL` to return, and S2's
gate is `cartMass` itself — so an S1 that compiles on its own has to state a mass the plan calls
wrong and then correct it one commit later. The stages stay numbered as written; only the commit is
shared.

`realistic_physics` is the name, settling the first Open decision by taking this document's own
default.

Six methods, not five. `wrecks()` is the sixth and is listed in the table above; the S1 draft had
left it implicit in S6.

`engine` and `topSpeed` came out differently from the draft signatures. Neither can call `super`
from an enum, which is what the draft's `super.getMaxSpeed(level)` assumed, so `topSpeed` takes both
figures and picks — the mixin, which *can* call `super`, computes them. `engine` returns null for
"leave vanilla's own method alone", which is what an `@Inject` that declines to cancel already does.
`retarded` names the two whole laws by delegating to `RailDrag`: `shared` for the vanilla model,
which needed only its `private` dropped, and `slowed` for the physical one, which is today's
behaviour unchanged. So S3's work is to split `slowed` apart, not to write a law from nothing.

Verified on the dev server: `gamerule minecarttweaks:realistic_physics` answers `false`, mixins
report `None.`, and `grep -rn REALISTIC_PHYSICS src/main/java` finds it in `MTGameRules` and
`RailForces` only.

## S2 — Cart mass, the quantity — **built**

**Build.** Mass has to exist before friction or the engine can divide by it, which is why it comes
first. `TrainCoupling.mass(cart)` already has the right name and returns a car count; generalise it
to a sum of per-cart mass, and add `RailDrag`-side access to one cart's own.

Units, one empty cart being 1.0:

| Contribution | Mass |
|---|---|
| Any cart | 1.0 |
| A furnace cart, instead | 1.5 |
| A container cart's contents | `+ comparator / 15`, so a full one is 2.0 |
| Each passenger | `+ 1.0` |

Two to one between empty and full is the starting ratio, to be adjusted against S3's coast legs rather
than argued about. The comparator reading is the same signal vanilla's own friction uses
(`AbstractContainerMenu.getRedstoneSignalFromContainer`) and `RailDrag.emptiness` already reads it, so
nothing new has to be measured.

The furnace cart's 1.5 costs it half a car's worth of its own output and is what makes double-heading
worth doing: two engines and eight cars come to 11.0 against one engine and nine cars' 10.5, and the
pair climbs a grade where the single engine cannot.

**`cartMass` reports 1.0 for everything under `VANILLA`**, which is not a special case so much as the
whole of how the mass question is gated — see Findings. So this stage's figures only ever apply under
the rule, and the sites that divide by mass need no model check of their own.

Mass is read per tick and not cached: a hopper cart filling up changes mass continuously, and a
comparator read is a cheap loop over a small inventory. The train's total is worth holding for the
tick the way `Train.rolling` is, since the friction path asks for it once per rail block crossed.

**Done when.** A debug line reports plausible mass for a mixed train — a full chest cart, an empty
one, a ridden cart and a plain one summing to 6.0 under the rule and 4.0 without it — and nothing
about how anything moves has changed yet, because nothing reads it.

### As built

**"Nothing reads it" was wrong, and the stage is better for it.** `TrainCoupling.mass` has three
readers already — the shove at `EntityMixin:79`, `shovable`'s eight-car ceiling, and
`closingMomentum`'s reduced mass — so generalising it as this stage asks makes all three mass-aware
the moment the rule is on. That is what S7 wanted from it ("S2 changes what it means for free") and
not a leak, so it stands; the claim of inertness is the part that was wrong. The invariant that
actually holds is the one worth testing: **with the rule off, every one of those three reads the car
count it read before**, because `cartMass` is a uniform 1.0 and a sum of ones is a count.

So the stage is inert at the default and live under the rule, in three places S3 does not cover. A
shove and a wreck threshold are the first two things to feel the model, before any friction or
engine work lands.

`Train.mass(forces, tick)` holds the total for the tick beside `Train.rolling`, for the reason the
plan gave. `RailForces.weigh` is the per-cart figure and is not cached at all — a hopper cart's mass
changes continuously and the comparator read is a short loop.

One vanilla detail the figures had to follow: an **unopened loot cart reads as full**, mass 2.0.
That is vanilla's own choice — `AbstractMinecartContainer.applyNaturalSlowdown` skips the emptiness
bonus entirely when `lootTable != null` (`:102-106`), giving it a full cart's `keep` of 0.98 — so
matching it costs nothing and diverging from it would be a second rule to remember.

The meter now reports `mass=` on its line. It measures nothing — the model is asked and it answers —
but it is the one field that says which model a coast or grade reading was taken under, and S3's and
S5's numbers are worthless without that recorded alongside them.

**The container figures are retuned per kind, which is the adjustment the Build text above deferred.**
One cargo term over a shared 1.0 chassis gave every container cart the same 1.0-to-2.0 range, and that
is wrong in both directions: a chest cart is a heavy box before it holds anything and its 27 slots
hold a great deal, while a hopper cart's five slots cannot hold much at all. So each kind carries its
own pair of endpoints instead:

| Cart | Empty | Full |
|---|---|---|
| Chest | 1.2 | 3.0 |
| Hopper | 1.0 | 1.5 |

That makes an empty hopper cart the same as a plain cart, which is right — a hopper is a funnel, not
cargo — and a full chest cart the heaviest single car in the mod at three times an empty one. The
comparator reading is unchanged; what changed is that it now interpolates between two figures the
cart's kind supplies rather than scaling one shared cargo mass. An unopened loot cart still reads as
full, so a loot chest cart is 3.0.

Any other container cart, a modded one included, takes the chest pair. There is nothing else to
measure it by, and a box of goods on a cart is what that pair describes.

**One item anywhere in a container cart already costs mass, because the comparator scale has no
second zero.** `Mth.lerpDiscrete(alpha, 0, 15)` is `floor(alpha × 14) + (alpha > 0 ? 1 : 0)`, so a
single stone in a 27-slot chest cart reads 1, not 0, and weighs 1.32 rather than 1.20. The empty
endpoint therefore belongs to a genuinely empty cart and to nothing else, which is the right
behaviour and worth knowing before reading a coast test: a cart that looks empty in a screenshot is
not necessarily the 1.2 row. Both endpoints were confirmed against measured coasts — see Reference.

**Three figures downstream move with this, and all three are gameplay-visible.** Coast distance is
exactly proportional to mass under the physical model, so the spread across a chest cart's fill widens
from 2:1 to 2.5:1 — see the retuned table in Reference. A full chest cart's wreck threshold and its
share of a shove both scale with the same figure, since `TrainCoupling.mass` is what they read. And on
a grade the loaded two-car train that S5's table calls *marginal* now does not climb at all; that is
recorded against S5 rather than here, because whether to retune `ENGINE_FORCE` for it is that stage's
question.

**`PUSHABLE_CARS` becomes `PUSHABLE_MASS`, still 8, and the unit is stated once rather than per
site.** Its comment claimed it was "counted in cars because the length is the part a player can see
before they try" while the code compared it against `mass`, so under the physical model three full
chest carts (9.0) refuse a shove where three empty ones (3.6) do not. That was already true at the
old 2.0 ceiling, at four cars instead of three. The mass reading is the one kept — what resists a
player is the load, not the length — and the name and comment now say so.

The sweep that went with it is the useful half: **every mass in the mod is in empty minecarts, and
that unit is now named in those words wherever one appears** — `RailForces.cartMass`, `Train.mass`
and `meanCarMass`, `TrainCoupling.mass` and `meanCarMass`, `PUSHABLE_MASS`, and
`cart_wreck_momentum`, which is a player-facing figure on the same scale and previously said "empty
carts". `EMPTY_MASS` carries the definition. Two divisors that look like the same thing and are not
were checked and left as car counts: `RailDrag.shared` averages vanilla's per-cart *factors* and
`TrainCoupling.sharedSpeed` averages *speeds*, neither of which is a mass. `slopeShare`'s count is
the one genuine remaining candidate and belongs to S7, which already plans it as
`Σ(mᵢ × gradeᵢ) / Σmᵢ`.

## S3 — Friction, as two separate laws — **built**

**Build.** The vanilla model keeps what is there: vanilla's per-cart factor, averaged over the train
by `RailDrag.shared`, and no flat term at all. The physical model owns the whole retarding law
instead — a deceleration of `(own + flat) / mass`, where `own` is the proportional loss expressed as
a rate rather than read off the cart, and the flat term is `RailDrag`'s current figure. It does not
call `shared`, having no per-car factor to average.

This is the stage that inverts vanilla's container mechanic, deliberately and only under the rule. It
is worth saying in the code comment, because a full chest cart coasting *further* than an empty one
is the opposite of what a player who knows vanilla expects, and the reason — that the load-
independent part of the resistance divides by mass — is not guessable from the behaviour.

**Done when.** With the rule off: an empty chest cart coasts about 78 blocks from 8 m/s and a full
one about 19, the vanilla ordering; a ridden cart runs about 130 and never comes to a dead stop. With
it on: empty 11.8, half 17.9, full 24.0, monotone in the mass and each one a dead stop. A rider is
worth the same as a full load, so a ridden plain cart reads 24.0 as well.

A mixed train must coast the same distance in both models when the rule is off, which is what proves
`shared` stayed. It need not match under the rule, where the whole train is one mass.

### As built

**The formula divides both terms by mass, not only the flat one, and that is a gameplay choice this
document should have stated outright.** Findings argues from the load-*independent* part of the
resistance, which is why the direction is right at all — but taken literally it licenses dividing
only the flat term, and that was measured: over a 2:1 mass range it gives 11.9 to 13.2 blocks, an
eleven percent spread nobody would notice. Dividing both gives 11.9 to 24.1, which is the 2:1 this
document's own figures and its ratio decision are written against. The code comment says which half
is physics and which is amplification.

**The divisor is the mean per-car mass, not the train's total.** Dividing by the total would have ten
empty cars coast five times as far as one, which breaks the invariant `RailDrag`'s own class comment
promises — resistance is per car and so is mass, so a long train coasts like a short one. The mean
keeps *load* mattering and *length* not: ten cars empty read 11.9 blocks and ten cars loaded 24.1.
The engine divides by the total instead, and that difference is not an inconsistency — a single
engine's output is not per car, so length is exactly what it is up against.

The proportional rate is one figure for every cart, vanilla's own empty-cart `0.025`, with water
expressed as a rate rather than a factor. That makes a lone empty cart read the same in both models
but for the flat term, which is a useful thing for a test to be able to lean on.

**The guard drops the flat term only, not the whole law.** Under power or a steering rider the
proportional part stays, because it cannot zero a speed and so cannot swallow a nudge — which is the
only thing the guard ever existed to prevent. That is also what the vanilla model already did by
accident, since its guard skipped the flat roll and left vanilla's own factor in place.

The `keepOf` loot-cart figure is fixed here as Findings asked: an unopened loot cart reads `0.98`,
vanilla's own, not a plain cart's `0.975`.

Computed against the model rather than measured: mass 1.0 coasts 11.9 blocks in 4.8 s, 1.5 reads 18.0
in 7.2 s, 2.0 reads 24.1 in 9.6 s, each to a real zero. The vanilla side reproduces vanilla's figures
exactly — 15.2 plain, 129.6 ridden, 77.6 empty chest, 19.1 full chest.

## S4 — Bend loss behind the model — **built**

**Build.** The simplest of the gated three: a pure departure with no repair inside it and, unlike
everything else here, **no mass term**. `RailDrag.bend` takes its fraction from
`RailForces.bendLoss` and returns without touching movement under `VANILLA`.

Mass stays out on purpose. A kink is an impact, and what an impact costs is the sideways component of
the speed that arrived at it — `1 - cos θ` of it, a fraction, with the mass cancelling on both sides.
So a loaded train loses exactly the same proportion at a corner as an empty one, and the temptation to
divide by mass here for consistency with the rest of the stage list would be wrong.

`TrainPlacer` goes on measuring the turn and `Train.setTurn` goes on recording it either way, so the
meter keeps reporting `turn=` in both models — it is diagnostics about the track, not about the force.

**Done when.** With the rule off, a train crosses a 90° corner with no speed change beyond friction,
while the log still shows `turn=1.00` for it. With it on, 2% per right angle, 8% a lap on the
four-corner circuit — and the same 2% whether the cars are full or empty.

### As built

As written, and the smallest stage here. `RailDrag.bend` asks `RailForces.bendLoss` for the fraction
and returns without touching movement when it is zero; the placer goes on measuring the turn and the
meter goes on reporting it under both models, since that is diagnostics about the track rather than
about the force.

## S5 — The engine — **built**

**Build.** The three engine sites together, since they are one question — how hard an engine pushes
and how fast it may go — and splitting them would leave a stage where a furnace cart has vanilla's
ceiling and this mod's impulse, which is neither model.

`Train.drive()` divides the train's summed output by `TrainCoupling.mass`, in both models — which is
the car count under `VANILLA`, since every cart weighs 1.0 there, and the real total under
`PHYSICAL`. **`FREE_CARS` is deleted**, not gated: it was standing in for the mass effect, and mass
now produces the curve directly.

**The engine force has to be re-derived, because dividing by mass changes what it has to beat.** At
the current `2 × SLOPE_PULL`, a three-car empty train gets 0.67 of gravity and cannot climb a grade at
all, which would fail a "Done when" S7 of `train-ribbon.md` already closed. The bar for climbing is
the slope pull itself, 0.0078125, so the force follows from the two ends of the intended curve:

| Train | Mass | At `6 × SLOPE_PULL` | Climbs? |
|---|---|---|---|
| Lone furnace cart | 1.5 | 4.00 × gravity | yes, easily |
| Furnace + 2 empty | 3.5 | 1.71 × gravity | yes |
| Furnace + 2 full | 5.5 | 1.09 × gravity | marginal |
| Furnace + 5 empty | 6.5 | 0.92 × gravity | no |
| Furnace + 10 empty | 11.5 | 0.52 × gravity | no |
| Two furnaces + 8 empty | 11.0 | 1.09 × gravity | marginal |

`6 × SLOPE_PULL` is proposed on that basis rather than measured, and the observable target is the
middle rows: a short train climbs, *loading* it to nearly the same mass is what tips it to marginal,
and a long one cannot at all. The last row is the double-heading case and the reason the furnace
cart's own 1.5 matters — eleven tonnes behind two engines climbs where ten and a half behind one does
not. Retune the constant against those observations, not against the multiplier.

`MinecartFurnaceMixin$accelerate` restores vanilla's branch under `VANILLA` —
`deltaMovement.multiply(0.8, 0.0, 0.8).add(push)` handed to `super.applyNaturalSlowdown`, vanilla's
own line at `MinecartFurnace.java:89` — and keeps the flat impulse under `PHYSICAL`.
`MinecartFurnaceMixin$furnaceSpeed` stops firing under `VANILLA`, leaving the halved ceiling.

The impulse projection stays unconditional in both, against vanilla, for the reason in Findings.

**Done when.** With the rule off: a lit furnace cart tops out at 4 m/s — half of `max_minecart_speed`
— reaches it within a second or two of being fed, and climbs any grade regardless of how many cars it
tows, which is the vanilla engine and far stronger than this mod's. With it on: 8 m/s, and the table
above observed on a grade, including that loading a three-car train is what tips it from climbing to
marginal.

Both models must start from rest on a corner, and neither may rotate a cart on its rails when a
furnace is fed into it.

### As built

`6 × SLOPE_PULL` stands, and `Train.drive` is now the summed output over `TrainCoupling.mass` with
`FREE_CARS` deleted. The grade table above is reproduced exactly by the code as written, so the
figures in it are the ones to retune against.

**The enum method came out as a predicate, `drivesEngine()`, not the `engineImpulse` value this
document specified.** An enum cannot reach `super`, and the site is an `@Inject` that can simply
decline to cancel and leave vanilla's whole powered branch to run — which is strictly less code and
strictly less drift than reconstructing what that branch would have returned. Declining also has to
happen *before* this mod re-aims the impulse, since vanilla's branch re-aims it itself inside
`calculateNewPushAlong` (`MinecartFurnace.java:100-106`); so under the vanilla model the aiming is
declined along with the force, which hands back vanilla's own fault of driving a shoved cart home
again. That is the price of being vanilla, and the impulse fix stays where this document put it, on
the stationary case vanilla never projects at all.

**A seventh method, `engineOutput()`, and the vanilla model has to answer it.** A train whose leading
car is not itself an engine has to be given the drive somewhere, and vanilla — having no trains —
states no figure for that. So the vanilla model synthesises one: `1.0` a tick, which is what vanilla's
impulse amounts to once this mod has normalised `push`. Vanilla's own magnitude is how far the player
was standing from the cart, which is arbitrary and lands past the ceiling for any plausible value, so
a normalised one is observably the same engine.

**A lone lit furnace cart divides by its own 1.5 too.** Without that the one cart carrying a firebox
got the only free ride, and the table's `4.00 × gravity` for a lone engine already assumed it did
not. One formula either way: force over mass.

**The grade table's middle row moved when the container masses were retuned, and the constant was
left alone.** At a full chest cart's 3.0 the loaded pair the table calls *marginal* comes to 7.5 and
gets 0.80 × gravity, so it does not climb:

| Train | Mass | Output | Ratio | Climbs? |
|---|---|---|---|---|
| Furnace + 2 empty chest | 3.9 | `6 ×` | 1.54 | yes |
| Furnace + 3 empty chest | 5.1 | `6 ×` | 1.18 | yes |
| Furnace + 1 full chest | 4.5 | `6 ×` | 1.33 | yes |
| Furnace + 2 full chest | 7.5 | `6 ×` | 0.80 | **no** |
| Two furnaces + 2 full chest | 9.0 | `12 ×` | 1.33 | yes |
| Furnace + 4 empty hopper | 5.5 | `6 ×` | 1.09 | marginal |

`6 × SLOPE_PULL` still stands, because what replaced the marginal row is a better statement of the
same idea: a pair of loaded chest carts needs a second engine, which is double-heading earning its
place on a two-car train rather than only on an eleven-tonne one. The observable target shifts from
"loading tips it to marginal" to "loading a short train stops it, and a second engine recovers it".
If the softer version is wanted back, the lever is `ENGINE_FORCE` — `8 × SLOPE_PULL` puts the loaded
pair at 1.07 — and not the chest cart's 3.0, which is the figure the whole load effect rests on.

**The ceiling is shared across the train, which S5 had not planned for.** See Findings — restoring
the halving made the ceiling depend on which end leads, and the repair is that the driven car takes
the lowest figure in its train. It recurses unless the walk is refused by every car that is not the
driven one, which is what `TrainCoupling.trainCeiling` does: no reentrancy flag, no static, and the
old `ownSpeedOnly` guard the ribbon redo deleted stays deleted. `AbstractMinecartExt` gains
`minecarttweaks$ownMaxSpeed` so the driven car can ask its neighbours without reaching for a
`protected` method on somebody else's instance.

## S6 — Wrecking behind the model — **built**

**Build.** One predicate. `CartInteraction.wrecks` returns false under `VANILLA`, and `of` already
falls through to `COLLIDE` for a cart pair that does not wreck, which is vanilla's own solid
collision — vanilla's `pushOtherMinecart` is left alone by this mod already, so nothing has to be
rebuilt to get the bounce back. `CartImpact.strikeNearby`'s `WRECK` branch then becomes unreachable
under the default rather than needing a check of its own.

`cart_wreck_momentum` becomes a rule that does nothing unless `realistic_physics` is on. Say so in
its javadoc and in the README row, since a rule that silently does nothing is worse than a rule that
is missing.

Under the rule, wrecking is also the last place in the mod that says "mass" and means "cars":
`CartInteraction.wrecks` builds a reduced mass `mₐ·m_b/(mₐ+m_b)` from car counts. Repoint it at
`TrainCoupling.mass` so a loaded train writes off a light one at a lower closing speed than an empty
train of the same length does, which is the whole point of having mass at all. `CartImpact`'s own
impact falloff needs the same repointing and is listed in S7.

**Done when.** With the rule off: two trains driven into each other head-on at full speed come to a
stop or shunt each other along, with no cart destroyed and nothing dropped, at any closing speed the
track can produce. With it on: the same collision writes both off as it does today, and a train of
loaded cars wrecks at a lower closing speed than the same train empty.

A train coming apart must still never destroy its own cars in either model — that is
`CartInteraction`'s existing `sameTrain`/`coupled` branch and it sits ahead of `wrecks`, so it should
be unaffected; worth confirming rather than assuming, since it is the failure that would be worst.

### As built

One predicate, as planned. The reduced-mass repointing this stage was to do turned out to be already
done: `closingMomentum` builds its `mₐ·m_b/(mₐ+m_b)` out of `TrainCoupling.mass`, which S2 gave a
real figure, so a loaded train already wrecks a light one at a lower closing speed with nothing
further changed here. `cart_wreck_momentum`'s javadoc now says the rule does nothing unless the
model is on.

S7's one line of real work came along with it, being the same question: `CartImpact.slowTrain`
divided its falloff by `Train.of(cart).cars().size()` and now divides by `TrainCoupling.mass`, which
is the same figure under the vanilla model and lets a loaded train shrug off an impact a light train
of the same length would feel.

## S7 — Gravity, and the repairs reviewed — **built**

**Build.** `TrainCoupling.slopeShare` sums the cars' grades over their number, and becomes
`Σ(mᵢ × gradeᵢ) / Σmᵢ` — which is the same expression it computes today whenever every car weighs
1.0, so it needs no model check and the vanilla model keeps vanilla's behaviour of pulling every cart
alike whatever it holds. Under the rule it only ever moves a train loaded unevenly across a crest,
where the loaded end is now the one that decides which way the train rolls.

The rest is expected to be a no-op in code and a pass in comments: confirm that `hauled`, the shove's
`1 / mass`, the impact's falloff and the brake's choice of the driven car are each the ensemble answer
rather than a departure, and say so where each one lives. The shove already divides by
`TrainCoupling.mass`, so S2 changes what it means for free — a loaded train is harder to shove under
the rule and exactly as shoveable as vanilla without it, which is the clearest single check that
`cartMass` is carrying the gate properly. `CartImpact` computes its own falloff from
`Train.size()` and has to be repointed at `mass` to follow, which is the one line of real work here.

`wreckMomentum`'s own car counts are S6's, not this stage's, and `CartImpact`'s falloff landed there
too — both were the same question and S6 answered it.

**~~`shovable`'s ceiling is the one comment this stage has to settle rather than confirm.~~ Settled
with S2's retune: it is a mass.** `PUSHABLE_CARS` is now `PUSHABLE_MASS`, the comparison against
`mass` is unchanged, and the comment says what the code does — what resists a player leaning on a
train is its load, not its visible length. So this stage inherits the shove ceiling already correct
and only has `slopeShare` left to change.

**Done when.** A player shoving a parked three-car train moves it the same distance with the rule on
and off while it is empty, and visibly less far once the cars are loaded. A train whose leader alone
is on a slope rolls downhill no faster than a single cart in either model. A plain cart leading a
furnace train moves in either model. A train loaded at one end and empty at the other crests a hill
differently under the rule than without it, which is the only thing the mass weighting changes.

### As built

`slopeShare` did not gain a weighting; it was replaced. `TrainCoupling.slopePull` **computes** the
train's pull instead of rescaling vanilla's, because a rescale of zero is zero and a leading car on
the level is exactly where vanilla hands back zero — the Findings entry above has the measurement.
The weighting this stage planned is in it unchanged, `Σ(mᵢ × gradeᵢ) / Σmᵢ`, and is still the whole
of what the model gate touches here: under `VANILLA` every car weighs one, so the sum is the plain
count mean it was before mass existed.

Three parts, and the sign convention is what holds them together. The **magnitude** is still
vanilla's wherever vanilla found a slope — `pull.length()`, which keeps the water factor and the
speed term for free — and is worked out from `RailDrag.SLOPE_PULL` only where vanilla found none,
which is the case that was broken. The **direction** is `RailPath.crossing(...).forward()` at the
leading car, which is an exact unit axis vector, so a pull laid along it stays on the rail through a
corner; the chord to the car behind only has to say which of the block's two ends is the front. The
**share** is summed towards the head and flipped once if the tail is the end that leads, so a
reversal costs nothing — either end may lead and the arithmetic does not change. Checked against
vanilla by hand for a train wholly on one slope: `EAST × −1 × slideSpeed` is literally vanilla's
`ASCENDING_EAST -> add(-slideSpeed, 0.0, 0.0)`.

**A grade has to be legible to a train that is not moving**, which is the second half of the fix and
was not foreseen. `RailPath.bend` reads its direction off the cart's own movement and answers
`Bend.NONE` below `EPSILON`, so a train stalled on a hill could not report the grade it was stalled
on — the exact state the report came from. `RailPath.grade(cart, towards)` takes the direction as an
argument instead, and it comes from the chord between neighbouring cars: geometry a stopped train
still has. The private `grade(state, out)` it shares also had to start answering **0** for a
direction across the slope rather than ±1; an exit is always on the axis so it could never trigger,
but a chord between two carts can lie anywhere.

`RailPath.bend` now has no callers. `Bend.turn` is the primitive S8 of the ribbon plan wants for bend
loss and the exit-finding in it is verified, so it stays for now rather than being deleted and
written again.

The review half came out as a pass: `slowTrain` already divides by `TrainCoupling.mass` — S6 did
that, as this stage's Build text anticipated — and `hauled`, the shove's `1 / mass` and the brake's
choice of the driven car needed no change.

**Measured**, on a rebuilt rig at z=346: flat x=300–339 at y=64, `ascending_east` x=340–347, flat
x=348–366 at y=72. Chest carts, `max_minecart_speed` 8.

| Leg | Result |
|---|---|
| 8 cars, 2 on the flat + 6 on the bank | Rolls down; spacing `worst` 0.33 → 0.05 → **0.00**, no sever |
| Same, to rest | All 8 reach the flat at a 1.5 pitch and coast ~10 blocks out |
| Leader uphill, shoved east at 0.35 | Climbs, decays 0.319 → 0.110 monotonically, stalls, **reverses**, returns to the flat |
| 4 cars wholly on the grade vs one cart, same release | Train coasts **1.45 blocks further** (10.12 vs 8.66) |
| Leader alone over the brow, 4 cars | `-0.0154` against a lone cart's `-0.0786` — about a fifth |
| Load on the grade end vs the flat end, 4 cars, both 8.40 | `-0.0512` against `-0.0173` |
| The same two under `VANILLA` | mass reads `4.00`; `-0.0212` against `-0.0188` |

The last two rows are this stage's own claim and its control. Two trains of the same length, the same
total mass and the same geometry roll at three times the difference under the rule depending only on
which end carries the load, and are the same train without it — the vanilla pair differs by 13%,
which is the sampling jitter of reading over rcon rather than a split.

The fourth row is not a contradiction of the fifth. Entering a grade a train is *slower* than a lone
cart, because fewer of its cars have weight on the slope; leaving one it is *faster*, because more of
them still do. Both are the same share read at the two ends of the traverse, and the freeze this
stage fixes was the second of them returning zero.

Not observed here: a player shoving a parked train, loaded against empty, which is the one leg of the
"Done when" that needs a player rather than a rig.

## S8 — The rule table and its drift — **built**

**Build.** `README.md`'s Configuration table is wrong in two ways already and will be wrong in a
third: `furnace_minecart_speed [20]` at `README.md:41` no longer exists, `rail_drag_speed` was never
added, and `realistic_physics` will need a row saying plainly that the default is vanilla motion, that
this is a change for existing worlds, and that trains themselves are not gated by it because vanilla
has no trains to mirror. `cart_wreck_momentum`'s existing row needs the same treatment from the other
direction: it has to say that the rule does nothing at all unless `realistic_physics` is on.

**This is the one stage that needs asking first.** README edits are not made unprompted here, so the
stage is to propose the diff and apply it on approval, not to apply it.

**Done when.** The table lists every rule `MTGameRules` registers and no rule it does not, checked by
reading the two against each other rather than by memory.

### As built

Approved and applied. The two were read against each other rather than from memory, and the table was
wrong in four ways, not the three this stage predicted:

- `furnace_minecart_speed [20]` was listed and **does not exist** — it went in the 26.2 port.
- `realistic_physics`, `carts_resume_from_brakes` and `rail_drag_speed` were all missing. The last of
  them was then removed by S9 instead of documented, so the table gained two rows.
- `cart_wreck_momentum`'s stated unit was **wrong**, not merely thin: "cars times blocks per second"
  where S2 made it empty minecarts times blocks per second. A row that names the wrong unit is worse
  than a missing row, because a player can act on it.
- The same row said nothing about needing `realistic_physics`, so the rule read as live on a default
  world where it does nothing whatever it is set to.

`trains_enabled`'s existing row already said it exists only under `minecart_improvements`, which is
the one thing in the table that was more careful than it had to be.

## S9 — `rail_drag_speed` settled — **built**

**Build.** The rule is in a poor state and this work makes it worse before it makes it better. It
converts a speed into the flat roll term, and the cap `RailDrag` puts on that term now binds at every
value above zero, so 1 and 1000 are the same setting and the rule reads as on or off. After S3 it also
does nothing at all under the default model, and under the physical one it tunes only part of a law it
no longer describes — the rule's own wording is about matching *vanilla's* proportional drag, which
the physical model has replaced.

Three ways out, and the decision is which: re-derive the cap from something other than the slope pull
so the rule grades again; keep the cap and demote the rule to a boolean, or fold it into
`realistic_physics` and remove it; or leave it, documented as on-or-off. The first is the only one
that recovers the middle settings, and it means changing what the rule is read against, which is a
change to what it *means* rather than to how strong the friction is.

**Done when.** Whichever is chosen, `gamerule minecarttweaks:rail_drag_speed` at three different
values produces three different measured coasting distances, or the rule no longer takes three
values.

### As built

Removed, and the flat term folded into the physical model as `ROLL_TERM`. The rule's own arithmetic
decided it: `roll` was `min(rule × 0.00125, SLOPE_PULL × ROLL_CEILING)`, and the cap binds from
`rule ≥ 0.78125` — so every legal value except zero gave the identical figure. Measured on a lone
chest cart released at 0.35 blocks a tick along flat track:

| `rail_drag_speed` | Comes to rest | Coast |
|---|---|---|
| 0 | **never** — still rolling after 200 polls | 15.53+ |
| 1 | yes | 12.185 |
| 3 | yes | **12.185** |
| 500 | yes | **12.185** |

1, 3 and 500 rest at `326.31472026926554`, equal to the last digit. So this stage's "Done when" —
three values, three distances — was **not achievable**, and could not have been by re-deriving the
cap either: the flat term has to stay well under `SLOPE_PULL` or gravity loses on a grade and a cart
will not roll downhill at all, while the rule's natural range of 1 to 8 blocks a second maps to 16%
to 128% of it. There is no band there for an integer to grade across. Recovering the middle settings
would have meant reading the rule as a fraction of the ceiling rather than as a speed, which is a
different rule wearing the same name.

What settled it against merely demoting it to a boolean is the **off** row above. Zero leaves
vanilla's purely proportional drag, which forever halves what is left and never reaches zero — a cart
that never stops, inside the one model whose defining feature is that carts stop. That is not a
setting a world would choose; it is the behaviour `realistic_physics` exists to replace. The rule was
also already inert under the default model, since `VANILLA.retarded` goes to `RailDrag.shared` and
never reaches `roll` at all, so it was a knob that only existed inside `realistic_physics` and had one
sensible position.

`roll` went with it rather than becoming a constant function: `RailForces.of` already answers
`VANILLA` for any level that is not a `ServerLevel`, so the client guard inside it was unreachable
once the rule read was gone.

**A removed rule leaves its value behind**, and this was confirmed on the dev world rather than taken
on trust — the key was still there at `rail_drag_speed = 3` after the removal, and the boot said so:

```
[main/ERROR] (Minecraft) Failed to parse saved data for 'SavedDataType[minecraft:game_rules]':
  Unknown registry key in ResourceKey[minecraft:root / minecraft:game_rule]:
  minecarttweaks:rail_drag_speed ... missed input: {"minecarttweaks:rail_drag_speed":3}
```

Logged by `main` at level load, ten lines *before* `Starting minecraft server`, so it is not a
runtime complaint and will not appear in a tail of a busy log. Dropped with `scripts/nbt.py`, server
stopped first or the next save undoes the edit. The live world also carries a stale
`minecarttweaks:furnace_minecart_speed` from the 26.2 port, so both want dropping in one pass
whenever this deploys.

## S10 — The engine put on a power curve — **built**

**Build.** `ENGINE_FORCE` is a constant, so a lit engine pulls just as hard at seven blocks a second
as it does from a standstill, and its top speed is whatever `getMaxSpeed` truncates it to. Replace
that with a traction curve: full effort up to a base speed, then effort falling as speed rises, the
way an electric traction motor behaves once back-EMF sets in.

Two regions, which is the textbook shape:

| Region | Effort | Called |
|---|---|---|
| `v ≤ vBase` | `ENGINE_FORCE` | constant-torque |
| `v > vBase` | `ENGINE_FORCE × vBase / v` | constant-power |

The flat region is not a convenience. Starting effort is what a heavy train needs and what S5's
grade table rests on, so it must not fall; and it is what keeps the falling branch from ever dividing
by a speed near zero, which makes `vBase` a guard before it is a tuning knob. Above it, effort
falling as `1/v` holds the product — the power — at whatever rating the flat region ends on.

Proposed figures, and the third column is the point of the stage — the emergent top speed of a train
under power on level track, where effort meets `n × (0.025 v + flat)` for `n` cars:

| Cars | Today, flat effort | Flat then `1/v` | Linear falloff |
|---|---|---|---|
| 1 | 8.00, the clamp (36.7 free) | 7.60 | 7.60 |
| 2 | 8.00, the clamp (18.0 free) | 5.27 | 6.16 |
| 3 | 8.00, the clamp (11.7 free) | 4.23 | 5.15 |
| 6 | 5.47 | 2.89 | 3.34 |
| 10 | 2.97 | 2.16 | 2.15 |
| 20 | 1.09 | 1.09 | 0.92 |

`vBase = 1.70 m/s` is derived, not picked: it is the base speed at which a lone engine's own top
speed lands at 7.60 m/s, just under the default ceiling, so the curve is what limits a furnace cart
and the clamp is only a backstop. `ENGINE_FORCE` itself does not move — the flat region *is* the
current figure, which is why the grade table survives this stage untouched.

**The payoff is that `max_minecart_speed` stops being a furnace train's top speed.** Today every
train of three cars or fewer pins the clamp, so the only thing separating them is how quickly they
get there; under the curve they settle at 7.6, 5.3 and 4.2, and length sets speed continuously
instead of in one step at six cars. That also finishes the raised-speed-rule decision already
settled above: the physical engine becomes not merely gradual but genuinely indifferent to the rule,
where vanilla's saturates whatever it is given.

**Three things this must not break, each of which it would break if written the obvious way.**

*`driveAcceleration() > 0` is a lit-or-not predicate at four sites and must stop carrying the
output.* `TrainCoupling.hauled` asks it to find out whether a car is its own engine, `underPower`
asks it to decide whether the flat rolling term applies, `driveHeading` asks it whether there is a
heading to read, and `Train.drive() > 0` asks it of a whole train. A curve that can reach or approach
zero silently answers "not powered" for a lit engine at speed — and `underPower` gating the flat term
means the train would then have rolling resistance reapplied, slow down, regain effort and oscillate.
So the effort and the fact of being lit have to become two separate questions before the curve lands,
not after.

*The grade table is preserved only because the flat region is.* A train that meets a grade at speed
is on the falling branch and will slow until it re-enters the flat one, which is the behaviour worth
having — a heavy train losing speed on a bank and holding a lower one — but it means the climb test
has to be read as "does it settle" rather than "does it keep its speed".

*The stall counter is unaffected and should be confirmed so in a comment.* `minecarttweaks$countStall`
fires on a lit, unparked, motionless cart, and effort at rest is at its maximum under the curve, so
nothing about the wedge detection changes. A reader will wonder; answer it where they look.

**Shape of the change.** `RailForces.engineOutput()` takes the speed — `engineOutput(double speed)`
— and `VANILLA` ignores it, because vanilla re-adds its whole impulse every tick whatever the cart is
doing, so its synthesised `1.0` is speed-independent by construction. That keeps the property
`RailForces` exists for: a law with no answer for both models is a compile error. The speed to hand
it is `TrainCoupling.drivenSpeed(cart)`, which already reads the one car whose movement is an input.
With two engines the efforts sum, so a double-headed train gets `2 × effort(v)` and reaches a higher
top speed as well as accelerating harder — which it does not today, since two flat efforts are still
truncated by the same clamp.

**Open, and the one thing this draft does not settle: which falloff.** Flat-then-`1/v` is
recommended, being the actual traction characteristic and the one whose flat region has a physical
reason to exist. A linear `ENGINE_FORCE × (1 − v / vFree)` is the simpler alternative and needs no
base speed at all, since it is already finite at rest. The two agree at both ends of the table above
and differ across the middle, where linear holds more effort — 3.55 against 2.55 times gravity at 4
m/s for a lone engine — so the choice is about how a three-to-six car train feels, not about top
speeds. Decide it by driving both.

**Done when.** A lone lit furnace cart settles at about 7.6 m/s and not at the ceiling, and raising
`max_minecart_speed` to 100 no longer makes it faster. Trains of one, three, six and ten cars settle
at four distinct speeds matching the chosen column to within a few tenths. A train double-headed
reaches a measurably higher top speed than the same train behind one engine. Every row of S5's grade
table still reads as it does now, since the flat region is unchanged. And a lit engine held at its
top speed for a minute neither oscillates nor reports itself unpowered — the flat rolling term stays
off the whole time.

### As built

Flat-then-`1/v`, as recommended. `BASE_SPEED = 0.077` blocks a tick.

**The stage found a bug that made its own premise false: a cart under power had no retarding force at
all.** Both of the `applyNaturalSlowdown` HEAD injections — `TrainCoupling.hauled` for a train and
the furnace engine for a lone cart — end by setting the return value, which *cancels* the method. So
neither vanilla's own slowdown nor this mod's `@At("RETURN")` friction hook was ever reached, and a
lit engine accelerated against nothing. The parked branch already handled exactly this and says so in
its comment; the two powered paths never did.

Flat effort hid it completely. The free-running speed with no resistance is the speed ceiling, and
with flat effort the balance point would have sat above the default 8 m/s anyway, so a lit cart
pinned the clamp and looked right. What it actually meant is stronger than a missing term: **train
length had no effect whatever on a powered train's top speed** — every powered train, of any length
and any load, ran to whatever `max_minecart_speed` allowed. The length effect this document has been
describing since S5 existed only in how quickly a train got there.

The fix is composition rather than another hook. The furnace branch applies the retarding law itself,
as the parked branch does. `hauled` stops being a HEAD cancel and stops being `@Nullable`: it takes
the movement the retarding law has already been applied to and adds the drive to it, called from the
one RETURN hook that was already there. Resistance then drive, in that order, in both paths — and no
second `@At("RETURN")` injection whose order against the first would be undefined.

**The base speed is derived from the balance that actually obtains, which is not the one the Build
text above computes.** Under power `underPower` suppresses the flat rolling term — deliberately, and
for the reason S3 records — so effort meets only the proportional loss. Resistance divides by the
mean per-car mass and effort by the train total, so mass cancels from both sides and the balance is

`E × ENGINE_FORCE × BASE_SPEED / v = 0.025 × n × v`, and so
`v = √(E × ENGINE_FORCE × BASE_SPEED / 0.025 n)`

for `n` cars behind `E` engines. Three things fall straight out of that and are worth stating plainly:
**load does not change a powered train's top speed at all**, length divides it as `1/√n`, and engines
multiply it as `√E`. Load still decides acceleration and whether a grade can be climbed, which is
where it belongs. The Build text's figures assumed the flat term applied and so read low; the first
`BASE_SPEED` of 0.085 put a lone engine at 7.96 m/s, effectively still pinned against the clamp, and
0.077 is the value that puts it at 7.6.

Measured on a 400-block straight at `realistic_physics true`, `max_minecart_speed 8`:

| Train | Cars | Engines | Predicted | Measured |
|---|---|---|---|---|
| Lone engine | 1 | 1 | 7.60 | **7.578** |
| Single-headed | 3 | 1 | 4.39 | **4.351** |
| Double-headed | 3 | 2 | 6.21 | **6.179** |
| Single-headed | 6 | 1 | 3.10 | **3.051** |

Every row within 1.6 percent, and the double-headed three-car train runs half again as fast as the
same train behind one engine — which it could not do before, both engines' output having been
truncated by the same clamp. Raising `max_minecart_speed` from 8 to 100 left the three-car and
six-car figures unchanged to every digit, so the ceiling is now a backstop and not the answer. The
lone engine held `0.3789158434708194` blocks a tick identically across forty seconds of sampling: no
oscillation, and the flat rolling term stayed off throughout.

On a twenty-step 45° bank the grade rows read as S5's table says. A lone engine climbed the whole
ramp. A furnace and two empty cars climbed it at a steady 1.86 m/s and then accelerated away to 4.35
on the flat at the top — which is the constant-power behaviour worth having, and reads as a train
dropping to a lower speed on the bank rather than holding its own. A furnace and two full chest carts
got five blocks up and stopped, as mass 7.5 against `6 ×` gravity must.

**One measurement here is contradicted by play, and the play is the authority.** The loaded train
that failed to climb froze on its bank rather than rolling back, with the engine's `PushX` measured
flipping between `+1.0` and `-1.0` every four ticks while both movement components stayed at exactly
zero — so `minecarttweaks$countStall` was firing and reversing faster than the train could build any
movement, cancelling itself out. In play a grade stall reverses as intended, so whatever that
headless case was, it is narrower than "the counter does not work on a grade": most likely the
continuous twenty-step 45° bank it was standing on, steeper and longer than anything a player builds,
or that the rig's engine was a placed trailing car rather than the leader. Recorded rather than acted
on, because a fix aimed at a rig and not at a reproduction from play is aimed at nothing. Should it
turn up in play, the shape is a cooldown on the reversal, so that one flip gets a chance to take
effect before the next.

## S11 — The brake and the boost reach the whole train — **planned**

**Build.** Two `RETURN` injections beside S7's, on `calculateHaltTrackSpeed` and
`calculateBoostTrackSpeed`, each answering for a train and declining for an unlinked cart so the lone
case stays bit-exact.

One share serves both, `TrainCoupling.railShare`: the count of cars standing on rails that answer a
predicate, over what the whole train weighs. Counted on top and weighed underneath — see the finding
above for why weighing both sides is the same as having no mass term at all.

`TrainCoupling.boostPull` takes that share against `RailBrake.boosts` and adds `share × 0.06` to the
train's own direction of travel, which is the whole of vanilla's rolling branch: it ignores which way
the rail points and merely speeds the cart up. Its standing branch cannot, so it reads
`getRedstoneDirection` at each lit car and carries the answer up to the driven one through
`alongTrack` — two cars round a bend do not agree on a direction, and the lit rail need not be
anywhere near the car vanilla drives. The magnitude there comes from `RailForces.launch`.

`TrainCoupling.braked` is the same share against `RailBrake.brakes`, handed to `RailForces.halted`.
The dead stop under vanilla's `0.03` floor stays outside the model and all-or-nothing on any car: a
clamped shoe is a constraint, not a force. `TrainCoupling.held` is the train-aware sibling of
`RailBrake.brakes`, and `hauled`'s parking-brake check and `RailBrake.release` both move onto it, so
a brake under any car suppresses the engine and holds the train rather than only one under the head.

Two methods the models answer differently, and both are quantities vanilla states in units no mass
divides: `launch(kick, share)` for the assigned standing speed, `halted(movement, share)` for the
multiplied fraction. `PHYSICAL`'s brake is `BRAKE_FORCE = 14 × SLOPE_PULL`, sized so a lone empty
cart at the default ceiling stops in the `0.73` blocks nearest vanilla's own `0.75`.

**Done when.**

- A four-car train whose **trailing** car crosses an unpowered powered rail is stopped by it, and a
  player cannot pull the train off it.
- A train standing fully on lit powered rails accelerates at 0.06 a tick, matching a single cart.
- The same train with one car of four on a lit rail gains 0.015 a tick, and over a full crossing of
  that section reaches the speed the leader-only behaviour reaches today. **The integral is the
  check, not the instantaneous figure** — the per-tick number is meant to be a quarter.
- With `realistic_physics` off, one lit rail under a stopped four-car train launches it at 0.2; with
  it on, at 0.05, and a loaded train slower still.
- A lone cart reads identically on all four counts under both models.
- A four-car train of full chest carts accelerates at a third of an empty one's rate on the same
  booster, and sheds a third as much on the same brake. **Under the rule only** — without it a
  loaded train reads exactly as an empty one, which is what the default is for.
- A longer train still takes longer to stop under either model, and `VANILLA` is unchanged to the
  digit by the share being recounted, every car weighing one there.

## Open decisions

**~~What the rule should be called.~~ Settled in S1: `realistic_physics`.** The alternative,
`physical_forces`, was the narrower name on the argument that nothing outside friction and
acceleration is gated — and by the time S1 landed that argument had already expired, since S6 moves
wrecking inside the model. Renaming it later resets every saved value for it, so it does not get
revisited.

**~~Whether a real momentum exchange should replace the cart-against-cart handoff.~~ Settled: it
should, and it has.** Not by the argument this entry made but by a bug report — the handoff was not
merely mass-blind, it **created** momentum, because the struck cart took the striker's whole movement
and the striker was billed nothing for it. The Findings entry above has the measurements. So the
`Σmv` exchange this entry proposed went in as a fix rather than as a refinement, and a heavy train
now shunts a light one aside instead of handing it its own speed.

**Whether running mobs down should be gated too.** Vanilla carts deal no collision damage at all and
do not run anything over, so on the strictest reading of this document's title they belong behind the
rule with wrecking. They are left on their own rules — `minecart_damage`, `cart_impact_speed`,
`cart_pickup_speed` — on the grounds that each already has an off switch, that they are features a
world installs this mod *for* rather than consequences of a force model, and that unlike wrecking
they do not destroy anything the player built. The line drawn is "does it break the player's own
property"; if that line is wrong, this is the entry to revisit.

**~~Where the empty-to-full mass ratio lands.~~ Settled, and it is not one ratio.** Started at 2:1
across the board, and S2's retune replaced that with a range per kind of cart: a chest cart 1.2 to
3.0, so 2.5:1 and the heaviest cart at both ends; a hopper cart 1.0 to 1.5, a funnel rather than
cargo. The single figure this entry looked for was the wrong shape — what a cart weighs empty is as
much a property of the kind as what it holds is of the load, and a hopper with five slots cannot
reach a chest's spread whatever ratio is chosen. Coast distance is exactly proportional to mass under
the physical model, so the chest figures are also the widest spread the coast legs can show: about
11.9 blocks of coasting per unit of mass from eight blocks a second.

**~~Whether the default model should also drop the ribbon.~~ Settled: no, and it is now said out
loud.** Out of scope as written — the rule gates forces and the placer is geometry — but a reader
will ask, because the placer is the largest behavioural difference of all and nothing gates it. S8
put the answer in `realistic_physics`'s own README row rather than leaving it only here: trains are
not gated by the rule, because vanilla has no trains to mirror.

**~~Whether the vanilla model should soften its engine at a raised `max_minecart_speed`.~~ Settled:
it should not. The physical model is the answer.** A vanilla world with `max_minecart_speed = 100`
has furnace carts that reach 50 m/s from standing inside a fifth of a second, and reproducing that
is the default model doing its job. Softening it would be a deviation in the one model that exists
in order not to deviate, and it would leave neither model honest.

So the brittleness at a raised cap is a reason to turn `realistic_physics` **on**, not a defect to
paper over — the physical model's engine is a fixed force, so it accelerates gradually at any
ceiling and does not care what the rule is set to. That is worth saying in the README row as a
positive reason to enable the rule rather than as a caveat about the default, and it is the clearest
practical answer to "why would I want this?" that this work has produced.

**Whether `PHYSICAL` should stay the tested path.** Every figure in `train-ribbon.md` was observed
with what becomes the opt-in model, and after this work the default is the one almost every world
runs and the one no stage of that document ever tested. Either the default gets its own pass over
the standing test track, or this document ends with the better-tested half switched off.

**~~Whether a lone cart should feel the physical model's rail forces.~~ Settled: it should, and it
does.** The first cut declined for an unlinked cart, which left a lone loaded chest cart coasting
three times as far as an empty one — mass reaches its drag, its engine and its gravity — while
accelerating and braking precisely like it, with a step at the moment of coupling rather than a
gradient: one loaded chest cart gained `0.06` a tick on a booster and the pair it became gained
`0.02`. A cart coupled to nothing is now a train of one, so the same share answers for it.

It cost the default nothing, which is the part worth recording: under `VANILLA` every cart weighs
one, so a lone cart's share comes to exactly one and every law hands back the figure vanilla
computed. Measured after the change — a lone empty cart on a lit rail left `0.100` at `0.1575`,
which is vanilla's `0.975` slowdown and its `0.06`; a full chest cart at `0.158`, which is vanilla's
own container figure of `0.98`; and both halved to `0.195` and `0.196` on a brake. Bit-exact, with
load doing nothing at all, which is what the default is for.

## Reference: the two models, computed

Vanilla's per-tick multipliers, from the source: `0.975` for a plain cart and `0.997` ridden
(`NewMinecartBehavior.java:500-501`); `0.98 + (15 - comparator) × 0.001` for a container cart, which
returns directly without calling super so an empty chest cart keeps `0.995`
(`AbstractMinecartContainer.java:101-112`); `× 0.95` again in water
(`AbstractMinecart.java:481-483`). Top speed is `max_minecart_speed / 20`
(`NewMinecartBehavior.java:485-486`), default 8, halved again for a furnace cart
(`MinecartFurnace.java:71`). Gravity on a slope is `max(0.0078125, |v| × 0.02)` once a tick.

Blocks coasted from 8 m/s until the cart reads as stopped, at 0.2 m/s:

| Cart | `VANILLA` | `PHYSICAL` |
|---|---|---|
| Plain, empty | 15.2 | 11.8 |
| Plain, ridden | 129.6 | 46.1 |
| Chest, empty | 77.6 | 36.1 |
| Chest, full | 19.1 | 14.2 |
| Furnace, unlit | 8.4 | 7.1 |

Vanilla never reaches zero, so those are the distances to a speed that looks stopped; the true
geometric totals are 16.0, 133.3, 80.0, 20.0 and 9.0 blocks with an infinite crawling tail.
`PHYSICAL` stops dead, in 1.6 seconds from 1 m/s for a plain cart and 2.4 for a ridden one against
vanilla's 3.2 and 26.8 to merely look stopped.

Those `PHYSICAL` figures are the model *before* S3 makes it mass-aware, and they are what the table
above was computed from. After S3 the column is replaced by one ordered on mass instead of on cart
kind, which is the whole point of the change:

| Mass | What it is | `PHYSICAL` after S3 | `VANILLA` |
|---|---|---|---|
| 1.0 | a plain cart, or an empty hopper cart | 11.9 blocks | 15.2 plain / 77.6 hopper |
| 1.2 | an empty chest cart | 14.3 | 77.6 |
| 1.5 | a full hopper cart, or an unlit furnace | 18.0 | 19.1 hopper / 8.4 furnace |
| 2.0 | a plain cart with a rider | 24.0 | 129.6 |
| 2.2 | a half-loaded chest cart | 26.0 | 30.0 |
| 3.0 | a full chest cart | 36.3 | 19.1 |

Every `PHYSICAL` figure above bar the rider row is **measured** on a 145-block straight at
`realistic_physics true`, from `Motion:[0.4d,0,0]` to a dead stop. Coast distance is proportional to
mass — the closed form is `(m / 0.025) × (v₀ − a·ln((v₀ + a) / (v₁ + a)))` with `a` the flat term over
the rate, and `m` factors straight out — and the measurements hold that to two percent, drifting from
11.87 blocks per unit mass at 1.0 to 12.10 at 3.0 as the last partial tick rounds up over a longer
run. So any mass not listed is about twelve blocks times it.

The same five carts at `realistic_physics false` read 70.2, 70.2, 19.6, 30.3 and 19.6 blocks at a
46-second cutoff, still crawling — the empty pair and the full pair **bit-identical to each other**,
which is the invariant worth keeping: under the vanilla model neither mass nor cart kind exists
beyond vanilla's own container rule, and a hopper cart and a chest cart holding the same fraction are
the same cart. The two models order the five almost exactly backwards: vanilla's empty carts coast
3.6 times as far as its full ones, the physical model's full ones 2.5 times as far as its empty.

The vanilla column is not monotone in mass and cannot be made so: a *ridden* cart is vanilla's
longest coast at mass 2.0 and a *full* chest cart is nearly its shortest at 3.0, while an *empty*
chest cart at 1.2 is vanilla's second longest. The two models order the same five carts almost
exactly backwards, and that is the clearest single statement of what they are for.

Terminal speed rolling downhill, with the ceiling applied:

| Cart | Mass | `VANILLA` | `PHYSICAL` |
|---|---|---|---|
| Plain, empty | 1.0 | 6.09 m/s | 5.31 |
| Chest, empty | 1.2 | 8.00, clamped | 6.52 |
| Plain, ridden | 2.0 | 8.00, clamped | 8.00, clamped |
| Chest, full | 3.0 | 7.66 | 8.00, clamped |
| Furnace, unlit | 1.5 | 3.35 | 8.00, clamped |

**Above mass 1.375 the physical model has no terminal speed on a grade at all** — it runs away to the
ceiling. Gravity's pull is `max(SLOPE_PULL, |v| × 0.02)` and mass-independent, while the retarding
rate is `0.025 / m`, so once `m > 1.25` the growing branch of the pull outruns drag outright, and the
constant branch stops binding first at 1.375. Everything at or above a full hopper cart therefore
reaches 8 m/s downhill and stays there, and only a plain cart and an empty chest cart find a speed of
their own. That is a consequence of dividing the *proportional* term by mass as well as the flat one
— the amplification S3's As built owns up to — and it means a loaded train on a downgrade is
speed-limited rather than friction-limited, which a player will read as needing brakes.

The unlit furnace cart is the sharpest reversal in the table: 3.35 m/s in vanilla, where its ceiling
is halved and its mass is not counted, against the clamp under the rule, where the halving is undone
and its 1.5 puts it past the runaway threshold.

The lit furnace cart is the widest gap of anything here. `VANILLA` reaches `4.43 × |push|` and so
its ceiling of `0.5 × max_minecart_speed` — 4 m/s at the default 8, and 3 m/s in water — almost at
once, from an impulse of roughly one block per tick, a force over a hundred times gravity. That is
why vanilla furnace carts climb anything and why length can never matter to them. `PHYSICAL` cancels
the halving for a ceiling of 8 m/s and accelerates at a proposed `6 × 0.0078125` divided by the
train's mass, so it is twice as fast at the top and tens of times weaker on the way there — and that
weakness, divided by mass, is the whole of what makes a train's length and load matter.

## Reference: the gated sites

| File | Line | What it does |
|---|---|---|
| `mixin/AbstractMinecartMixin.java` | 286 | `applyNaturalSlowdown` HEAD → `TrainCoupling.hauled` |
| `mixin/AbstractMinecartMixin.java` | 297 | RETURN → `RailDrag.slowed` |
| `mixin/AbstractMinecartContainerMixin.java` | 28, 40 | the same two, for carts whose override skips super |
| `mixin/NewMinecartBehaviorMixin.java` | 36 | `calculateSlopeSpeed` RETURN → `TrainCoupling.slopePull` |
| `mixin/MinecartFurnaceMixin.java` | 113 | `getMaxSpeed` RETURN, cancelling vanilla's halving |
| `mixin/MinecartFurnaceMixin.java` | 122 | `applyNaturalSlowdown` HEAD, replacing vanilla's engine |
| `rail/RailDrag.java` | 220 | the per-kink charge, called from `TrainPlacer` |
| `mixin/EntityMixin.java` | 85 | a shove divided by the train's mass |
| `collision/CartImpact.java` | 193 | impact drag applied once |
| `rail/RailBrake.java` | 115 | the brake launching the driven car |
| `collision/CartInteraction.java` | 184 | whether a cart pair wrecks or merely collides |
| `collision/CartImpact.java` | 97 | the wreck itself, unreachable once the predicate is false |

Not in scope, though each touches movement: the junction and switch steering, which is geometry; the
brake's own rule `carts_resume_from_brakes`; and the mob-collision rules `minecart_damage`,
`cart_impact_speed` and `cart_pickup_speed`, which gate features vanilla has no counterpart for and
which keep their own switches — see Open decisions for where that line is drawn.
`cart_wreck_momentum` is the one collision rule that moves inside the model, in S6.
