# AbstractMinecartExt rationale

Why each accessor on `ext/AbstractMinecartExt.java` exists and is shaped the way it is. The
interface's own Javadoc states each accessor's contract; this document is where the reasoning
behind that contract lives instead, per STYLE.md's Javadoc section.

## Driving state

`polytrains$driving()` and `polytrains$driveAcceleration()` are asked separately because
the acceleration figure falls with the cart's own speed: an engine near its free-running speed puts
out almost nothing, so reading the output as a yes-or-no would answer "not powered" for a cart that
plainly is. Everything that wants the fact rather than the figure asks `driving()`.
`driveAcceleration()` is a live reading taken now, not a cached property.

## Engine aiming

`polytrains$turnEngine(Vec3)` is unconditional, unlike `polytrains$aimEngine(Vec3)`: it
exists specifically to turn an engine round, so the refusals that normally keep a heading from
being knocked about don't apply.

`polytrains$aimEngine(Vec3)` exists because a seated cart never runs vanilla's stepper, and
that stepper is the only thing that re-aims an engine off the ground it covered — so an engine in a
placed train would otherwise keep the heading it had before it was first seated, through every
corner after it. Refusing a reversal stays the engine's own business; this only offers the heading.

## Swept box

`polytrains$sweptBox()` is read once a tick and held for the same reason
`polytrains$railContext()` is: collision asks each of a pair of carts about the other
separately, and a box read live would be read on either side of the cart having moved, giving one
of the pair a wall the other didn't have.

## Nose heading

`polytrains$nose()` is remembered rather than derived from the train's direction of travel: a
cart can't turn round on a rail, and two cars of one train may honestly face opposite ways.

It's held as a direction rather than as a sign along some track, because a sign only means anything
while that track keeps its numbering — and a ribbon is thrown away and re-recorded often enough to
discard this with it every time.
