# One west-to-east row, then step south and recurse. Positioned at the row's western end.
scoreboard players operation #cols mtxrig = #cols_max mtxrig
function mtxrig:scan_cell
scoreboard players remove #rows mtxrig 1
execute if score #rows mtxrig matches 1.. positioned ~ ~ ~1 run function mtxrig:scan_row
