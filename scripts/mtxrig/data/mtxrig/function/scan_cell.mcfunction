# One rail block, then step east and recurse. Which pass is running decides what the cell is for.
execute if score #pass mtxrig matches 1 run function mtxrig:seed
execute if score #pass mtxrig matches 2 run function mtxrig:place
execute if score #pass mtxrig matches 3 run function mtxrig:find_cell
scoreboard players remove #cols mtxrig 1
execute if score #cols mtxrig matches 1.. positioned ~1 ~ ~ run function mtxrig:scan_cell
