# A sign names the strip it belongs to. All but a handful of the box's cells have no sign over them
# at either height one is read from, so that is the first thing asked and the only cost they pay.
execute unless block ~ ~1 ~ #minecraft:all_signs unless block ~ ~2 ~ #minecraft:all_signs run return 0

# On the strip itself: standing in the marker line in place of one of its blocks, or one higher
# standing on one. Either way the rail under the sign is the cell to anchor.
execute if block ~ ~ ~ #minecraft:rails if block ~ ~1 ~ #minecraft:all_signs run function mtxrig:seed_read_1
execute if block ~ ~ ~ #minecraft:rails if block ~ ~2 ~ #minecraft:all_signs if block ~ ~1 ~ #mtxrig:markers run function mtxrig:seed_read_2

# Off the strip, on whatever it suits to stand a sign on — the ground beside the track, a post, a
# wall. It names the marked rail it abuts instead, so naming a strip costs none of its own cells.
execute unless block ~ ~ ~ #minecraft:rails if block ~ ~1 ~ #minecraft:all_signs run function mtxrig:seed_beside_1
execute unless block ~ ~1 ~ #mtxrig:markers if block ~ ~2 ~ #minecraft:all_signs run function mtxrig:seed_beside_2
