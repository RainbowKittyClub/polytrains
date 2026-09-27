# Places one group's trains: a search for its signs round where it runs, the strip growth from them,
# then a cart on every cell the growth reached.
$data modify storage mtxrig:args group set value "$(group)"

# Anchors left by an interrupted run would widen this group's strips into someone else's.
kill @e[tag=mtx_anchor]
scoreboard players set #unlinked mtxrig 0

# Pass one anchors the cell under every [mtxrig] sign whose second line names this group.
scoreboard players set #pass mtxrig 1
function mtxrig:search

# Then the rest of each signed strip, one block per generation outward from the sign.
scoreboard players set #spread mtxrig 32
function mtxrig:spread

# A cart on every anchored cell, each coupled to the carts already standing beside it, so a strip
# comes out as one train whichever order the anchors are taken in.
execute as @e[tag=mtx_anchor] at @s run function mtxrig:place

# Named after the fact, once, rather than per cart at summon time: the group is only known as a
# macro argument, and a macro line costs more than a tag does.
$tag @e[tag=mtx_fresh] add mtx_$(group)
tag @e[tag=mtx_fresh] remove mtx_fresh
kill @e[tag=mtx_anchor]
