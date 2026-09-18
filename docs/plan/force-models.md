# Force models

Two models for friction and acceleration, chosen by a game rule. The default reproduces vanilla, so
a world that installs this mod for its links, chains, junctions and switches gets vanilla's own
motion; `realistic_physics` opts into the force model this mod has grown instead.

Claims about vanilla are checked against `libs/decompiledjava/minecraft-26.2/` and cited by line;
claims about this mod are checked against the tree at `5f6aa3b`. The figures in Reference are
computed from those verified force laws, not measured in-world — the model behind them reproduces
the three numbers `train-ribbon.md` recorded from observation (4.53, 5.09 and 6.09 blocks per second
downhill), which is why they are quoted, but they are arithmetic and a stage is not closed by them.

Stage order. **S1 names both models and adds the rule, and S2 defines cart mass**; everything after
depends on both, since the physical model divides almost everything by mass. S3 (friction), S4 (bend
loss) and S5 (the engine) are independent of each other and can land in any order; **the feature is
complete at the end of S5**, since those three cover every gated force. S6 reviews the repairs and
settles the one of them that turns out to be model-dependent. S7 documents what landed and is last.
S8 is a loose end this work exposes rather than creates.

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

**The impulse projection stays unconditional in both models, and is the one deliberate deviation
from vanilla in the default.** Vanilla projects the impulse onto the direction of travel in
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

## S1 — Both models named, the rule added, nothing gated — **planned**

**Build.** Add `MTGameRules.REALISTIC_PHYSICS`, a boolean defaulting to **false**. Add
`rail/RailForces`, an enum of two constants — `VANILLA` and `PHYSICAL` — with one abstract method per
gated decision and a `static RailForces of(Level)` that reads the rule. The methods, from the
inventory above:

| Method | `VANILLA` | `PHYSICAL` |
|---|---|---|
| `cartMass(cart)` | `1.0`, always | the real figure from S2 |
| `decelerationOf(cart, movement)` | vanilla's own factor, train-averaged | `(own + flat) / mass` |
| `bendLoss(turn)` | `0.0` | `BEND_LOSS * turn` |
| `engineImpulse(cart, movement)` | vanilla's `0.8`-decay branch | the flat engine force |
| `topSpeed(halved)` | `halved` — the mixin does not fire | `super.getMaxSpeed(level)` |

Five methods, not the six an earlier draft of this document had: `cartMass` at a uniform 1.0 makes
the drive divisor and the slope weighting count-weighted without either needing a model of its own,
as Findings sets out.

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

## S2 — Cart mass, the quantity — **planned**

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

## S3 — Friction, as two separate laws — **planned**

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

## S4 — Bend loss behind the model — **planned**

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

## S5 — The engine — **planned**

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

## S6 — Gravity, and the repairs reviewed — **planned**

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

Also worth checking rather than assuming: `wreckMomentum` builds a reduced mass `mₐ·m_b/(mₐ+m_b)`
from car counts of its own. It belongs to the collision feature and its own rules, so it is out of
scope — but it is the last place in the mod that says "mass" and means "cars", and leaving it that way
should be a decision rather than an oversight.

**Done when.** A player shoving a parked three-car train moves it the same distance with the rule on
and off while it is empty, and visibly less far once the cars are loaded. A train whose leader alone
is on a slope rolls downhill no faster than a single cart in either model. A plain cart leading a
furnace train moves in either model. A train loaded at one end and empty at the other crests a hill
differently under the rule than without it, which is the only thing the mass weighting changes.

## S7 — The rule table and its drift — **planned**

**Build.** `README.md`'s Configuration table is wrong in two ways already and will be wrong in a
third: `furnace_minecart_speed [20]` at `README.md:41` no longer exists, `rail_drag_speed` was never
added, and `realistic_physics` will need a row saying plainly that the default is vanilla motion, that
this is a change for existing worlds, and that trains themselves are not gated by it because vanilla
has no trains to mirror.

**This is the one stage that needs asking first.** README edits are not made unprompted here, so the
stage is to propose the diff and apply it on approval, not to apply it.

**Done when.** The table lists every rule `MTGameRules` registers and no rule it does not, checked by
reading the two against each other rather than by memory.

## S8 — `rail_drag_speed` settled — **planned**

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

**What the rule should be called.** `realistic_physics` throughout this document, which fits the
mod's existing descriptive naming. `physical_forces` is the narrower and more accurate name, since
nothing outside friction and acceleration is gated, and this would sit badly if the rule later grew
to cover collision or wrecking. Settled by picking one before S1, since renaming a rule later resets
every saved value for it.

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
| 1.0 | any empty cart | 11.8 blocks | 15.2 plain / 77.6 chest |
| 1.5 | a half-loaded container | 17.9 | 19.1 to 77.6, on fill |
| 2.0 | a full container, or a rider | 24.0 | 19.1 full chest / 129.6 ridden |

The vanilla column is not monotone in mass and cannot be made so: a *ridden* cart is vanilla's
longest coast and a *full* chest cart is nearly its shortest, though both are mass 2.0. That
disagreement is the clearest single statement of what the two models are for.

Terminal speed rolling downhill, with the ceiling applied:

| Cart | `VANILLA` | `PHYSICAL` |
|---|---|---|
| Plain, empty | 6.09 m/s | 5.31 |
| Plain, ridden | 8.00, clamped | 8.00, clamped |
| Chest, empty | 8.00, clamped | 8.00, clamped |
| Chest, full | 7.66 | 6.68 |
| Furnace, unlit | 3.35 | 2.92 |

Two rows are identical because the speed ceiling binds before friction does, which is worth knowing
before reading a downhill test: only empty plain carts, loaded chest carts and unlit furnace carts
were ever slower downhill than vanilla.

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

Not in scope, though each touches movement: the junction and switch steering, which is geometry; the
brake's own rule `carts_resume_from_brakes`; and the collision rules `minecart_damage`,
`cart_impact_speed`, `cart_wreck_momentum` and `cart_pickup_speed`, which gate a feature vanilla has
no counterpart for at all.
