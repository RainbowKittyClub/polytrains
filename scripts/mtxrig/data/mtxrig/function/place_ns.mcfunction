# On track running north to south.
#
# Summoned at ~ ~ ~ and not ~0.5 ~ ~0.5: a whole-number coordinate given to `execute positioned`
# already resolves to the middle of that block, so adding half a block again lands the cart on the
# corner where four blocks meet. There is no rail there, so it never rails at all - it sits still,
# keeps whatever yaw it was given, and stays that way across a reconnect.
#
# Yaw 90 lies along z. A minecart's model runs east to west at yaw 0, so the yaw that matches a rail
# is a quarter turn from the direction of travel. Vanilla re-aligns a cart that is properly on its
# rail anyway, but the spawn yaw is what a cart with no recorded nose yet has its first one read
# from, so it is worth getting right rather than leaving for a correction.
# Summoned already rolling: a car placed at rest has no heading to read, so the
# leading end gets decided off noise and a train can lay itself down backwards.
execute if block ~ ~1 ~ minecraft:green_wool run summon minecraft:furnace_minecart ~ ~ ~ {Tags:["mtx","mtx_new","mtx_fresh"],Motion:[0.0d,0.0d,0.4d],Rotation:[90.0f,0.0f],Fuel:32000s,PushX:0.0d,PushZ:1.0d}
execute if block ~ ~1 ~ minecraft:gray_wool run summon minecraft:chest_minecart ~ ~ ~ {Tags:["mtx","mtx_new","mtx_fresh"],Motion:[0.0d,0.0d,0.4d],Rotation:[90.0f,0.0f]}
execute if block ~ ~1 ~ minecraft:magenta_wool run summon minecraft:hopper_minecart ~ ~ ~ {Tags:["mtx","mtx_new","mtx_fresh"],Motion:[0.0d,0.0d,0.4d],Rotation:[90.0f,0.0f]}
execute if block ~ ~1 ~ minecraft:yellow_wool run summon minecraft:minecart ~ ~ ~ {Tags:["mtx","mtx_new","mtx_fresh"],Motion:[0.0d,0.0d,0.4d],Rotation:[90.0f,0.0f]}
function mtxrig:couple
