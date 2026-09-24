# The four cells a sign standing clear of the track could be naming, each taken only if it is marked
# rail — the sign is off the strip, so unlike the in-strip cases nothing has vouched for the cell
# yet. Anchoring every match rather than the first is deliberate: on an inside corner two of them are
# the same strip, which the spread's own visited check folds back together, and a sign wedged between
# two strips has honestly named both.
execute positioned ~1 ~ ~ if block ~ ~ ~ #minecraft:rails if block ~ ~1 ~ #mtxrig:markers run function mtxrig:seed_match with storage mtxrig:args
execute positioned ~-1 ~ ~ if block ~ ~ ~ #minecraft:rails if block ~ ~1 ~ #mtxrig:markers run function mtxrig:seed_match with storage mtxrig:args
execute positioned ~ ~ ~1 if block ~ ~ ~ #minecraft:rails if block ~ ~1 ~ #mtxrig:markers run function mtxrig:seed_match with storage mtxrig:args
execute positioned ~ ~ ~-1 if block ~ ~ ~ #minecraft:rails if block ~ ~1 ~ #mtxrig:markers run function mtxrig:seed_match with storage mtxrig:args
