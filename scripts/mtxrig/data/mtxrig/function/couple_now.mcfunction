# Run as the cart just placed, at its own position. Couples it to every cart this spawn has already
# placed on an orthogonally neighbouring cell, so a strip becomes one train whichever way it runs or
# turns, and the order its cars are placed in does not matter.
#
# Exact distances: no tick passes inside one spawn, so every cart still stands where it was summoned,
# at the centre of its cell and at its strip's rail level, even on a slope.
execute positioned ~1 ~ ~ if entity @e[tag=mtx_fresh,tag=!mtx_new,distance=..0.6,limit=1] run function mtxrig:couple_pair
execute positioned ~-1 ~ ~ if entity @e[tag=mtx_fresh,tag=!mtx_new,distance=..0.6,limit=1] run function mtxrig:couple_pair
execute positioned ~ ~ ~1 if entity @e[tag=mtx_fresh,tag=!mtx_new,distance=..0.6,limit=1] run function mtxrig:couple_pair
execute positioned ~ ~ ~-1 if entity @e[tag=mtx_fresh,tag=!mtx_new,distance=..0.6,limit=1] run function mtxrig:couple_pair

# Two cells on only across a sign standing on the rail in the line, which takes a cell and places no
# cart. A parallel strip two blocks over has no such sign between, so it stays a train of its own.
execute if block ~1 ~ ~ #minecraft:rails if block ~1 ~1 ~ #minecraft:all_signs positioned ~2 ~ ~ if entity @e[tag=mtx_fresh,tag=!mtx_new,distance=..0.6,limit=1] run function mtxrig:couple_pair
execute if block ~-1 ~ ~ #minecraft:rails if block ~-1 ~1 ~ #minecraft:all_signs positioned ~-2 ~ ~ if entity @e[tag=mtx_fresh,tag=!mtx_new,distance=..0.6,limit=1] run function mtxrig:couple_pair
execute if block ~ ~ ~1 #minecraft:rails if block ~ ~1 ~1 #minecraft:all_signs positioned ~ ~ ~2 if entity @e[tag=mtx_fresh,tag=!mtx_new,distance=..0.6,limit=1] run function mtxrig:couple_pair
execute if block ~ ~ ~-1 #minecraft:rails if block ~ ~1 ~-1 #minecraft:all_signs positioned ~ ~ ~-2 if entity @e[tag=mtx_fresh,tag=!mtx_new,distance=..0.6,limit=1] run function mtxrig:couple_pair
