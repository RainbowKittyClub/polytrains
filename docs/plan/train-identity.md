# Train identity and control

Consolidating the several overlapping answers a train gives to "which car speaks for this" and
"is this train under control", so that each question has exactly one answer that every subsystem
asks.

Everything in the Findings below was read out of the source or observed in `run/logs/debug.log`
during the 2026-09-18 session; line numbers are from `09f3a8e` plus the uncommitted nose and
`holdNose` work. The failure timelines are observed. The *consequences* claimed for S3
(leader-as-head) are reasoned, not measured — that stage is the one most likely to be wrong, and
it is deliberately placed after the cheap wins so the rest is not blocked on it.

Status as of 2026-09-19: S1–S15 are done bar S3, which was built, measured and backed out. S16 is
built and under test; S17 is not started.
S1–S8 were the original plan; S9–S15 came out of the unseating audit in the Findings below and
landed the same day, measured in "Measured after S9–S15" near the end. What is left is the two
Open decisions, which want evidence rather than code.

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

**The seven bails are not the whole of it. A train also comes unseated with no bail at all, and
that path leaves no log line.** Audited 2026-09-19 by reading every route into and out of
`TrainPlacer.seat`, after S1–S8 landed; this supersedes the two-kinds table above, which described
only the refusals.

**Read as a record, not as current state.** Every line item below was true when the audit was
taken, and S10–S15 closed most of them the same day — the table rows are annotated with which
stage did it. What still stands is in "Measured after S9–S15" near the end.

Seating is reached from exactly one place — `AbstractMinecartMixin.tick` TAIL →
`TrainCoupling.holdAfterMove` (`TrainCoupling.java:131`) — and that call is made by **one car per
train per tick**, the one `leader()` names. Four things stop it being made at all:

| Cause | Site | Costs | Since |
| --- | --- | --- | --- |
| Game rule off | `TrainCoupling.java:132` | whole train | by design |
| Cart holds no link | `TrainCoupling.java:132` | n/a | by design |
| No car satisfies `leader() == cart` this tick | `TrainCoupling.java:138` | whole train | **closed by S13** |
| Train rebuilt, so `attempted` is false again | `Train.java:291` | `placed()` only | **closed by S12** |

The third is the live one. The leading car may not tick: it can sit outside simulation distance
while the trailing cars are inside it, on a train long enough to straddle the border; or the train
can be rebuilt during another car's tick and the new `leader()` be a car that already ticked. The
one-tick grace in `seated()` absorbs exactly one missed tick and no more, so it is load-bearing for
this and not slack.

The fourth is an asymmetry rather than a failure, but a sharp one: a rebuild discards `attempted`
while the cars keep their stamps, so for one tick the cars read seated and the train reads
unplaced. `EntityMixin.shoveTrain` gates on `placed()`, so a shove arriving on a rebuild tick is
not routed to the leading car — it lands on a car whose movement the placer overwrites, and is
lost.

**Two of the four train-wide bails are now unreachable or nearly so, and one is reachable for a
reason S6 did not cover.**

| Bail | Now |
| --- | --- |
| `NO_LEADING_END` | *stands.* `leader == null` is unreachable for two cars or more; fires only on `size < 2`, which means a linked partner that did not resolve — an unloaded chunk (`Linkable.java:50`). Self-heals on reload. |
| `LEADER_OFF_RIBBON` | **narrowed by S11**, which added a tolerance so a leader within its own width of recorded track still resolves. What survives is a collision transient that clears on the next pass. Formerly: `arcOfLeader` demands `node.block().equals(pos)` — exact block identity, no tolerance — so a leader nudged off its rail block by a collision reads as fully off-ribbon on an intact ribbon. S6's re-seed does not help: it is gated on the car standing on a rail. |
| `LEADER_MID_TRAIN` | **deleted by S10.** Was structurally impossible: `leader` is only ever assigned from `decideLeader`, which returns `cars.getFirst()`, `cars.getLast()` or `heldLeader()`, and `heldLeader()` (`Train.java:717`) returns null unless the held car is one of those two. `cars` is final on a `Train` and `leader` is cached on that same object, so the two cannot drift apart. |
| `ORDER_UNREADABLE` | **closed by S14**, which settles the lie at the reset. Was compound and rare: needs a ribbon reset (which renumbers the frame and so voids `order`), cars collapsed tighter than a quarter of their gap, *and* a leader below `EPSILON` speed, all at once. |

**What makes a whole-train release categorically worse than the sum of its per-car parts is
`CartInteraction.java:223`.** Pass-through between two cars of one train needs at least one of them
seated:

```java
&& (TrainPlacer.seated(cart) || TrainPlacer.seated(other))
```

A per-car refusal leaves one loose car among seated neighbours, so every pair still has a seated
member and still passes through. A release makes every pair `false || false`, and the whole train
becomes solid to itself in one tick at whatever spacing the placer last chose. S15 later found the
same split missing one level up, in `Train.placed()`, which is what let one fouled seat take the
mass off a whole train. That is the
mechanism behind carts ending up inside each other, and it is why the four train-wide bails are a
different kind of event rather than a larger one.

**Of the seven, three are physical facts and stay.** `RIBBON_SHORT` is track that does not reach,
`CAR_OFF_RIBBON` is rail that was broken, `SEAT_FOULED` is another train in the way, and
`NO_LEADING_END` is a partner that is not loaded. `SEAT_FOULED` has no exit condition by design,
which is correct and leaves an unruled-out live-lock: a loose car is driven by vanilla, and vanilla
can shove it further into the train that is fouling it.

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

In-world on the serverside-trains dev server (`scripts/devserver.sh rkc/mods/serverside-trains`), with
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

## S1 — Refuse the closed train outright — **done**

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

### As built

`527c885`. The Build text offered two answers to a cyclic link graph and said to resolve it by
building; the second won. A cycle is logged at WARN and read from its lowest-UUID car, and **no
link is touched** — breaking one would have been a second `sever`, which is the thing this document
keeps finding at the root of other failures. The graph stays exactly as the save wrote it, and the
only thing the cycle decides is where the list starts.

`closed` is gone from `build`, `leader()` and `follows()`. S4 landed in the same commit, since
removing the closed case is the whole of what left `anchor()` with nothing to answer.

Not built: the "break the weakest link" repair. There is no weakest link — every coupling in a
cycle is identical — and choosing one would have been arbitrary in the way the head already was.

## S2 — Split `seated` into its two questions — **done**

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

### As built

`7525976`. Split as planned. `Train.placed()` is now `attempted && bail == null`, so a train never
attempted answers *no* rather than *yes*; the one-tick grace stayed on the per-car question, where
the Build text expected it.

The flagged edge — a one-tick recording hole on the tick the leading end flips, where the new
leading car is still reading as seated and so records nothing — was **not** confirmed either way.
It is cheap to observe (`ribbon nodes=` in `TrainMeter` stalling for one tick across a
`changed leading end` line) and was not looked for. Still open.

## S3 — Keep the leading car at index 0 — **skipped**

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

### As built

Built as specified and **backed out the same session**; the attempt is saved at
`scratchpad/s3-attempt`.

What the measurement showed. Flip rate was *unchanged* — 20.7 `changed leading end` lines per
minute under S3 against 22.2 per minute before it. So S3 did not cause more reversals; it changed
what each one cost. `TrainMeter` reported `worst=0.00` up to a reversal, then `worst=0.55` and
ribbon `worst=1.27` after it, and never recovered. Spacing broke *at* the reversal and stayed
broken.

Each piece of the reversal — `cars`, `gaps`, `faults`, `order` — was audited individually and each
was correct on its own. The fault was not located. That is itself the finding, and it is the exact
risk the Build text named: a per-index misattribution across a reversal is silent, and being silent
it was not diagnosable from the logs in one session.

What was built instead: S9's orientation consolidation, which takes the readability half of the
prize without making any per-index state orientation-sensitive.

What would revive it. A way to see the arrays across a single reversal directly — a debug line
dumping `gaps`, `order` and `lead` on the tick `leader()` changes its mind — so the
misattribution is observed rather than inferred. Without that, a retry is the same blind swap.

## S4 — Delete `anchor` — **done**

**Build.** Remove `Train.anchor()`. Its two callers (`TrainCoupling.java:140` and `:675`) become
plain reads of the leading car once S1 guarantees one exists.

**Done when.** `grep -rn "anchor" src/main/java` returns nothing, and exactly one car per train
still calls `holdTrain` per tick — observable as one `TrainMeter` line per train per second, not N.

Trivial once S1 is in, and listed separately because it is the point at which the "which car speaks"
row of the Findings table has a single answer.

### As built

`527c885`, alongside S1. `Train.anchor()` and both call sites are gone.

## S5 — Gate the drive on control, not on linkage — **done**

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

### As built

`d34525c`. The Build text's claim that the speed curve is unaffected held: both routes to
`Train.drive()` already divided by train mass, so neither produces a different figure.

It also found the **opposite** bug, which the Build text did not predict. `sharedAcceleration` was
gated on `hasLink()`, so a train with a *trailing* furnace spent the train's whole figure a second
time — once through the leading car's `hauled`, once through the trailing engine's own path. Both
now ask `train.leader() == this` and a car that is not it gets `0.0`.

Verified in world on the rear-furnace rig: a three-car train repeatedly crossing a one-block gap,
with two of its three cars loose, held `worst=0.00` and a steady 0.50 speed instead of accelerating
apart. The bootstrap unknown resolved the way it was reasoned — a standing train still nominates
its head, so the drive always has somewhere to go.

## S6 — Seed the ribbon whenever it cannot answer for the leading car — **done**

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

### As built

`fd0630f`. Widened exactly as specified: `reachTrain` now seeds on
`ribbon.isEmpty() || ribbon.arcOfLeader(leader) == null`.

The residual the Build text did not account for: the seed is gated on the leading car **standing on
a rail**, so it does nothing for a car knocked off its rail block by a collision.
`LEADER_OFF_RIBBON` survives there, and S11 closes it — but keep the denominator in view. Counted
over five rotated `debug.log`s plus the current one: `SEAT_FOULED` 898, `RIBBON_SHORT` 65,
`LEADER_OFF_RIBBON` 32, `CAR_OFF_RIBBON` 6, `ORDER_UNREADABLE` 1. So `LEADER_OFF_RIBBON` is 32 of
the 33 observed *whole-train* releases and worth closing, and 3% of all bails. Nine in ten are
`SEAT_FOULED`, which S15 is about.

## S7 — Keep the leading end readable — **done**

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

### As built

`c534f62`. Both guards landed. `heldLeader` now requires an end car rather than mere membership.

The Build text's own correction was the right one: falling back to the *held* `order` is unsound,
because `Train.order()` already returns the held value whenever the ribbon keeps its frame, so
`lieOf` is reached only when nothing is held. The travel-direction fallback was built instead.

The prediction that `LEADER_MID_TRAIN` would become "genuinely unreachable rather than merely rare"
turned out stronger than stated — the audit below traces every assignment of `leader` and finds the
bail **structurally impossible**, not merely unobserved. S10 deletes it.

## S8 — Release only the cars that cannot be placed — **done**

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

### As built

`b6763d9`. Built, and the counter-argument was weighed in world rather than on paper: a partly-held
train whose rigid cars cannot respond to a loose one is a real cost, and it is smaller than every
car being loose at once. 79% of bails now cost exactly one car.

`SEAT_FOULED` deliberately keeps `Refusal.NONE` rather than naming its car, because `Refusal.car()`
is read by nothing but the sever and a fouled train is waiting on another train, not coming apart.

One thing was built, measured and removed inside this stage: a `FOUL_TICKS = 5` give-up that let a
train seat itself after five fouled ticks. It seated trains *into* each other permanently — a seat
is applied outright, so neither train could then move under its own power to work its way out.
`SEAT_FOULED` now refuses for as long as it takes, with no allowance that runs out.

## S9 — Derive the train's orientation once — **done**

**Build.** S3's premise was that nine `lead == 0 ? x : y` branches are nine chances to get a sign
wrong. That premise was right; its remedy was not. Keep `lead` as a value that can be either end,
and derive the orientation from it **once**, near the top of `TrainPlacer.attempt`, so that every
later question about which end a thing sits at is a read of a name rather than a fresh ternary.

**Done when.** `grep -n "lead == 0 ?"` returns three adjacent derivations and nothing else, and a
train reversing repeatedly on the busy junction holds `worst=` at the same figure it held before —
this is a behaviour-preserving refactor, so the exit criterion is that *nothing changes*.

### As built

Three names carry it:

```java
int fromHead = lead == 0 ? 1 : -1;
int tail     = lead == 0 ? cars.size() - 1 : 0;
int nearGap  = lead == 0 ? 0 : cars.size() - 2;
```

Every former ternary became a read: `cars.get(lead + fromHead)`, `gaps[nearGap]`, `fromHead < 0`,
`order * fromHead`, `(leadArc - nextArc) * fromHead`, and in `TrainCoupling.sever`,
`car == lead ? fromHead : -fromHead`. The two mirrored loops in `offsets` collapsed into one with
`step = order * fromHead` and `i = fromHead > 0 ? away : gaps.length - 1 - away`; both directions
were traced back to the originals term by term.

Nine independent sign decisions became three adjacent ones. The point is not the line count: a
wrong sign is now wrong *everywhere at once*, which is a bug found in the first minute rather than
the first grade. And unlike S3 it needs no orientation-sensitive state — `gaps` and `faults` stay
indexed head-first forever.

## S10 — Delete `LEADER_MID_TRAIN` — **done**

**Build.** Remove the enum constant and its guard in `TrainPlacer.attempt`. The audit traces every
assignment of `Train.leader` and finds no route by which it can be a middle car: `decideLeader`
returns only `cars.getFirst()`, `cars.getLast()` or `heldLeader()`, `heldLeader()` requires an end
car, `cars` is final on a `Train`, and `leader` is cached on that same object. S7 made this true;
the bail is what was left behind.

Correct the `Bail` javadoc with it — it says "the four that do" of the train-wide kind, and after
this there are three.

**Done when.** `grep -rn LEADER_MID_TRAIN src` returns nothing, and a session including merges and
hard collisions shows no new bail kind in its place.

The thing to be sure of is that deleting a guard does not delete the invariant. It does not: the
invariant is enforced at `heldLeader`, which is where it belongs, and the guard was a second
statement of it that could only ever agree.

### As built

As specified, and it is the whole of the change: the constant, the guard, and "the four that do"
in the `Bail` javadoc.

The audit was not the first to reach this. `docs/plan/train-ribbon.md` already recorded it, in the
round that introduced the tag: *"`LEADER_MID_TRAIN` did not [fire], and cannot: the car order and
`decideLeader` agree by construction, so it guards a contradiction in `Train` rather than a
situation on the track."* Two independent analyses, same answer, and the bail outlived both because
neither round went back and deleted it — which is its own small lesson about findings that are
written down and then left where they were written.

Where the guard stood there is now a comment saying where the invariant actually lives, because an
invariant nothing states anywhere is one the next reader re-adds a check for.

## S11 — Let `arcOfLeader` tolerate a car off its own block — **done**

**Build.** `TrackRibbon.arcOfLeader` matches on `node.block().equals(pos)` and gives up when no
node holds that block. A leading car shoved sideways by a collision — the single most common thing
that happens to a train — is then fully off a ribbon that describes its track perfectly. Add a
fallback: when no node holds the block, take the node whose chord passes nearest the car in space,
within a radius derived from the car's own width, tie-broken by continuity from `leaderArc` exactly
as the exact-match path already is.

This closes two failures, not one. Today a shoved leader either releases the whole train
(`LEADER_OFF_RIBBON`) or, if it happens to be over some other rail, makes `reachTrain` call
`record` on a block the ribbon has never seen — which is a **discontinuity, so the ribbon resets**,
taking `order` and `headArc` with it. The tolerance prevents both.

**Done when.** `LEADER_OFF_RIBBON` no longer appears for a leading car within a block of its
recorded track, and the `ribbon reset at` rate in `debug.log` falls across a session of head-on
collisions. Both are countable before and after on the same rig.

The risk to watch is parallel track a block apart: a leader shoved from track A onto track B's
block will snap to A, and the whole train is then seated a block from where its leader really is.
That is bounded, self-corrects the moment the leader lands back on recorded track, and is strictly
better than the release it replaces — but it is the thing to look for, so keep the radius tight
and take the distance in three dimensions so a car on a bridge cannot match track beneath it.

### As built

`occurrenceUnder` keeps the strict reading; `nearestTrack` is the fallback, ranked exactly as
`arcOf` ranks so the lap tie-break carries over.

The radius turned out to select itself, which was luck rather than design. A cart's `getBbWidth()`
is 0.98 and parallel track sits 1.0 apart, so a car squarely on a neighbouring line falls *outside*
reach and is honestly reported off this ribbon, while a car nudged off its own rail — 0.3 to 0.6
from the chord — falls inside. Deriving the bound from the entity rather than from a block literal
is what put it there.

It closes a second failure the Build text did not name. Before this, a shoved leader standing over
*some other* rail made `reachTrain` call `record` on a block the ribbon had never seen, which is a
discontinuity — so the ribbon reset, taking `order` and `headArc` with it. The tolerance stops the
reset as well as the release, because `reachTrain`'s re-seed is gated on `arcOfLeader` returning
null.

Not fully closed: `LEADER_OFF_RIBBON` still runs at about one a minute in bursts when two trains
collide at speed, where the leader ends up further than its own width from any recorded track. It
clears on the next pass now instead of costing a sever — see the measurements below.

## S12 — Carry the seating verdict across a rebuild — **done**

**Build.** `Train.of` already inherits the ribbon across a rebuild, on the grounds that a link
change does not move the train. `attempted` and `bail` are the same argument and are not carried,
so a rebuilt train reads `placed() == false` for one tick while its cars are still stamped seated.
Carry them, as the ribbon is carried.

**Done when.** A shove landing on the tick a train is coupled or severed reaches the leading car
rather than vanishing — observable as the train moving at all, where today that one shove is
silently dropped.

### As built

**Nothing is carried.** The Build text proposed inheriting `attempted` and `bail` the way the
ribbon is inherited, and that would have worked, but it keeps a second key for a fact the cars
already record. `holding()` is derived instead:

```java
public boolean holding() {
    for (AbstractMinecart car : cars) {
        if (TrainPlacer.seated(car)) {
            return true;
        }
    }

    return false;
}
```

A rebuild then needs no inheritance at all — the seat marks live on the carts and a rebuild does
not touch them — and three other things fall out that the inheriting version would not have fixed:

- **It says what S8's own `loose()` comment already said** and `holding()` did not: *"a refusal
  naming one car and leaving one car loose is the placer holding the rest; the same refusal leaving
  every car loose is not."* The verdict cannot tell those apart, because they are the same verdict.
  A train of five with one car fouled is still held; a train of two with one car fouled is not.
- **`release()` reads here without being reported here.** It zeroes every seat mark, so a released
  train answers no immediately, rather than by way of a `bail` that `seat()` has to remember to set.
- **A train whose leading car stops ticking answers no after two ticks**, as the stamps expire,
  instead of holding a stale `bail == null` forever. That does not fix the missed tick — S13 — but
  it stops the missed tick being invisible to everything downstream.

The deliberate behaviour change: a train with *every* trailing car loose now freezes its leading
end in `settledLeader`, where before it could still flip. That is the correct reading of that
method's own comment — such a train is loose carts, and a car of it moving towards the others is
shunting rather than leading a reversal. It does not touch the single-fouled-car case, which is
what the deadlock actually looks like in the logs (`1 loose`, not `2 loose`).

Cost: `holding()` is an O(length) scan rather than a field read, short-circuiting on the first
seated car — which for a held train is index 0 or 1. Its two callers are one shove and one per-tick
leader decision, neither on the per-collision-candidate path, so it is not cached. If it ever needs
to be, `mass` and `rolling` in the same class show the per-tick pattern.

## S13 — Seat from a car other than the leader when the leader has not ticked — **done**

**Build.** Seating is driven by exactly one car per tick and is simply not done if that car does
not tick — a leading car outside simulation distance on a train that straddles the border, or a
rebuild that renames the leader to a car already ticked. Let a second car drive it, but only on the
*second* consecutive missed tick, so the existing one-tick grace still does the work in the common
case and nothing changes for a train that is fine.

**Done when.** A train long enough to straddle the simulation border holds formation instead of
its trailing half coming loose.

The reason this is last and not first: reading the leading car's position from another car's tick
is exactly the staleness that put `holdAfterMove` at `tick` TAIL in the first place, and at four
blocks a tick a stale read is not a small error. It may be that the right answer is to refuse to
seat rather than to seat from stale data, in which case this stage is a `release` with a reason
attached instead. Resolve by building it and watching the border case.

### As built

**The staleness the Build text was worried about does not apply to the case this fixes**, and
noticing that is what made the stage small. A leading car that has not ticked *yet* this tick is
about to move, and reading it then is reading it up to four blocks early — that is the real hazard
and it is why `holdAfterMove` sits at `tick` TAIL. But a leading car that missed the tick *before*
is not late, it is stopped: it is not being simulated, so it is not going anywhere, and where it
stands is exactly where it is going to be. The two are indistinguishable within one tick and
obvious across two. So the fallback waits a whole tick out, and then seats from a position that is
not stale at all rather than from one that is.

That made the per-cart tick stamp unnecessary. One field on `Train` carries it:

```java
public boolean unheld(long tick) {
    return heldTick + 1 < tick;
}
```

with `holdTrain` stamping `setHeldAt` at the top — deliberately before the attempt, because what it
records is that somebody asked, not that the asking worked. A train refused every tick is being
looked after, and a refusal must not let a second car conclude the first never came.

Checked against the steady state, which is what a fallback like this most often breaks: a trailing
car ticking *before* its leader reads `heldTick == tick - 1`, so `unheld` is false and nothing
fires. It fires only after a tick with no holder at all, and the first car to notice stamps it, so
it fires once.

Not built: any attempt to close the one-tick hole. `seated` forgives one tick and the fallback
arrives on the second, so the cars run on vanilla's own movement for the tick this decides in and
are gathered up at the end of it. Closing it would mean knowing whether a car is going to tick,
which is not knowable within the tick. One tick loose beats loose for as long as the leading car
stays outside the border.

## S14 — Derive `order` after a ribbon reset — **done**

**Build.** `Train.order()` returns 0 whenever `ribbon.frame() != orderAt`, so every reset throws
the train's lie away and `lieOf` has to read it back off the cars — which is what `ORDER_UNREADABLE`
fires on when they are collapsed. But a reset is not an unknown: `record` sets `leaderArc = 0.0`
and orients the new frame off the leading car's own `movement`, so the sign is recoverable rather
than lost. Recover it.

**Done when.** `ORDER_UNREADABLE` no longer appears after a `ribbon reset at` line in
`debug.log`.

Narrow, and worth doing only after S11 — which removes most of the resets that would reach it.

### As built

**The Build text sold this on the wrong benefit.** `ORDER_UNREADABLE` was observed once in about a
thousand bails, so refusing less often is worth almost nothing. What is worth something is the
failure next door: when `lieOf` *does* read two collapsed cars and gets the sign wrong, the train
is laid backwards for the life of the frame and seated off the end of its own track — which
`lieOf`'s own comment names as the thing it is most afraid of. Settling the lie at the reset means
`lieOf` is never asked after one, so that reading cannot be got wrong there at all.

The derivation, checked twice over because a wrong sign here is exactly the failure being
prevented. A reset seeds its one node from `RailPath.crossing(level, pos, movement)`, whose exit is
the one the movement is heading for — so arc increases the way the leading car was going, which
puts that car at the highest arc of the train and every other car behind it at lower ones. The head
is at the higher arc exactly when the head is the car that was leading, so `order = fromHead`.
Cross-checked against S7's independent travel-direction fallback, `lie = read * fromHead`: at a
reset `read` is +1 by construction, giving the same answer.

Where it had to go. Not in `order()` asked lazily, which was the obvious shape and is wrong: the
frame's numbering is fixed at the reset but which car leads is not, and after a reversal the
leading car sits at the *lowest* arc, so the same comparison made later gives the opposite answer.
It has to be taken at the reset, while it is still true. `recordTrack` compares `ribbon.frame()`
either side of the `record` call and settles it when the frame moved.

Guarded on the movement having a direction in it. `makeStepAlongTrack` only fires for a cart that
is crossing blocks, so this should never be zero — but the whole derivation rests on that vector,
and a lie settled off a rail walked in an arbitrary direction is the backwards-train failure with
extra steps.

Not built: carrying `order` across a rebuild. It looks free, since the ribbon and its frame are
inherited, but `order` is defined against `cars.getFirst()` and `build` re-picks the head by UUID —
so a sever can hand a half-train the opposite head and invert the sign. The frame guard on
`orderAt` stays for exactly that case, and a rebuild goes on reading the lie off the cars, which
is the common path and works.

## S15 — Ask whether the placer holds *this car*, not whether it holds every car — **done**

**Build.** `Train.placed()` is `attempted && bail == null`, and it has exactly one reader:
`EntityMixin.shoveTrain:74`, the gate that cancels vanilla's mutual shove, divides it by train mass
and routes it to the leading car. S8 made the three per-car bails cost one car; it did not change
`placed()`. So one fouled seat still flips the whole train to *unplaced*, and for that tick the
shove is not cancelled, not divided by mass and not routed — it lands full force on whichever car
was leaned on.

Replace the train-wide gate with the per-car fact the placer already records: route the shove when
the shoved car is one the placer is holding — seated, or the leading car — and leave it to vanilla
otherwise. That also fixes the converse, which the current gate gets wrong in the other direction:
a genuinely loose car has its shove routed to the leading car today, when it is an ordinary vanilla
cart and should take its own.

**Done when.** A single unlinked cart cannot hold a standing train of three. Today it can, and the
signature is `SEAT_FOULED` and `seated` alternating at sub-second period in `debug.log` while
neither the cart nor the train goes anywhere:

```
12:23:46 train of 3 not seated, SEAT_FOULED, 1 loose
12:23:46 train of 3 seated
12:23:47 train of 3 not seated, SEAT_FOULED, 1 loose
12:23:47 train of 3 seated
```

The loop is self-sustaining, which is what makes it a deadlock rather than a stall. A cart fouling
one seat makes the train read unplaced; unplaced, the train has no mass advantage, so the cart
shoves the leading car; the leading car moving moves every seat with it, so the foul clears; seated,
the train recovers its mass and creeps back into the cart; foul again.

This is the largest single bail class by an order of magnitude — nine in ten — and neither S10 nor
S11 touches it. Do it before either of the remaining planned stages.

### As built

The Build text said `placed()` had exactly one reader. **It had two.** The second is
`Train.settledLeader` (`Train.java:649`), which freezes the leading end whenever the train is not
placed — so a train with one seat fouled could not change which end it led with, and therefore
could not decide to back off the thing fouling it. That is the other half of the deadlock, and it
was found only by grepping for the unqualified call.

`Train.placed()` is gone, replaced by `Train.holding()`:

```java
public boolean holding() {
    return attempted && (bail == null || !bail.whole());
}
```

Both former readers take it. The per-car question became `TrainCoupling.carried(cart)`, which is
what the shove gate now asks:

```java
return TrainPlacer.seated(cart) || (cart == train.leader() && train.holding());
```

The leading car is never seated, so it needs the train-level clause; every other car answers from
its own stamp. The gamerule and `hasLink()` tests moved into `carried` with it, which left
`EntityMixin` with the bare pair of conditions the injection actually cares about.

Two stale javadocs went with it — `TrainPlacer`'s class comment and `seat()`'s both still said
nothing is moved unless every car can be placed, which S8 stopped being true and neither was
updated at the time.

Residual, deliberately not built: `share` is still `1.0 / mass(cart)` over the *whole* train,
including cars the placer has let go of. A train of three with one car loose therefore resists a
shove as though all three were held. The error is bounded by how many cars are loose, 79% of bails
leave exactly one, and making mass follow seating means recomputing it per tick rather than per
train. Revisit only if a train with several loose cars reads as implausibly heavy.

Not to be confused with widening `SEAT_FOULED` itself. Refusing to seat a car into an occupied
space is correct and must stay unconditional; a `FOUL_TICKS` give-up was tried in S8 and removed
because it seated trains permanently into each other. What is wrong is not the refusal but what the
refusal is taken to mean about the other cars.

## S16 — Take a car's facing from its train, not from the car — **in progress**

**Build.** Delete the per-cart nose entirely — the `Vec3` field, both ext methods, and `noseOf` —
and take a car's facing from the one thing that already describes where its head lies: the train's
own `order`.

```java
boolean flipped = order * facing < 0.0;
car.setYRot(MinecartHelper.yawOf(along.scale(order)));
```

`holdNose` keeps its job of covering the cars the placer does not aim — the leading car, and every
car of a train that could not be seated — but becomes a correction against the **track** rather
than against the heading the car had last tick.

**Done when.** No chain is ever drawn across a car, in a session that includes head-on collisions
at speed; `holdNose` reports few or no corrections; and the new order detector never fires.

The invariant that makes it work is that both halves of `along.scale(order)` are read against the
ribbon's numbering, and a re-recording reverses both — so the direction that comes out is the same
in the world either way round. That is the property the per-cart heading was invented to get, and
it turns out the train already had it.

What it gives up is a car deliberately coupled facing backwards, which this straightens. That is
the one state it can no longer represent, and it is the state that draws a chain across a car.

The risk it takes is the mirror of the bug it fixes: `order` is shared, so a wrong `order` turns a
whole train at once rather than one car. That is the better failure — visible immediately,
self-consistent, and it never crosses a chain — and `order` now has exactly two derivation points
(S14 at a reset, `lieOf` otherwise) rather than a geometric comparison per car per tick.

### As built

Built as specified; net **39 lines deleted**, and one piece of per-cart state gone.

The argument that stood against this was a conflation, written into `aim`'s own comment: *"a U-turn
swings a car through 180 degrees without reversing it, and two cars of one train may honestly face
opposite ways, so there is no train-wide answer to hold either."* Opposite ways **in the world** —
which `along.scale(order)` gives, because the tangent differs along the turn. What it refuses is
two cars disagreeing about which way along their own train they face, and no U-turn does that. The
per-cart heading existed to solve a problem that was never there.

Three defects died together rather than one at a time, because all three came of asking *"has this
car turned since last tick?"* — a question about two headings, which has no answer at ninety
degrees and needs per-car memory to ask at all. The new question is *"is this car pointing along
its own track right now?"*: two antipodal candidates against the tangent where the car actually is,
unambiguous at every angle. So the ninety-degree tie-break, the bias it produced and the ratchet
that followed have nowhere left to live.

Measured over the first session: `holdNose` corrected **zero** times, and the order detector fired
**zero** times.

Two pieces of instrumentation landed with it, both debug-only.

`TrainMeter.reportDisorder` says when the cars do not lie along the track in the order their links
run in. Nothing was measuring that: the spacing walk reports how far apart list-adjacent cars are,
which reads identically whether a car is ahead of its neighbour or behind it, so a swapped pair
printed as a perfectly held train.

Every train now logs as `train <head entity id> of <n>`. Before this every line said only how many
cars a train had, and on a busy track that is several trains answering to one name — which
silently corrupted earlier readings in this document's own session, where a fault passing between
trains at one bad piece of track read as a single train frozen there.

## S17 — Treat a fouling cart as the end of the track, not as a refusal — **planned**

**Build.** `SEAT_FOULED` leaves the fouled car unseated, and an unseated car is driven by vanilla
straight back into whatever fouled it. When the thing fouling it is another *train*, both sides do
this at once and neither moves: each refuses to seat, each leaves a car loose, each loose car is
driven into the other train.

Stop treating a foreign cart as a refusal and treat it as the end of usable track. The placer
already has that machinery — `blockedBeyond`, `pressing`, `packing` and the `blocked` draw-back
are exactly "this train has run out of room and packs up short of it". Resolve the fouling cart to
an arc on this train's ribbon, take the nearest one ahead of the leading car as a limit, and feed
that limit into the same path. Every car then gets a seat, the train packs short of the
obstruction, keeps its mass, and shoves through the ordinary collision path.

**Done when.** Two trains driven head-on come to a stop with **every car still seated**, no
`SEAT_FOULED` and no loose car on either side, and both resume when one is driven away. Observable
as the absence of the alternating seated/`SEAT_FOULED` lines in `debug.log` for the pair, now that
each train logs under its own name.

This does not relax the rule that a car is never seated into occupied space, which is correct and
which an earlier `FOUL_TICKS` give-up already proved cannot be relaxed. It changes what the refusal
*means*: today it means "give this car to vanilla", and vanilla drives it back into the problem.

Three things to resolve by building it.

A fouling cart may have no arc on this ribbon at all — a cart on a crossing track, or on a parallel
line the ribbon never recorded. There is no limit to derive from that, so `SEAT_FOULED` has to
survive for it. It should become the rare case rather than the common one.

Two trains each drawing back from the other could oscillate, each backing off until the foul clears
and then advancing again. The packing path already carries damping for exactly this shape —
`carried` holds the speed a train arrived with, and `packedTick` records that give was taken — so
the question is whether that damping is enough here or whether a standoff needs a rule of its own.

The swept-volume half of `fouled` is a different question from the static one: it asks what a car
would pass *through* on the way to its seat, which is about the path and not about an obstruction
sitting somewhere. It may want to keep refusing.

---

## Measured after S9–S15

One 49-minute session on the dev rig against the last session before any of this landed. Rates per
minute, because the windows differ in length; `debug.log` rotates on each deploy, so the rows in
between are a few minutes each of whatever was being tested at the time and are **not** a
controlled comparison. The first and last rows are the ones worth reading.

| Build | mins | flips/m | resets/m | `LEADER_OFF`/m | `SEAT_FOULED`/m | severs |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| before S9 | 12.7 | 27.3 | 1.1 | 0.0 | 34.4 | 1 |
| after S15 | 49.0 | **7.7** | 1.9 | 0.9 | **3.7** | **0** |

Leading-end flips fell from 27 a minute to 8, fouls from 34 to 4, and no train was cut in
forty-nine minutes where four of the five windows before it cut one. `ORDER_UNREADABLE` did not
appear once across 111 ribbon resets, and `LEADER_MID_TRAIN` cannot appear at all.

`now lies` fired 5 times against those 111 resets, which is the evidence that S14 is live at all: a
reset that leaves the lie unchanged logs nothing, so those five are resets where the sign really
did change — the cases `lieOf` would otherwise have had to read off two collapsed cars.

What remains is understood rather than open. `LEADER_OFF_RIBBON` fires in bursts when two trains
collide at speed and clears on the next pass: the placer's own log shows `RIBBON_SHORT`, then
`LEADER_OFF_RIBBON`, then `seated` inside the same second, with spacing back at `worst=0.00` in
between. Worst spacing seen all session was 1.35, during those collisions. What changed is not that
a collision stops knocking a leader off its track, but that a train no longer has to be cut to
recover from it.

## Open decisions

**What a diverted switch should do to a train.** Today it severs
(`TrainCoupling.holdTrain` → `sever`), which is irreversible and, when the named car is an end car,
leaves a train of one. Observed at `01:22:23`: a five-car train coming to rest on a junction that
other traffic kept throwing was cut where it stood, having been seated correctly the whole time.
The alternative is to stop seating it and let vanilla drive its cars onto the rails that actually
exist, which is self-correcting. This was blocked on S5 — releasing instead of severing is only
safe once an uncontrolled train is not also under power, and is actively worse before that. **S5
has landed, so it is now unblocked and still undecided.** What would settle it: run the busy
junction with the sever replaced by a release and count how often a train that would have been cut
recovers on its own.

**Whether `Refusal.car()` should name a car at all.** Every use of the index is a sever, and severs
are what this document keeps finding at the root of other failures. S5 is built and no non-sever
caller appeared, so the index still has exactly one consumer — which means this decision now rides
entirely on the one above it. Settle them together.

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
