# Starts one pass at the box's north-west corner. Called with the box as its arguments.
$scoreboard players set #cols_max mtxrig $(w)
$scoreboard players set #rows mtxrig $(d)
$execute positioned $(x) $(y) $(z) run function mtxrig:scan_row
