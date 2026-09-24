# Links the cart just placed to the one placed before it, when the two are close enough to be one
# train. A gap wider than the coupling's own reach starts a new train instead, which is how several
# trains come out of one sweep.
execute as @e[tag=mtx_new,limit=1] at @s if entity @e[tag=mtx_prev,distance=..3] run function mtxrig:couple_pair
tag @e[tag=mtx_prev] remove mtx_prev
tag @e[tag=mtx_new] add mtx_prev
tag @e[tag=mtx_new] remove mtx_new
