# One sign the search found. The group is not read here, only the header line, so one sweep reports
# every group at once.
data modify storage mtxrig:sign line0 set from block ~ ~ ~ front_text.messages[0]
execute if data storage mtxrig:sign {line0:"[mtxrig]"} run summon minecraft:marker ~ ~ ~ {Tags:["mtx_found"]}
