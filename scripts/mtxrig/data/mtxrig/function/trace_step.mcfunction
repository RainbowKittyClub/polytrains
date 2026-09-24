# One generation of following, bounded so a trace that escapes onto the main line cannot run away.
scoreboard players remove #trace mtxrig 1
execute as @e[tag=mtx_trace,tag=!mtx_traced] at @s run function mtxrig:trace_from
execute if score #trace mtxrig matches 1.. if entity @e[tag=mtx_trace,tag=!mtx_traced] run function mtxrig:trace_step
