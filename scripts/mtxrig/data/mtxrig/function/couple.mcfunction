# Called once per anchored cell, whether or not a cart was placed there: a sign's cell places none,
# and then there is no mtx_new cart for the execute to run as.
execute as @e[tag=mtx_new,limit=1] at @s run function mtxrig:couple_now
tag @e[tag=mtx_new] remove mtx_new
tag @e[tag=mtx_mate] remove mtx_mate
