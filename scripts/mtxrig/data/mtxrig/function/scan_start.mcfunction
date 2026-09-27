# Starts one pass at the square's north-west corner. Called with the radius and side as arguments.
$scoreboard players set #cols_max mtxrig $(w)
$scoreboard players set #rows mtxrig $(w)
$execute positioned ~-$(r) ~ ~-$(r) run function mtxrig:scan_row
