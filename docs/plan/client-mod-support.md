# Client-optional chain rendering

A client that installs this same jar draws the chain between linked carts itself, in its own
render frame, from the same interpolated pose the carts are drawn at — instead of receiving a
server-posed block display that trails the carts by about three and a half ticks. Vanilla clients
are unchanged and keep the display.

Every API claim below was checked against `libs/decompiledjava/minecraft-26.2/`, against the
nested jars inside Fabric API `0.157.0+26.2` (with `javap` on the shipped classes, not the wiki),
and against the Polymer checkout at `libs/polymer/`. Nothing here has been built or run; the
tuning numbers quoted from `LinkChain` are the ones currently live, and everything stated about
how the new renderer will *look* is a prediction until S3 is on screen.

Stages run in one chain: S1 creates the client side and the per-connection flag, S2 gets the link
graph to those clients, S3 draws the chain from it, and S4 stops sending them the display. S4 is
the one that makes the feature complete, and it must not land before S3 — a client that has
stopped receiving the display and cannot yet draw its own shows linked carts with nothing between
them.

---

## Findings that reshape the work

**The cargo half of this task belongs to `sensible-storage`, not here.** The TODO entry reads
"minecarts with cargo and chains", but this mod has no cargo code at all — a grep for
`displayBlock` or `Cargo` over `src/main/java` returns nothing. Cart cargo is drawn by `kalic0re`'s
`common/vehicle/CargoDisplayHolder`, which only `sensible-storage` shades, and the fix for its lag
is already scoped in that mod's `docs/plan/client-mod-support.md` under "Follow-up: a chest
*inside a cart*". This document covers chains only, and the two tracks share nothing but the
handshake pattern.

**There is no world-render callback to draw the chain from.** `fabric-rendering-v1-25.3.2` ships
no `WorldRenderEvents` and no `WorldRenderContext`; the 26.x render rework around
`SubmitNodeCollector` removed the free-standing hook, and what is left in
`net.fabricmc.fabric.api.client.rendering.v1` is per-thing registries. So the chain has to be
drawn from inside a minecart renderer this mod installs over vanilla's, via
`EntityRendererRegistry.register`. That registry is a bare `HashMap.put` into `EntityRenderers`'
provider map with no duplicate check (`EntityRendererRegistryImpl`), so this is an override with
no arbitration: any other client mod replacing the minecart renderer either loses this mod's chain
or has its own work thrown away, decided by Fabric's entrypoint order. Accepted, because there is
no alternative seam — but it is why S3 subclasses vanilla's renderer and overrides as little as it
can, rather than writing one from scratch.

**The synced link value cannot be the persisted one.** `LinkableData` holds two neighbour *UUIDs*
(`LinkableData.java`), which is right for the save file and useless on the client:
`ClientLevel.getEntity(int)` is public (`ClientLevel.java:549`) but `getEntities()`, the only
thing carrying `LevelEntityGetter.get(UUID)`, is `protected` (`:1072`). So S2 syncs a separate,
transient attachment holding two **network entity ids** rather than putting a `syncWith` codec on
`MTAttachments.LINK`. A client accessor mixin onto `ClientLevel` would also work and is rejected:
a second attachment is smaller, needs no mixin, and costs two ints per linked cart.

**Nothing needs a custom packet.** `AttachmentRegistry.Builder` has `syncWith(StreamCodec<? super
RegistryFriendlyByteBuf, A>, AttachmentSyncPredicate)`, and `AttachmentSyncPredicate` is a
`BiPredicate<AttachmentTarget, ServerPlayer>` — so the predicate can be `(target, player) ->
HANDSHAKE.has(player.connection)` and the link graph reaches handshake-positive clients only. This
mod has no networking code today and does not need any.

**The resource pack stays required, unlike in `sensible-storage`.** That mod dropped
`PolymerResourcePackUtils.markAsRequired()` when it went client-optional, which was right for it:
a mod client there gets real chests and never needs the pack. It is wrong here. The junction
rail's model and texture reach *every* client only through the pack (`Polytrains.java:58-59`),
and giving mod clients real rail block states is a different and much larger feature than chains.
Leave both calls alone.

---

## How these stages get tested

On this mod's own dev server and a dev client against it, so nothing collides with the other mods'
ports. Each stage is tested twice: once with the client running this jar, and once with a plain
vanilla client on the same server. The vanilla leg is not optional at any stage — vanilla clients
are the whole live audience, and every stage here touches a code path they share.

A stage is not finished until its "Done when" is observed. Build and lint prove nothing about any
of this: the handshake in S1, the sync predicate in S2 and the renderer override in S3 all fail at
runtime or not at all.

## S1 — Client entrypoint and handshake — **planned**

**Build.** Flip `fabric.mod.json`'s `"environment"` from `"server"` to `"*"` and add a `client`
entrypoint, `club.rainbowkitty.polytrains.client.PolytrainsClient`, alongside the existing
`main`. Add `net` to `kalic0re_shared_packages` in `gradle.properties` and stand up a
`PolymerClientHandshake` on `Polytrains.MOD_ID`, created as a `public static final` on
`Polytrains` and `register()`ed from `onInitialize` — which runs on both sides, so the same
call advertises the capability from the client and arms the flag on the server.

**Done when.** A dev client running this jar joins the dev server and a temporary log line at join
reports the handshake positive for that connection; a vanilla client joining the same server
reports it negative, and plays normally.

Polymer becomes a hard dependency on the client as well, which it already is in `depends`. Nothing
in `depends` changes — `polymer-core`, `polymer-blocks` and `polymer-resource-pack` are all wanted
on both sides.

The `kalic0re` share list currently reads `display` only. Adding `net` brings across one class. Do
not add `client` — this mod's client rendering is its own, and `common/client` is
`sensible-storage`'s chest renderer.

## S2 — Link graph synced to handshake-positive clients — **planned**

**Build.** A second attachment on `MTAttachments`, holding the two neighbour **entity ids** as
ints (absent slot as a sentinel, not a boxed optional — it is written every time a link changes
and read every frame). Built with `AttachmentRegistry.builder()`, `syncWith(...)` on a
hand-written `StreamCodec`, and a predicate of `(target, player) ->
Polytrains.HANDSHAKE.has(player.connection)`. Not persistent: entity ids do not survive a
restart, and `LINK` already carries what does. Write it from wherever `Linkable.link`/`unlink`
land, so the two can never disagree.

**Done when.** With two carts linked in-world, a temporary client-side log line on the dev client
names both neighbour ids and resolves each through `ClientLevel.getEntity(int)` to the right cart;
unlinking clears it within a tick. A vanilla client on the same server receives no such attachment
data — confirm by the absence of the sync payload rather than by the absence of a visible effect.

The open question this stage resolves by building it: whether the attachment sync fires promptly
enough on a *change* to keep the chain from lagging a link or unlink by more than a tick. Fabric
syncs on write, but the write happens in the link code rather than on the tick boundary, and the
two have never been observed together.

`MTAttachments`' class Javadoc already points here for this; update it to say what was built when
the stage lands.

## S3 — Chain drawn from a client-side minecart renderer — **planned**

**Build.** Six of the seven cart types use plain `MinecartRenderer` with a different model layer
(`EntityRenderers.java:93`, `:96`, `:130`, `:144`, `:164`, `:221`); TNT uses `TntMinecartRenderer`
(`:239`). So: one subclass of `MinecartRenderer` covering the six, one of `TntMinecartRenderer`,
both registered from the client entrypoint through `EntityRendererRegistry.register` for their
respective `EntityTypes` constants. Each overrides `createRenderState` to return a state subclass
carrying the chain geometry, `extractRenderState` to compute it (that is the only hook holding the
entity), and `submit` to draw it after `super.submit`.

The geometry is `LinkChain.aim()`'s, moved to a shared helper and fed live values instead of
delayed ones: `centre`, `coupler`, `lengthwise` and `pointAlong` are already static and
side-agnostic, and the two `RenderTrail`s and the `RENDER_LAG` constant simply drop out. The
neighbour's pose comes from its `NewMinecartBehavior`'s
`getCartLerpPosition/XRot/YRot(partialTicks)` — all public, all the same
methods vanilla's own `newExtractState` uses (`AbstractMinecartRenderer.java:126-128`), so both
carts are read at one `partialTicks` and cannot drift by construction.

Draw each link from one end only: the cart whose entity id is the lower of the two. The chain is
symmetric geometry, the rule needs nothing the client does not already have, and it cannot
double-draw.

**Done when.** On the dev client, a train taken round a bend at full speed shows the chain meeting
both couplers with no visible slack or overshoot at any speed, including the moment a car crests a
slope — which is the case `LinkChain.centre` exists for and the one the delayed samples handle
worst. That is the actual requirement. A vanilla client watching the same train at the same time
must look exactly as it does today.

Two traps. The chain is currently a `BlockDisplayElement` of `Blocks.IRON_CHAIN`, scaled on Y; the
client path draws a block model into a `PoseStack` instead, so the model's own `(0.5, *, 0.5)`
axis offset that `LinkChain.MODEL_AXIS` cancels has to be cancelled again, differently. And
`getBoundingBoxForCulling` is per-entity: a chain spanning toward a neighbour outside the frustum
will be culled with its owner unless the box is expanded toward the neighbour.

## S4 — Server chain withheld from those clients — **planned**

**Build.** Override `LinkChain.startWatching(ServerGamePacketListenerImpl)` to return `false` when
the handshake is positive for that connection, as `ElementHolder` allows
(`libs/polymer/…/ElementHolder.java:90`) and `sensible-storage`'s `ChestElementHolder` already
does for placed chests. The holder still exists and still ticks — only the connections that can
draw their own chain stop receiving it.

**Done when.** A mod client and a vanilla client watch the same train side by side: one chain
each, neither doubled, and the mod client's tracks the carts through a bend while the vanilla
client's trails them as it does today. Relogging on the mod client does not produce a second
chain.

Leave the `ServerEntityMixin` train resync in place. It exists so a whole train's position batches
land on one beat, which the vanilla client's display chain needs and which also keeps the cars
themselves from stuttering relative to each other — that second effect is for every client.

## Open decisions

**Whether the junction and switch rails also go real-state for mod clients.** Out of scope here
and deliberately not stage-numbered: it is a much larger change than chains, it would put
`markAsRequired` back in question, and nothing about the chain track depends on it. Settled by
someone wanting an accurate rail hitbox enough to pay for it.

**Whether `sensible-storage`'s cargo carts get chains.** If that mod ever hands a mod client the
real `sensiblestorage:chest_minecart` entity type, as its own plan doc's follow-up describes, that
type carries no renderer registered here and would show no chain on exactly the clients this work
is for. Settled when that follow-up is picked up; whoever does it should register this mod's
renderer for that type too, which means the renderer class cannot be package-private.

**The `supportsAll` id-parity bug.** `sensible-storage`'s plan doc records a join-time kick caused
by Fabric's per-launch entrypoint shuffle changing Polymer's raw item ids, with *this mod's* three
Polymer items as the thing that shifts them. Nothing in this document relies on `supportsAll` —
the handshake here is used only for `has`, which is a per-key answer and is not affected. Worth
knowing before anyone extends the handshake use here, and settled by that mod's fix, not by this
one.

## Reference: the three-tick pipeline the display is fighting

The lag `LinkChain.RENDER_LAG = 3.5` compensates for (`LinkChain.java:34`) is the sum of two
mechanisms, both measured here rather than estimated.

`ServerEntity` sends on `tickCount % updateInterval == 0` (`ServerEntity.java:125`), and
`updateInterval` defaults to 3 (`EntityType.java:482`) — `EntityTypes.MINECART` does not override
it (`EntityTypes.java:665`), nor do the other six cart types. When the cart has accumulated lerp
steps the whole batch goes in one `ClientboundMoveMinecartPacket` (`ServerEntity.java:253`).

The client appends that batch to `NewMinecartBehavior.lerpSteps`, then replays it one step per
tick: `lerpClientPositionAndRotation` sets `lerpDelay = 3` on picking a batch up
(`NewMinecartBehavior.java:77`) and counts down, and `getCartLerpPosition(partialTicks)`
reads `(3 - lerpDelay + partialTicks) / 3` through that batch. `POS_ROT_LERP_TICKS` is `3` (`:30`).

So the client is drawing the cart roughly three ticks behind where the server has it, plus the
hop. A server-posed display element is posed at server truth and therefore *leads* the cart the
client draws — which is why the fix is to sample the past, and why `RENDER_LAG` is slightly over
three. A client-drawn chain reads the same `getCartLerp*` values the cart itself is drawn from, so
none of this applies to it: the compensation is a property of the stand-in, not of carts.

## Reference: the seams this work stands on

| Seam | Where | Verified |
| --- | --- | --- |
| `EntityRendererRegistry.register` | `fabric-rendering-v1-25.3.2` | `javap`; unguarded map put |
| `AttachmentRegistry.Builder.syncWith` | `fabric-data-attachment-api-v1-2.2.17` | `javap` |
| `AttachmentSyncPredicate` is a `BiPredicate` | same | `javap` |
| `PolymerClientHandshake.create/register/has` | `kalic0re` `common/net` | source |
| `ElementHolder.startWatching` returns `boolean` | `…/ElementHolder.java:90` | source |
| `AbstractMinecart.getBehavior()` public | `AbstractMinecart.java:147` | decompile |
| `NewMinecartBehavior.getCartLerp*(float)` public | `NewMinecartBehavior.java:98-113` | decompile |
| `MinecartRenderer`/`TntMinecartRenderer` extendable | `EntityRenderers.java:93-239` | decompile |
| `submitMinecartContents` protected | `AbstractMinecartRenderer.java:159` | decompile |
| `getEntity(int)` public, `getEntities()` protected | `ClientLevel.java:549`, `:1072` | decompile |

No world-render event exists in `fabric-rendering-v1-25.3.2`; that absence was checked by listing
the jar, and is the reason S3 takes the renderer-override route.
