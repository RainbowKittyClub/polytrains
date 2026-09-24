# The four orthogonal neighbours of one anchor. Marked spread first, so it is not asked again on the
# next generation.
tag @s add mtx_spread
execute positioned ~1 ~ ~ run function mtxrig:spread_to
execute positioned ~-1 ~ ~ run function mtxrig:spread_to
execute positioned ~ ~ ~1 run function mtxrig:spread_to
execute positioned ~ ~ ~-1 run function mtxrig:spread_to
