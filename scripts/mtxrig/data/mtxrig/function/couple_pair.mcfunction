# Run as the new cart, at its own position. The slots are unordered, so the new car takes the one
# still free on each side: its own slotA back to its neighbour, and the neighbour's slotB forward.
data modify entity @s "fabric:attachments"."minecarttweaks:link".slotA set from entity @e[tag=mtx_prev,limit=1,sort=nearest,distance=..3] UUID
execute as @e[tag=mtx_prev,limit=1,sort=nearest,distance=..3] run data modify entity @s "fabric:attachments"."minecarttweaks:link".slotB set from entity @e[tag=mtx_new,limit=1] UUID
