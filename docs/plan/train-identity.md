# Train identity and control

Consolidating the several overlapping answers a train gives to "which car speaks for this" and
"is this train under control", so that each question has exactly one answer that every subsystem
asks.

Everything in the Findings below was read out of the source or observed in `run/logs/debug.log`
during the 2026-09-18 session; line numbers are from `09f3a8e` plus the uncommitted nose and
`holdNose` work. The failure timelines are observed. The *consequences* claimed for S3
(leader-as-head) are reasoned, not measured — that stage is the one most likely to be wrong, and
it is deliberately placed after the cheap wins so the rest is not blocked on it.

Stage order. S6, S7 and S8 narrow the ways a train can be released and are the highest-value work
here — build them first, in that order, since each is independent and S8 is the one that pays most.
S1 and S2 are independent of everything and of each other. S4 depends on S1 (abolishing the closed
case is what leaves `anchor` with nothing to do). S3 depends on S1 and S4, and is the only stage
that touches indexing across a reversal. S5 should not be built before S2, for the reason in its
body. The feature is complete at S4; S3 and S5 are improvements the rest makes safe.

---

## Findings that reshape the work

**Every failure chased this session is the same defect: one fact, two subsystems, two different
keys — and the bug lives where the keys disagree.** Five instances, four of them already fixed by
patching the individual gap rather than the pattern:

| The fact | Keyed one way | Keyed another |
| --- | --- | --- |
| The track a train is on | owned by a `Train` | shared by reference when a train splits |
| Which end of a cart is its front | durable per cart | discarded on every ribbon arc frame |
| A train is under power | gated on `cart.hasLink()` | constraint gated on seatability |
| A train is under control | `Train.placed()` — `bail == null` | `TrainPlacer.seated(car)` — a per-car tick stamp |
| Which car speaks for a train | `leader()` | `anchor()`, and `cars.getFirst()` |

The first three are fixed. The last two are what this document is about, and they are the two that
produce *silent* wrong answers rather than visible breakage.

**Seating is all-or-nothing, so one car's problem hands every car to vanilla.** A refusal calls
`release(train)`, which zeroes the seat mark on *every* car — including the ones the placer could
have placed perfectly. From that tick until the next success the train is N independent vanilla
carts that happen to still be chained: each moves itself along whatever rail is under it, each has
its yaw set from its own step delta, each receives the whole train's acceleration (S5), and nothing
holds them in formation, because `holdTrain` states *"There is no second system."*

It is also a per-tick condition and not a mode. `run/logs/debug.log` shows a train flipping several
times a second, so every consequence above applies for a tick at a time:

```
01:26:38  train of 4 not seated, SEAT_FOULED ...
01:26:38  train of 4 seated
01:26:38  train of 4 not seated, SEAT_FOULED ...
01:26:39  train of 4 seated
```

**The seven ways in divide cleanly into two kinds, and that division is what makes them
eliminable.** Four leave the placer with no frame of reference at all and must release the whole
train; three are one car's problem and need not.

| Bail | Kind | Structurally |
| --- | --- | --- |
| `NO_LEADING_END` | train-wide | collapses to "a train of one" once S1 lands — not a failure at all |
| `LEADER_OFF_RIBBON` | train-wide | eliminable: re-seed the ribbon instead of refusing (S6) |
| `LEADER_MID_TRAIN` | train-wide | eliminable: hold the leading end to an end car (S7) |
| `ORDER_UNREADABLE` | train-wide | reducible: fall back to the order already held (S7) |
| `RIBBON_SHORT` | one car | need not release the rest (S8) |
| `CAR_OFF_RIBBON` | one car | need not release the rest (S8) |
| `SEAT_FOULED` | one car | need not release the rest (S8) |

**A train that closes on itself is already unreachable, so `closed` is corruption tolerance wearing
a train kind's clothes.** `MinecartHelper.refusalFor:463` refuses `SAME_TRAIN` — and closing a loop
means linking two carts already in one train, so the wrench cannot build one. The only remaining
route is a damaged link graph, which `Train.MAX_WALK`'s own comment names: *"a link graph corrupted
by a half-written save could"*. Yet `closed` is threaded through `Train.build`, `leader()` (returns
null), `anchor()` and `follows()` (returns null) as though it were an ordinary state. Four branches
and a concept, carried to describe something that should be repaired where it is detected.

**`anchor()` is the head under another name.**

```java
public @Nullable AbstractMinecart anchor() {
    return closed ? lowest(cars) : cars.getFirst();   // Train.java:629
}
```

For any open train it returns `cars.getFirst()`. It exists solely to answer the closed case, and
both of its callers are `driver == null ? train.anchor() : driver`. Remove the closed case and the
method has nothing left to do.

**`seated` answers two different questions, and the leading car answers them differently.** It is
asked to mean "vanilla's move is cancelled for this car" (`AbstractMinecartMixin.placedInstead`)
and "this car is under the placer's control" (everywhere else). The leading car is *yes* to the
second and *no* to the first, because it is the car vanilla drives and therefore the only car that
records ribbon nodes at all. So the two can never be merged into one flag — they have to be split
into two named questions. `Train.placed()` compounds this by defaulting to **true** for a train
never attempted.

**The current head is arbitrary, so nothing is lost by making it mean something.** `Train.build`
canonicalises the car list by UUID (`sortsFirst`, `Train.java:829`). Index 0 is therefore already a
bookkeeping artefact with no physical meaning — not the front, not the end a player built from, not
the engine. This is what makes S3 cheap in principle: it replaces an arbitrary convention with a
useful one rather than overwriting a meaningful one.

**The leading end cannot simply be the head, but the head can be kept as the leading end.** These
are opposite proposals and only the second works. The ribbon is recorded by whichever car vanilla
actually drives, and that must be the car at the front of travel: a train reverses routinely
(furnace switchback, a stall on a grade, a shove), and a head that kept recording after a reversal
would re-record covered track while the cars now in front ran onto unrecorded track — `RIBBON_SHORT`,
refusal, and the train comes apart. Reversing the *list* when the leading end changes has no such
problem, because the leading car is still the one recording; it has merely been renumbered to 0.

---

## How these stages get tested

In-world on the minecart-tweaks dev server (`scripts/devserver.sh rkc/mods/minecart-tweaks`), with
`run/logs/debug.log` as the evidence record — **not** `latest.log`, which carries none of the train
logging. The useful filter is:

```
grep -E "TrainPlacer|TrackRibbon|TrainCoupling|TrainMeter|SwitchedRailBlock" run/logs/debug.log
```

Three rigs, all of which already exist on the primary test track:

- **A busy junction** — two trains crossing one switch, which is what produced the diverted-track
  sever at `01:22:23`.
- **A head-on pair** — two trains driven into each other hard enough to bounce, which is what
  produces off-rail movement steps and therefore every facing failure.
- **A grade with a run-up** long enough that a train straddles the foot of it.

A stage is not finished until its "Done when" is observed in world. Build-green and Checkstyle-clean
prove nothing here: every defect in the Findings table compiles.

## S1 — Refuse the closed train outright — **planned**

**Build.** Delete the `closed` concept from `Train`. `build()` currently sets it when `walk` comes
back on itself; instead, treat a cycle as the corruption it is — log it once and break the weakest
link so the graph is a path again, then build normally. Remove the `closed` branches from
`leader()`, `anchor()` and `follows()`. `MinecartHelper.refusalFor` already refuses `SAME_TRAIN`,
so no new link-time guard is needed; confirm that by reading it rather than by assuming.

**Done when.** A train of any length reports a non-null `leader()` on every tick it is linked, and
`grep -c "closed" src/main/java` returns only hits in unrelated contexts (the packing sense of
"closed up"). In world: no train ever reaches `holdAfterMove`'s `driver == null` path, observable
as the absence of any `NO_LEADING_END` bail that is not a train of one.

The repair-on-detect behaviour is the part to get right. Breaking a link silently would be a second
`sever`, with the orphan-making problem S5 is about; logging and refusing to build a train from a
cyclic graph may be the better answer. Resolve this by building it and watching what a hand-edited
cyclic save actually does.

## S2 — Split `seated` into its two questions — **planned**

**Build.** `TrainPlacer.seated(car)` currently answers both "vanilla's move is cancelled for this
car" and "this car is under the placer's control". Name them separately — the first stays as the
`moveAlongTrack` cancel test, the second becomes a train-level question that includes the leading
car. Fold `Train.placed()` into the second; drop its default-true-when-never-attempted behaviour,
which is a third answer again.

**Done when.** The leading car of a seated train answers *yes* to "under the placer's control" and
*no* to "movement cancelled", and both are observable: the train holds formation while its leading
car still records new ribbon nodes (`ribbon nodes=` climbing in `TrainMeter`).

The one-tick grace in `seated()` exists because a train is seated on its leader's tick and other
cars may have ticked already. That grace belongs to the movement-cancel question only; the control
question should not need it, because it is asked of the train and not of a car mid-tick.

Check one edge while re-homing it. On the tick the leading end flips, the new leading car was a
placed car the tick before, so the grace still reads it as seated, its `moveAlongTrack` is
cancelled, and it records nothing — `recordTrack` gates on `train.leader() == cart`. That is a
one-tick recording hole at exactly the moment the ribbon needs to start growing the other way.
Reasoned from the source, not observed; it is cheap to confirm once the two questions are named
apart.

## S3 — Keep the leading car at index 0 — **planned**

**Build.** When the leading end changes, reverse the car list so `cars.getFirst()` is always the
leading car, and reverse every per-index array with it in the same operation — `gaps` (one per
coupling, head first) and `faults`. `build()` keeps canonicalising by UUID for determinism, then
orients as a post-step. The flip must stay as sticky as `leader()` is today (`LEADER_FLIP_TICKS`),
or the list flaps and the per-index arrays flap with it.

**Done when.** `lead` is provably always 0 — the nine `lead == 0 ? x : y` branches
(`TrainPlacer.java:172, 178, 185, 200, 267, 351, 510, 561` and `TrainCoupling.java:899`) are gone
rather than merely unreachable, and a train driven back and forth across the busy junction holds its
spacing through repeated reversals with no `ORDER_UNREADABLE` or spacing discontinuity in
`TrainMeter`.

The upside is those nine branches: each is a sign that can be got wrong independently, and one of
them — the walk direction in `offsets` — was written this session and is exactly the shape of thing
that goes wrong silently on a grade or round a corner.

The risk is the mirror image: per-index state becomes orientation-sensitive, and a missed array is a
*silent* misattribution rather than a crash. That is the defect class in the Findings table, so this
stage must not introduce a second place that knows the orientation. One method reverses the train
and everything it indexes, or the stage is not done.

This is the stage most likely to be wrong. It is also the one that pays for itself only if S1 and S4
land first — a train with no leading end has no index 0 to be the leader.

## S4 — Delete `anchor` — **planned**

**Build.** Remove `Train.anchor()`. Its two callers (`TrainCoupling.java:140` and `:675`) become
plain reads of the leading car once S1 guarantees one exists.

**Done when.** `grep -rn "anchor" src/main/java` returns nothing, and exactly one car per train
still calls `holdTrain` per tick — observable as one `TrainMeter` line per train per second, not N.

Trivial once S1 is in, and listed separately because it is the point at which the "which car speaks"
row of the Findings table has a single answer.

## S5 — Gate the drive on control, not on linkage — **planned**

**Build.** Make the force model ask for the leading car itself instead of relying on the placer to
arrange that every other car never reaches it.

Both routes to the train's drive already assert the invariant. `AbstractMinecartMixin.railFriction`
says the drive is *"taken by the leading car ... every other car is placed, so this is the only car
that can carry it"*, and `MinecartFurnaceMixin.sharedAcceleration` says it answers for the engines
behind it *"since only the leading car reaches this"*. Neither checks. What actually keeps them
true is that a seated car has `moveAlongTrack` cancelled and so never reaches either — which holds
only while the placer is succeeding. `hauled`'s own gate is `cart.hasLink()` and `held()`, and
`held()` is about brakes, not seating (`TrainCoupling.java:513`).

So an uncontrolled train hands **every** car the whole train's acceleration, and there is no second
system to hold it together: `holdTrain` states *"There is no second system. A train is placed or it
is not."* Add the check the comments already claim.

**Done when.** A train that cannot be seated coasts to a stop instead of its cars accelerating apart
on intact chains. Observable on the busy junction: a train that logs a sustained bail no longer
shows its cars separating while `TrainMeter` reports rising `worst=` spacing.

Do not build this before S2. "Which car the placer does not place" is one of the two questions
`seated` currently conflates, and gating a force model on the wrong one of them would put the force
gap back in a new place.

**The speed curve is not affected, and this was checked rather than assumed.** `Train.drive()` is
summed engine output over total train mass, and it reaches the train by one of two routes depending
on whether the leading car is itself an engine: `railFriction` → `hauled` when it is not, and
`sharedAcceleration` when it is. Both produce the same figure, so there is no case where the drive
fails to reach a train, and no case where a seated train's behaviour changes — its other cars never
reach either route today. The only behaviour this stage alters is an uncontrolled train receiving
its own acceleration once per car, which is not a point on the curve but a multiplier that should
never have existed.

The residual unknown is the bootstrap: a train freshly coupled and standing still has never been
seated and has no settled leading end. `decideLeader` returns the head in that case, so exactly one
car is still nominated and the drive still has somewhere to go — but that is reasoned from the
source, not observed, and it is the thing to watch while building this.

## S6 — Seed the ribbon whenever it cannot answer for the leading car — **planned**

**Build.** `TrainCoupling.reachTrain` seeds a ribbon from the rail under the leading car, but only
`if (ribbon.isEmpty())`. A ribbon that holds track and simply does not cover where the leading car
now stands falls straight through to `arcOfLeader` returning null and a `LEADER_OFF_RIBBON` refusal
— every tick, until the leader happens to wander back onto recorded track or something resets the
ribbon. Widen the seed to "empty, or does not reach the leading car": if the leader stands on a
rail the ribbon cannot answer for, throw the ribbon away and start it from that rail.

**Done when.** `LEADER_OFF_RIBBON` no longer appears in `debug.log` for a train whose leading car is
on a rail. The bail survives only for a leader genuinely off the rails, where it names a car and a
sever is the right answer.

This is a strict improvement on what the ribbon already does for itself: `record` treats a block it
has never seen as a discontinuity and starts over. This stage simply asks the same question a tick
earlier, from the placer, rather than waiting for the leader to cross a block boundary.

## S7 — Keep the leading end readable — **planned**

**Build.** Two small guards.

`Train.heldLeader` returns the previously-leading car if the train still `contains` it. After a
merge that car can be in the *middle* of the new list, which is the only reachable route to
`LEADER_MID_TRAIN` — `decideLeader` itself only ever returns `head`, `cars.getLast()` or a held
value. Require an end car, not merely a member.

`ORDER_UNREADABLE` fires when `lieOf` finds two cars closer than a quarter of their gap, which is
what a collapsed train looks like. Fall back to the direction the leading car is travelling: the
end that leads is at the end of the train it is running towards, so `order` is `read` signed by
whether the leading car is the head. Refuse only when the train is also too slow to read a heading
from.

Not a fallback to the held `order`, which was the first idea and is unsound. `Train.order()`
already returns the held value whenever the ribbon keeps its arc frame, so `lieOf` is reached only
when nothing is held — and a value held against a *different* numbering would lay the train
backwards, which `lieOf`'s own comment names as the failure that seats a train off the end of its
track.

**Done when.** Neither bail appears in `debug.log` across a session that includes merges, hard
collisions and a busy junction. `LEADER_MID_TRAIN` should then be genuinely unreachable rather than
merely rare — S3 makes it structurally impossible, and this is the cheap version of the same
guarantee.

## S8 — Release only the cars that cannot be placed — **planned**

**Build.** The three per-car bails (`RIBBON_SHORT`, `CAR_OFF_RIBBON`, `SEAT_FOULED`) name a car
already. Place every car the ribbon can answer for and leave only the named one to vanilla, instead
of calling `release(train)` on all of them. The seats are computed from the leading car's arc and
each car's own offset, so the placeable cars are at their correct positions whether or not a car
further down the train could be placed — nothing about their seats depends on its.

**Done when.** A train with one fouled seat shows exactly one car loose in world, not all of them,
and `TrainMeter` reports `worst=` spacing rising for one gap rather than the train scattering.

This is the largest single reduction in exposure in the document: it converts most of the release
cases from train-wide to one car, and every consequence in the Findings table scales with how many
cars are loose.

The counter-argument is the design note it contradicts — *"A train is placed or it is not"* — whose
point is that a partly-held train has rigid cars that cannot respond to a loose one pulling on them.
That is real, but it is weighed against the present behaviour, which is that *every* car is loose
and under N-fold power. Build it and compare the two in world before deciding it is right; this is
the stage most worth being willing to back out.

---

## Open decisions

**What a diverted switch should do to a train.** Today it severs
(`TrainCoupling.holdTrain` → `sever`), which is irreversible and, when the named car is an end car,
leaves a train of one. Observed at `01:22:23`: a five-car train coming to rest on a junction that
other traffic kept throwing was cut where it stood, having been seated correctly the whole time.
The alternative is to stop seating it and let vanilla drive its cars onto the rails that actually
exist, which is self-correcting. Settled by S5: releasing instead of severing is only safe once an
uncontrolled train is not also under power, and is actively worse before that.

**Whether `Refusal.car()` should name a car at all.** Every use of the index is a sever, and severs
are what this document keeps finding at the root of other failures. If S5 makes release-instead-of-
sever viable, the index may have no remaining consumer. Settled by whether any non-sever caller
appears once S5 is built.

## Reference: what deliberately stays

**`leader` remains distinct from the head as a *concept*,** even after S3 makes them the same car.
The leading car is the one vanilla drives and therefore the only one that records ribbon nodes; the
head is index 0. S3 makes the second track the first, which is not the same as deleting either.

**`order` survives S3.** It records which way round the train lies on the ribbon's arc numbering,
which is a fact about the *track's* recording direction and not about which end leads — a ribbon is
free to have been recorded either way regardless of how the train is running on it. What S3 removes
is `facing = lead == 0 ? order : -order`, not `order` itself.

**Arc stays horizontal.** Unrelated to this document but adjacent to every change in it: arc is a
horizontal measure because that is the unit vanilla's stepper charges movement in
(`NewMinecartBehavior:256`, `:438`). A distance along the rails is a different unit and the two are
equal only on flat ground. `TrackRibbon.arcAlong` and `trackBetween` are the only sanctioned
conversions.
