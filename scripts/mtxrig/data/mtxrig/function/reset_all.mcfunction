# Every group at once, for clearing the yard rather than one train set. Takes no argument.
execute as @e[tag=mtx,type=minecraft:hopper_minecart] run data modify entity @s Items set value []
execute as @e[tag=mtx,type=minecraft:chest_minecart] run data modify entity @s Items set value []
kill @e[tag=mtx]
kill @e[type=minecraft:item,nbt={Item:{id:"minecraft:iron_chain"}}]
tag @e remove mtx_new
tag @e remove mtx_mate
tag @e remove mtx_fresh
kill @e[tag=mtx_anchor]
