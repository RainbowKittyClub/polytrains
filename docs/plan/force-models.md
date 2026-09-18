# Force models

Two models for friction and acceleration, chosen by a game rule. The default reproduces vanilla, so
a world that installs this mod for its links, chains, junctions and switches gets vanilla's own
motion; `realistic_physics` opts into the force model this mod has grown instead.

Claims about vanilla are checked against `libs/decompiledjava/minecraft-26.2/` and cited by line;
claims about this mod are checked against the tree at `5f6aa3b`. The figures in Reference are
computed from those verified force laws, not measured in-world — the model behind them reproduces
the three numbers `train-ribbon.md` recorded from observation (4.53, 5.09 and 6.09 blocks per second
downhill), which is why they are quoted, but they are arithmetic and a stage is not closed by them.

Stage order. **S1 names both models and adds the rule, and everything else depends on it.** S2
(friction), S3 (bend loss) and S4 (the engine) are independent of each other and can land in any
order; **the feature is complete at the end of S4**, since those three cover every gated force. S5
is a review with no expected code change and can be done at any point. S6 documents what landed and
is last. S7 is a loose end this work exposes rather than creates.

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
| `RailDrag.shared` — friction averaged over the train | repair |
| `TrainCoupling.slopeShare` — gravity from the cars on the grade | repair |
| `TrainCoupling.hauled` — the train's drive given to a non-engine leader | repair |
| `EntityMixin:85` — a shove divided by the train's mass | repair |
| `CartImpact:193` — impact drag applied once to the driven car | repair |
| `RailBrake:115` — the brake launching the driven car | repair |
| `RailDrag.slowed`'s flat roll term | **departure** |
| `RailDrag.bend` — the per-kink charge | **departure** |
| `Train.drive`'s `FREE_CARS` divisor | **departure** |
| `MinecartFurnaceMixin:122` — flat impulse, no decay, no friction | **departure** |
| `MinecartFurnaceMixin:113` — vanilla's halved ceiling undone | **departure** |

**Two sites are a repair and a departure in the same expression**, which is why this is not a
mechanical job of wrapping hooks in an `if`. `Train.drive()` divides the train's output by
`cars.size() - FREE_CARS * engines`; the ensemble-vanilla divisor is `cars.size()`, so the *divisor*
is what the model picks, not whether the method runs. `RailDrag.slowed` folds the train mean and the
flat roll into one call, though there the two are already separate methods and only the call needs
splitting.

**Vanilla's furnace impulse is arbitrary, so it cannot be mirrored exactly — only observably.**
`MinecartFurnace.addFuel` sets `push = position().subtract(interactingPos).horizontal()`
(`MinecartFurnace.java:122`), unnormalized: the impulse is the distance from wherever the player was
standing. Terminal speed is `0.975p / 0.22 = 4.43p`, so any plausible `p` is far past the ceiling and
the observable behaviour is "reaches the halved cap at once and climbs any grade". That is the target
to reproduce; reproducing `p` itself would be reproducing a quantity vanilla does not define.

**One departure is load-bearing for a bug fix, and vanilla mode would bring the bug back.** Vanilla
projects the impulse onto the direction of travel in `calculateNewPushAlong`
(`MinecartFurnace.java:103-105`) but only once the cart is already moving — `push` unprojected on a
*stationary* cart is what rotates a cart on its rails when a furnace is fed into it, stretching the
chains across it. This mod projects unconditionally. Restoring vanilla here would restore the visual
fault, so the projection is proposed as an unconditional repair with the deviation stated. See Open
decisions.

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
| `rollLoss(AbstractMinecart)` | `0.0` | `RailDrag`'s current flat term |
| `bendLoss(double turn)` | `0.0` | `BEND_LOSS * turn` |
| `driveDivisor(int cars, int engines)` | `cars` | `cars - FREE_CARS * engines` |
| `engineImpulse(...)` | vanilla's `0.8`-decay branch | the flat `2 × SLOPE_PULL` |
| `topSpeed(double vanilla)` | `vanilla` | `vanilla / 0.5`, i.e. the halving undone |

Nothing calls it in this stage. The enum exists so that the two models are legible as a pair in one
file and a missing case is a compile error rather than a site someone forgot; the alternative is a
boolean read at each of the eight sites, which is less code and more drift, and the reason for
preferring the enum is that this refactor's whole risk is a site left ungated.

**Done when.** `rcon "gamerule minecarttweaks:realistic_physics"` answers `false`, toggling it
changes nothing observable, `devserver.sh … mixins` reports no failures, and `grep -rn
REALISTIC_PHYSICS src/main/java` finds it only in `MTGameRules` and `RailForces`.

The default is `false`, which inverts this mod's current behaviour for every existing world. That is
the point of the work, but it means a saved world quietly changes how its carts move on the first
boot after the update — worth a line in the README rather than left for someone to discover.

## S2 — Friction split into its repair and its departure — **planned**

**Build.** `RailDrag.slowed` keeps `shared()` unconditional and takes the flat roll from
`RailForces.rollLoss`. `AbstractMinecartMixin:297` and `AbstractMinecartContainerMixin:40` are
untouched — both still call `slowed`, since the train mean has to run in either model.

Under `VANILLA` the method must return `shared(cart, movement)` and nothing else, so an unlinked cart
gets its argument back unchanged. `shared` already returns early without a link, so that falls out
rather than needing a second check.

**Done when.** With the rule off, a ridden cart coasts about 130 blocks from 8 m/s and never comes to
a dead stop — it is still crawling after 25 seconds, which is the vanilla behaviour S7 of
`train-ribbon.md` removed. With it on, 46 blocks and a dead stop in 2.4 seconds from 1 m/s. An empty
plain cart reads 15.2 against 11.8 and is the leg most likely to look identical either way, since
vanilla's own drag is most of its loss.

A mixed train — one chest cart among plain ones — must coast the same distance in both models, which
is what proves the mean stayed unconditional.

## S3 — Bend loss behind the model — **planned**

**Build.** The simplest of the three: a pure departure with no repair inside it.
`RailDrag.bend(cart, turn)` takes its fraction from `RailForces.bendLoss` and returns without
touching movement under `VANILLA`. `TrainPlacer` goes on measuring the turn and `Train.setTurn`
goes on recording it either way, so the meter keeps reporting `turn=` in both models — it is
diagnostics about the track, not about the force.

**Done when.** With the rule off, a train crosses a 90° corner with no speed change beyond friction,
while the log still shows `turn=1.00` for it. With it on, 2% per right angle, 8% a lap on the
four-corner circuit.

## S4 — The engine — **planned**

**Build.** The three engine sites together, since they are one question — how hard an engine pushes
and how fast it may go — and splitting them would leave a stage where a furnace cart has vanilla's
ceiling and this mod's impulse, which is neither model.

`Train.drive()` takes its divisor from `RailForces.driveDivisor`, so `FREE_CARS` survives only in the
physical model. `MinecartFurnaceMixin$accelerate` restores vanilla's branch under `VANILLA` —
`deltaMovement.multiply(0.8, 0.0, 0.8).add(push)` handed to `super.applyNaturalSlowdown`, which is
vanilla's own line at `MinecartFurnace.java:89` — and keeps the flat impulse under `PHYSICAL`.
`MinecartFurnaceMixin$furnaceSpeed` stops cancelling the halving under `VANILLA`.

The impulse *projection* stays unconditional in both, against vanilla, for the reason in Findings.
Flag it in the code comment as the one deliberate deviation in the default model.

**Done when.** With the rule off: a lit furnace cart tops out at 4 m/s, reaches it within a second or
two of being fed, and climbs any grade regardless of how many cars it tows — the vanilla engine,
which is far stronger than this mod's. With it on: 8 m/s, 2× gravity of acceleration, and a
twelve-car train that visibly cannot climb what a three-car train can.

Both models must start from rest on a corner, which is what the proportional bend loss was built to
guarantee, and neither may rotate a cart on its rails when a furnace is fed into it.

## S5 — The repairs reviewed and left alone — **planned**

**Build.** Expected to be a no-op in code and a pass in comments: confirm that `slopeShare`,
`hauled`, the shove's `1 / mass`, the impact's `1 / n` and the brake's choice of the driven car are
each the ensemble answer rather than a departure, and say so where each one lives. Any that turns out
to carry a departure inside it joins S2–S4 — `Train.drive` was found that way and there may be
another.

**Done when.** A player shoving a parked three-car train moves it the same distance with the rule on
and with it off; a train whose leader alone is on a slope rolls downhill no faster than a single cart
in either model; a plain cart leading a furnace train moves in either model. Those three are the
requirement — that the repairs do not respond to the rule at all.

## S6 — The rule table and its drift — **planned**

**Build.** `README.md`'s Configuration table is wrong in two ways already and will be wrong in a
third: `furnace_minecart_speed [20]` at `README.md:41` no longer exists, `rail_drag_speed` was never
added, and `realistic_physics` will need a row saying plainly that the default is vanilla motion and
that this is a change for existing worlds.

**This is the one stage that needs asking first.** README edits are not made unprompted here, so the
stage is to propose the diff and apply it on approval, not to apply it.

**Done when.** The table lists every rule `MTGameRules` registers and no rule it does not, checked by
reading the two against each other rather than by memory.

## S7 — `rail_drag_speed` settled — **planned**

**Build.** The rule is in a poor state and this work makes it worse before it makes it better. It
converts a speed into the flat roll term, and the cap `RailDrag` puts on that term now binds at every
value above zero, so 1 and 1000 are the same setting and the rule reads as on or off. After S2 it
also does nothing at all under the default model.

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

**Whether the furnace impulse projection stays unconditional.** Proposed yes: vanilla only projects
once a cart is moving (`MinecartFurnace.java:103-105`), so vanilla mode would restore the fault where
feeding a stationary cart rotates it on its rails and stretches the chains across it. The argument
against is that "exactly vanilla" should mean exactly vanilla including its faults, and that a mod
which quietly fixes one has no clean claim to the word. Settled by deciding whether the default model
is "vanilla" or "vanilla as it would be without visual bugs this mod's chains make obvious" — a
question about what is being promised, not about the code.

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
the halved ceiling of 4 m/s almost at once, from an impulse of roughly one block per tick — a force
over a hundred times gravity, which is why vanilla furnace carts climb anything. `PHYSICAL`
accelerates at `2 × 0.0078125` to a ceiling of 8 m/s, so it is twice as fast at the top and
hundreds of times weaker on the way there, and that weakness is the whole of what makes train length
matter.

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
