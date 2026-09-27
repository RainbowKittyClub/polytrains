# One sign the search found, dy above where it runs. Which pass is running decides what it is for.
#
# A sign names the rail one below it, standing in the strip's marker line or beside it, or two below,
# standing on a marker or a post; seed tells which from that rail, so both are asked.
$execute if score #pass mtxrig matches 1 positioned ~ ~$(dy) ~ positioned ~ ~-1 ~ run function mtxrig:seed
$execute if score #pass mtxrig matches 1 positioned ~ ~$(dy) ~ positioned ~ ~-2 ~ run function mtxrig:seed
$execute if score #pass mtxrig matches 3 positioned ~ ~$(dy) ~ run function mtxrig:find_sign
