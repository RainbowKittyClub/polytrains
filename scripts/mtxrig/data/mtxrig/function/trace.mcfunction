# Follows the track a group's train is standing on and leaves a marker on every rail block of it,
# for measuring a circuit: how long a lap is, whether it climbs, and what is wired into it. Markers
# are tagged mtx_trace and stay until the next trace.
#
# Called as /function mtxrig:trace {group:"<name>"}.
kill @e[tag=mtx_trace]
# Summoned at the cart's own position, which sits inside the rail block below it - a block
# lower would start the trace off the track and only find it again through the slope check.
$execute as @e[tag=mtx,tag=mtx_$(group),limit=1] at @s run summon minecraft:marker ~ ~ ~ {Tags:["mtx_trace"]}
scoreboard players set #trace mtxrig 512
function mtxrig:trace_step
