# Brake lever

A brake lever on top of every furnace cart: right-clicked, it throws or releases the levers of every
furnace cart in the train, and each thrown lever brakes its own engine as one car standing on an
unpowered powered rail does, with that engine's drive suspended.

How the claims here were checked: the mod's brake path and the engine's parked state were mapped by
a read-only research pass with file citations; Polymer's virtual-entity API, kalic0re's
`CargoDisplayHolder` and the vanilla geometry (furnace block, passenger points, slime and
interaction sizes, lever states) by a second, against `libs/polymer` (dev/26.2) and the 26.2
decompile. The braking figures below were measured; what a client draws and what a click does were
not, since nothing here can see a client.

Stage order: S1 (the brake) stands alone and is what makes a thrown lever do anything. S2 (the lever
on screen and its click) needs S1's toggle. S3 (docs) needs both. The feature is complete at S2.

---

## Findings that reshape the work

**The lever is one more answer to the brake's existing per-car question.** Every brake decision in
the mod rests on `RailBrake.brakes(cart)`: `TrainCoupling.braked` shares the halt over a train as
the cars answering it over the train's mass (`railShare`), and `held` — which parks every engine of
the train — asks whether any car answers it. `braked` runs every tick on the driven car whatever
rail it is on (a RETURN hook on `NewMinecartBehavior.calculateHaltTrackSpeed`), so a lever counted
there brakes a train with no powered rail anywhere near it, and a lone cart the same way through
`drivenCars`' `List.of(cart)`. Nothing else needed a new hook.

**A slime cannot be posed, so it rides an interaction to reach the furnace's top.** A rider sits on
its vehicle's passenger point — 0.1875 above a cart, inside the furnace (whose top is at 1.03125:
`AbstractMinecartRenderer` draws it at 0.75 from 0.28125) — and a mob takes no display transform.
An interaction's own passenger point is its top, so the slime rides a zero-width interaction 0.84375
tall, which a client cannot pick (a zero-width box is never inside and almost never clipped). Both
points are straight up from the cart whatever its pitch.

---

## How these stages get tested

On the private dev server (`run/` of this worktree, port 25681), driven over rcon by
`lever_trial.py` in the job's tmp dir: a train rolls east on a flat line at z=460 under its engines'
power, its engines' levers are thrown by writing the `polytrains:brake_lever` attachment, and
the leading car's speed is read each tick until it stands; then the levers are released. The click
and the drawing need a client and are observed in game by the user. A stage is not finished until
its "Done when" is observed.

## S1 — The brake — **done**

**Build.** A persistent `polytrains:brake_lever` attachment (`EntityAttachments.BRAKE_LEVER`,
`Codec.BOOL`, absent when released). `RailBrake.brakes` answers true for a car with it set.
`train/BrakeLevers.toggle(engine)` flips the clicked engine's lever and sets every furnace cart of
its train the same, and plays vanilla's lever click.

**Done when.** A thrown lever slows a train as a brake rail under that many cars would — vanilla's
halving spread over the train's cars, for each engine — stops it dead, holds it with its engines
idle, and the train leaves the way it was going once the levers are released.

### As built

As planned. Measured per tick, with the parked engine's natural slowdown (×0.955) on top of the
brake: a lone furnace cart ×0.478 (a full halving), stopped in 4 ticks; four cars with one engine
×0.836 (1 − ½·¼); four with two engines ×0.717 (1 − ½·½); eight with one ×0.896 (1 − ½·⅛). Every
train stood still for 2 s with its levers thrown, and drove off east, its arrival direction, once
they were released.

**Revised after the user's first in-game run.** A furnace cart coupled to a parked train kept its
own released lever: `BrakeLevers.matchCoupled` now throws every engine's lever when any in the
newly joined train is thrown, from both places a coupling is made (`LinkAction.linkPair` and the
in-place upgrade in `MinecartHelper`) — a coupling never releases a brake. Not testable over rcon,
since only a player's click couples carts.

The same run left the user's train stuck against a buffer: three engines, the cold one at the free
end. Not the lever's doing, and older than it: a furnace cart at a train's head spends the train's
drive along its own impulse, and a cold one has neither fuel nor impulse, so heading away from the
buffer the train had no drive at all, stalled, turned into the buffer, stalled and turned back for
good. A furnace cart's own slowdown hook now passes through `TrainCoupling.hauled` as every other
leading car does, which a lit engine, driving, leaves alone. Rebuilt on the private server — five
cars packed 0.98 apart, lit engine against the buffer, cold engine at the free end — it sat flipping
its engines on the old code and left the buffer within half a second on the new; the lever trial
and six of the brake matrix's cases gave the same figures as before.

## S2 — The lever on screen, and its click — **superseded by S4**

**Build.** `display/BrakeLeverDisplay`, a kalic0re `CargoDisplayHolder` attached to every furnace
cart from `MinecartFurnaceMixin`'s first server tick, the way `LinkChain` is (no teardown: it is
ticked only through the cart's tracker). It holds a block display of
`lever[face=floor,facing=east]`, `powered` when thrown, posed in the cart's frame on the furnace's
top at the furnace's 0.75 scale; the riser interaction; and an invisible, silent size-1 slime riding
the riser through a passenger packet of its own (`startWatchingExtraPackets`). The slime's handler
answers the main hand only (a client offers a slime every click once per hand), checks reach as
vanilla does for a real entity, toggles and swings the arm; a shift-click, anything done with the
wrench, a punch and a pick are passed to the cart (`InteractionHandler.redirect`).

**Done when (observed in game, vanilla client).** Every furnace cart shows a floor lever on its
furnace, along the cart, staying on it as the cart moves, turns and climbs. Right-clicking it flips
the lever on every engine of the train and brakes the train as S1 measured; right-clicking again
releases it. Punching the lever hurts the cart; shift-clicking it with the wrench links as clicking
the cart does. Nothing else of the slime or the riser can be seen.

The holder also refreshes each rider's last sent position every tick (`updateLastSyncedPos`): an
element that ignores position updates is otherwise spawned for a late watcher wherever it first
was, possibly in chunks that client has not loaded and so never ticks into its seat. kalic0re's
`CargoDisplayHolder` has the same gap for every holder built on it, the tender's included; that fix
belongs there.

## S4 — The choo-choo parts and their lever box — **prototype, in game check pending**

**Build.** `display/ChooChooPartsDisplay` replaces S2's lever, riser and slime with one item display:
the `choo_choo_parts` group of `art/furnace_minecart.bbmodel` (smokestack, whistle, brake lever),
baked by `scripts/bbmodel-group-to-item-model.py` into `choo_choo_parts_brake_on` (as drawn) and
`choo_choo_parts_brake_off` (`--turn brake_lever z 45`). The model was built on a copy of vanilla's
cart and furnace, door on -x, so its axes are the cart's frame and one transform places it. The
smokestack and whistle are gated on `enable_choochoo` (default on); off, the display draws
`brake_lever_on`/`brake_lever_off`, the `brake_lever` group baked alone, whose lever is the same.
While they are drawn and the furnace burns, the stack's mouth sends `CAMPFIRE_COSY_SMOKE` as a
campfire does (one tick in nine, two or three puffs rising at 0.07), placed from a `RenderTrail`
read at the holder's render lag so it leaves the stack the client draws, not the one ahead of it.

The lever is clicked through an interaction box riding the cart: 1.0 wide, from the passenger point
(0.1875) to 1.0625, so a client draws it on the cart every frame, and a click on the cart above its
foot meets the box first. A plain main-hand click on its lever half — frame +z, the cart's left as
it faces its front: the left side and the left halves of the front, back and top — toggles the brake
(`throwsLever`); anything else is redirected to the cart (`InteractionHandler.redirect`, which
replays it through `handleInteract`, so Fabric's `UseEntityCallback` still runs). A click on the cart
itself is answered by the same rule in `MinecartHelper.useMinecart` from where on the cart's box it
landed (Fabric's `EntityHitResult`: the packet's location plus the cart's position).

Why a box at all, when S4 first read the cart's own: a client answers a click on a furnace cart
itself before the server hears of it, and the client mod Sounds (`sounds-2.5.1`) plays its "Furnace
Minecart crackles" from a `TAIL` inject on `MinecartFurnace.interact` on every such click, fuel or
not. Only a click that lands on another entity escapes it. Fuel goes past the box: a player holding
fuel the cart would take (`FurnaceFuel.feeds`) and not sneaking is told, alone, that the box has no
size, so their click lands on the cart, feeds it, and crackles as Sounds means it to. Sneaking with
fuel shows the box again, and a shift-click with fuel on its lever half works the lever. Left over:
below the box's foot, the cart's lowest 0.1875 is still the cart's own, lever half and crackle
included.

The box is `display/ClickBox`, shared with `display/CommandCartBox`: a command block cart's client
opens the editor itself on any click by an op in creative (`MinecartCommandBlock#interact`), wrench
or not, sneaking or not, so such a player holding the wrench is shown a box on the cart that passes
the click to it on the server instead.

A placed furnace cart is turned round (`MinecartHelper.placementLook`), its front, the door, to the
player.

**Done when (observed in game, vanilla client).** The parts stand on every furnace cart and stay on
it as it moves, turns and climbs; the lever stands upright when thrown and tips 45° toward the door
when released; a click on the lever's half flips it and brakes the train, with no crackle from
Sounds; a click holding fuel feeds the cart wherever it lands, crackling, and a shift-click with
fuel on the lever half works the lever; a click on the other half answers as the cart did; a
furnace cart placed from either end of a rail faces its placer; an op in creative holding the
wrench links a command block cart without its editor opening.

## S3 — Documentation — **done**

**Build.** A "Brake Lever" section in the README.

**Done when.** The README describes the lever.

## Open decisions

None blocking. Chosen without asking, and easy to change: right-click throws and releases, and
everything else on the lever reaches the cart; a train whose engines disagree — two trains coupled
with their levers set differently — is held by the thrown ones, brakes by their share only, and
agrees again from whichever lever is clicked next; the lever is on every furnace cart, with no rule.
S4's click half is the cart's box, which does not turn or tip: on a curve or a slope the half that
answers drifts a little from the drawn lever, as S2's slime did.
