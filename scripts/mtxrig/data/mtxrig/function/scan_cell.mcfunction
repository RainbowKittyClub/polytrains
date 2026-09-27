# One column, then step east and recurse: signs 8 below where the search began to 8 above. The height
# goes to at_sign as an argument, since each modifier in a line costs a command even once one failed.
execute if block ~ ~-8 ~ #minecraft:all_signs run function mtxrig:at_sign {dy:-8}
execute if block ~ ~-7 ~ #minecraft:all_signs run function mtxrig:at_sign {dy:-7}
execute if block ~ ~-6 ~ #minecraft:all_signs run function mtxrig:at_sign {dy:-6}
execute if block ~ ~-5 ~ #minecraft:all_signs run function mtxrig:at_sign {dy:-5}
execute if block ~ ~-4 ~ #minecraft:all_signs run function mtxrig:at_sign {dy:-4}
execute if block ~ ~-3 ~ #minecraft:all_signs run function mtxrig:at_sign {dy:-3}
execute if block ~ ~-2 ~ #minecraft:all_signs run function mtxrig:at_sign {dy:-2}
execute if block ~ ~-1 ~ #minecraft:all_signs run function mtxrig:at_sign {dy:-1}
execute if block ~ ~ ~ #minecraft:all_signs run function mtxrig:at_sign {dy:0}
execute if block ~ ~1 ~ #minecraft:all_signs run function mtxrig:at_sign {dy:1}
execute if block ~ ~2 ~ #minecraft:all_signs run function mtxrig:at_sign {dy:2}
execute if block ~ ~3 ~ #minecraft:all_signs run function mtxrig:at_sign {dy:3}
execute if block ~ ~4 ~ #minecraft:all_signs run function mtxrig:at_sign {dy:4}
execute if block ~ ~5 ~ #minecraft:all_signs run function mtxrig:at_sign {dy:5}
execute if block ~ ~6 ~ #minecraft:all_signs run function mtxrig:at_sign {dy:6}
execute if block ~ ~7 ~ #minecraft:all_signs run function mtxrig:at_sign {dy:7}
execute if block ~ ~8 ~ #minecraft:all_signs run function mtxrig:at_sign {dy:8}
scoreboard players remove #cols mtxrig 1
execute if score #cols mtxrig matches 1.. positioned ~1 ~ ~ run function mtxrig:scan_cell
