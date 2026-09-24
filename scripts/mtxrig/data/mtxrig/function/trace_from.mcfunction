# The four orthogonal neighbours, each at the same height and one either side of it, so a slope is
# followed rather than read as the end of the track.
tag @s add mtx_traced
execute positioned ~1 ~ ~ run function mtxrig:trace_to
execute positioned ~-1 ~ ~ run function mtxrig:trace_to
execute positioned ~ ~ ~1 run function mtxrig:trace_to
execute positioned ~ ~ ~-1 run function mtxrig:trace_to
execute positioned ~1 ~1 ~ run function mtxrig:trace_to
execute positioned ~-1 ~1 ~ run function mtxrig:trace_to
execute positioned ~ ~1 ~1 run function mtxrig:trace_to
execute positioned ~ ~1 ~-1 run function mtxrig:trace_to
execute positioned ~1 ~-1 ~ run function mtxrig:trace_to
execute positioned ~-1 ~-1 ~ run function mtxrig:trace_to
execute positioned ~ ~-1 ~1 run function mtxrig:trace_to
execute positioned ~ ~-1 ~-1 run function mtxrig:trace_to
