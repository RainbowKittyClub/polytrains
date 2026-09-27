# Sets how far the rig looks for signs round wherever it runs, e.g. /function mtxrig:radius {r:24}.
# Every column of a square 2r+1 across is read at 17 heights, so the cost grows with r squared; past
# 24 one search nears the 65,536 commands a function may run, and is refused.
#
# Storage keeps whatever radius was last set here: load.mcfunction's default applies only to a world
# that has none.
$scoreboard players set #r mtxrig $(r)
execute unless score #r mtxrig matches 1..24 run return run tellraw @a [{text:"[mtxrig] ",color:"gold"},{text:"radius must be 1 to 24; kept "},{storage:"mtxrig:search",nbt:"r"}]

# The square's side, 2r+1, which the search's macro cannot work out itself.
$data modify storage mtxrig:search r set value $(r)
scoreboard players operation #w mtxrig = #r mtxrig
scoreboard players operation #w mtxrig += #r mtxrig
scoreboard players add #w mtxrig 1
execute store result storage mtxrig:search w int 1 run scoreboard players get #w mtxrig
