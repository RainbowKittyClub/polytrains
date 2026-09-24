# Clears one group. Called as /function mtxrig:reset {group:"<name>"}; mtxrig:reset_all takes every
# group at once.
#
# Emptied before they are killed: a container cart drops its inventory when it dies, and the hopper
# cars will have swept up every chain a severed coupling dropped since the last reset, so killing
# them first just puts those chains back on the track.
$execute as @e[tag=mtx,tag=mtx_$(group),type=minecraft:hopper_minecart] run data modify entity @s Items set value []
$execute as @e[tag=mtx,tag=mtx_$(group),type=minecraft:chest_minecart] run data modify entity @s Items set value []

# Tag-scoped twice over, so neither another group's train nor anything the rig did not place is
# touched.
$kill @e[tag=mtx,tag=mtx_$(group)]

# Whatever was already lying loose. A coupling severed by a wreck drops the iron chain it was made
# with, and those pile up on the track across a session of resets. Not group-scoped: a chain on the
# ground belongs to nothing, so there is no group to ask it for.
kill @e[type=minecraft:item,nbt={Item:{id:"minecraft:iron_chain"}}]

# Working tags, in case a run was interrupted partway.
tag @e remove mtx_prev
tag @e remove mtx_new
tag @e remove mtx_fresh
kill @e[tag=mtx_anchor]
