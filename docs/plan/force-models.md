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
consequence of force this mod lets destroy a player's property. S7 reviews the repairs — reduced to
a review, since the one line of code it held landed in S6. S8 documents what landed and is last. S9
is a loose end this work exposes rather than creates, and S3 made it live: `rail_drag_speed` now
reaches nothing at all under the default model.

---

## Findings that reshape the work

**"Exactly vanilla" is only well defined for a single unlinked cart.** Vanilla has no trains, so
there is no vanilla behaviour for a train to mirror. The target has to be stated twice: for an
unlinked cart, *bit-exact* — every hook returns what vanilla computed, untouched; for a train, the
**ensemble** those same carts would form in vanilla — what you would see if the links were replaced
by a player pushing the cars along in formation.

**So the divergences split in two, and only half of them can be gated.** The placer leaves one car
running vanilla's pipeline and places the rest, which means several of this mod's scalings exist to
*reconstruct* the ensemble rather than to depart from it. `TrainCoupling.slopeShare` is the clearest:
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
| `TrainCoupling.slopeShare` — gravity from the cars on the grade | repair, **weighted per model** |
| `RailDrag.slowed`'s retarding law | **departure** |
| `RailDrag.bend` — the per-kink charge | **departure** |
| `Train.drive`'s divisor | **departure** |
| `MinecartFurnaceMixin:122` — flat impulse, no decay, no friction | **departure** |
| `MinecartFurnaceMixin:113` — vanilla's halved ceiling undone | **departure** |

**Some sites are a repair and a departure in the same expression**, which is why this is not a
mechanical job of wrapping hooks in an `if`. `Train.drive()` divides the train's output by
`cars.size() - FREE_CARS * engines`; the ensemble-vanilla divisor is `cars.size()`, so the *divisor*
is what the model picks, not whether the method runs. `TrainCoupling.slopeShare` is the same shape,
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
the car count when every car weighs one. `slopeShare`'s weighting is the same identity:
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

**Three figures downstream move with this, and all three are gameplay-visible.** Coast distance is
exactly proportional to mass under the physical model, so the spread across a chest cart's fill widens
from 2:1 to 2.5:1 — see the retuned table in Reference. A full chest cart's wreck threshold and its
share of a shove both scale with the same figure, since `TrainCoupling.mass` is what they read. And on
a grade the loaded two-car train that S5's table calls *marginal* now does not climb at all; that is
recorded against S5 rather than here, because whether to retune `ENGINE_FORCE` for it is that stage's
question.

**`PUSHABLE_CARS` is left at 8 and its comment is now the weaker half-truth it always was under the
rule.** It says "counted in cars because the length is the part a player can see before they try",
but it is compared against `mass`, so under the physical model three full chest carts (9.0) refuse a
shove where three empty ones (3.6) do not. That was already true at the old 2.0 ceiling, at four cars
instead of three; the new figures only make it easier to hit. Whether the shove ceiling should be a
car count or a mass is S7's to settle, and it is listed there.

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

## S7 — Gravity, and the repairs reviewed — **planned**

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

**`shovable`'s ceiling is the one comment this stage has to settle rather than confirm.**
`PUSHABLE_CARS = 8` is compared against `mass`, and its comment claims the figure is "counted in cars
because the length is the part a player can see before they try" — which stopped being true the
moment mass did. With the retuned container figures it takes three full chest carts (9.0) to refuse a
shove and eight empty plain ones, so the same visible train length answers differently depending on
what is in it. Either is defensible; what is not defensible is a comment describing one while the
code does the other. Decide between a car count (compare `Train.size()`, and the rule changes nothing)
and a mass ceiling (keep the comparison, rewrite the comment and rename the constant), then say which
and why.

**Done when.** A player shoving a parked three-car train moves it the same distance with the rule on
and off while it is empty, and visibly less far once the cars are loaded. A train whose leader alone
is on a slope rolls downhill no faster than a single cart in either model. A plain cart leading a
furnace train moves in either model. A train loaded at one end and empty at the other crests a hill
differently under the rule than without it, which is the only thing the mass weighting changes.

## S8 — The rule table and its drift — **planned**

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

## S9 — `rail_drag_speed` settled — **planned**

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

## Open decisions

**~~What the rule should be called.~~ Settled in S1: `realistic_physics`.** The alternative,
`physical_forces`, was the narrower name on the argument that nothing outside friction and
acceleration is gated — and by the time S1 landed that argument had already expired, since S6 moves
wrecking inside the model. Renaming it later resets every saved value for it, so it does not get
revisited.

**Whether a real momentum exchange should replace the cart-against-cart handoff.** Both the mod's
line (`CartImpact.java:104`, the struck cart takes the striker's whole movement) and vanilla's own
exchange (`AbstractMinecart.java:568-573`, both damped to `0.2` and pushed by the mean) are
mass-blind, so a ten-car train meeting one loose cart is stopped as dead as a single cart would be.
Once `cartMass` exists, conserving `Σmv` across the pair is a few lines and would make a heavy train
shunt a light one aside instead of bouncing off it — which is what a player expects and neither model
currently does. Deliberately not folded into S6, which is only about whether carts are destroyed.
Settled by whether S6's "two trains shunt each other" leg reads acceptably without it.

**Whether running mobs down should be gated too.** Vanilla carts deal no collision damage at all and
do not run anything over, so on the strictest reading of this document's title they belong behind the
rule with wrecking. They are left on their own rules — `minecart_damage`, `cart_impact_speed`,
`cart_pickup_speed` — on the grounds that each already has an off switch, that they are features a
world installs this mod *for* rather than consequences of a force model, and that unlike wrecking
they do not destroy anything the player built. The line drawn is "does it break the player's own
property"; if that line is wrong, this is the entry to revisit.

**Where the empty-to-full mass ratio lands.** Starting at 2:1, which is what S2 and every figure
downstream of it are written against, and to be adjusted from there against observation rather than
argument. It is the one number that sets how far apart the two ends of every result in S3 and S5 sit:
at 2:1 a full chest cart coasts twice an empty one and loading a three-car train is what tips it from
climbing a grade to marginal. 3:1 would make loading the dominant consideration in running a railway
and 1.5:1 would make it barely worth noticing, so the range worth trying is narrow. Settled by
running S3's coast legs and S5's grade legs at the starting ratio first.

**Whether the default model should also drop the ribbon.** Out of scope as written: the rule gates
forces, and the placer is geometry. But a reader will ask, because the placer is the largest
behavioural difference of all and it is not gated by anything. The answer — that a train is this
mod's own feature and has no vanilla behaviour to mirror — is worth stating in the README row rather
than only here.

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
| 1.0 | a plain cart, or an empty hopper cart | 11.8 blocks | 15.2 plain / 77.6 hopper |
| 1.2 | an empty chest cart | 14.2 | 77.6 |
| 1.5 | a full hopper cart, or an unlit furnace | 17.7 | 19.1 hopper / 8.4 furnace |
| 2.0 | a plain cart with a rider | 23.6 | 129.6 |
| 2.0 | a half-loaded chest cart | 23.6 | 29.8 |
| 3.0 | a full chest cart | 35.4 | 19.1 |

Coast distance is exactly proportional to mass under the physical model — the closed form is
`(m / 0.025) × (v₀ − a·ln((v₀ + a) / (v₁ + a)))` with `a` the flat term over the rate, and `m` factors
straight out — so any mass not listed is 11.8 blocks times it.

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
| `mixin/NewMinecartBehaviorMixin.java` | 37 | `calculateSlopeSpeed` RETURN → `TrainCoupling.slopeShare` |
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
