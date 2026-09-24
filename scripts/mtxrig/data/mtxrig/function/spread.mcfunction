# One generation of growth outward from the signed cells, until every anchor has spread or the
# generation budget runs out. Bounded rather than run to exhaustion so a marker strip that somehow
# touches another cannot claim the whole yard.
scoreboard players remove #spread mtxrig 1
execute as @e[tag=mtx_anchor,tag=!mtx_spread] at @s run function mtxrig:spread_from
execute if score #spread mtxrig matches 1.. if entity @e[tag=mtx_anchor,tag=!mtx_spread] run function mtxrig:spread
