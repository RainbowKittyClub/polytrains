# Run as the new cart, positioned on the cell of a cart placed before it. The slots are unordered, so
# each end takes whichever of its own is still free, filling slotA before slotB.
tag @e[tag=mtx_mate] remove mtx_mate
tag @e[tag=mtx_fresh,tag=!mtx_new,distance=..0.6,limit=1,sort=nearest] add mtx_mate

# Both ends are asked before either is written, so a coupling is never recorded by one cart alone.
execute if data entity @s "fabric:attachments"."polytrains:link".slotA if data entity @s "fabric:attachments"."polytrains:link".slotB run return run function mtxrig:couple_warn
execute as @e[tag=mtx_mate] if data entity @s "fabric:attachments"."polytrains:link".slotA if data entity @s "fabric:attachments"."polytrains:link".slotB run return run function mtxrig:couple_warn

# slotB is written first and only behind a full slotA, so the slotA line after it cannot fire too.
execute if data entity @s "fabric:attachments"."polytrains:link".slotA unless data entity @s "fabric:attachments"."polytrains:link".slotB run data modify entity @s "fabric:attachments"."polytrains:link".slotB set from entity @e[tag=mtx_mate,limit=1] UUID
execute unless data entity @s "fabric:attachments"."polytrains:link".slotA run data modify entity @s "fabric:attachments"."polytrains:link".slotA set from entity @e[tag=mtx_mate,limit=1] UUID
execute as @e[tag=mtx_mate] if data entity @s "fabric:attachments"."polytrains:link".slotA unless data entity @s "fabric:attachments"."polytrains:link".slotB run data modify entity @s "fabric:attachments"."polytrains:link".slotB set from entity @e[tag=mtx_new,limit=1] UUID
execute as @e[tag=mtx_mate] unless data entity @s "fabric:attachments"."polytrains:link".slotA run data modify entity @s "fabric:attachments"."polytrains:link".slotA set from entity @e[tag=mtx_new,limit=1] UUID
