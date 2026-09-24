# A cart goes here only where there is track to stand on, a marker directly above it, and an
# anchor this group's sign reached. Every other marker strip in the box is left alone.
execute unless entity @e[tag=mtx_anchor,distance=..0.4] run return 0

# The rail's own shape decides which way the cart is laid, and it is decided before the summon
# rather than corrected after: a rotation written with /data never reaches the client, so a cart
# fixed up afterwards is right on the server and still drawn across its rail in game. The spawn
# packet carries rotation, so the only reliable place to set it is the summon itself.
#
# Matched through #minecraft:rails, which takes a state predicate on the tag - so this covers
# powered, detector and activator rail, and this mod's junction and switch rail, all of which name
# the property "shape".
execute if block ~ ~ ~ #minecraft:rails[shape=north_south] run function mtxrig:place_ns
execute if block ~ ~ ~ #minecraft:rails[shape=ascending_north] run function mtxrig:place_ns
execute if block ~ ~ ~ #minecraft:rails[shape=ascending_south] run function mtxrig:place_ns
execute if block ~ ~ ~ #minecraft:rails[shape=east_west] run function mtxrig:place_ew
execute if block ~ ~ ~ #minecraft:rails[shape=ascending_east] run function mtxrig:place_ew
execute if block ~ ~ ~ #minecraft:rails[shape=ascending_west] run function mtxrig:place_ew
# A curve joins one north-south leg to one east-west leg; either is a fair way to face, so the
# east-west leg is taken for all four of them.
execute if block ~ ~ ~ #minecraft:rails[shape=south_east] run function mtxrig:place_ew
execute if block ~ ~ ~ #minecraft:rails[shape=south_west] run function mtxrig:place_ew
execute if block ~ ~ ~ #minecraft:rails[shape=north_west] run function mtxrig:place_ew
execute if block ~ ~ ~ #minecraft:rails[shape=north_east] run function mtxrig:place_ew
