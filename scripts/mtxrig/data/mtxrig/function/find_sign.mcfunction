# One candidate sign block.
execute unless block ~ ~ ~ #minecraft:all_signs run return 0
data modify storage mtxrig:sign line0 set from block ~ ~ ~ front_text.messages[0]
execute if data storage mtxrig:sign {line0:"[mtxrig]"} run summon minecraft:marker ~ ~ ~ {Tags:["mtx_found"]}
