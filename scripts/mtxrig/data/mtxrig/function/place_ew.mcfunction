# On track running east to west, and on curves. Yaw 0 lies along x, which is a minecart model's own
# resting axis. See place_ns for why the summon is at ~ ~ ~ rather than half a block on.
# Summoned already rolling: a car placed at rest has no heading to read, so the
# leading end gets decided off noise and a train can lay itself down backwards.
execute if block ~ ~1 ~ minecraft:green_wool run summon minecraft:furnace_minecart ~ ~ ~ {Tags:["mtx","mtx_new","mtx_fresh"],Motion:[0.4d,0.0d,0.0d],Rotation:[0.0f,0.0f],Fuel:32000s,PushX:1.0d,PushZ:0.0d}
execute if block ~ ~1 ~ minecraft:gray_wool run summon minecraft:chest_minecart ~ ~ ~ {Tags:["mtx","mtx_new","mtx_fresh"],Motion:[0.4d,0.0d,0.0d],Rotation:[0.0f,0.0f]}
execute if block ~ ~1 ~ minecraft:magenta_wool run summon minecraft:hopper_minecart ~ ~ ~ {Tags:["mtx","mtx_new","mtx_fresh"],Motion:[0.4d,0.0d,0.0d],Rotation:[0.0f,0.0f]}
execute if block ~ ~1 ~ minecraft:yellow_wool run summon minecraft:minecart ~ ~ ~ {Tags:["mtx","mtx_new","mtx_fresh"],Motion:[0.4d,0.0d,0.0d],Rotation:[0.0f,0.0f]}
function mtxrig:couple
