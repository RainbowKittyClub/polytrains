# Runs one pass over the square round wherever it is called, from the middle of that block: a player
# standing by the rig, or a place given with execute positioned. rcon runs at the world spawn.
execute align xyz positioned ~0.5 ~ ~0.5 run function mtxrig:scan_start with storage mtxrig:search
