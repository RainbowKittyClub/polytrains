# Coal tender

A coal tender cart — a minecart carrying a coal block stretched to fill it to one pixel below the
rim, holding only coal and charcoal — that keeps a furnace cart coupled to it burning at the rate it
burns, replacing the "any coupled container feeds the engine" behaviour of `079657a`.

How the claims here were checked: vanilla geometry and call order are read from the 26.2 decompile
(`libs/decompiledjava/minecraft-26.2`); sensible-storage's and kalic0re's cart machinery was mapped
by a read-only research pass with file citations, and the load-bearing claims spot-checked against
the source and the pinned `kalic0re-0.15.0.jar`. Paths below are relative to each repo's
`src/main/java/club/rainbowkitty/<mod>/`; `K/` is kalic0re's `common/`. Nothing here has been built.

Stage order: S1 is a revert and stands alone. S2 (the cart and its item) unblocks S3 (feeding) and
S4 (the load on screen), which are independent of each other. S5 (icon and docs) needs S2. S3 is the
stage that makes the tender do anything; the feature is complete at S5. **S1 and S3 must reach a
server together** — between them no cart feeds an engine at all.

---

## Findings that reshape the work

**kalic0re already holds the cargo-cart machinery, so the tender is mostly configuration.**
`K/vehicle/` has `CargoMinecarts.register` (entity type plus Polymer type substitution,
`CargoMinecarts.java:172-180`), `CargoMinecartChest extends MinecartChest` (`:43`),
`CartCargoSupport.apply` (blanks vanilla's cargo slot and attaches a display holder,
`CartCargoSupport.java:69-90`), `CargoDisplayHolder` (display elements mounted as riders and posed
each tick off a `RenderTrail`, `CargoDisplayHolder.java:26`) and `PolymerMinecartItem` (placement
through vanilla's own `MinecartItem.useOn`, `PolymerMinecartItem.java:30`). The pinned 0.15.0 jar
has all seven classes. This mod shades `display` already and adds `vehicle`.

**A tender built on `MinecartChest` would already be fed by the code S1 removes.**
`FurnaceFuel.drawFromCoupled` accepts any `Container` neighbour, so the removal and the tender-only
feeding replacing it ship together.

**Vanilla clients constrain the entity three ways.** It may carry no synched entity data: a vanilla
client's tracker is sized for the vanilla class and extra ids crash it
(`K/vehicle/CargoMinecartChest.java:39-41`), so the tender's burn state lives in NBT only. It is
presented as plain `minecraft:minecart`, not `chest_minecart`: a client watching a container cart
draws that cart's block even with `DisplayState` air (sensible-storage
`docs/plan/chest-minecarts.md:510-534`). And a plain minecart makes the client send each click
twice, which kalic0re answers by taking the main hand only (`CargoMinecartChest.java:86-91`) — while
this mod already dedupes two-handed clicks on every cart through its own `UseEntityCallback`
(`ServersideTrains.java:86`) and `USE_ANSWERED_AT`. S2 reconciles the two rather than stacking them.

**Vanilla's cargo slot cannot draw the load; a display element must.** A cart's display block is
drawn at a uniform 0.75 (`AbstractMinecartRenderer.java:62`); the interior is 16 × 12 × 8 px (see
the reference section), so a block stretched to it needs its own element.

**`minecraft:furnace_minecart_fuel` is exactly coal and charcoal**, so the tender's filter is the
tag the hand-feed path already reads through `FurnaceFuel.cartBurnDuration`.

## How these stages get tested

On a private dev server — a snapshot of the main checkout's `run/` on its own ports, as for the
brake work — driven over rcon by scripted cases in the style of that work's trial driver, with
`scripts/devserver.sh <dir> mixins` and a log scan for errors after every boot. Gameplay criteria
are observed by script. Anything drawn on screen (S4, S5) needs a client and is observed in game by
the user: nothing here can see a client. A stage is not finished until its "Done when" is observed,
so a failure at stage N implicates stage N and not the ones before it.

## S1 — Remove the generic container pull — **done**

**Build.** Take out what `079657a` added for feeding: `FurnaceFuel.drawFromCoupled` and `takeOne`,
the `TENDER_INTERVAL` hook in `MinecartFurnaceMixin`'s tick, the rule
`FURNACES_REFUEL_FROM_STORAGE_CARTS`, its two lang keys and its README row. Keep `69b8e3e`'s
unification — `feedByHand`, `FurnaceFuel.cartBurnDuration`, `MinecartFurnaceAccessor` — which S3
reuses. Removing the rule leaves no stale game-rule key anywhere: it never reached a world that
saves rules (live runs `30909e5`; the main dev server runs the main checkout).

**Done when.** On the private server a lit engine coupled to a chest cart of coal burns its own fuel
down one a tick and the chest keeps its coal; `gamerule sstrains:furnaces_refuel_from_storage_carts`
is an unknown rule; the mixin check is clean.

### As built

As planned. `cartBurnDuration`'s private overload, which existed only so the scan could read
`furnaces_can_use_all_fuels` once, went with the scan. README, lang and `ModGameRules` are byte-for-
byte what they were at `30909e5`. Observed: a lit engine beside a chest cart of three coal, and
beside a hopper cart of two charcoal, went from 1000 to 992 fuel over four seconds parked (its own
idle burn) with the coal untouched; a moving train with two coal in the cart behind ran dry after
two seconds; the rule answers "Incorrect argument". The one world that ever held the rule, the
private test copy, logs it as a stale saved key until it is dropped.

## S2 — The tender cart and its item — **in progress**

**Build.** Add `vehicle` to `kalic0re_shared_packages`. A `CoalTenderMinecart extends
AbstractMinecartContainer implements CartCargo`, registered through
`CargoMinecarts.register(MOD_ID, "coal_tender", …, EntityTypes.MINECART)` with the container-cart
builder settings (`CargoMinecarts.java:143-150`), its load drawn through `CartCargoSupport`. Not on
kalic0re's `CargoMinecartChest`: that is a 27-slot chest whose purpose is a per-cart cargo component
and `Cargo` NBT, and a tender has a fixed load and five slots. From it the tender takes the
main-hand-only `interact` (`CargoMinecartChest.java:86-91`), which answers the plain click a cart
with riders sends twice; this mod's own `MinecartHelper.useMinecart` dedupes only the shift-click
gestures it consumes, so the two do not overlap. Five slots on vanilla's `HOPPER` menu, taking only
`furnace_minecart_fuel`: `canPlaceItem` for hoppers (`HopperBlockEntity.java:300-311`), and a copy
of `HopperMenu` whose slots refuse anything else in `mayPlace`, since `HopperMenu` builds plain
`Slot`s. The item is a `PolymerMinecartItem` falling back to `Items.MINECART` for
vanilla clients. Recipe (shapeless: minecart and coal block) as hand-written JSON, since this mod
has no datagen; lang keys for the item and the entity; a creative-tab entry; the dispenser behaviour
(`CargoMinecarts.registerDispenseBehaviour`, without which dispensers will not place it); breaking
drops the tender item and spills the contents as a chest cart does. `fabric.mod.json` gains
`polymer-virtual-entity`, which `LinkChain` already uses without declaring.

**Done when.** A tender crafts, places on rail and couples with the wrench; its menu takes coal and
charcoal and refuses anything else, by hand and from a hopper above; a vanilla client sees a plain
minecart; broken, it drops the tender item and spills its contents; after a restart it comes back
coupled and with its contents.

## S3 — Feeding at the burn rate — **done**

**Build.** The tender keeps `burnLeft`, the ticks left in the coal it has opened (NBT, never
synched). `MinecartFurnaceMixin`'s tick hook runs before vanilla's `fuel--`
(`MinecartFurnace.java:50-58`): there, a lit engine whose fuel is under a floor of 20 ticks draws
the shortfall from a tender coupled to it — out of `burnLeft`, opening the next coal or charcoal for
`MinecartFurnaceAccessor.serversidetrains$fuelTicksPerItem()` ticks when that runs out. The engine
never needs room for a whole item, so the draw follows consumption whatever `furnace_max_burn_time`
is, and a parked engine, burning a tenth, draws a tenth. Coupled means what it means everywhere else
here: a direct neighbour, loaded, with `trainsEnabled` — the review of `079657a` found that path
skipping the last check. A cold engine is never lit: vanilla clears its impulse with the last fuel,
so it has no direction to leave in, and a parked train must not set off because its tender was
filled.

**Done when.** On the private server: a lit engine with 40 ticks of fuel and a tender of N coal runs
on for N × 3600 ticks, give or take a tick, its fuel never reaching zero; the tender spends one coal
per 3600 ticks driving and per about 36 000 parked; with `furnace_max_burn_time` at 1000 and at 3600
it is still fed; a cold engine stays cold; a tender one car away, a chest cart, and any tender with
`trains_enabled` off feed nothing.

### As built

As planned: `CoalTenderMinecart.feed` walks the engine's coupled, loaded tenders and `draw` hands
over burn a tick at a time out of `BurnLeft`, opening coal or charcoal for
`FurnaceFuel.ticksPerCoal()` — a new one-line home for the accessor — as it runs out. The engine's
hook sits after the parked idle refund, so a parked engine is topped up only for the tick it really
spends.

The run of N × 3600 ticks was measured as a rate rather than waited out: the sum of the engine's
fuel, the tender's `BurnLeft` and 3600 per unopened coal is what is left to burn, and it fell by
exactly 50 over 50 ticks driving (51 over 51 in another run), and by 215 over 2154 sprinted ticks
parked on a brake, 0.100 a tick. The engine's fuel held at 19 once the tender took over — the floor
less the tick vanilla had just burned — and never went lower; the coal was opened the tick the
floor was reached. With `furnace_max_burn_time` at 1000 and at 3600 the figures were the same to the
tick. A cold engine with a tender of coal sat still with the coal untouched; a tender one car away,
a chest cart in its place, and a tender with `trains_enabled` off each let the engine run its own
40 ticks dry and fed nothing.

## S4 — The coal load on screen — **planned**

**Build.** A `CargoDisplayHolder` subclass with one item display of `minecraft:coal_block`, attached
through `CartCargoSupport` with vanilla's slot blanked to air. A full load runs from the floor to
1 px under the rim, 3 → 10 px above the cart's position: centre 0.40625, scale about
(1.0, 0.4375, 0.75) along the cart's length, height and width, each trimmed a hair so no face lands
on a wall (sensible-storage trimmed its shulker box from 0.75 to 0.745 for exactly that). The load
rises with the contents in whole pixels: its height is the fill — coal and charcoal held, plus the
one being burned, over the 320 five slots hold — of 7 px, rounded up, so any coal shows at least a
pixel and an empty tender shows none. The holder reads the fill each tick and asks for a re-pose
(`markPoseDirty`) only when the height changes. An item display of a
`cube_all` block renders centred at 1:1, so no corner correction is needed, unlike a block display.
Posed in the constructor; interpolation 0, as on sensible-storage's chest base, which slid inside
the cart when eased.

**Done when (observed in game, vanilla client).** A full tender shows coal level to one pixel below
the rim with no flicker against the walls, it stays put in the cart on straight track, a curve and a
slope at speed, and it drops a pixel at a time as the tender empties, gone when it is. A client
running this mod sees the same — it is sent the same path.

## S5 — Icon and documentation — **planned**

**Build.** A 16 × 16 inventory icon (a minecart with coal), its `item/generated` model and `items/`
definition in this mod's resource pack, and the item's model id. README: a "Coal tender" section,
and the rules table without the row S1 removes.

**Done when.** A vanilla client holding the server's resource pack sees the icon in hand and in the
inventory; the README describes the tender.

## Open decisions

- **Icon art.** Hand-drawn, or composited by a script from vanilla's minecart icon and a coal
  overlay, as sensible-storage does for its carts. Settled at S5.

Decided by the user on 2026-10-04, and written into the stages: a tender only keeps a lit engine
lit and never lights a cold one (S3); there is no rule (S1); the tender holds 5 slots behind a
hopper menu (S2); and the load rises with the contents (S4).

## Reference: minecart interior

From `MinecartModel.createBodyLayer` (`MinecartModel.java:20-48`), drawn under `scale(-1, -1, 1)`
(`AbstractMinecartRenderer.java:69`) in a frame lifted 6 px and turned by the cart's yaw and pitch
(`:74-78`). In pixels above the cart's position, cart frame (X along the cart, Z across):

| Part | Extent |
|---|---|
| Floor slab | y 1 → 3, 20 × 16 outer |
| Walls | y 3 → 11; front and back at \|x\| 8 → 10, sides at \|z\| 6 → 8 |
| Interior | 16 (X −8 → 8) × 12 (Z −6 → 6) × 8 (y 3 → 11) |
| Rim | 11 px (0.6875); the hit box is 0.7 tall |
| Floor top | 3 px — the 0.1875 passenger point kalic0re mounts cargo at |
| Load (S4) | y 3 → 10, centre 6.5 px (0.40625) |

A 12 px interior width is exactly a 0.75 block, which is why a full-width load needs trimming.
